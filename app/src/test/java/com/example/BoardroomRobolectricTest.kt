package com.example

import android.content.Context
import android.os.Looper
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.example.data.local.BoardroomDatabase
import com.example.data.local.TokenCryptoManager
import com.example.data.model.AgentAvailabilityStatus
import com.example.data.model.AgentConfig
import com.example.data.model.AgentProvider
import com.example.data.model.BoardroomMessage
import com.example.data.model.BoardroomSettings
import com.example.data.model.ChatViewMode
import com.example.data.model.InAppBrowserStatus
import com.example.data.model.MuseConnectionStatus
import com.example.data.model.resolveAgentAvailability
import com.example.data.repository.BoardroomRepository
import com.example.engine.BridgeSocketService
import com.example.engine.BridgeSseEventData
import com.example.engine.EmbeddedBridgeBotRecord
import com.example.engine.EmbeddedBridgeExecutor
import com.example.engine.EmbeddedBridgeServer
import com.example.engine.MultiBotBridgeClient
import com.example.ui.BoardroomApp
import com.example.ui.BoardroomViewModel
import com.example.ui.components.AgentSidebarContent
import com.example.ui.components.BoardroomLiveRosterStrip
import com.example.ui.components.MuseConnectionStatusCard
import com.example.ui.screens.BotsScreen
import com.example.ui.theme.MuseBoardroomTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class BoardroomRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun appNameMatchesStringsXml() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Muse Boardroom", context.getString(R.string.app_name))
    }

    @Test
    fun resolveAgentAvailability_handlesAllFiveStates() {
        val gemini = AgentConfig(id = "gemini", name = "Gemini", provider = AgentProvider.GEMINI)
        val openAiNoKey = AgentConfig(id = "openai", name = "ChatGPT", provider = AgentProvider.OPENAI, apiKey = "")
        val openAiWithKey = openAiNoKey.copy(apiKey = "sk-test")
        val browserAgent = AgentConfig(id = "muse", name = "Muse AI", provider = AgentProvider.INAPP_BROWSER)
        val bridgeAgent = AgentConfig(
            id = "bridge-bot-01",
            name = "Bot 01",
            provider = AgentProvider.MUSE_BRIDGE,
            bridgeBotId = "bot-01"
        )
        val simAgent = AgentConfig(id = "demo", name = "Demo", provider = AgentProvider.SIMULATION)

        // Disabled overrides everything to Offline
        assertEquals(
            AgentAvailabilityStatus.OFFLINE,
            resolveAgentAvailability(gemini.copy(enabled = false), browserStatus = null, isBusy = true, isNetworkAvailable = true)
        )
        // Busy overrides ready states
        assertEquals(
            AgentAvailabilityStatus.BUSY,
            resolveAgentAvailability(gemini, browserStatus = null, isBusy = true, isNetworkAvailable = true)
        )
        // Error overrides idle ready state
        assertEquals(
            AgentAvailabilityStatus.ERROR,
            resolveAgentAvailability(gemini, browserStatus = null, isBusy = false, errorMessage = "Timeout")
        )
        // Gemini is Online when network available
        assertEquals(
            AgentAvailabilityStatus.ONLINE,
            resolveAgentAvailability(gemini, browserStatus = null, isBusy = false, isNetworkAvailable = true)
        )
        // Network loss moves live agents Offline
        assertEquals(
            AgentAvailabilityStatus.OFFLINE,
            resolveAgentAvailability(gemini, browserStatus = null, isBusy = false, isNetworkAvailable = false)
        )
        // OpenAI depends on API key presence (Needs Auth when missing)
        assertEquals(
            AgentAvailabilityStatus.NEEDS_AUTH,
            resolveAgentAvailability(openAiNoKey, browserStatus = null, isBusy = false, isNetworkAvailable = true)
        )
        assertEquals(
            AgentAvailabilityStatus.ONLINE,
            resolveAgentAvailability(openAiWithKey, browserStatus = null, isBusy = false, isNetworkAvailable = true)
        )
        // Browser agent maps needsLogin -> NEEDS_AUTH and ready -> ONLINE
        assertEquals(
            AgentAvailabilityStatus.NEEDS_AUTH,
            resolveAgentAvailability(browserAgent, browserStatus = InAppBrowserStatus("muse", needsLogin = true), isBusy = false)
        )
        assertEquals(
            AgentAvailabilityStatus.ONLINE,
            resolveAgentAvailability(browserAgent, browserStatus = InAppBrowserStatus("muse", ready = true), isBusy = false)
        )
        // Multi-Bot Bridge honors remote bot status
        assertEquals(
            AgentAvailabilityStatus.NEEDS_AUTH,
            resolveAgentAvailability(bridgeAgent, browserStatus = null, isBusy = false, remoteBridgeStatus = AgentAvailabilityStatus.NEEDS_AUTH)
        )
        assertEquals(
            AgentAvailabilityStatus.ONLINE,
            resolveAgentAvailability(bridgeAgent, browserStatus = null, isBusy = false, remoteBridgeStatus = AgentAvailabilityStatus.ONLINE)
        )
        // Simulation is Offline
        assertEquals(
            AgentAvailabilityStatus.OFFLINE,
            resolveAgentAvailability(simAgent, browserStatus = null, isBusy = false, isNetworkAvailable = true)
        )
    }

    @Test
    fun multiBotBridgeClient_parsesBotsDispatchAndSseEvents() {
        val botsJson = """
            {
              "bots": [
                {
                  "botId": "bot-01",
                  "displayName": "Bot 01",
                  "provider": "muse-web",
                  "enabled": true,
                  "selectedForBroadcast": true,
                  "health": { "status": "online", "reason": "Ready" }
                },
                {
                  "botId": "bot-02",
                  "displayName": "Bot 02",
                  "provider": "muse-web",
                  "enabled": true,
                  "selectedForBroadcast": false,
                  "health": { "status": "needs_auth", "reason": "Sign in required" }
                }
              ]
            }
        """.trimIndent()
        val parsedBots = MultiBotBridgeClient.parseBotsResponse(botsJson)
        assertEquals(2, parsedBots.size)
        assertEquals("bot-01", parsedBots[0].botId)
        assertEquals(AgentAvailabilityStatus.ONLINE, parsedBots[0].status)
        assertEquals(AgentAvailabilityStatus.NEEDS_AUTH, parsedBots[1].status)
        assertEquals(false, parsedBots[1].selectedForBroadcast)

        val dispatchJson = """
            {
              "dispatchBatchId": "batch_1",
              "conversationId": "boardroom-main",
              "parentMessageId": "ceo-99",
              "tasks": [
                { "taskId": "task_1", "botId": "bot-01", "provider": "muse-web", "status": "completed", "responseMessageId": "msg_1" },
                { "taskId": "task_2", "botId": "bot-02", "provider": "muse-web", "status": "needs_auth", "errorCode": "NEEDS_AUTH", "errorMessage": "Login wall" }
              ],
              "responses": [
                { "id": "msg_1", "deliveryTaskId": "task_1", "content": "Response from Account 01" }
              ]
            }
        """.trimIndent()
        val batch = MultiBotBridgeClient.parseDispatchResult(dispatchJson)
        assertEquals("batch_1", batch.dispatchBatchId)
        assertEquals(2, batch.items.size)
        assertEquals("Response from Account 01", batch.items[0].text)
        assertEquals("needs_auth", batch.items[1].status)

        val sseEvents = MultiBotBridgeClient.parseSseEvents(
            "task:delta",
            """{"taskId":"task_1","dispatchBatchId":"batch_1","parentMessageId":"ceo-99","botId":"bot-01","fullTextSoFar":"Partial text"}"""
        )
        assertEquals(1, sseEvents.size)
        val delta = sseEvents[0] as BridgeSseEventData.TaskDelta
        assertEquals("Partial text", delta.fullTextSoFar)
    }

    @Test
    fun roomRepository_seedsDefaultAgentsAndPersistsBridgeAndTaskFields() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, BoardroomDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = BoardroomRepository(db.boardroomDao())
            repo.ensureSeeded()

            val agents = repo.agentsFlow.first()
            assertEquals(4, agents.size)
            assertTrue(agents.any { it.name == "Gemini" && it.provider == AgentProvider.GEMINI })

            val bridgeBot = AgentConfig(
                id = "bridge-bot-01",
                name = "Bot 01",
                role = "Bridge · muse-web",
                provider = AgentProvider.MUSE_BRIDGE,
                customUrl = "https://bridge.example.com",
                bridgeBotId = "bot-01",
                enabled = true,
                selectedForBroadcast = false,
                isSupervisor = true
            )
            repo.upsertAgent(bridgeBot)
            repo.saveSettings(
                BoardroomSettings(
                    maxCollaborationHops = 4,
                    supervisorAgentId = "bridge-bot-01",
                    bridgeServerUrl = "https://bridge.example.com",
                    bridgeAuthToken = "tok-123"
                )
            )
            repo.insertMessage(
                BoardroomMessage(
                    id = "msg-b1",
                    sender = "bridge-bot-01",
                    role = "muse-web",
                    senderName = "Bot 01",
                    text = "Reply from Bot 01",
                    parentMessageId = "ceo-1",
                    deliveryTaskId = "task_1",
                    dispatchBatchId = "batch_1"
                )
            )

            val updatedAgents = repo.agentsFlow.first()
            assertEquals(5, updatedAgents.size)
            val savedBridge = updatedAgents.first { it.id == "bridge-bot-01" }
            assertEquals("bot-01", savedBridge.bridgeBotId)
            assertEquals(false, savedBridge.selectedForBroadcast)

            val updatedSettings = repo.settingsFlow.first()
            assertEquals(4, updatedSettings.maxCollaborationHops)
            assertEquals("https://bridge.example.com", updatedSettings.bridgeServerUrl)

            val savedMsgs = repo.messagesFlow.first()
            val linked = savedMsgs.first { it.id == "msg-b1" }
            assertEquals("task_1", linked.deliveryTaskId)
            assertEquals("ceo-1", linked.parentMessageId)
        } finally {
            db.close()
        }
    }

    @Test
    fun statusIndicators_displayOnlineBusyAndOfflineAcrossSidebarAndRosterStrip() {
        val agents = listOf(
            AgentConfig(id = "gemini", name = "Gemini", provider = AgentProvider.GEMINI),
            AgentConfig(id = "muse", name = "Muse AI", provider = AgentProvider.INAPP_BROWSER),
            AgentConfig(id = "chatgpt", name = "ChatGPT", provider = AgentProvider.OPENAI)
        )
        val statuses = mapOf(
            "gemini" to AgentAvailabilityStatus.ONLINE,
            "muse" to AgentAvailabilityStatus.BUSY,
            "chatgpt" to AgentAvailabilityStatus.OFFLINE
        )
        val busyDetails = mapOf("muse" to "Replying...")

        composeRule.setContent {
            MuseBoardroomTheme {
                androidx.compose.foundation.layout.Column {
                    BoardroomLiveRosterStrip(
                        agents = agents,
                        agentStatuses = statuses,
                        busyDetails = busyDetails,
                        selectedDirectAgentId = null,
                        isDirectMode = false,
                        onSelectAgent = {}
                    )
                    AgentSidebarContent(
                        agents = agents,
                        settings = BoardroomSettings(),
                        browserStatuses = emptyMap(),
                        agentStatuses = statuses,
                        busyDetails = busyDetails,
                        viewMode = ChatViewMode.BOARDROOM,
                        selectedDirectAgentId = null,
                        onOpenBrowser = {},
                        onAddAgent = {},
                        onSelectDirectAgent = {},
                        onEditAgent = {},
                        onDeleteAgent = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("live_agent_status_strip").assertIsDisplayed()
        composeRule.onNodeWithTag("live_status_pill_gemini").assertContentDescriptionContains("Online", substring = true)
        composeRule.onNodeWithTag("live_status_pill_muse").assertContentDescriptionContains("Busy", substring = true)
        composeRule.onNodeWithTag("live_status_pill_chatgpt").assertContentDescriptionContains("Offline", substring = true)

        composeRule.onNodeWithTag("agent_status_badge_gemini").assertIsDisplayed()
        composeRule.onNodeWithTag("agent_status_badge_muse").assertIsDisplayed()
        composeRule.onNodeWithTag("agent_status_badge_chatgpt").assertIsDisplayed()
        composeRule.onNodeWithText("1 online · 1 busy · 1 offline").assertIsDisplayed()
        composeRule.onAllNodes(androidx.compose.ui.test.hasText("Replying..."))[0].assertIsDisplayed()
    }

    @Test
    fun boardroomApp_rendersCoreUiAndHandlesComposerAndDialogs() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = BoardroomViewModel(app)

        composeRule.setContent {
            MuseBoardroomTheme {
                BoardroomApp(
                    widthSizeClass = WindowWidthSizeClass.Compact,
                    viewModel = vm
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Muse Boardroom").assertIsDisplayed()
        composeRule.onNodeWithTag("live_agent_status_strip").assertIsDisplayed()
        composeRule.onNodeWithTag("command_input").assertIsDisplayed()
        composeRule.onNodeWithTag("send_button").assertIsDisplayed()
        composeRule.onNodeWithTag("manage_agents_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("manage_agents_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("close_manage_agents_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("command_input").performTextInput("Hello team")
        composeRule.waitForIdle()
        assertEquals("Hello team", vm.uiState.value.composerText)
    }

    @Test
    fun botsScreen_listsBotsDisplaysStatusesAndTogglesBroadcastStream() {
        val bots = listOf(
            AgentConfig(
                id = "bot-01",
                name = "Bot 01",
                provider = AgentProvider.MUSE_BRIDGE,
                bridgeBotId = "bot-01",
                enabled = true,
                selectedForBroadcast = true
            ),
            AgentConfig(
                id = "bot-02",
                name = "Bot 02",
                provider = AgentProvider.OPENAI,
                enabled = true,
                selectedForBroadcast = false
            ),
            AgentConfig(
                id = "bot-03",
                name = "Bot 03",
                provider = AgentProvider.INAPP_BROWSER,
                enabled = false,
                selectedForBroadcast = false
            )
        )
        val statuses = mapOf(
            "bot-01" to AgentAvailabilityStatus.ONLINE,
            "bot-02" to AgentAvailabilityStatus.OFFLINE,
            "bot-03" to AgentAvailabilityStatus.NEEDS_AUTH
        )
        val broadcastEvents = mutableListOf<Pair<String, Boolean>>()
        val bulkEvents = mutableListOf<Boolean>()

        composeRule.setContent {
            MuseBoardroomTheme {
                BotsScreen(
                    bots = bots,
                    agentStatuses = statuses,
                    browserStatuses = emptyMap(),
                    busyDetails = emptyMap(),
                    errorDetails = emptyMap(),
                    settings = BoardroomSettings(),
                    bridgeSseConnected = false,
                    isSyncingBridge = false,
                    onToggleBroadcast = { id, checked -> broadcastEvents.add(id to checked) },
                    onToggleBotEnabled = { _, _ -> },
                    onSetAllBroadcast = { all -> bulkEvents.add(all) },
                    onSyncBridge = {},
                    onAddBot = {},
                    onEditBot = {},
                    onOpenBrowserForBot = {},
                    onInitBridgeBot = {},
                    onBack = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("bots_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("muse_connection_status_card").assertIsDisplayed()
        composeRule.onNodeWithTag("bots_screen_list").assertIsDisplayed()

        composeRule.onNodeWithTag("bots_screen_list").performScrollToNode(hasTestTag("bot_screen_card_bot-01"))
        composeRule.onNodeWithTag("bot_screen_card_bot-01").assertIsDisplayed()
        composeRule.onNodeWithTag("bot_screen_status_badge_bot-01").assertContentDescriptionContains("Online", substring = true)
        composeRule.onNodeWithTag("bot_broadcast_switch_bot-01").assertIsOn()
        composeRule.onNodeWithTag("bot_broadcast_switch_bot-01").performClick()

        composeRule.onNodeWithTag("bots_screen_list").performScrollToNode(hasTestTag("bot_screen_card_bot-02"))
        composeRule.onNodeWithTag("bot_screen_card_bot-02").assertIsDisplayed()
        composeRule.onNodeWithTag("bot_screen_status_badge_bot-02").assertContentDescriptionContains("Offline", substring = true)
        composeRule.onNodeWithTag("bot_broadcast_switch_bot-02").assertIsOff()
        composeRule.onNodeWithTag("bot_broadcast_switch_bot-02").performClick()

        composeRule.onNodeWithTag("bots_screen_list").performScrollToNode(hasTestTag("bot_screen_card_bot-03"))
        composeRule.onNodeWithTag("bot_screen_card_bot-03").assertIsDisplayed()
        composeRule.onNodeWithTag("bot_screen_status_badge_bot-03").assertContentDescriptionContains("Needs Auth", substring = true)
        composeRule.onNodeWithTag("bot_broadcast_switch_bot-03").assertIsOff()

        composeRule.onNodeWithTag("bots_screen_list").performScrollToNode(hasTestTag("bots_enable_all_broadcast_button"))
        composeRule.onNodeWithTag("bots_enable_all_broadcast_button").performClick()
        composeRule.onNodeWithTag("bots_disable_all_broadcast_button").performClick()

        assertEquals(listOf("bot-01" to false, "bot-02" to true), broadcastEvents)
        assertEquals(listOf(true, false), bulkEvents)

        // Filter tabs narrow the list properly
        composeRule.onNodeWithTag("bots_filter_online").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("bots_screen_list").performScrollToNode(hasTestTag("bot_screen_card_bot-01"))
        composeRule.onNodeWithTag("bot_screen_card_bot-01").assertIsDisplayed()
        composeRule.onNodeWithTag("bot_screen_card_bot-02").assertDoesNotExist()
    }

    @Test
    fun boardroomApp_navigatesToBotsScreenAndUpdatesBroadcastSelection() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = BoardroomViewModel(app)

        composeRule.setContent {
            MuseBoardroomTheme {
                BoardroomApp(
                    widthSizeClass = WindowWidthSizeClass.Compact,
                    viewModel = vm
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            vm.uiState.value.agents.isNotEmpty()
        }

        composeRule.onNodeWithTag("open_bots_screen_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("bots_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("bots_screen_list").assertIsDisplayed()

        val firstBot = vm.uiState.value.agents.first()
        val initialSelected = firstBot.selectedForBroadcast && firstBot.enabled
        composeRule.onNodeWithTag("bot_broadcast_switch_${firstBot.id}").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val updated = vm.uiState.value.agents.first { it.id == firstBot.id }
            (updated.selectedForBroadcast && updated.enabled) != initialSelected
        }

        if (initialSelected) {
            composeRule.onNodeWithTag("bot_broadcast_switch_${firstBot.id}").assertIsOff()
        } else {
            composeRule.onNodeWithTag("bot_broadcast_switch_${firstBot.id}").assertIsOn()
        }

        composeRule.onNodeWithTag("bots_screen_back_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("command_input").assertIsDisplayed()
        composeRule.onNodeWithTag("bots_screen").assertDoesNotExist()
        vm.embeddedBridgeServer.stop()
    }

    @Test
    fun embeddedBridgeServer_createsBotMintsTokenAuthenticatesAndDispatchesOverHttp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("muse_embedded_bridge_server", Context.MODE_PRIVATE)
            .edit().clear().commit()

        val executor = object : EmbeddedBridgeExecutor {
            override suspend fun checkBotSession(bot: EmbeddedBridgeBotRecord): Pair<AgentAvailabilityStatus, String> {
                return AgentAvailabilityStatus.ONLINE to "Connected to ${bot.targetUrl}"
            }

            override suspend fun executePrompt(
                bot: EmbeddedBridgeBotRecord,
                prompt: String,
                onPartialDelta: suspend (String) -> Unit
            ): Result<String> {
                val answer = "Muse AI Reply: $prompt"
                onPartialDelta(answer)
                return Result.success(answer)
            }
        }

        val server = EmbeddedBridgeServer(context, preferredPort = 0, executor = executor)
        val client = MultiBotBridgeClient()
        try {
            val status = server.ensureStarted()
            assertTrue(status.running)
            assertTrue(status.baseUrl.startsWith("http://127.0.0.1:"))

            val createdRes = client.createBotAndToken(
                serverUrl = status.baseUrl,
                displayName = "Muse Bot 01",
                targetUrl = "https://muse.ai"
            )
            assertTrue("Expected bot creation to succeed: ${createdRes.exceptionOrNull()}", createdRes.isSuccess)
            val created = createdRes.getOrThrow()
            assertTrue(created.token.startsWith("mb_live_"))
            assertEquals("Muse Bot 01", created.bot.displayName)
            assertEquals(AgentAvailabilityStatus.ONLINE, created.bot.status)

            // Unauthenticated bot fetch should fail once a token exists
            val unauth = client.testAndFetchBots(status.baseUrl, "")
            assertFalse(unauth.isSuccess)

            // Authenticated fetch succeeds and lists the created bot
            val authSync = client.testAndFetchBots(status.baseUrl, created.token).getOrThrow()
            assertEquals(1, authSync.bots.size)
            assertEquals(created.bot.botId, authSync.bots[0].botId)

            // Dispatch executes through EmbeddedBridgeServer and returns reply
            val dispatch = client.dispatchBroadcast(
                serverUrl = status.baseUrl,
                authToken = created.token,
                conversationId = "boardroom-main",
                parentMessageId = "ceo-42",
                prompt = "Summarize Q4 plan",
                targetBotIds = listOf(created.bot.botId)
            ).getOrThrow()
            assertEquals(1, dispatch.items.size)
            assertEquals("completed", dispatch.items[0].status)
            assertEquals("Muse AI Reply: Summarize Q4 plan", dispatch.items[0].text)
        } finally {
            server.stop()
        }
    }

    @Test
    fun createBotBridgeButton_completesServerRegistrationAndDisplaysTokenDialog() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        app.getSharedPreferences("muse_embedded_bridge_server", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val vm = BoardroomViewModel(app)

        try {
            composeRule.setContent {
                MuseBoardroomTheme {
                    BoardroomApp(
                        widthSizeClass = WindowWidthSizeClass.Compact,
                        viewModel = vm
                    )
                }
            }
            composeRule.waitForIdle()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                vm.uiState.value.agents.isNotEmpty()
            }

            composeRule.onNodeWithTag("open_bots_screen_button").performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("create_bot_bridge_button").assertIsDisplayed()
            composeRule.onNodeWithTag("create_bot_bridge_button").performClick()

            composeRule.waitUntil(timeoutMillis = 6_000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                vm.uiState.value.createdBridgeCredentials != null
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("bridge_token_dialog").assertIsDisplayed()
            composeRule.onNodeWithTag("bridge_generated_token_text").assertIsDisplayed()
            composeRule.onNodeWithTag("copy_bridge_token_button").assertIsDisplayed()
            composeRule.onNodeWithTag("connect_muse_ai_button").assertIsDisplayed()

            val creds = vm.uiState.value.createdBridgeCredentials!!
            assertTrue(creds.token.startsWith("mb_live_"))
            assertTrue(creds.serverUrl.startsWith("http://127.0.0.1:"))
            assertTrue(
                vm.uiState.value.agents.any {
                    it.provider == AgentProvider.MUSE_BRIDGE &&
                        it.bridgeBotId == creds.botId &&
                        it.apiKey == creds.token
                }
            )
        } finally {
            vm.embeddedBridgeServer.stop()
        }
    }

    @Test
    fun tokenCryptoAndRoomDao_encryptsPersistsRevokesAndMigratesTokens(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, BoardroomDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val crypto = TokenCryptoManager(context)
            val dao = db.boardroomDao()
            val tokenDao = db.bridgeAuthTokenDao()
            val repo = BoardroomRepository(dao, tokenDao, crypto)

            val rawToken = "mb_live_aabbccddeeff00112233445566778899"
            repo.saveEncryptedAuthToken(
                botId = "bot-01",
                serverUrl = "http://127.0.0.1:8787",
                rawToken = rawToken
            )

            // Stored entity in SQLite must never contain plaintext token
            val storedEntity = tokenDao.getActiveTokenForBotOnce("bot-01")
            assertTrue(storedEntity != null)
            assertNotEquals(rawToken, storedEntity!!.encryptedToken)
            assertTrue(storedEntity.encryptedToken.startsWith("v1:"))
            assertEquals(TokenCryptoManager.sha256Hex(rawToken), storedEntity.tokenHash)
            assertEquals("bot-01", storedEntity.botId)
            assertTrue(storedEntity.isActive)

            // Repository decrypts the token transparently for active sessions
            val decrypted = repo.getDecryptedTokenForBot("bot-01")
            assertEquals(rawToken, decrypted)
            assertEquals(1, repo.activeAuthTokensFlow.first().size)

            // Rotating token marks the prior token inactive and persists the new encrypted one
            val rotatedToken = "mb_live_99887766554433221100ffeeddccbbaa"
            repo.saveEncryptedAuthToken(
                botId = "bot-01",
                serverUrl = "http://127.0.0.1:8787",
                rawToken = rotatedToken
            )
            assertEquals(rotatedToken, repo.getDecryptedTokenForBot("bot-01"))
            assertEquals(1, repo.getDecryptedActiveTokens().size)

            // Revoking removes active session access
            repo.revokeTokensForBot("bot-01")
            assertNull(repo.getDecryptedTokenForBot("bot-01"))
            assertEquals(0, repo.activeAuthTokensFlow.first().size)

            // Verify MIGRATION_2_3 SQL executes cleanly on a v2 schema database
            val dbName = "migration_test_${System.currentTimeMillis()}.db"
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbName)
                    .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE IF NOT EXISTS agents (id TEXT NOT NULL PRIMARY KEY)")
                        }
                        override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    })
                    .build()
            )
            val rawDb = helper.writableDatabase
            BoardroomDatabase.MIGRATION_2_3.migrate(rawDb)
            val cursor = rawDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='bridge_auth_tokens'")
            assertTrue(cursor.moveToFirst())
            cursor.close()
            helper.close()
            context.deleteDatabase(dbName)
        } finally {
            db.close()
        }
        Unit
    }

    @Test
    fun bridgeSocketService_establishesPersistentSocketIoConnectionAndUpdatesMuseStatus() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("muse_embedded_bridge_server", Context.MODE_PRIVATE)
            .edit().clear().commit()

        val executor = object : EmbeddedBridgeExecutor {
            override suspend fun checkBotSession(bot: EmbeddedBridgeBotRecord): Pair<AgentAvailabilityStatus, String> {
                return AgentAvailabilityStatus.ONLINE to "Connected to Muse AI"
            }

            override suspend fun executePrompt(
                bot: EmbeddedBridgeBotRecord,
                prompt: String,
                onPartialDelta: suspend (String) -> Unit
            ): Result<String> = Result.success("ok")
        }

        val server = EmbeddedBridgeServer(context, preferredPort = 0, executor = executor)
        val client = MultiBotBridgeClient()
        val socketService = BridgeSocketService()

        try {
            val srv = server.ensureStarted()
            val created = client.createBotAndToken(
                serverUrl = srv.baseUrl,
                displayName = "Muse Socket Bot",
                targetUrl = "https://muse.ai"
            ).getOrThrow()

            socketService.connect(srv.baseUrl, created.token)

            var attempts = 0
            while (attempts < 80 && !socketService.state.value.connected) {
                delay(50)
                attempts++
            }
            assertTrue(
                "Expected Socket.IO to connect (lastError=${socketService.state.value.lastError})",
                socketService.state.value.connected
            )

            // Wait briefly for initial bridge:ready / muse:status push
            var statusAttempts = 0
            while (statusAttempts < 40 && !socketService.state.value.museConnected) {
                delay(50)
                statusAttempts++
            }
            assertTrue(socketService.state.value.museConnected)

            val prevChecked = socketService.state.value.lastCheckedAtMillis
            assertTrue(socketService.refreshMuseConnection())
            var refreshAttempts = 0
            while (refreshAttempts < 40 && socketService.state.value.lastCheckedAtMillis == prevChecked) {
                delay(50)
                refreshAttempts++
            }
            assertTrue(socketService.state.value.lastCheckedAtMillis >= prevChecked)
        } finally {
            socketService.disconnect()
            server.stop()
        }
    }

    @Test
    fun museConnectionStatusCard_displaysConnectedAndDisconnectedStatesWithRefreshAction() {
        var refreshCount = 0
        var isConnected by androidx.compose.runtime.mutableStateOf(false)

        composeRule.setContent {
            MuseBoardroomTheme {
                MuseConnectionStatusCard(
                    status = MuseConnectionStatus(
                        isConnected = isConnected,
                        socketConnected = isConnected,
                        statusDetail = if (isConnected) "Active Muse AI Socket.IO session" else "Not signed in to Muse AI"
                    ),
                    storedTokenCount = if (isConnected) 1 else 0,
                    onRefreshConnection = { refreshCount++ }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("muse_connection_status_card").assertIsDisplayed()
        composeRule.onNodeWithTag("muse_connection_status_label").assertIsDisplayed()
        composeRule.onNodeWithText("Disconnected").assertIsDisplayed()
        composeRule.onNodeWithTag("muse_connection_refresh_button").performClick()
        assertEquals(1, refreshCount)

        isConnected = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Connected").assertIsDisplayed()
        composeRule.onNodeWithText("Socket.IO Live").assertIsDisplayed()
        composeRule.onNodeWithText("1 Encrypted Token").assertIsDisplayed()
    }
}
