package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.AgentAvailabilityStatus
import com.example.data.model.AgentConfig
import com.example.data.model.AgentProvider
import com.example.data.model.BoardroomSettings
import com.example.data.model.ChatViewMode
import com.example.data.model.InAppBrowserStatus
import com.example.data.model.resolveAgentAvailability
import com.example.engine.InAppBrowserCoordinator
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.AmberOnAccent
import com.example.ui.theme.BgCanvas
import com.example.ui.theme.BgElevated
import com.example.ui.theme.BgSurface
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.SupervisorAmberBg
import com.example.ui.theme.SupervisorAmberBorder
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.parseHexColor

@Composable
fun AgentSidebarContent(
    agents: List<AgentConfig>,
    settings: BoardroomSettings,
    browserStatuses: Map<String, InAppBrowserStatus>,
    agentStatuses: Map<String, AgentAvailabilityStatus>,
    busyDetails: Map<String, String>,
    viewMode: ChatViewMode,
    selectedDirectAgentId: String?,
    onOpenBrowser: (String?) -> Unit,
    onAddAgent: () -> Unit,
    onSelectDirectAgent: (String) -> Unit,
    onEditAgent: (String) -> Unit,
    onDeleteAgent: (AgentConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val onlineCount = agents.count { agent ->
        (agentStatuses[agent.id] ?: resolveAgentAvailability(agent, browserStatuses[agent.id], false)) ==
            AgentAvailabilityStatus.ONLINE
    }
    val busyCount = agents.count { agent ->
        agentStatuses[agent.id] == AgentAvailabilityStatus.BUSY
    }
    val offlineCount = (agents.size - onlineCount - busyCount).coerceAtLeast(0)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgSurface)
    ) {
        // In-App Browser Connector Summary
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.in_app_connector).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    fontWeight = FontWeight.SemiBold
                )
                Button(
                    onClick = { onOpenBrowser(null) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberAccent,
                        contentColor = AmberOnAccent
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .height(34.dp)
                        .testTag("sidebar_open_browser_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Language,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Browser",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = BgElevated,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.testTag("sidebar_status_summary")
                    ) {
                        AgentStatusDot(
                            status = when {
                                busyCount > 0 -> AgentAvailabilityStatus.BUSY
                                onlineCount > 0 -> AgentAvailabilityStatus.ONLINE
                                else -> AgentAvailabilityStatus.OFFLINE
                            },
                            dotSize = 8.dp
                        )
                        val summaryParts = buildList {
                            add("$onlineCount online")
                            if (busyCount > 0) add("$busyCount busy")
                            if (offlineCount > 0) add("$offlineCount offline")
                        }
                        Text(
                            text = summaryParts.joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary
                        )
                    }
                    Text(
                        text = stringResource(R.string.connector_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }

        HorizontalDivider(color = BorderSubtle)

        // Connected Agents Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.connected_agents).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(
                onClick = onAddAgent,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("sidebar_add_agent_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add agent",
                    tint = AmberAccent
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("agent_roster_list"),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(agents, key = { it.id }) { agent ->
                val isSelectedDirect = viewMode == ChatViewMode.DIRECT && selectedDirectAgentId == agent.id
                val status = browserStatuses[agent.id]
                val availability = agentStatuses[agent.id]
                    ?: resolveAgentAvailability(agent, status, isBusy = false)
                val busyDetail = busyDetails[agent.id]
                val isSupervisor = agent.isSupervisor || settings.supervisorAgentId == agent.id
                AgentRosterCard(
                    agent = agent,
                    isSupervisor = isSupervisor,
                    isSelected = isSelectedDirect,
                    browserStatus = status,
                    availability = availability,
                    busyDetail = busyDetail,
                    canDelete = agents.size > 1,
                    onClick = { onSelectDirectAgent(agent.id) },
                    onOpenBrowser = { onOpenBrowser(agent.id) },
                    onEdit = { onEditAgent(agent.id) },
                    onDelete = { onDeleteAgent(agent) }
                )
            }
        }
    }
}

@Composable
private fun AgentRosterCard(
    agent: AgentConfig,
    isSupervisor: Boolean,
    isSelected: Boolean,
    browserStatus: InAppBrowserStatus?,
    availability: AgentAvailabilityStatus,
    busyDetail: String?,
    canDelete: Boolean,
    onClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val accent = parseHexColor(agent.colorHex)
    val subtitleText = if (availability == AgentAvailabilityStatus.BUSY) {
        busyDetail?.takeIf { it.isNotBlank() } ?: "Processing prompt..."
    } else {
        when (agent.provider) {
            AgentProvider.INAPP_BROWSER -> {
                val host = InAppBrowserCoordinator.hostOf(agent.customUrl).ifBlank { "Web" }
                when {
                    browserStatus?.ready == true -> "$host · Chat Ready"
                    browserStatus?.needsLogin == true -> "$host · Sign in needed"
                    browserStatus?.hasSavedProfile == true -> "$host · Saved session"
                    else -> "$host · Tap globe to connect"
                }
            }
            AgentProvider.GEMINI -> {
                val model = agent.model.ifBlank { "gemini-3.8-flash" }
                if (agent.apiKey.isNotBlank()) "Custom Key · $model" else "Firebase AI · $model"
            }
            AgentProvider.OPENAI -> {
                val model = agent.model.ifBlank { "gpt-4o-mini" }
                if (agent.apiKey.isNotBlank()) "API Ready · $model" else "API Key Required"
            }
            AgentProvider.DOCKER_WEB -> {
                val host = InAppBrowserCoordinator.hostOf(agent.customUrl)
                if (host.isNotBlank()) "Webhook · $host" else "Webhook URL needed"
            }
            AgentProvider.SIMULATION -> "Offline Demo"
        }
    }

    val borderColor = when {
        isSelected -> AmberAccent
        availability == AgentAvailabilityStatus.BUSY -> SupervisorAmberBorder
        else -> BorderSubtle
    }
    val containerColor = if (isSelected) BgElevated else BgCanvas

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(10.dp)
            .testTag("agent_card_${agent.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(modifier = Modifier.size(38.dp)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent.copy(alpha = 0.20f))
                        .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = agent.avatar.ifBlank { agent.name.take(1).uppercase() },
                        style = MaterialTheme.typography.labelLarge,
                        color = accent,
                        fontWeight = FontWeight.Bold
                    )
                }
                AgentStatusDot(
                    status = availability,
                    dotSize = 9.dp,
                    borderColor = containerColor,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = agent.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isSupervisor) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(SupervisorAmberBg)
                                .border(1.dp, SupervisorAmberBorder, RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = AmberAccent,
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                text = "CEO",
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberAccent,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    AgentStatusBadgeChip(
                        status = availability,
                        modifier = Modifier.testTag("agent_status_badge_${agent.id}")
                    )
                }
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClick,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("agent_chat_button_${agent.id}")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Chat,
                    contentDescription = "Direct chat with ${agent.name}",
                    tint = if (isSelected) AmberAccent else TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (agent.provider == AgentProvider.INAPP_BROWSER) {
                IconButton(
                    onClick = onOpenBrowser,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("agent_browser_button_${agent.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Language,
                        contentDescription = "Open ${agent.name} in In-App Browser",
                        tint = AmberAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("agent_edit_button_${agent.id}")
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "Edit ${agent.name}",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (canDelete) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("agent_delete_button_${agent.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "Remove ${agent.name}",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
