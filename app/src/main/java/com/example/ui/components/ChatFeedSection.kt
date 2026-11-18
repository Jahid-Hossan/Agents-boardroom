package com.example.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.AgentAvailabilityStatus
import com.example.data.model.AgentConfig
import com.example.data.model.AttachmentData
import com.example.data.model.BoardroomMessage
import com.example.data.model.ChatViewMode
import com.example.data.model.CollaborationRole
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.AmberOnAccent
import com.example.ui.theme.BgCanvas
import com.example.ui.theme.BgElevated
import com.example.ui.theme.BgSurface
import com.example.ui.theme.BorderStrong
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.HandoffBlueBg
import com.example.ui.theme.HandoffBlueBorder
import com.example.ui.theme.HandoffBlueText
import com.example.ui.theme.SupervisorAmberBg
import com.example.ui.theme.SupervisorAmberBorder
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.parseHexColor
import java.text.DateFormat
import java.util.Date

@Composable
fun ChatFeedSection(
    agents: List<AgentConfig>,
    agentStatuses: Map<String, AgentAvailabilityStatus>,
    busyDetails: Map<String, String>,
    streamingDrafts: Map<String, String> = emptyMap(),
    messages: List<BoardroomMessage>,
    viewMode: ChatViewMode,
    directAgent: AgentConfig?,
    thinkingStatus: String?,
    onSelectDirectAgent: (String) -> Unit,
    onReturnToBoardroom: () -> Unit,
    onOpenInAppBrowser: () -> Unit,
    onOpenManageAgents: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visibleMessages = remember(messages, viewMode, directAgent?.id) {
        if (viewMode == ChatViewMode.BOARDROOM) {
            messages.filter { msg ->
                msg.mode != "direct" && msg.targetAgentId == "all"
            }
        } else {
            val targetId = directAgent?.id
            messages.filter { msg ->
                msg.sender == "system" ||
                    msg.sender == targetId ||
                    (msg.sender == "ceo" && msg.targetAgentId == targetId) ||
                    msg.rootTargetAgentId == targetId
            }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(visibleMessages.size, thinkingStatus) {
        if (visibleMessages.isNotEmpty()) {
            listState.animateScrollToItem(visibleMessages.size - 1)
        }
    }

    val onlineCount = agents.count { agentStatuses[it.id] == AgentAvailabilityStatus.ONLINE }
    val busyCount = agents.count { agentStatuses[it.id] == AgentAvailabilityStatus.BUSY }

    Column(modifier = modifier.fillMaxSize()) {
        // Context Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface.copy(alpha = 0.7f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (viewMode == ChatViewMode.BOARDROOM) {
                            stringResource(R.string.boardroom_broadcast)
                        } else {
                            "Direct Chat · ${directAgent?.name ?: "Agent"}"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (viewMode == ChatViewMode.DIRECT && directAgent != null) {
                        val directStatus = agentStatuses[directAgent.id] ?: AgentAvailabilityStatus.OFFLINE
                        AgentStatusBadgeChip(
                            status = directStatus,
                            detailText = busyDetails[directAgent.id]
                        )
                    }
                }
                val summaryLine = if (viewMode == ChatViewMode.BOARDROOM) {
                    buildString {
                        append("$onlineCount/${agents.size} online")
                        if (busyCount > 0) append(" · $busyCount busy")
                    }
                } else {
                    "1-on-1 channel with ${directAgent?.name ?: "selected agent"}"
                }
                Text(
                    text = summaryLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (viewMode == ChatViewMode.DIRECT) {
                IconButton(
                    onClick = onReturnToBoardroom,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("return_boardroom_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Return to Boardroom",
                        tint = AmberAccent
                    )
                }
            }
        }
        HorizontalDivider(color = BorderSubtle)

        BoardroomLiveRosterStrip(
            agents = agents,
            agentStatuses = agentStatuses,
            busyDetails = busyDetails,
            selectedDirectAgentId = directAgent?.id,
            isDirectMode = viewMode == ChatViewMode.DIRECT,
            onSelectAgent = onSelectDirectAgent
        )
        HorizontalDivider(color = BorderSubtle)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("chat_feed_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (visibleMessages.size <= 1) {
                item(key = "onboarding_hero") {
                    OnboardingHeroCard(
                        onOpenBrowser = onOpenInAppBrowser,
                        onOpenManage = onOpenManageAgents
                    )
                }
            }

            items(visibleMessages, key = { it.id }) { msg ->
                MessageCard(message = msg)
            }

            streamingDrafts.entries.forEach { (agentId, draftText) ->
                val agent = agents.firstOrNull { it.id == agentId }
                if (agent != null && draftText.isNotBlank() &&
                    (viewMode == ChatViewMode.BOARDROOM || directAgent?.id == agentId)
                ) {
                    item(key = "stream_draft_$agentId") {
                        MessageCard(
                            message = BoardroomMessage(
                                id = "draft-$agentId",
                                sender = agent.id,
                                role = "${agent.role.ifBlank { agent.provider.displayLabel }} · Live Stream",
                                senderName = agent.name,
                                avatar = agent.avatar,
                                colorHex = agent.colorHex,
                                text = draftText
                            )
                        )
                    }
                }
            }

            if (!thinkingStatus.isNullOrBlank()) {
                item(key = "thinking_banner") {
                    ThinkingStatusBanner(status = thinkingStatus)
                }
            }
        }
    }
}

@Composable
private fun OnboardingHeroCard(
    onOpenBrowser: () -> Unit,
    onOpenManage: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BgSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 760.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(
                painter = painterResource(id = R.drawable.img_boardroom_hero_1794987638021),
                contentDescription = "Muse Boardroom orchestration banner",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            )
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.onboarding_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Text(
                    text = stringResource(R.string.onboarding_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Button(
                        onClick = onOpenBrowser,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AmberAccent,
                            contentColor = AmberOnAccent
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("hero_open_browser_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Language,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("In-App Browser")
                    }
                    OutlinedButton(
                        onClick = onOpenManage,
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderStrong),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("hero_manage_agents_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Configure Agents", color = TextPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageCard(message: BoardroomMessage) {
    val isUser = message.sender == "ceo"
    val isSystem = message.sender == "system"
    val accent = if (isUser) AmberAccent else parseHexColor(message.colorHex)

    val containerColor = when {
        isSystem -> BgElevated.copy(alpha = 0.55f)
        isUser -> Color(0x14F59E0B)
        else -> BgSurface
    }
    val borderColor = when {
        message.collaborationRole == CollaborationRole.SUPERVISOR_PLAN ||
            message.collaborationRole == CollaborationRole.SUPERVISOR_FINAL -> SupervisorAmberBorder
        isUser -> AmberAccent.copy(alpha = 0.45f)
        else -> BorderSubtle
    }

    val timeLabel = remember(message.timestampMillis) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.timestampMillis))
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 820.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent.copy(alpha = 0.22f))
                        .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = message.avatar.ifBlank { message.senderName.take(1).uppercase() },
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = message.senderName,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        if (message.role.isNotBlank()) {
                            Text(
                                text = message.role,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    CollaborationBadgeRow(message = message)
                }

                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }

            if (message.attachment != null) {
                MessageAttachmentPreview(attachment = message.attachment)
            }

            if (message.text.isNotBlank()) {
                MarkdownText(markdown = message.text)
            }
        }
    }
}

@Composable
private fun CollaborationBadgeRow(message: BoardroomMessage) {
    val badgeInfo: Pair<String, Boolean>? = when (message.collaborationRole) {
        CollaborationRole.SUPERVISOR_PLAN -> "CEO Delegation" to true
        CollaborationRole.SUPERVISOR_FINAL -> "CEO Final Synthesis" to true
        CollaborationRole.HANDOFF -> {
            val fromPart = if (!message.fromAgentName.isNullOrBlank()) "from @${message.fromAgentName} · " else ""
            "Handoff ${fromPart}Round ${message.hop}/${message.maxHops}" to false
        }
        CollaborationRole.NONE, CollaborationRole.PRIMARY -> {
            when {
                message.isSupervisor && message.sender != "ceo" -> "CEO / Supervisor" to true
                !message.deliveryTaskId.isNullOrBlank() -> "Bridge · Task ${message.deliveryTaskId.take(10)}" to false
                else -> null
            }
        }
    }

    if (badgeInfo != null) {
        val (label, isSup) = badgeInfo
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (isSup) SupervisorAmberBg else HandoffBlueBg)
                .border(
                    1.dp,
                    if (isSup) SupervisorAmberBorder else HandoffBlueBorder,
                    RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSup) AmberAccent else HandoffBlueText
            )
        }
    }
}

@Composable
private fun MessageAttachmentPreview(attachment: AttachmentData) {
    if (attachment.isImage && attachment.base64Data.isNotBlank()) {
        val bitmap = remember(attachment.base64Data) {
            try {
                val bytes = Base64.decode(attachment.base64Data, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) {
                null
            }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = attachment.filename,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(BgCanvas)
            )
            return
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BgElevated)
            .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.AttachFile,
            contentDescription = null,
            tint = AmberAccent,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = attachment.filename,
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary
        )
    }
}

@Composable
private fun ThinkingStatusBanner(status: String) {
    val transition = rememberInfiniteTransition(label = "thinking_pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "thinking_dot_alpha"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgElevated)
            .border(1.dp, BorderStrong, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("thinking_status_banner")
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .alpha(alpha)
                .clip(CircleShape)
                .background(AmberAccent)
        )
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}
