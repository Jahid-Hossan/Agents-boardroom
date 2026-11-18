package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Core Obsidian & Amber Command Center Palette (from public/styles.css)
val BgCanvas = Color(0xFF0B0E14)
val BgSurface = Color(0xFF121722)
val BgElevated = Color(0xFF182030)
val BgHover = Color(0xFF1F293D)

val BorderSubtle = Color(0xFF232D42)
val BorderStrong = Color(0xFF33415C)

val TextPrimary = Color(0xFFF1F5F9)
val TextSecondary = Color(0xFF94A3B8)
val TextMuted = Color(0xFF64748B)

val AmberAccent = Color(0xFFF59E0B)
val AmberHover = Color(0xFFFBBF24)
val AmberOnAccent = Color(0xFF090D16)

val StatusOk = Color(0xFF10B981)
val StatusWarn = Color(0xFFF59E0B)
val StatusWarnSoft = Color(0x24F59E0B)
val StatusWarnBorder = Color(0x73F59E0B)
val StatusWarnText = Color(0xFFFCD34D)
val StatusOffline = Color(0xFF64748B)
val StatusOfflineSoft = Color(0x2264748B)
val StatusOfflineBorder = Color(0x5564748B)
val StatusErr = Color(0xFFEF4444)
val StatusErrSoft = Color(0x20EF4444)
val StatusErrBorder = Color(0x66EF4444)
val StatusErrText = Color(0xFFFCA5A5)

val HandoffBlue = Color(0xFF3B82F6)
val HandoffBlueBg = Color(0x243B82F6)
val HandoffBlueBorder = Color(0x613B82F6)
val HandoffBlueText = Color(0xFF93C5FD)

val SupervisorAmberBg = Color(0x24F59E0B)
val SupervisorAmberBorder = Color(0x73F59E0B)

val AgentMuseBlue = Color(0xFF3B82F6)
val AgentGeminiPurple = Color(0xFF8B5CF6)
val AgentChatGptEmerald = Color(0xFF10B981)
val AgentGrokSlate = Color(0xFFE2E8F0)
val AgentClaudeAmber = Color(0xFFF59E0B)

fun parseHexColor(hex: String, fallback: Color = AgentMuseBlue): Color {
    val clean = hex.trim().removePrefix("#")
    return try {
        when (clean.length) {
            6 -> Color(("FF$clean").toLong(16))
            8 -> Color(clean.toLong(16))
            else -> fallback
        }
    } catch (_: Exception) {
        fallback
    }
}
