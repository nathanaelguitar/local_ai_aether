package com.nathanaelguitar.canopychat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nathanaelguitar.canopychat.core.AssistantPersona
import com.nathanaelguitar.canopychat.core.CanopyLocationService
import com.nathanaelguitar.canopychat.core.CanopyNetworkMonitor
import com.nathanaelguitar.canopychat.core.ChatMessage
import com.nathanaelguitar.canopychat.core.Conversation
import com.nathanaelguitar.canopychat.core.InferenceProvider
import com.nathanaelguitar.canopychat.core.MemoryPlanner
import com.nathanaelguitar.canopychat.core.MemoryStore
import com.nathanaelguitar.canopychat.core.MessageRole
import com.nathanaelguitar.canopychat.core.ModelCatalog
import com.nathanaelguitar.canopychat.core.TitleGenerator
import com.nathanaelguitar.canopychat.core.WebSearchIntent
import com.nathanaelguitar.canopychat.core.WebSearchService
import com.nathanaelguitar.canopychat.core.Workspace
import com.nathanaelguitar.canopychat.inference.BackendInferenceEngine
import com.nathanaelguitar.canopychat.inference.LlamaCppEngine
import com.nathanaelguitar.canopychat.inference.ModelStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.UUID

// Android counterpart of AppState in iphone/AetherChat/Models.swift.
class AppState(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("canopychat", Application.MODE_PRIVATE)
    private val memoryStore = MemoryStore(application)
    private val webSearch = WebSearchService()
    private val locationService = CanopyLocationService(application)
    private val networkMonitor = CanopyNetworkMonitor(application)
    private val modelStore = ModelStore(application.filesDir)
    private val onDevice = LlamaCppEngine(modelStore)
    private val backend = BackendInferenceEngine { apiEndpoint.value }

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _generationStatus = MutableStateFlow<String?>(null)
    val generationStatus: StateFlow<String?> = _generationStatus.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    val apiEndpoint = MutableStateFlow(prefs.getString("apiEndpoint", "") ?: "")
    val customSystemPrompt = MutableStateFlow(prefs.getString("customSystemPrompt", "") ?: "")
    val inferenceProvider = MutableStateFlow(InferenceProvider.from(prefs.getString("inferenceProvider", null)))
    val isDarkTheme = MutableStateFlow(prefs.getBoolean("isDarkTheme", false))
    val defaultWorkspaceId = MutableStateFlow(prefs.getString("defaultWorkspaceId", "personal") ?: "personal")

    private var customWorkspaces = loadWorkspaces()
    private var customPersonas = loadPersonas()

    val availableWorkspaces: List<Workspace> get() = Workspace.BUILT_INS + customWorkspaces
    val availablePersonas: List<AssistantPersona>
        get() = AssistantPersona.ALL + customPersonas.filter { persona ->
            AssistantPersona.ALL.none { it.id == persona.id }
        }
    val defaultWorkspace: Workspace
        get() = availableWorkspaces.firstOrNull { it.id == defaultWorkspaceId.value }
            ?: Workspace.PERSONAL

    init {
        val saved = memoryStore.loadConversations()
        _conversations.value = if (saved.isEmpty()) {
            sampleConversations().also { memoryStore.saveAll(it) }
        } else {
            saved.map(::canonicalizeConversation).map(TitleGenerator::repairIfNeeded)
        }
    }

    fun setDarkTheme(enabled: Boolean) {
        isDarkTheme.value = enabled
        prefs.edit().putBoolean("isDarkTheme", enabled).apply()
    }

    fun setApiEndpoint(endpoint: String) {
        apiEndpoint.value = endpoint
        prefs.edit().putString("apiEndpoint", endpoint).apply()
    }

    fun setCustomSystemPrompt(prompt: String) {
        customSystemPrompt.value = prompt.trim()
        prefs.edit().putString("customSystemPrompt", customSystemPrompt.value).apply()
    }

    fun setInferenceProvider(provider: InferenceProvider) {
        inferenceProvider.value = provider
        prefs.edit().putString("inferenceProvider", provider.rawValue).apply()
    }

    fun setDefaultWorkspace(workspace: Workspace) {
        if (availableWorkspaces.none { it.id == workspace.id }) return
        defaultWorkspaceId.value = workspace.id
        prefs.edit().putString("defaultWorkspaceId", workspace.id).apply()
    }

    fun createCustomWorkspace(name: String): Workspace {
        val workspace = Workspace.custom(name.trim().ifEmpty { "New Workspace" })
        customWorkspaces = customWorkspaces + workspace
        saveWorkspaces()
        return workspace
    }

    fun deleteCustomWorkspace(workspace: Workspace) {
        if (workspace.isBuiltIn) return
        customWorkspaces = customWorkspaces.filterNot { it.id == workspace.id }
        if (defaultWorkspace.id == workspace.id) setDefaultWorkspace(Workspace.PERSONAL)
        _conversations.value = _conversations.value.map { conversation ->
            if (conversation.workspace.id == workspace.id) {
                conversation.copy(workspace = Workspace.PERSONAL).also { memoryStore.saveConversation(it) }
            } else conversation
        }
        saveWorkspaces()
    }

    fun createCustomPersona(name: String, description: String, instructions: String): AssistantPersona {
        val persona = AssistantPersona(
            id = "custom-${UUID.randomUUID()}",
            name = name.trim().ifEmpty { "Custom Assistant" },
            description = description.trim().ifEmpty { "Custom assistant" },
            instructions = instructions.trim()
        )
        customPersonas = customPersonas + persona
        savePersonas()
        return persona
    }

    fun updateCustomPersona(id: String, name: String, description: String, instructions: String): AssistantPersona? {
        if (!id.startsWith("custom-")) return null
        val updated = AssistantPersona(
            id = id,
            name = name.trim().ifEmpty { "Custom Assistant" },
            description = description.trim().ifEmpty { "Custom assistant" },
            instructions = instructions.trim()
        )
        if (customPersonas.none { it.id == id }) return null
        customPersonas = customPersonas.map { if (it.id == id) updated else it }
        _conversations.value = _conversations.value.map { conversation ->
            if (conversation.persona.id == id) {
                conversation.copy(persona = updated).also { memoryStore.saveConversation(it) }
            } else conversation
        }
        savePersonas()
        return updated
    }

    fun deleteCustomPersona(persona: AssistantPersona) {
        if (!persona.id.startsWith("custom-")) return
        customPersonas = customPersonas.filterNot { it.id == persona.id }
        _conversations.value = _conversations.value.map { conversation ->
            if (conversation.persona.id == persona.id) {
                conversation.copy(persona = AssistantPersona.DEFAULT).also { memoryStore.saveConversation(it) }
            } else conversation
        }
        savePersonas()
    }

    fun needsLocationPermissionFor(text: String): Boolean =
        CanopyLocationService.needsLocation(text) && !locationService.hasLocationPermission()

    fun createConversation(title: String, workspace: Workspace = defaultWorkspace, persona: AssistantPersona = AssistantPersona.DEFAULT): UUID {
        val conversation = Conversation(
            title = title.trim().ifEmpty { "Untitled" },
            workspace = workspace,
            persona = persona
        )
        _conversations.value = listOf(conversation) + _conversations.value
        memoryStore.saveConversation(conversation)
        return conversation.id
    }

    fun deleteConversation(id: UUID) {
        _conversations.value = _conversations.value.filterNot { it.id == id }
        memoryStore.deleteConversation(id)
    }

    fun togglePin(id: UUID) {
        updateConversation(id) { it.copy(isPinned = !it.isPinned) }
    }

    fun renameConversation(id: UUID, title: String) {
        updateConversation(id) { it.copy(title = title.trim().ifEmpty { "Untitled" }) }
    }

    fun sendMessage(conversationId: UUID, text: String, attachments: List<com.nathanaelguitar.canopychat.core.ChatAttachment> = emptyList()) {
        val conversation = _conversations.value.firstOrNull { it.id == conversationId } ?: return
        val priorMessages = conversation.messages
        val userMessage = ChatMessage(role = MessageRole.USER, content = text, attachments = attachments)

        updateConversation(conversationId) { current ->
            val title = if (current.title == "Untitled") TitleGenerator.title(text, attachments) else current.title
            current.copy(
                title = title,
                messages = current.messages + userMessage,
                previewText = text.ifBlank { attachments.firstOrNull()?.displayName ?: "Attachment" },
                updatedAtMillis = System.currentTimeMillis(),
                memorySummary = MemoryPlanner.summary(current.messages + userMessage, current.memorySummary)
            )
        }

        viewModelScope.launch {
            _isSending.value = true
            try {
            generateAndAppendReply(conversationId, priorMessages, text)
            } finally {
                _isSending.value = false
                _generationStatus.value = null
            }
        }
    }

    private suspend fun generateAndAppendReply(
        conversationId: UUID,
        priorMessages: List<ChatMessage>,
        latestUserText: String
    ) {
        val conversation = _conversations.value.firstOrNull { it.id == conversationId } ?: return
        val snapshot = conversation.messages
        val persona = conversation.persona

        try {
            _generationStatus.value = "Reading the conversation"

            var webSearchContext: String? = null
            var webSourcesMarkdown: String? = null
            WebSearchIntent.query(latestUserText, priorMessages)?.let { rawWebQuery ->
                if (!networkMonitor.isConnected.value) {
                    webSearchContext = WebSearchService.offlineContext(rawWebQuery)
                } else {
                    _generationStatus.value = "Searching the web"
                    val webQuery = locationService.localizeSearchQuery(rawWebQuery, latestUserText)
                    try {
                        val result = webSearch.search(webQuery)
                        webSearchContext = result.context.ifEmpty { null }
                        webSourcesMarkdown = result.sourcesMarkdown
                    } catch (_: Exception) {
                        webSearchContext = WebSearchService.offlineContext(rawWebQuery)
                    }
                }
            }

            val recentIds = snapshot.takeLast(8).map { it.id }.toSet()
            val hits = memoryStore.relevantMessages(conversationId, latestUserText, recentIds)
            val memoryContext = MemoryPlanner.memoryContext(conversation.memorySummary, hits)

            _generationStatus.value = "Composing a response"
            var response = generateReply(persona, snapshot, webSearchContext, memoryContext, customSystemPrompt.value)

            if (LoopDetector.isLooping(response, snapshot)) {
                _generationStatus.value = "Redirecting repeated response"
                response = generateReply(
                    persona, snapshot, webSearchContext, memoryContext,
                    LoopDetector.redirectedSystemPrompt(customSystemPrompt.value, response, latestUserText)
                )
                if (LoopDetector.isLooping(response, snapshot)) {
                    response = LoopDetector.fallbackRedirectResponse(latestUserText)
                }
            }

            webSourcesMarkdown?.let { sources ->
                val lc = response.lowercase()
                if (!(lc.contains("sources") && lc.contains("](http"))) {
                    response = "${response.trim()}\n\n$sources"
                }
            }

            appendAssistantMessage(conversationId, response.trim())
        } catch (e: Exception) {
            appendAssistantMessage(conversationId, "Inference error: ${e.message ?: e.toString()}")
        }
    }

    private suspend fun generateReply(
        persona: AssistantPersona,
        messages: List<ChatMessage>,
        webSearchContext: String?,
        memoryContext: String?,
        customSystemPrompt: String
    ): String {
        val engine = if (inferenceProvider.value == InferenceProvider.ON_DEVICE && onDevice.isAvailable) {
            onDevice
        } else {
            backend
        }
        return engine.send(persona, messages, webSearchContext, memoryContext, customSystemPrompt) { status ->
            _generationStatus.value = status ?: "Composing a response"
        }
    }

    private fun appendAssistantMessage(conversationId: UUID, content: String) {
        updateConversation(conversationId) { current ->
            val reply = ChatMessage(role = MessageRole.ASSISTANT, content = content)
            current.copy(
                messages = current.messages + reply,
                previewText = content,
                updatedAtMillis = System.currentTimeMillis(),
                memorySummary = MemoryPlanner.summary(current.messages + reply, current.memorySummary)
            )
        }
    }

    private fun updateConversation(id: UUID, transform: (Conversation) -> Conversation) {
        var updated: Conversation? = null
        _conversations.value = _conversations.value.map { conversation ->
            if (conversation.id == id) transform(conversation).also { updated = it } else conversation
        }
        updated?.let { memoryStore.saveConversation(it) }
    }

    private fun canonicalizeConversation(conversation: Conversation): Conversation {
        val workspace = availableWorkspaces.firstOrNull { it.id == conversation.workspace.id } ?: Workspace.PERSONAL
        val persona = availablePersonas.firstOrNull { it.id == conversation.persona.id } ?: AssistantPersona.DEFAULT
        return conversation.copy(workspace = workspace, persona = persona)
    }

    private fun loadWorkspaces(): List<Workspace> = runCatching {
        val raw = prefs.getString("customWorkspaces", "[]") ?: "[]"
        val array = JSONArray(raw)
        (0 until array.length()).map { Workspace.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun saveWorkspaces() {
        val array = JSONArray()
        customWorkspaces.forEach { array.put(it.toJson()) }
        prefs.edit().putString("customWorkspaces", array.toString()).apply()
    }

    private fun loadPersonas(): List<AssistantPersona> = runCatching {
        val raw = prefs.getString("customPersonas", "[]") ?: "[]"
        val array = JSONArray(raw)
        (0 until array.length()).map { AssistantPersona.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun savePersonas() {
        val array = JSONArray()
        customPersonas.forEach { array.put(it.toJson()) }
        prefs.edit().putString("customPersonas", array.toString()).apply()
    }

    override fun onCleared() {
        networkMonitor.close()
        super.onCleared()
    }

    private fun sampleConversations(): List<Conversation> = listOf(
        Conversation(
            title = "Product Launch Checklist",
            workspace = Workspace.WORK,
            persona = AssistantPersona.ANALYTICAL,
            isPinned = true,
            previewText = "Here's a two-week launch checklist, working backward from release day...",
            messages = listOf(
                ChatMessage(role = MessageRole.USER, content = "Help me build a launch checklist for a small product release in two weeks."),
                ChatMessage(role = MessageRole.ASSISTANT, content = "Here's a two-week launch checklist, working backward from release day: finalize pricing and positioning this week, then line up announcement copy, support docs, and a rollback plan for week two. Want me to expand any of these into daily tasks?")
            )
        ),
        Conversation(
            title = "Customer Follow-Up Email",
            workspace = Workspace.WORK,
            persona = AssistantPersona.DEFAULT,
            previewText = "Here's a short, friendly follow-up draft you can adjust...",
            messages = listOf(
                ChatMessage(role = MessageRole.USER, content = "Draft a friendly follow-up email to a customer who went quiet after a demo."),
                ChatMessage(role = MessageRole.ASSISTANT, content = "Here's a short, friendly follow-up draft you can adjust:\n\nSubject: Following up on the demo\n\nHi [Name],\n\nThanks again for taking the time to see the product last week. I know things get busy, so no pressure — I just wanted to check whether any questions came up on your side. Happy to share pricing details or set up a quick call with your team whenever it's useful.\n\nBest,\n[Your name]")
            )
        ),
        Conversation(
            title = "Social Post Ideas",
            workspace = Workspace.CREATIVE,
            persona = AssistantPersona.CREATIVE,
            previewText = "Three angles for this week's posts: behind-the-scenes, a customer win, and a quick tip...",
            messages = listOf(
                ChatMessage(role = MessageRole.USER, content = "Give me a few social post ideas for a small business this week."),
                ChatMessage(role = MessageRole.ASSISTANT, content = "Three angles for this week's posts: behind-the-scenes, a customer win, and a quick tip. Behind-the-scenes builds trust, the customer win is social proof, and a genuinely useful tip earns saves and shares. Tell me your business and I'll draft all three.")
            )
        )
    )
}

// Port of the loop-detection helpers in AppState (iphone/AetherChat/Models.swift).
object LoopDetector {

    fun isLooping(response: String, messages: List<ChatMessage>): Boolean =
        isRepeatedAssistantResponse(response, messages) || hasInternalRepetition(response)

    fun redirectedSystemPrompt(basePrompt: String, rejectedResponse: String, latestUserText: String): String {
        val parts = mutableListOf<String>()
        if (basePrompt.isNotBlank()) parts.add(basePrompt.trim())
        parts.add(
            """
            Loop prevention: your previous draft repeated an earlier assistant reply instead of answering the latest user message.
            Do not repeat, summarize, or ask again for details already provided.
            Answer this latest user message directly: "${latestUserText.trim()}"
            Avoid starting with the same wording as this rejected draft:
            ${rejectedResponse.take(600)}
            """.trimIndent()
        )
        return parts.joinToString("\n\n")
    }

    fun fallbackRedirectResponse(latestUserText: String): String {
        val trimmed = latestUserText.trim()
        return if (trimmed.isEmpty()) {
            "I don't have enough reliable information to answer that confidently. Please add a little more context and I will try again."
        } else {
            "I don't have enough reliable information to answer that confidently. Please add more context about \"$trimmed\" or rephrase it, and I will try again."
        }
    }

    private fun isRepeatedAssistantResponse(response: String, messages: List<ChatMessage>): Boolean {
        val candidate = normalized(response)
        if (candidate.length <= 80) return false
        return messages.reversed()
            .filter { it.role == MessageRole.ASSISTANT }
            .take(4)
            .any { previous ->
                val normalizedPrevious = normalized(previous.content)
                normalizedPrevious.length > 80 &&
                    (candidate == normalizedPrevious || similarity(candidate, normalizedPrevious) >= 0.92)
            }
    }

    private fun hasInternalRepetition(response: String): Boolean {
        val normalized = normalized(response)
        val words = normalized.split(" ").filter { it.isNotEmpty() }
        if (words.size < 32) return false

        val windowSize = 14
        val windowCounts = mutableMapOf<String, Int>()
        for (start in 0..(words.size - windowSize)) {
            val window = words.subList(start, start + windowSize).joinToString(" ")
            val count = windowCounts.merge(window, 1, Int::plus) ?: 1
            if (count >= 2) return true
        }

        val sentences = response.split(Regex("[.!?\\n]")).map { normalized(it) }.filter { it.length >= 45 }
        val sentenceCounts = mutableMapOf<String, Int>()
        for (sentence in sentences) {
            val count = sentenceCounts.merge(sentence, 1, Int::plus) ?: 1
            if (count >= 2) return true
        }
        return false
    }

    private fun normalized(text: String): String = text
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .replace(Regex("[^a-z0-9 ]"), "")
        .trim()

    private fun similarity(lhs: String, rhs: String): Double {
        val lhsWords = lhs.split(" ").toSet()
        val rhsWords = rhs.split(" ").toSet()
        if (lhsWords.isEmpty() || rhsWords.isEmpty()) return 0.0
        return lhsWords.intersect(rhsWords).size.toDouble() / lhsWords.union(rhsWords).size.toDouble()
    }
}
