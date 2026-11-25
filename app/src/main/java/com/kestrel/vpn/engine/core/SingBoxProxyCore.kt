package com.kestrel.vpn.engine.core
import android.content.Context
import android.net.TrafficStats as AndroidTrafficStats
import android.os.Process
import com.kestrel.vpn.domain.core.CoreStartException
import com.kestrel.vpn.domain.core.CoreUnavailableException
import com.kestrel.vpn.domain.core.ProxyCore
import com.kestrel.vpn.domain.core.ProxyInstance
import com.kestrel.vpn.domain.core.TrafficStats
import com.kestrel.vpn.domain.model.ProxyConfig
import com.kestrel.vpn.util.DiagnosticsLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class SingBoxProxyCore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tlsPolicy: TlsPolicy
) : ProxyCore {
    override val coreName: String = "sing-box (libbox)"
    private val setupDone = AtomicBoolean(false)
    private val activeInstances = ConcurrentHashMap<String, ProxyInstance>()
    private val libboxClass: Class<*>? by lazy {
        runCatching { Class.forName(LIBBOX_CLASS) }.getOrNull()
    }
    override fun isAvailable(): Boolean = libboxClass != null
    override fun startLocalSocks(
        config: ProxyConfig,
        listenPort: Int,
        username: String,
        password: String,
        verbose: Boolean
    ): ProxyInstance {
        ensureSetup()
        val configJson = SingBoxConfigBuilder.buildLocalSocksConfig(
            config = ServerAddressResolver.resolve(context, config),
            listenPort = listenPort,
            allowInsecureTls = tlsPolicy.allowInsecure,
            username = username,
            password = password,
            verbose = verbose,
            fingerprintOverride = tlsPolicy.fingerprintOverride
        )
        return startServiceInternal(
            configJson = configJson,
            listenPort = listenPort,
            tunFd = -1,
            protectSocket = ProbeProtector.protect,
            network = if (ProbeProtector.protect != null) {
                AndroidPlatformNetwork(context) { DiagnosticsLog.add("net", it) }
            } else null
        )
    }
    override fun startTunTunnel(
        config: ProxyConfig,
        tunFd: Int,
        mtu: Int,
        protectSocket: (Int) -> Boolean,
        bypassIran: Boolean
    ): ProxyInstance {
        ensureSetup()
        SingBoxConfigBuilder.describeReality(config, tlsPolicy.fingerprintOverride)?.let {
            com.kestrel.vpn.util.DiagnosticsLog.add("vpn", it)
        }
        val configJson = SingBoxConfigBuilder.buildTunConfig(
            config = ServerAddressResolver.resolve(context, config),
            mtu = mtu,
            allowInsecureTls = tlsPolicy.allowInsecure,
            bypassIran = bypassIran,
            fingerprintOverride = tlsPolicy.fingerprintOverride
        )
        return startServiceInternal(
            configJson = configJson,
            listenPort = null,
            tunFd = tunFd,
            protectSocket = protectSocket,
            network = AndroidPlatformNetwork(context) { DiagnosticsLog.add("net", it) }
        )
    }
    override fun closeAllActive() {
        val snapshot = activeInstances.values.toList()
        activeInstances.clear()
        for (instance in snapshot) {
            runCatching { instance.close() }
        }
    }
    private val setupLock = Any()

    private fun ensureSetup() = synchronized(setupLock) {
        val clazz = libboxClass
            ?: throw CoreUnavailableException(
            "sing-box native core (app/libs/libbox.aar) is not installed."
        )
        val problems = LibboxContract.verify()
        if (problems.isNotEmpty()) {
            throw CoreStartException("libbox.aar is incompatible with this app: " + problems.joinToString("; "), null)
        }
        if (!setupDone.get()) {
            try {
                val baseDir = File(context.filesDir, "singbox").apply { mkdirs() }
                val workDir = File(context.filesDir, "singbox_work").apply { mkdirs() }
                val tempDir = File(context.cacheDir, "singbox_tmp").apply { mkdirs() }
                val setupOptionsClass = Class.forName(SETUP_OPTIONS_CLASS)
                val options = setupOptionsClass.getDeclaredConstructor().newInstance()
                setupOptionsClass.getMethod("setBasePath", String::class.java)
                    .invoke(options, baseDir.absolutePath)
                setupOptionsClass.getMethod("setWorkingPath", String::class.java)
                    .invoke(options, workDir.absolutePath)
                setupOptionsClass.getMethod("setTempPath", String::class.java)
                    .invoke(options, tempDir.absolutePath)
                clazz.getMethod("setup", setupOptionsClass).invoke(null, options)
                setupDone.set(true)
            } catch (t: Throwable) {
                setupDone.set(false)
                throw CoreStartException("Failed to initialize libbox runtime", t)
            }
        }
        Unit
    }
    private fun startServiceInternal(
        configJson: String,
        listenPort: Int?,
        tunFd: Int,
        protectSocket: ((Int) -> Boolean)?,
        network: PlatformNetwork?
    ): ProxyInstance {
        val clazz = libboxClass
            ?: throw CoreUnavailableException("libbox.aar is not installed.")
        val platformInterfaceClass = try {
            Class.forName(PLATFORM_INTERFACE_CLASS)
        } catch (t: Throwable) {
            throw CoreStartException("Missing libbox PlatformInterface class", t)
        }
        val platformProxy = Proxy.newProxyInstance(
            platformInterfaceClass.classLoader,
            arrayOf(platformInterfaceClass),
            PlatformBridge(
                tunFd,
                protectSocket,
                network = network,
                onLog = { line -> DiagnosticsLog.add("singbox", line.take(300)) },
                onUnhandled = { name -> DiagnosticsLog.add("core", "unhandled libbox platform method: $name") }
            )
        )
        val serviceObj = try {
            val newServiceMethod = clazz.getMethod(
                "newService",
                String::class.java,
                platformInterfaceClass
            )
            val svc = newServiceMethod.invoke(null, configJson, platformProxy)
                ?: throw CoreStartException("Libbox.newService returned null")
            svc.javaClass.getMethod("start").invoke(svc)
            svc
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            throw CoreStartException("Failed to start sing-box instance: ${cause.message}", cause)
        }
        val id = UUID.randomUUID().toString()
        val instance = SingBoxInstance(
            id = id,
            serviceObj = serviceObj,
            listenPort = listenPort,
            onClosed = { activeInstances.remove(id) }
        )
        activeInstances[id] = instance
        return instance
    }
    private class SingBoxInstance(
        private val id: String,
        private val serviceObj: Any,
        override val listenPort: Int?,
        private val onClosed: () -> Unit
    ) : ProxyInstance {
        private val running = AtomicBoolean(true)
        private val uid = Process.myUid()
        private val startRx = AndroidTrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
        private val startTx = AndroidTrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
        override fun isRunning(): Boolean = running.get()
        override fun readTrafficStats(): TrafficStats {
            val rx = (AndroidTrafficStats.getUidRxBytes(uid) - startRx).coerceAtLeast(0L)
            val tx = (AndroidTrafficStats.getUidTxBytes(uid) - startTx).coerceAtLeast(0L)
            return TrafficStats(rxBytes = rx, txBytes = tx)
        }
        override fun close() {
            if (running.compareAndSet(true, false)) {
                try {
                    serviceObj.javaClass.getMethod("close").invoke(serviceObj)
                } catch (_: Throwable) {
                } finally {
                    onClosed()
                }
            }
        }
    }
    companion object {
        private const val LIBBOX_CLASS = "io.nekohasekai.libbox.Libbox"
        private const val SETUP_OPTIONS_CLASS = "io.nekohasekai.libbox.SetupOptions"
        private const val PLATFORM_INTERFACE_CLASS = "io.nekohasekai.libbox.PlatformInterface"
    }
}
