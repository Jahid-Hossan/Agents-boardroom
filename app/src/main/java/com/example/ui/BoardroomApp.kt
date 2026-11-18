package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.model.AgentAvailabilityStatus
import com.example.data.model.AgentConfig
import com.example.data.model.ChatViewMode
import com.example.ui.components.AgentSidebarContent
import com.example.ui.components.ChatFeedSection
import com.example.ui.components.ComposerBar
import com.example.ui.screens.BotsScreen
import com.example.ui.screens.BridgeTokenDialog
import com.example.ui.screens.InAppBrowserDialog
import com.example.ui.screens.ManageAgentsDialog
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.BgCanvas
import com.example.ui.theme.BgElevated
import com.example.ui.theme.BgSurface
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.StatusErr
import com.example.ui.theme.StatusOk
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardroomApp(
    widthSizeClass: WindowWidthSizeClass,
    viewModel: BoardroomViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }

    var showClearConfirm by remember { mutableStateOf(false) }
    var agentPendingDelete by remember { mutableStateOf<AgentConfig?>(null) }
    var showDirectAgentMenu by remember { mutableStateOf(false) }

    val isExpandedLayout = widthSizeClass != WindowWidthSizeClass.Compact
    val directAgent = remember(uiState.agents, uiState.selectedDirectAgentId) {
        uiState.agents.firstOrNull { it.id == uiState.selectedDirectAgentId }
            ?: uiState.agents.firstOrNull()
    }

    // Native Photo Picker (zero storage permission)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.attachFileFromUri(uri)
    }

    // Native Document Picker (zero storage permission)
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.attachFileFromUri(uri)
    }

    // Runtime RECORD_AUDIO Permission Flow
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startVoiceRecording()
        } else {
            viewModel.showMessage("Microphone permission is required for voice prompts.")
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeSnackbar()
        }
    }

    // Return to Boardroom on system Back when in 1-on-1 Direct Chat
    BackHandler(enabled = uiState.viewMode == ChatViewMode.DIRECT && !uiState.showManageAgentsDialog && !uiState.showInAppBrowserDialog) {
        viewModel.switchToBoardroom()
    }

    val sidebarBody: @Composable () -> Unit = {
        AgentSidebarContent(
            agents = uiState.agents,
            settings = uiState.settings,
            browserStatuses = uiState.browserStatuses,
            agentStatuses = uiState.agentStatuses,
            busyDetails = uiState.busyAgentDetails,
            agentErrorDetails = uiState.agentErrorDetails,
            bridgeSseConnected = uiState.bridgeSseConnected,
            embeddedBridgeRunning = uiState.embeddedBridgeRunning,
            embeddedBridgeUrl = uiState.embeddedBridgeUrl,
            isCreatingBridgeBot = uiState.isCreatingBridgeBot,
            museConnectionStatus = uiState.museConnectionStatus,
            storedAuthTokenCount = uiState.storedAuthTokenCount,
            onRefreshMuseConnection = viewModel::refreshMuseConnection,
            viewMode = uiState.viewMode,
            selectedDirectAgentId = uiState.selectedDirectAgentId,
            onOpenBrowser = { agentId ->
                scope.launch { drawerState.close() }
                viewModel.openInAppBrowser(agentId)
            },
            onAddAgent = {
                scope.launch { drawerState.close() }
                val created = viewModel.createNewAgent()
                viewModel.openManageAgents(created.id)
            },
            onSelectDirectAgent = { agentId ->
                scope.launch { drawerState.close() }
                viewModel.switchToDirectChat(agentId)
            },
            onEditAgent = { agentId ->
                scope.launch { drawerState.close() }
                viewModel.openManageAgents(agentId)
            },
            onDeleteAgent = { agent ->
                agentPendingDelete = agent
            },
            onToggleEnabled = viewModel::toggleAgentEnabled,
            onToggleBroadcastSelected = viewModel::toggleAgentBroadcastSelected,
            onInitBridgeBot = viewModel::initBridgeBotSession,
            onOpenBotsScreen = {
                scope.launch { drawerState.close() }
                viewModel.openBotsScreen()
            },
            onCreateBotBridge = {
                scope.launch { drawerState.close() }
                viewModel.createBotAndBridge()
            }
        )
    }

    val mainContent: @Composable () -> Unit = {
        Scaffold(
            containerColor = BgCanvas,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = BgSurface,
                        titleContentColor = TextPrimary
                    ),
                    navigationIcon = {
                        if (!isExpandedLayout) {
                            IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("mobile_menu_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Menu,
                                    contentDescription = "Open agents sidebar",
                                    tint = TextPrimary
                                )
                            }
                        }
                    },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isExpandedLayout) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_boardroom_emblem_1794987637847),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            }
                            Column {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val onlineCount = uiState.agentStatuses.values.count {
                                    it == AgentAvailabilityStatus.ONLINE
                                }
                                val busyCount = uiState.agentStatuses.values.count {
                                    it == AgentAvailabilityStatus.BUSY
                                }
                                val subtitle = buildString {
                                    append("$onlineCount/${uiState.agents.size} online")
                                    if (busyCount > 0) append(" · $busyCount busy")
                                }
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (busyCount > 0) AmberAccent else TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        if (isExpandedLayout) {
                            // Boardroom Broadcast vs Direct Chat switch
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(BgElevated)
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp)),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { viewModel.switchToBoardroom() },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(
                                            if (uiState.viewMode == ChatViewMode.BOARDROOM) {
                                                AmberAccent.copy(alpha = 0.22f)
                                            } else {
                                                BgElevated
                                            }
                                        )
                                        .testTag("view_boardroom_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Groups,
                                        contentDescription = "Boardroom Broadcast",
                                        tint = if (uiState.viewMode == ChatViewMode.BOARDROOM) AmberAccent else TextSecondary
                                    )
                                }
                                Box {
                                    IconButton(
                                        onClick = { showDirectAgentMenu = true },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(
                                                if (uiState.viewMode == ChatViewMode.DIRECT) {
                                                    AmberAccent.copy(alpha = 0.22f)
                                                } else {
                                                    BgElevated
                                                }
                                            )
                                            .testTag("view_direct_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Chat,
                                            contentDescription = "Direct Chat",
                                            tint = if (uiState.viewMode == ChatViewMode.DIRECT) AmberAccent else TextSecondary
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showDirectAgentMenu,
                                        onDismissRequest = { showDirectAgentMenu = false }
                                    ) {
                                        uiState.agents.forEach { agent ->
                                            DropdownMenuItem(
                                                text = { Text("Direct: ${agent.name}") },
                                                onClick = {
                                                    showDirectAgentMenu = false
                                                    viewModel.switchToDirectChat(agent.id)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        // In-App Browser button
                        IconButton(
                            onClick = { viewModel.openInAppBrowser() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("open_inapp_browser_button")
                        ) {
                            Box(contentAlignment = Alignment.TopEnd) {
                                Icon(
                                    imageVector = Icons.Filled.Language,
                                    contentDescription = "Open In-App Browser",
                                    tint = AmberAccent
                                )
                                val anyReady = uiState.browserStatuses.values.any { it.ready || it.hasSavedProfile }
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (anyReady) StatusOk else AmberAccent)
                                )
                            }
                        }

                        // Bots Screen button
                        IconButton(
                            onClick = { viewModel.openBotsScreen() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("open_bots_screen_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Hub,
                                contentDescription = "Bots & Broadcast Stream",
                                tint = AmberAccent
                            )
                        }

                        // Manage Agents button
                        IconButton(
                            onClick = { viewModel.openManageAgents() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("manage_agents_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Tune,
                                contentDescription = "Manage Agents",
                                tint = TextSecondary
                            )
                        }

                        // Clear Chat button
                        IconButton(
                            onClick = { showClearConfirm = true },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("clear_chat_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteSweep,
                                contentDescription = "Clear Chat",
                                tint = StatusErr
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isExpandedLayout) {
                    Box(
                        modifier = Modifier
                            .width(304.dp)
                            .fillMaxHeight()
                    ) {
                        sidebarBody()
                    }
                    VerticalDivider(color = BorderSubtle)
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    ChatFeedSection(
                        agents = uiState.agents,
                        agentStatuses = uiState.agentStatuses,
                        busyDetails = uiState.busyAgentDetails,
                        streamingDrafts = uiState.streamingDrafts,
                        messages = uiState.messages,
                        viewMode = uiState.viewMode,
                        directAgent = directAgent,
                        thinkingStatus = uiState.thinkingStatus,
                        onSelectDirectAgent = viewModel::switchToDirectChat,
                        onReturnToBoardroom = { viewModel.switchToBoardroom() },
                        onOpenInAppBrowser = { viewModel.openInAppBrowser() },
                        onOpenManageAgents = { viewModel.openManageAgents() },
                        modifier = Modifier.weight(1f)
                    )

                    ComposerBar(
                        agents = uiState.agents,
                        agentStatuses = uiState.agentStatuses,
                        viewMode = uiState.viewMode,
                        directAgent = directAgent,
                        composerText = uiState.composerText,
                        pendingAttachment = uiState.pendingAttachment,
                        isSending = uiState.isSending,
                        isRecordingVoice = uiState.isRecordingVoice,
                        recordingSeconds = uiState.recordingSeconds,
                        isTranscribingVoice = uiState.isTranscribingVoice,
                        onTextChange = viewModel::updateComposerText,
                        onInsertMention = viewModel::insertMention,
                        onPickPhoto = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onPickDocument = {
                            documentPickerLauncher.launch(arrayOf("*/*"))
                        },
                        onRemoveAttachment = viewModel::removePendingAttachment,
                        onTranscribeAttachment = viewModel::transcribeAudioAttachment,
                        onStartVoice = {
                            val hasPerm = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPerm) {
                                viewModel.startVoiceRecording()
                            } else {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onCancelVoice = viewModel::cancelVoiceRecording,
                        onStopVoice = viewModel::stopAndTranscribeVoice,
                        onSend = viewModel::sendCommand
                    )
                }
            }
        }
    }

    if (uiState.showBotsScreen) {
        Box(modifier = Modifier.fillMaxSize()) {
            BotsScreen(
                bots = uiState.agents,
                agentStatuses = uiState.agentStatuses,
                browserStatuses = uiState.browserStatuses,
                busyDetails = uiState.busyAgentDetails,
                errorDetails = uiState.agentErrorDetails,
                settings = uiState.settings,
                bridgeSseConnected = uiState.bridgeSseConnected,
                isSyncingBridge = uiState.isSyncingBridge,
                embeddedBridgeRunning = uiState.embeddedBridgeRunning,
                embeddedBridgeUrl = uiState.embeddedBridgeUrl,
                isCreatingBridgeBot = uiState.isCreatingBridgeBot,
                museConnectionStatus = uiState.museConnectionStatus,
                storedAuthTokenCount = uiState.storedAuthTokenCount,
                onRefreshMuseConnection = viewModel::refreshMuseConnection,
                onCreateBotBridge = { viewModel.createBotAndBridge() },
                onShowBotToken = viewModel::showBotTokenCredentials,
                onToggleEmbeddedServer = viewModel::toggleEmbeddedBridgeServer,
                onToggleBroadcast = viewModel::setBotBroadcastEnabled,
                onToggleBotEnabled = viewModel::toggleAgentEnabled,
                onSetAllBroadcast = viewModel::setAllBotsBroadcastEnabled,
                onSyncBridge = viewModel::syncConfiguredBridge,
                onAddBot = {
                    val created = viewModel.createNewAgent()
                    viewModel.openManageAgents(created.id)
                },
                onEditBot = viewModel::openManageAgents,
                onOpenBrowserForBot = viewModel::openInAppBrowser,
                onInitBridgeBot = viewModel::initBridgeBotSession,
                onBack = viewModel::closeBotsScreen
            )
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    } else if (isExpandedLayout) {
        mainContent()
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = BgSurface,
                    modifier = Modifier.width(304.dp)
                ) {
                    sidebarBody()
                }
            },
            content = mainContent
        )
    }

    if (uiState.showManageAgentsDialog) {
        ManageAgentsDialog(
            agents = uiState.agents,
            agentStatuses = uiState.agentStatuses,
            selectedAgentId = uiState.manageSelectedAgentId,
            settings = uiState.settings,
            webhookTestResult = uiState.webhookTestResult,
            isTestingWebhook = uiState.isTestingWebhook,
            bridgeSyncResult = uiState.bridgeSyncResult,
            isSyncingBridge = uiState.isSyncingBridge,
            onSelectAgent = viewModel::selectManageAgent,
            onAddAgent = { viewModel.createNewAgent() },
            onDuplicateAgent = { source -> viewModel.duplicateAgent(source) },
            onSaveAgent = viewModel::upsertAgent,
            onDeleteAgent = viewModel::deleteAgent,
            onSaveCollaboration = viewModel::updateCollaborationSettings,
            onOpenBrowserForAgent = { id ->
                viewModel.closeManageAgents()
                viewModel.openInAppBrowser(id)
            },
            onTestWebhook = viewModel::testWebhookConnection,
            onSyncBridgeBots = viewModel::syncBridgeBots,
            onInitBridgeBot = viewModel::initBridgeBotSession,
            onDismiss = viewModel::closeManageAgents
        )
    }

    if (uiState.showInAppBrowserDialog) {
        InAppBrowserDialog(
            agents = uiState.agents,
            activeAgentId = uiState.activeBrowserAgentId,
            statuses = uiState.browserStatuses,
            coordinator = viewModel.browserCoordinator,
            onSelectAgent = viewModel::selectBrowserAgent,
            onAddAccount = { viewModel.createIsolatedBrowserAccount() },
            onNavigate = viewModel::browserNavigate,
            onRefreshStatus = viewModel::browserRefreshStatus,
            onInjectQuickText = viewModel::browserInjectQuickText,
            onDismiss = viewModel::closeInAppBrowser
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.confirm_clear_title)) },
            text = { Text(stringResource(R.string.confirm_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearChatHistory()
                    }
                ) {
                    Text("Clear", color = StatusErr)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val pendingDelete = agentPendingDelete
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { agentPendingDelete = null },
            title = { Text(stringResource(R.string.confirm_remove_agent_title)) },
            text = { Text("Remove ${pendingDelete.name} from the boardroom?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAgent(pendingDelete.id)
                        agentPendingDelete = null
                    }
                ) {
                    Text("Remove", color = StatusErr)
                }
            },
            dismissButton = {
                TextButton(onClick = { agentPendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    val bridgeCreds = uiState.createdBridgeCredentials
    if (bridgeCreds != null) {
        BridgeTokenDialog(
            credentials = bridgeCreds,
            onCopyToken = {
                viewModel.showMessage("Copied Muse AI Bridge token to clipboard.")
            },
            onRegenerateToken = viewModel::regenerateBotToken,
            onConnectMuseAi = { agentId ->
                viewModel.dismissBridgeTokenDialog()
                viewModel.openInAppBrowser(agentId, bridgeCreds.targetUrl)
            },
            onDismiss = viewModel::dismissBridgeTokenDialog
        )
    }
}
