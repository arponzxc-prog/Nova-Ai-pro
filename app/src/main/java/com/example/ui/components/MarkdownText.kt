package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.delay

internal sealed interface MarkdownBlock {
    data class TextParagraph(val text: String) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock
    data class Blockquote(val text: String) : MarkdownBlock
    object HorizontalRule : MarkdownBlock
}

/**
 * AI-এর উত্তরের জন্য প্রিমিয়াম, স্মুথ ও প্রফেশনাল Markdown রেন্ডারার।
 * কোড ব্লক (```), কোট ব্লক (>), হেডিং (#, ##, ###), বোল্ড (**), ইটালিক (*),
 * ইনলাইন কোড (`), লিস্ট এবং স্ট্রিমিং ব্লিংকিং কার্সর সাপোর্ট করে।
 */
@Composable
fun MarkdownText(
    markdown: String,
    textColor: Color,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false
) {
    val blocks = remember(markdown, isStreaming) { parseMarkdownBlocks(markdown, isStreaming) }
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is MarkdownBlock.CodeBlock -> {
                    CodeBlockCard(language = block.language, code = block.code)
                }
                is MarkdownBlock.Blockquote -> {
                    BlockquoteCard(text = block.text, textColor = textColor)
                }
                is MarkdownBlock.HorizontalRule -> {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                is MarkdownBlock.TextParagraph -> {
                    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f)
                    val isLastBlock = index == blocks.lastIndex
                    val annotated = remember(block.text, textColor, primaryColor, codeBg) {
                        parseInlineMarkdown(block.text, textColor, primaryColor, codeBg)
                    }

                    if (isStreaming && isLastBlock) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = annotated,
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor,
                                lineHeight = 24.sp,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            StreamingCursor()
                        }
                    } else {
                        Text(
                            text = annotated,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                            lineHeight = 24.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingCursor() {
    val infiniteTransition = rememberInfiniteTransition(label = "cursorBlink")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )

    Box(
        modifier = Modifier
            .padding(bottom = 3.dp)
            .width(3.dp)
            .height(18.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = cursorAlpha),
                        MaterialTheme.colorScheme.tertiary.copy(alpha = cursorAlpha)
                    )
                )
            )
    )
}

@Composable
private fun BlockquoteCard(text: String, textColor: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = textColor.copy(alpha = 0.85f),
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
private fun CodeBlockCard(language: String, code: String) {
    val context = LocalContext.current
    var isCopied by remember { mutableStateOf(false) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(2000L)
            isCopied = false
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = (if (language.isNotBlank()) language else "code").uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontFamily = JetBrainsMonoFontFamily
                    )
                }

                FilledTonalButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Code", code))
                        isCopied = true
                        Toast.makeText(context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(28.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isCopied) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                ) {
                    Crossfade(targetState = isCopied, label = "copyIcon") { copied ->
                        if (copied) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.copied_feedback),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = stringResource(R.string.copy_code),
                                    modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.copy_code),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(14.dp)
            ) {
                Text(
                    text = code,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

internal fun parseMarkdownBlocks(input: String, isStreaming: Boolean = false): List<MarkdownBlock> {
    val result = mutableListOf<MarkdownBlock>()
    val codeFenceRegex = Regex("```([a-zA-Z0-9_+-]*)\\n?([\\s\\S]*?)```")
    var lastIndex = 0

    for (match in codeFenceRegex.findAll(input)) {
        if (match.range.first > lastIndex) {
            val before = input.substring(lastIndex, match.range.first)
            parseSubBlocks(before, result)
        }
        val lang = match.groupValues[1].trim()
        val code = match.groupValues[2].trimEnd()
        result.add(MarkdownBlock.CodeBlock(lang, code))
        lastIndex = match.range.last + 1
    }

    if (lastIndex < input.length) {
        val remainingRaw = input.substring(lastIndex)
        if (isStreaming) {
            val openFenceIndex = remainingRaw.indexOf("```")
            if (openFenceIndex >= 0) {
                val beforeFence = remainingRaw.substring(0, openFenceIndex)
                parseSubBlocks(beforeFence, result)
                val afterTicks = remainingRaw.substring(openFenceIndex + 3)
                val firstNewline = afterTicks.indexOf('\n')
                val lang = if (firstNewline >= 0) afterTicks.substring(0, firstNewline).trim() else ""
                val code = if (firstNewline >= 0) afterTicks.substring(firstNewline + 1).trimEnd() else ""
                result.add(MarkdownBlock.CodeBlock(lang, code))
                return result
            }
        }
        parseSubBlocks(remainingRaw, result)
    }

    if (result.isEmpty() && input.isNotBlank()) {
        result.add(MarkdownBlock.TextParagraph(input))
    }
    return result
}

private fun parseSubBlocks(rawText: String, output: MutableList<MarkdownBlock>) {
    val lines = rawText.split("\n")
    val currentParagraph = StringBuilder()

    fun flushParagraph() {
        val trimmed = currentParagraph.toString().trim()
        if (trimmed.isNotEmpty()) {
            output.add(MarkdownBlock.TextParagraph(trimmed))
        }
        currentParagraph.clear()
    }

    for (line in lines) {
        val trimmedLine = line.trim()
        if (trimmedLine == "---" || trimmedLine == "***") {
            flushParagraph()
            output.add(MarkdownBlock.HorizontalRule)
        } else if (trimmedLine.startsWith("> ")) {
            flushParagraph()
            output.add(MarkdownBlock.Blockquote(trimmedLine.removePrefix("> ").trim()))
        } else {
            if (currentParagraph.isNotEmpty()) currentParagraph.append("\n")
            currentParagraph.append(line)
        }
    }
    flushParagraph()
}

private fun parseInlineMarkdown(
    text: String,
    baseColor: Color,
    primaryColor: Color,
    inlineCodeBg: Color
): AnnotatedString {
    return buildAnnotatedString {
        val lines = text.split("\n")
        lines.forEachIndexed { lineIndex, rawLine ->
            var line = rawLine
            var isH1 = false
            var isH2 = false
            var isH3 = false
            var isBullet = false
            var isNumbered = false

            when {
                line.startsWith("# ") -> {
                    line = line.removePrefix("# ")
                    isH1 = true
                }
                line.startsWith("## ") -> {
                    line = line.removePrefix("## ")
                    isH2 = true
                }
                line.startsWith("### ") -> {
                    line = line.removePrefix("### ")
                    isH3 = true
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    val indent = line.takeWhile { it == ' ' }
                    line = "$indent• " + line.trimStart().drop(2)
                    isBullet = true
                }
                line.trimStart().matches(Regex("^\\d+\\.\\s.*")) -> {
                    isNumbered = true
                }
            }

            when {
                isH1 -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, color = primaryColor)) {
                        appendInlineTokens(line, inlineCodeBg)
                    }
                }
                isH2 -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.5.sp, color = primaryColor)) {
                        appendInlineTokens(line, inlineCodeBg)
                    }
                }
                isH3 -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = baseColor)) {
                        appendInlineTokens(line, inlineCodeBg)
                    }
                }
                isBullet -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = baseColor)) {
                        appendInlineTokens(line, inlineCodeBg)
                    }
                }
                isNumbered -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = baseColor)) {
                        appendInlineTokens(line, inlineCodeBg)
                    }
                }
                else -> {
                    appendInlineTokens(line, inlineCodeBg)
                }
            }

            if (lineIndex < lines.lastIndex) {
                append("\n")
            }
        }
    }
}

private fun AnnotatedString.Builder.appendInlineTokens(line: String, inlineCodeBg: Color) {
    // **bold**, *italic*, `code` টোকেন পার্স করা
    val tokenRegex = Regex("(\\*\\*[^*]+\\*\\*|\\*[^*]+\\*|`[^`]+`)")
    var idx = 0
    for (match in tokenRegex.findAll(line)) {
        if (match.range.first > idx) {
            append(line.substring(idx, match.range.first))
        }
        val token = match.value
        when {
            token.startsWith("**") && token.endsWith("**") && token.length > 4 -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(token.substring(2, token.length - 2))
                }
            }
            token.startsWith("*") && token.endsWith("*") && token.length > 2 -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(token.substring(1, token.length - 1))
                }
            }
            token.startsWith("`") && token.endsWith("`") && token.length > 2 -> {
                withStyle(
                    SpanStyle(
                        fontFamily = JetBrainsMonoFontFamily,
                        background = inlineCodeBg,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                ) {
                    append(" ${token.substring(1, token.length - 1)} ")
                }
            }
            else -> append(token)
        }
        idx = match.range.last + 1
    }
    if (idx < line.length) {
        append(line.substring(idx))
    }
}
