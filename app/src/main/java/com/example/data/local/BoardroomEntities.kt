package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.AgentConfig
import com.example.data.model.AgentProvider
import com.example.data.model.AttachmentData
import com.example.data.model.BoardroomMessage
import com.example.data.model.BoardroomSettings
import com.example.data.model.BridgeAuthTokenRecord
import com.example.data.model.CollaborationRole

@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val role: String,
    val avatar: String,
    val colorHex: String,
    val providerId: String,
    val customUrl: String,
    val apiKey: String,
    val model: String,
    val endpoint: String,
    val systemPrompt: String,
    val isSupervisor: Boolean,
    val sortOrder: Int,
    val enabled: Boolean = true,
    val selectedForBroadcast: Boolean = true,
    val bridgeBotId: String = ""
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val sender: String,
    val role: String,
    val senderName: String,
    val avatar: String,
    val colorHex: String,
    val text: String,
    val attachmentFilename: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentUri: String? = null,
    val attachmentBase64: String? = null,
    val attachmentTextPreview: String? = null,
    val attachmentSizeBytes: Long = 0L,
    val mode: String,
    val targetAgentId: String,
    val rootTargetAgentId: String? = null,
    val rootMessageId: String? = null,
    val hop: Int = 0,
    val maxHops: Int = 3,
    val isSupervisor: Boolean = false,
    val collaborationRoleId: String = "none",
    val fromAgentName: String? = null,
    val timestampMillis: Long,
    val parentMessageId: String? = null,
    val deliveryTaskId: String? = null,
    val dispatchBatchId: String? = null
)

@Entity(tableName = "boardroom_settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val companyName: String,
    val ceoName: String,
    val supervisorAgentId: String?,
    val collaborationEnabled: Boolean,
    val maxCollaborationHops: Int,
    val bridgeServerUrl: String = "",
    val bridgeAuthToken: String = ""
)

fun AgentEntity.toDomain(): AgentConfig = AgentConfig(
    id = id,
    name = name,
    role = role,
    avatar = avatar,
    colorHex = colorHex,
    provider = AgentProvider.fromId(providerId),
    customUrl = customUrl,
    apiKey = apiKey,
    model = model,
    endpoint = endpoint,
    systemPrompt = systemPrompt,
    isSupervisor = isSupervisor,
    sortOrder = sortOrder,
    enabled = enabled,
    selectedForBroadcast = selectedForBroadcast,
    bridgeBotId = bridgeBotId
)

fun AgentConfig.toEntity(): AgentEntity = AgentEntity(
    id = id,
    name = name,
    role = role,
    avatar = avatar,
    colorHex = colorHex,
    providerId = provider.id,
    customUrl = customUrl,
    apiKey = apiKey,
    model = model,
    endpoint = endpoint,
    systemPrompt = systemPrompt,
    isSupervisor = isSupervisor,
    sortOrder = sortOrder,
    enabled = enabled,
    selectedForBroadcast = selectedForBroadcast,
    bridgeBotId = bridgeBotId
)

fun MessageEntity.toDomain(): BoardroomMessage = BoardroomMessage(
    id = id,
    sender = sender,
    role = role,
    senderName = senderName,
    avatar = avatar,
    colorHex = colorHex,
    text = text,
    attachment = if (!attachmentFilename.isNullOrBlank()) {
        AttachmentData(
            filename = attachmentFilename,
            mimeType = attachmentMimeType.orEmpty(),
            uriString = attachmentUri.orEmpty(),
            base64Data = attachmentBase64.orEmpty(),
            textPreview = attachmentTextPreview.orEmpty(),
            sizeBytes = attachmentSizeBytes
        )
    } else null,
    mode = mode,
    targetAgentId = targetAgentId,
    rootTargetAgentId = rootTargetAgentId,
    rootMessageId = rootMessageId,
    hop = hop,
    maxHops = maxHops,
    isSupervisor = isSupervisor,
    collaborationRole = CollaborationRole.fromId(collaborationRoleId),
    fromAgentName = fromAgentName,
    timestampMillis = timestampMillis,
    parentMessageId = parentMessageId,
    deliveryTaskId = deliveryTaskId,
    dispatchBatchId = dispatchBatchId
)

fun BoardroomMessage.toEntity(): MessageEntity = MessageEntity(
    id = id,
    sender = sender,
    role = role,
    senderName = senderName,
    avatar = avatar,
    colorHex = colorHex,
    text = text,
    attachmentFilename = attachment?.filename,
    attachmentMimeType = attachment?.mimeType,
    attachmentUri = attachment?.uriString,
    attachmentBase64 = attachment?.base64Data,
    attachmentTextPreview = attachment?.textPreview,
    attachmentSizeBytes = attachment?.sizeBytes ?: 0L,
    mode = mode,
    targetAgentId = targetAgentId,
    rootTargetAgentId = rootTargetAgentId,
    rootMessageId = rootMessageId,
    hop = hop,
    maxHops = maxHops,
    isSupervisor = isSupervisor,
    collaborationRoleId = collaborationRole.id,
    fromAgentName = fromAgentName,
    timestampMillis = timestampMillis,
    parentMessageId = parentMessageId,
    deliveryTaskId = deliveryTaskId,
    dispatchBatchId = dispatchBatchId
)

fun SettingsEntity.toDomain(): BoardroomSettings = BoardroomSettings(
    companyName = companyName,
    ceoName = ceoName,
    supervisorAgentId = supervisorAgentId,
    collaborationEnabled = collaborationEnabled,
    maxCollaborationHops = maxCollaborationHops,
    bridgeServerUrl = bridgeServerUrl,
    bridgeAuthToken = bridgeAuthToken
)

fun BoardroomSettings.toEntity(): SettingsEntity = SettingsEntity(
    id = 1,
    companyName = companyName,
    ceoName = ceoName,
    supervisorAgentId = supervisorAgentId,
    collaborationEnabled = collaborationEnabled,
    maxCollaborationHops = maxCollaborationHops,
    bridgeServerUrl = bridgeServerUrl,
    bridgeAuthToken = bridgeAuthToken
)

@Entity(
    tableName = "bridge_auth_tokens",
    indices = [
        Index(value = ["botId"]),
        Index(value = ["isActive"]),
        Index(value = ["tokenHash"])
    ]
)
data class BridgeAuthTokenEntity(
    @PrimaryKey val id: String,
    val botId: String,
    val serverUrl: String,
    val encryptedToken: String,
    val tokenHash: String,
    val tokenPreview: String,
    val isActive: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val lastUsedAtMillis: Long = System.currentTimeMillis(),
    val expiresAtMillis: Long? = null
)

fun BridgeAuthTokenEntity.toDomain(): BridgeAuthTokenRecord = BridgeAuthTokenRecord(
    id = id,
    botId = botId,
    serverUrl = serverUrl,
    tokenPreview = tokenPreview,
    tokenHash = tokenHash,
    isActive = isActive,
    createdAtMillis = createdAtMillis,
    lastUsedAtMillis = lastUsedAtMillis,
    expiresAtMillis = expiresAtMillis
)
