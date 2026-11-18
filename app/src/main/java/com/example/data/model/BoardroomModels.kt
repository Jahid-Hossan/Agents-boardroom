package com.example.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class AgentProvider(val id: String, val displayLabel: String) {
    INAPP_BROWSER("inapp-browser", "In-App Browser"),
    GEMINI("gemini", "Gemini AI"),
    OPENAI("openai", "OpenAI / Custom API"),
    DOCKER_WEB("docker-web", "Webhook / Docker"),
    SIMULATION("simulation", "Offline Demo");

    companion object {
        fun fromId(raw: String): AgentProvider =
            entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: INAPP_BROWSER
    }
}

enum class ChatViewMode {
    BOARDROOM,
    DIRECT
}

enum class CollaborationRole(val id: String) {
    NONE("none"),
    PRIMARY("primary"),
    SUPERVISOR_PLAN("supervisor-plan"),
    HANDOFF("handoff"),
    SUPERVISOR_FINAL("supervisor-final");

    companion object {
        fun fromId(raw: String?): CollaborationRole =
            entries.firstOrNull { it.id == raw } ?: NONE
    }
}

data class AgentConfig(
    val id: String,
    val name: String,
    val role: String = "",
    val avatar: String = "AI",
    val colorHex: String = "#3B82F6",
    val provider: AgentProvider = AgentProvider.INAPP_BROWSER,
    val customUrl: String = "https://muse.ai",
    val apiKey: String = "",
    val model: String = "",
    val endpoint: String = "",
    val systemPrompt: String = "",
    val isSupervisor: Boolean = false,
    val sortOrder: Int = 0
)

data class AttachmentData(
    val filename: String,
    val mimeType: String,
    val uriString: String = "",
    val base64Data: String = "",
    val textPreview: String = "",
    val sizeBytes: Long = 0L
) {
    val isImage: Boolean
        get() = mimeType.startsWith("image/", ignoreCase = true)

    val isAudio: Boolean
        get() = mimeType.startsWith("audio/", ignoreCase = true) ||
            Regex("\\.(mp3|wav|m4a|ogg|webm|flac|aac)$", RegexOption.IGNORE_CASE).containsMatchIn(filename)

    val isTextLike: Boolean
        get() = mimeType.startsWith("text/", ignoreCase = true) ||
            mimeType.equals("application/json", ignoreCase = true) ||
            Regex("\\.(txt|md|csv|json|js|ts|py|kt|java|html|css|log)$", RegexOption.IGNORE_CASE)
                .containsMatchIn(filename)
}

data class BoardroomMessage(
    val id: String,
    val sender: String, // "ceo", "system", or agent.id
    val role: String,
    val senderName: String,
    val avatar: String = "AI",
    val colorHex: String = "#3B82F6",
    val text: String,
    val attachment: AttachmentData? = null,
    val mode: String = "broadcast", // "broadcast" or "direct"
    val targetAgentId: String = "all",
    val rootTargetAgentId: String? = null,
    val rootMessageId: String? = null,
    val hop: Int = 0,
    val maxHops: Int = 3,
    val isSupervisor: Boolean = false,
    val collaborationRole: CollaborationRole = CollaborationRole.NONE,
    val fromAgentName: String? = null,
    val timestampMillis: Long = System.currentTimeMillis()
)

data class InAppBrowserStatus(
    val agentId: String,
    val open: Boolean = false,
    val ready: Boolean = false,
    val needsLogin: Boolean = false,
    val hasSavedProfile: Boolean = false,
    val currentUrl: String = "",
    val pageTitle: String = ""
)

enum class AgentAvailabilityStatus(val label: String) {
    ONLINE("Online"),
    BUSY("Busy"),
    OFFLINE("Offline")
}

fun resolveAgentAvailability(
    agent: AgentConfig,
    browserStatus: InAppBrowserStatus?,
    isBusy: Boolean,
    isNetworkAvailable: Boolean = true
): AgentAvailabilityStatus {
    if (isBusy) return AgentAvailabilityStatus.BUSY
    if (agent.provider == AgentProvider.SIMULATION) return AgentAvailabilityStatus.OFFLINE
    if (!isNetworkAvailable) return AgentAvailabilityStatus.OFFLINE

    return when (agent.provider) {
        AgentProvider.GEMINI -> AgentAvailabilityStatus.ONLINE
        AgentProvider.OPENAI ->
            if (agent.apiKey.isNotBlank()) AgentAvailabilityStatus.ONLINE else AgentAvailabilityStatus.OFFLINE
        AgentProvider.DOCKER_WEB ->
            if (agent.customUrl.isNotBlank()) AgentAvailabilityStatus.ONLINE else AgentAvailabilityStatus.OFFLINE
        AgentProvider.INAPP_BROWSER ->
            if (browserStatus?.ready == true || browserStatus?.hasSavedProfile == true) {
                AgentAvailabilityStatus.ONLINE
            } else {
                AgentAvailabilityStatus.OFFLINE
            }
        AgentProvider.SIMULATION -> AgentAvailabilityStatus.OFFLINE
    }
}

data class BoardroomSettings(
    val companyName: String = "Muse Boardroom",
    val ceoName: String = "You",
    val supervisorAgentId: String? = null,
    val collaborationEnabled: Boolean = true,
    val maxCollaborationHops: Int = 3
)

data class AiPreset(
    val label: String,
    val url: String,
    val role: String,
    val avatar: String,
    val colorHex: String
)

val DEFAULT_AI_PRESETS = listOf(
    AiPreset("Muse AI", "https://muse.ai", "Muse AI", "M", "#3B82F6"),
    AiPreset("Gemini", "https://gemini.google.com/app", "Google Gemini", "G", "#8B5CF6"),
    AiPreset("ChatGPT", "https://chatgpt.com", "OpenAI ChatGPT", "C", "#10B981"),
    AiPreset("Grok", "https://grok.com", "xAI Grok", "X", "#E2E8F0"),
    AiPreset("Claude", "https://claude.ai", "Anthropic Claude", "C", "#F59E0B")
)
