import Foundation
import UIKit
import UserNotifications

enum AetherNotifications {
    static func requestAuthorization() async {
        do {
            try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound])
        } catch {
            // Notifications are optional; inference still works if authorization is denied.
        }
    }

    static func notifyReplyReady(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: "aether.reply.\(UUID().uuidString)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }
}

enum AetherBackgroundTask {
    @MainActor
    static func begin(name: String, expirationHandler: @escaping @Sendable () -> Void = {}) -> UIBackgroundTaskIdentifier {
        UIApplication.shared.beginBackgroundTask(withName: name, expirationHandler: expirationHandler)
    }

    @MainActor
    static func end(_ identifier: UIBackgroundTaskIdentifier) {
        guard identifier != .invalid else { return }
        UIApplication.shared.endBackgroundTask(identifier)
    }
}

struct Conversation: Identifiable, Codable, Sendable {
    let id: UUID
    var title: String
    var workspace: Workspace
    var persona: AssistantPersona
    var isPinned: Bool
    var previewText: String
    var updatedAt: Date
    var messages: [ChatMessage]
    var memorySummary: String

    init(id: UUID = UUID(), title: String, workspace: Workspace, persona: AssistantPersona = .default,
         isPinned: Bool = false, previewText: String = "", updatedAt: Date = Date(), messages: [ChatMessage] = [], memorySummary: String = "") {
        self.id = id; self.title = title; self.workspace = workspace; self.persona = persona
        self.isPinned = isPinned; self.previewText = previewText; self.updatedAt = updatedAt; self.messages = messages
        self.memorySummary = memorySummary
    }
}

struct ChatMessage: Identifiable, Codable, Sendable {
    let id: UUID
    let role: MessageRole
    var content: String
    var attachments: [ChatAttachment]
    let timestamp: Date

    init(id: UUID = UUID(), role: MessageRole, content: String, attachments: [ChatAttachment] = [], timestamp: Date = Date()) {
        self.id = id; self.role = role; self.content = content; self.attachments = attachments; self.timestamp = timestamp
    }
}

struct ChatAttachment: Identifiable, Codable, Sendable {
    let id: UUID
    let data: Data
    let mimeType: String
    let filename: String
    let extractedText: String?

    init(id: UUID = UUID(), data: Data, mimeType: String = "image/jpeg", filename: String = "image.jpg", extractedText: String? = nil) {
        self.id = id
        self.data = data
        self.mimeType = mimeType
        self.filename = filename
        self.extractedText = extractedText
    }

    var isImage: Bool {
        mimeType.hasPrefix("image/")
    }

    var isTextFile: Bool {
        extractedText?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
    }

    var displayName: String {
        filename.isEmpty ? "Attachment" : filename
    }
}

enum MessageRole: String, Codable, Sendable {
    case user, assistant

    var apiRole: String {
        switch self {
        case .user: return "user"
        case .assistant: return "assistant"
        }
    }
}

@MainActor
class AppState: ObservableObject {
    @Published var conversations: [Conversation] = []
    @Published var isDarkTheme: Bool = UserDefaults.standard.bool(forKey: "isDarkTheme") {
        didSet { UserDefaults.standard.set(isDarkTheme, forKey: "isDarkTheme") }
    }
    @Published var apiEndpoint: String = UserDefaults.standard.string(forKey: "apiEndpoint") ?? "http://127.0.0.1:8787" {
        didSet { UserDefaults.standard.set(apiEndpoint, forKey: "apiEndpoint") }
    }
    @Published var selectedModel: String = {
        let stored = UserDefaults.standard.string(forKey: "selectedModel")
        guard let stored, stored != AetherModelCatalog.legacyAetherV1DisplayName else {
            return AetherModelCatalog.aetherV1DisplayName
        }
        return stored
    }() {
        didSet {
            UserDefaults.standard.set(selectedModel, forKey: "selectedModel")
            if selectedModel == AetherModelCatalog.aetherV1DisplayName {
                inferenceProvider = .onDevice
            }
        }
    }
    @Published var inferenceProvider: InferenceProvider = InferenceProvider(rawValue: UserDefaults.standard.string(forKey: "inferenceProvider") ?? "") ?? .onDevice {
        didSet { UserDefaults.standard.set(inferenceProvider.rawValue, forKey: "inferenceProvider") }
    }
    @Published var modelLoadingMessage: String?
    @Published var generationStatusMessage: String?
    @Published var appIsActive = true
    @Published var defaultWorkspace: Workspace = .personal
    @Published var messageFontScale: Double = UserDefaults.standard.double(forKey: "messageFontScale") == 0 ? 1.0 : UserDefaults.standard.double(forKey: "messageFontScale") {
        didSet { UserDefaults.standard.set(messageFontScale, forKey: "messageFontScale") }
    }
    @Published var customSystemPrompt: String = UserDefaults.standard.string(forKey: "customSystemPrompt") ?? "" {
        didSet { UserDefaults.standard.set(customSystemPrompt, forKey: "customSystemPrompt") }
    }
    @Published var customPersonas: [AssistantPersona] = AppState.loadCustomPersonas() {
        didSet { AppState.saveCustomPersonas(customPersonas) }
    }
    @Published var customWorkspaces: [Workspace] = AppState.loadCustomWorkspaces() {
        didSet { AppState.saveCustomWorkspaces(customWorkspaces) }
    }
    private let memoryStore: AetherMemoryStore
    private let backend = AetherBackendClient()
    private let onDevice = AetherOnDeviceClient()
    private let webSearch = AetherWebSearchService()
    private let locationService = AetherLocationService()
    private let networkMonitor = AetherNetworkMonitor()

    init(memoryStore: AetherMemoryStore = .shared) {
        self.memoryStore = memoryStore
        let savedConversations = memoryStore.loadConversations()
        self.conversations = savedConversations.isEmpty
            ? sampleConversations
            : savedConversations.map(Self.canonicalizeBuiltInWorkspace).map(AetherTitleGenerator.repairIfNeeded)
        removeLegacySeedConversations()
        persistAllConversations()
    }

    var availablePersonas: [AssistantPersona] {
        [defaultPersona] + AssistantPersona.all.filter { $0.id != AssistantPersona.default.id } + customPersonas
    }

    var defaultPersona: AssistantPersona {
        .default
    }

    var availableWorkspaces: [Workspace] {
        Workspace.builtIns + customWorkspaces
    }

    private static func canonicalizeBuiltInWorkspace(_ conversation: Conversation) -> Conversation {
        guard let canonical = Workspace.builtIns.first(where: { $0.id == conversation.workspace.id }) else {
            return conversation
        }
        var updated = conversation
        updated.workspace = canonical
        return updated
    }

    func togglePin(_ id: UUID) {
        guard let idx = conversations.firstIndex(where: { $0.id == id }) else { return }
        conversations[idx].isPinned.toggle()
        persistConversation(at: idx)
    }

    func delete(_ id: UUID) {
        conversations.removeAll { $0.id == id }
        memoryStore.deleteConversation(id: id)
    }

    func createConversation(title: String, workspace: Workspace, persona: AssistantPersona) -> UUID {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let conversation = Conversation(title: trimmed.isEmpty ? "Untitled" : trimmed, workspace: workspace, persona: persona)
        conversations.insert(conversation, at: 0)
        memoryStore.saveConversation(conversation)
        return conversation.id
    }

    func createCustomPersona(name: String, description: String, instructions: String) -> AssistantPersona {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedDescription = description.trimmingCharacters(in: .whitespacesAndNewlines)
        let persona = AssistantPersona(
            id: "custom-\(UUID().uuidString)",
            name: trimmedName.isEmpty ? "Custom Assistant" : trimmedName,
            description: trimmedDescription.isEmpty ? "Custom assistant" : trimmedDescription,
            instructions: instructions.trimmingCharacters(in: .whitespacesAndNewlines)
        )
        customPersonas.append(persona)
        return persona
    }

    func updateCustomPersona(id: String, name: String, description: String, instructions: String) -> AssistantPersona? {
        guard let index = customPersonas.firstIndex(where: { $0.id == id }) else { return nil }
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedDescription = description.trimmingCharacters(in: .whitespacesAndNewlines)
        let updated = AssistantPersona(
            id: id,
            name: trimmedName.isEmpty ? "Custom Assistant" : trimmedName,
            description: trimmedDescription.isEmpty ? "Custom assistant" : trimmedDescription,
            instructions: instructions.trimmingCharacters(in: .whitespacesAndNewlines)
        )
        customPersonas[index] = updated
        for idx in conversations.indices where conversations[idx].persona.id == id {
            conversations[idx].persona = updated
            persistConversation(at: idx)
        }
        return updated
    }

    func deleteCustomPersona(_ persona: AssistantPersona) {
        guard persona.id.hasPrefix("custom-") else { return }
        customPersonas.removeAll { $0.id == persona.id }
        for idx in conversations.indices where conversations[idx].persona.id == persona.id {
            conversations[idx].persona = .default
            persistConversation(at: idx)
        }
    }

    func createWorkspace(name: String) -> Workspace {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let workspace = Workspace.custom(name: trimmedName.isEmpty ? "New Workspace" : trimmedName)
        customWorkspaces.append(workspace)
        return workspace
    }

    func deleteWorkspace(_ workspace: Workspace) {
        guard !workspace.isBuiltIn else { return }
        customWorkspaces.removeAll { $0.id == workspace.id }
        if defaultWorkspace == workspace {
            defaultWorkspace = .personal
        }
        for idx in conversations.indices where conversations[idx].workspace == workspace {
            conversations[idx].workspace = .personal
            persistConversation(at: idx)
        }
    }

    func renameConversation(_ id: UUID, title: String) {
        guard let idx = conversations.firstIndex(where: { $0.id == id }) else { return }
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        conversations[idx].title = trimmed.isEmpty ? "Untitled" : trimmed
        persistConversation(at: idx)
    }

    func sendMessage(in id: UUID, text: String, attachments: [ChatAttachment] = []) async {
        guard let idx = conversations.firstIndex(where: { $0.id == id }) else { return }
        Task { await AetherNotifications.requestAuthorization() }
        let priorMessages = conversations[idx].messages
        let userMsg = ChatMessage(role: .user, content: text, attachments: attachments)
        conversations[idx].messages.append(userMsg)
        conversations[idx].previewText = text.isEmpty ? attachmentPreview(for: attachments) : text
        if conversations[idx].title == "Untitled" {
            conversations[idx].title = AetherTitleGenerator.title(for: text, attachments: attachments)
        }
        conversations[idx].updatedAt = Date()
        refreshMemorySummary(at: idx)
        persistConversation(at: idx)

        let persona = conversations[idx].persona
        let messageSnapshot = conversations[idx].messages
        let memoryContext = memoryContext(for: id, latestUserText: text, messageSnapshot: messageSnapshot)
        await generateAndAppendReply(to: id, persona: persona, messageSnapshot: messageSnapshot, priorMessages: priorMessages, latestUserText: text, memoryContext: memoryContext)
    }

    func editUserMessage(in id: UUID, messageID: UUID, text: String) async {
        guard let conversationIndex = conversations.firstIndex(where: { $0.id == id }) else { return }
        guard let messageIndex = conversations[conversationIndex].messages.firstIndex(where: { $0.id == messageID }) else { return }
        guard conversations[conversationIndex].messages[messageIndex].role == .user else { return }

        let updatedText = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let priorMessages = Array(conversations[conversationIndex].messages.prefix(upTo: messageIndex))
        conversations[conversationIndex].messages[messageIndex].content = updatedText
        if conversations[conversationIndex].messages[messageIndex].attachments.isEmpty {
            conversations[conversationIndex].previewText = updatedText
        } else {
            conversations[conversationIndex].previewText = updatedText.isEmpty
                ? attachmentPreview(for: conversations[conversationIndex].messages[messageIndex].attachments)
                : updatedText
        }
        if messageIndex + 1 < conversations[conversationIndex].messages.count {
            conversations[conversationIndex].messages.removeSubrange((messageIndex + 1)...)
        }
        conversations[conversationIndex].updatedAt = Date()
        refreshMemorySummary(at: conversationIndex)
        persistConversation(at: conversationIndex)

        let persona = conversations[conversationIndex].persona
        let snapshot = conversations[conversationIndex].messages
        let memoryContext = memoryContext(for: id, latestUserText: updatedText, messageSnapshot: snapshot)
        await generateAndAppendReply(
            to: id,
            persona: persona,
            messageSnapshot: snapshot,
            priorMessages: priorMessages,
            latestUserText: updatedText,
            memoryContext: memoryContext
        )
    }

    func regenerateLastResponse(in id: UUID) async {
        guard let idx = conversations.firstIndex(where: { $0.id == id }) else { return }
        guard let lastAssistantIndex = conversations[idx].messages.lastIndex(where: { $0.role == .assistant }) else { return }
        let promptMessages = Array(conversations[idx].messages.prefix(upTo: lastAssistantIndex))
        guard let lastUserIndex = promptMessages.lastIndex(where: { $0.role == .user }) else { return }
        let latestUser = promptMessages[lastUserIndex]
        conversations[idx].messages.removeSubrange(lastAssistantIndex...)
        conversations[idx].previewText = latestUser.content.isEmpty ? attachmentPreview(for: latestUser.attachments) : latestUser.content
        conversations[idx].updatedAt = Date()
        refreshMemorySummary(at: idx)
        persistConversation(at: idx)

        let persona = conversations[idx].persona
        let priorMessages = Array(promptMessages.prefix(upTo: lastUserIndex))
        let memoryContext = memoryContext(for: id, latestUserText: latestUser.content, messageSnapshot: promptMessages)
        await generateAndAppendReply(
            to: id,
            persona: persona,
            messageSnapshot: promptMessages,
            priorMessages: priorMessages,
            latestUserText: latestUser.content,
            memoryContext: memoryContext
        )
    }

    private func generateAndAppendReply(
        to id: UUID,
        persona: AssistantPersona,
        messageSnapshot: [ChatMessage],
        priorMessages: [ChatMessage],
        latestUserText: String,
        memoryContext: String?
    ) async {
        let task = AetherBackgroundTask.begin(name: "Aether V1 inference") { [weak self] in
            Task { @MainActor in
                self?.modelLoadingMessage = nil
                self?.generationStatusMessage = nil
            }
        }
        defer { AetherBackgroundTask.end(task) }

        do {
            generationStatusMessage = messageSnapshot.contains(where: { !$0.attachments.isEmpty })
                ? "Reading attachments and the conversation"
                : "Reading the conversation"
            let webQuery = AetherWebSearchIntent.query(from: latestUserText, previousMessages: priorMessages)
            var webSearchContext: String?
            var webSourcesMarkdown: String?
            if let webQuery {
                if networkMonitor.hasReceivedStatus && !networkMonitor.isConnected {
                    webSearchContext = AetherWebSearchIntent.offlineContext(for: webQuery)
                    webSourcesMarkdown = nil
                } else {
                    generationStatusMessage = "Searching the web"
                    do {
                        let localizedQuery = await locationService.localizeSearchQuery(webQuery, originalUserText: latestUserText)
                        let searchResult = try await webSearch.search(query: localizedQuery)
                        webSearchContext = searchResult.context
                        webSourcesMarkdown = searchResult.sourcesMarkdown
                    } catch {
                        webSearchContext = AetherWebSearchIntent.offlineContext(for: webQuery)
                        webSourcesMarkdown = nil
                    }
                }
            }
            generationStatusMessage = "Composing a response"
            var response = try await generateReply(
                persona: persona,
                messages: messageSnapshot,
                webSearchContext: webSearchContext,
                memoryContext: memoryContext,
                customSystemPrompt: runtimeSystemPrompt
            )
            if isLoopingResponse(response, in: messageSnapshot) {
                generationStatusMessage = "Redirecting repeated response"
                response = try await generateReply(
                    persona: persona,
                    messages: messageSnapshot,
                    webSearchContext: webSearchContext,
                    memoryContext: memoryContext,
                    customSystemPrompt: redirectedSystemPrompt(afterRepeatedResponse: response, latestUserText: latestUserText)
                )
                if isLoopingResponse(response, in: messageSnapshot) {
                    generationStatusMessage = "Trying a shorter recovery"
                    response = try await generateReply(
                        persona: persona,
                        messages: messageSnapshot,
                        webSearchContext: webSearchContext,
                        memoryContext: memoryContext,
                        customSystemPrompt: compactRecoverySystemPrompt(latestUserText: latestUserText)
                    )
                }
                if isLoopingResponse(response, in: messageSnapshot) {
                    response = fallbackRedirectResponse(latestUserText: latestUserText)
                }
            }
            response = responseWithSources(response, sourcesMarkdown: webSourcesMarkdown)
            modelLoadingMessage = nil
            generationStatusMessage = nil
            appendAssistantMessage(to: id, content: response)
            notifyIfNeeded(conversationTitle: conversations.first(where: { $0.id == id })?.title ?? "Canopy", response: response)
        } catch is CancellationError {
            modelLoadingMessage = nil
            generationStatusMessage = nil
            appendAssistantMessage(to: id, content: "Response stopped.")
        } catch {
            modelLoadingMessage = nil
            generationStatusMessage = nil
            let errorMessage = localBackgroundInterruptionMessage(for: error)
                ?? "Inference error: \(inferenceErrorDescription(error))"
            appendAssistantMessage(to: id, content: errorMessage)
            notifyIfNeeded(conversationTitle: conversations.first(where: { $0.id == id })?.title ?? "Canopy", response: errorMessage)
        }
    }

    private func generateReply(
        persona: AssistantPersona,
        messages: [ChatMessage],
        webSearchContext: String? = nil,
        memoryContext: String? = nil,
        customSystemPrompt: String = ""
    ) async throws -> String {
        if selectedModel == AetherModelCatalog.aetherV1DisplayName {
            return try await onDevice.send(
                persona: persona,
                messages: messages,
                webSearchContext: webSearchContext,
                memoryContext: memoryContext,
                customSystemPrompt: customSystemPrompt,
                status: { [weak self] message in
                    await MainActor.run {
                        if let message {
                            self?.modelLoadingMessage = message
                        } else {
                            self?.modelLoadingMessage = nil
                            self?.generationStatusMessage = "Composing a response"
                        }
                    }
                }
            )
        }

        if inferenceProvider == .onDevice {
            throw AetherOnDeviceError.unsupportedLocalModel(selectedModel)
        }

        return try await backend.send(
            endpoint: apiEndpoint,
            model: selectedModel,
            persona: persona,
            messages: messages,
            webSearchContext: webSearchContext,
            memoryContext: memoryContext,
            customSystemPrompt: customSystemPrompt
        )
    }

    private func responseWithSources(_ response: String, sourcesMarkdown: String?) -> String {
        let trimmedResponse = response.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let sourcesMarkdown = sourcesMarkdown?.trimmingCharacters(in: .whitespacesAndNewlines),
              !sourcesMarkdown.isEmpty else {
            return trimmedResponse
        }

        let lowercased = trimmedResponse.lowercased()
        if lowercased.contains("sources") && lowercased.contains("](http") {
            return trimmedResponse
        }

        return "\(trimmedResponse)\n\n\(sourcesMarkdown)"
    }

    private var runtimeSystemPrompt: String {
        var parts = [String]()
        let preferences = customSystemPrompt.trimmingCharacters(in: .whitespacesAndNewlines)
        if !preferences.isEmpty {
            parts.append(preferences)
        }
        return parts.joined(separator: "\n")
    }

    private func memoryContext(for conversationID: UUID, latestUserText: String, messageSnapshot: [ChatMessage]) -> String? {
        let recentIDs = Set(messageSnapshot.suffix(8).map(\.id))
        let summary = conversations.first(where: { $0.id == conversationID })?.memorySummary ?? ""
        let hits = memoryStore.relevantMessages(
            conversationID: conversationID,
            query: latestUserText,
            excluding: recentIDs,
            limit: 6
        )
        return AetherMemoryPlanner.memoryContext(summary: summary, hits: hits)
    }

    private func refreshMemorySummary(at index: Int) {
        conversations[index].memorySummary = AetherMemoryPlanner.summary(
            for: conversations[index].messages,
            existingSummary: conversations[index].memorySummary
        )
    }

    private func persistConversation(at index: Int) {
        guard conversations.indices.contains(index) else { return }
        memoryStore.saveConversation(conversations[index])
    }

    private func persistAllConversations() {
        memoryStore.saveAll(conversations)
    }

    private func removeLegacySeedConversations() {
        let legacySeedTitles: Set<String> = [
            "Morning Reflection",
            "Q3 Strategy Deck",
            "Novel Outline",
            "ML Paper Notes"
        ]
        let removedIDs = conversations
            .filter { legacySeedTitles.contains($0.title) }
            .map(\.id)
        guard !removedIDs.isEmpty else { return }

        conversations.removeAll { legacySeedTitles.contains($0.title) }
        for id in removedIDs {
            memoryStore.deleteConversation(id: id)
        }
    }

    private func redirectedSystemPrompt(afterRepeatedResponse response: String, latestUserText: String) -> String {
        var parts = [String]()
        let basePrompt = runtimeSystemPrompt.trimmingCharacters(in: .whitespacesAndNewlines)
        if !basePrompt.isEmpty {
            parts.append(basePrompt)
        }
        parts.append("""
        Loop prevention: your previous draft repeated an earlier assistant reply instead of answering the latest user message.
        Do not repeat, summarize, or ask again for details already provided.
        Answer this latest user message directly: "\(latestUserText.trimmingCharacters(in: .whitespacesAndNewlines))"
        If the user asks for a poem, story, rhyme, rewrite, or other creative output, produce the requested output now.
        Avoid starting with the same wording as this rejected draft:
        \(String(response.prefix(600)))
        """)
        return parts.joined(separator: "\n\n")
    }

    private func compactRecoverySystemPrompt(latestUserText: String) -> String {
        var parts = [String]()
        let basePrompt = runtimeSystemPrompt.trimmingCharacters(in: .whitespacesAndNewlines)
        if !basePrompt.isEmpty {
            parts.append(basePrompt)
        }
        parts.append("""
        Recovery mode: the previous draft repeated itself. Answer only the latest user message.
        Use 2-5 concise sentences unless the user explicitly asked for code, a list, or creative writing.
        Do not repeat earlier assistant wording. Do not ask for details already provided.
        Latest user message: "\(latestUserText.trimmingCharacters(in: .whitespacesAndNewlines))"
        """)
        return parts.joined(separator: "\n\n")
    }

    private func isLoopingResponse(_ response: String, in messages: [ChatMessage]) -> Bool {
        isRepeatedAssistantResponse(response, in: messages) || hasInternalRepetition(response)
    }

    private func isRepeatedAssistantResponse(_ response: String, in messages: [ChatMessage]) -> Bool {
        let candidate = normalizedForLoopDetection(response)
        guard candidate.count > 80 else { return false }
        return messages
            .reversed()
            .filter { $0.role == .assistant }
            .prefix(4)
            .contains { previous in
                let normalizedPrevious = normalizedForLoopDetection(previous.content)
                guard normalizedPrevious.count > 80 else { return false }
                return candidate == normalizedPrevious || similarity(candidate, normalizedPrevious) >= 0.92
            }
    }

    private func hasInternalRepetition(_ response: String) -> Bool {
        let normalized = normalizedForLoopDetection(response)
        let words = normalized.split(separator: " ").map(String.init)
        guard words.count >= 32 else { return false }

        var windowCounts: [String: Int] = [:]
        let windowSize = 14
        for start in 0...(words.count - windowSize) {
            let window = words[start..<(start + windowSize)].joined(separator: " ")
            windowCounts[window, default: 0] += 1
            if windowCounts[window, default: 0] >= 2 {
                return true
            }
        }

        let sentences = response
            .components(separatedBy: CharacterSet(charactersIn: ".!?\n"))
            .map(normalizedForLoopDetection)
            .filter { $0.count >= 45 }
        var sentenceCounts: [String: Int] = [:]
        for sentence in sentences {
            sentenceCounts[sentence, default: 0] += 1
            if sentenceCounts[sentence, default: 0] >= 2 {
                return true
            }
        }

        let chunks = response
            .components(separatedBy: CharacterSet.newlines)
            .map(normalizedForLoopDetection)
            .filter { $0.count >= 60 }
        guard chunks.count >= 2 else { return false }
        var chunkCounts: [String: Int] = [:]
        for chunk in chunks {
            chunkCounts[chunk, default: 0] += 1
            if chunkCounts[chunk, default: 0] >= 2 {
                return true
            }
        }
        return false
    }

    private func normalizedForLoopDetection(_ text: String) -> String {
        text
            .lowercased()
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"[^a-z0-9 ]"#, with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func similarity(_ lhs: String, _ rhs: String) -> Double {
        let lhsWords = Set(lhs.split(separator: " ").map(String.init))
        let rhsWords = Set(rhs.split(separator: " ").map(String.init))
        guard !lhsWords.isEmpty, !rhsWords.isEmpty else { return 0 }
        let intersection = lhsWords.intersection(rhsWords).count
        let union = lhsWords.union(rhsWords).count
        return Double(intersection) / Double(union)
    }

    private func fallbackRedirectResponse(latestUserText: String) -> String {
        let trimmed = latestUserText.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return "I don't have enough reliable information to answer that confidently. Please add a little more context and I will try again."
        }
        return "I don't have enough reliable information to answer that confidently. Please add more context about \"\(trimmed)\" or rephrase it, and I will try again."
    }

    private static func loadCustomPersonas() -> [AssistantPersona] {
        guard let data = UserDefaults.standard.data(forKey: "customPersonas") else { return [] }
        return (try? JSONDecoder().decode([AssistantPersona].self, from: data)) ?? []
    }

    private static func saveCustomPersonas(_ personas: [AssistantPersona]) {
        guard let data = try? JSONEncoder().encode(personas) else { return }
        UserDefaults.standard.set(data, forKey: "customPersonas")
    }

    private static func loadCustomWorkspaces() -> [Workspace] {
        guard let data = UserDefaults.standard.data(forKey: "customWorkspaces") else { return [] }
        return (try? JSONDecoder().decode([Workspace].self, from: data)) ?? []
    }

    private static func saveCustomWorkspaces(_ workspaces: [Workspace]) {
        guard let data = try? JSONEncoder().encode(workspaces) else { return }
        UserDefaults.standard.set(data, forKey: "customWorkspaces")
    }

    private func appendAssistantMessage(to id: UUID, content: String) {
        guard let idx = conversations.firstIndex(where: { $0.id == id }) else { return }
        let reply = ChatMessage(role: .assistant, content: content)
        conversations[idx].messages.append(reply)
        conversations[idx].previewText = reply.content
        conversations[idx].updatedAt = Date()
        refreshMemorySummary(at: idx)
        persistConversation(at: idx)
    }

    private func inferenceErrorDescription(_ error: Error) -> String {
        if let localized = error as? LocalizedError, let description = localized.errorDescription {
            return description
        }
        let nsError = error as NSError
        if !nsError.localizedDescription.isEmpty {
            return nsError.localizedDescription
        }
        return String(describing: error)
    }

    private func localBackgroundInterruptionMessage(for error: Error) -> String? {
        guard !appIsActive, let localError = error as? AetherOnDeviceError else { return nil }
        switch localError {
        case .decodeFailed, .emptyResponse, .multimodalDecodeFailed:
            return "CanopyChat was interrupted while running local inference in the background. The local model has been reset; please resend the message."
        default:
            return nil
        }
    }

    private func notifyIfNeeded(conversationTitle: String, response: String) {
        guard !appIsActive else { return }
        let preview = response
            .replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        AetherNotifications.notifyReplyReady(
            title: "\(conversationTitle) replied",
            body: String(preview.prefix(160))
        )
    }

    private func attachmentPreview(for attachments: [ChatAttachment]) -> String {
        let imageCount = attachments.filter(\.isImage).count
        let fileCount = attachments.count - imageCount
        switch (imageCount, fileCount) {
        case (0, 0):
            return ""
        case (_, 0):
            return "\(imageCount) image attachment\(imageCount == 1 ? "" : "s")"
        case (0, _):
            return "\(fileCount) file attachment\(fileCount == 1 ? "" : "s")"
        default:
            return "\(imageCount) image\(imageCount == 1 ? "" : "s"), \(fileCount) file\(fileCount == 1 ? "" : "s")"
        }
    }
}

enum AetherTitleGenerator {
    static func title(for text: String, attachments: [ChatAttachment]) -> String {
        let cleaned = cleanedSource(text)
        let source: String
        if cleaned.isEmpty, let first = attachments.first {
            source = first.isImage ? "Image Analysis" : first.displayName
        } else {
            source = cleaned
        }

        let stopwords: Set<String> = [
            "a", "an", "and", "are", "at", "can", "could", "do", "does", "for", "good",
            "how", "i", "in", "is", "it", "me", "my", "near", "of", "on", "or", "place",
            "please", "the", "to", "want", "what", "whats", "where", "with", "you"
        ]
        let words = source
            .components(separatedBy: CharacterSet.alphanumerics.inverted)
            .filter { !$0.isEmpty }
            .filter { $0.count > 1 }
            .filter { !stopwords.contains($0.lowercased()) }
            .prefix(5)

        let title = words.map { word in
            word.prefix(1).uppercased() + word.dropFirst()
        }.joined(separator: " ")

        return title.isEmpty ? "Untitled" : String(title.prefix(42))
    }

    static func repairIfNeeded(_ conversation: Conversation) -> Conversation {
        guard shouldRepair(conversation.title),
              let firstUserMessage = conversation.messages.first(where: { $0.role == .user }) else {
            return conversation
        }

        var repaired = conversation
        repaired.title = title(for: firstUserMessage.content, attachments: firstUserMessage.attachments)
        return repaired
    }

    private static func shouldRepair(_ title: String) -> Bool {
        let words = title.split(separator: " ").map(String.init)
        guard let first = words.first else { return false }
        return first.count == 1 || first.lowercased() == "s" || title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private static func cleanedSource(_ text: String) -> String {
        var cleaned = text
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: #"(?i)\bwhat['’]s\b"#, with: "what is", options: .regularExpression)
            .replacingOccurrences(of: #"(?i)\bwhere['’]s\b"#, with: "where is", options: .regularExpression)
            .replacingOccurrences(of: #"(?i)\bwho['’]s\b"#, with: "who is", options: .regularExpression)
            .replacingOccurrences(of: #"(?i)\bhow['’]s\b"#, with: "how is", options: .regularExpression)
            .replacingOccurrences(of: #"(?i)\bfind\s+(me\s+)?\b"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"(?i)\btell\s+me\s+about\b"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"(?i)\bnear\s+me\b"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"(?i)\baround\s+me\b"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"(?i)\bmy\s+area\b"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)

        if cleaned.lowercased().hasPrefix("what is a ") {
            cleaned.removeFirst("what is a ".count)
        } else if cleaned.lowercased().hasPrefix("what is an ") {
            cleaned.removeFirst("what is an ".count)
        } else if cleaned.lowercased().hasPrefix("what is ") {
            cleaned.removeFirst("what is ".count)
        }

        return cleaned.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

let sampleConversations: [Conversation] = [
    Conversation(title: "Two-Week Launch Plan", workspace: .work, persona: .analytical,
                 isPinned: true, previewText: "Working backward from release day, here's your two-week plan...", updatedAt: Date().addingTimeInterval(-540),
                 messages: [
                     ChatMessage(role: .user, content: "Help me plan a small product launch in two weeks.", timestamp: Date().addingTimeInterval(-660)),
                     ChatMessage(role: .assistant, content: """
                     Working backward from release day, here's your two-week plan:

                     **Week 1 — decide and prepare**
                     - Lock pricing and positioning by Wednesday
                     - Draft the announcement and a one-page FAQ
                     - Line up 3 early customers for launch-day quotes

                     **Week 2 — polish and ship**
                     - Finish support docs and a rollback plan
                     - Schedule the announcement for Tuesday morning
                     - Hold Friday as a buffer for anything that slips

                     Want me to expand any of these into daily tasks?
                     """, timestamp: Date().addingTimeInterval(-600)),
                     ChatMessage(role: .user, content: "Yes, break down week 1 for me.", timestamp: Date().addingTimeInterval(-570)),
                     ChatMessage(role: .assistant, content: """
                     Here's week 1, day by day:

                     - **Monday** — write down the one sentence you want customers to repeat about the product
                     - **Tuesday** — pressure-test pricing against your two closest competitors
                     - **Wednesday** — lock both; no more changes after today
                     - **Thursday** — draft the announcement while decisions are fresh
                     - **Friday** — send the draft to your three early customers and ask for quotes

                     The Wednesday lock is the one that matters — everything downstream depends on it.
                     """, timestamp: Date().addingTimeInterval(-540))
                 ]),
    Conversation(title: "Coffee Before the Market", workspace: .personal, persona: .default,
                 previewText: "Two good options within a short walk of the market...", updatedAt: Date().addingTimeInterval(-3600),
                 messages: [
                     ChatMessage(role: .user, content: "Find a quiet coffee shop near the farmers market that opens early.", timestamp: Date().addingTimeInterval(-3660)),
                     ChatMessage(role: .assistant, content: """
                     Two good options within a short walk of the market:

                     - **Grounded Coffee Co.** — opens 6:30am, known for quiet back room and pour-overs
                     - **The Morning Bloom** — opens 7am, more seats, good pastries, gets busier after 9

                     If you want to talk before the market opens, Grounded is the safer bet.

                     Sources
                     - [Grounded Coffee Co. — hours and menu](https://example.com/grounded-coffee)
                     - [The Morning Bloom — visitor reviews](https://example.com/morning-bloom)
                     """, timestamp: Date().addingTimeInterval(-3600))
                 ]),
    Conversation(title: "Eco Brand Taglines", workspace: .creative, persona: .creative,
                 previewText: "Five taglines, from earnest to playful...", updatedAt: Date().addingTimeInterval(-9000),
                 messages: [
                     ChatMessage(role: .user, content: "Write taglines for a refill shop that cuts single-use plastic.", timestamp: Date().addingTimeInterval(-9060)),
                     ChatMessage(role: .assistant, content: """
                     Five taglines, from earnest to playful:

                     1. **Refill more. Waste less.**
                     2. **The last bottle you'll ever buy.**
                     3. **Good habits, by the ounce.**
                     4. **Bring the bottle. Skip the landfill.**
                     5. **Your soap called. It wants a refill.**

                     Number 2 is the boldest claim — great on a storefront, but pair it with the refill count so it feels earned.
                     """, timestamp: Date().addingTimeInterval(-9000))
                 ]),
    Conversation(title: "Customer Follow-Up Email", workspace: .work, persona: .default,
                 previewText: "Here's a short, friendly draft you can adjust...", updatedAt: Date().addingTimeInterval(-26_000),
                 messages: [
                     ChatMessage(role: .user, content: "Draft a friendly follow-up email to a customer who went quiet after a demo.", timestamp: Date().addingTimeInterval(-26_060)),
                     ChatMessage(role: .assistant, content: """
                     Here's a short, friendly draft you can adjust:

                     **Subject:** Following up on the demo

                     Hi [Name],

                     Thanks again for taking the time to see the product last week. I know things get busy, so no pressure — I just wanted to check whether any questions came up on your side.

                     Happy to share pricing details or set up a quick call with your team whenever it's useful.

                     Best,
                     [Your name]

                     The "no pressure" line does the real work — it makes replying feel easy instead of overdue.
                     """, timestamp: Date().addingTimeInterval(-26_000))
                 ]),
    Conversation(title: "Reading a Nutrition Label", workspace: .research, persona: .analytical,
                 previewText: "From that label, three things stand out...", updatedAt: Date().addingTimeInterval(-90_000),
                 messages: [
                     ChatMessage(role: .user, content: "This granola says: serving 1/4 cup, sugar 11g, ingredients: whole oats, cane sugar, honey, brown rice syrup, almonds. Is it actually healthy?", timestamp: Date().addingTimeInterval(-90_060)),
                     ChatMessage(role: .assistant, content: """
                     From that label, three things stand out:

                     - **Serving size is 1/4 cup** — most people pour 3-4x that, so the calories on the label roughly triple in practice
                     - **Sugar is 11g per serving**, and two of the first four ingredients are sweeteners
                     - **The fiber and whole oats are real** — that part of the front-label claim holds up

                     Verdict: closer to a dessert topping than a breakfast base. Use it as a yogurt topper rather than a bowl on its own.
                     """, timestamp: Date().addingTimeInterval(-90_000))
                 ])
]
