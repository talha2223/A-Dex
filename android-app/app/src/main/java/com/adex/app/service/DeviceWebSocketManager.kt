package com.adex.app.service

import android.util.Log
import com.adex.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface WebSocketEvents {
    fun onConnected()
    fun onDisconnected()
    fun onCommand(command: DeviceCommand)
}

// DeviceWebSocketManager handles bidirectional WSS messaging with reconnect backoff.
class DeviceWebSocketManager(
    private val settingsStore: SettingsStore,
    private val events: WebSocketEvents,
    private val scope: CoroutineScope,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectAttempt = 0

    fun connect(metadata: Map<String, String>) {
        reconnectJob?.cancel()
        val request = Request.Builder()
            .url(settingsStore.backendWsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                val hello = JSONObject().apply {
                    put("type", "device.hello")
                    put("deviceId", settingsStore.stableDeviceId)
                    put("deviceToken", settingsStore.deviceToken ?: "")
                    put("name", metadata["name"] ?: "A-Dex Android")
                    put("model", metadata["model"] ?: "unknown")
                    put("androidVersion", metadata["androidVersion"] ?: "unknown")
                    put("appVersion", metadata["appVersion"] ?: "unknown")
                }
                webSocket.send(hello.toString())
                startHeartbeat()
                events.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopHeartbeat()
                events.onDisconnected()
                scheduleReconnect(metadata)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("ADexWS", "WebSocket failure: ${t.message}")
                stopHeartbeat()
                events.onDisconnected()
                scheduleReconnect(metadata)
            }
        })
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        stopHeartbeat()
        webSocket?.close(1000, "Service stopping")
        webSocket = null
    }

    fun sendResult(result: CommandResult) {
        val message = JSONObject().apply {
            put("type", "device.result")
            put("commandId", result.commandId)
            put("status", result.status)
            put("data", JSONObject(result.data))
            result.errorCode?.let { put("errorCode", it) }
            result.errorMessage?.let { put("errorMessage", it) }
            result.mediaId?.let { put("mediaId", it) }
        }
        webSocket?.send(message.toString())
    }

    fun sendEvent(eventType: String, data: Map<String, Any?>) {
        val message = JSONObject().apply {
            put("type", "device.event")
            put("eventType", eventType)
            put("data", JSONObject(data))
        }
        webSocket?.send(message.toString())
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(25_000)
                webSocket?.send(JSONObject().put("type", "device.heartbeat").toString())
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun scheduleReconnect(metadata: Map<String, String>) {
        if (reconnectJob?.isActive == true) {
            return
        }

        reconnectJob = scope.launch(Dispatchers.IO) {
            reconnectAttempt += 1
            val delayMs = (1_000L * (1 shl reconnectAttempt.coerceAtMost(6))).coerceAtMost(30_000L)
            delay(delayMs)
            connect(metadata)
        }
    }

    private fun handleMessage(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (json.optString("type") != "device.command") {
            return
        }

        val payloadObj = json.optJSONObject("payload") ?: JSONObject()
        val payloadMap = mutableMapOf<String, Any?>()
        payloadObj.keys().forEach { key ->
            payloadMap[key] = payloadObj.get(key)
        }

        val command = DeviceCommand(
            commandId = json.optString("commandId"),
            requestId = json.optString("requestId"),
            commandName = json.optString("commandName"),
            payload = payloadMap,
            expiresAt = json.optLong("expiresAt", 0L)
        )
        events.onCommand(command)
    }
}
