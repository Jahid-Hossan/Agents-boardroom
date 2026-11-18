package com.example.data.repository

import com.example.data.local.BoardroomDao
import com.example.data.local.BridgeAuthTokenDao
import com.example.data.local.BridgeAuthTokenEntity
import com.example.data.local.TokenCryptoManager
import com.example.data.local.toDomain
import com.example.data.local.toEntity
import com.example.data.model.AgentConfig
import com.example.data.model.AgentProvider
import com.example.data.model.BoardroomMessage
import com.example.data.model.BoardroomSettings
import com.example.data.model.BridgeAuthTokenRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.UUID

class BoardroomRepository(
    private val dao: BoardroomDao,
    private val tokenDao: BridgeAuthTokenDao? = null,
    private val cryptoManager: TokenCryptoManager? = null
) {

    val agentsFlow: Flow<List<AgentConfig>> = dao.observeAgents().map { list ->
        list.map { it.toDomain() }
    }

    val messagesFlow: Flow<List<BoardroomMessage>> = dao.observeMessages().map { list ->
        list.map { it.toDomain() }
    }

    val settingsFlow: Flow<BoardroomSettings> = dao.observeSettings().map { entity ->
        entity?.toDomain() ?: BoardroomSettings()
    }

    val activeAuthTokensFlow: Flow<List<BridgeAuthTokenRecord>> =
        tokenDao?.observeActiveTokens()?.map { list ->
            val now = System.currentTimeMillis()
            list.filter { it.expiresAtMillis == null || it.expiresAtMillis > now }
                .map { it.toDomain() }
        } ?: flowOf(emptyList())

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

    suspend fun saveEncryptedAuthToken(
        botId: String,
        serverUrl: String,
        rawToken: String,
        expiresAtMillis: Long? = null
    ) {
        val daoRef = tokenDao ?: return
        val crypto = cryptoManager ?: return
        val trimmed = rawToken.trim()
        if (trimmed.isEmpty() || botId.isBlank()) return

        val now = System.currentTimeMillis()
        daoRef.deleteExpiredTokens(now)
        val encrypted = crypto.encrypt(trimmed)
        val entity = BridgeAuthTokenEntity(
            id = "tok-${UUID.randomUUID()}",
            botId = botId,
            serverUrl = serverUrl.trim(),
            encryptedToken = encrypted,
            tokenHash = TokenCryptoManager.sha256Hex(trimmed),
            tokenPreview = TokenCryptoManager.previewToken(trimmed),
            isActive = true,
            createdAtMillis = now,
            lastUsedAtMillis = now,
            expiresAtMillis = expiresAtMillis
        )
        daoRef.replaceActiveTokenForBot(entity)
    }

    suspend fun getDecryptedActiveTokens(): List<Pair<String, String>> {
        val daoRef = tokenDao ?: return emptyList()
        val crypto = cryptoManager ?: return emptyList()
        val now = System.currentTimeMillis()
        daoRef.deleteExpiredTokens(now)
        return daoRef.getActiveTokensOnce()
            .filter { it.expiresAtMillis == null || it.expiresAtMillis > now }
            .mapNotNull { entity ->
                crypto.decrypt(entity.encryptedToken)?.let { decrypted ->
                    decrypted to entity.botId
                }
            }
    }

    suspend fun getDecryptedTokenForBot(botId: String): String? {
        val daoRef = tokenDao ?: return null
        val crypto = cryptoManager ?: return null
        val entity = daoRef.getActiveTokenForBotOnce(botId) ?: return null
        val now = System.currentTimeMillis()
        if (entity.expiresAtMillis != null && entity.expiresAtMillis <= now) {
            daoRef.deleteTokenById(entity.id)
            return null
        }
        return crypto.decrypt(entity.encryptedToken)
    }

    suspend fun touchAuthTokenUsed(rawToken: String) {
        val daoRef = tokenDao ?: return
        if (rawToken.isBlank()) return
        val hash = TokenCryptoManager.sha256Hex(rawToken.trim())
        val entity = daoRef.getByTokenHash(hash) ?: return
        daoRef.touchLastUsed(entity.id, System.currentTimeMillis())
    }

    suspend fun revokeTokensForBot(botId: String) {
        tokenDao?.revokeTokensForBot(botId)
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
