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

enum AetherResponseNormalizer {
    static func displayText(_ response: String) -> String {
        let normalized = response
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
        let lines = normalized.components(separatedBy: "\n")
        var inCodeFence = false
        var output: [String] = []

        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("```") {
                inCodeFence.toggle()
                output.append(line)
            } else if inCodeFence {
                output.append(line)
            } else {
                output.append(normalizeMath(in: line))
            }
        }

        return output.joined(separator: "\n")
    }

    private static func normalizeMath(in source: String) -> String {
        var text = source
            .replacingOccurrences(of: "$$", with: "")
            .replacingOccurrences(of: "\\[", with: "")
            .replacingOccurrences(of: "\\]", with: "")
            .replacingOccurrences(of: "\\(", with: "")
            .replacingOccurrences(of: "\\)", with: "")

        text = replaceTwoArgumentCommand(in: text, command: "frac") { numerator, denominator in
            "(\(normalizeMath(in: numerator))) / (\(normalizeMath(in: denominator)))"
        }
        text = replaceOneArgumentCommand(in: text, command: "sqrt") { argument in
            "√(\(normalizeMath(in: argument)))"
        }
        text = replaceOneArgumentCommand(in: text, command: "text") { argument in
            normalizeMath(in: argument)
        }
        return normalizeFormula(text)
    }

    private static func normalizeFormula(_ source: String) -> String {
        source
            .replacingOccurrences(of: "\\pm", with: "±")
            .replacingOccurrences(of: "\\mp", with: "∓")
            .replacingOccurrences(of: "\\times", with: "×")
            .replacingOccurrences(of: "\\cdot", with: "·")
            .replacingOccurrences(of: "\\leq", with: "≤")
            .replacingOccurrences(of: "\\geq", with: "≥")
            .replacingOccurrences(of: "\\neq", with: "≠")
            .replacingOccurrences(of: "\\left", with: "")
            .replacingOccurrences(of: "\\right", with: "")
            .replacingOccurrences(of: "\\,", with: " ")
            .replacingOccurrences(of: "\\!", with: "")
            .replacingOccurrences(of: "\\%", with: "%")
            .replacingOccurrences(of: "{", with: "(")
            .replacingOccurrences(of: "}", with: ")")
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func replaceOneArgumentCommand(
        in source: String,
        command: String,
        transform: (String) -> String
    ) -> String {
        let marker = "\\\(command){"
        var result = ""
        var cursor = source.startIndex

        while let markerRange = source.range(of: marker, range: cursor..<source.endIndex),
              let closingBrace = matchingBrace(in: source, openingAt: source.index(before: markerRange.upperBound)) {
            result += source[cursor..<markerRange.lowerBound]
            let bodyStart = markerRange.upperBound
            result += transform(String(source[bodyStart..<closingBrace]))
            cursor = source.index(after: closingBrace)
        }

        result += source[cursor..<source.endIndex]
        return result
    }

    private static func replaceTwoArgumentCommand(
        in source: String,
        command: String,
        transform: (String, String) -> String
    ) -> String {
        let marker = "\\\(command)"
        var result = ""
        var cursor = source.startIndex

        while let markerRange = source.range(of: marker, range: cursor..<source.endIndex),
              let firstOpening = nextOpeningBrace(in: source, after: markerRange.upperBound),
              let firstClosing = matchingBrace(in: source, openingAt: firstOpening),
              let secondOpening = nextOpeningBrace(in: source, after: source.index(after: firstClosing)),
              let secondClosing = matchingBrace(in: source, openingAt: secondOpening) {
            result += source[cursor..<markerRange.lowerBound]
            let numerator = String(source[source.index(after: firstOpening)..<firstClosing])
            let denominator = String(source[source.index(after: secondOpening)..<secondClosing])
            result += transform(numerator, denominator)
            cursor = source.index(after: secondClosing)
        }

        result += source[cursor..<source.endIndex]
        return result
    }

    private static func nextOpeningBrace(in source: String, after index: String.Index) -> String.Index? {
        var cursor = index
        while cursor < source.endIndex {
            if source[cursor] == "{" { return cursor }
            if !source[cursor].isWhitespace { return nil }
            cursor = source.index(after: cursor)
        }
        return nil
    }

    private static func matchingBrace(in source: String, openingAt opening: String.Index) -> String.Index? {
        guard opening < source.endIndex, source[opening] == "{" else { return nil }
        var depth = 0
        var cursor = opening
        while cursor < source.endIndex {
            if source[cursor] == "{" {
                depth += 1
            } else if source[cursor] == "}" {
                depth -= 1
                if depth == 0 { return cursor }
            }
            cursor = source.index(after: cursor)
        }
        return nil
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

struct DeletedConversation: Identifiable, Codable, Sendable {
    let conversation: Conversation
    let deletedAt: Date

    var id: UUID { conversation.id }
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
    @Published var streamingPreview: String?
    @Published var webSearchSuggestion: AetherWebSearchSuggestion?
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
    @Published var recentlyDeleted: [DeletedConversation] = []
    @Published var webSearchEnabled: Bool = UserDefaults.standard.object(forKey: "webSearchEnabled") as? Bool ?? true {
        didSet { UserDefaults.standard.set(webSearchEnabled, forKey: "webSearchEnabled") }
    }
    private var offlineWebNoticeShownConversationIDs: Set<UUID> = []
    private var activeInferenceCount = 0

    static let deletedRetentionDays = 30

    init(memoryStore: AetherMemoryStore = .shared) {
        self.memoryStore = memoryStore
        let savedConversations = memoryStore.loadConversations()
        self.conversations = savedConversations.isEmpty
            ? sampleConversations
            : savedConversations.map(Self.canonicalizeBuiltInWorkspace).map(AetherTitleGenerator.repairIfNeeded)
        migrateStockConversationsIfNeeded()
        // Seed conversations are intentionally preserved. Keep the old cleanup
        // routine below for reference, but never delete user-visible chats at launch.
        // removeLegacySeedConversations()
        persistAllConversations()
        loadRecentlyDeleted()
        purgeExpiredDeletedConversations()
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
        if let conversation = conversations.first(where: { $0.id == id }) {
            recentlyDeleted.insert(DeletedConversation(conversation: conversation, deletedAt: Date()), at: 0)
            saveRecentlyDeleted()
        }
        conversations.removeAll { $0.id == id }
        memoryStore.deleteConversation(id: id)
    }

    func restoreDeleted(_ id: UUID) {
        guard let index = recentlyDeleted.firstIndex(where: { $0.id == id }) else { return }
        let conversation = recentlyDeleted.remove(at: index).conversation
        conversations.insert(conversation, at: 0)
        memoryStore.saveConversation(conversation)
        saveRecentlyDeleted()
    }

    func permanentlyDeleteConversation(_ id: UUID) {
        recentlyDeleted.removeAll { $0.id == id }
        saveRecentlyDeleted()
    }

    func emptyRecentlyDeleted() {
        recentlyDeleted.removeAll()
        saveRecentlyDeleted()
    }

    private func purgeExpiredDeletedConversations() {
        let cutoff = Date().addingTimeInterval(-Double(Self.deletedRetentionDays) * 86_400)
        let kept = recentlyDeleted.filter { $0.deletedAt > cutoff }
        if kept.count != recentlyDeleted.count {
            recentlyDeleted = kept
            saveRecentlyDeleted()
        }
    }

    private static var recentlyDeletedFileURL: URL? {
        try? FileManager.default
            .url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("RecentlyDeletedConversations.json")
    }

    private func loadRecentlyDeleted() {
        guard let url = Self.recentlyDeletedFileURL,
              let data = try? Data(contentsOf: url),
              let items = try? JSONDecoder().decode([DeletedConversation].self, from: data) else { return }
        recentlyDeleted = items
    }

    private func saveRecentlyDeleted() {
        guard let url = Self.recentlyDeletedFileURL,
              let data = try? JSONEncoder().encode(recentlyDeleted) else { return }
        try? data.write(to: url, options: .atomic)
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
        webSearchSuggestion = nil
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
        webSearchSuggestion = nil
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

    func regenerateLastResponse(in id: UUID, forcedWebSearchQuery: String? = nil) async {
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
            memoryContext: memoryContext,
            forcedWebSearchQuery: forcedWebSearchQuery
        )
    }

    func searchWebAndRegenerate(in id: UUID) async {
        guard let suggestion = webSearchSuggestion, suggestion.conversationID == id else { return }
        let latestUserText = conversations.first(where: { $0.id == id })?.messages.last(where: { $0.role == .user })?.content
        AetherBetaTelemetry.shared.record(
            .searchChosen,
            conversationID: id,
            prompt: latestUserText,
            metadata: ["query": suggestion.query]
        )
        webSearchSuggestion = nil
        await regenerateLastResponse(in: id, forcedWebSearchQuery: suggestion.query)
    }

    private func generateAndAppendReply(
        to id: UUID,
        persona: AssistantPersona,
        messageSnapshot: [ChatMessage],
        priorMessages: [ChatMessage],
        latestUserText: String,
        memoryContext: String?,
        forcedWebSearchQuery: String? = nil
    ) async {
        webSearchSuggestion = nil
        let inferenceStartedAt = Date()
        beginInferenceActivity()
        let task = AetherBackgroundTask.begin(name: "Aether V1 inference") { [weak self] in
            Task { @MainActor in
                self?.modelLoadingMessage = nil
                self?.generationStatusMessage = nil
                self?.streamingPreview = nil
            }
        }
        defer {
            AetherBackgroundTask.end(task)
            endInferenceActivity()
        }

        do {
            generationStatusMessage = messageSnapshot.contains(where: { !$0.attachments.isEmpty })
                ? "Reading attachments and the conversation"
                : "Reading the conversation"
            // Detect explicit search language even when search is disabled. In
            // the Contributor Beta, that distinction is valuable harness data:
            // it shows a user wanted grounding but the current setting blocked it.
            let detectedExplicitWebQuery = AetherWebSearchIntent.explicitQuery(
                from: latestUserText,
                previousMessages: priorMessages
            )
            let candidateWebQuery = AetherWebSearchIntent.query(
                from: latestUserText,
                previousMessages: priorMessages
            )
            let requestedWebQuery = forcedWebSearchQuery ?? detectedExplicitWebQuery ?? candidateWebQuery
            let webSearchRequestSource: String = {
                if forcedWebSearchQuery != nil { return "suggested_action" }
                if detectedExplicitWebQuery != nil { return "explicit_prompt" }
                if candidateWebQuery != nil { return "automatic_candidate" }
                return "none"
            }()
            let webQuery = webSearchEnabled ? requestedWebQuery : nil
            var webSearchContext: String?
            var webSourcesMarkdown: String?
            var webSearchSourceCount = 0
            var webSearchSucceeded = false
            var webSearchOutcome = requestedWebQuery == nil
                ? "not_requested"
                : (webSearchEnabled ? "pending" : "disabled")
            if let webQuery {
                if networkMonitor.hasReceivedStatus && !networkMonitor.isConnected {
                    webSearchContext = offlineWebContext(for: webQuery, conversationID: id)
                    webSourcesMarkdown = nil
                    webSearchOutcome = "offline"
                } else {
                    generationStatusMessage = "Searching the web"
                    do {
                        let localizedQuery = await locationService.localizeSearchQuery(webQuery, originalUserText: latestUserText)
                        let searchResult = try await webSearch.search(query: localizedQuery)
                        webSearchContext = searchResult.context
                        webSourcesMarkdown = searchResult.sourcesMarkdown
                        webSearchSourceCount = searchResult.citations.count
                        webSearchSucceeded = true
                        webSearchOutcome = "succeeded"
                    } catch {
                        webSearchContext = offlineWebContext(for: webQuery, conversationID: id)
                        webSourcesMarkdown = nil
                        webSearchOutcome = "failed"
                    }
                }
            }
            generationStatusMessage = "Composing a response"
            let generatedReply = try await generateReply(
                persona: persona,
                messages: messageSnapshot,
                webSearchContext: webSearchContext,
                memoryContext: memoryContext,
                customSystemPrompt: runtimeSystemPrompt
            )
            var response = generatedReply.text
            /*
             Anti-doom-loop recovery is intentionally disabled while the model-level
             tuning is being evaluated. Keep this block intact so it can be restored
             if TestFlight reveals a failure mode the model does not catch.

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
            */
            response = AetherResponseNormalizer.displayText(response)
            response = responseWithSources(response, sourcesMarkdown: webSourcesMarkdown)
            let suggestedWebQuery: String? = nil
            modelLoadingMessage = nil
            generationStatusMessage = nil
            streamingPreview = nil
            appendAssistantMessage(to: id, content: response)
            let responseMessageID = conversations.first(where: { $0.id == id })?.messages.last(where: { $0.role == .assistant })?.id
            let searchLabel: String = {
                if forcedWebSearchQuery != nil || detectedExplicitWebQuery != nil {
                    return "explicit_positive"
                }
                if candidateWebQuery != nil || AetherLocationService.needsLocation(latestUserText) {
                    return "heuristic_candidate"
                }
                return "unlabeled"
            }()
            let searchLabelSource: String = {
                if forcedWebSearchQuery != nil { return "user_search_action" }
                if detectedExplicitWebQuery != nil { return "explicit_prompt" }
                if candidateWebQuery != nil { return "heuristic_candidate" }
                return "none"
            }()
            AetherBetaTelemetry.shared.record(
                .responseGenerated,
                conversationID: id,
                messageID: responseMessageID,
                prompt: latestUserText,
                response: response,
                metadata: [
                    "latency_ms": String(Int(Date().timeIntervalSince(inferenceStartedAt) * 1_000)),
                    "web_search_requested": String(webQuery != nil),
                    "web_search_enabled": String(webSearchEnabled),
                    "web_search_intent_detected": String(requestedWebQuery != nil),
                    "web_search_request_source": webSearchRequestSource,
                    "web_search_outcome": webSearchOutcome,
                    "web_search_succeeded": String(webSearchSucceeded),
                    "web_search_source_count": String(webSearchSourceCount),
                    "location_query_detected": String(AetherLocationService.needsLocation(latestUserText)),
                    "search_suggested": String(suggestedWebQuery != nil),
                    "search_label": searchLabel,
                    "search_label_source": searchLabelSource,
                    "control_group_eligible": searchLabel == "unlabeled" ? "true" : "false"
                ]
            )
            if let suggestedWebQuery {
                AetherBetaTelemetry.shared.record(
                    .searchSuggested,
                    conversationID: id,
                    messageID: responseMessageID,
                    prompt: latestUserText,
                    response: response,
                    metadata: ["query": String(suggestedWebQuery.prefix(1_024))]
                )
            }
            if AetherWebSearchIntent.isPotentialSearchRequest(latestUserText) {
                AetherBetaTelemetry.shared.record(
                    .webSearchEvaluated,
                    conversationID: id,
                    messageID: responseMessageID,
                    prompt: latestUserText,
                    response: response,
                    metadata: [
                        "candidate_query": String((candidateWebQuery ?? "").prefix(1_024)),
                        "detected_query": String((requestedWebQuery ?? "").prefix(1_024)),
                        "enabled": String(webSearchEnabled),
                        "outcome": webSearchOutcome,
                        "location_query_detected": String(AetherLocationService.needsLocation(latestUserText))
                    ]
                )
            }
            if let requestedWebQuery {
                let searchMetadata = [
                    "query": String(requestedWebQuery.prefix(1_024)),
                    "source": webSearchRequestSource,
                    "enabled": String(webSearchEnabled),
                    "outcome": webSearchOutcome
                ]
                AetherBetaTelemetry.shared.record(
                    .webSearchRequested,
                    conversationID: id,
                    messageID: responseMessageID,
                    prompt: latestUserText,
                    response: response,
                    metadata: searchMetadata
                )
                if webQuery != nil {
                    AetherBetaTelemetry.shared.record(
                        .webSearchPerformed,
                        conversationID: id,
                        messageID: responseMessageID,
                        prompt: latestUserText,
                        response: response,
                        metadata: searchMetadata
                    )
                }
            }
            if generatedReply.didReachOutputLimit {
                AetherBetaTelemetry.shared.record(
                    .responseTruncated,
                    conversationID: id,
                    messageID: responseMessageID,
                    prompt: latestUserText,
                    response: response,
                    metadata: ["truncated": "true", "reason": "output_token_limit"]
                )
            }
            notifyIfNeeded(conversationTitle: conversations.first(where: { $0.id == id })?.title ?? "Canopy", response: response)
        } catch is CancellationError {
            modelLoadingMessage = nil
            generationStatusMessage = nil
            streamingPreview = nil
            appendAssistantMessage(to: id, content: "Response stopped.")
        } catch {
            modelLoadingMessage = nil
            generationStatusMessage = nil
            streamingPreview = nil
            let errorMessage = userFacingInferenceErrorMessage(for: error)
            appendAssistantMessage(to: id, content: errorMessage)
            let failedMessageID = conversations.first(where: { $0.id == id })?.messages.last(where: { $0.role == .assistant })?.id
            AetherBetaTelemetry.shared.record(
                .responseGenerated,
                conversationID: id,
                messageID: failedMessageID,
                prompt: latestUserText,
                response: errorMessage,
                metadata: [
                    "latency_ms": String(Int(Date().timeIntervalSince(inferenceStartedAt) * 1_000)),
                    "inference_failed": "true"
                ]
            )
            AetherBetaTelemetry.shared.record(
                .inferenceFailed,
                conversationID: id,
                messageID: failedMessageID,
                prompt: latestUserText,
                response: errorMessage,
                metadata: ["error": String(inferenceErrorDescription(error).prefix(512))]
            )
            if case AetherOnDeviceError.emptyResponse = error {
                AetherBetaTelemetry.shared.record(
                    .responseEmpty,
                    conversationID: id,
                    messageID: failedMessageID,
                    prompt: latestUserText,
                    response: errorMessage,
                    metadata: ["reason": "empty_model_output"]
                )
            }
            notifyIfNeeded(conversationTitle: conversations.first(where: { $0.id == id })?.title ?? "Canopy", response: errorMessage)
        }
    }

    private func beginInferenceActivity() {
        activeInferenceCount += 1
        UIApplication.shared.isIdleTimerDisabled = true
    }

    private func endInferenceActivity() {
        activeInferenceCount = max(0, activeInferenceCount - 1)
        if activeInferenceCount == 0 {
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }

    private func offlineWebContext(for query: String, conversationID: UUID) -> String {
        let includeNotice = offlineWebNoticeShownConversationIDs.insert(conversationID).inserted
        return AetherWebSearchIntent.offlineContext(
            for: query,
            includeUnavailableNotice: includeNotice
        )
    }

    private func generateReply(
        persona: AssistantPersona,
        messages: [ChatMessage],
        webSearchContext: String? = nil,
        memoryContext: String? = nil,
        customSystemPrompt: String = ""
    ) async throws -> AetherGeneratedReply {
        if selectedModel == AetherModelCatalog.aetherV1DisplayName {
            streamingPreview = nil
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
                },
                onToken: { [weak self] preview in
                    Task { @MainActor in
                        self?.streamingPreview = preview
                    }
                }
            )
        }

        if inferenceProvider == .onDevice {
            throw AetherOnDeviceError.unsupportedLocalModel(selectedModel)
        }

        return AetherGeneratedReply(
            text: try await backend.send(
            endpoint: apiEndpoint,
            model: selectedModel,
            persona: persona,
            messages: messages,
            webSearchContext: webSearchContext,
            memoryContext: memoryContext,
            customSystemPrompt: customSystemPrompt
            ),
            didReachOutputLimit: false
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
        // Disabled intentionally. Seed cleanup must never delete conversations
        // from a user's local store. If a future migration is needed, add an
        // explicit, non-destructive migration keyed to a known seed record.
    }

    /// Migrates only the untouched built-in examples. User-created or edited
    /// conversations are left intact, and no local conversations are deleted.
    private func migrateStockConversationsIfNeeded() {
        let migrations: [(oldTitle: String, oldPrompt: String, newTitle: String)] = [
            ("Two-Week Launch Plan", "Help me plan a small product launch in two weeks.", "Morning Reflection"),
            ("Coffee Before the Market", "Find a quiet coffee shop near the farmers market that opens early.", "Dinner From What's Left"),
            ("Eco Brand Taglines", "Write taglines for a refill shop that cuts single-use plastic.", "Are These Leftovers Still Good?"),
            ("Customer Follow-Up Email", "Draft a friendly follow-up email to a customer who went quiet after a demo.", "A Text I Keep Putting Off"),
            ("Reading a Nutrition Label", "This granola says: serving 1/4 cup, sugar 11g, ingredients: whole oats, cane sugar, honey, brown rice syrup, almonds. Is it actually healthy?", "Making Sense of a Car Repair Quote")
        ]

        var migratedAny = false
        for migration in migrations {
            guard let index = conversations.firstIndex(where: {
                $0.title == migration.oldTitle &&
                $0.messages.first?.role == .user &&
                $0.messages.first?.content == migration.oldPrompt
            }), let replacement = sampleConversations.first(where: { $0.title == migration.newTitle }) else {
                continue
            }

            let existingID = conversations[index].id
            conversations[index] = Conversation(
                id: existingID,
                title: replacement.title,
                workspace: replacement.workspace,
                persona: replacement.persona,
                isPinned: replacement.isPinned,
                previewText: replacement.previewText,
                updatedAt: replacement.updatedAt,
                messages: replacement.messages,
                memorySummary: replacement.memorySummary
            )
            migratedAny = true
        }

        if migratedAny && !conversations.contains(where: { $0.title == "Morning Reflection" }) {
            conversations.insert(sampleConversations[0], at: 0)
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

    @discardableResult
    private func appendAssistantMessage(to id: UUID, content: String) -> UUID? {
        guard let idx = conversations.firstIndex(where: { $0.id == id }) else { return nil }
        let reply = ChatMessage(role: .assistant, content: content)
        conversations[idx].messages.append(reply)
        conversations[idx].previewText = reply.content
        conversations[idx].updatedAt = Date()
        refreshMemorySummary(at: idx)
        persistConversation(at: idx)
        return reply.id
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

    private func userFacingInferenceErrorMessage(for error: Error) -> String {
        if let interruptionMessage = localBackgroundInterruptionMessage(for: error) {
            return interruptionMessage
        }

        switch error {
        case AetherOnDeviceError.modelDownloadFailed,
             AetherOnDeviceError.invalidModelURL:
            return "CanopyChat needs to download a model before it can respond. Please check your internet connection and try again."
        case AetherOnDeviceError.modelLoadFailed,
             AetherOnDeviceError.contextLoadFailed,
             AetherOnDeviceError.llamaUnavailable:
            return "CanopyChat couldn't start right now. Please try sending your message again."
        case AetherOnDeviceError.emptyResponse:
            return "CanopyChat didn't finish responding. Keep the app open while it responds, then try again."
        default:
            return "CanopyChat couldn't finish responding. Keep the app open while it responds, then try again."
        }
    }

    private func localBackgroundInterruptionMessage(for error: Error) -> String? {
        guard !appIsActive, let localError = error as? AetherOnDeviceError else { return nil }
        switch localError {
        case .decodeFailed, .emptyResponse, .multimodalDecodeFailed:
            return "You left the app, so Canopy was interrupted while responding. Stay in the app while waiting for a reply next time. Please try again."
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
    Conversation(title: "Morning Reflection", workspace: .personal, persona: .default,
                 isPinned: true, previewText: "What does a good day look like for you?", updatedAt: Date(),
                 messages: [
                     ChatMessage(role: .user, content: "I want to reflect on my goals this week."),
                     ChatMessage(role: .assistant, content: "What does a good day look like for you?")
                 ]),
    Conversation(title: "Dinner From What's Left", workspace: .personal, persona: .default,
                 previewText: "That's enough for a solid one-pan dinner...", updatedAt: Date().addingTimeInterval(-3600),
                 messages: [
                     ChatMessage(role: .user, content: "I have chicken thighs, half an onion, rice, and a lemon. What can I make for dinner?", timestamp: Date().addingTimeInterval(-3660)),
                     ChatMessage(role: .assistant, content: """
                     That's enough for a solid one-pan dinner — lemon chicken and rice:

                     1. Salt the thighs and brown them skin-side down in an oven-safe pan, about 6 minutes. Set them aside.
                     2. Cook the onion in the same pan until soft, then stir in the rice for a minute so it picks up the fat.
                     3. Add 1½ cups water per cup of rice, squeeze in half the lemon, and nestle the chicken back on top.
                     4. Cover and bake at 375°F for 25 minutes, then rest 5 minutes off the heat.

                     Squeeze the other lemon half over everything right before serving — that's what makes it taste finished rather than plain.
                     """, timestamp: Date().addingTimeInterval(-3600))
                 ]),
    Conversation(title: "Are These Leftovers Still Good?", workspace: .personal, persona: .concise,
                 previewText: "Four days is the outer limit for cooked chicken...", updatedAt: Date().addingTimeInterval(-9000),
                 messages: [
                     ChatMessage(role: .user, content: "How long is cooked chicken safe in the fridge? Mine's been in there since Sunday.", timestamp: Date().addingTimeInterval(-9060)),
                     ChatMessage(role: .assistant, content: """
                     Four days is the outer limit for cooked chicken in the fridge at 40°F or below.

                     If Sunday was more than four days ago, throw it out — smell isn't a reliable test, since the bacteria that cause illness don't change how food looks or smells.

                     To buy more time next round, freeze it within a day or two; it keeps for months at 0°F.

                     Sources
                     - [FoodSafety.gov — Cold Food Storage Chart](https://www.foodsafety.gov/food-safety-charts/cold-food-storage-charts)
                     """, timestamp: Date().addingTimeInterval(-9000))
                 ]),
    Conversation(title: "A Text I Keep Putting Off", workspace: .creative, persona: .creative,
                 previewText: "Here's a version that's warm but doesn't over-explain...", updatedAt: Date().addingTimeInterval(-26_000),
                 messages: [
                     ChatMessage(role: .user, content: "I need to cancel dinner plans with a friend tonight and I feel bad about it. Help me word the text.", timestamp: Date().addingTimeInterval(-26_060)),
                     ChatMessage(role: .assistant, content: """
                     Here's a version that's warm but doesn't over-explain:

                     "Hey — I'm so sorry, I have to bail on tonight. I'm running on empty and I'd be bad company. Can we do next week instead? I'll pick the place."

                     Three things it does on purpose:

                     - **Apologizes once**, not three times — repeated apologies make the other person reassure you
                     - **Gives a reason without an essay** — one honest line lands better than a paragraph of justification
                     - **Offers the next date immediately**, so it reads as a reschedule, not a fade

                     Want a shorter version, or one for a friend you cancel on often?
                     """, timestamp: Date().addingTimeInterval(-26_000))
                 ]),
    Conversation(title: "Making Sense of a Car Repair Quote", workspace: .research, persona: .analytical,
                 previewText: "Three of those four lines are worth questioning...", updatedAt: Date().addingTimeInterval(-90_000),
                 messages: [
                     ChatMessage(role: .user, content: "The shop quoted me $1,240: brake pads and rotors $610, brake fluid flush $150, engine air filter $95, shop supplies $85, labor $300. Is any of that padding?", timestamp: Date().addingTimeInterval(-90_060)),
                     ChatMessage(role: .assistant, content: """
                     Three of those four lines are worth questioning:

                     - **Pads and rotors at $610** — reasonable if that covers all four wheels, high if it's only the front. Ask which axles are included before anything else.
                     - **Engine air filter at $95** — the part itself is usually $20-30 and takes a few minutes to swap. This is the easiest line to decline and do yourself.
                     - **Shop supplies at $85** — a percentage-based catch-all fee. Many shops will reduce or drop it if you ask.
                     - **Brake fluid flush at $150** — legitimate maintenance, but only if it's actually due. Ask when it was last done.

                     Ask for the itemized parts numbers and the labor hours. A shop that won't put those in writing is telling you something.
                     """, timestamp: Date().addingTimeInterval(-90_000))
                 ])
]
