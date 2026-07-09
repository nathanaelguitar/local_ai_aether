package com.nathanaelguitar.canopychat.core

import java.util.UUID

// Port of AetherMemoryPlanner from iphone/AetherChat/AetherMemoryStore.swift.

data class MemoryHit(
    val messageId: UUID,
    val role: MessageRole,
    val content: String,
    val timestampMillis: Long
)

object MemoryPlanner {

    fun summary(messages: List<ChatMessage>, existingSummary: String = ""): String {
        val nonError = messages.filter { !it.content.startsWith("Inference error:") }
        if (nonError.size <= 12) return existingSummary

        val older = nonError.dropLast(8)
        val lines = older.takeLast(12).map { message ->
            val speaker = if (message.role == MessageRole.USER) "User" else "Assistant"
            val target = if (message.role == MessageRole.USER) 280 else 360
            "- $speaker: ${compact(message.content, target)}"
        }
        if (lines.isEmpty()) return existingSummary
        return "Earlier conversation summary:\n" + lines.joinToString("\n")
    }

    fun memoryContext(summary: String, hits: List<MemoryHit>): String? {
        val parts = mutableListOf<String>()
        val trimmedSummary = summary.trim()
        if (trimmedSummary.isNotEmpty()) parts.add(trimmedSummary)

        val retrieved = hits
            .filter { it.content.isNotBlank() }
            .take(6)
            .map { hit ->
                val speaker = if (hit.role == MessageRole.USER) "User" else "Assistant"
                "- $speaker: ${compact(hit.content, 520)}"
            }
        if (retrieved.isNotEmpty()) {
            parts.add("Relevant earlier turns retrieved from local memory:\n" + retrieved.joinToString("\n"))
        }

        return if (parts.isEmpty()) null else parts.joinToString("\n\n")
    }

    fun compact(text: String, targetCharacters: Int): String {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.length <= targetCharacters) return cleaned

        val sentences = cleaned
            .split(Regex("[.!?\\n]"))
            .map { it.trim() }
            .filter { it.length >= 24 }
        val keySentences = sentences.sortedByDescending { score(it) }.take(6)

        val headCount = maxOf(120, targetCharacters / 4)
        val tailCount = maxOf(100, targetCharacters / 5)
        val parts = mutableListOf("Opening: ${cleaned.take(headCount)}")
        if (keySentences.isNotEmpty()) {
            parts.add("Key points: " + keySentences.joinToString(" | "))
        }
        parts.add("Ending: ${cleaned.takeLast(tailCount)}")

        var compacted = "[Compacted summary of long content]\n" + parts.joinToString("\n")
        if (compacted.length > targetCharacters) {
            compacted = compacted.take(targetCharacters) + "\n[Compacted to fit local context.]"
        }
        return compacted
    }

    private val weightedTerms = listOf(
        "because", "therefore", "important", "summary", "result", "decision",
        "error", "fix", "issue", "date", "price", "weather", "forecast",
        "requirement", "must", "should", "cannot", "can", "number", "total"
    )

    private fun score(sentence: String): Int {
        val lc = sentence.lowercase()
        var value = minOf(sentence.length / 12, 18)
        for (term in weightedTerms) {
            if (lc.contains(term)) value += 8
        }
        if (sentence.contains(Regex("\\d"))) value += 6
        return value
    }
}
