package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.AgentAvailabilityStatus
import com.example.data.model.AgentConfig
import com.example.data.model.AttachmentData
import com.example.data.model.ChatViewMode
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.AmberOnAccent
import com.example.ui.theme.BgCanvas
import com.example.ui.theme.BgElevated
import com.example.ui.theme.BgSurface
import com.example.ui.theme.BorderStrong
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.StatusErr
import com.example.ui.theme.StatusErrBorder
import com.example.ui.theme.StatusErrSoft
import com.example.ui.theme.StatusErrText
import com.example.ui.theme.StatusOk
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun ComposerBar(
    agents: List<AgentConfig>,
    agentStatuses: Map<String, AgentAvailabilityStatus>,
    viewMode: ChatViewMode,
    directAgent: AgentConfig?,
    composerText: String,
    pendingAttachment: AttachmentData?,
    isSending: Boolean,
    isRecordingVoice: Boolean,
    recordingSeconds: Int,
    isTranscribingVoice: Boolean,
    onTextChange: (String) -> Unit,
    onInsertMention: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onPickDocument: () -> Unit,
    onRemoveAttachment: () -> Unit,
    onTranscribeAttachment: () -> Unit,
    onStartVoice: () -> Unit,
    onCancelVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAttachMenu by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BgSurface)
            .navigationBarsPadding()
            .imePadding()
    ) {
        HorizontalDivider(color = BorderSubtle)

        // Quick @mention strip in Boardroom mode
        if (viewMode == ChatViewMode.BOARDROOM && agents.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MentionChip(label = "@all", status = null, onClick = { onInsertMention("@all") })
                agents.forEach { agent ->
                    MentionChip(
                        label = "@${agent.name}",
                        status = agentStatuses[agent.id] ?: AgentAvailabilityStatus.OFFLINE,
                        onClick = { onInsertMention("@${agent.name}") }
                    )
                }
            }
        }

        // Pending Attachment Preview
        if (pendingAttachment != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(BgElevated)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.AttachFile,
                    contentDescription = null,
                    tint = AmberAccent,
                    modifier = Modifier.size(16.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pendingAttachment.filename,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${pendingAttachment.sizeBytes / 1024} KB · ${pendingAttachment.mimeType}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
                if (pendingAttachment.isAudio) {
                    TextButton(
                        onClick = onTranscribeAttachment,
                        enabled = !isTranscribingVoice
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = null,
                            tint = AmberAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isTranscribingVoice) "Transcribing..." else "Transcribe",
                            color = AmberAccent
                        )
                    }
                }
                IconButton(
                    onClick = onRemoveAttachment,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Remove attachment",
                        tint = TextSecondary
                    )
                }
            }
        }

        // Active Voice Recording / Transcribing Banner
        if (isRecordingVoice || isTranscribingVoice) {
            VoiceRecordingBanner(
                isRecording = isRecordingVoice,
                recordingSeconds = recordingSeconds,
                isTranscribing = isTranscribingVoice,
                onCancel = onCancelVoice,
                onConfirm = onStopVoice
            )
        }

        // Main Input Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box {
                IconButton(
                    onClick = { showAttachMenu = true },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("attach_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.AttachFile,
                        contentDescription = "Attach image, audio, or document",
                        tint = TextSecondary
                    )
                }
                DropdownMenu(
                    expanded = showAttachMenu,
                    onDismissRequest = { showAttachMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Attach Photo / Image") },
                        leadingIcon = {
                            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null)
                        },
                        onClick = {
                            showAttachMenu = false
                            onPickPhoto()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Attach File / Document / Audio") },
                        leadingIcon = {
                            Icon(Icons.Filled.AttachFile, contentDescription = null)
                        },
                        onClick = {
                            showAttachMenu = false
                            onPickDocument()
                        }
                    )
                }
            }

            IconButton(
                onClick = if (isRecordingVoice) onStopVoice else onStartVoice,
                enabled = !isTranscribingVoice && !isSending,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (isRecordingVoice) StatusErrSoft else BgElevated)
                    .testTag("mic_button")
            ) {
                Icon(
                    imageVector = if (isRecordingVoice) Icons.Filled.Check else Icons.Filled.Mic,
                    contentDescription = if (isRecordingVoice) "Finish voice recording" else "Voice prompt",
                    tint = if (isRecordingVoice) StatusErrText else TextSecondary
                )
            }

            OutlinedTextField(
                value = composerText,
                onValueChange = onTextChange,
                placeholder = {
                    Text(
                        text = if (viewMode == ChatViewMode.BOARDROOM) {
                            stringResource(R.string.prompt_placeholder_boardroom)
                        } else {
                            "Message ${directAgent?.name ?: "agent"} directly..."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                maxLines = 4,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = BgCanvas,
                    unfocusedContainerColor = BgCanvas,
                    focusedBorderColor = AmberAccent,
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = AmberAccent
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("command_input")
            )

            Button(
                onClick = onSend,
                enabled = !isSending && !isRecordingVoice && (composerText.isNotBlank() || pendingAttachment != null),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AmberAccent,
                    contentColor = AmberOnAccent,
                    disabledContainerColor = BgElevated,
                    disabledContentColor = TextMuted
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("send_button"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send prompt"
                )
            }
        }
    }
}

@Composable
private fun MentionChip(
    label: String,
    status: AgentAvailabilityStatus?,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = BgElevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            if (status != null) {
                AgentStatusDot(status = status, dotSize = 6.dp)
            }
            Text(
                text = label,
                fontFamily = JetBrainsMonoFontFamily,
                style = MaterialTheme.typography.labelSmall,
                color = AmberAccent
            )
        }
    }
}

@Composable
private fun VoiceRecordingBanner(
    isRecording: Boolean,
    recordingSeconds: Int,
    isTranscribing: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "voice_pulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(550),
            repeatMode = RepeatMode.Reverse
        ),
        label = "voice_dot"
    )
    val mins = recordingSeconds / 60
    val secs = recordingSeconds % 60
    val formatted = "$mins:${secs.toString().padStart(2, '0')}"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isRecording) StatusErrSoft else BgElevated)
            .border(
                1.dp,
                if (isRecording) StatusErrBorder else BorderStrong,
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .alpha(pulseAlpha)
                .clip(CircleShape)
                .background(if (isRecording) StatusErr else StatusOk)
        )
        Text(
            text = if (isRecording) "Recording voice prompt" else "Transcribing speech with Gemini...",
            style = MaterialTheme.typography.bodySmall,
            color = if (isRecording) StatusErrText else TextPrimary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (isRecording) {
            Text(
                text = formatted,
                fontFamily = JetBrainsMonoFontFamily,
                style = MaterialTheme.typography.labelMedium,
                color = StatusErrText
            )
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Cancel voice recording",
                    tint = StatusErrText
                )
            }
            IconButton(
                onClick = onConfirm,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Stop and transcribe voice",
                    tint = StatusOk
                )
            }
        }
    }
}
