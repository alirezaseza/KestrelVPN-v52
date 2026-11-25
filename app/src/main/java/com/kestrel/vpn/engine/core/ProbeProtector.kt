package com.kestrel.vpn.engine.core

/**
 * Lets probe cores (local SOCKS test instances) protect their sockets while the VPN is up,
 * so a server test does not travel through the currently active tunnel.
 * KestrelVpnService registers itself on tunnel start and clears on teardown.
 */
object ProbeProtector {
    @Volatile
    var protect: ((Int) -> Boolean)? = null
}
