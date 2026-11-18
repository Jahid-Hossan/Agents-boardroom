package com.example.engine

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.example.data.model.AgentConfig
import com.example.data.model.AgentProvider
import com.example.data.model.AttachmentData
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "AgentInferenceService"
private const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"

class AgentInferenceService(
    private val context: Context,
    private val browserCoordinator: InAppBrowserCoordinator,
    val bridgeClient: MultiBotBridgeClient = MultiBotBridgeClient()
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val aiBackend by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI())
    }

    suspend fun invokeAgent(
        agent: AgentConfig,
        promptText: String,
        attachment: AttachmentData? = null,
        bridgeServerUrl: String = "",
        bridgeAuthToken: String = "",
        parentMessageId: String? = null
    ): String {
        return try {
            withTimeout(65_000L) {
                when (agent.provider) {
                    AgentProvider.MUSE_BRIDGE ->
                        callMuseBridgeSingle(
                            agent = agent,
                            promptText = promptText,
                            attachment = attachment,
                            bridgeServerUrl = bridgeServerUrl,
                            bridgeAuthToken = bridgeAuthToken,
                            parentMessageId = parentMessageId
                        )
                    AgentProvider.INAPP_BROWSER ->
                        browserCoordinator.sendPrompt(agent, promptText, attachment)
                    AgentProvider.GEMINI ->
                        callGemini(agent, promptText, attachment)
                    AgentProvider.OPENAI ->
                        callOpenAiCompatible(agent, promptText, attachment)
                    AgentProvider.DOCKER_WEB ->
                        callDockerWebhook(agent, appendAttachmentToTextPrompt(promptText, attachment))
                    AgentProvider.SIMULATION ->
                        generateDemoResponse(agent, appendAttachmentToTextPrompt(promptText, attachment))
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "${agent.name} timed out", e)
            "**${agent.name} error:** Request timed out after 60 seconds."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "${agent.name} failed", e)
            "**${agent.name} error:** ${e.localizedMessage ?: "Request failed."}"
        }
    }

    private suspend fun callMuseBridgeSingle(
        agent: AgentConfig,
        promptText: String,
        attachment: AttachmentData?,
        bridgeServerUrl: String,
        bridgeAuthToken: String,
        parentMessageId: String?
    ): String {
        val server = agent.customUrl.trim().ifBlank { bridgeServerUrl.trim() }
        val token = agent.apiKey.trim().ifBlank { bridgeAuthToken.trim() }
        val remoteBotId = agent.bridgeBotId.trim().ifBlank { agent.id }

        if (server.isEmpty()) {
            return "**${agent.name} Bridge not configured.** Open **Manage Agents** and set the Multi-Bot Bridge URL."
        }

        val result = bridgeClient.dispatchBroadcast(
            serverUrl = server,
            authToken = token,
            conversationId = "boardroom-main",
            parentMessageId = parentMessageId ?: "msg_${System.currentTimeMillis()}",
            prompt = promptText,
            targetBotIds = listOf(remoteBotId),
            attachment = attachment
        )

        return result.fold(
            onSuccess = { batch ->
                val item = batch.items.firstOrNull { it.botId == remoteBotId } ?: batch.items.firstOrNull()
                when {
                    item == null -> "**${agent.name} error:** Bridge returned no delivery task."
                    !item.text.isNullOrBlank() -> item.text
                    item.status == "needs_auth" ->
                        "**${agent.name} needs authentication:** ${item.errorMessage ?: "Sign in on the Bridge host for bot `$remoteBotId`."}"
                    else ->
                        "**${agent.name} error:** ${item.errorMessage ?: "Bot task ${item.status}."}"
                }
            },
            onFailure = { err ->
                "**${agent.name} Bridge error:** ${err.localizedMessage ?: "Failed to reach Multi-Bot Bridge."}"
            }
        )
    }

    private suspend fun callGemini(
        agent: AgentConfig,
        promptText: String,
        attachment: AttachmentData?
    ): String {
        val modelName = normalizeGeminiModel(agent.model)

        // If user explicitly supplied a custom Gemini API key in Manage Agents, call REST with their key.
        // Otherwise use Firebase AI Logic (zero API key required, protected by App Check).
        if (agent.apiKey.isNotBlank()) {
            return callGeminiWithCustomKey(agent.apiKey.trim(), modelName, agent.systemPrompt, promptText, attachment)
        }

        val model = if (agent.systemPrompt.isNotBlank()) {
            aiBackend.generativeModel(
                modelName = modelName,
                systemInstruction = content { text(agent.systemPrompt) }
            )
        } else {
            aiBackend.generativeModel(modelName = modelName)
        }

        val inputContent = content {
            text(promptText)
            if (attachment != null) {
                if (attachment.isTextLike && attachment.textPreview.isNotBlank()) {
                    text("[Attached file: ${attachment.filename}]\n${attachment.textPreview.take(16000)}")
                } else if (attachment.isImage && attachment.base64Data.isNotBlank()) {
                    val bytes = Base64.decode(attachment.base64Data, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        image(bitmap)
                    } else {
                        inlineData(bytes, attachment.mimeType.ifBlank { "image/jpeg" })
                    }
                } else if (attachment.base64Data.isNotBlank()) {
                    val bytes = Base64.decode(attachment.base64Data, Base64.DEFAULT)
                    if (bytes.size <= 18 * 1024 * 1024) {
                        inlineData(bytes, attachment.mimeType.ifBlank { "application/octet-stream" })
                    } else {
                        text("[Attached file: ${attachment.filename} (${attachment.mimeType}) exceeds 18MB limit]")
                    }
                }
            }
        }

        val response = model.generateContent(inputContent)
        return response.text?.trim()?.takeIf { it.isNotEmpty() }
            ?: "No response received from Gemini."
    }

    private suspend fun callGeminiWithCustomKey(
        apiKey: String,
        modelName: String,
        systemPrompt: String,
        promptText: String,
        attachment: AttachmentData?
    ): String = withContext(Dispatchers.IO) {
        val parts = JSONArray().apply {
            put(JSONObject().put("text", promptText))
            if (attachment != null) {
                if (attachment.isTextLike && attachment.textPreview.isNotBlank()) {
                    put(JSONObject().put("text", "[Attached file: ${attachment.filename}]\n${attachment.textPreview.take(16000)}"))
                } else if (attachment.base64Data.isNotBlank()) {
                    put(
                        JSONObject().put(
                            "inlineData",
                            JSONObject()
                                .put("mimeType", attachment.mimeType.ifBlank { "application/octet-stream" })
                                .put("data", attachment.base64Data)
                        )
                    )
                }
            }
        }
        val payload = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            if (systemPrompt.isNotBlank()) {
                put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                )
            }
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
        val req = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Gemini API (${resp.code}): ${bodyStr.take(180)}")
            }
            val json = JSONObject(bodyStr)
            val candidates = json.optJSONArray("candidates")
            val text = candidates?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()
                .trim()
            text.ifEmpty { "No response received from Gemini." }
        }
    }

    private suspend fun callOpenAiCompatible(
        agent: AgentConfig,
        promptText: String,
        attachment: AttachmentData?
    ): String = withContext(Dispatchers.IO) {
        val endpoint = agent.endpoint.trim().ifEmpty { "https://api.openai.com/v1/chat/completions" }
        val modelName = agent.model.trim().ifEmpty { "gpt-4o-mini" }

        val userContent: Any = when {
            attachment == null -> promptText
            attachment.isImage && attachment.base64Data.isNotBlank() -> {
                val dataUrl = "data:${attachment.mimeType.ifBlank { "image/jpeg" }};base64,${attachment.base64Data}"
                JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", promptText))
                    put(
                        JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", dataUrl))
                    )
                }
            }
            attachment.isTextLike && attachment.textPreview.isNotBlank() ->
                "$promptText\n\n[Attached file: ${attachment.filename}]\n${attachment.textPreview.take(16000)}"
            else ->
                "$promptText\n\n[Attached file: ${attachment.filename} (${attachment.mimeType})]"
        }

        val messagesArr = JSONArray().apply {
            put(
                JSONObject()
                    .put("role", "system")
                    .put("content", agent.systemPrompt.ifBlank { "You are a helpful AI assistant." })
            )
            put(
                JSONObject()
                    .put("role", "user")
                    .put("content", userContent)
            )
        }

        val payload = JSONObject()
            .put("model", modelName)
            .put("messages", messagesArr)
            .put("max_tokens", 1200)

        val builder = Request.Builder()
            .url(endpoint)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))

        if (agent.apiKey.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer ${agent.apiKey.trim()}")
        }

        httpClient.newCall(builder.build()).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("API error (${resp.code}): ${bodyStr.take(180)}")
            }
            val json = JSONObject(bodyStr)
            json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "No response received."
        }
    }

    private suspend fun callDockerWebhook(agent: AgentConfig, promptText: String): String = withContext(Dispatchers.IO) {
        val baseUrl = agent.customUrl.trim().trimEnd('/')
        if (baseUrl.isEmpty()) {
            return@withContext "**${agent.name} is not configured yet.**\n\nOpen **Manage Agents** and enter a public `https://` endpoint URL for this agent."
        }

        val openAiMessages = JSONArray().apply {
            if (agent.systemPrompt.isNotBlank()) {
                put(JSONObject().put("role", "system").put("content", agent.systemPrompt))
            }
            put(JSONObject().put("role", "user").put("content", promptText))
        }

        val attempts = listOf(
            "$baseUrl/v1/chat/completions" to JSONObject()
                .put("model", agent.model.ifBlank { "default" })
                .put("messages", openAiMessages),
            "$baseUrl/api/chat" to JSONObject()
                .put("message", promptText)
                .put("prompt", promptText)
                .put("input", promptText),
            "$baseUrl/chat" to JSONObject()
                .put("message", promptText)
                .put("prompt", promptText)
                .put("input", promptText),
            baseUrl to JSONObject()
                .put("message", promptText)
                .put("prompt", promptText)
                .put("input", promptText)
        )

        var lastError = "No chat API endpoint responded"
        val shortClient = httpClient.newBuilder().readTimeout(8, TimeUnit.SECONDS).build()

        for ((url, bodyJson) in attempts) {
            try {
                val builder = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                if (agent.apiKey.isNotBlank()) {
                    builder.addHeader("Authorization", "Bearer ${agent.apiKey.trim()}")
                }
                shortClient.newCall(builder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val contentType = resp.header("Content-Type").orEmpty()
                        val raw = resp.body?.string().orEmpty()
                        if (contentType.contains("application/json", ignoreCase = true) || raw.trimStart().startsWith("{")) {
                            val data = JSONObject(raw)
                            val reply = data.optString("reply")
                                .ifBlank { data.optString("response") }
                                .ifBlank { data.optString("message") }
                                .ifBlank {
                                    data.optJSONArray("choices")
                                        ?.optJSONObject(0)
                                        ?.optJSONObject("message")
                                        ?.optString("content")
                                        .orEmpty()
                                }
                            if (reply.isNotBlank()) return@withContext reply
                        }
                    } else {
                        lastError = "HTTP ${resp.code} from $url"
                    }
                }
            } catch (e: Exception) {
                lastError = e.localizedMessage ?: "Connection failed"
            }
        }

        "Could not reach **${agent.name}** at `$baseUrl` ($lastError). Ensure the URL is publicly accessible and exposes `/v1/chat/completions` or `/api/chat`."
    }

    suspend fun testWebhookConnection(rawUrl: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val normalized = normalizeHttpUrl(rawUrl)
            if (normalized.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Enter a URL first."))
            }
            val req = Request.Builder().url(normalized).get().build()
            val shortClient = httpClient.newBuilder().readTimeout(6, TimeUnit.SECONDS).build()
            shortClient.newCall(req).execute().use { resp ->
                Result.success("Reachable (HTTP ${resp.code})")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun transcribeAudio(audioBytes: ByteArray, mimeType: String): Result<String> {
        return try {
            withTimeout(60_000L) {
                val model = aiBackend.generativeModel(DEFAULT_GEMINI_MODEL)
                val response = model.generateContent(
                    content {
                        inlineData(audioBytes, mimeType.ifBlank { "audio/mp4" })
                        text(
                            "Transcribe the spoken audio accurately into clean prompt text ready to send to an AI assistant. Preserve @mentions if spoken. Return only the transcribed text with no surrounding quotes or commentary."
                        )
                    }
                )
                val transcript = response.text?.trim().orEmpty()
                if (transcript.isEmpty()) {
                    Result.failure(IllegalStateException("No speech detected in the recording. Please speak clearly and try again."))
                } else {
                    Result.success(transcript)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Voice transcription timed out. Please try again."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Audio transcription failed", e)
            Result.failure(e)
        }
    }

    private fun normalizeGeminiModel(rawModel: String): String {
        val m = rawModel.trim()
        if (m.isEmpty() || Regex("^gemini-(1\\.5|2\\.0|2\\.5|pro\\b|4-hydrogen)", RegexOption.IGNORE_CASE).containsMatchIn(m)) {
            return DEFAULT_GEMINI_MODEL
        }
        return m
    }

    private fun appendAttachmentToTextPrompt(promptText: String, attachment: AttachmentData?): String {
        if (attachment == null) return promptText
        return if (attachment.isTextLike && attachment.textPreview.isNotBlank()) {
            "$promptText\n\n[Attached file: ${attachment.filename}]\n${attachment.textPreview.take(16000)}"
        } else {
            "$promptText\n\n[Attached file: ${attachment.filename} (${attachment.mimeType})]"
        }
    }

    private fun generateDemoResponse(agent: AgentConfig, prompt: String): String {
        val core = extractDemoCorePrompt(prompt)
        val roleStr = if (agent.role.isNotBlank()) " (${agent.role})" else ""
        val isGreeting = Regex("^(hello|hi|hey|greetings|good\\s*(morning|afternoon|evening)|hola|salam)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(core)
        return if (isGreeting) {
            "Hello! **${agent.name}**$roleStr is running in **Offline Demo** mode. Switch to **In-App Browser**, **Gemini AI**, or **OpenAI / Custom API** in **Manage Agents** for live AI responses."
        } else {
            "**[Offline Demo — ${agent.name}$roleStr]** Received: \"$core\".\n\nSwitch this agent to **In-App Browser**, **Gemini AI**, or **OpenAI / Custom API** in **Manage Agents** to get real AI output."
        }
    }

    private fun extractDemoCorePrompt(prompt: String): String {
        val m1 = Regex("Original user request:\\s*([\\s\\S]*?)(?:\\n\\n|\\nRequest from|$)", RegexOption.IGNORE_CASE).find(prompt)
        val m2 = Regex("User request:\\s*([\\s\\S]*)$", RegexOption.IGNORE_CASE).find(prompt)
        val extracted = (m1?.groups?.get(1)?.value ?: m2?.groups?.get(1)?.value ?: prompt).trim()
        return extracted.replace(Regex("^@[\\w.-]+\\s*"), "").trim()
    }

    companion object {
        fun normalizeHttpUrl(raw: String): String {
            val s = raw.trim()
            if (s.isEmpty()) return ""
            return if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(s)) s else "https://$s"
        }
    }
}
