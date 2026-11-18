package com.example.engine

import com.example.data.model.AgentConfig
import com.example.data.model.BoardroomMessage
import kotlin.math.roundToInt

data class HandoffTarget(
    val agent: AgentConfig,
    val subPrompt: String
)

data class IncomingHandoffContext(
    val fromName: String,
    val subPrompt: String,
    val sourceText: String
)

object AgentCollaboration {
    const val DEFAULT_MAX_HOPS = 3

    fun clampMaxHops(value: Int?, fallback: Int = DEFAULT_MAX_HOPS): Int {
        val raw = value ?: fallback
        return raw.coerceIn(1, 5)
    }

    fun isErrorOrSetupReply(text: String?): Boolean {
        val s = text.orEmpty().trim()
        if (s.isEmpty()) return true
        return Regex("needs login in the In-App Browser", RegexOption.IGNORE_CASE).containsMatchIn(s) ||
            Regex("not signed in yet", RegexOption.IGNORE_CASE).containsMatchIn(s) ||
            Regex("Desktop Browser Bridge is not connected", RegexOption.IGNORE_CASE).containsMatchIn(s) ||
            Regex("^\\*\\*[^*]+ error:\\*\\*", RegexOption.IGNORE_CASE).containsMatchIn(s) ||
            Regex("is not configured yet\\.\\*\\*", RegexOption.IGNORE_CASE).containsMatchIn(s)
    }

    private fun buildNamePattern(name: String): String? {
        val tokens = name.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .map { Regex.escape(it) }
        if (tokens.isEmpty()) return null
        return tokens.joinToString("[\\s_-]+")
    }

    private data class MentionHit(
        val agent: AgentConfig,
        val mentionStart: Int,
        val mentionEnd: Int
    )

    fun parseAgentHandoffs(
        replyText: String,
        allAgents: List<AgentConfig>,
        senderAgentId: String
    ): List<HandoffTarget> {
        if (replyText.isBlank() || isErrorOrSetupReply(replyText)) {
            return emptyList()
        }

        // Strip fenced code blocks and inline code so technical examples don't trigger accidental hops
        val cleaned = replyText
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("`[^`\\n]+`"), " ")

        val candidates = allAgents
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .sortedByDescending { it.name.length }

        if (candidates.isEmpty()) return emptyList()

        val hits = mutableListOf<MentionHit>()
        val occupiedRanges = mutableListOf<IntRange>()

        for (agent in candidates) {
            val namePat = buildNamePattern(agent.name) ?: continue
            val regex = Regex("(^|[^\\w.])@($namePat)(?=$|[^\\w-])", RegexOption.IGNORE_CASE)
            for (match in regex.findAll(cleaned)) {
                val prefixLen = match.groups[1]?.value?.length ?: 0
                val mentionStart = match.range.first + prefixLen
                val mentionEnd = match.range.last + 1
                val overlaps = occupiedRanges.any { range ->
                    mentionStart in range || (mentionEnd - 1) in range
                }
                if (!overlaps) {
                    occupiedRanges.add(mentionStart until mentionEnd)
                    hits.add(MentionHit(agent, mentionStart, mentionEnd))
                }
            }
        }

        if (hits.isEmpty()) return emptyList()

        hits.sortBy { it.mentionStart }

        val seenAgentIds = mutableSetOf<String>()
        val targets = mutableListOf<HandoffTarget>()

        for (i in hits.indices) {
            val hit = hits[i]
            if (hit.agent.id == senderAgentId) continue
            if (!seenAgentIds.add(hit.agent.id)) continue

            val nextStart = if (i + 1 < hits.size) hits[i + 1].mentionStart else cleaned.length
            var slice = cleaned.substring(hit.mentionEnd, nextStart)
            val paraMatch = Regex("\\n\\s*\\n").find(slice)
            if (paraMatch != null) {
                slice = slice.substring(0, paraMatch.range.first)
            }
            val subPrompt = slice.replace(Regex("^[\\s:,\\-–—>]+"), "").trim()

            targets.add(
                HandoffTarget(
                    agent = hit.agent,
                    subPrompt = subPrompt.ifEmpty { cleaned.trim() }
                )
            )
        }

        return targets
    }

    fun buildCollaborationPrompt(
        userCommand: String,
        agent: AgentConfig,
        allAgents: List<AgentConfig>,
        incomingContext: IncomingHandoffContext? = null,
        collectedReplies: List<BoardroomMessage> = emptyList(),
        isSupervisorInitial: Boolean = false,
        isSupervisorSynthesis: Boolean = false,
        hop: Int = 1,
        maxHops: Int = DEFAULT_MAX_HOPS
    ): String {
        val supervisor = allAgents.firstOrNull { it.id != agent.id && it.isSupervisor }
        val teammates = allAgents
            .filter { it.id != agent.id }
            .map { a ->
                val rolePart = if (a.role.isNotBlank()) " (${a.role})" else ""
                val supPart = if (a.id == supervisor?.id) " [CEO/Supervisor]" else ""
                "@${a.name}$rolePart$supPart"
            }

        val teamLine = if (teammates.isNotEmpty()) {
            "Connected teammates: ${teammates.joinToString(", ")}."
        } else {
            "No other teammates connected."
        }

        if (isSupervisorSynthesis) {
            val transcript = collectedReplies.joinToString("\n\n---\n\n") { r ->
                "[${r.senderName}]:\n${r.text.trim()}"
            }
            return listOf(
                "[CEO / SUPERVISOR FINAL SYNTHESIS]",
                "Original user request: $userCommand",
                "",
                "Team responses collected:",
                transcript,
                "",
                "Instructions: Act as the CEO / Supervisor. Synthesize the team's work into one clear, authoritative final answer or decision for the user. Do NOT @mention any teammate in this final synthesis."
            ).joinToString("\n")
        }

        if (isSupervisorInitial) {
            return listOf(
                "[CEO / SUPERVISOR MODE · Round 1/$maxHops]",
                teamLine,
                "Instructions: You are the CEO / Supervisor. Review the user's request below.",
                "- If you need specialist input or work from teammates, delegate specific tasks on separate lines using `@AgentName: <clear task>` (mention ONLY the teammates you actually want to invoke).",
                "- If no teammate input is needed, answer the user directly without @mentioning anyone.",
                "",
                "User request: $userCommand"
            ).joinToString("\n")
        }

        if (incomingContext != null) {
            val roundLabel = "Round $hop/$maxHops"
            val handoffInstruction = if (hop < maxHops && teammates.isNotEmpty()) {
                "If—and only if—you need another teammate's input before finishing, write `@AgentName: <specific request>` ($hop/$maxHops max rounds). Otherwise answer directly without @mentioning anyone."
            } else {
                "This is the final interaction round ($roundLabel). Provide a direct, complete answer and do NOT @mention any teammate."
            }

            val lines = mutableListOf(
                "[TEAM HANDOFF · $roundLabel]",
                teamLine,
                "Original user request: $userCommand",
                "Request from @${incomingContext.fromName}: ${incomingContext.subPrompt}"
            )
            if (incomingContext.sourceText.isNotBlank() && incomingContext.sourceText != incomingContext.subPrompt) {
                lines.add("Context from @${incomingContext.fromName}:\n${incomingContext.sourceText.take(1500)}")
            }
            lines.add("")
            lines.add("Instructions: $handoffInstruction")
            return lines.joinToString("\n")
        }

        if (teammates.isEmpty()) return userCommand

        return listOf(
            "[BOARDROOM COLLABORATION · Round 1/$maxHops]",
            teamLine,
            "Instructions: Answer the user's request. If—and only if—you genuinely need another teammate's input or review, include `@AgentName: <specific request>`. Do NOT @mention any agent unless you want them to reply.",
            "",
            "User request: $userCommand"
        ).joinToString("\n")
    }
}
