package com.example.ui

import android.app.Application
import android.content.Context
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.BoardroomDatabase
import com.example.data.local.TokenCryptoManager
import com.example.data.model.AgentAvailabilityStatus
import com.example.data.model.AgentConfig
import com.example.data.model.AgentProvider
import com.example.data.model.AttachmentData
import com.example.data.model.BoardroomMessage
import com.example.data.model.BoardroomSettings
import com.example.data.model.ChatViewMode
import com.example.data.model.CollaborationRole
import com.example.data.model.InAppBrowserStatus
import com.example.data.model.MuseConnectionStatus
import com.example.data.model.resolveAgentAvailability
import com.example.data.repository.BoardroomRepository
import com.example.engine.AgentCollaboration
import com.example.engine.AgentInferenceService
import com.example.engine.BridgeSocketService
import com.example.engine.BridgeSocketState
import com.example.engine.BridgeSseEventData
import com.example.engine.EmbeddedBridgeBotRecord
import com.example.engine.EmbeddedBridgeExecutor
import com.example.engine.EmbeddedBridgeServer
import com.example.engine.InAppBrowserCoordinator
import com.example.engine.IncomingHandoffContext
import com.example.engine.MultiBotBridgeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class CreatedBridgeCredentials(
    val agentId: String,
    val botId: String,
    val botName: String,
    val serverUrl: String,
    val token: String,
    val targetUrl: String
)

data class BoardroomUiState(
    val agents: List<AgentConfig> = emptyList(),
    val messages: List<BoardroomMessage> = emptyList(),
    val settings: BoardroomSettings = BoardroomSettings(),
    val browserStatuses: Map<String, InAppBrowserStatus> = emptyMap(),
    val agentStatuses: Map<String, AgentAvailabilityStatus> = emptyMap(),
    val busyAgentDetails: Map<String, String> = emptyMap(),
    val agentErrorDetails: Map<String, String> = emptyMap(),
    val streamingDrafts: Map<String, String> = emptyMap(),
    val isNetworkAvailable: Boolean = true,
    val viewMode: ChatViewMode = ChatViewMode.BOARDROOM,
    val selectedDirectAgentId: String? = null,
    val composerText: String = "",
    val pendingAttachment: AttachmentData? = null,
    val isSending: Boolean = false,
    val thinkingStatus: String? = null,
    val isRecordingVoice: Boolean = false,
    val recordingSeconds: Int = 0,
    val isTranscribingVoice: Boolean = false,
    val showManageAgentsDialog: Boolean = false,
    val manageSelectedAgentId: String? = null,
    val showInAppBrowserDialog: Boolean = false,
    val activeBrowserAgentId: String? = null,
    val showBotsScreen: Boolean = false,
    val webhookTestResult: String? = null,
    val isTestingWebhook: Boolean = false,
    val bridgeSyncResult: String? = null,
    val isSyncingBridge: Boolean = false,
    val bridgeSseConnected: Boolean = false,
    val embeddedBridgeRunning: Boolean = false,
    val embeddedBridgeUrl: String = "",
    val isCreatingBridgeBot: Boolean = false,
    val createdBridgeCredentials: CreatedBridgeCredentials? = null,
    val museConnectionStatus: MuseConnectionStatus = MuseConnectionStatus(),
    val storedAuthTokenCount: Int = 0,
    val snackbarMessage: String? = null
)

class BoardroomViewModel(application: Application) : AndroidViewModel(application) {

    private val database = BoardroomDatabase.getInstance(application)
    private val tokenCryptoManager = TokenCryptoManager(application)
    private val repository = BoardroomRepository(
        dao = database.boardroomDao(),
        tokenDao = database.bridgeAuthTokenDao(),
        cryptoManager = tokenCryptoManager
    )
    val browserCoordinator = InAppBrowserCoordinator(application)
    private val inferenceService = AgentInferenceService(application, browserCoordinator)
    val bridgeSocketService = BridgeSocketService()

    private val embeddedBridgeExecutor = object : EmbeddedBridgeExecutor {
        override suspend fun checkBotSession(bot: EmbeddedBridgeBotRecord): Pair<AgentAvailabilityStatus, String> {
            val agents = repository.agentsFlow.first()
            val matchedAgent = agents.firstOrNull {
                it.bridgeBotId == bot.botId || it.id == "bridge-${bot.botId}"
            } ?: AgentConfig(
                id = "bridge-${bot.botId}",
                name = bot.displayName,
                provider = AgentProvider.MUSE_BRIDGE,
                endpoint = bot.targetUrl,
                bridgeBotId = bot.botId
            )
            val status = browserCoordinator.statuses.value[matchedAgent.id]
            val isReady = status?.ready == true || status?.hasSavedProfile == true
            return when {
                !bot.enabled -> AgentAvailabilityStatus.OFFLINE to "Disabled"
                isReady -> AgentAvailabilityStatus.ONLINE to "Connected to ${bot.targetUrl}"
                else -> AgentAvailabilityStatus.NEEDS_AUTH to "Bridge ready · Tap Connect to Muse AI"
            }
        }

        override suspend fun executePrompt(
            bot: EmbeddedBridgeBotRecord,
            prompt: String,
            onPartialDelta: suspend (String) -> Unit
        ): Result<String> {
            val agents = repository.agentsFlow.first()
            val matchedAgent = agents.firstOrNull {
                it.bridgeBotId == bot.botId || it.id == "bridge-${bot.botId}"
            } ?: AgentConfig(
                id = "bridge-${bot.botId}",
                name = bot.displayName,
                provider = AgentProvider.MUSE_BRIDGE,
                endpoint = bot.targetUrl,
                bridgeBotId = bot.botId
            )
            return runCatching {
                val reply = browserCoordinator.sendPrompt(matchedAgent, prompt, attachment = null)
                onPartialDelta(reply)
                reply
            }
        }
    }

    val embeddedBridgeServer = EmbeddedBridgeServer(
        context = application,
        preferredPort = 8787,
        executor = embeddedBridgeExecutor
    )

    private val localState = MutableStateFlow(LocalUiControls())

    val uiState: StateFlow<BoardroomUiState> = combine(
        repository.agentsFlow,
        repository.messagesFlow,
        repository.settingsFlow,
        browserCoordinator.statuses,
        localState
    ) { agents, messages, settings, statuses, local ->
        val validDirectId = local.selectedDirectAgentId
            ?.takeIf { id -> agents.any { it.id == id } }
            ?: agents.firstOrNull()?.id
        val validManageId = local.manageSelectedAgentId
            ?.takeIf { id -> agents.any { it.id == id } }
            ?: agents.firstOrNull()?.id
        val validBrowserId = local.activeBrowserAgentId
            ?.takeIf { id -> agents.any { it.id == id } }
            ?: agents.firstOrNull { it.provider == AgentProvider.INAPP_BROWSER }?.id
            ?: agents.firstOrNull()?.id

        val resolvedStatuses = agents.associate { agent ->
            val remoteKey = agent.bridgeBotId.ifBlank { agent.id }
            val remoteStatus = local.remoteBridgeStatuses[remoteKey] ?: local.remoteBridgeStatuses[agent.id]
            val errMsg = local.agentErrorDetails[agent.id] ?: local.agentErrorDetails[remoteKey]
            agent.id to resolveAgentAvailability(
                agent = agent,
                browserStatus = statuses[agent.id],
                isBusy = local.busyAgentDetails.containsKey(agent.id),
                isNetworkAvailable = local.isNetworkAvailable,
                remoteBridgeStatus = remoteStatus,
                errorMessage = errMsg
            )
        }

        val hasOnlineMuseAgent = agents.any { agent ->
            val targetsMuse = agent.id.equals("muse", ignoreCase = true) ||
                agent.provider == AgentProvider.MUSE_BRIDGE ||
                agent.customUrl.contains("muse.ai", ignoreCase = true) ||
                agent.endpoint.contains("muse.ai", ignoreCase = true)
            targetsMuse && resolvedStatuses[agent.id] == AgentAvailabilityStatus.ONLINE
        }
        val isMuseConnected = local.socketState.museConnected || hasOnlineMuseAgent
        val museDetail = when {
            isMuseConnected && local.socketState.connected ->
                "Connected to Muse AI · Socket.IO real-time channel active"
            isMuseConnected ->
                "Connected to Muse AI session"
            local.socketState.connected ->
                local.socketState.museStatusDetail.ifBlank {
                    "Bridge Socket.IO live · Sign in to Muse AI in Browser"
                }
            local.embeddedBridgeRunning ->
                "Disconnected · Start or sign in to Muse AI and tap Refresh"
            else ->
                local.socketState.lastError?.takeIf { it.isNotBlank() }
                    ?: "Disconnected from Muse AI"
        }

        BoardroomUiState(
            agents = agents,
            messages = messages,
            settings = settings,
            browserStatuses = statuses,
            agentStatuses = resolvedStatuses,
            busyAgentDetails = local.busyAgentDetails,
            agentErrorDetails = local.agentErrorDetails,
            streamingDrafts = local.streamingDrafts,
            isNetworkAvailable = local.isNetworkAvailable,
            viewMode = local.viewMode,
            selectedDirectAgentId = validDirectId,
            composerText = local.composerText,
            pendingAttachment = local.pendingAttachment,
            isSending = local.isSending,
            thinkingStatus = local.thinkingStatus,
            isRecordingVoice = local.isRecordingVoice,
            recordingSeconds = local.recordingSeconds,
            isTranscribingVoice = local.isTranscribingVoice,
            showManageAgentsDialog = local.showManageAgentsDialog,
            manageSelectedAgentId = validManageId,
            showInAppBrowserDialog = local.showInAppBrowserDialog,
            activeBrowserAgentId = validBrowserId,
            showBotsScreen = local.showBotsScreen,
            webhookTestResult = local.webhookTestResult,
            isTestingWebhook = local.isTestingWebhook,
            bridgeSyncResult = local.bridgeSyncResult,
            isSyncingBridge = local.isSyncingBridge,
            bridgeSseConnected = local.bridgeSseConnected || local.socketState.connected,
            embeddedBridgeRunning = local.embeddedBridgeRunning,
            embeddedBridgeUrl = local.embeddedBridgeUrl,
            isCreatingBridgeBot = local.isCreatingBridgeBot,
            createdBridgeCredentials = local.createdBridgeCredentials,
            museConnectionStatus = MuseConnectionStatus(
                isConnected = isMuseConnected,
                isRefreshing = local.isRefreshingMuseConnection,
                socketConnected = local.socketState.connected,
                statusDetail = museDetail,
                lastUpdatedMillis = maxOf(local.socketState.lastCheckedAtMillis, local.lastMuseRefreshMillis)
            ),
            storedAuthTokenCount = local.storedAuthTokenCount,
            snackbarMessage = local.snackbarMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BoardroomUiState()
    )

    private var sendJob: Job? = null
    private var sseJob: Job? = null
    private var recordingTimerJob: Job? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        registerNetworkMonitor()
        viewModelScope.launch(Dispatchers.IO) {
            // Migrate any legacy plaintext tokens from SharedPreferences into encrypted Room vault
            val legacyTokens = embeddedBridgeServer.consumeLegacySharedPrefTokens()
            legacyTokens.forEach { (rawToken, botId) ->
                runCatching {
                    repository.saveEncryptedAuthToken(
                        botId = botId,
                        serverUrl = "http://127.0.0.1:8787",
                        rawToken = rawToken
                    )
                }
            }
            val activePairs = runCatching { repository.getDecryptedActiveTokens() }.getOrDefault(emptyList())
            embeddedBridgeServer.restoreValidTokens(activePairs)
            runCatching { embeddedBridgeServer.ensureStarted() }
        }
        viewModelScope.launch {
            repository.activeAuthTokensFlow.collect { tokenRecords ->
                localState.update { it.copy(storedAuthTokenCount = tokenRecords.size) }
            }
        }
        viewModelScope.launch {
            bridgeSocketService.state.collect { sock ->
                localState.update { it.copy(socketState = sock) }
            }
        }
        viewModelScope.launch {
            bridgeSocketService.events.collect { event ->
                handleBridgeSseEvent(event)
            }
        }
        viewModelScope.launch {
            embeddedBridgeServer.status.collect { srv ->
                localState.update {
                    it.copy(
                        embeddedBridgeRunning = srv.running,
                        embeddedBridgeUrl = srv.baseUrl
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.ensureSeeded()
        }
        viewModelScope.launch {
            repository.agentsFlow.collect { agents ->
                browserCoordinator.syncAgents(agents)
            }
        }
        viewModelScope.launch {
            browserCoordinator.statuses.collect { statusMap ->
                val agents = uiState.value.agents
                agents.filter { it.provider == AgentProvider.MUSE_BRIDGE }.forEach { agent ->
                    val st = statusMap[agent.id] ?: return@forEach
                    val remoteId = agent.bridgeBotId.ifBlank { agent.id }
                    if (st.ready || st.hasSavedProfile || st.needsLogin) {
                        embeddedBridgeServer.markBotBrowserReady(
                            botId = remoteId,
                            ready = st.ready || st.hasSavedProfile,
                            needsLogin = st.needsLogin && !st.ready
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            var activeUrl = ""
            var activeToken = ""
            combine(
                repository.settingsFlow,
                embeddedBridgeServer.status,
                repository.activeAuthTokensFlow
            ) { settings, srv, _ ->
                val url = settings.bridgeServerUrl.trim().ifBlank {
                    if (srv.running) srv.baseUrl else ""
                }
                val token = settings.bridgeAuthToken.trim().ifBlank {
                    embeddedBridgeServer.getPrimaryToken().orEmpty()
                }
                url to token
            }.collect { (url, token) ->
                if (url != activeUrl || token != activeToken) {
                    activeUrl = url
                    activeToken = token
                    bridgeSocketService.connect(url, token)
                    restartBridgeSse(url, token)
                }
            }
        }
    }

    private fun restartBridgeSse(serverUrl: String, authToken: String) {
        sseJob?.cancel()
        if (serverUrl.isBlank()) {
            localState.update { it.copy(bridgeSseConnected = false) }
            return
        }
        sseJob = viewModelScope.launch {
            while (true) {
                try {
                    localState.update { it.copy(bridgeSseConnected = true) }
                    inferenceService.bridgeClient.streamEvents(serverUrl, authToken).collect { event ->
                        handleBridgeSseEvent(event)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    localState.update { it.copy(bridgeSseConnected = false) }
                }
                delay(4_000)
            }
        }
    }

    private fun handleBridgeSseEvent(event: BridgeSseEventData) {
        val agents = uiState.value.agents
        fun localAgentIdFor(botId: String): String =
            agents.firstOrNull { it.bridgeBotId == botId || it.id == botId }?.id ?: botId

        when (event) {
            is BridgeSseEventData.BotStatusUpdated -> {
                val localId = localAgentIdFor(event.botId)
                localState.update { state ->
                    val nextErrors = if (event.status == AgentAvailabilityStatus.ERROR && event.reason.isNotBlank()) {
                        state.agentErrorDetails + (localId to event.reason)
                    } else if (event.status == AgentAvailabilityStatus.ONLINE || event.status == AgentAvailabilityStatus.BUSY) {
                        state.agentErrorDetails - localId
                    } else {
                        state.agentErrorDetails
                    }
                    state.copy(
                        remoteBridgeStatuses = state.remoteBridgeStatuses + (event.botId to event.status),
                        agentErrorDetails = nextErrors
                    )
                }
            }
            is BridgeSseEventData.TaskDelta -> {
                val localId = localAgentIdFor(event.botId)
                localState.update { state ->
                    state.copy(
                        streamingDrafts = state.streamingDrafts + (localId to event.fullTextSoFar),
                        busyAgentDetails = state.busyAgentDetails + (localId to "Streaming...")
                    )
                }
            }
            is BridgeSseEventData.TaskTerminal -> {
                val localId = localAgentIdFor(event.botId)
                localState.update { state ->
                    val nextErrors = if (!event.errorMessage.isNullOrBlank() && event.status != "needs_auth") {
                        state.agentErrorDetails + (localId to event.errorMessage)
                    } else {
                        state.agentErrorDetails - localId
                    }
                    state.copy(
                        streamingDrafts = state.streamingDrafts - localId,
                        busyAgentDetails = state.busyAgentDetails - localId,
                        agentErrorDetails = nextErrors
                    )
                }
            }
            is BridgeSseEventData.MessageCreated -> {
                val localId = localAgentIdFor(event.botId)
                localState.update { state ->
                    state.copy(streamingDrafts = state.streamingDrafts - localId)
                }
            }
        }
    }

    private fun registerNetworkMonitor() {
        try {
            val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            fun checkNow(): Boolean {
                val network = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            localState.update { it.copy(isNetworkAvailable = checkNow()) }

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    localState.update { it.copy(isNetworkAvailable = true) }
                }

                override fun onLost(network: Network) {
                    localState.update { it.copy(isNetworkAvailable = checkNow()) }
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val hasNet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    localState.update { it.copy(isNetworkAvailable = hasNet || checkNow()) }
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (_: Exception) {
            // Keep default optimistic network availability on restricted test environments
        }
    }

    private fun setAgentBusy(agentId: String, label: String) {
        localState.update { it.copy(busyAgentDetails = it.busyAgentDetails + (agentId to label)) }
    }

    private fun clearAgentBusy(agentId: String) {
        localState.update { it.copy(busyAgentDetails = it.busyAgentDetails - agentId) }
    }

    fun updateComposerText(text: String) {
        localState.update { it.copy(composerText = text) }
    }

    fun insertMention(mention: String) {
        localState.update { state ->
            val current = state.composerText
            val separator = if (current.isBlank() || current.endsWith(" ") || current.endsWith("\n")) "" else " "
            state.copy(composerText = "$current$separator$mention ")
        }
    }

    fun switchToBoardroom() {
        localState.update { it.copy(viewMode = ChatViewMode.BOARDROOM) }
    }

    fun switchToDirectChat(agentId: String) {
        localState.update {
            it.copy(
                viewMode = ChatViewMode.DIRECT,
                selectedDirectAgentId = agentId
            )
        }
    }

    fun openManageAgents(agentId: String? = null) {
        localState.update {
            it.copy(
                showManageAgentsDialog = true,
                manageSelectedAgentId = agentId ?: it.manageSelectedAgentId,
                webhookTestResult = null
            )
        }
    }

    fun closeManageAgents() {
        localState.update {
            it.copy(
                showManageAgentsDialog = false,
                webhookTestResult = null
            )
        }
    }

    fun selectManageAgent(agentId: String) {
        localState.update { it.copy(manageSelectedAgentId = agentId, webhookTestResult = null) }
    }

    fun openInAppBrowser(agentId: String? = null, forceUrl: String? = null) {
        val agents = uiState.value.agents
        val targetId = agentId
            ?: uiState.value.activeBrowserAgentId
            ?: agents.firstOrNull { it.provider == AgentProvider.INAPP_BROWSER }?.id
            ?: agents.firstOrNull()?.id
        localState.update {
            it.copy(
                showInAppBrowserDialog = true,
                activeBrowserAgentId = targetId
            )
        }
        if (targetId != null && !forceUrl.isNullOrBlank()) {
            val agent = agents.firstOrNull { it.id == targetId }
            if (agent != null) {
                viewModelScope.launch {
                    browserCoordinator.getOrCreateWebView(agent, forceUrl)
                }
            }
        }
    }

    fun closeInAppBrowser() {
        localState.update { it.copy(showInAppBrowserDialog = false) }
    }

    fun selectBrowserAgent(agentId: String) {
        localState.update { it.copy(activeBrowserAgentId = agentId) }
    }

    fun openBotsScreen() {
        localState.update { it.copy(showBotsScreen = true) }
    }

    fun closeBotsScreen() {
        localState.update { it.copy(showBotsScreen = false) }
    }

    fun consumeSnackbar() {
        localState.update { it.copy(snackbarMessage = null) }
    }

    fun showMessage(msg: String) {
        localState.update { it.copy(snackbarMessage = msg) }
    }

    fun removePendingAttachment() {
        localState.update { it.copy(pendingAttachment = null) }
    }

    fun attachFileFromUri(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                readAttachmentFromUri(uri)
            }
            result.onSuccess { attachment ->
                localState.update { it.copy(pendingAttachment = attachment) }
            }.onFailure { err ->
                showMessage(err.localizedMessage ?: "Failed to attach file.")
            }
        }
    }

    private fun readAttachmentFromUri(uri: Uri): Result<AttachmentData> {
        return try {
            val resolver = getApplication<Application>().contentResolver
            var filename = "attachment"
            var sizeBytes = 0L
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) filename = cursor.getString(nameIdx) ?: filename
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) sizeBytes = cursor.getLong(sizeIdx)
                }
            }

            if (sizeBytes > MAX_ATTACHMENT_BYTES) {
                return Result.failure(IllegalArgumentException("File exceeds 12 MB limit."))
            }

            val mimeType = resolver.getType(uri) ?: guessMimeFromName(filename)
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Result.failure(IllegalStateException("Could not open selected file."))

            if (bytes.size > MAX_ATTACHMENT_BYTES) {
                return Result.failure(IllegalArgumentException("File exceeds 12 MB limit."))
            }

            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val isText = mimeType.startsWith("text/", ignoreCase = true) ||
                mimeType.equals("application/json", ignoreCase = true) ||
                Regex("\\.(txt|md|csv|json|js|ts|py|kt|java|html|css|log)$", RegexOption.IGNORE_CASE)
                    .containsMatchIn(filename)
            val preview = if (isText) {
                bytes.decodeToString().take(16_000)
            } else {
                ""
            }

            Result.success(
                AttachmentData(
                    filename = filename,
                    mimeType = mimeType,
                    uriString = uri.toString(),
                    base64Data = base64,
                    textPreview = preview,
                    sizeBytes = bytes.size.toLong()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun transcribeAudioAttachment() {
        val attachment = uiState.value.pendingAttachment ?: return
        if (!attachment.isAudio || attachment.base64Data.isBlank()) return
        if (uiState.value.isTranscribingVoice) return

        viewModelScope.launch {
            localState.update { it.copy(isTranscribingVoice = true) }
            try {
                val bytes = withContext(Dispatchers.IO) {
                    Base64.decode(attachment.base64Data, Base64.DEFAULT)
                }
                val res = inferenceService.transcribeAudio(bytes, attachment.mimeType.ifBlank { "audio/mp4" })
                res.onSuccess { transcript ->
                    localState.update { state ->
                        val prefix = if (state.composerText.isBlank()) "" else "${state.composerText.trimEnd()} "
                        state.copy(
                            composerText = "$prefix$transcript",
                            pendingAttachment = null
                        )
                    }
                    showMessage("Audio transcribed into prompt.")
                }.onFailure { err ->
                    showMessage(err.localizedMessage ?: "Audio transcription failed.")
                }
            } finally {
                localState.update { it.copy(isTranscribingVoice = false) }
            }
        }
    }

    fun startVoiceRecording() {
        if (uiState.value.isRecordingVoice || uiState.value.isTranscribingVoice) return
        try {
            val outFile = File(getApplication<Application>().cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(getApplication())
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(16_000)
                setOutputFile(outFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            recordingFile = outFile
            localState.update { it.copy(isRecordingVoice = true, recordingSeconds = 0) }
            recordingTimerJob?.cancel()
            recordingTimerJob = viewModelScope.launch {
                while (true) {
                    delay(1_000)
                    localState.update { it.copy(recordingSeconds = it.recordingSeconds + 1) }
                }
            }
        } catch (e: Exception) {
            releaseRecorder(deleteFile = true)
            showMessage(e.localizedMessage ?: "Could not start voice recording.")
        }
    }

    fun cancelVoiceRecording() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        releaseRecorder(deleteFile = true)
        localState.update { it.copy(isRecordingVoice = false, recordingSeconds = 0) }
    }

    fun stopAndTranscribeVoice() {
        if (!uiState.value.isRecordingVoice) return
        recordingTimerJob?.cancel()
        recordingTimerJob = null

        val file = recordingFile
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
            // Short tap or early stop; handle gracefully below
        }
        releaseRecorder(deleteFile = false)
        localState.update { it.copy(isRecordingVoice = false, recordingSeconds = 0) }

        if (file == null || !file.exists() || file.length() < 512) {
            file?.delete()
            showMessage("Recording too short. Hold or speak for at least 1 second.")
            return
        }

        viewModelScope.launch {
            localState.update { it.copy(isTranscribingVoice = true) }
            try {
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                file.delete()
                val res = inferenceService.transcribeAudio(bytes, "audio/mp4")
                res.onSuccess { transcript ->
                    localState.update { state ->
                        val prefix = if (state.composerText.isBlank()) "" else "${state.composerText.trimEnd()} "
                        state.copy(composerText = "$prefix$transcript")
                    }
                }.onFailure { err ->
                    showMessage(err.localizedMessage ?: "Voice transcription failed.")
                }
            } finally {
                localState.update { it.copy(isTranscribingVoice = false) }
            }
        }
    }

    private fun releaseRecorder(deleteFile: Boolean) {
        try {
            mediaRecorder?.reset()
        } catch (_: Exception) {
        }
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
        if (deleteFile) {
            recordingFile?.delete()
            recordingFile = null
        }
    }

    fun sendCommand() {
        val snapshot = uiState.value
        if (snapshot.isSending) return
        val rawText = snapshot.composerText.trim()
        val attachment = snapshot.pendingAttachment
        if (rawText.isEmpty() && attachment == null) return

        val agents = snapshot.agents
        if (agents.isEmpty()) {
            showMessage("Add at least one AI agent in Manage Agents first.")
            return
        }

        val effectivePromptText = rawText.ifEmpty {
            "Please analyze this attached file: ${attachment?.filename ?: "attachment"}"
        }
        val settings = snapshot.settings
        val maxHops = AgentCollaboration.clampMaxHops(settings.maxCollaborationHops)
        val isDirect = snapshot.viewMode == ChatViewMode.DIRECT
        val directAgent = agents.firstOrNull { it.id == snapshot.selectedDirectAgentId } ?: agents.first()
        val rootTargetId = if (isDirect) directAgent.id else "all"
        val modeName = if (isDirect) "direct" else "broadcast"

        val userMessage = BoardroomMessage(
            id = "ceo-${UUID.randomUUID()}",
            sender = "ceo",
            role = "CEO",
            senderName = settings.ceoName.ifBlank { "You" },
            avatar = "Y",
            colorHex = "#F59E0B",
            text = rawText.ifEmpty { "Sent attachment: ${attachment?.filename ?: ""}" },
            attachment = attachment,
            mode = modeName,
            targetAgentId = rootTargetId,
            rootTargetAgentId = rootTargetId,
            timestampMillis = System.currentTimeMillis()
        )

        localState.update {
            it.copy(
                composerText = "",
                pendingAttachment = null,
                isSending = true,
                thinkingStatus = "Dispatching to ${if (isDirect) directAgent.name else "boardroom"}..."
            )
        }

        sendJob = viewModelScope.launch {
            try {
                repository.insertMessage(userMessage)

                val explicitHandoffs = AgentCollaboration.parseAgentHandoffs(
                    replyText = effectivePromptText,
                    allAgents = agents,
                    senderAgentId = "__ceo__"
                )
                val supervisor = agents.firstOrNull { it.isSupervisor }
                    ?: agents.firstOrNull { it.id == settings.supervisorAgentId }

                var supervisorDelegationOnly = false
                val initialTargets: List<Pair<AgentConfig, String>> = when {
                    isDirect -> listOf(directAgent to effectivePromptText)
                    explicitHandoffs.isNotEmpty() -> explicitHandoffs.map { it.agent to it.subPrompt }
                    supervisor != null && settings.collaborationEnabled -> {
                        supervisorDelegationOnly = true
                        val prompt = AgentCollaboration.buildCollaborationPrompt(
                            userCommand = effectivePromptText,
                            agent = supervisor,
                            allAgents = agents,
                            isSupervisorInitial = true,
                            hop = 1,
                            maxHops = maxHops
                        )
                        listOf(supervisor to prompt)
                    }
                    else -> agents
                        .filter { it.enabled && it.selectedForBroadcast }
                        .map { agent ->
                            val prompt = if (settings.collaborationEnabled) {
                                AgentCollaboration.buildCollaborationPrompt(
                                    userCommand = effectivePromptText,
                                    agent = agent,
                                    allAgents = agents,
                                    hop = 1,
                                    maxHops = maxHops
                                )
                            } else {
                                effectivePromptText
                            }
                            agent to prompt
                        }
                }

                if (initialTargets.isEmpty()) {
                    showMessage("Enable and select at least one bot/agent for broadcast.")
                    return@launch
                }

                val targetNames = initialTargets.joinToString(", ") { it.first.name }
                localState.update {
                    it.copy(
                        thinkingStatus = if (supervisorDelegationOnly) {
                            "${supervisor?.name} (CEO) is reviewing & delegating..."
                        } else {
                            "Waiting on $targetNames..."
                        }
                    )
                }

                // Partition Multi-Bot Bridge bots (which fan out via a single Bridge broadcast call)
                // from local/direct agents (Gemini, In-App Browser, OpenAI, Webhook, Simulation).
                val bridgeTargets = initialTargets.filter { (agent, _) ->
                    agent.provider == AgentProvider.MUSE_BRIDGE &&
                        (agent.customUrl.isNotBlank() || settings.bridgeServerUrl.isNotBlank())
                }
                val localTargets = initialTargets.filterNot { (agent, _) ->
                    agent.provider == AgentProvider.MUSE_BRIDGE &&
                        (agent.customUrl.isNotBlank() || settings.bridgeServerUrl.isNotBlank())
                }

                val round1Replies = coroutineScope {
                    val localJobs = localTargets.map { (agent, prompt) ->
                        async {
                            localState.update { st ->
                                st.copy(agentErrorDetails = st.agentErrorDetails - agent.id)
                            }
                            setAgentBusy(
                                agent.id,
                                if (supervisorDelegationOnly) "Delegating..." else "Replying..."
                            )
                            val replyText = try {
                                inferenceService.invokeAgent(
                                    agent = agent,
                                    promptText = prompt,
                                    attachment = attachment,
                                    bridgeServerUrl = settings.bridgeServerUrl,
                                    bridgeAuthToken = settings.bridgeAuthToken,
                                    parentMessageId = userMessage.id
                                )
                            } finally {
                                clearAgentBusy(agent.id)
                            }
                            if (AgentCollaboration.isErrorOrSetupReply(replyText) &&
                                replyText.contains("error", ignoreCase = true)
                            ) {
                                localState.update { st ->
                                    st.copy(agentErrorDetails = st.agentErrorDetails + (agent.id to replyText.take(120)))
                                }
                            }
                            val handoffsFound = if (settings.collaborationEnabled) {
                                AgentCollaboration.parseAgentHandoffs(replyText, agents, agent.id)
                            } else {
                                emptyList()
                            }
                            val roleTag = if (supervisorDelegationOnly && handoffsFound.isNotEmpty()) {
                                CollaborationRole.SUPERVISOR_PLAN
                            } else {
                                CollaborationRole.PRIMARY
                            }
                            val msg = BoardroomMessage(
                                id = "msg-${UUID.randomUUID()}",
                                sender = agent.id,
                                role = agent.role.ifBlank { agent.provider.displayLabel },
                                senderName = agent.name,
                                avatar = agent.avatar,
                                colorHex = agent.colorHex,
                                text = replyText,
                                mode = modeName,
                                targetAgentId = rootTargetId,
                                rootTargetAgentId = rootTargetId,
                                rootMessageId = userMessage.id,
                                parentMessageId = userMessage.id,
                                hop = 1,
                                maxHops = maxHops,
                                isSupervisor = agent.isSupervisor,
                                collaborationRole = roleTag,
                                timestampMillis = System.currentTimeMillis()
                            )
                            repository.insertMessage(msg)
                            msg
                        }
                    }

                    val groupedBridge = bridgeTargets.groupBy { (agent, _) ->
                        val url = agent.customUrl.trim().ifBlank { settings.bridgeServerUrl.trim() }
                        val token = agent.apiKey.trim().ifBlank { settings.bridgeAuthToken.trim() }
                        url to token
                    }

                    val bridgeJobs = groupedBridge.entries.map { (endpointPair, group) ->
                        async {
                            val (serverUrl, authToken) = endpointPair
                            group.forEach { (agent, _) ->
                                localState.update { st ->
                                    st.copy(agentErrorDetails = st.agentErrorDetails - agent.id)
                                }
                                setAgentBusy(agent.id, "Bridge...")
                            }
                            val targetBotIds = group.map { (agent, _) ->
                                agent.bridgeBotId.ifBlank { agent.id }
                            }
                            val batchResult = try {
                                inferenceService.bridgeClient.dispatchBroadcast(
                                    serverUrl = serverUrl,
                                    authToken = authToken,
                                    conversationId = if (isDirect) "direct_${directAgent.id}" else "boardroom-main",
                                    parentMessageId = userMessage.id,
                                    prompt = effectivePromptText,
                                    targetBotIds = targetBotIds,
                                    attachment = attachment
                                )
                            } finally {
                                group.forEach { (agent, _) ->
                                    clearAgentBusy(agent.id)
                                    localState.update { st ->
                                        st.copy(streamingDrafts = st.streamingDrafts - agent.id)
                                    }
                                }
                            }

                            batchResult.fold(
                                onSuccess = { batch ->
                                    group.map { (agent, _) ->
                                        val remoteId = agent.bridgeBotId.ifBlank { agent.id }
                                        val item = batch.items.firstOrNull { it.botId == remoteId }
                                        val replyText = when {
                                            item == null -> "**${agent.name} error:** Bridge returned no response."
                                            !item.text.isNullOrBlank() -> item.text
                                            item.status == "needs_auth" -> {
                                                localState.update { st ->
                                                    st.copy(
                                                        remoteBridgeStatuses = st.remoteBridgeStatuses +
                                                            (remoteId to AgentAvailabilityStatus.NEEDS_AUTH)
                                                    )
                                                }
                                                "**${agent.name} needs authentication:** ${item.errorMessage ?: "Sign in required on Bridge host."}"
                                            }
                                            else -> {
                                                val err = item.errorMessage ?: "Task ${item.status}"
                                                localState.update { st ->
                                                    st.copy(agentErrorDetails = st.agentErrorDetails + (agent.id to err))
                                                }
                                                "**${agent.name} error:** $err"
                                            }
                                        }
                                        val msg = BoardroomMessage(
                                            id = item?.responseMessageId ?: "msg-${UUID.randomUUID()}",
                                            sender = agent.id,
                                            role = agent.role.ifBlank { agent.provider.displayLabel },
                                            senderName = agent.name,
                                            avatar = agent.avatar,
                                            colorHex = agent.colorHex,
                                            text = replyText,
                                            mode = modeName,
                                            targetAgentId = rootTargetId,
                                            rootTargetAgentId = rootTargetId,
                                            rootMessageId = userMessage.id,
                                            parentMessageId = userMessage.id,
                                            deliveryTaskId = item?.deliveryTaskId,
                                            dispatchBatchId = item?.dispatchBatchId ?: batch.dispatchBatchId,
                                            hop = 1,
                                            maxHops = maxHops,
                                            isSupervisor = agent.isSupervisor,
                                            collaborationRole = CollaborationRole.PRIMARY,
                                            timestampMillis = System.currentTimeMillis()
                                        )
                                        repository.insertMessage(msg)
                                        msg
                                    }
                                },
                                onFailure = { err ->
                                    val errText = err.localizedMessage ?: "Failed to reach Multi-Bot Bridge."
                                    group.map { (agent, _) ->
                                        localState.update { st ->
                                            st.copy(agentErrorDetails = st.agentErrorDetails + (agent.id to errText))
                                        }
                                        val msg = BoardroomMessage(
                                            id = "msg-${UUID.randomUUID()}",
                                            sender = agent.id,
                                            role = agent.role.ifBlank { agent.provider.displayLabel },
                                            senderName = agent.name,
                                            avatar = agent.avatar,
                                            colorHex = agent.colorHex,
                                            text = "**${agent.name} Bridge error:** $errText",
                                            mode = modeName,
                                            targetAgentId = rootTargetId,
                                            rootTargetAgentId = rootTargetId,
                                            rootMessageId = userMessage.id,
                                            parentMessageId = userMessage.id,
                                            hop = 1,
                                            maxHops = maxHops,
                                            isSupervisor = agent.isSupervisor,
                                            collaborationRole = CollaborationRole.PRIMARY,
                                            timestampMillis = System.currentTimeMillis()
                                        )
                                        repository.insertMessage(msg)
                                        msg
                                    }
                                }
                            )
                        }
                    }

                    localJobs.awaitAll() + bridgeJobs.awaitAll().flatten()
                }

                val allWorkerReplies = mutableListOf<BoardroomMessage>()
                allWorkerReplies.addAll(round1Replies)

                val visitedEdges = mutableSetOf<Pair<String, String>>()
                var currentWave = round1Replies.filter { !AgentCollaboration.isErrorOrSetupReply(it.text) }
                var hop = 1

                while (settings.collaborationEnabled && hop < maxHops && currentWave.isNotEmpty()) {
                    data class HandoffJob(
                        val fromMsg: BoardroomMessage,
                        val toAgent: AgentConfig,
                        val subPrompt: String
                    )

                    val queuedJobs = mutableListOf<HandoffJob>()
                    for (fromMsg in currentWave) {
                        val targets = AgentCollaboration.parseAgentHandoffs(
                            replyText = fromMsg.text,
                            allAgents = agents,
                            senderAgentId = fromMsg.sender
                        )
                        for (t in targets) {
                            val edge = fromMsg.sender to t.agent.id
                            if (visitedEdges.add(edge)) {
                                queuedJobs.add(HandoffJob(fromMsg, t.agent, t.subPrompt))
                            }
                        }
                    }

                    if (queuedJobs.isEmpty()) break
                    hop += 1

                    val handoffNames = queuedJobs.joinToString(", ") { "@${it.toAgent.name}" }
                    localState.update {
                        it.copy(thinkingStatus = "Round $hop/$maxHops · Coordinating $handoffNames...")
                    }

                    val nextWave = coroutineScope {
                        queuedJobs.map { job ->
                            async {
                                val handoffPrompt = AgentCollaboration.buildCollaborationPrompt(
                                    userCommand = effectivePromptText,
                                    agent = job.toAgent,
                                    allAgents = agents,
                                    incomingContext = IncomingHandoffContext(
                                        fromName = job.fromMsg.senderName,
                                        subPrompt = job.subPrompt,
                                        sourceText = job.fromMsg.text
                                    ),
                                    hop = hop,
                                    maxHops = maxHops
                                )
                                setAgentBusy(job.toAgent.id, "Handoff R$hop...")
                                val replyText = try {
                                    inferenceService.invokeAgent(
                                        agent = job.toAgent,
                                        promptText = handoffPrompt,
                                        attachment = null,
                                        bridgeServerUrl = settings.bridgeServerUrl,
                                        bridgeAuthToken = settings.bridgeAuthToken,
                                        parentMessageId = userMessage.id
                                    )
                                } finally {
                                    clearAgentBusy(job.toAgent.id)
                                }
                                val msg = BoardroomMessage(
                                    id = "msg-${UUID.randomUUID()}",
                                    sender = job.toAgent.id,
                                    role = job.toAgent.role.ifBlank { job.toAgent.provider.displayLabel },
                                    senderName = job.toAgent.name,
                                    avatar = job.toAgent.avatar,
                                    colorHex = job.toAgent.colorHex,
                                    text = replyText,
                                    mode = modeName,
                                    targetAgentId = rootTargetId,
                                    rootTargetAgentId = rootTargetId,
                                    rootMessageId = userMessage.id,
                                    hop = hop,
                                    maxHops = maxHops,
                                    isSupervisor = job.toAgent.isSupervisor,
                                    collaborationRole = CollaborationRole.HANDOFF,
                                    fromAgentName = job.fromMsg.senderName,
                                    timestampMillis = System.currentTimeMillis()
                                )
                                repository.insertMessage(msg)
                                msg
                            }
                        }.awaitAll()
                    }

                    allWorkerReplies.addAll(nextWave)
                    currentWave = nextWave.filter { !AgentCollaboration.isErrorOrSetupReply(it.text) }
                }

                // Supervisor Final Synthesis when supervisor delegated or multiple teammates contributed
                if (!isDirect && settings.collaborationEnabled && supervisor != null) {
                    val nonSupervisorReplies = allWorkerReplies.filter {
                        it.sender != supervisor.id && !AgentCollaboration.isErrorOrSetupReply(it.text)
                    }
                    if (nonSupervisorReplies.isNotEmpty()) {
                        localState.update {
                            it.copy(thinkingStatus = "${supervisor.name} (CEO) is synthesizing final decision...")
                        }
                        val synthesisPrompt = AgentCollaboration.buildCollaborationPrompt(
                            userCommand = effectivePromptText,
                            agent = supervisor,
                            allAgents = agents,
                            collectedReplies = nonSupervisorReplies,
                            isSupervisorSynthesis = true,
                            hop = hop,
                            maxHops = maxHops
                        )
                        setAgentBusy(supervisor.id, "Synthesizing...")
                        val synthesisText = try {
                            inferenceService.invokeAgent(
                                agent = supervisor,
                                promptText = synthesisPrompt,
                                attachment = null,
                                bridgeServerUrl = settings.bridgeServerUrl,
                                bridgeAuthToken = settings.bridgeAuthToken,
                                parentMessageId = userMessage.id
                            )
                        } finally {
                            clearAgentBusy(supervisor.id)
                        }
                        val finalMsg = BoardroomMessage(
                            id = "msg-${UUID.randomUUID()}",
                            sender = supervisor.id,
                            role = supervisor.role.ifBlank { "CEO / Supervisor" },
                            senderName = supervisor.name,
                            avatar = supervisor.avatar,
                            colorHex = supervisor.colorHex,
                            text = synthesisText,
                            mode = modeName,
                            targetAgentId = rootTargetId,
                            rootTargetAgentId = rootTargetId,
                            rootMessageId = userMessage.id,
                            hop = hop + 1,
                            maxHops = maxHops,
                            isSupervisor = true,
                            collaborationRole = CollaborationRole.SUPERVISOR_FINAL,
                            timestampMillis = System.currentTimeMillis()
                        )
                        repository.insertMessage(finalMsg)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage(e.localizedMessage ?: "Failed to complete command.")
            } finally {
                localState.update {
                    it.copy(isSending = false, thinkingStatus = null, busyAgentDetails = emptyMap())
                }
            }
        }
    }

    fun upsertAgent(agent: AgentConfig, isSupervisor: Boolean) {
        viewModelScope.launch {
            val normalizedUrl = AgentInferenceService.normalizeHttpUrl(agent.customUrl)
            val saved = agent.copy(
                customUrl = normalizedUrl,
                avatar = agent.avatar.trim().ifBlank { agent.name.take(1).uppercase().ifBlank { "A" } },
                isSupervisor = isSupervisor
            )
            repository.upsertAgent(saved)
            if (isSupervisor) {
                repository.setSupervisor(saved.id)
            } else if (uiState.value.settings.supervisorAgentId == saved.id) {
                repository.setSupervisor(null)
            }
            showMessage("Saved ${saved.name}.")
        }
    }

    fun createNewAgent(): AgentConfig {
        val count = uiState.value.agents.size + 1
        val id = "agent-${UUID.randomUUID().toString().take(8)}"
        val agent = AgentConfig(
            id = id,
            name = "Agent $count",
            role = "Specialist AI",
            avatar = "A",
            colorHex = "#3B82F6",
            provider = AgentProvider.GEMINI,
            customUrl = "https://gemini.google.com/app",
            model = "gemini-3.8-flash",
            sortOrder = count
        )
        viewModelScope.launch {
            repository.upsertAgent(agent)
            localState.update { it.copy(manageSelectedAgentId = agent.id) }
        }
        return agent
    }

    fun duplicateAgent(source: AgentConfig): AgentConfig {
        val count = uiState.value.agents.size + 1
        val copy = source.copy(
            id = "agent-${UUID.randomUUID().toString().take(8)}",
            name = "${source.name} (Copy)",
            isSupervisor = false,
            sortOrder = count
        )
        viewModelScope.launch {
            repository.upsertAgent(copy)
            localState.update { it.copy(manageSelectedAgentId = copy.id) }
            showMessage("Duplicated ${source.name}.")
        }
        return copy
    }

    fun deleteAgent(agentId: String) {
        val agents = uiState.value.agents
        if (agents.size <= 1) {
            showMessage("Keep at least one agent in the boardroom.")
            return
        }
        val targetAgent = agents.firstOrNull { it.id == agentId }
        viewModelScope.launch {
            browserCoordinator.closeSession(agentId)
            if (targetAgent?.provider == AgentProvider.MUSE_BRIDGE) {
                val remoteId = targetAgent.bridgeBotId.ifBlank { targetAgent.id }
                repository.revokeTokensForBot(remoteId)
                embeddedBridgeServer.revokeBotTokens(remoteId)
            }
            if (uiState.value.settings.supervisorAgentId == agentId) {
                repository.setSupervisor(null)
            }
            repository.deleteAgent(agentId)
            val remaining = agents.firstOrNull { it.id != agentId }?.id
            localState.update {
                it.copy(
                    manageSelectedAgentId = remaining,
                    selectedDirectAgentId = if (it.selectedDirectAgentId == agentId) remaining else it.selectedDirectAgentId
                )
            }
            showMessage("Agent removed.")
        }
    }

    fun updateCollaborationSettings(enabled: Boolean, maxHops: Int) {
        val clamped = AgentCollaboration.clampMaxHops(maxHops)
        viewModelScope.launch {
            val current = uiState.value.settings
            repository.saveSettings(
                current.copy(
                    collaborationEnabled = enabled,
                    maxCollaborationHops = clamped
                )
            )
        }
    }

    fun clearChatHistory() {
        viewModelScope.launch {
            repository.clearChatHistory()
            showMessage("Chat history cleared.")
        }
    }

    fun testWebhookConnection(url: String) {
        viewModelScope.launch {
            localState.update { it.copy(isTestingWebhook = true, webhookTestResult = null) }
            val result = inferenceService.testWebhookConnection(url)
            localState.update {
                it.copy(
                    isTestingWebhook = false,
                    webhookTestResult = result.fold(
                        onSuccess = { msg -> "Connected: $msg" },
                        onFailure = { err -> "Failed: ${err.localizedMessage ?: "Unreachable"}" }
                    )
                )
            }
        }
    }

    fun createIsolatedBrowserAccount(baseName: String = "Account") {
        val count = uiState.value.agents.size + 1
        val newAgent = AgentConfig(
            id = "acct-${UUID.randomUUID().toString().take(8)}",
            name = "$baseName $count",
            role = "In-App Browser Session",
            avatar = baseName.take(1).uppercase().ifBlank { "B" },
            colorHex = "#3B82F6",
            provider = AgentProvider.INAPP_BROWSER,
            customUrl = "https://muse.ai",
            sortOrder = count
        )
        viewModelScope.launch {
            repository.upsertAgent(newAgent)
            localState.update { it.copy(activeBrowserAgentId = newAgent.id) }
            showMessage("Created browser tab for ${newAgent.name}.")
        }
    }

    fun browserNavigate(agent: AgentConfig, action: String, url: String = "") {
        viewModelScope.launch {
            browserCoordinator.navigate(agent, action, url)
        }
    }

    fun browserRefreshStatus(agent: AgentConfig) {
        viewModelScope.launch {
            val status = browserCoordinator.refreshStatus(agent)
            val label = when {
                status.ready -> "${agent.name} is Chat Ready."
                status.needsLogin -> "${agent.name} needs login on page."
                else -> "Checked ${agent.name} page state."
            }
            showMessage(label)
        }
    }

    fun browserInjectQuickText(agent: AgentConfig, text: String, pressEnter: Boolean) {
        viewModelScope.launch {
            browserCoordinator.injectQuickText(agent, text, pressEnter)
        }
    }

    fun toggleAgentEnabled(agentId: String, enabled: Boolean) {
        val agent = uiState.value.agents.firstOrNull { it.id == agentId } ?: return
        viewModelScope.launch {
            repository.upsertAgent(agent.copy(enabled = enabled))
            if (agent.provider == AgentProvider.MUSE_BRIDGE) {
                val serverUrl = agent.customUrl.ifBlank { uiState.value.settings.bridgeServerUrl }
                val token = agent.apiKey.ifBlank { uiState.value.settings.bridgeAuthToken }
                val remoteId = agent.bridgeBotId.ifBlank { agent.id }
                if (serverUrl.isNotBlank()) {
                    inferenceService.bridgeClient.setBotEnabled(serverUrl, token, remoteId, enabled)
                        .onSuccess { remote ->
                            localState.update { st ->
                                st.copy(remoteBridgeStatuses = st.remoteBridgeStatuses + (remote.botId to remote.status))
                            }
                        }
                }
            }
        }
    }

    fun toggleAgentBroadcastSelected(agentId: String, selected: Boolean) {
        val agent = uiState.value.agents.firstOrNull { it.id == agentId } ?: return
        viewModelScope.launch {
            repository.upsertAgent(agent.copy(selectedForBroadcast = selected))
            if (agent.provider == AgentProvider.MUSE_BRIDGE) {
                val serverUrl = agent.customUrl.ifBlank { uiState.value.settings.bridgeServerUrl }
                val token = agent.apiKey.ifBlank { uiState.value.settings.bridgeAuthToken }
                val remoteId = agent.bridgeBotId.ifBlank { agent.id }
                if (serverUrl.isNotBlank()) {
                    inferenceService.bridgeClient.setBotSelected(serverUrl, token, remoteId, selected)
                }
            }
        }
    }

    fun setBotBroadcastEnabled(agentId: String, includeInBroadcast: Boolean) {
        val agent = uiState.value.agents.firstOrNull { it.id == agentId } ?: return
        val nextEnabled = if (includeInBroadcast) true else agent.enabled
        val enabledChanged = nextEnabled != agent.enabled
        viewModelScope.launch {
            repository.upsertAgent(
                agent.copy(
                    enabled = nextEnabled,
                    selectedForBroadcast = includeInBroadcast
                )
            )
            if (agent.provider == AgentProvider.MUSE_BRIDGE) {
                val serverUrl = agent.customUrl.ifBlank { uiState.value.settings.bridgeServerUrl }
                val token = agent.apiKey.ifBlank { uiState.value.settings.bridgeAuthToken }
                val remoteId = agent.bridgeBotId.ifBlank { agent.id }
                if (serverUrl.isNotBlank()) {
                    if (enabledChanged) {
                        inferenceService.bridgeClient.setBotEnabled(serverUrl, token, remoteId, nextEnabled)
                            .onSuccess { remote ->
                                localState.update { st ->
                                    st.copy(remoteBridgeStatuses = st.remoteBridgeStatuses + (remote.botId to remote.status))
                                }
                            }
                    }
                    inferenceService.bridgeClient.setBotSelected(serverUrl, token, remoteId, includeInBroadcast)
                }
            }
        }
    }

    fun setAllBotsBroadcastEnabled(includeInBroadcast: Boolean) {
        val currentAgents = uiState.value.agents
        if (currentAgents.isEmpty()) return
        val settings = uiState.value.settings
        viewModelScope.launch {
            currentAgents.forEach { agent ->
                val nextEnabled = if (includeInBroadcast) true else agent.enabled
                val enabledChanged = nextEnabled != agent.enabled
                repository.upsertAgent(
                    agent.copy(
                        enabled = nextEnabled,
                        selectedForBroadcast = includeInBroadcast
                    )
                )
                if (agent.provider == AgentProvider.MUSE_BRIDGE) {
                    val serverUrl = agent.customUrl.ifBlank { settings.bridgeServerUrl }
                    val token = agent.apiKey.ifBlank { settings.bridgeAuthToken }
                    val remoteId = agent.bridgeBotId.ifBlank { agent.id }
                    if (serverUrl.isNotBlank()) {
                        if (enabledChanged) {
                            inferenceService.bridgeClient.setBotEnabled(serverUrl, token, remoteId, nextEnabled)
                        }
                        inferenceService.bridgeClient.setBotSelected(serverUrl, token, remoteId, includeInBroadcast)
                    }
                }
            }
            showMessage(
                if (includeInBroadcast) "All bots enabled for broadcast." else "All bots excluded from broadcast."
            )
        }
    }

    fun syncConfiguredBridge() {
        val settings = uiState.value.settings
        if (settings.bridgeServerUrl.isBlank()) {
            showMessage("Set the Multi-Bot Bridge URL in Manage Agents first.")
            return
        }
        syncBridgeBots(settings.bridgeServerUrl, settings.bridgeAuthToken)
    }

    fun syncBridgeBots(serverUrl: String, authToken: String) {
        val normalized = MultiBotBridgeClient.normalizeBaseUrl(serverUrl)
        viewModelScope.launch {
            localState.update { it.copy(isSyncingBridge = true, bridgeSyncResult = null) }
            val currentSettings = uiState.value.settings
            repository.saveSettings(
                currentSettings.copy(
                    bridgeServerUrl = normalized,
                    bridgeAuthToken = authToken.trim()
                )
            )

            val trimmedToken = authToken.trim()
            if (trimmedToken.isNotBlank()) {
                repository.saveEncryptedAuthToken(
                    botId = "bridge-sync",
                    serverUrl = normalized,
                    rawToken = trimmedToken
                )
            }

            val result = inferenceService.bridgeClient.testAndFetchBots(normalized, trimmedToken)
            result.onSuccess { sync ->
                val existingAgents = uiState.value.agents
                val statusUpdates = mutableMapOf<String, AgentAvailabilityStatus>()
                sync.bots.forEachIndexed { idx, remote ->
                    statusUpdates[remote.botId] = remote.status
                    if (trimmedToken.isNotBlank()) {
                        repository.saveEncryptedAuthToken(
                            botId = remote.botId,
                            serverUrl = normalized,
                            rawToken = trimmedToken
                        )
                    }
                    val match = existingAgents.firstOrNull {
                        it.provider == AgentProvider.MUSE_BRIDGE &&
                            (it.bridgeBotId == remote.botId || it.id == "bridge-${remote.botId}")
                    }
                    val colorPalette = listOf("#3B82F6", "#10B981", "#8B5CF6", "#F59E0B", "#EC4899")
                    val upserted = (match ?: AgentConfig(
                        id = "bridge-${remote.botId}",
                        name = remote.displayName,
                        role = "Bridge (${remote.provider})",
                        avatar = remote.displayName.take(2).uppercase(),
                        colorHex = colorPalette[idx % colorPalette.size],
                        provider = AgentProvider.MUSE_BRIDGE,
                        sortOrder = existingAgents.size + idx
                    )).copy(
                        name = remote.displayName,
                        role = "Bridge · ${remote.provider}",
                        provider = AgentProvider.MUSE_BRIDGE,
                        customUrl = normalized,
                        apiKey = trimmedToken,
                        bridgeBotId = remote.botId,
                        enabled = remote.enabled,
                        selectedForBroadcast = remote.selectedForBroadcast
                    )
                    repository.upsertAgent(upserted)
                }

                bridgeSocketService.connect(normalized, trimmedToken)

                localState.update { st ->
                    st.copy(
                        isSyncingBridge = false,
                        remoteBridgeStatuses = st.remoteBridgeStatuses + statusUpdates,
                        bridgeSyncResult = if (sync.bots.isEmpty()) {
                            "Connected (${sync.providers.joinToString(", ")}). No bots registered on server yet."
                        } else {
                            "Connected · Synced ${sync.bots.size} bot(s) (${sync.providers.joinToString(", ")})"
                        }
                    )
                }
                showMessage("Multi-Bot Bridge synced (${sync.bots.size} bots).")
            }.onFailure { err ->
                localState.update { st ->
                    st.copy(
                        isSyncingBridge = false,
                        bridgeSyncResult = "Failed: ${err.localizedMessage ?: "Unable to reach Bridge server"}"
                    )
                }
            }
        }
    }

    fun initBridgeBotSession(agentId: String) {
        val agent = uiState.value.agents.firstOrNull { it.id == agentId } ?: return
        val serverUrl = agent.customUrl.ifBlank { uiState.value.settings.bridgeServerUrl }
        val token = agent.apiKey.ifBlank { uiState.value.settings.bridgeAuthToken }
        val remoteId = agent.bridgeBotId.ifBlank { agent.id }
        if (serverUrl.isBlank()) {
            showMessage("Set the Multi-Bot Bridge Server URL in Manage Agents first.")
            return
        }
        viewModelScope.launch {
            setAgentBusy(agentId, "Connecting...")
            val res = try {
                inferenceService.bridgeClient.initBotSession(serverUrl, token, remoteId)
            } finally {
                clearAgentBusy(agentId)
            }
            res.onSuccess { (status, message) ->
                localState.update { st ->
                    st.copy(
                        remoteBridgeStatuses = st.remoteBridgeStatuses + (remoteId to status),
                        agentErrorDetails = if (status == AgentAvailabilityStatus.ERROR) {
                            st.agentErrorDetails + (agentId to message)
                        } else {
                            st.agentErrorDetails - agentId
                        }
                    )
                }
                showMessage("${agent.name}: $message")
            }.onFailure { err ->
                val msg = err.localizedMessage ?: "Failed to initialize bot session."
                localState.update { st ->
                    st.copy(agentErrorDetails = st.agentErrorDetails + (agentId to msg))
                }
                showMessage("${agent.name}: $msg")
            }
        }
    }

    fun createBotAndBridge(
        displayName: String = "",
        targetUrl: String = "https://muse.ai"
    ) {
        if (localState.value.isCreatingBridgeBot) return
        viewModelScope.launch {
            localState.update { it.copy(isCreatingBridgeBot = true) }
            try {
                val srvStatus = withContext(Dispatchers.IO) {
                    embeddedBridgeServer.ensureStarted()
                }
                val existingAgents = uiState.value.agents
                val bridgeCount = existingAgents.count { it.provider == AgentProvider.MUSE_BRIDGE } + 1
                val resolvedName = displayName.trim().ifBlank {
                    "Muse Bot ${String.format("%02d", bridgeCount)}"
                }
                val normalizedTarget = AgentInferenceService.normalizeHttpUrl(targetUrl.ifBlank { "https://muse.ai" })
                val existingToken = uiState.value.settings.bridgeAuthToken.trim()
                    .ifBlank { embeddedBridgeServer.getPrimaryToken().orEmpty() }

                val createRes = inferenceService.bridgeClient.createBotAndToken(
                    serverUrl = srvStatus.baseUrl,
                    authToken = existingToken,
                    displayName = resolvedName,
                    provider = "muse-web",
                    targetUrl = normalizedTarget
                )

                createRes.onSuccess { created ->
                    repository.saveEncryptedAuthToken(
                        botId = created.bot.botId,
                        serverUrl = created.serverUrl,
                        rawToken = created.token
                    )
                    embeddedBridgeServer.registerExternalToken(created.bot.botId, created.token)

                    val palette = listOf("#10B981", "#3B82F6", "#8B5CF6", "#F59E0B", "#EC4899")
                    val colorHex = palette[(bridgeCount - 1) % palette.size]
                    val localId = "bridge-${created.bot.botId}"
                    val newAgent = AgentConfig(
                        id = localId,
                        name = created.bot.displayName,
                        role = "Muse Bridge (${InAppBrowserCoordinator.hostOf(normalizedTarget).ifBlank { "muse.ai" }})",
                        avatar = "M$bridgeCount",
                        colorHex = colorHex,
                        provider = AgentProvider.MUSE_BRIDGE,
                        customUrl = created.serverUrl,
                        apiKey = created.token,
                        endpoint = normalizedTarget,
                        bridgeBotId = created.bot.botId,
                        enabled = true,
                        selectedForBroadcast = true,
                        sortOrder = existingAgents.size + 1
                    )
                    repository.upsertAgent(newAgent)

                    val currentSettings = uiState.value.settings
                    repository.saveSettings(
                        currentSettings.copy(
                            bridgeServerUrl = created.serverUrl,
                            bridgeAuthToken = created.token
                        )
                    )

                    bridgeSocketService.connect(created.serverUrl, created.token)

                    localState.update { st ->
                        st.copy(
                            remoteBridgeStatuses = st.remoteBridgeStatuses + (created.bot.botId to created.bot.status),
                            createdBridgeCredentials = CreatedBridgeCredentials(
                                agentId = localId,
                                botId = created.bot.botId,
                                botName = created.bot.displayName,
                                serverUrl = created.serverUrl,
                                token = created.token,
                                targetUrl = normalizedTarget
                            )
                        )
                    }
                    showMessage("${created.bot.displayName} connected & encrypted token stored.")
                }.onFailure { err ->
                    showMessage(err.localizedMessage ?: "Failed to create Bridge bot.")
                }
            } finally {
                localState.update { it.copy(isCreatingBridgeBot = false) }
            }
        }
    }

    fun showBotTokenCredentials(agentId: String) {
        val agent = uiState.value.agents.firstOrNull { it.id == agentId } ?: return
        val remoteId = agent.bridgeBotId.ifBlank { agent.id }
        val serverUrl = agent.customUrl.ifBlank {
            uiState.value.embeddedBridgeUrl.ifBlank { uiState.value.settings.bridgeServerUrl }
        }
        viewModelScope.launch {
            val decryptedVaultToken = repository.getDecryptedTokenForBot(remoteId).orEmpty()
            val token = decryptedVaultToken
                .ifBlank { agent.apiKey }
                .ifBlank { uiState.value.settings.bridgeAuthToken }
                .ifBlank { embeddedBridgeServer.getPrimaryToken().orEmpty() }
            localState.update {
                it.copy(
                    createdBridgeCredentials = CreatedBridgeCredentials(
                        agentId = agent.id,
                        botId = remoteId,
                        botName = agent.name,
                        serverUrl = serverUrl,
                        token = token,
                        targetUrl = agent.endpoint.ifBlank { "https://muse.ai" }
                    )
                )
            }
        }
    }

    fun dismissBridgeTokenDialog() {
        localState.update { it.copy(createdBridgeCredentials = null) }
    }

    fun regenerateBotToken(agentId: String) {
        val agent = uiState.value.agents.firstOrNull { it.id == agentId } ?: return
        val remoteId = agent.bridgeBotId.ifBlank { agent.id }
        viewModelScope.launch {
            val srv = withContext(Dispatchers.IO) {
                embeddedBridgeServer.ensureStarted()
            }
            val newToken = embeddedBridgeServer.rotateTokenForBot(remoteId)
            val serverUrl = agent.customUrl.ifBlank { srv.baseUrl }
            repository.saveEncryptedAuthToken(
                botId = remoteId,
                serverUrl = serverUrl,
                rawToken = newToken
            )
            val updatedAgent = agent.copy(
                customUrl = serverUrl,
                apiKey = newToken
            )
            repository.upsertAgent(updatedAgent)
            val currentSettings = uiState.value.settings
            if (currentSettings.bridgeServerUrl.isBlank() || currentSettings.bridgeServerUrl == srv.baseUrl) {
                repository.saveSettings(
                    currentSettings.copy(
                        bridgeServerUrl = srv.baseUrl,
                        bridgeAuthToken = newToken
                    )
                )
            }
            bridgeSocketService.connect(serverUrl, newToken)
            localState.update { st ->
                st.copy(
                    createdBridgeCredentials = CreatedBridgeCredentials(
                        agentId = agent.id,
                        botId = remoteId,
                        botName = agent.name,
                        serverUrl = updatedAgent.customUrl,
                        token = newToken,
                        targetUrl = agent.endpoint.ifBlank { "https://muse.ai" }
                    )
                )
            }
            showMessage("Generated & encrypted fresh Muse AI Bridge token for ${agent.name}.")
        }
    }

    fun toggleEmbeddedBridgeServer(start: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (start) {
                val srv = embeddedBridgeServer.ensureStarted()
                withContext(Dispatchers.Main) {
                    showMessage("In-app Bridge Server running at ${srv.baseUrl}")
                }
            } else {
                embeddedBridgeServer.stop()
                withContext(Dispatchers.Main) {
                    showMessage("In-app Bridge Server stopped.")
                }
            }
        }
    }

    fun refreshMuseConnection() {
        if (localState.value.isRefreshingMuseConnection) return
        viewModelScope.launch {
            localState.update { it.copy(isRefreshingMuseConnection = true) }
            try {
                val activePairs = withContext(Dispatchers.IO) {
                    repository.getDecryptedActiveTokens()
                }
                embeddedBridgeServer.restoreValidTokens(activePairs)
                val srvStatus = withContext(Dispatchers.IO) {
                    embeddedBridgeServer.ensureStarted()
                }
                val settings = uiState.value.settings
                val bridgeUrl = settings.bridgeServerUrl.trim().ifBlank { srvStatus.baseUrl }
                val primaryToken = settings.bridgeAuthToken.trim()
                    .ifBlank { activePairs.firstOrNull()?.first.orEmpty() }
                    .ifBlank { embeddedBridgeServer.getPrimaryToken().orEmpty() }

                if (primaryToken.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        repository.touchAuthTokenUsed(primaryToken)
                    }
                }

                // Refresh browser sessions for any Muse-targeting agents
                val agents = uiState.value.agents
                val museAgents = agents.filter { agent ->
                    agent.id.equals("muse", ignoreCase = true) ||
                        agent.provider == AgentProvider.MUSE_BRIDGE ||
                        agent.customUrl.contains("muse.ai", ignoreCase = true) ||
                        agent.endpoint.contains("muse.ai", ignoreCase = true)
                }
                for (agent in museAgents) {
                    val existingBrowserStatus = browserCoordinator.statuses.value[agent.id]
                    if (existingBrowserStatus?.open == true || existingBrowserStatus?.hasSavedProfile == true) {
                        val refreshed = browserCoordinator.refreshStatus(agent)
                        if (agent.provider == AgentProvider.MUSE_BRIDGE) {
                            embeddedBridgeServer.markBotBrowserReady(
                                botId = agent.bridgeBotId.ifBlank { agent.id },
                                ready = refreshed.ready || refreshed.hasSavedProfile,
                                needsLogin = refreshed.needsLogin && !refreshed.ready
                            )
                        }
                    }
                    if (agent.provider == AgentProvider.MUSE_BRIDGE && bridgeUrl.isNotBlank()) {
                        val botId = agent.bridgeBotId.ifBlank { agent.id }
                        val botToken = agent.apiKey.ifBlank { primaryToken }
                        inferenceService.bridgeClient.initBotSession(bridgeUrl, botToken, botId)
                            .onSuccess { (status, message) ->
                                localState.update { st ->
                                    st.copy(
                                        remoteBridgeStatuses = st.remoteBridgeStatuses + (botId to status),
                                        agentErrorDetails = if (status == AgentAvailabilityStatus.ERROR) {
                                            st.agentErrorDetails + (agent.id to message)
                                        } else {
                                            st.agentErrorDetails - agent.id
                                        }
                                    )
                                }
                            }
                    }
                }

                val sentPing = bridgeSocketService.refreshMuseConnection()
                if (!sentPing && bridgeUrl.isNotBlank()) {
                    bridgeSocketService.connect(bridgeUrl, primaryToken)
                }

                localState.update {
                    it.copy(lastMuseRefreshMillis = System.currentTimeMillis())
                }
                val connectedNow = uiState.value.museConnectionStatus.isConnected
                showMessage(
                    if (connectedNow) {
                        "Muse AI connection verified."
                    } else {
                        "Muse AI is disconnected. Sign in via Browser or create a Bridge bot."
                    }
                )
            } finally {
                localState.update { it.copy(isRefreshingMuseConnection = false) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        sendJob?.cancel()
        sseJob?.cancel()
        recordingTimerJob?.cancel()
        releaseRecorder(deleteFile = true)
        bridgeSocketService.disconnect()
        embeddedBridgeServer.stop()
        networkCallback?.let { cb ->
            try {
                val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
        uiState.value.agents.forEach { agent ->
            browserCoordinator.closeSession(agent.id)
        }
    }

    private data class LocalUiControls(
        val viewMode: ChatViewMode = ChatViewMode.BOARDROOM,
        val selectedDirectAgentId: String? = null,
        val composerText: String = "",
        val pendingAttachment: AttachmentData? = null,
        val isSending: Boolean = false,
        val thinkingStatus: String? = null,
        val busyAgentDetails: Map<String, String> = emptyMap(),
        val remoteBridgeStatuses: Map<String, AgentAvailabilityStatus> = emptyMap(),
        val agentErrorDetails: Map<String, String> = emptyMap(),
        val streamingDrafts: Map<String, String> = emptyMap(),
        val isNetworkAvailable: Boolean = true,
        val isRecordingVoice: Boolean = false,
        val recordingSeconds: Int = 0,
        val isTranscribingVoice: Boolean = false,
        val showManageAgentsDialog: Boolean = false,
        val manageSelectedAgentId: String? = null,
        val showInAppBrowserDialog: Boolean = false,
        val activeBrowserAgentId: String? = null,
        val showBotsScreen: Boolean = false,
        val webhookTestResult: String? = null,
        val isTestingWebhook: Boolean = false,
        val bridgeSyncResult: String? = null,
        val isSyncingBridge: Boolean = false,
        val bridgeSseConnected: Boolean = false,
        val embeddedBridgeRunning: Boolean = false,
        val embeddedBridgeUrl: String = "",
        val isCreatingBridgeBot: Boolean = false,
        val createdBridgeCredentials: CreatedBridgeCredentials? = null,
        val socketState: BridgeSocketState = BridgeSocketState(),
        val storedAuthTokenCount: Int = 0,
        val isRefreshingMuseConnection: Boolean = false,
        val lastMuseRefreshMillis: Long = 0L,
        val snackbarMessage: String? = null
    )

    companion object {
        private const val MAX_ATTACHMENT_BYTES = 12 * 1024 * 1024

        private fun guessMimeFromName(filename: String): String {
            val lower = filename.lowercase()
            return when {
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                lower.endsWith(".webp") -> "image/webp"
                lower.endsWith(".gif") -> "image/gif"
                lower.endsWith(".mp3") -> "audio/mpeg"
                lower.endsWith(".wav") -> "audio/wav"
                lower.endsWith(".m4a") -> "audio/mp4"
                lower.endsWith(".ogg") -> "audio/ogg"
                lower.endsWith(".json") -> "application/json"
                lower.endsWith(".md") -> "text/markdown"
                lower.endsWith(".csv") -> "text/csv"
                lower.endsWith(".txt") -> "text/plain"
                else -> "application/octet-stream"
            }
        }
    }
}
