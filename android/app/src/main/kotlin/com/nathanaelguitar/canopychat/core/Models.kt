package com.nathanaelguitar.canopychat.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// Kotlin port of the iOS domain models in iphone/AetherChat/Models.swift and Theme.swift.
// Field names and JSON encodings intentionally match the Swift Codable output so a future
// sync/export feature can share data between platforms.

enum class MessageRole(val rawValue: String) {
    USER("user"),
    ASSISTANT("assistant");

    val apiRole: String get() = rawValue

    companion object {
        fun from(rawValue: String): MessageRole =
            entries.firstOrNull { it.rawValue == rawValue } ?: USER
    }
}

data class ChatAttachment(
    val id: UUID = UUID.randomUUID(),
    val data: ByteArray = ByteArray(0),
    val mimeType: String = "image/jpeg",
    val filename: String = "image.jpg",
    val extractedText: String? = null
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val displayName: String get() = filename.ifEmpty { "Attachment" }

    override fun equals(other: Any?): Boolean = other is ChatAttachment && other.id == id
    override fun hashCode(): Int = id.hashCode()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id.toString())
        put("mimeType", mimeType)
        put("filename", filename)
        put("extractedText", extractedText ?: JSONObject.NULL)
        put("data", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP))
    }

    companion object {
        fun fromJson(json: JSONObject): ChatAttachment = ChatAttachment(
            id = UUID.fromString(json.getString("id")),
            data = android.util.Base64.decode(json.optString("data", ""), android.util.Base64.NO_WRAP),
            mimeType = json.optString("mimeType", "image/jpeg"),
            filename = json.optString("filename", "image.jpg"),
            extractedText = if (json.isNull("extractedText")) null else json.optString("extractedText")
        )
    }
}

data class ChatMessage(
    val id: UUID = UUID.randomUUID(),
    val role: MessageRole,
    val content: String,
    val attachments: List<ChatAttachment> = emptyList(),
    val timestampMillis: Long = System.currentTimeMillis()
)

data class Workspace(
    val id: String,
    val name: String,
    val iconName: String,
    val colorHex: String,
    val isBuiltIn: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("iconName", iconName)
        put("colorHex", colorHex)
        put("isBuiltIn", isBuiltIn)
    }

    companion object {
        val PERSONAL = Workspace("personal", "Personal", "person", "6B4423", true)
        val WORK = Workspace("work", "Work", "briefcase", "4A7C4A", true)
        val CREATIVE = Workspace("creative", "Creative", "palette", "B87333", true)
        val RESEARCH = Workspace("research", "Research", "book", "4A7CB8", true)
        val BUILT_INS = listOf(PERSONAL, WORK, CREATIVE, RESEARCH)

        fun custom(name: String): Workspace = Workspace(
            id = "custom-${UUID.randomUUID()}",
            name = name.ifBlank { "New Workspace" },
            iconName = "folder",
            colorHex = "A0784A",
            isBuiltIn = false
        )

        fun fromJson(json: JSONObject): Workspace = Workspace(
            id = json.getString("id"),
            name = json.getString("name"),
            iconName = json.optString("iconName", "folder"),
            colorHex = json.optString("colorHex", "A0784A"),
            isBuiltIn = json.optBoolean("isBuiltIn", false)
        )
    }
}

data class AssistantPersona(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("instructions", instructions)
    }

    companion object {
        val DEFAULT = AssistantPersona("default", "Canopy", "Balanced, thoughtful assistant")
        val ANALYTICAL = AssistantPersona("analytical", "Sage", "Deep analytical reasoning")
        val CREATIVE = AssistantPersona("creative", "Muse", "Creative and imaginative thinking")
        val CONCISE = AssistantPersona("concise", "Swift", "Direct and to the point")
        val ALL = listOf(DEFAULT, ANALYTICAL, CREATIVE, CONCISE)

        fun fromJson(json: JSONObject): AssistantPersona = AssistantPersona(
            id = json.getString("id"),
            name = json.getString("name"),
            description = json.optString("description", ""),
            instructions = json.optString("instructions", "")
        )
    }
}

data class Conversation(
    val id: UUID = UUID.randomUUID(),
    var title: String,
    var workspace: Workspace,
    var persona: AssistantPersona = AssistantPersona.DEFAULT,
    var isPinned: Boolean = false,
    var previewText: String = "",
    var updatedAtMillis: Long = System.currentTimeMillis(),
    var messages: List<ChatMessage> = emptyList(),
    var memorySummary: String = ""
)

enum class InferenceProvider(val rawValue: String) {
    ON_DEVICE("On-device"),
    BACKEND("Backend");

    companion object {
        fun from(rawValue: String?): InferenceProvider =
            entries.firstOrNull { it.rawValue == rawValue } ?: ON_DEVICE
    }
}

object ModelCatalog {
    const val CANOPY_V1_DISPLAY_NAME = "Canopy V1"
    const val GGUF_REPOSITORY = "mradermacher/Qwen3.5-2b-Kimi-and-Opus-Distillation-GGUF"
    const val GGUF_FILENAME = "Qwen3.5-2b-Kimi-and-Opus-Distillation.Q4_K_M.gguf"
    const val MMPROJ_FILENAME = "Qwen3.5-2b-Kimi-and-Opus-Distillation.mmproj-Q8_0.gguf"
    const val CONTEXT_TOKENS = 20_000
    const val BATCH_TOKENS = 2_048
    const val MAX_OUTPUT_TOKENS = 512

    val ggufDownloadUrl = "https://huggingface.co/$GGUF_REPOSITORY/resolve/main/$GGUF_FILENAME"
    val mmprojDownloadUrl = "https://huggingface.co/$GGUF_REPOSITORY/resolve/main/$MMPROJ_FILENAME"

    const val RUNTIME_MESSAGE =
        "Canopy V1 runs locally with llama.cpp. The first on-device reply downloads about 1.7 GB, then caches the files on this device."
}

fun jsonArrayOfAttachments(attachments: List<ChatAttachment>): JSONArray {
    val array = JSONArray()
    attachments.forEach { array.put(it.toJson()) }
    return array
}

fun attachmentsFromJsonArray(raw: String): List<ChatAttachment> = try {
    val array = JSONArray(raw)
    (0 until array.length()).map { ChatAttachment.fromJson(array.getJSONObject(it)) }
} catch (_: Exception) {
    emptyList()
}
