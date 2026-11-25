package com.kestrel.vpn.engine.core

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Reflection handler behind libbox's `PlatformInterface`. Kept separate from [SingBoxProxyCore]
 * so it can be unit-tested against a fake interface without the native library.
 *
 * - `java.lang.Object` methods (equals/hashCode/toString) MUST behave normally: gomobile keeps
 *   proxies in identity-based reference tables, and `equals() == false` for the same instance breaks that.
 * - Methods this bridge does not know about get a type-appropriate default and are reported once
 *   through [onUnhandled], so a libbox upgrade that adds a method is visible in diagnostics.
 */
internal class PlatformBridge(
    private val tunFd: Int,
    private val protectSocket: ((Int) -> Boolean)?,
    private val network: PlatformNetwork? = null,
    private val onLog: (String) -> Unit = {},
    private val onUnhandled: (String) -> Unit = {}
) : InvocationHandler {

    private val reportedUnhandled = ConcurrentHashMap.newKeySet<String>()

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        if (method.declaringClass == Any::class.java) {
            return when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                else -> "KestrelPlatformBridge"
            }
        }
        return when (method.name) {
            "usePlatformAutoDetectInterfaceControl" -> protectSocket != null
            "autoDetectInterfaceControl" -> {
                val fd = (args?.firstOrNull() as? Number)?.toInt() ?: -1
                if (fd >= 0 && protectSocket != null && !protectSocket.invoke(fd)) {
                    throw IllegalStateException("VpnService.protect($fd) failed")
                }
                null
            }
            "openTun" -> {
                check(tunFd >= 0) { "openTun called without active TUN fd" }
                tunFd
            }
            "useProcFS" -> false
            // Android 11+ forbids netlink for apps, so sing-box cannot find the default network by itself:
            // the platform must report it (ConnectivityManager) and list the interfaces.
            "usePlatformDefaultInterfaceMonitor", "usePlatformInterfaceGetter" -> network != null
            "startDefaultInterfaceMonitor" -> {
                args?.firstOrNull()?.let { network?.start(it) }
                null
            }
            "closeDefaultInterfaceMonitor" -> {
                network?.stop()
                null
            }
            "getInterfaces" -> network?.interfaces()
            "writeLog" -> {
                // sing-box runtime errors (DNS failures, refused outbounds...) arrive here.
                // They used to be dropped, which made "connected but nothing loads" undiagnosable.
                (args?.firstOrNull() as? String)?.let(onLog)
                null
            }
            "clearDNSCache", "sendNotification" -> null
            "readWIFIState", "systemCertificates" -> null
            "underNetworkExtension", "includeAllNetworks" -> false
            "findConnectionOwner" -> -1
            "packageNameByUid" -> ""
            "uidByPackageName" -> -1
            else -> {
                if (reportedUnhandled.add(method.name)) onUnhandled(method.name)
                defaultReturnValue(method.returnType)
            }
        }
    }

    companion object {
        internal fun defaultReturnValue(returnType: Class<*>): Any? = when (returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            String::class.java -> ""
            else -> null
        }
    }
}
