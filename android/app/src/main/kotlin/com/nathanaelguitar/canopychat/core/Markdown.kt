package com.nathanaelguitar.canopychat.core

import java.net.URI

/**
 * Port of MarkdownBlock / MarkdownBlockParser / MarkdownSource / MarkdownSourceParser
 * in iphone/AetherChat/ChatView.swift. Kept free of Compose types so it can be unit tested.
 */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val text: String) : MarkdownBlock
    data class Numbered(val number: Int, val text: String) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data class Table(val rows: List<String>) : MarkdownBlock
}

object MarkdownBlockParser {

    fun parse(content: String): List<MarkdownBlock> {
        val lines = content
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .split("\n")
        val blocks = mutableListOf<MarkdownBlock>()
        var index = 0

        while (index < lines.size) {
            val line = lines[index].trim()

            if (line.isEmpty()) {
                index += 1
                continue
            }

            if (line.startsWith("```")) {
                index += 1
                val codeLines = mutableListOf<String>()
                while (index < lines.size) {
                    val next = lines[index]
                    if (next.trim().startsWith("```")) {
                        index += 1
                        break
                    }
                    codeLines.add(next)
                    index += 1
                }
                blocks.add(MarkdownBlock.Code(codeLines.joinToString("\n")))
                continue
            }

            val headingMatch = heading(line)
            if (headingMatch != null) {
                blocks.add(MarkdownBlock.Heading(headingMatch.first, headingMatch.second))
                index += 1
                continue
            }

            val bulletMatch = bullet(line)
            if (bulletMatch != null) {
                blocks.add(MarkdownBlock.Bullet(bulletMatch))
                index += 1
                continue
            }

            val numberedMatch = numbered(line)
            if (numberedMatch != null) {
                blocks.add(MarkdownBlock.Numbered(numberedMatch.first, numberedMatch.second))
                index += 1
                continue
            }

            if (line.startsWith("|") && line.endsWith("|")) {
                val rows = mutableListOf<String>()
                while (index < lines.size) {
                    val tableLine = lines[index].trim()
                    if (!tableLine.startsWith("|") || !tableLine.endsWith("|")) break
                    rows.add(tableLine)
                    index += 1
                }
                blocks.add(MarkdownBlock.Table(rows))
                continue
            }

            val paragraphLines = mutableListOf(line)
            index += 1
            while (index < lines.size) {
                val next = lines[index].trim()
                if (next.isEmpty() ||
                    next.startsWith("```") ||
                    heading(next) != null ||
                    bullet(next) != null ||
                    numbered(next) != null ||
                    next.startsWith("|")
                ) {
                    break
                }
                paragraphLines.add(next)
                index += 1
            }
            blocks.add(MarkdownBlock.Paragraph(paragraphLines.joinToString(" ")))
        }

        return blocks.ifEmpty { listOf(MarkdownBlock.Paragraph(content)) }
    }

    private fun heading(line: String): Pair<Int, String>? {
        val level = line.takeWhile { it == '#' }.length
        if (level !in 1..6) return null
        if (line.getOrNull(level) != ' ') return null
        return level to line.drop(level + 1)
    }

    private fun bullet(line: String): String? {
        if (!line.startsWith("- ") && !line.startsWith("* ")) return null
        return line.drop(2)
    }

    private fun numbered(line: String): Pair<Int, String>? {
        val dot = line.indexOf('.')
        if (dot <= 0) return null
        val number = line.substring(0, dot).toIntOrNull() ?: return null
        val rest = line.substring(dot + 1).trim()
        if (rest.isEmpty()) return null
        return number to rest
    }
}

/** Port of MarkdownSource in iphone/AetherChat/ChatView.swift. */
data class MarkdownSource(val title: String, val url: String) {

    val host: String
        get() = runCatching { URI(url).host }.getOrNull()
            ?.removePrefix("www.")
            ?: url.replace("www.", "")

    /**
     * Page titles are frequently "Article Title - Publisher"; the chip only has room
     * for the distinguishing part.
     */
    val compactTitle: String
        get() {
            if (title.lowercase().endsWith(" - ${host.lowercase()}")) return host
            val parts = title.split(" - ")
            return parts.lastOrNull()?.takeIf { it.isNotEmpty() } ?: host
        }
}

object MarkdownSourceParser {

    private val linkPattern = Regex("""\[([^\]]+)]\((https?://[^)\s]+)\)""")

    /**
     * Splits a trailing "Sources" section off the reply body so it can render as chips
     * instead of raw markdown links.
     */
    fun splitTrailingSources(content: String): Pair<String, List<MarkdownSource>> {
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split("\n")

        val sourceIndex = lines.indexOfLast { it.trim().lowercase() == "sources" }
        if (sourceIndex < 0) return content to emptyList()

        val sourceLines = lines.drop(sourceIndex + 1).joinToString("\n")
        val sources = parseSources(sourceLines)
        if (sources.isEmpty()) return content to emptyList()

        val body = lines.take(sourceIndex).joinToString("\n").trim()
        return body to sources
    }

    private fun parseSources(text: String): List<MarkdownSource> =
        linkPattern.findAll(text).map { match ->
            MarkdownSource(title = match.groupValues[1], url = match.groupValues[2])
        }.toList()
}
