package com.nathanaelguitar.canopychat.core

// Port of AetherTitleGenerator from iphone/AetherChat/Models.swift.
object TitleGenerator {

    private val stopwords = setOf(
        "a", "an", "and", "are", "at", "can", "could", "do", "does", "for", "good",
        "how", "i", "in", "is", "it", "me", "my", "near", "of", "on", "or", "place",
        "please", "the", "to", "want", "what", "whats", "where", "with", "you"
    )

    fun title(text: String, attachments: List<ChatAttachment>): String {
        val cleaned = cleanedSource(text)
        val source = if (cleaned.isEmpty() && attachments.isNotEmpty()) {
            val first = attachments.first()
            if (first.isImage) "Image Analysis" else first.displayName
        } else {
            cleaned
        }

        val words = source
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 && it.lowercase() !in stopwords }
            .take(5)

        val title = words.joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
        return if (title.isEmpty()) "Untitled" else title.take(42)
    }

    fun repairIfNeeded(conversation: Conversation): Conversation {
        if (!shouldRepair(conversation.title)) return conversation
        val firstUser = conversation.messages.firstOrNull { it.role == MessageRole.USER } ?: return conversation
        return conversation.copy(title = title(firstUser.content, firstUser.attachments))
    }

    private fun shouldRepair(title: String): Boolean {
        val first = title.split(" ").firstOrNull() ?: return false
        return first.length == 1 || first.lowercase() == "s" || title.isBlank()
    }

    private fun cleanedSource(text: String): String {
        var cleaned = text
            .replace("\n", " ")
            .replace(Regex("(?i)\\bwhat['’]s\\b"), "what is")
            .replace(Regex("(?i)\\bwhere['’]s\\b"), "where is")
            .replace(Regex("(?i)\\bwho['’]s\\b"), "who is")
            .replace(Regex("(?i)\\bhow['’]s\\b"), "how is")
            .replace(Regex("(?i)\\bfind\\s+(me\\s+)?\\b"), " ")
            .replace(Regex("(?i)\\btell\\s+me\\s+about\\b"), " ")
            .replace(Regex("(?i)\\bnear\\s+me\\b"), " ")
            .replace(Regex("(?i)\\baround\\s+me\\b"), " ")
            .replace(Regex("(?i)\\bmy\\s+area\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        for (prefix in listOf("what is a ", "what is an ", "what is ")) {
            if (cleaned.lowercase().startsWith(prefix)) {
                cleaned = cleaned.drop(prefix.length)
                break
            }
        }
        return cleaned.trim()
    }
}
