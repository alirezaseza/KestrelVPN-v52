package com.kestrel.vpn.engine.hunt

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Lets Settings ask the Home screen to start Hunt Mode (it no longer has a button on Home). */
@Singleton
class HuntRequests @Inject constructor() {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events: SharedFlow<Unit> = _events.asSharedFlow()
    fun request() {
        _events.tryEmit(Unit)
    }
}
