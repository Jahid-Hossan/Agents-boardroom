package com.example.data.repository

import com.example.data.local.BoardroomDao
import com.example.data.local.toDomain
import com.example.data.local.toEntity
import com.example.data.model.AgentConfig
import com.example.data.model.AgentProvider
import com.example.data.model.BoardroomMessage
import com.example.data.model.BoardroomSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BoardroomRepository(private val dao: BoardroomDao) {

    val agentsFlow: Flow<List<AgentConfig>> = dao.observeAgents().map { list ->
        list.map { it.toDomain() }
    }

    val messagesFlow: Flow<List<BoardroomMessage>> = dao.observeMessages().map { list ->
        list.map { it.toDomain() }
    }

    val settingsFlow: Flow<BoardroomSettings> = dao.observeSettings().map { entity ->
        entity?.toDomain() ?: BoardroomSettings()
    }

    suspend fun ensureSeeded() {
        if (dao.getSettingsOnce() == null) {
            dao.upsertSettings(BoardroomSettings().toEntity())
        }
        if (dao.getAgentCount() == 0) {
            dao.upsertAgents(defaultAgents().map { it.toEntity() })
        }
        if (dao.getMessageCount() == 0) {
            dao.insertMessage(welcomeSystemMessage().toEntity())
        }
    }

    suspend fun upsertAgent(agent: AgentConfig) {
        dao.upsertAgent(agent.toEntity())
    }

    suspend fun deleteAgent(agentId: String) {
        dao.deleteAgentById(agentId)
    }

    suspend fun setSupervisor(supervisorAgentId: String?) {
        dao.setSupervisorFlag(supervisorAgentId)
        val current = dao.getSettingsOnce()?.toDomain() ?: BoardroomSettings()
        dao.upsertSettings(current.copy(supervisorAgentId = supervisorAgentId).toEntity())
    }

    suspend fun saveSettings(settings: BoardroomSettings) {
        dao.upsertSettings(settings.toEntity())
        dao.setSupervisorFlag(settings.supervisorAgentId)
    }

    suspend fun insertMessage(message: BoardroomMessage) {
        dao.insertMessage(message.toEntity())
    }

    suspend fun clearChatHistory() {
        dao.clearAllMessages()
        dao.insertMessage(
            BoardroomMessage(
                id = "sys-${System.currentTimeMillis()}",
                sender = "system",
                role = "System",
                senderName = "System",
                text = "Chat history cleared. Ready for your next command.",
                timestampMillis = System.currentTimeMillis()
            ).toEntity()
        )
    }

    companion object {
        fun defaultAgents(): List<AgentConfig> = listOf(
            AgentConfig(
                id = "muse",
                name = "Muse AI",
                role = "Muse AI",
                avatar = "M",
                colorHex = "#3B82F6",
                provider = AgentProvider.INAPP_BROWSER,
                customUrl = "https://muse.ai",
                sortOrder = 0
            ),
            AgentConfig(
                id = "gemini",
                name = "Gemini",
                role = "Google Gemini",
                avatar = "G",
                colorHex = "#8B5CF6",
                provider = AgentProvider.GEMINI,
                customUrl = "https://gemini.google.com/app",
                model = "gemini-3.8-flash",
                sortOrder = 1
            ),
            AgentConfig(
                id = "chatgpt",
                name = "ChatGPT",
                role = "OpenAI ChatGPT",
                avatar = "C",
                colorHex = "#10B981",
                provider = AgentProvider.INAPP_BROWSER,
                customUrl = "https://chatgpt.com",
                model = "gpt-4o-mini",
                sortOrder = 2
            ),
            AgentConfig(
                id = "grok",
                name = "Grok",
                role = "xAI Grok",
                avatar = "X",
                colorHex = "#E2E8F0",
                provider = AgentProvider.INAPP_BROWSER,
                customUrl = "https://grok.com",
                sortOrder = 3
            )
        )

        fun welcomeSystemMessage(): BoardroomMessage = BoardroomMessage(
            id = "init-1",
            sender = "system",
            role = "System",
            senderName = "System",
            text = "Command Center ready with **Muse AI**, **Gemini**, **ChatGPT**, and **Grok**. Tap **In-App Browser** (or the globe icon on any agent) to sign in inside the app, or chat directly with **Gemini** right away. Agents can collaborate automatically via **@AgentName** (up to 3 rounds), and you can designate any agent as **CEO / Supervisor** inside **Manage Agents**.",
            timestampMillis = System.currentTimeMillis()
        )
    }
}
