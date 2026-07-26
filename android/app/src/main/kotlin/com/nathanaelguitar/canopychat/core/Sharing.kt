package com.nathanaelguitar.canopychat.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Android substitute for AetherShare / SharePayload / ActivityView in
 * iphone/AetherChat/ChatView.swift. UIActivityViewController has no direct
 * equivalent; this uses the system share sheet via Intent.ACTION_SEND.
 */
object CanopyShare {

    /**
     * iOS reads AETHER_APP_STORE_URL from Info.plist. Android has no Info.plist, so the
     * Play listing URL is derived from the package name once the app is published.
     * Returns null until PLAY_STORE_URL is set, matching the iOS "no URL configured" path.
     */
    private const val PLAY_STORE_URL = ""

    private val storeUrl: String?
        get() = PLAY_STORE_URL.trim().takeIf { it.isNotEmpty() }

    fun messageText(text: String): String {
        val url = storeUrl ?: return text
        return "$text\n\nShared from CanopyChat: $url"
    }

    fun conversationText(conversation: Conversation): String {
        val transcript = conversation.messages.joinToString("\n\n") { message ->
            val speaker = if (message.role == MessageRole.USER) "You" else conversation.persona.name
            val content = message.content.trim()
            val attachmentText = if (message.attachments.isEmpty()) {
                ""
            } else {
                "\n[Attachments: ${message.attachments.joinToString(", ") { it.displayName }}]"
            }
            "$speaker: $content$attachmentText"
        }

        val base = "CanopyChat conversation: ${conversation.title}\n" +
            "with ${conversation.persona.name}\n\n$transcript"
        val url = storeUrl ?: return base
        return "$base\n\nShared from CanopyChat: $url"
    }

    fun shareText(context: Context, text: String, chooserTitle: String = "Share") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Port of SharePayload(feedbackText:), which attaches a mailto: URL alongside the text. */
    fun shareFeedback(context: Context, feedbackText: String) {
        val mailto = Uri.parse(
            "mailto:${Uri.encode(CanopyLegal.SUPPORT_EMAIL)}" +
                "?subject=${Uri.encode("CanopyChat Feedback")}" +
                "&body=${Uri.encode(feedbackText)}"
        )
        val emailIntent = Intent(Intent.ACTION_SENDTO, mailto).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching { context.startActivity(emailIntent); true }.getOrDefault(false)
        if (!launched) shareText(context, feedbackText, "Send feedback")
    }
}

/** Mirrors SpeechPlaybackState in iphone/AetherChat/ChatView.swift. */
enum class SpeechPlaybackState { STOPPED, PLAYING, PAUSED }

/**
 * Android substitute for AetherSpeechController (AVSpeechSynthesizer) in
 * iphone/AetherChat/ChatView.swift.
 *
 * Note: Android's TextToSpeech has no true pause/resume. PAUSED is implemented by
 * stopping playback and remembering the remaining text, so resume() restarts from the
 * start of the current utterance rather than mid-word.
 */
class CanopySpeechController private constructor(context: Context) {

    private var engine: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null
    private var currentText: String = ""
    private var onDone: (() -> Unit)? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                engine?.language = Locale.getDefault().takeIf {
                    engine?.isLanguageAvailable(it) == TextToSpeech.LANG_AVAILABLE
                } ?: Locale.US
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        onDone?.invoke()
                    }

                    @Deprecated("Required override", ReplaceWith(""))
                    override fun onError(utteranceId: String?) {
                        onDone?.invoke()
                    }
                })
                pendingText?.let { text ->
                    pendingText = null
                    speak(text) { onDone?.invoke() }
                }
            }
        }
    }

    fun speak(text: String, completion: () -> Unit) {
        onDone = completion
        currentText = text
        if (!ready) {
            pendingText = text
            return
        }
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "canopy.reply")
    }

    fun pause() {
        engine?.stop()
    }

    fun resume() {
        if (currentText.isEmpty()) return
        engine?.speak(currentText, TextToSpeech.QUEUE_FLUSH, null, "canopy.reply")
    }

    fun stop() {
        pendingText = null
        currentText = ""
        onDone = null
        engine?.stop()
    }

    fun shutdown() {
        stop()
        engine?.shutdown()
        engine = null
    }

    companion object {
        @Volatile
        private var instance: CanopySpeechController? = null

        fun shared(context: Context): CanopySpeechController =
            instance ?: synchronized(this) {
                instance ?: CanopySpeechController(context).also { instance = it }
            }
    }
}
