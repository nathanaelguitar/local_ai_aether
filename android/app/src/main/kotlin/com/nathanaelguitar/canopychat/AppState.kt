package com.nathanaelguitar.canopychat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nathanaelguitar.canopychat.core.AssistantPersona
import com.nathanaelguitar.canopychat.core.CanopyLocationService
import com.nathanaelguitar.canopychat.core.CanopyNetworkMonitor
import com.nathanaelguitar.canopychat.core.CanopyNotifications
import com.nathanaelguitar.canopychat.core.ChatAttachment
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

    /** In-flight generation, so stopSending() can cancel it. */
    private var replyJob: Job? = null

    /** Distinguishes a user-initiated stop from a cancellation caused by the next turn. */
    private var stoppedByUser = false

    /** Mirrors offlineWebNoticeShownConversationIDs on iOS. */
    private val offlineWebNoticeShownConversationIds = mutableSetOf<UUID>()

    private val _generationStatus = MutableStateFlow<String?>(null)
    val generationStatus: StateFlow<String?> = _generationStatus.asStateFlow()

    private val _modelLoadingMessage = MutableStateFlow<String?>(null)
    val modelLoadingMessage: StateFlow<String?> = _modelLoadingMessage.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    /** Mirrors AppState.appIsActive on iOS; drives the background-reply notification. */
    private val _appIsActive = MutableStateFlow(true)
    val appIsActive: StateFlow<Boolean> = _appIsActive.asStateFlow()

    // iOS defaults to http://127.0.0.1:8787; 10.0.2.2 is that host as seen from an emulator.
    val apiEndpoint = MutableStateFlow(prefs.getString("apiEndpoint", "http://10.0.2.2:8787") ?: "")
    val customSystemPrompt = MutableStateFlow(prefs.getString("customSystemPrompt", "") ?: "")
    val inferenceProvider = MutableStateFlow(InferenceProvider.from(prefs.getString("inferenceProvider", null)))
    val isDarkTheme = MutableStateFlow(prefs.getBoolean("isDarkTheme", false))
    val defaultWorkspaceId = MutableStateFlow(prefs.getString("defaultWorkspaceId", "personal") ?: "personal")
    val messageFontScale = MutableStateFlow(
        prefs.getFloat("messageFontScale", 1.0f).toDouble().takeIf { it > 0.0 } ?: 1.0
    )

    /**
     * Mirrors AppState.selectedModel on iOS, including the "Aether V1" → "Canopy V1"
     * migration for installs that predate the rename.
     */
    val selectedModel = MutableStateFlow(
        prefs.getString("selectedModel", null)
            ?.takeIf { it != ModelCatalog.LEGACY_DISPLAY_NAME }
            ?: ModelCatalog.CANOPY_V1_DISPLAY_NAME
    )

    // StateFlow-backed so Compose recomposes when a custom workspace/assistant is
    // created, edited, or deleted.
    private val _customWorkspaces = MutableStateFlow(loadWorkspaces())
    private val _customPersonas = MutableStateFlow(loadPersonas())

    private var customWorkspaces: List<Workspace>
        get() = _customWorkspaces.value
        set(value) { _customWorkspaces.value = value }
    private var customPersonas: List<AssistantPersona>
        get() = _customPersonas.value
        set(value) { _customPersonas.value = value }

    val workspacesFlow: StateFlow<List<Workspace>> = _customWorkspaces.asStateFlow()
    val personasFlow: StateFlow<List<AssistantPersona>> = _customPersonas.asStateFlow()

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
        removeLegacySeedConversations()
    }

    /**
     * Mirrors AppState.selectedModel's didSet on iOS: selecting the bundled model
     * also forces the on-device provider.
     */
    fun setSelectedModel(model: String) {
        selectedModel.value = model
        prefs.edit().putString("selectedModel", model).apply()
        if (model == ModelCatalog.CANOPY_V1_DISPLAY_NAME) {
            setInferenceProvider(InferenceProvider.ON_DEVICE)
        }
    }

    fun setMessageFontScale(scale: Double) {
        val clamped = scale.coerceIn(0.85, 1.35)
        messageFontScale.value = clamped
        prefs.edit().putFloat("messageFontScale", clamped.toFloat()).apply()
    }

    fun setAppIsActive(active: Boolean) {
        _appIsActive.value = active
    }

    /**
     * Port of removeLegacySeedConversations in iphone/AetherChat/Models.swift — drops
     * the pre-rename starter chats so upgrading installs don't keep stale samples.
     */
    private fun removeLegacySeedConversations() {
        val legacySeedTitles = setOf(
            "Morning Reflection",
            "Q3 Strategy Deck",
            "Novel Outline",
            "ML Paper Notes"
        )
        val removed = _conversations.value.filter { legacySeedTitles.contains(it.title) }
        if (removed.isEmpty()) return
        _conversations.value = _conversations.value.filterNot { legacySeedTitles.contains(it.title) }
        removed.forEach { memoryStore.deleteConversation(it.id) }
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

    fun sendMessage(conversationId: UUID, text: String, attachments: List<ChatAttachment> = emptyList()) {
        val conversation = _conversations.value.firstOrNull { it.id == conversationId } ?: return
        val priorMessages = conversation.messages
        val userMessage = ChatMessage(role = MessageRole.USER, content = text, attachments = attachments)

        updateConversation(conversationId) { current ->
            val title = if (current.title == "Untitled") TitleGenerator.title(text, attachments) else current.title
            current.copy(
                title = title,
                messages = current.messages + userMessage,
                previewText = text.ifBlank { attachmentPreview(attachments) },
                updatedAtMillis = System.currentTimeMillis(),
                memorySummary = MemoryPlanner.summary(current.messages + userMessage, current.memorySummary)
            )
        }

        launchReply(conversationId, priorMessages, text)
    }

    /**
     * Port of editUserMessage in iphone/AetherChat/Models.swift — rewrites a user turn,
     * truncates everything after it, and regenerates from that point.
     */
    fun editUserMessage(conversationId: UUID, messageId: UUID, text: String) {
        val conversation = _conversations.value.firstOrNull { it.id == conversationId } ?: return
        val messageIndex = conversation.messages.indexOfFirst { it.id == messageId }
        if (messageIndex < 0) return
        val target = conversation.messages[messageIndex]
        if (target.role != MessageRole.USER) return

        val updatedText = text.trim()
        val priorMessages = conversation.messages.take(messageIndex)

        updateConversation(conversationId) { current ->
            val edited = current.messages[messageIndex].copy(content = updatedText)
            val truncated = current.messages.take(messageIndex) + edited
            current.copy(
                messages = truncated,
                previewText = if (edited.attachments.isEmpty()) {
                    updatedText
                } else {
                    updatedText.ifBlank { attachmentPreview(edited.attachments) }
                },
                updatedAtMillis = System.currentTimeMillis(),
                memorySummary = MemoryPlanner.summary(truncated, current.memorySummary)
            )
        }

        launchReply(conversationId, priorMessages, updatedText)
    }

    /**
     * Port of regenerateLastResponse in iphone/AetherChat/Models.swift — drops the trailing
     * assistant turn(s) and re-answers the last user message.
     */
    fun regenerateLastResponse(conversationId: UUID) {
        val conversation = _conversations.value.firstOrNull { it.id == conversationId } ?: return
        val lastAssistantIndex = conversation.messages.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (lastAssistantIndex < 0) return
        val promptMessages = conversation.messages.take(lastAssistantIndex)
        val lastUserIndex = promptMessages.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIndex < 0) return
        val latestUser = promptMessages[lastUserIndex]

        updateConversation(conversationId) { current ->
            current.copy(
                messages = promptMessages,
                previewText = latestUser.content.ifBlank { attachmentPreview(latestUser.attachments) },
                updatedAtMillis = System.currentTimeMillis(),
                memorySummary = MemoryPlanner.summary(promptMessages, current.memorySummary)
            )
        }

        launchReply(conversationId, promptMessages.take(lastUserIndex), latestUser.content)
    }

    /** Port of ChatView.stopSending on iOS — cancels the in-flight generation. */
    fun stopSending() {
        // Only a deliberate stop should leave a "Response stopped." message behind;
        // a cancellation caused by starting the next turn must not.
        stoppedByUser = true
        replyJob?.cancel()
    }

    private fun launchReply(conversationId: UUID, priorMessages: List<ChatMessage>, latestUserText: String) {
        replyJob?.cancel()
        stoppedByUser = false
        lateinit var job: Job
        job = viewModelScope.launch {
            _isSending.value = true
            try {
                generateAndAppendReply(conversationId, priorMessages, latestUserText)
            } finally {
                // A superseded job must not clear state belonging to the job that replaced
                // it — cancel() does not join, so these finally blocks can interleave.
                if (replyJob === job) {
                    _isSending.value = false
                    _generationStatus.value = null
                    _modelLoadingMessage.value = null
                }
            }
        }
        replyJob = job
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
            _generationStatus.value = if (snapshot.any { it.attachments.isNotEmpty() }) {
                "Reading attachments and the conversation"
            } else {
                "Reading the conversation"
            }

            var webSearchContext: String? = null
            var webSourcesMarkdown: String? = null
            WebSearchIntent.query(latestUserText, priorMessages)?.let { rawWebQuery ->
                if (networkMonitor.hasReceivedStatus && !networkMonitor.isConnected.value) {
                    webSearchContext = offlineWebContext(rawWebQuery, conversationId)
                } else {
                    _generationStatus.value = "Searching the web"
                    try {
                        val webQuery = locationService.localizeSearchQuery(rawWebQuery, latestUserText)
                        val result = webSearch.search(webQuery)
                        webSearchContext = result.context.ifEmpty { null }
                        webSourcesMarkdown = result.sourcesMarkdown
                    } catch (_: Exception) {
                        webSearchContext = offlineWebContext(rawWebQuery, conversationId)
                    }
                }
            }

            val recentIds = snapshot.takeLast(8).map { it.id }.toSet()
            val hits = memoryStore.relevantMessages(conversationId, latestUserText, recentIds)
            val memoryContext = MemoryPlanner.memoryContext(conversation.memorySummary, hits)

            _generationStatus.value = "Composing a response"
            var response = generateReply(persona, snapshot, webSearchContext, memoryContext, runtimeSystemPrompt)

            if (LoopDetector.isLooping(response, snapshot)) {
                _generationStatus.value = "Redirecting repeated response"
                response = generateReply(
                    persona, snapshot, webSearchContext, memoryContext,
                    LoopDetector.redirectedSystemPrompt(runtimeSystemPrompt, response, latestUserText)
                )
                if (LoopDetector.isLooping(response, snapshot)) {
                    _generationStatus.value = "Trying a shorter recovery"
                    response = generateReply(
                        persona, snapshot, webSearchContext, memoryContext,
                        LoopDetector.compactRecoverySystemPrompt(runtimeSystemPrompt, latestUserText)
                    )
                }
                if (LoopDetector.isLooping(response, snapshot)) {
                    response = LoopDetector.fallbackRedirectResponse(latestUserText)
                }
            }

            response = responseWithSources(response, webSourcesMarkdown)
            _modelLoadingMessage.value = null
            _generationStatus.value = null
            appendAssistantMessage(conversationId, response)
            notifyIfNeeded(conversationId, response)
        } catch (cancellation: CancellationException) {
            _modelLoadingMessage.value = null
            _generationStatus.value = null
            if (stoppedByUser) {
                appendAssistantMessage(conversationId, "Response stopped.")
            }
            throw cancellation
        } catch (error: Exception) {
            _modelLoadingMessage.value = null
            _generationStatus.value = null
            val message = localBackgroundInterruptionMessage(error)
                ?: "Inference error: ${inferenceErrorDescription(error)}"
            appendAssistantMessage(conversationId, message)
            notifyIfNeeded(conversationId, message)
        }
    }

    /** Port of offlineWebContext on iOS — the unavailable notice is shown once per conversation. */
    private fun offlineWebContext(query: String, conversationId: UUID): String {
        val includeNotice = offlineWebNoticeShownConversationIds.add(conversationId)
        return WebSearchService.offlineContext(query, includeNotice)
    }

    /** Port of responseWithSources on iOS — appends citations unless the model already cited. */
    private fun responseWithSources(response: String, sourcesMarkdown: String?): String {
        val trimmed = response.trim()
        val sources = sourcesMarkdown?.trim().orEmpty()
        if (sources.isEmpty()) return trimmed
        val lowercased = trimmed.lowercase()
        if (lowercased.contains("sources") && lowercased.contains("](http")) return trimmed
        return "$trimmed\n\n$sources"
    }

    /** Port of runtimeSystemPrompt on iOS. */
    private val runtimeSystemPrompt: String
        get() = customSystemPrompt.value.trim()

    private suspend fun generateReply(
        persona: AssistantPersona,
        messages: List<ChatMessage>,
        webSearchContext: String?,
        memoryContext: String?,
        customSystemPrompt: String
    ): String {
        // Mirrors generateReply on iOS: the bundled model always goes on-device, and an
        // unavailable local runtime is a hard error rather than a silent fall-through to a
        // backend endpoint the user has no UI to configure.
        val wantsOnDevice = selectedModel.value == ModelCatalog.CANOPY_V1_DISPLAY_NAME ||
            inferenceProvider.value == InferenceProvider.ON_DEVICE
        if (wantsOnDevice && !onDevice.isAvailable) {
            throw IllegalStateException(
                "${selectedModel.value} is not available for on-device inference on this device. " +
                    "The bundled llama.cpp runtime could not be loaded."
            )
        }
        val engine = if (wantsOnDevice) onDevice else backend
        return engine.send(persona, messages, webSearchContext, memoryContext, customSystemPrompt) { status ->
            if (status != null) {
                _modelLoadingMessage.value = status
            } else {
                _modelLoadingMessage.value = null
                _generationStatus.value = "Composing a response"
            }
        }
    }

    /** Port of inferenceErrorDescription on iOS. */
    private fun inferenceErrorDescription(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.toString()

    /**
     * Port of localBackgroundInterruptionMessage on iOS — local inference killed while
     * backgrounded produces a decode failure that is not the user's fault.
     */
    private fun localBackgroundInterruptionMessage(error: Throwable): String? {
        if (_appIsActive.value) return null
        val message = error.message.orEmpty()
        val interrupted = message.contains("decode", ignoreCase = true) ||
            message.contains("empty response", ignoreCase = true)
        if (!interrupted) return null
        return "CanopyChat was interrupted while running local inference in the background. " +
            "The local model has been reset; please resend the message."
    }

    /** Port of notifyIfNeeded on iOS — only notifies when the app is backgrounded. */
    private fun notifyIfNeeded(conversationId: UUID, response: String) {
        if (_appIsActive.value) return
        val title = _conversations.value.firstOrNull { it.id == conversationId }?.title ?: "Canopy"
        val preview = response.replace("\n", " ").trim().take(160)
        CanopyNotifications.notifyReplyReady(getApplication(), "$title replied", preview)
    }

    /** Port of attachmentPreview on iOS. */
    private fun attachmentPreview(attachments: List<ChatAttachment>): String {
        val imageCount = attachments.count { it.isImage }
        val fileCount = attachments.size - imageCount
        return when {
            imageCount == 0 && fileCount == 0 -> ""
            fileCount == 0 -> "$imageCount image attachment${if (imageCount == 1) "" else "s"}"
            imageCount == 0 -> "$fileCount file attachment${if (fileCount == 1) "" else "s"}"
            else -> "$imageCount image${if (imageCount == 1) "" else "s"}, " +
                "$fileCount file${if (fileCount == 1) "" else "s"}"
        }
    }

    private fun appendAssistantMessage(conversationId: UUID, content: String) {
        updateConversation(conversationId) { current ->
            val reply = ChatMessage(role = MessageRole.ASSISTANT, content = content.trim())
            current.copy(
                messages = current.messages + reply,
                previewText = reply.content,
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
            title = "Dinner From What's Left",
            workspace = Workspace.PERSONAL,
            persona = AssistantPersona.DEFAULT,
            previewText = "That's enough for a solid one-pan dinner...",
            messages = listOf(
                ChatMessage(role = MessageRole.USER, content = "I have chicken thighs, half an onion, rice, and a lemon. What can I make for dinner?"),
                ChatMessage(role = MessageRole.ASSISTANT, content = "That's enough for a solid one-pan dinner — lemon chicken and rice. Brown the salted thighs skin-side down in an oven-safe pan, set them aside, then soften the onion and toast the rice in the same fat. Add 1½ cups water per cup of rice, squeeze in half the lemon, nestle the chicken back on top, and bake covered at 375°F for 25 minutes.\n\nSqueeze the other lemon half over everything right before serving — that's what makes it taste finished rather than plain.")
            )
        ),
        Conversation(
            title = "Are These Leftovers Still Good?",
            workspace = Workspace.PERSONAL,
            persona = AssistantPersona.CONCISE,
            previewText = "Four days is the outer limit for cooked chicken...",
            messages = listOf(
                ChatMessage(role = MessageRole.USER, content = "How long is cooked chicken safe in the fridge? Mine's been in there since Sunday."),
                ChatMessage(role = MessageRole.ASSISTANT, content = "Four days is the outer limit for cooked chicken in the fridge at 40°F or below.\n\nIf Sunday was more than four days ago, throw it out — smell isn't a reliable test, since the bacteria that cause illness don't change how food looks or smells. To buy more time next round, freeze it within a day or two; it keeps for months at 0°F.\n\nSources\n- [FoodSafety.gov — Cold Food Storage Chart](https://www.foodsafety.gov/food-safety-charts/cold-food-storage-charts)")
            )
        ),
        Conversation(
            title = "The Text I Keep Putting Off",
            workspace = Workspace.CREATIVE,
            persona = AssistantPersona.CREATIVE,
            previewText = "Here's a version that's warm but doesn't over-explain...",
            messages = listOf(
                ChatMessage(role = MessageRole.USER, content = "I need to cancel dinner plans with a friend tonight and I feel bad about it. Help me word the text."),
                ChatMessage(role = MessageRole.ASSISTANT, content = "Here's a version that's warm but doesn't over-explain:\n\n\"Hey — I'm so sorry, I have to bail on tonight. I'm running on empty and I'd be bad company. Can we do next week instead? I'll pick the place.\"\n\nIt apologizes once rather than three times, gives a reason without an essay, and offers the next date immediately so it reads as a reschedule instead of a fade. Want a shorter version?")
            )
        ),
        Conversation(
            title = "Making Sense of a Car Repair Quote",
            workspace = Workspace.RESEARCH,
            persona = AssistantPersona.ANALYTICAL,
            previewText = "Three of those four lines are worth questioning...",
            messages = listOf(
                ChatMessage(role = MessageRole.USER, content = "The shop quoted me \$1,240: brake pads and rotors \$610, brake fluid flush \$150, engine air filter \$95, shop supplies \$85, labor \$300. Is any of that padding?"),
                ChatMessage(role = MessageRole.ASSISTANT, content = "Three of those four lines are worth questioning:\n\n- **Pads and rotors at \$610** — reasonable if that covers all four wheels, high if it's only the front. Ask which axles are included before anything else.\n- **Engine air filter at \$95** — the part itself is usually \$20-30 and takes a few minutes to swap. This is the easiest line to decline and do yourself.\n- **Shop supplies at \$85** — a percentage-based catch-all fee. Many shops will reduce or drop it if you ask.\n- **Brake fluid flush at \$150** — legitimate maintenance, but only if it's actually due. Ask when it was last done.\n\nAsk for the itemized parts numbers and the labor hours. A shop that won't put those in writing is telling you something.")
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

    /** Port of compactRecoverySystemPrompt in iphone/AetherChat/Models.swift. */
    fun compactRecoverySystemPrompt(basePrompt: String, latestUserText: String): String {
        val parts = mutableListOf<String>()
        if (basePrompt.isNotBlank()) parts.add(basePrompt.trim())
        parts.add(
            """
            Recovery mode: the previous draft repeated itself. Answer only the latest user message.
            Use 2-5 concise sentences unless the user explicitly asked for code, a list, or creative writing.
            Do not repeat earlier assistant wording. Do not ask for details already provided.
            Latest user message: "${latestUserText.trim()}"
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
