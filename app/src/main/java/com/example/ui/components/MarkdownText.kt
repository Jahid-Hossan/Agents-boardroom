package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.BgCanvas
import com.example.ui.theme.BgElevated
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.HandoffBlueText
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class CodeFence(val language: String, val code: String) : MarkdownBlock
    data class BulletLine(val marker: String, val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }

    SelectionContainer(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Heading -> {
                        val size = when (block.level) {
                            1 -> 18.sp
                            2 -> 16.sp
                            else -> 15.sp
                        }
                        Text(
                            text = parseInlineMarkdown(block.text),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontSize = size,
                                fontWeight = FontWeight.Bold
                            ),
                            color = TextPrimary
                        )
                    }

                    is MarkdownBlock.CodeFence -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(BgCanvas)
                                .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                        ) {
                            if (block.language.isNotBlank()) {
                                Text(
                                    text = block.language.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(BgElevated)
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = block.code,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    color = TextPrimary
                                )
                            }
                        }
                    }

                    is MarkdownBlock.BulletLine -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = block.marker,
                                style = MaterialTheme.typography.bodyLarge,
                                color = AmberAccent,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = parseInlineMarkdown(block.text),
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    is MarkdownBlock.Quote -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(BgElevated.copy(alpha = 0.65f))
                                .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = parseInlineMarkdown(block.text),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }

                    is MarkdownBlock.Paragraph -> {
                        Text(
                            text = parseInlineMarkdown(block.text),
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary
                        )
                    }
                }
            }
        }
    }
}

private fun parseMarkdownBlocks(raw: String): List<MarkdownBlock> {
    val lines = raw.replace("\r\n", "\n").lines()
    val result = mutableListOf<MarkdownBlock>()
    val paragraphBuffer = mutableListOf<String>()
    var inCode = false
    var codeLang = ""
    val codeBuffer = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraphBuffer.isNotEmpty()) {
            result.add(MarkdownBlock.Paragraph(paragraphBuffer.joinToString("\n")))
            paragraphBuffer.clear()
        }
    }

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            if (!inCode) {
                flushParagraph()
                inCode = true
                codeLang = trimmed.removePrefix("```").trim()
                codeBuffer.clear()
            } else {
                result.add(MarkdownBlock.CodeFence(codeLang, codeBuffer.joinToString("\n")))
                inCode = false
                codeLang = ""
                codeBuffer.clear()
            }
            continue
        }

        if (inCode) {
            codeBuffer.add(line)
            continue
        }

        if (trimmed.isEmpty()) {
            flushParagraph()
            continue
        }

        val headingMatch = Regex("^(#{1,3})\\s+(.+)$").find(trimmed)
        if (headingMatch != null) {
            flushParagraph()
            result.add(
                MarkdownBlock.Heading(
                    level = headingMatch.groupValues[1].length,
                    text = headingMatch.groupValues[2]
                )
            )
            continue
        }

        val bulletMatch = Regex("^([-*•]|\\d+\\.)\\s+(.+)$").find(trimmed)
        if (bulletMatch != null) {
            flushParagraph()
            val marker = if (bulletMatch.groupValues[1] in listOf("-", "*", "•")) "•" else bulletMatch.groupValues[1]
            result.add(MarkdownBlock.BulletLine(marker, bulletMatch.groupValues[2]))
            continue
        }

        if (trimmed.startsWith(">")) {
            flushParagraph()
            result.add(MarkdownBlock.Quote(trimmed.removePrefix(">").trim()))
            continue
        }

        paragraphBuffer.add(line)
    }

    if (inCode && codeBuffer.isNotEmpty()) {
        result.add(MarkdownBlock.CodeFence(codeLang, codeBuffer.joinToString("\n")))
    }
    flushParagraph()
    return result
}

private fun parseInlineMarkdown(text: String): AnnotatedString {
    val pattern = Regex("(`[^`]+`|\\*\\*[^*]+\\*\\*|\\*[^*]+\\*|\\[[^\\]]+\\]\\([^)]+\\)|@[A-Za-z0-9_.-]+)")
    return buildAnnotatedString {
        var lastIdx = 0
        for (match in pattern.findAll(text)) {
            if (match.range.first > lastIdx) {
                append(text.substring(lastIdx, match.range.first))
            }
            val token = match.value
            when {
                token.startsWith("`") && token.endsWith("`") && token.length >= 2 -> {
                    withStyle(
                        SpanStyle(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 12.sp,
                            background = BgCanvas,
                            color = AmberAccent
                        )
                    ) {
                        append(" ${token.substring(1, token.length - 1)} ")
                    }
                }
                token.startsWith("**") && token.endsWith("**") && token.length >= 4 -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TextPrimary)) {
                        append(token.substring(2, token.length - 2))
                    }
                }
                token.startsWith("*") && token.endsWith("*") && token.length >= 2 -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(token.substring(1, token.length - 1))
                    }
                }
                token.startsWith("[") && token.contains("](") -> {
                    val label = token.substringAfter("[").substringBefore("](")
                    withStyle(
                        SpanStyle(
                            color = AmberAccent,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Medium
                        )
                    ) {
                        append(label)
                    }
                }
                token.startsWith("@") -> {
                    withStyle(SpanStyle(color = HandoffBlueText, fontWeight = FontWeight.SemiBold)) {
                        append(token)
                    }
                }
                else -> append(token)
            }
            lastIdx = match.range.last + 1
        }
        if (lastIdx < text.length) {
            append(text.substring(lastIdx))
        }
    }
}
