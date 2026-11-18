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
import com.example.data.model.AgentAvailabilityStatus
import com.example.data.model.AgentConfig
import com.example.data.model.AgentProvider
import com.example.data.model.AttachmentData
import com.example.data.model.BoardroomMessage
import com.example.data.model.BoardroomSettings
import com.example.data.model.ChatViewMode
import com.example.data.model.CollaborationRole
import com.example.data.model.InAppBrowserStatus
import com.example.data.model.resolveAgentAvailability
import com.example.data.repository.BoardroomRepository
import com.example.engine.AgentCollaboration
import com.example.engine.AgentInferenceService
import com.example.engine.InAppBrowserCoordinator
import com.example.engine.IncomingHandoffContext
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class BoardroomUiState(
    val agents: List<AgentConfig> = emptyList(),
    val messages: List<BoardroomMessage> = emptyList(),
    val settings: BoardroomSettings = BoardroomSettings(),
    val browserStatuses: Map<String, InAppBrowserStatus> = emptyMap(),
    val agentStatuses: Map<String, AgentAvailabilityStatus> = emptyMap(),
    val busyAgentDetails: Map<String, String> = emptyMap(),
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
    val webhookTestResult: String? = null,
    val isTestingWebhook: Boolean = false,
    val snackbarMessage: String? = null
)

class BoardroomViewModel(application: Application) : AndroidViewModel(application) {

    private val database = BoardroomDatabase.getInstance(application)
    private val repository = BoardroomRepository(database.boardroomDao())
    val browserCoordinator = InAppBrowserCoordinator(application)
    private val inferenceService = AgentInferenceService(application, browserCoordinator)

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
            agent.id to resolveAgentAvailability(
                agent = agent,
                browserStatus = statuses[agent.id],
                isBusy = local.busyAgentDetails.containsKey(agent.id),
                isNetworkAvailable = local.isNetworkAvailable
            )
        }

        BoardroomUiState(
            agents = agents,
            messages = messages,
            settings = settings,
            browserStatuses = statuses,
            agentStatuses = resolvedStatuses,
            busyAgentDetails = local.busyAgentDetails,
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
            webhookTestResult = local.webhookTestResult,
            isTestingWebhook = local.isTestingWebhook,
            snackbarMessage = local.snackbarMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BoardroomUiState()
    )

    private var sendJob: Job? = null
    private var recordingTimerJob: Job? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        registerNetworkMonitor()
        viewModelScope.launch {
            repository.ensureSeeded()
        }
        viewModelScope.launch {
            repository.agentsFlow.collect { agents ->
                browserCoordinator.syncAgents(agents)
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
                    else -> agents.map { agent ->
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

                val round1Replies = coroutineScope {
                    initialTargets.map { (agent, prompt) ->
                        async {
                            setAgentBusy(
                                agent.id,
                                if (supervisorDelegationOnly) "Delegating..." else "Replying..."
                            )
                            val replyText = try {
                                inferenceService.invokeAgent(agent, prompt, attachment)
                            } finally {
                                clearAgentBusy(agent.id)
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
                                hop = 1,
                                maxHops = maxHops,
                                isSupervisor = agent.isSupervisor,
                                collaborationRole = roleTag,
                                timestampMillis = System.currentTimeMillis()
                            )
                            repository.insertMessage(msg)
                            msg
                        }
                    }.awaitAll()
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
                                    inferenceService.invokeAgent(job.toAgent, handoffPrompt, null)
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
                            inferenceService.invokeAgent(supervisor, synthesisPrompt, null)
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
        viewModelScope.launch {
            browserCoordinator.closeSession(agentId)
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

    override fun onCleared() {
        super.onCleared()
        sendJob?.cancel()
        recordingTimerJob?.cancel()
        releaseRecorder(deleteFile = true)
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
        val isNetworkAvailable: Boolean = true,
        val isRecordingVoice: Boolean = false,
        val recordingSeconds: Int = 0,
        val isTranscribingVoice: Boolean = false,
        val showManageAgentsDialog: Boolean = false,
        val manageSelectedAgentId: String? = null,
        val showInAppBrowserDialog: Boolean = false,
        val activeBrowserAgentId: String? = null,
        val webhookTestResult: String? = null,
        val isTestingWebhook: Boolean = false,
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
