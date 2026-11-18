package com.example

import com.example.data.model.AgentConfig
import com.example.data.model.AgentProvider
import com.example.data.model.BoardroomMessage
import com.example.engine.AgentCollaboration
import com.example.engine.IncomingHandoffContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCollaborationTest {

    private val sampleAgents = listOf(
        AgentConfig(
            id = "muse",
            name = "Muse AI",
            role = "Creative Lead",
            provider = AgentProvider.INAPP_BROWSER,
            isSupervisor = true
        ),
        AgentConfig(
            id = "gemini",
            name = "Gemini",
            role = "Research Analyst",
            provider = AgentProvider.GEMINI
        ),
        AgentConfig(
            id = "chatgpt",
            name = "ChatGPT",
            role = "Code Architect",
            provider = AgentProvider.OPENAI
        ),
        AgentConfig(
            id = "grok",
            name = "Grok",
            role = "Trend Reviewer",
            provider = AgentProvider.SIMULATION
        )
    )

    @Test
    fun parseAgentHandoffs_extractsMultipleOrderedTargetsAndIgnoresCodeBlocks() {
        val reply = """
            Let's split this work:
            ```kotlin
            // Ignore @Grok inside code fence
            val example = "@ChatGPT test"
            ```
            @Gemini: Research market competitors and pricing tiers.

            @ChatGPT: Draft a clean Kotlin architecture proposal.
        """.trimIndent()

        val handoffs = AgentCollaboration.parseAgentHandoffs(
            replyText = reply,
            allAgents = sampleAgents,
            senderAgentId = "muse"
        )

        assertEquals(2, handoffs.size)
        assertEquals("gemini", handoffs[0].agent.id)
        assertTrue(handoffs[0].subPrompt.contains("Research market competitors"))
        assertEquals("chatgpt", handoffs[1].agent.id)
        assertTrue(handoffs[1].subPrompt.contains("Draft a clean Kotlin architecture"))
    }

    @Test
    fun parseAgentHandoffs_ignoresSelfMentionAndErrorReplies() {
        val selfMention = "@Muse AI: I should verify this myself. @Gemini: Help check numbers."
        val handoffs = AgentCollaboration.parseAgentHandoffs(
            replyText = selfMention,
            allAgents = sampleAgents,
            senderAgentId = "muse"
        )
        assertEquals(1, handoffs.size)
        assertEquals("gemini", handoffs[0].agent.id)

        val errReply = "**Gemini error:** Request timed out. CC @ChatGPT"
        assertTrue(AgentCollaboration.isErrorOrSetupReply(errReply))
        assertTrue(
            AgentCollaboration.parseAgentHandoffs(errReply, sampleAgents, "gemini").isEmpty()
        )
    }

    @Test
    fun clampMaxHops_enforcesOneToFiveBounds() {
        assertEquals(3, AgentCollaboration.clampMaxHops(null))
        assertEquals(1, AgentCollaboration.clampMaxHops(0))
        assertEquals(5, AgentCollaboration.clampMaxHops(99))
        assertEquals(4, AgentCollaboration.clampMaxHops(4))
    }

    @Test
    fun buildCollaborationPrompt_formatsSupervisorAndHandoffContexts() {
        val supPrompt = AgentCollaboration.buildCollaborationPrompt(
            userCommand = "Launch Q3 campaign",
            agent = sampleAgents[0],
            allAgents = sampleAgents,
            isSupervisorInitial = true,
            hop = 1,
            maxHops = 3
        )
        assertTrue(supPrompt.contains("CEO / SUPERVISOR MODE"))
        assertTrue(supPrompt.contains("Launch Q3 campaign"))

        val handoffPrompt = AgentCollaboration.buildCollaborationPrompt(
            userCommand = "Launch Q3 campaign",
            agent = sampleAgents[1],
            allAgents = sampleAgents,
            incomingContext = IncomingHandoffContext(
                fromName = "Muse AI",
                subPrompt = "Analyze target audience",
                sourceText = "Full plan"
            ),
            hop = 2,
            maxHops = 3
        )
        assertTrue(handoffPrompt.contains("Round 2/3"))
        assertTrue(handoffPrompt.contains("Request from @Muse AI: Analyze target audience"))
        assertFalse(handoffPrompt.contains("This is the final interaction round"))

        val finalSynthPrompt = AgentCollaboration.buildCollaborationPrompt(
            userCommand = "Launch Q3 campaign",
            agent = sampleAgents[0],
            allAgents = sampleAgents,
            collectedReplies = listOf(
                BoardroomMessage(
                    id = "1",
                    sender = "gemini",
                    role = "Research",
                    senderName = "Gemini",
                    text = "Audience insight"
                )
            ),
            isSupervisorSynthesis = true
        )
        assertTrue(finalSynthPrompt.contains("CEO / SUPERVISOR FINAL SYNTHESIS"))
        assertTrue(finalSynthPrompt.contains("[Gemini]:"))
    }
}
