package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.model.AgentAvailabilityStatus
import com.example.data.model.AgentConfig
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.BgCanvas
import com.example.ui.theme.BgElevated
import com.example.ui.theme.BgSurface
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.StatusErr
import com.example.ui.theme.StatusErrBorder
import com.example.ui.theme.StatusErrSoft
import com.example.ui.theme.StatusErrText
import com.example.ui.theme.StatusOk
import com.example.ui.theme.SupervisorAmberBg
import com.example.ui.theme.SupervisorAmberBorder
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.parseHexColor

@Composable
fun agentStatusColors(status: AgentAvailabilityStatus): AgentStatusStyle {
    val dotColor by animateColorAsState(
        targetValue = when (status) {
            AgentAvailabilityStatus.ONLINE -> StatusOk
            AgentAvailabilityStatus.BUSY -> AmberAccent
            AgentAvailabilityStatus.OFFLINE -> StatusErr
        },
        animationSpec = spring(),
        label = "status_dot_color"
    )
    return when (status) {
        AgentAvailabilityStatus.ONLINE -> AgentStatusStyle(
            dotColor = dotColor,
            textColor = StatusOk,
            containerColor = StatusOk.copy(alpha = 0.14f),
            borderColor = StatusOk.copy(alpha = 0.45f)
        )
        AgentAvailabilityStatus.BUSY -> AgentStatusStyle(
            dotColor = dotColor,
            textColor = AmberAccent,
            containerColor = SupervisorAmberBg,
            borderColor = SupervisorAmberBorder
        )
        AgentAvailabilityStatus.OFFLINE -> AgentStatusStyle(
            dotColor = dotColor,
            textColor = StatusErrText,
            containerColor = StatusErrSoft,
            borderColor = StatusErrBorder
        )
    }
}

data class AgentStatusStyle(
    val dotColor: Color,
    val textColor: Color,
    val containerColor: Color,
    val borderColor: Color
)

@Composable
fun AgentStatusDot(
    status: AgentAvailabilityStatus,
    modifier: Modifier = Modifier,
    dotSize: Dp = 8.dp,
    borderColor: Color? = null
) {
    val style = agentStatusColors(status)
    val transition = rememberInfiniteTransition(label = "agent_status_pulse")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(750),
            repeatMode = RepeatMode.Reverse
        ),
        label = "busy_pulse_scale"
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(750),
            repeatMode = RepeatMode.Reverse
        ),
        label = "busy_pulse_alpha"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(dotSize + 6.dp)
            .semantics {
                contentDescription = "Status: ${status.label}"
            }
    ) {
        if (status == AgentAvailabilityStatus.BUSY) {
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .scale(pulseScale)
                    .alpha(pulseAlpha)
                    .clip(CircleShape)
                    .background(AmberAccent)
            )
        } else if (status == AgentAvailabilityStatus.ONLINE) {
            Box(
                modifier = Modifier
                    .size(dotSize + 4.dp)
                    .clip(CircleShape)
                    .background(StatusOk.copy(alpha = 0.22f))
            )
        }

        val innerMod = Modifier
            .size(dotSize)
            .clip(CircleShape)
            .background(style.dotColor)
            .let { base ->
                if (borderColor != null) {
                    base.border(1.5.dp, borderColor, CircleShape)
                } else {
                    base
                }
            }
        Box(modifier = innerMod)
    }
}

@Composable
fun AgentStatusBadgeChip(
    status: AgentAvailabilityStatus,
    detailText: String? = null,
    modifier: Modifier = Modifier
) {
    val style = agentStatusColors(status)
    val label = if (status == AgentAvailabilityStatus.BUSY && !detailText.isNullOrBlank()) {
        detailText
    } else {
        status.label
    }

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = style.containerColor,
        border = BorderStroke(1.dp, style.borderColor),
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "Agent status $label"
        }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
            AgentStatusDot(status = status, dotSize = 6.dp)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = style.textColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun BoardroomLiveRosterStrip(
    agents: List<AgentConfig>,
    agentStatuses: Map<String, AgentAvailabilityStatus>,
    busyDetails: Map<String, String>,
    selectedDirectAgentId: String?,
    isDirectMode: Boolean,
    onSelectAgent: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (agents.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(BgSurface.copy(alpha = 0.55f))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("live_agent_status_strip"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        agents.forEach { agent ->
            val status = agentStatuses[agent.id] ?: AgentAvailabilityStatus.OFFLINE
            val style = agentStatusColors(status)
            val accent = parseHexColor(agent.colorHex)
            val isHighlighted = isDirectMode && selectedDirectAgentId == agent.id
            val busyLabel = busyDetails[agent.id]

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isHighlighted) BgElevated else BgCanvas,
                border = BorderStroke(
                    width = 1.dp,
                    color = when {
                        isHighlighted -> AmberAccent
                        status == AgentAvailabilityStatus.BUSY -> SupervisorAmberBorder
                        else -> BorderSubtle
                    }
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelectAgent(agent.id) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${agent.name}, ${status.label}"
                    }
                    .testTag("live_status_pill_${agent.id}")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(accent.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = agent.avatar.ifBlank { agent.name.take(1).uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = agent.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    AgentStatusDot(status = status, dotSize = 7.dp)

                    Text(
                        text = if (status == AgentAvailabilityStatus.BUSY && !busyLabel.isNullOrBlank()) {
                            busyLabel
                        } else {
                            status.label
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = style.textColor,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
