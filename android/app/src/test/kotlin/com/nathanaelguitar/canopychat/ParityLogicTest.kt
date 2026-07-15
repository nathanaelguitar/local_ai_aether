package com.nathanaelguitar.canopychat

import com.nathanaelguitar.canopychat.core.AssistantPersona
import com.nathanaelguitar.canopychat.core.ChatAttachment
import com.nathanaelguitar.canopychat.core.ChatMessage
import com.nathanaelguitar.canopychat.core.MessageRole
import com.nathanaelguitar.canopychat.core.PromptBuilder
import com.nathanaelguitar.canopychat.core.WebSearchIntent
import com.nathanaelguitar.canopychat.core.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParityLogicTest {

    @Test
    fun webSearchIntentPreservesSubjectAcrossWeakFollowUp() {
        val previous = listOf(
            ChatMessage(role = MessageRole.USER, content = "What is OpenAI's current IPO status?"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "I would verify that live.")
        )

        val query = WebSearchIntent.query("Look it up", previous)

        assertTrue(query?.contains("OpenAI") == true)
        assertTrue(query?.contains("IPO") == true)
    }

    @Test
    fun promptCarriesGroundingRulesPreferencesAndAttachmentBudgeting() {
        val image = ChatAttachment(data = byteArrayOf(1, 2, 3), mimeType = "image/png", filename = "photo.png")
        val file = ChatAttachment(
            data = byteArrayOf(),
            mimeType = "text/plain",
            filename = "notes.txt",
            extractedText = "A project note that should be available to the local prompt."
        )
        val prompt = PromptBuilder.prompt(
            persona = AssistantPersona.DEFAULT.copy(instructions = "Use a calm tone."),
            messages = listOf(ChatMessage(role = MessageRole.USER, content = "What is happening today?", attachments = listOf(image, file))),
            webSearchContext = "Network status: offline. Current information is unavailable.",
            customSystemPrompt = "Prefer short paragraphs."
        )

        assertTrue(prompt.contains("Use a calm tone."))
        assertTrue(prompt.contains("Prefer short paragraphs."))
        assertTrue(prompt.contains("device is offline"))
        assertTrue(prompt.contains("<__media__>"))
        assertTrue(prompt.contains("notes.txt"))
    }

    @Test
    fun loopDetectorRejectsRepeatedAssistantDrafts() {
        val old = "This is a repeated answer with enough words to make the similarity detector consider it a real response rather than a short acknowledgement."
        val messages = listOf(ChatMessage(role = MessageRole.ASSISTANT, content = old))

        assertTrue(LoopDetector.isLooping(old, messages))
        assertFalse(LoopDetector.isLooping("A short new answer.", messages))
    }

    @Test
    fun customWorkspaceAndPersonaRemainSelectable() {
        val workspace = Workspace.custom("Research")
        val persona = AssistantPersona("custom-test", "Scout", "Research assistant", "Cite sources")

        assertTrue(workspace.id.startsWith("custom-"))
        assertFalse(workspace.isBuiltIn)
        assertEquals("Scout", persona.name)
        assertEquals("Cite sources", persona.instructions)
    }
}
