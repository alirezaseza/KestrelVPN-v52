package com.kestrel.vpn.engine.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.system.OsConstants
import java.io.File
import java.lang.reflect.Proxy
import java.net.NetworkInterface

/**
 * What libbox needs from Android to know which physical network is the default one.
 *
 * Without this, sing-box (on Android 11+) cannot enumerate network interfaces itself and every
 * outbound connection fails with "no available network interface" - the tunnel looks connected
 * but nothing loads.
 */
internal interface PlatformNetwork {
    /** [listener] is libbox's InterfaceUpdateListener; push the current default network into it. */
    fun start(listener: Any)
    fun stop()
    /** A libbox NetworkInterfaceIterator describing the physical networks, or null if unavailable. */
    fun interfaces(): Any?
}

internal class AndroidPlatformNetwork(
    context: Context,
    private val log: (String) -> Unit = {}
) : PlatformNetwork {

    private val cm: ConnectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var listener: Any? = null
    private var lastKey: String? = null

    private class Candidate(
        val network: Network,
        val caps: NetworkCapabilities,
        val props: LinkProperties,
        val name: String
    )

    override fun start(listener: Any) {
        this.listener = listener
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish(false)
            override fun onLost(network: Network) = publish(false)
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                publish(false)
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                publish(false)
        }
        callback = cb
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, cb)
        publish(true)
    }

    override fun stop() {
        listener = null
        val cb = callback
        callback = null
        if (cb != null) runCatching { cm.unregisterNetworkCallback(cb) }
    }

    @Suppress("DEPRECATION")
    private fun candidates(): List<Candidate> = cm.allNetworks.mapNotNull { network ->
        val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
        val props = cm.getLinkProperties(network) ?: return@mapNotNull null
        val name = props.interfaceName ?: return@mapNotNull null
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
        Candidate(network, caps, props, name)
    }

    private fun score(c: Candidate): Int {
        var s = 0
        if (c.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) s += 100
        if (c.caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) s += 30
        else if (c.caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) s += 20
        else if (c.caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) s += 10
        return s
    }

    private fun indexOf(name: String): Int =
        runCatching { NetworkInterface.getByName(name)?.index }.getOrNull()
            ?: runCatching { File("/sys/class/net/$name/ifindex").readText().trim().toInt() }.getOrNull()
            ?: -1

    @Synchronized
    private fun publish(force: Boolean) {
        val target = listener ?: return
        val best = runCatching { candidates().maxByOrNull { score(it) } }.getOrNull()
        val name = best?.name ?: ""
        val index = if (best != null) indexOf(best.name) else -1
        val expensive = best != null && !best.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val key = "$name/$index/$expensive"
        if (!force && key == lastKey) return
        lastKey = key
        log("default network -> ${if (name.isEmpty()) "none" else "$name idx=$index"}")
        val method = target.javaClass.methods.firstOrNull { it.name == "updateDefaultInterface" }
        if (method == null) {
            log("InterfaceUpdateListener.updateDefaultInterface not found")
            return
        }
        val all = listOf<Any>(name, index, expensive, false)
        val args = method.parameterTypes.mapIndexed { i, type -> coerce(type, all.getOrNull(i)) }
        runCatching { method.invoke(target, *args.toTypedArray()) }
            .onFailure { log("updateDefaultInterface failed: ${it.cause?.message ?: it.message}") }
    }

    override fun interfaces(): Any? {
        return runCatching {
            val iteratorClass = Class.forName(NETWORK_INTERFACE_ITERATOR)
            val itemClass = Class.forName(NETWORK_INTERFACE)
            val items = candidates().mapNotNull { c -> buildInterface(itemClass, c) }
            var i = 0
            Proxy.newProxyInstance(iteratorClass.classLoader, arrayOf(iteratorClass)) { proxy, method, args ->
                when (method.name) {
                    "hasNext" -> i < items.size
                    "next" -> items[i++]
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "KestrelInterfaceIterator"
                    else -> PlatformBridge.defaultReturnValue(method.returnType)
                }
            }
        }.onFailure { log("getInterfaces failed: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()
    }

    private fun buildInterface(itemClass: Class<*>, c: Candidate): Any? = runCatching {
        val ni = NetworkInterface.getByName(c.name)
        val item = itemClass.getDeclaredConstructor().newInstance()
        set(item, "Name", c.name)
        set(item, "Index", ni?.index ?: indexOf(c.name))
        set(item, "MTU", runCatching { ni?.mtu }.getOrNull() ?: 1500)
        val type = when {
            c.caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> libboxConst("InterfaceTypeWIFI", 0)
            c.caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> libboxConst("InterfaceTypeCellular", 1)
            c.caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> libboxConst("InterfaceTypeEthernet", 2)
            else -> libboxConst("InterfaceTypeOther", 3)
        }
        set(item, "Type", type)
        var flags = 0
        runCatching {
            if (ni != null && ni.isUp) flags = flags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            if (ni != null && ni.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
            if (ni != null && ni.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
            else flags = flags or OsConstants.IFF_BROADCAST
            if (ni != null && ni.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
        }
        set(item, "Flags", flags)
        set(item, "Metered", !c.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        val addresses = ni?.interfaceAddresses.orEmpty().mapNotNull {
            val host = it.address?.hostAddress?.substringBefore('%') ?: return@mapNotNull null
            "$host/${it.networkPrefixLength}"
        }
        set(item, "Addresses", stringIterator(addresses))
        val dns = c.props.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') }
        set(item, "DNSServer", stringIterator(dns))
        item
    }.onFailure { log("describe ${c.name} failed: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()

    private fun stringIterator(values: List<String>): Any? {
        val cls = runCatching { Class.forName(STRING_ITERATOR) }.getOrNull() ?: return null
        var i = 0
        return Proxy.newProxyInstance(cls.classLoader, arrayOf(cls)) { proxy, method, args ->
            when (method.name) {
                "len" -> coerce(method.returnType, values.size)
                "hasNext" -> i < values.size
                "next" -> values[i++]
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "KestrelStringIterator"
                else -> PlatformBridge.defaultReturnValue(method.returnType)
            }
        }
    }

    /** gomobile property setters differ in capitalisation between versions (setMTU / setMtu), so match loosely. */
    private fun set(target: Any, property: String, value: Any?) {
        if (value == null) return
        val method = target.javaClass.methods.firstOrNull {
            it.name.equals("set$property", ignoreCase = true) && it.parameterCount == 1
        } ?: return
        method.invoke(target, coerce(method.parameterTypes[0], value))
    }

    private fun coerce(type: Class<*>, value: Any?): Any? = when {
        value is Number && (type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType) -> value.toInt()
        value is Number && (type == Long::class.javaPrimitiveType || type == Long::class.javaObjectType) -> value.toLong()
        else -> value
    }

    private fun libboxConst(name: String, fallback: Int): Int =
        runCatching { (Class.forName(LIBBOX).getField(name).get(null) as Number).toInt() }.getOrDefault(fallback)

    private companion object {
        const val LIBBOX = "io.nekohasekai.libbox.Libbox"
        const val NETWORK_INTERFACE = "io.nekohasekai.libbox.NetworkInterface"
        const val NETWORK_INTERFACE_ITERATOR = "io.nekohasekai.libbox.NetworkInterfaceIterator"
        const val STRING_ITERATOR = "io.nekohasekai.libbox.StringIterator"
    }
}
