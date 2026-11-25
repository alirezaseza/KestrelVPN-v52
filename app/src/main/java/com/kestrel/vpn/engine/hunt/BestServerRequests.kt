package com.kestrel.vpn.engine.hunt

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Lets the Servers screen ask the Home screen to run the "Best server" benchmark and connect. */
@Singleton
class BestServerRequests @Inject constructor() {
    private val channel = Channel<Unit>(Channel.CONFLATED)
    val events: Flow<Unit> = channel.receiveAsFlow()
    fun request() {
        channel.trySend(Unit)
    }
}
