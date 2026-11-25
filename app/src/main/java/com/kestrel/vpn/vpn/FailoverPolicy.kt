package com.kestrel.vpn.vpn

import com.kestrel.vpn.domain.model.ServerWithResult

/** Pure failover selection, extracted from the service so it can be unit-tested. */
object FailoverPolicy {
    /** First candidate (already sorted best-first) that has not failed in this session. */
    fun next(candidates: List<ServerWithResult>, failedKeys: Set<String>): ServerWithResult? =
    candidates.firstOrNull { it.config.canonicalKey !in failedKeys }
}
