package com.example.engine

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.data.model.AgentAvailabilityStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "EmbeddedBridgeServer"
private const val WS_MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

interface EmbeddedBridgeExecutor {
    suspend fun checkBotSession(bot: EmbeddedBridgeBotRecord): Pair<AgentAvailabilityStatus, String>
    suspend fun executePrompt(
        bot: EmbeddedBridgeBotRecord,
        prompt: String,
        onPartialDelta: suspend (String) -> Unit
    ): Result<String>
}

data class EmbeddedBridgeBotRecord(
    val botId: String,
    val displayName: String,
    val provider: String = "muse-web",
    val targetUrl: String = "https://muse.ai",
    val enabled: Boolean = true,
    val selectedForBroadcast: Boolean = true,
    val status: AgentAvailabilityStatus = AgentAvailabilityStatus.NEEDS_AUTH,
    val reason: String = "Sign in to Muse AI to connect this bot",
    val tokenPreview: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("botId", botId)
        .put("displayName", displayName)
        .put("provider", provider)
        .put("targetUrl", targetUrl)
        .put("enabled", enabled)
        .put("selectedForBroadcast", selectedForBroadcast)
        .put("tokenPreview", tokenPreview)
        .put(
            "health",
            JSONObject()
                .put("status", status.toRemoteKey())
                .put("reason", reason)
        )
}

data class EmbeddedBridgeServerStatus(
    val running: Boolean = false,
    val port: Int = 0,
    val baseUrl: String = "",
    val botCount: Int = 0,
    val tokenCount: Int = 0,
    val socketClientCount: Int = 0,
    val museConnected: Boolean = false
)

private fun AgentAvailabilityStatus.toRemoteKey(): String = when (this) {
    AgentAvailabilityStatus.ONLINE -> "online"
    AgentAvailabilityStatus.OFFLINE -> "offline"
    AgentAvailabilityStatus.BUSY -> "busy"
    AgentAvailabilityStatus.NEEDS_AUTH -> "needs_auth"
    AgentAvailabilityStatus.ERROR -> "error"
}

class EmbeddedBridgeServer(
    context: Context,
    private val preferredPort: Int = 8787,
    private val executor: EmbeddedBridgeExecutor
) {
    private val prefs = context.getSharedPreferences("muse_embedded_bridge_server", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    private val bots = ConcurrentHashMap<String, EmbeddedBridgeBotRecord>()
    private val tokens = ConcurrentHashMap<String, String>() // token -> botId (loaded from encrypted Room DB)
    private val activeTasks = ConcurrentHashMap<String, Job>()
    private val sseSubscribers = CopyOnWriteArrayList<OutputStream>()
    private val socketIoSessions = ConcurrentHashMap<String, SocketIoSession>()

    private var serverScope: CoroutineScope? = null
    private var acceptJob: Job? = null
    private var serverSocket: ServerSocket? = null

    private val _status = MutableStateFlow(EmbeddedBridgeServerStatus())
    val status: StateFlow<EmbeddedBridgeServerStatus> = _status.asStateFlow()

    private class SocketIoSession(
        val sid: String,
        @Volatile var namespaceSid: String? = null,
        @Volatile var authenticated: Boolean = false,
        @Volatile var wsOutput: OutputStream? = null,
        @Volatile var wsSocket: Socket? = null,
        val pollingQueue: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
    )

    init {
        loadPersistedState()
    }

    @Synchronized
    fun ensureStarted(): EmbeddedBridgeServerStatus {
        val currentSocket = serverSocket
        if (currentSocket != null && !currentSocket.isClosed && _status.value.running) {
            return _status.value
        }

        val boundSocket = runCatching {
            ServerSocket(preferredPort, 50, InetAddress.getByName("127.0.0.1"))
        }.getOrElse {
            ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        }

        serverSocket = boundSocket
        val actualPort = boundSocket.localPort
        val baseUrl = "http://127.0.0.1:$actualPort"

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverScope = scope
        acceptJob = scope.launch {
            while (!boundSocket.isClosed) {
                try {
                    val client = boundSocket.accept()
                    launch { handleClient(client) }
                } catch (_: Exception) {
                    break
                }
            }
        }

        val snapshot = EmbeddedBridgeServerStatus(
            running = true,
            port = actualPort,
            baseUrl = baseUrl,
            botCount = bots.size,
            tokenCount = tokens.size,
            socketClientCount = socketIoSessions.size,
            museConnected = computeMuseConnected()
        )
        _status.value = snapshot
        return snapshot
    }

    @Synchronized
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        activeTasks.values.forEach { it.cancel() }
        activeTasks.clear()
        sseSubscribers.forEach { out -> runCatching { out.close() } }
        sseSubscribers.clear()
        socketIoSessions.values.forEach { session ->
            runCatching { session.wsSocket?.close() }
        }
        socketIoSessions.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverScope?.coroutineContext?.get(Job)?.cancel()
        serverScope = null
        _status.value = _status.value.copy(running = false, port = 0, baseUrl = "", socketClientCount = 0)
    }

    fun getPrimaryToken(): String? = tokens.keys.firstOrNull()

    fun getBot(botId: String): EmbeddedBridgeBotRecord? = bots[botId]

    fun listBots(): List<EmbeddedBridgeBotRecord> = bots.values.sortedBy { it.createdAt }

    fun restoreValidTokens(tokenPairs: List<Pair<String, String>>) {
        tokens.clear()
        tokenPairs.forEach { (rawToken, botId) ->
            if (rawToken.isNotBlank() && botId.isNotBlank()) {
                tokens[rawToken] = botId
            }
        }
        // Clean up any legacy plaintext tokens from SharedPreferences once Room owns them
        if (prefs.contains("tokens_json")) {
            prefs.edit().remove("tokens_json").apply()
        }
        refreshStatusMetrics()
    }

    fun consumeLegacySharedPrefTokens(): Map<String, String> {
        val raw = prefs.getString("tokens_json", null) ?: return emptyMap()
        val migrated = linkedMapOf<String, String>()
        runCatching {
            val obj = JSONObject(raw)
            obj.keys().forEach { tok ->
                val botId = obj.optString(tok, "global")
                if (tok.isNotBlank()) {
                    migrated[tok] = botId
                }
            }
        }
        prefs.edit().remove("tokens_json").apply()
        return migrated
    }

    fun registerExternalToken(botId: String, rawToken: String) {
        if (rawToken.isBlank() || botId.isBlank()) return
        tokens[rawToken] = botId
        refreshStatusMetrics()
    }

    fun revokeBotTokens(botId: String) {
        tokens.entries.removeIf { it.value == botId }
        bots.remove(botId)
        persistState()
        refreshStatusMetrics()
        emitMuseStatusEvent()
    }

    fun markBotBrowserReady(botId: String, ready: Boolean, needsLogin: Boolean = false) {
        val existing = bots[botId] ?: return
        val nextStatus = when {
            !existing.enabled -> AgentAvailabilityStatus.OFFLINE
            ready -> AgentAvailabilityStatus.ONLINE
            needsLogin -> AgentAvailabilityStatus.NEEDS_AUTH
            else -> existing.status
        }
        val nextReason = when (nextStatus) {
            AgentAvailabilityStatus.ONLINE -> "Connected to ${existing.targetUrl}"
            AgentAvailabilityStatus.NEEDS_AUTH -> "Sign in required on ${existing.targetUrl}"
            else -> existing.reason
        }
        val updated = existing.copy(status = nextStatus, reason = nextReason)
        bots[botId] = updated
        persistState()
        refreshStatusMetrics()
        emitBotStatus(updated)
        emitMuseStatusEvent()
    }

    fun rotateTokenForBot(botId: String): String {
        val existing = bots[botId]
        tokens.entries.removeIf { it.value == botId }
        val token = generateSecureToken()
        tokens[token] = botId
        if (existing != null) {
            bots[botId] = existing.copy(tokenPreview = previewToken(token))
        }
        persistState()
        refreshStatusMetrics()
        return token
    }

    private suspend fun handleClient(socket: Socket) {
        var keepOpen = false
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val req = readHttpRequest(input) ?: return

            val pathOnly = req.path.substringBefore('?')
            val queryParams = parseQueryParams(req.path)
            val authHeader = req.headers["authorization"].orEmpty()
            val bearer = authHeader.removePrefix("Bearer ").removePrefix("bearer ").trim()
                .ifBlank { queryParams["token"].orEmpty().trim() }

            // Socket.IO / Engine.IO v4 endpoint
            if (pathOnly == "/socket.io/" || pathOnly == "/socket.io") {
                keepOpen = handleSocketIoHttpOrWebSocket(socket, input, output, req, queryParams, bearer)
                return
            }

            // Health check is local diagnostic liveness endpoint
            if (pathOnly == "/api/bridge/health" && req.method == "GET") {
                val museConnected = computeMuseConnected()
                val health = JSONObject()
                    .put("ok", true)
                    .put("mode", "embedded")
                    .put("port", _status.value.port)
                    .put("baseUrl", _status.value.baseUrl)
                    .put("botCount", bots.size)
                    .put("tokenCount", tokens.size)
                    .put("sseClients", sseSubscribers.size)
                    .put("socketClients", socketIoSessions.size)
                    .put("museConnected", museConnected)
                    .put("providers", JSONArray(listOf("muse-web", "gemini", "openai", "custom-http")))
                writeJsonResponse(output, 200, "OK", health)
                return
            }

            val isBootstrapCreate = req.method == "POST" && (pathOnly == "/api/bridge/bots" || pathOnly == "/api/bridge/tokens")
            if (!isBootstrapCreate && tokens.isNotEmpty() && !tokens.containsKey(bearer)) {
                writeJsonResponse(
                    output,
                    401,
                    "Unauthorized",
                    JSONObject().put("error", "Valid Bridge Bearer token required.")
                )
                return
            }

            when {
                req.method == "POST" && pathOnly == "/api/bridge/tokens" -> {
                    val body = parseJsonObject(req.body)
                    val targetBotId = body.optString("botId").ifBlank { "global" }
                    val token = generateSecureToken()
                    tokens[token] = targetBotId
                    persistState()
                    refreshStatusMetrics()
                    writeJsonResponse(
                        output,
                        201,
                        "Created",
                        JSONObject()
                            .put("token", token)
                            .put("tokenPreview", previewToken(token))
                            .put("serverUrl", _status.value.baseUrl)
                    )
                }

                req.method == "GET" && pathOnly == "/api/bridge/bots" -> {
                    val arr = JSONArray()
                    listBots().forEach { arr.put(it.toJson()) }
                    writeJsonResponse(output, 200, "OK", JSONObject().put("bots", arr))
                }

                req.method == "POST" && pathOnly == "/api/bridge/bots" -> {
                    val body = parseJsonObject(req.body)
                    val idx = bots.size + 1
                    val requestedId = body.optString("botId").trim()
                    val botId = if (requestedId.isNotBlank() && !bots.containsKey(requestedId)) {
                        requestedId
                    } else {
                        "bot-${String.format("%02d", idx)}-${UUID.randomUUID().toString().take(4)}"
                    }
                    val displayName = body.optString("displayName").trim()
                        .ifBlank { "Muse Bot ${String.format("%02d", idx)}" }
                    val provider = body.optString("provider").trim().ifBlank { "muse-web" }
                    val targetUrl = body.optString("targetUrl").trim().ifBlank { "https://muse.ai" }
                    val mintedToken = generateSecureToken()
                    tokens[mintedToken] = botId

                    val initialBot = EmbeddedBridgeBotRecord(
                        botId = botId,
                        displayName = displayName,
                        provider = provider,
                        targetUrl = targetUrl,
                        enabled = true,
                        selectedForBroadcast = true,
                        status = AgentAvailabilityStatus.NEEDS_AUTH,
                        reason = "Bridge connected · Ready for Muse AI sign-in",
                        tokenPreview = previewToken(mintedToken)
                    )
                    bots[botId] = initialBot

                    val (checkedStatus, checkedReason) = runCatching {
                        executor.checkBotSession(initialBot)
                    }.getOrDefault(AgentAvailabilityStatus.NEEDS_AUTH to "Bridge connected · Sign in to Muse AI")

                    val settledBot = initialBot.copy(status = checkedStatus, reason = checkedReason)
                    bots[botId] = settledBot
                    persistState()
                    refreshStatusMetrics()
                    emitBotStatus(settledBot)
                    emitMuseStatusEvent()

                    writeJsonResponse(
                        output,
                        201,
                        "Created",
                        JSONObject()
                            .put("ok", true)
                            .put("token", mintedToken)
                            .put("serverUrl", _status.value.baseUrl)
                            .put("bot", settledBot.toJson())
                    )
                }

                req.method == "POST" && pathOnly.startsWith("/api/bridge/bots/") && pathOnly.endsWith("/enable") -> {
                    val botId = extractBotId(pathOnly, "/enable")
                    val body = parseJsonObject(req.body)
                    val enabled = body.optBoolean("enabled", true)
                    val bot = bots[botId]
                    if (bot == null) {
                        writeJsonResponse(output, 404, "Not Found", JSONObject().put("error", "Bot $botId not found"))
                        return
                    }
                    val updated = bot.copy(
                        enabled = enabled,
                        status = if (!enabled) AgentAvailabilityStatus.OFFLINE else {
                            if (bot.status == AgentAvailabilityStatus.OFFLINE) AgentAvailabilityStatus.ONLINE else bot.status
                        },
                        reason = if (!enabled) "Disabled" else "Enabled"
                    )
                    bots[botId] = updated
                    persistState()
                    refreshStatusMetrics()
                    emitBotStatus(updated)
                    emitMuseStatusEvent()
                    writeJsonResponse(output, 200, "OK", JSONObject().put("bot", updated.toJson()))
                }

                req.method == "POST" && pathOnly.startsWith("/api/bridge/bots/") && pathOnly.endsWith("/select") -> {
                    val botId = extractBotId(pathOnly, "/select")
                    val body = parseJsonObject(req.body)
                    val selected = body.optBoolean("selected", true)
                    val bot = bots[botId]
                    if (bot == null) {
                        writeJsonResponse(output, 404, "Not Found", JSONObject().put("error", "Bot $botId not found"))
                        return
                    }
                    val updated = bot.copy(selectedForBroadcast = selected)
                    bots[botId] = updated
                    persistState()
                    writeJsonResponse(output, 200, "OK", JSONObject().put("bot", updated.toJson()))
                }

                req.method == "POST" && pathOnly.startsWith("/api/bridge/bots/") && pathOnly.endsWith("/init") -> {
                    val botId = extractBotId(pathOnly, "/init")
                    val bot = bots[botId]
                    if (bot == null) {
                        writeJsonResponse(output, 404, "Not Found", JSONObject().put("error", "Bot $botId not found"))
                        return
                    }
                    val (nextStatus, message) = runCatching {
                        executor.checkBotSession(bot)
                    }.getOrElse { err ->
                        AgentAvailabilityStatus.ERROR to (err.localizedMessage ?: "Session check failed")
                    }
                    val updated = bot.copy(status = nextStatus, reason = message)
                    bots[botId] = updated
                    persistState()
                    refreshStatusMetrics()
                    emitBotStatus(updated)
                    emitMuseStatusEvent()
                    writeJsonResponse(
                        output,
                        200,
                        "OK",
                        JSONObject().put(
                            "init",
                            JSONObject()
                                .put("botId", botId)
                                .put("status", nextStatus.toRemoteKey())
                                .put("message", message)
                        )
                    )
                }

                req.method == "POST" && pathOnly == "/api/bridge/dispatch" -> {
                    val body = parseJsonObject(req.body)
                    val responseJson = executeBridgeDispatch(body)
                    persistState()
                    writeJsonResponse(output, 200, "OK", responseJson)
                }

                req.method == "POST" && pathOnly.startsWith("/api/bridge/tasks/") && pathOnly.endsWith("/cancel") -> {
                    val taskId = pathOnly.removePrefix("/api/bridge/tasks/").removeSuffix("/cancel")
                    activeTasks.remove(taskId)?.cancel()
                    writeJsonResponse(output, 200, "OK", JSONObject().put("cancelled", true))
                }

                req.method == "GET" && pathOnly == "/api/bridge/events" -> {
                    keepOpen = true
                    val headers = (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/event-stream; charset=utf-8\r\n" +
                            "Cache-Control: no-cache\r\n" +
                            "Connection: keep-alive\r\n\r\n"
                        ).toByteArray(StandardCharsets.UTF_8)
                    output.write(headers)

                    val arr = JSONArray()
                    listBots().forEach { arr.put(it.toJson()) }
                    val readyFrame = formatSseFrame(
                        "bridge:ready",
                        JSONObject()
                            .put("bots", arr)
                            .put("museConnected", computeMuseConnected())
                    )
                    output.write(readyFrame)
                    output.flush()

                    sseSubscribers.add(output)
                    try {
                        while (!socket.isClosed && _status.value.running) {
                            delay(15_000)
                            synchronized(output) {
                                output.write(": keepalive\n\n".toByteArray(StandardCharsets.UTF_8))
                                output.flush()
                            }
                        }
                    } catch (_: Exception) {
                        // Client disconnected
                    } finally {
                        sseSubscribers.remove(output)
                        runCatching { socket.close() }
                    }
                }

                else -> {
                    writeJsonResponse(
                        output,
                        404,
                        "Not Found",
                        JSONObject().put("error", "Unknown endpoint: ${req.method} $pathOnly")
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Bridge request error", e)
        } finally {
            if (!keepOpen) {
                runCatching { socket.close() }
            }
        }
    }

    private suspend fun executeBridgeDispatch(body: JSONObject): JSONObject {
        val batchId = "batch_${UUID.randomUUID().toString().take(8)}"
        val conversationId = body.optString("conversationId").ifBlank { "boardroom-main" }
        val parentMessageId = body.optString("parentMessageId").ifBlank { "msg_${System.currentTimeMillis()}" }
        val prompt = body.optString("prompt")
        val targetArr = body.optJSONArray("targetBotIds") ?: JSONArray()
        val requestedIds = List(targetArr.length()) { i -> targetArr.optString(i) }
            .filter { it.isNotBlank() }
        val targetBots = if (requestedIds.isEmpty()) {
            listBots().filter { it.enabled && it.selectedForBroadcast }
        } else {
            requestedIds.mapNotNull { bots[it] }
        }

        val tasksJson = JSONArray()
        val responsesJson = JSONArray()

        coroutineScope {
            val results = targetBots.map { bot ->
                async {
                    val taskId = "task_${UUID.randomUUID().toString().take(8)}"
                    val busyBot = bot.copy(status = AgentAvailabilityStatus.BUSY, reason = "Processing...")
                    bots[bot.botId] = busyBot
                    emitBotStatus(busyBot)

                    val job = launch { }
                    activeTasks[taskId] = job

                    val execResult = try {
                        executor.executePrompt(bot, prompt) { partial ->
                            emitBridgeEvent(
                                "task:delta",
                                JSONObject()
                                    .put("taskId", taskId)
                                    .put("dispatchBatchId", batchId)
                                    .put("parentMessageId", parentMessageId)
                                    .put("botId", bot.botId)
                                    .put("fullTextSoFar", partial)
                            )
                        }
                    } finally {
                        activeTasks.remove(taskId)
                    }

                    execResult.fold(
                        onSuccess = { text ->
                            val isNeedsAuth = text.contains("not signed in", ignoreCase = true) ||
                                text.contains("needs login", ignoreCase = true)
                            val isErrorReply = text.contains("timed out", ignoreCase = true) ||
                                text.contains("error:**", ignoreCase = true)
                            if (isNeedsAuth) {
                                val authBot = bot.copy(
                                    status = AgentAvailabilityStatus.NEEDS_AUTH,
                                    reason = "Sign in required on ${bot.targetUrl}"
                                )
                                bots[bot.botId] = authBot
                                emitBotStatus(authBot)
                                emitMuseStatusEvent()
                                emitBridgeEvent(
                                    "task:failed",
                                    JSONObject()
                                        .put("taskId", taskId)
                                        .put("botId", bot.botId)
                                        .put("status", "needs_auth")
                                        .put("errorCode", "NEEDS_AUTH")
                                        .put("errorMessage", text)
                                )
                                Triple(
                                    JSONObject()
                                        .put("taskId", taskId)
                                        .put("dispatchBatchId", batchId)
                                        .put("botId", bot.botId)
                                        .put("provider", bot.provider)
                                        .put("status", "needs_auth")
                                        .put("errorCode", "NEEDS_AUTH")
                                        .put("errorMessage", text),
                                    null,
                                    Unit
                                )
                            } else {
                                val msgId = "msg_${UUID.randomUUID().toString().take(8)}"
                                val readyBot = bot.copy(
                                    status = if (isErrorReply) AgentAvailabilityStatus.ERROR else AgentAvailabilityStatus.ONLINE,
                                    reason = if (isErrorReply) text.take(100) else "Connected to ${bot.targetUrl}"
                                )
                                bots[bot.botId] = readyBot
                                emitBotStatus(readyBot)
                                emitMuseStatusEvent()

                                emitBridgeEvent(
                                    "task:completed",
                                    JSONObject()
                                        .put("taskId", taskId)
                                        .put("botId", bot.botId)
                                        .put("status", "completed")
                                )
                                emitBridgeEvent(
                                    "message:created",
                                    JSONObject()
                                        .put("id", msgId)
                                        .put("authorBotId", bot.botId)
                                        .put("authorName", bot.displayName)
                                        .put("provider", bot.provider)
                                        .put("parentMessageId", parentMessageId)
                                        .put("deliveryTaskId", taskId)
                                        .put("dispatchBatchId", batchId)
                                        .put("content", text)
                                )

                                val taskObj = JSONObject()
                                    .put("taskId", taskId)
                                    .put("dispatchBatchId", batchId)
                                    .put("botId", bot.botId)
                                    .put("provider", bot.provider)
                                    .put("status", "completed")
                                    .put("responseMessageId", msgId)
                                val respObj = JSONObject()
                                    .put("id", msgId)
                                    .put("deliveryTaskId", taskId)
                                    .put("content", text)
                                Triple(taskObj, respObj, Unit)
                            }
                        },
                        onFailure = { err ->
                            val msg = err.localizedMessage ?: "Bridge execution failed"
                            val errBot = bot.copy(status = AgentAvailabilityStatus.ERROR, reason = msg)
                            bots[bot.botId] = errBot
                            emitBotStatus(errBot)
                            emitMuseStatusEvent()
                            emitBridgeEvent(
                                "task:failed",
                                JSONObject()
                                    .put("taskId", taskId)
                                    .put("botId", bot.botId)
                                    .put("status", "failed")
                                    .put("errorCode", "EXECUTION_ERROR")
                                    .put("errorMessage", msg)
                            )
                            Triple(
                                JSONObject()
                                    .put("taskId", taskId)
                                    .put("dispatchBatchId", batchId)
                                    .put("botId", bot.botId)
                                    .put("provider", bot.provider)
                                    .put("status", "failed")
                                    .put("errorCode", "EXECUTION_ERROR")
                                    .put("errorMessage", msg),
                                null,
                                Unit
                            )
                        }
                    )
                }
            }.awaitAll()

            results.forEach { (taskObj, respObj, _) ->
                tasksJson.put(taskObj)
                if (respObj != null) responsesJson.put(respObj)
            }
        }

        return JSONObject()
            .put("dispatchBatchId", batchId)
            .put("conversationId", conversationId)
            .put("parentMessageId", parentMessageId)
            .put("tasks", tasksJson)
            .put("responses", responsesJson)
    }

    /**
     * Implements Engine.IO v4 + Socket.IO v4 over WebSocket (RFC 6455) and HTTP long-polling
     * so `io.socket:socket.io-client` maintains a real-time bidirectional session.
     */
    private suspend fun handleSocketIoHttpOrWebSocket(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        req: RawHttpRequest,
        queryParams: Map<String, String>,
        headerBearer: String
    ): Boolean {
        val upgrade = req.headers["upgrade"].orEmpty()
        val transport = queryParams["transport"].orEmpty().ifBlank { "polling" }
        val sidParam = queryParams["sid"]?.takeIf { it.isNotBlank() }

        if (upgrade.equals("websocket", ignoreCase = true) || transport == "websocket") {
            val wsKey = req.headers["sec-websocket-key"].orEmpty()
            if (wsKey.isBlank()) {
                writeTextResponse(output, 400, "Bad Request", "Missing Sec-WebSocket-Key")
                return false
            }

            val acceptKey = computeWebSocketAccept(wsKey)
            val handshakeResp = (
                "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"
                ).toByteArray(StandardCharsets.UTF_8)
            output.write(handshakeResp)
            output.flush()

            val isDirectWebSocket = sidParam == null
            val sid = sidParam ?: UUID.randomUUID().toString().replace("-", "").take(16)
            val session = socketIoSessions.getOrPut(sid) {
                SocketIoSession(
                    sid = sid,
                    authenticated = tokens.isEmpty() || (headerBearer.isNotBlank() && tokens.containsKey(headerBearer))
                )
            }
            session.wsOutput = output
            session.wsSocket = socket
            refreshStatusMetrics()

            if (isDirectWebSocket) {
                // Engine.IO v4 direct WebSocket connect requires server OPEN packet first
                val openJson = JSONObject()
                    .put("sid", sid)
                    .put("upgrades", JSONArray())
                    .put("pingInterval", 25_000)
                    .put("pingTimeout", 20_000)
                    .put("maxPayload", 1_000_000)
                sendWsTextFrame(output, "0$openJson")
            }

            try {
                while (!socket.isClosed && _status.value.running) {
                    val frame = readWsFrame(input) ?: break
                    when (frame.opcode) {
                        0x8 -> {
                            runCatching { sendWsControlFrame(output, 0x8, ByteArray(0)) }
                            break
                        }
                        0x9 -> sendWsControlFrame(output, 0xA, frame.payload)
                        0x1 -> {
                            val packet = String(frame.payload, StandardCharsets.UTF_8)
                            handleEngineIoPacket(session, packet, headerBearer, sendOnWs = true)
                        }
                    }
                }
            } catch (_: Exception) {
                // Socket closed
            } finally {
                socketIoSessions.remove(sid)
                refreshStatusMetrics()
                runCatching { socket.close() }
            }
            return true
        }

        // HTTP Polling fallback for Engine.IO v4 handshake or buffered events
        if (req.method == "GET") {
            if (sidParam == null) {
                val sid = UUID.randomUUID().toString().replace("-", "").take(16)
                val session = SocketIoSession(
                    sid = sid,
                    authenticated = tokens.isEmpty() || (headerBearer.isNotBlank() && tokens.containsKey(headerBearer))
                )
                socketIoSessions[sid] = session
                refreshStatusMetrics()
                val openJson = JSONObject()
                    .put("sid", sid)
                    .put("upgrades", JSONArray(listOf("websocket")))
                    .put("pingInterval", 25_000)
                    .put("pingTimeout", 20_000)
                    .put("maxPayload", 1_000_000)
                writeTextResponse(output, 200, "OK", "0$openJson")
                return false
            }

            val session = socketIoSessions[sidParam]
            if (session == null) {
                writeJsonResponse(output, 400, "Bad Request", JSONObject().put("message", "Session ID unknown"))
                return false
            }

            // Wait briefly for queued packets if empty
            val packets = mutableListOf<String>()
            var waitedMs = 0
            while (session.pollingQueue.isEmpty() && waitedMs < 1_500 && !socket.isClosed && _status.value.running) {
                delay(40)
                waitedMs += 40
            }
            while (true) {
                val next = session.pollingQueue.poll() ?: break
                packets.add(next)
            }
            val payload = if (packets.isEmpty()) "6" else packets.joinToString("\u001E")
            writeTextResponse(output, 200, "OK", payload)
            return false
        }

        if (req.method == "POST") {
            val session = sidParam?.let { socketIoSessions[it] }
            if (session == null) {
                writeJsonResponse(output, 400, "Bad Request", JSONObject().put("message", "Session ID unknown"))
                return false
            }
            val chunks = req.body.split('\u001E').filter { it.isNotEmpty() }
            for (pkt in chunks) {
                handleEngineIoPacket(session, pkt, headerBearer, sendOnWs = false)
            }
            writeTextResponse(output, 200, "OK", "ok")
            return false
        }

        writeTextResponse(output, 405, "Method Not Allowed", "Unsupported method")
        return false
    }

    private suspend fun handleEngineIoPacket(
        session: SocketIoSession,
        packet: String,
        headerBearer: String,
        sendOnWs: Boolean
    ) {
        if (packet.isEmpty()) return
        when {
            packet == "2probe" -> {
                deliverSocketPacket(session, "3probe", preferWs = sendOnWs)
            }
            packet == "2" -> {
                deliverSocketPacket(session, "3", preferWs = sendOnWs)
            }
            packet == "5" -> {
                // Upgrade from HTTP polling to WebSocket complete — flush queued frames
                while (true) {
                    val queued = session.pollingQueue.poll() ?: break
                    val wsOut = session.wsOutput ?: break
                    runCatching { sendWsTextFrame(wsOut, queued) }
                }
            }
            packet == "1" -> {
                session.wsSocket?.let { runCatching { it.close() } }
                socketIoSessions.remove(session.sid)
                refreshStatusMetrics()
            }
            packet.startsWith("40") -> {
                // Socket.IO v4 CONNECT packet: "40" or "40{\"token\":\"...\"}"
                val rawAuthJson = packet.removePrefix("40").trim()
                val bodyToken = if (rawAuthJson.startsWith("{")) {
                    runCatching { JSONObject(rawAuthJson).optString("token") }.getOrDefault("")
                } else {
                    ""
                }
                val presentedToken = bodyToken.ifBlank { headerBearer }
                val isValid = tokens.isEmpty() || (presentedToken.isNotBlank() && tokens.containsKey(presentedToken))
                if (!isValid) {
                    val errPayload = JSONObject().put("message", "Unauthorized Bridge token")
                    deliverSocketPacket(session, "44$errPayload", preferWs = sendOnWs)
                    return
                }
                session.authenticated = true
                val nsSid = UUID.randomUUID().toString().replace("-", "").take(16)
                session.namespaceSid = nsSid
                val ackObj = JSONObject().put("sid", nsSid)
                deliverSocketPacket(session, "40$ackObj", preferWs = sendOnWs)

                // Immediately push initial Bridge + Muse state to the newly connected Socket.IO client
                val arr = JSONArray()
                listBots().forEach { arr.put(it.toJson()) }
                val readyPayload = JSONObject()
                    .put("bots", arr)
                    .put("museConnected", computeMuseConnected())
                deliverSocketPacket(session, formatSocketIoEvent("bridge:ready", readyPayload), preferWs = sendOnWs)
                deliverSocketPacket(session, formatSocketIoEvent("muse:status", buildMuseStatusJson()), preferWs = sendOnWs)
            }
            packet.startsWith("42") -> {
                if (!session.authenticated && tokens.isNotEmpty()) {
                    return
                }
                val arrayRaw = packet.removePrefix("42").trim()
                val arr = runCatching { JSONArray(arrayRaw) }.getOrNull() ?: return
                val eventName = arr.optString(0)
                val eventArg = arr.optJSONObject(1) ?: JSONObject()
                when (eventName) {
                    "bridge:ping" -> {
                        val pong = buildMuseStatusJson().put("ok", true)
                        deliverSocketPacket(session, formatSocketIoEvent("bridge:pong", pong), preferWs = sendOnWs)
                    }
                    "muse:refresh" -> {
                        val currentBots = listBots()
                        for (bot in currentBots) {
                            if (!bot.enabled) continue
                            val (nextStatus, reason) = runCatching {
                                executor.checkBotSession(bot)
                            }.getOrElse { err ->
                                AgentAvailabilityStatus.ERROR to (err.localizedMessage ?: "Session check failed")
                            }
                            val updated = bot.copy(status = nextStatus, reason = reason)
                            bots[bot.botId] = updated
                            emitBotStatus(updated)
                        }
                        persistState()
                        refreshStatusMetrics()
                        emitMuseStatusEvent()
                    }
                    "bridge:dispatch" -> {
                        val resultJson = executeBridgeDispatch(eventArg)
                        persistState()
                        deliverSocketPacket(
                            session,
                            formatSocketIoEvent("bridge:dispatch_ack", resultJson),
                            preferWs = sendOnWs
                        )
                    }
                }
            }
        }
    }

    private fun deliverSocketPacket(session: SocketIoSession, packet: String, preferWs: Boolean) {
        val wsOut = session.wsOutput
        if (preferWs || wsOut != null) {
            if (wsOut != null) {
                try {
                    sendWsTextFrame(wsOut, packet)
                    return
                } catch (_: Exception) {
                    // Fallback to queue if write failed
                }
            }
        }
        session.pollingQueue.add(packet)
    }

    private fun extractBotId(pathOnly: String, suffix: String): String {
        val raw = pathOnly.removePrefix("/api/bridge/bots/").removeSuffix(suffix)
        return URLDecoder.decode(raw, "UTF-8")
    }

    private fun computeMuseConnected(): Boolean {
        return bots.values.any { it.enabled && it.status == AgentAvailabilityStatus.ONLINE }
    }

    private fun buildMuseStatusJson(): JSONObject {
        val current = listBots()
        val enabledBots = current.filter { it.enabled }
        val onlineBots = enabledBots.filter { it.status == AgentAvailabilityStatus.ONLINE }
        val connected = onlineBots.isNotEmpty()
        val detail = when {
            connected -> "Connected to Muse AI (${onlineBots.size} active bot${if (onlineBots.size == 1) "" else "s"})"
            enabledBots.any { it.status == AgentAvailabilityStatus.NEEDS_AUTH } ->
                "Disconnected · Sign in to Muse AI in the In-App Browser"
            enabledBots.isNotEmpty() -> "Disconnected from Muse AI · Tap Refresh to re-check"
            else -> "Disconnected · Create a Muse Bot / Bridge first"
        }
        return JSONObject()
            .put("connected", connected)
            .put("detail", detail)
            .put("botCount", current.size)
            .put("onlineCount", onlineBots.size)
            .put("timestamp", System.currentTimeMillis())
    }

    private fun emitMuseStatusEvent() {
        emitBridgeEvent("muse:status", buildMuseStatusJson())
    }

    private fun emitBotStatus(bot: EmbeddedBridgeBotRecord) {
        emitBridgeEvent(
            "bot:status",
            JSONObject()
                .put("botId", bot.botId)
                .put("provider", bot.provider)
                .put("status", bot.status.toRemoteKey())
                .put("reason", bot.reason)
        )
    }

    private fun emitBridgeEvent(eventName: String, payload: JSONObject) {
        emitSseEvent(eventName, payload)
        emitSocketIoEvent(eventName, payload)
    }

    private fun emitSseEvent(eventName: String, payload: JSONObject) {
        val frame = formatSseFrame(eventName, payload)
        val dead = mutableListOf<OutputStream>()
        for (out in sseSubscribers) {
            try {
                synchronized(out) {
                    out.write(frame)
                    out.flush()
                }
            } catch (_: Exception) {
                dead.add(out)
            }
        }
        if (dead.isNotEmpty()) sseSubscribers.removeAll(dead.toSet())
    }

    private fun emitSocketIoEvent(eventName: String, payload: JSONObject) {
        if (socketIoSessions.isEmpty()) return
        val packet = formatSocketIoEvent(eventName, payload)
        val deadSids = mutableListOf<String>()
        for ((sid, session) in socketIoSessions) {
            if (!session.authenticated && tokens.isNotEmpty()) continue
            val wsOut = session.wsOutput
            if (wsOut != null) {
                try {
                    sendWsTextFrame(wsOut, packet)
                } catch (_: Exception) {
                    deadSids.add(sid)
                }
            } else {
                session.pollingQueue.add(packet)
            }
        }
        deadSids.forEach { socketIoSessions.remove(it) }
        if (deadSids.isNotEmpty()) refreshStatusMetrics()
    }

    private fun formatSocketIoEvent(eventName: String, payload: JSONObject): String {
        val arr = JSONArray().put(eventName).put(payload)
        return "42$arr"
    }

    private fun formatSseFrame(eventName: String, payload: JSONObject): ByteArray {
        val text = "event: $eventName\ndata: $payload\n\n"
        return text.toByteArray(StandardCharsets.UTF_8)
    }

    private fun refreshStatusMetrics() {
        _status.value = _status.value.copy(
            botCount = bots.size,
            tokenCount = tokens.size,
            socketClientCount = socketIoSessions.size,
            museConnected = computeMuseConnected()
        )
    }

    private fun generateSecureToken(): String {
        val bytes = ByteArray(18)
        random.nextBytes(bytes)
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "mb_live_$hex"
    }

    private fun previewToken(token: String): String {
        if (token.length <= 14) return token
        return "${token.take(10)}...${token.takeLast(4)}"
    }

    private fun persistState() {
        val botsArr = JSONArray()
        bots.values.forEach { bot ->
            botsArr.put(
                JSONObject()
                    .put("botId", bot.botId)
                    .put("displayName", bot.displayName)
                    .put("provider", bot.provider)
                    .put("targetUrl", bot.targetUrl)
                    .put("enabled", bot.enabled)
                    .put("selectedForBroadcast", bot.selectedForBroadcast)
                    .put("status", bot.status.toRemoteKey())
                    .put("reason", bot.reason)
                    .put("tokenPreview", bot.tokenPreview)
                    .put("createdAt", bot.createdAt)
            )
        }
        // Only non-secret bot metadata is kept in SharedPreferences; raw tokens live in encrypted Room DB
        prefs.edit()
            .putString("bots_json", botsArr.toString())
            .remove("tokens_json")
            .apply()
    }

    private fun loadPersistedState() {
        val botsRaw = prefs.getString("bots_json", null)
        if (!botsRaw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(botsRaw)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val record = EmbeddedBridgeBotRecord(
                        botId = obj.optString("botId"),
                        displayName = obj.optString("displayName"),
                        provider = obj.optString("provider", "muse-web"),
                        targetUrl = obj.optString("targetUrl", "https://muse.ai"),
                        enabled = obj.optBoolean("enabled", true),
                        selectedForBroadcast = obj.optBoolean("selectedForBroadcast", true),
                        status = AgentAvailabilityStatus.fromRemote(obj.optString("status")),
                        reason = obj.optString("reason"),
                        tokenPreview = obj.optString("tokenPreview"),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                    if (record.botId.isNotBlank()) bots[record.botId] = record
                }
            }
        }
        refreshStatusMetrics()
    }

    private data class WsFrame(val opcode: Int, val payload: ByteArray)

    private fun computeWebSocketAccept(wsKey: String): String {
        val concat = (wsKey.trim() + WS_MAGIC_GUID).toByteArray(StandardCharsets.US_ASCII)
        val sha1 = MessageDigest.getInstance("SHA-1").digest(concat)
        return Base64.encodeToString(sha1, Base64.NO_WRAP)
    }

    private fun readWsFrame(input: InputStream): WsFrame? {
        val b1 = input.read()
        if (b1 == -1) return null
        val b2 = input.read()
        if (b2 == -1) return null

        val opcode = b1 and 0x0F
        val masked = (b2 and 0x80) != 0
        var length = (b2 and 0x7F).toLong()

        if (length == 126L) {
            val lenBytes = readExactBytes(input, 2) ?: return null
            length = (((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)).toLong()
        } else if (length == 127L) {
            val lenBytes = readExactBytes(input, 8) ?: return null
            length = 0L
            for (i in 0 until 8) {
                length = (length shl 8) or (lenBytes[i].toLong() and 0xFFL)
            }
        }

        if (length > 2 * 1024 * 1024) return null
        val maskKey = if (masked) (readExactBytes(input, 4) ?: return null) else null
        val payload = readExactBytes(input, length.toInt()) ?: return null
        if (maskKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor (maskKey[i % 4].toInt() and 0xFF)).toByte()
            }
        }
        return WsFrame(opcode = opcode, payload = payload)
    }

    private fun readExactBytes(input: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = try {
                input.read(buf, offset, count - offset)
            } catch (_: EOFException) {
                -1
            }
            if (read == -1) return null
            offset += read
        }
        return buf
    }

    private fun sendWsTextFrame(output: OutputStream, text: String) {
        val data = text.toByteArray(StandardCharsets.UTF_8)
        sendWsControlFrame(output, 0x1, data)
    }

    private fun sendWsControlFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        synchronized(output) {
            output.write(0x80 or (opcode and 0x0F))
            val len = payload.size
            when {
                len < 126 -> output.write(len)
                len <= 65535 -> {
                    output.write(126)
                    output.write((len shr 8) and 0xFF)
                    output.write(len and 0xFF)
                }
                else -> {
                    output.write(127)
                    val lenLong = len.toLong()
                    for (shift in 56 downTo 0 step 8) {
                        output.write(((lenLong shr shift) and 0xFFL).toInt())
                    }
                }
            }
            if (payload.isNotEmpty()) {
                output.write(payload)
            }
            output.flush()
        }
    }

    private data class RawHttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )

    private fun parseQueryParams(rawPath: String): Map<String, String> {
        val query = rawPath.substringAfter('?', "")
        if (query.isBlank()) return emptyMap()
        return query.split('&')
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null
                else {
                    val key = runCatching { URLDecoder.decode(pair.substring(0, idx), "UTF-8") }.getOrDefault("")
                    val value = runCatching { URLDecoder.decode(pair.substring(idx + 1), "UTF-8") }.getOrDefault("")
                    if (key.isNotEmpty()) key to value else null
                }
            }
            .toMap()
    }

    private fun parseJsonObject(raw: String): JSONObject {
        if (raw.isBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun readHttpRequest(input: InputStream): RawHttpRequest? {
        val headerBytes = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val b = input.read()
            if (b == -1) return null
            headerBytes.write(b)
            matched = when (matched) {
                0 -> if (b == '\r'.code) 1 else 0
                1 -> if (b == '\n'.code) 2 else if (b == '\r'.code) 1 else 0
                2 -> if (b == '\r'.code) 3 else 0
                3 -> if (b == '\n'.code) 4 else 0
                else -> 0
            }
            if (matched == 4) break
            if (headerBytes.size() > 64 * 1024) return null
        }

        val headerStr = headerBytes.toString(StandardCharsets.US_ASCII.name())
        val lines = headerStr.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val requestParts = lines[0].split(" ")
        if (requestParts.size < 2) return null
        val method = requestParts[0].uppercase()
        val path = requestParts[1]

        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) {
                val k = lines[i].substring(0, idx).trim().lowercase()
                val v = lines[i].substring(idx + 1).trim()
                headers[k] = v
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val bodyStr = if (contentLength > 0 && contentLength <= 4 * 1024 * 1024) {
            val bodyBuf = ByteArray(contentLength)
            var total = 0
            while (total < contentLength) {
                val read = input.read(bodyBuf, total, contentLength - total)
                if (read == -1) break
                total += read
            }
            String(bodyBuf, 0, total, StandardCharsets.UTF_8)
        } else {
            ""
        }

        return RawHttpRequest(method = method, path = path, headers = headers, body = bodyStr)
    }

    private fun writeJsonResponse(output: OutputStream, code: Int, reason: String, body: JSONObject) {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        val headers = (
            "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.UTF_8)
        output.write(headers)
        output.write(bytes)
        output.flush()
    }

    private fun writeTextResponse(output: OutputStream, code: Int, reason: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = (
            "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.UTF_8)
        output.write(headers)
        output.write(bytes)
        output.flush()
    }
}
