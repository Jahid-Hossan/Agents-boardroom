package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.AgentAvailabilityStatus
import com.example.data.model.AgentConfig
import com.example.data.model.AgentProvider
import com.example.data.model.BoardroomSettings
import com.example.data.model.InAppBrowserStatus
import com.example.data.model.MuseConnectionStatus
import com.example.engine.InAppBrowserCoordinator
import com.example.ui.components.AgentStatusBadgeChip
import com.example.ui.components.AgentStatusDot
import com.example.ui.components.MuseConnectionStatusCard
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
import com.example.ui.theme.parseHexColor

private enum class BotFilterTab(val label: String) {
    ALL("All"),
    ONLINE("Online"),
    BROADCAST("In Broadcast")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotsScreen(
    bots: List<AgentConfig>,
    agentStatuses: Map<String, AgentAvailabilityStatus>,
    browserStatuses: Map<String, InAppBrowserStatus>,
    busyDetails: Map<String, String>,
    errorDetails: Map<String, String>,
    settings: BoardroomSettings,
    bridgeSseConnected: Boolean,
    isSyncingBridge: Boolean,
    embeddedBridgeRunning: Boolean = false,
    embeddedBridgeUrl: String = "",
    isCreatingBridgeBot: Boolean = false,
    museConnectionStatus: MuseConnectionStatus = MuseConnectionStatus(),
    storedAuthTokenCount: Int = 0,
    onRefreshMuseConnection: () -> Unit = {},
    onCreateBotBridge: () -> Unit = {},
    onShowBotToken: (String) -> Unit = {},
    onToggleEmbeddedServer: (Boolean) -> Unit = {},
    onToggleBroadcast: (String, Boolean) -> Unit,
    onToggleBotEnabled: (String, Boolean) -> Unit,
    onSetAllBroadcast: (Boolean) -> Unit,
    onSyncBridge: () -> Unit,
    onAddBot: () -> Unit,
    onEditBot: (String) -> Unit,
    onOpenBrowserForBot: (String) -> Unit,
    onInitBridgeBot: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    var selectedFilter by remember { mutableStateOf(BotFilterTab.ALL) }

    val onlineCount = remember(bots, agentStatuses) {
        bots.count { agentStatuses[it.id] == AgentAvailabilityStatus.ONLINE }
    }
    val broadcastCount = remember(bots) {
        bots.count { it.enabled && it.selectedForBroadcast }
    }
    val filteredBots = remember(bots, agentStatuses, selectedFilter) {
        when (selectedFilter) {
            BotFilterTab.ALL -> bots
            BotFilterTab.ONLINE -> bots.filter { agentStatuses[it.id] == AgentAvailabilityStatus.ONLINE }
            BotFilterTab.BROADCAST -> bots.filter { it.enabled && it.selectedForBroadcast }
        }
    }

    Scaffold(
        containerColor = BgCanvas,
        contentWindowInsets = WindowInsets.safeDrawing,
        modifier = modifier
            .fillMaxSize()
            .testTag("bots_screen"),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgSurface,
                    titleContentColor = TextPrimary
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("bots_screen_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Boardroom",
                            tint = TextPrimary
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.configured_bots),
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${bots.size} configured · $onlineCount online · $broadcastCount in broadcast",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    if (settings.bridgeServerUrl.isNotBlank()) {
                        IconButton(
                            onClick = onSyncBridge,
                            enabled = !isSyncingBridge,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("bots_screen_sync_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Sync,
                                contentDescription = "Sync Multi-Bot Bridge",
                                tint = AmberAccent
                            )
                        }
                    }
                    IconButton(
                        onClick = onAddBot,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("bots_screen_add_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add new bot",
                            tint = AmberAccent
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 780.dp)
            ) {
                Spacer(modifier = Modifier.height(10.dp))

                MuseConnectionStatusCard(
                    status = museConnectionStatus,
                    storedTokenCount = storedAuthTokenCount,
                    compact = false,
                    onRefreshConnection = onRefreshMuseConnection
                )

                // Filter Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BotFilterTab.entries.forEach { tab ->
                        FilterChip(
                            selected = selectedFilter == tab,
                            onClick = { selectedFilter = tab },
                            label = { Text(tab.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AmberAccent.copy(alpha = 0.2f),
                                selectedLabelColor = AmberAccent
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("bots_filter_${tab.name.lowercase()}")
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("bots_screen_list"),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "broadcast_stream_summary_card") {
                        // Broadcast Stream Summary & Bulk Toggle Card
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = BgSurface,
                            border = BorderStroke(1.dp, BorderSubtle),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Hub,
                                            contentDescription = null,
                                            tint = if (embeddedBridgeRunning) StatusOk else AmberAccent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Column {
                                            Text(
                                                text = stringResource(R.string.broadcast_stream_title),
                                                style = MaterialTheme.typography.titleSmall,
                                                color = TextPrimary
                                            )
                                            val bridgeLine = when {
                                                embeddedBridgeRunning && embeddedBridgeUrl.isNotBlank() ->
                                                    "In-App Bridge Live · ${embeddedBridgeUrl.removePrefix("http://")} · $broadcastCount/${bots.size} active"
                                                settings.bridgeServerUrl.isNotBlank() && bridgeSseConnected ->
                                                    "Bridge SSE connected · $broadcastCount/${bots.size} active"
                                                settings.bridgeServerUrl.isNotBlank() ->
                                                    "Bridge configured · $broadcastCount/${bots.size} active"
                                                else -> "Toggle which bots receive boardroom broadcasts"
                                            }
                                            Text(
                                                text = bridgeLine,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (embeddedBridgeRunning) StatusOk else TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { onToggleEmbeddedServer(!embeddedBridgeRunning) },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("toggle_embedded_bridge_server_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.PowerSettingsNew,
                                            contentDescription = if (embeddedBridgeRunning) "Stop In-App Bridge Server" else "Start In-App Bridge Server",
                                            tint = if (embeddedBridgeRunning) StatusOk else TextMuted
                                        )
                                    }
                                }

                                Button(
                                    onClick = onCreateBotBridge,
                                    enabled = !isCreatingBridgeBot,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AmberAccent,
                                        contentColor = AmberOnAccent
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("create_bot_bridge_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Hub,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isCreatingBridgeBot) "Connecting Bridge Server..." else stringResource(R.string.create_bot_bridge),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { onSetAllBroadcast(true) },
                                        border = BorderStroke(1.dp, BorderStrong),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("bots_enable_all_broadcast_button")
                                    ) {
                                        Text("Enable All", color = AmberAccent, fontWeight = FontWeight.SemiBold)
                                    }
                                    OutlinedButton(
                                        onClick = { onSetAllBroadcast(false) },
                                        border = BorderStroke(1.dp, BorderSubtle),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("bots_disable_all_broadcast_button")
                                    ) {
                                        Text("Mute All", color = TextSecondary)
                                    }
                                }
                            }
                        }
                    }

                    if (filteredBots.isEmpty()) {
                        item(key = "bots_empty_state") {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = BgSurface,
                                border = BorderStroke(1.dp, BorderSubtle),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .testTag("bots_empty_state")
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Hub,
                                        contentDescription = null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Text(
                                        text = if (bots.isEmpty()) {
                                            "No bots configured yet"
                                        } else {
                                            "No bots match this filter"
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = if (bots.isEmpty()) {
                                            "Add a Muse AI, Gemini, ChatGPT, or Multi-Bot Bridge account to get started."
                                        } else {
                                            "Switch filters or enable a bot to include it in the broadcast stream."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                    if (bots.isEmpty()) {
                                        Button(
                                            onClick = onAddBot,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = AmberAccent,
                                                contentColor = AmberOnAccent
                                            ),
                                            modifier = Modifier.height(48.dp)
                                        ) {
                                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Add Bot")
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        items(filteredBots, key = { it.id }) { bot ->
                            val status = agentStatuses[bot.id] ?: AgentAvailabilityStatus.OFFLINE
                            val browserStatus = browserStatuses[bot.id]
                            BotScreenCard(
                                bot = bot,
                                status = status,
                                browserStatus = browserStatus,
                                busyDetail = busyDetails[bot.id],
                                errorDetail = errorDetails[bot.id],
                                onToggleBroadcast = { checked -> onToggleBroadcast(bot.id, checked) },
                                onToggleEnabled = { enabled -> onToggleBotEnabled(bot.id, enabled) },
                                onEdit = { onEditBot(bot.id) },
                                onOpenBrowser = { onOpenBrowserForBot(bot.id) },
                                onInitBridge = { onInitBridgeBot(bot.id) },
                                onShowToken = { onShowBotToken(bot.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BotScreenCard(
    bot: AgentConfig,
    status: AgentAvailabilityStatus,
    browserStatus: InAppBrowserStatus?,
    busyDetail: String?,
    errorDetail: String?,
    onToggleBroadcast: (Boolean) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onOpenBrowser: () -> Unit,
    onInitBridge: () -> Unit,
    onShowToken: () -> Unit
) {
    val accent = parseHexColor(bot.colorHex)
    val broadcastActive = bot.enabled && bot.selectedForBroadcast
    val statusDetail = when {
        !bot.enabled -> "Bot disabled"
        status == AgentAvailabilityStatus.BUSY -> busyDetail?.takeIf { it.isNotBlank() } ?: "Processing request..."
        status == AgentAvailabilityStatus.ERROR && !errorDetail.isNullOrBlank() -> errorDetail
        status == AgentAvailabilityStatus.NEEDS_AUTH -> "Authentication required before receiving prompts"
        bot.provider == AgentProvider.MUSE_BRIDGE -> {
            val remoteId = bot.bridgeBotId.ifBlank { bot.id }
            if (status == AgentAvailabilityStatus.ONLINE) "Bridge ($remoteId) · Connected" else "Bridge ($remoteId)"
        }
        bot.provider == AgentProvider.INAPP_BROWSER -> {
            val host = InAppBrowserCoordinator.hostOf(bot.customUrl).ifBlank { "Web" }
            if (browserStatus?.ready == true) "$host · Session ready" else "$host · Browser session"
        }
        else -> bot.role.ifBlank { bot.provider.displayLabel }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BgSurface,
        border = BorderStroke(
            width = 1.dp,
            color = if (broadcastActive) BorderStrong else BorderSubtle
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bot_screen_card_${bot.id}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(modifier = Modifier.size(44.dp)) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent.copy(alpha = 0.2f))
                            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = bot.avatar.ifBlank { bot.name.take(1).uppercase() },
                            style = MaterialTheme.typography.titleSmall,
                            color = accent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    AgentStatusDot(
                        status = status,
                        dotSize = 10.dp,
                        borderColor = BgSurface,
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = bot.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        AgentStatusBadgeChip(
                            status = status,
                            detailText = busyDetail,
                            modifier = Modifier.testTag("bot_screen_status_badge_${bot.id}")
                        )
                    }
                    Text(
                        text = "${bot.provider.displayLabel} · $statusDetail",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(color = BorderSubtle)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.broadcast_stream_toggle_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = if (broadcastActive) {
                            "Receives messages sent to Boardroom"
                        } else {
                            "Excluded from Boardroom broadcast"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (broadcastActive) StatusOk else TextMuted
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (bot.provider == AgentProvider.INAPP_BROWSER) {
                        IconButton(
                            onClick = onOpenBrowser,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Language,
                                contentDescription = "Open browser for ${bot.name}",
                                tint = AmberAccent
                            )
                        }
                    } else if (bot.provider == AgentProvider.MUSE_BRIDGE) {
                        IconButton(
                            onClick = onOpenBrowser,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("bot_connect_muse_button_${bot.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Language,
                                contentDescription = "Connect ${bot.name} to Muse AI",
                                tint = AmberAccent
                            )
                        }
                        IconButton(
                            onClick = onShowToken,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("bot_view_token_button_${bot.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Key,
                                contentDescription = "View Bridge Token for ${bot.name}",
                                tint = AmberAccent
                            )
                        }
                    }

                    IconButton(
                        onClick = { onToggleEnabled(!bot.enabled) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("bot_screen_power_button_${bot.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PowerSettingsNew,
                            contentDescription = if (bot.enabled) "Disable ${bot.name}" else "Enable ${bot.name}",
                            tint = if (bot.enabled) StatusOk else TextMuted
                        )
                    }

                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("bot_screen_edit_button_${bot.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "Edit ${bot.name}",
                            tint = TextSecondary
                        )
                    }

                    Switch(
                        checked = broadcastActive,
                        onCheckedChange = onToggleBroadcast,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = AmberAccent,
                            checkedTrackColor = AmberAccent.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .semantics {
                                contentDescription = "${bot.name} broadcast stream toggle"
                            }
                            .testTag("bot_broadcast_switch_${bot.id}")
                    )
                }
            }
        }
    }
}
