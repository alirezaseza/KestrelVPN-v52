package com.kestrel.vpn.vpn

import com.kestrel.vpn.data.local.SplitMode

/**
 * Which apps go in/out of the tunnel. Pure so it can be unit-tested; the service only applies it.
 *
 * Android forbids mixing allowed and disallowed lists, so a plan carries at most one of them.
 * This app itself must always stay INSIDE the tunnel: the health probe and the exit-IP check
 * would otherwise measure the direct network and silently report a healthy tunnel.
 */
internal data class SplitTunnelPlan(
    val allowed: List<String> = emptyList(),
    val disallowed: List<String> = emptyList()
) {
    companion object {
        fun from(mode: SplitMode, packages: Set<String>, ownPackage: String): SplitTunnelPlan {
            val cleaned = packages.map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet()
            return when (mode) {
                SplitMode.OFF -> SplitTunnelPlan()
                SplitMode.EXCLUDE -> SplitTunnelPlan(disallowed = (cleaned - ownPackage).toList())
                SplitMode.INCLUDE ->
                    if (cleaned.isEmpty()) SplitTunnelPlan() else SplitTunnelPlan(allowed = (cleaned + ownPackage).toList())
            }
        }
    }
}
