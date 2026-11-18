package com.example

import android.content.Context
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.BoardroomDatabase
import com.example.data.model.AgentAvailabilityStatus
import com.example.data.model.AgentConfig
import com.example.data.model.AgentProvider
import com.example.data.model.BoardroomSettings
import com.example.data.model.ChatViewMode
import com.example.data.model.InAppBrowserStatus
import com.example.data.model.resolveAgentAvailability
import com.example.data.repository.BoardroomRepository
import com.example.ui.BoardroomApp
import com.example.ui.BoardroomViewModel
import com.example.ui.components.AgentSidebarContent
import com.example.ui.components.BoardroomLiveRosterStrip
import com.example.ui.theme.MuseBoardroomTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
    fun resolveAgentAvailability_handlesOnlineOfflineAndBusyStates() {
        val gemini = AgentConfig(id = "gemini", name = "Gemini", provider = AgentProvider.GEMINI)
        val openAiNoKey = AgentConfig(id = "openai", name = "ChatGPT", provider = AgentProvider.OPENAI, apiKey = "")
        val openAiWithKey = openAiNoKey.copy(apiKey = "sk-test")
        val browserAgent = AgentConfig(id = "muse", name = "Muse AI", provider = AgentProvider.INAPP_BROWSER)
        val simAgent = AgentConfig(id = "demo", name = "Demo", provider = AgentProvider.SIMULATION)

        // Busy overrides everything
        assertEquals(
            AgentAvailabilityStatus.BUSY,
            resolveAgentAvailability(gemini, browserStatus = null, isBusy = true, isNetworkAvailable = true)
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
        // OpenAI depends on API key presence
        assertEquals(
            AgentAvailabilityStatus.OFFLINE,
            resolveAgentAvailability(openAiNoKey, browserStatus = null, isBusy = false, isNetworkAvailable = true)
        )
        assertEquals(
            AgentAvailabilityStatus.ONLINE,
            resolveAgentAvailability(openAiWithKey, browserStatus = null, isBusy = false, isNetworkAvailable = true)
        )
        // Browser agent depends on ready/saved session
        assertEquals(
            AgentAvailabilityStatus.OFFLINE,
            resolveAgentAvailability(browserAgent, browserStatus = InAppBrowserStatus("muse", needsLogin = true), isBusy = false)
        )
        assertEquals(
            AgentAvailabilityStatus.ONLINE,
            resolveAgentAvailability(browserAgent, browserStatus = InAppBrowserStatus("muse", ready = true), isBusy = false)
        )
        // Simulation is Offline
        assertEquals(
            AgentAvailabilityStatus.OFFLINE,
            resolveAgentAvailability(simAgent, browserStatus = null, isBusy = false, isNetworkAvailable = true)
        )
    }

    @Test
    fun roomRepository_seedsDefaultAgentsAndPersistsUpdates() = runBlocking {
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

            val offlineDemo = AgentConfig(
                id = "demo-1",
                name = "Demo Agent",
                role = "Tester",
                provider = AgentProvider.SIMULATION,
                isSupervisor = true
            )
            repo.upsertAgent(offlineDemo)
            repo.saveSettings(BoardroomSettings(maxCollaborationHops = 4, supervisorAgentId = "demo-1"))

            val updatedAgents = repo.agentsFlow.first()
            assertEquals(5, updatedAgents.size)
            val updatedSettings = repo.settingsFlow.first()
            assertEquals(4, updatedSettings.maxCollaborationHops)
            assertEquals("demo-1", updatedSettings.supervisorAgentId)
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
}
