package com.flowz.android.core

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class HubState {
    IDLE,
    CONNECTING,
    CONNECTED,
    FAILED
}

object CoreState {
    private val _status = MutableStateFlow(HubState.IDLE)
    val status: StateFlow<HubState> = _status

    private val _failureReason = MutableStateFlow("")
    val failureReason: StateFlow<String> = _failureReason

    fun setStatus(state: HubState, reason: String = "") {
        _status.value = state
        _failureReason.value = reason
    }

    fun startService(context: Context) {
        context.startService(
            Intent(context, FlowzVpnService::class.java).apply {
                action = FlowzVpnService.ACTION_START
            }
        )
    }

    fun stopService(context: Context) {
        context.startService(
            Intent(context, FlowzVpnService::class.java).apply {
                action = FlowzVpnService.ACTION_STOP
            }
        )
    }
}
