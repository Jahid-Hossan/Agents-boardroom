package com.example.ui.screens

import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.AgentConfig
import com.example.data.model.DEFAULT_AI_PRESETS
import com.example.data.model.InAppBrowserStatus
import com.example.engine.InAppBrowserCoordinator
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.AmberOnAccent
import com.example.ui.theme.BgCanvas
import com.example.ui.theme.BgElevated
import com.example.ui.theme.BgSurface
import com.example.ui.theme.BorderStrong
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.StatusOk
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun InAppBrowserDialog(
    agents: List<AgentConfig>,
    activeAgentId: String?,
    statuses: Map<String, InAppBrowserStatus>,
    coordinator: InAppBrowserCoordinator,
    onSelectAgent: (String) -> Unit,
    onAddAccount: () -> Unit,
    onNavigate: (AgentConfig, String, String) -> Unit,
    onRefreshStatus: (AgentConfig) -> Unit,
    onInjectQuickText: (AgentConfig, String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val activeAgent = agents.firstOrNull { it.id == activeAgentId } ?: agents.firstOrNull()
    val status = activeAgent?.let { statuses[it.id] }

    var urlInput by remember(activeAgent?.id) {
        mutableStateOf(
            status?.currentUrl?.takeIf { it.isNotBlank() }
                ?: activeAgent?.customUrl.orEmpty()
        )
    }
    var quickText by remember { mutableStateOf("") }
    var activeWebView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(activeAgent?.id) {
        if (activeAgent != null) {
            urlInput = statuses[activeAgent.id]?.currentUrl?.takeIf { it.isNotBlank() }
                ?: activeAgent.customUrl
            activeWebView = coordinator.getOrCreateWebView(activeAgent)
        }
    }

    LaunchedEffect(status?.currentUrl) {
        val current = status?.currentUrl
        if (!current.isNullOrBlank()) {
            urlInput = current
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = BgSurface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, BorderStrong),
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .testTag("inapp_browser_dialog")
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "In-App AI Browser",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        val statusLabel = when {
                            status?.ready == true -> "Chat Ready · Session active"
                            status?.needsLogin == true -> "Sign in on page to connect"
                            status?.hasSavedProfile == true -> "Saved session loaded"
                            else -> "Sign in once — cookies stay saved"
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (status?.ready == true) StatusOk else AmberAccent)
                            )
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("close_browser_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close In-App Browser",
                            tint = TextSecondary
                        )
                    }
                }

                // Agent session tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    agents.forEach { agent ->
                        val st = statuses[agent.id]
                        FilterChip(
                            selected = agent.id == activeAgent?.id,
                            onClick = { onSelectAgent(agent.id) },
                            label = { Text(agent.name) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (st?.ready == true || st?.hasSavedProfile == true) StatusOk else TextMuted
                                        )
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AmberAccent.copy(alpha = 0.2f),
                                selectedLabelColor = AmberAccent
                            )
                        )
                    }
                    OutlinedButton(
                        onClick = onAddAccount,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Account")
                    }
                }

                if (activeAgent != null) {
                    // Navigation + URL Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = { onNavigate(activeAgent, "back", "") },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TextPrimary
                            )
                        }
                        IconButton(
                            onClick = { onNavigate(activeAgent, "reload", "") },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Reload",
                                tint = TextPrimary
                            )
                        }
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            singleLine = true,
                            placeholder = { Text("https://muse.ai", color = TextMuted) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = BgCanvas,
                                unfocusedContainerColor = BgCanvas,
                                focusedBorderColor = AmberAccent,
                                unfocusedBorderColor = BorderSubtle,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { onNavigate(activeAgent, "goto", urlInput) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AmberAccent,
                                contentColor = AmberOnAccent
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("Go", fontWeight = FontWeight.Bold)
                        }
                        IconButton(
                            onClick = { onRefreshStatus(activeAgent) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Verified,
                                contentDescription = "Check chat ready state",
                                tint = StatusOk
                            )
                        }
                    }

                    // Quick AI site chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DEFAULT_AI_PRESETS.forEach { preset ->
                            OutlinedButton(
                                onClick = {
                                    urlInput = preset.url
                                    onNavigate(activeAgent, "goto", preset.url)
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(preset.label, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    HorizontalDivider(color = BorderSubtle)

                    // Hosted WebView surface
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(BgCanvas)
                    ) {
                        val wv = activeWebView
                        if (wv != null) {
                            AndroidView(
                                factory = { ctx ->
                                    FrameLayout(ctx).apply {
                                        (wv.parent as? ViewGroup)?.removeView(wv)
                                        addView(
                                            wv,
                                            FrameLayout.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                        )
                                    }
                                },
                                update = { container ->
                                    val first = container.getChildAt(0)
                                    if (first !== wv) {
                                        container.removeAllViews()
                                        (wv.parent as? ViewGroup)?.removeView(wv)
                                        container.addView(
                                            wv,
                                            FrameLayout.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    HorizontalDivider(color = BorderSubtle)

                    // Bottom quick text / OTP / Enter helper bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgElevated)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = quickText,
                            onValueChange = { quickText = it },
                            singleLine = true,
                            placeholder = {
                                Text(
                                    "Quick type email / OTP into focused page field...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = BgCanvas,
                                unfocusedContainerColor = BgCanvas,
                                focusedBorderColor = AmberAccent,
                                unfocusedBorderColor = BorderSubtle,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = {
                                if (quickText.isNotEmpty()) {
                                    onInjectQuickText(activeAgent, quickText, false)
                                    quickText = ""
                                }
                            },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("Type")
                        }
                        Button(
                            onClick = {
                                onInjectQuickText(activeAgent, quickText, true)
                                quickText = ""
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AmberAccent,
                                contentColor = AmberOnAccent
                            ),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardReturn,
                                contentDescription = "Send Enter key",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
