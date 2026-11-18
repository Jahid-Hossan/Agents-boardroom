package com.example.engine

import com.example.data.model.AgentAvailabilityStatus
import com.example.data.model.AttachmentData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class BridgeRemoteBot(
    val botId: String,
    val displayName: String,
    val provider: String,
    val enabled: Boolean,
    val selectedForBroadcast: Boolean,
    val status: AgentAvailabilityStatus,
    val reason: String,
    val targetUrl: String = "https://muse.ai",
    val tokenPreview: String = ""
)

data class BridgeCreateBotResult(
    val bot: BridgeRemoteBot,
    val token: String,
    val serverUrl: String
)

data class BridgeSyncResult(
    val providers: List<String>,
    val bots: List<BridgeRemoteBot>
)

data class BridgeDispatchItem(
    val botId: String,
    val provider: String,
    val status: String,
    val deliveryTaskId: String,
    val dispatchBatchId: String,
    val responseMessageId: String?,
    val text: String?,
    val errorCode: String?,
    val errorMessage: String?
)

data class BridgeDispatchBatchResult(
    val dispatchBatchId: String,
    val conversationId: String,
    val parentMessageId: String,
    val items: List<BridgeDispatchItem>
)

sealed interface BridgeSseEventData {
    data class BotStatusUpdated(
        val botId: String,
        val provider: String,
        val status: AgentAvailabilityStatus,
        val reason: String
    ) : BridgeSseEventData

    data class TaskDelta(
        val taskId: String,
        val dispatchBatchId: String,
        val parentMessageId: String,
        val botId: String,
        val fullTextSoFar: String
    ) : BridgeSseEventData

    data class TaskTerminal(
        val taskId: String,
        val botId: String,
        val status: String,
        val errorCode: String?,
        val errorMessage: String?
    ) : BridgeSseEventData

    data class MessageCreated(
        val id: String,
        val botId: String,
        val authorName: String,
        val provider: String,
        val parentMessageId: String?,
        val deliveryTaskId: String?,
        val dispatchBatchId: String?,
        val content: String
    ) : BridgeSseEventData
}

class MultiBotBridgeClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(65, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    private val sseClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    suspend fun testAndFetchBots(serverUrl: String, authToken: String): Result<BridgeSyncResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = normalizeBaseUrl(serverUrl)
                require(base.isNotBlank()) { "Enter a Multi-Bot Bridge server URL first." }

                val healthReq = buildRequest("$base/api/bridge/health", authToken).get().build()
                val providers = httpClient.newCall(healthReq).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Bridge health check failed (HTTP ${resp.code}): ${raw.take(140)}")
                    }
                    val json = JSONObject(raw)
                    val arr = json.optJSONArray("providers") ?: JSONArray()
                    List(arr.length()) { idx -> arr.optString(idx) }.filter { it.isNotBlank() }
                }

                val botsReq = buildRequest("$base/api/bridge/bots", authToken).get().build()
                val bots = httpClient.newCall(botsReq).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Failed to fetch bots (HTTP ${resp.code}): ${raw.take(140)}")
                    }
                    parseBotsResponse(raw)
                }

                BridgeSyncResult(providers = providers, bots = bots)
            }
        }

    suspend fun createBotAndToken(
        serverUrl: String,
        authToken: String = "",
        displayName: String,
        provider: String = "muse-web",
        targetUrl: String = "https://muse.ai",
        botId: String = ""
    ): Result<BridgeCreateBotResult> = withContext(Dispatchers.IO) {
        runCatching {
            val base = normalizeBaseUrl(serverUrl)
            require(base.isNotBlank()) { "Bridge server URL is not configured." }
            val payload = JSONObject()
                .put("displayName", displayName)
                .put("provider", provider)
                .put("targetUrl", targetUrl)
            if (botId.isNotBlank()) payload.put("botId", botId)

            val req = buildRequest("$base/api/bridge/bots", authToken)
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Create bot failed (HTTP ${resp.code}): ${raw.take(180)}")
                }
                parseCreateBotResult(raw, base)
            }
        }
    }

    suspend fun mintToken(
        serverUrl: String,
        authToken: String = "",
        botId: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val base = normalizeBaseUrl(serverUrl)
            require(base.isNotBlank()) { "Bridge server URL is not configured." }
            val payload = JSONObject()
            if (botId.isNotBlank()) payload.put("botId", botId)
            val req = buildRequest("$base/api/bridge/tokens", authToken)
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Mint token failed (HTTP ${resp.code}): ${raw.take(180)}")
                }
                JSONObject(raw).getString("token")
            }
        }
    }

    suspend fun setBotEnabled(
        serverUrl: String,
        authToken: String,
        botId: String,
        enabled: Boolean
    ): Result<BridgeRemoteBot> = withContext(Dispatchers.IO) {
        runCatching {
            val base = normalizeBaseUrl(serverUrl)
            val encodedId = URLEncoder.encode(botId, "UTF-8")
            val payload = JSONObject().put("enabled", enabled)
            val req = buildRequest("$base/api/bridge/bots/$encodedId/enable", authToken)
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code}: ${raw.take(140)}")
                }
                parseSingleBot(JSONObject(raw).getJSONObject("bot"))
            }
        }
    }

    suspend fun setBotSelected(
        serverUrl: String,
        authToken: String,
        botId: String,
        selected: Boolean
    ): Result<BridgeRemoteBot> = withContext(Dispatchers.IO) {
        runCatching {
            val base = normalizeBaseUrl(serverUrl)
            val encodedId = URLEncoder.encode(botId, "UTF-8")
            val payload = JSONObject().put("selected", selected)
            val req = buildRequest("$base/api/bridge/bots/$encodedId/select", authToken)
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code}: ${raw.take(140)}")
                }
                parseSingleBot(JSONObject(raw).getJSONObject("bot"))
            }
        }
    }

    suspend fun initBotSession(
        serverUrl: String,
        authToken: String,
        botId: String
    ): Result<Pair<AgentAvailabilityStatus, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = normalizeBaseUrl(serverUrl)
            val encodedId = URLEncoder.encode(botId, "UTF-8")
            val req = buildRequest("$base/api/bridge/bots/$encodedId/init", authToken)
                .post("{}".toRequestBody(JSON_MEDIA))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code}: ${raw.take(140)}")
                }
                val init = JSONObject(raw).getJSONObject("init")
                val status = AgentAvailabilityStatus.fromRemote(init.optString("status"))
                val message = init.optString("message").ifBlank { status.label }
                status to message
            }
        }
    }

    suspend fun dispatchBroadcast(
        serverUrl: String,
        authToken: String,
        conversationId: String,
        parentMessageId: String,
        prompt: String,
        targetBotIds: List<String>,
        attachment: AttachmentData? = null
    ): Result<BridgeDispatchBatchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val base = normalizeBaseUrl(serverUrl)
            require(base.isNotBlank()) { "Bridge server URL is not configured." }
            val effectivePrompt = if (attachment != null && attachment.isTextLike && attachment.textPreview.isNotBlank()) {
                "$prompt\n\n[Attached file: ${attachment.filename}]\n${attachment.textPreview.take(16000)}"
            } else if (attachment != null) {
                "$prompt\n\n[Attached file: ${attachment.filename} (${attachment.mimeType})]"
            } else {
                prompt
            }

            val payload = JSONObject()
                .put("conversationId", conversationId)
                .put("parentMessageId", parentMessageId)
                .put("prompt", effectivePrompt)
                .put("targetBotIds", JSONArray(targetBotIds))

            val req = buildRequest("$base/api/bridge/dispatch", authToken)
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Bridge dispatch failed (HTTP ${resp.code}): ${raw.take(180)}")
                }
                parseDispatchResult(raw)
            }
        }
    }

    suspend fun cancelTask(serverUrl: String, authToken: String, taskId: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = normalizeBaseUrl(serverUrl)
                val encoded = URLEncoder.encode(taskId, "UTF-8")
                val req = buildRequest("$base/api/bridge/tasks/$encoded/cancel", authToken)
                    .post("{}".toRequestBody(JSON_MEDIA))
                    .build()
                httpClient.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }

    fun streamEvents(serverUrl: String, authToken: String): Flow<BridgeSseEventData> = flow {
        val base = normalizeBaseUrl(serverUrl)
        if (base.isBlank()) return@flow

        val req = buildRequest("$base/api/bridge/events", authToken)
            .addHeader("Accept", "text/event-stream")
            .get()
            .build()

        sseClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("SSE stream failed (HTTP ${resp.code})")
            }
            val body = resp.body ?: return@use
            BufferedReader(InputStreamReader(body.byteStream())).use { reader ->
                var currentEvent = ""
                val dataBuffer = StringBuilder()

                while (currentCoroutineContext().isActive) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith(":") -> Unit
                        line.startsWith("event:") -> {
                            currentEvent = line.removePrefix("event:").trim()
                        }
                        line.startsWith("data:") -> {
                            if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                            dataBuffer.append(line.removePrefix("data:").trim())
                        }
                        line.isEmpty() -> {
                            if (currentEvent.isNotBlank() && dataBuffer.isNotEmpty()) {
                                val parsed = parseSseEvents(currentEvent, dataBuffer.toString())
                                for (evt in parsed) emit(evt)
                            }
                            currentEvent = ""
                            dataBuffer.clear()
                        }
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun buildRequest(url: String, authToken: String): Request.Builder {
        val builder = Request.Builder().url(url)
        val token = authToken.trim()
        if (token.isNotEmpty()) {
            builder.addHeader("Authorization", "Bearer $token")
        }
        return builder
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun normalizeBaseUrl(rawUrl: String): String {
            val normalized = AgentInferenceService.normalizeHttpUrl(rawUrl).trimEnd('/')
            return normalized
        }

        fun parseBotsResponse(rawJson: String): List<BridgeRemoteBot> {
            val root = JSONObject(rawJson)
            val arr = root.optJSONArray("bots") ?: return emptyList()
            return List(arr.length()) { idx -> parseSingleBot(arr.getJSONObject(idx)) }
        }

        fun parseSingleBot(obj: JSONObject): BridgeRemoteBot {
            val health = obj.optJSONObject("health")
            return BridgeRemoteBot(
                botId = obj.optString("botId"),
                displayName = obj.optString("displayName").ifBlank { obj.optString("botId") },
                provider = obj.optString("provider").ifBlank { "muse-web" },
                enabled = obj.optBoolean("enabled", true),
                selectedForBroadcast = obj.optBoolean("selectedForBroadcast", true),
                status = AgentAvailabilityStatus.fromRemote(health?.optString("status")),
                reason = health?.optString("reason").orEmpty(),
                targetUrl = obj.optString("targetUrl").ifBlank { "https://muse.ai" },
                tokenPreview = obj.optString("tokenPreview").orEmpty()
            )
        }

        fun parseCreateBotResult(rawJson: String, fallbackServerUrl: String = ""): BridgeCreateBotResult {
            val json = JSONObject(rawJson)
            val bot = parseSingleBot(json.getJSONObject("bot"))
            val token = json.optString("token")
            val serverUrl = json.optString("serverUrl").ifBlank { fallbackServerUrl }
            return BridgeCreateBotResult(
                bot = bot,
                token = token,
                serverUrl = serverUrl
            )
        }

        fun parseDispatchResult(rawJson: String): BridgeDispatchBatchResult {
            val root = JSONObject(rawJson)
            val batchId = root.optString("dispatchBatchId")
            val convId = root.optString("conversationId")
            val parentMsgId = root.optString("parentMessageId")

            val tasksArr = root.optJSONArray("tasks") ?: JSONArray()
            val respArr = root.optJSONArray("responses") ?: JSONArray()
            val responseByTaskId = mutableMapOf<String, JSONObject>()
            for (i in 0 until respArr.length()) {
                val r = respArr.getJSONObject(i)
                val tId = r.optString("deliveryTaskId")
                if (tId.isNotBlank()) responseByTaskId[tId] = r
            }

            val items = List(tasksArr.length()) { idx ->
                val task = tasksArr.getJSONObject(idx)
                val taskId = task.optString("taskId")
                val resp = responseByTaskId[taskId]
                BridgeDispatchItem(
                    botId = task.optString("botId"),
                    provider = task.optString("provider"),
                    status = task.optString("status"),
                    deliveryTaskId = taskId,
                    dispatchBatchId = task.optString("dispatchBatchId").ifBlank { batchId },
                    responseMessageId = resp?.optString("id")?.takeIf { it.isNotBlank() }
                        ?: task.optString("responseMessageId").takeIf { it.isNotBlank() },
                    text = resp?.optString("content")?.takeIf { it.isNotBlank() },
                    errorCode = task.optString("errorCode").takeIf { it.isNotBlank() },
                    errorMessage = task.optString("errorMessage").takeIf { it.isNotBlank() }
                )
            }

            return BridgeDispatchBatchResult(
                dispatchBatchId = batchId,
                conversationId = convId,
                parentMessageId = parentMsgId,
                items = items
            )
        }

        fun parseSseEvents(eventName: String, rawData: String): List<BridgeSseEventData> {
            return try {
                val json = JSONObject(rawData)
                when (eventName) {
                    "bridge:ready" -> {
                        val botsArr = json.optJSONArray("bots") ?: return emptyList()
                        List(botsArr.length()) { i ->
                            val bot = parseSingleBot(botsArr.getJSONObject(i))
                            BridgeSseEventData.BotStatusUpdated(
                                botId = bot.botId,
                                provider = bot.provider,
                                status = bot.status,
                                reason = bot.reason
                            )
                        }
                    }
                    "bot:status" -> listOf(
                        BridgeSseEventData.BotStatusUpdated(
                            botId = json.optString("botId"),
                            provider = json.optString("provider"),
                            status = AgentAvailabilityStatus.fromRemote(json.optString("status")),
                            reason = json.optString("reason")
                        )
                    )
                    "task:delta" -> listOf(
                        BridgeSseEventData.TaskDelta(
                            taskId = json.optString("taskId"),
                            dispatchBatchId = json.optString("dispatchBatchId"),
                            parentMessageId = json.optString("parentMessageId"),
                            botId = json.optString("botId"),
                            fullTextSoFar = json.optString("fullTextSoFar")
                        )
                    )
                    "task:completed", "task:failed", "task:cancelled" -> listOf(
                        BridgeSseEventData.TaskTerminal(
                            taskId = json.optString("taskId"),
                            botId = json.optString("botId"),
                            status = json.optString("status"),
                            errorCode = json.optString("errorCode").takeIf { it.isNotBlank() },
                            errorMessage = json.optString("errorMessage").takeIf { it.isNotBlank() }
                        )
                    )
                    "message:created" -> listOf(
                        BridgeSseEventData.MessageCreated(
                            id = json.optString("id"),
                            botId = json.optString("authorBotId"),
                            authorName = json.optString("authorName"),
                            provider = json.optString("provider"),
                            parentMessageId = json.optString("parentMessageId").takeIf { it.isNotBlank() },
                            deliveryTaskId = json.optString("deliveryTaskId").takeIf { it.isNotBlank() },
                            dispatchBatchId = json.optString("dispatchBatchId").takeIf { it.isNotBlank() },
                            content = json.optString("content")
                        )
                    )
                    else -> emptyList()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
