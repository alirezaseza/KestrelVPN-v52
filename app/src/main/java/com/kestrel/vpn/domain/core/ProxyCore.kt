package com.kestrel.vpn.domain.core
import com.kestrel.vpn.domain.model.ProxyConfig
data class TrafficStats(
    val rxBytes: Long,
    val txBytes: Long
)
interface ProxyInstance : AutoCloseable {
    val listenPort: Int?
    fun isRunning(): Boolean
    fun readTrafficStats(): TrafficStats
    override fun close()
}
class CoreUnavailableException(message: String, cause: Throwable? = null) :
IllegalStateException(message, cause)
class CoreStartException(message: String, cause: Throwable? = null) :
RuntimeException(message, cause)
interface ProxyCore {
    val coreName: String
    fun isAvailable(): Boolean
    @Throws(CoreUnavailableException::class, CoreStartException::class)
    fun startLocalSocks(
        config: ProxyConfig,
        listenPort: Int,
        username: String,
        password: String,
        verbose: Boolean = false
    ): ProxyInstance
    @Throws(CoreUnavailableException::class, CoreStartException::class)
    fun startTunTunnel(
        config: ProxyConfig,
        tunFd: Int,
        mtu: Int,
        protectSocket: (Int) -> Boolean,
        bypassIran: Boolean = true
    ): ProxyInstance
    fun closeAllActive()
}
