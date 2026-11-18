package com.example.engine

import android.util.Log
import com.example.data.model.AgentAvailabilityStatus
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

data class BridgeSocketState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val serverUrl: String = "",
    val transportName: String = "socket.io",
    val museConnected: Boolean = false,
    val museStatusDetail: String = "Disconnected from Muse AI",
    val lastCheckedAtMillis: Long = 0L,
    val lastError: String? = null
)

/**
 * Persistent Socket.IO v4 client service managing the real-time connection between
 * the Muse Boardroom Android app and the Multi-Bot Bridge server (embedded or remote).
 */
class BridgeSocketService {

    private var socket: Socket? = null
    private var activeServerUrl: String = ""
    private var activeAuthToken: String = ""

    private val _state = MutableStateFlow(BridgeSocketState())
    val state: StateFlow<BridgeSocketState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<BridgeSseEventData>(extraBufferCapacity = 64)
    val events: SharedFlow<BridgeSseEventData> = _events.asSharedFlow()

    @Synchronized
    fun connect(serverUrl: String, authToken: String) {
        val normalizedUrl = MultiBotBridgeClient.normalizeBaseUrl(serverUrl)
        val trimmedToken = authToken.trim()

        if (normalizedUrl.isBlank()) {
            disconnect()
            return
        }

        val currentSocket = socket
        if (currentSocket != null &&
            normalizedUrl == activeServerUrl &&
            trimmedToken == activeAuthToken &&
            (currentSocket.connected() || _state.value.connecting)
        ) {
            return
        }

        disconnectInternal(resetMuseState = false)

        activeServerUrl = normalizedUrl
        activeAuthToken = trimmedToken
        _state.update {
            it.copy(
                connected = false,
                connecting = true,
                serverUrl = normalizedUrl,
                lastError = null
            )
        }

        try {
            val opts = IO.Options().apply {
                transports = arrayOf(WebSocket.NAME)
                upgrade = false
                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = 1_500L
                reconnectionDelayMax = 8_000L
                timeout = 10_000L
                forceNew = true
                if (trimmedToken.isNotEmpty()) {
                    auth = mapOf("token" to trimmedToken)
                    extraHeaders = mapOf("Authorization" to listOf("Bearer $trimmedToken"))
                }
            }

            val clientSocket = IO.socket(normalizedUrl, opts)
            attachListeners(clientSocket)
            socket = clientSocket
            clientSocket.connect()
        } catch (err: Exception) {
            Log.w(TAG, "Socket.IO initialization error for $normalizedUrl", err)
            _state.update {
                it.copy(
                    connected = false,
                    connecting = false,
                    lastError = err.localizedMessage ?: "Failed to start Socket.IO client"
                )
            }
        }
    }

    @Synchronized
    fun disconnect() {
        disconnectInternal(resetMuseState = true)
    }

    @Synchronized
    private fun disconnectInternal(resetMuseState: Boolean) {
        socket?.let { existing ->
            runCatching {
                existing.off()
                existing.disconnect()
                existing.close()
            }
        }
        socket = null
        activeServerUrl = ""
        activeAuthToken = ""
        _state.update { prev ->
            prev.copy(
                connected = false,
                connecting = false,
                serverUrl = "",
                museConnected = if (resetMuseState) false else prev.museConnected,
                museStatusDetail = if (resetMuseState) "Disconnected from Muse AI" else prev.museStatusDetail
            )
        }
    }

    fun refreshMuseConnection(): Boolean {
        val current = socket
        return if (current != null && current.connected()) {
            current.emit("muse:refresh", JSONObject().put("timestamp", System.currentTimeMillis()))
            current.emit("bridge:ping", JSONObject())
            true
        } else if (activeServerUrl.isNotBlank()) {
            val url = activeServerUrl
            val tok = activeAuthToken
            disconnectInternal(resetMuseState = false)
            connect(url, tok)
            false
        } else {
            false
        }
    }

    fun emitDispatch(
        conversationId: String,
        parentMessageId: String,
        prompt: String,
        targetBotIds: List<String>
    ): Boolean {
        val current = socket ?: return false
        if (!current.connected()) return false
        val payload = JSONObject()
            .put("conversationId", conversationId)
            .put("parentMessageId", parentMessageId)
            .put("prompt", prompt)
            .put("targetBotIds", JSONArray(targetBotIds))
        current.emit("bridge:dispatch", payload)
        return true
    }

    private fun attachListeners(clientSocket: Socket) {
        clientSocket.on(Socket.EVENT_CONNECT) {
            _state.update {
                it.copy(
                    connected = true,
                    connecting = false,
                    lastCheckedAtMillis = System.currentTimeMillis(),
                    lastError = null
                )
            }
        }

        clientSocket.on(Socket.EVENT_DISCONNECT) { args ->
            val reason = args.firstOrNull()?.toString().orEmpty()
            _state.update {
                it.copy(
                    connected = false,
                    connecting = false,
                    lastError = reason.takeIf { r -> r.isNotBlank() }
                )
            }
        }

        clientSocket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val errDetail = args.firstOrNull()?.toString() ?: "Socket.IO connection error"
            _state.update {
                it.copy(
                    connected = false,
                    connecting = false,
                    lastError = errDetail
                )
            }
        }

        clientSocket.on("bridge:ready") { args ->
            val raw = args.firstOrNull()?.toString() ?: return@on
            runCatching {
                val json = JSONObject(raw)
                val museReady = json.optBoolean("museConnected", false)
                val parsed = MultiBotBridgeClient.parseSseEvents("bridge:ready", raw)
                parsed.forEach { _events.tryEmit(it) }
                _state.update {
                    it.copy(
                        museConnected = museReady,
                        museStatusDetail = if (museReady) {
                            "Connected to Muse AI via Bridge Socket.IO"
                        } else {
                            "Bridge connected · Waiting for Muse AI session"
                        },
                        lastCheckedAtMillis = System.currentTimeMillis()
                    )
                }
            }
        }

        clientSocket.on("muse:status") { args ->
            val raw = args.firstOrNull()?.toString() ?: return@on
            runCatching {
                val json = JSONObject(raw)
                val connected = json.optBoolean("connected", false)
                val detail = json.optString("detail").ifBlank {
                    if (connected) "Connected to Muse AI" else "Disconnected from Muse AI"
                }
                _state.update {
                    it.copy(
                        museConnected = connected,
                        museStatusDetail = detail,
                        lastCheckedAtMillis = System.currentTimeMillis()
                    )
                }
            }
        }

        clientSocket.on("bridge:pong") { args ->
            val raw = args.firstOrNull()?.toString() ?: return@on
            runCatching {
                val json = JSONObject(raw)
                if (json.has("connected")) {
                    val connected = json.optBoolean("connected", false)
                    val detail = json.optString("detail").ifBlank {
                        if (connected) "Connected to Muse AI" else "Disconnected from Muse AI"
                    }
                    _state.update {
                        it.copy(
                            museConnected = connected,
                            museStatusDetail = detail,
                            lastCheckedAtMillis = System.currentTimeMillis()
                        )
                    }
                }
            }
        }

        listOf("bot:status", "task:delta", "task:completed", "task:failed", "task:cancelled", "message:created")
            .forEach { eventName ->
                clientSocket.on(eventName) { args ->
                    val raw = args.firstOrNull()?.toString() ?: return@on
                    val parsed = MultiBotBridgeClient.parseSseEvents(eventName, raw)
                    parsed.forEach { _events.tryEmit(it) }
                }
            }
    }

    companion object {
        private const val TAG = "BridgeSocketService"
    }
}
