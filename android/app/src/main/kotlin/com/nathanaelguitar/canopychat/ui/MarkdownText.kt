package com.nathanaelguitar.canopychat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.nathanaelguitar.canopychat.core.MarkdownBlock
import com.nathanaelguitar.canopychat.core.MarkdownBlockParser
import com.nathanaelguitar.canopychat.core.MarkdownSource
import com.nathanaelguitar.canopychat.core.MarkdownSourceParser
import kotlinx.coroutines.delay

/**
 * Port of MarkdownMessageText in iphone/AetherChat/ChatView.swift. Splits trailing
 * "Sources" into chips, then renders headings, bullets, numbered items, code and
 * tables as discrete blocks.
 */
@Composable
fun MarkdownMessageText(
    content: String,
    isDark: Boolean,
    fontScale: Double,
    color: Color,
    modifier: Modifier = Modifier
) {
    val split = remember(content) { MarkdownSourceParser.splitTrailingSources(content) }
    val blocks = remember(split.first) { MarkdownBlockParser.parse(split.first) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        blocks.forEach { block -> MarkdownBlockView(block, isDark, fontScale, color) }
        if (split.second.isNotEmpty()) {
            SourceChips(split.second, isDark, fontScale, Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock, isDark: Boolean, fontScale: Double, color: Color) {
    when (block) {
        is MarkdownBlock.Heading -> Text(
            text = inlineMarkdown(block.text),
            color = color,
            fontSize = (headingSize(block.level) * fontScale).sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = if (block.level == 1) 2.dp else 4.dp)
        )

        is MarkdownBlock.Paragraph -> Text(
            text = inlineMarkdown(block.text),
            color = color,
            fontSize = (15 * fontScale).sp,
            lineHeight = (21 * fontScale).sp
        )

        is MarkdownBlock.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("•", color = color, fontSize = (14 * fontScale).sp, fontWeight = FontWeight.Bold)
            Text(
                text = inlineMarkdown(block.text),
                color = color,
                fontSize = (15 * fontScale).sp,
                lineHeight = (20 * fontScale).sp
            )
        }

        is MarkdownBlock.Numbered -> Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                "${block.number}.",
                color = color,
                fontSize = (14 * fontScale).sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = inlineMarkdown(block.text),
                color = color,
                fontSize = (15 * fontScale).sp,
                lineHeight = (20 * fontScale).sp
            )
        }

        is MarkdownBlock.Code -> CodeBlockView(block.text, isDark, fontScale)

        // iOS renders tables through the same monospaced block as code.
        is MarkdownBlock.Table -> CodeBlockView(block.rows.joinToString("\n"), isDark, fontScale)
    }
}

private fun headingSize(level: Int): Double = when (level) {
    1 -> 19.0
    2 -> 17.0
    else -> 16.0
}

/**
 * Compose has no AttributedString(markdown:) equivalent, so inline **bold**, *italic*,
 * `code` and [text](url) are parsed by hand to match the iOS inline rendering.
 */
internal fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        val remaining = text.substring(index)

        val link = Regex("""^\[([^\]]+)]\((https?://[^)\s]+)\)""").find(remaining)
        if (link != null) {
            // LinkAnnotation keeps the href live, matching AttributedString(markdown:) on iOS.
            withLink(
                LinkAnnotation.Url(
                    link.groupValues[2],
                    TextLinkStyles(SpanStyle(color = OakColors.info, textDecoration = TextDecoration.Underline))
                )
            ) {
                append(link.groupValues[1])
            }
            index += link.value.length
            continue
        }

        val bold = Regex("""^\*\*(.+?)\*\*""", RegexOption.DOT_MATCHES_ALL).find(remaining)
        if (bold != null) {
            withStyleSpan(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold.groupValues[1]) }
            index += bold.value.length
            continue
        }

        val code = Regex("""^`([^`]+)`""").find(remaining)
        if (code != null) {
            withStyleSpan(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code.groupValues[1]) }
            index += code.value.length
            continue
        }

        // Guarded on "**" so an unmatched bold marker isn't consumed as italics.
        val italic = if (remaining.startsWith("**")) null else Regex("""^\*([^*\n]+)\*""").find(remaining)
        if (italic != null) {
            withStyleSpan(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic.groupValues[1]) }
            index += italic.value.length
            continue
        }

        append(text[index])
        index += 1
    }
}

private inline fun androidx.compose.ui.text.AnnotatedString.Builder.withStyleSpan(
    style: SpanStyle,
    block: androidx.compose.ui.text.AnnotatedString.Builder.() -> Unit
) {
    val start = length
    block()
    addStyle(style, start, length)
}

/**
 * Citations for a reply. iOS lays these out as a horizontal strip of pills; that reads as
 * clutter on a phone-width column, so Android stacks them as quiet, full-width rows under
 * a small caption — the source host leads, since that is what tells you whether to trust it.
 */
@Composable
fun SourceChips(
    sources: List<MarkdownSource>,
    isDark: Boolean,
    fontScale: Double,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val captionColor = if (isDark) OakColors.warmGray400 else OakColors.warmGray500
    val hostColor = if (isDark) OakColors.warmGray100 else OakColors.oakDark
    val titleColor = if (isDark) OakColors.warmGray400 else OakColors.warmGray600

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "SOURCES",
            fontSize = (10 * fontScale).sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = captionColor
        )

        sources.take(4).forEachIndexed { index, source ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { openUrl(context, source.url) }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    "${index + 1}",
                    fontSize = (10 * fontScale).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = captionColor,
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(OakColors.oakMedium.copy(alpha = 0.12f))
                        .wrapContentHeight(Alignment.CenterVertically),
                    textAlign = TextAlign.Center
                )
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        source.host,
                        fontSize = (12 * fontScale).sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = hostColor
                    )
                    Text(
                        source.compactTitle,
                        fontSize = (11 * fontScale).sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = titleColor
                    )
                }
            }
        }
    }
}

/** Port of CodeBlockView in iphone/AetherChat/ChatView.swift, including copy-to-clipboard. */
@Composable
fun CodeBlockView(code: String, isDark: Boolean, fontScale: Double) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1_200)
            copied = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isDark) OakColors.warmGray900.copy(alpha = 0.92f) else OakColors.warmGray100.copy(alpha = 0.95f)
            )
            .then(
                if (isDark) {
                    Modifier.border(1.dp, OakColors.oakMedium.copy(alpha = 0.24f), RoundedCornerShape(12.dp))
                } else {
                    Modifier
                }
            )
    ) {
        Text(
            text = code,
            fontFamily = FontFamily.Monospace,
            fontSize = (13 * fontScale).sp,
            lineHeight = (18 * fontScale).sp,
            color = if (isDark) OakColors.warmGray100 else OakColors.warmBlack,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 34.dp, bottom = 12.dp)
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(
                    if (isDark) OakColors.warmGray800.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.9f)
                )
                .clickable {
                    copyToClipboard(context, code)
                    copied = true
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = if (copied) "Copied" else "Copy code",
                tint = if (isDark) OakColors.oakPale else OakColors.oakMedium,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

internal fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("CanopyChat", text))
}

internal fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
