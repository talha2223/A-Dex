package com.adex.app.service

// DeviceCommand mirrors backend `device.command` envelopes.
data class DeviceCommand(
    val commandId: String,
    val requestId: String,
    val commandName: String,
    val payload: Map<String, Any?>,
    val expiresAt: Long
)

// CommandResult maps to backend `device.result` WebSocket contract.
data class CommandResult(
    val commandId: String,
    val status: String,
    val data: Map<String, Any?> = emptyMap(),
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val mediaId: String? = null
)
