package com.kestrel.vpn.engine.hunt

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Lets the Servers screen ask the Home screen to test one picked server (and connect if it is healthy). */
@Singleton
class ServerTestRequests @Inject constructor() {
    private val channel = Channel<String>(Channel.CONFLATED)
    val events: Flow<String> = channel.receiveAsFlow()
    fun request(canonicalKey: String) {
        channel.trySend(canonicalKey)
    }
}
