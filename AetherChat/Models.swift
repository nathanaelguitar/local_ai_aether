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

struct Conversation: Identifiable, Sendable {
    let id: UUID
    var title: String
    var workspace: Workspace
    var persona: AssistantPersona
    var isPinned: Bool
    var previewText: String
    var updatedAt: Date
    var messages: [ChatMessage]

    init(id: UUID = UUID(), title: String, workspace: Workspace, persona: AssistantPersona = .default,
         isPinned: Bool = false, previewText: String = "", updatedAt: Date = Date(), messages: [ChatMessage] = []) {
        self.id = id; self.title = title; self.workspace = workspace; self.persona = persona
        self.isPinned = isPinned; self.previewText = previewText; self.updatedAt = updatedAt; self.messages = messages
    }
}

struct ChatMessage: Identifiable, Sendable {
    let id: UUID
    let role: MessageRole
    var content: String
    var attachments: [ChatAttachment]
    let timestamp: Date

    init(id: UUID = UUID(), role: MessageRole, content: String, attachments: [ChatAttachment] = [], timestamp: Date = Date()) {
        self.id = id; self.role = role; self.content = content; self.attachments = attachments; self.timestamp = timestamp
    }
}

struct ChatAttachment: Identifiable, Sendable {
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

enum MessageRole: Sendable {
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
    @Published var conversations: [Conversation] = sampleConversations
    @Published var isDarkTheme: Bool = UserDefaults.standard.bool(forKey: "isDarkTheme") {
        didSet { UserDefaults.standard.set(isDarkTheme, forKey: "isDarkTheme") }
    }
    @Published var apiEndpoint: String = UserDefaults.standard.string(forKey: "apiEndpoint") ?? "http://127.0.0.1:8787" {
        didSet { UserDefaults.standard.set(apiEndpoint, forKey: "apiEndpoint") }
    }
    @Published var selectedModel: String = UserDefaults.standard.string(forKey: "selectedModel") ?? AetherModelCatalog.aetherV1DisplayName {
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
    @Published var customAssistantName: String = UserDefaults.standard.string(forKey: "customAssistantName") ?? "" {
        didSet { UserDefaults.standard.set(customAssistantName, forKey: "customAssistantName") }
    }
    @Published var customSystemPrompt: String = UserDefaults.standard.string(forKey: "customSystemPrompt") ?? "" {
        didSet { UserDefaults.standard.set(customSystemPrompt, forKey: "customSystemPrompt") }
    }
    private let backend = AetherBackendClient()
    private let onDevice = AetherOnDeviceClient()
    private let webSearch = AetherWebSearchService()

    func togglePin(_ id: UUID) {
        guard let idx = conversations.firstIndex(where: { $0.id == id }) else { return }
        conversations[idx].isPinned.toggle()
    }

    func delete(_ id: UUID) {
        conversations.removeAll { $0.id == id }
    }

    func createConversation(title: String, workspace: Workspace, persona: AssistantPersona) -> UUID {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let conversation = Conversation(title: trimmed.isEmpty ? "Untitled" : trimmed, workspace: workspace, persona: persona)
        conversations.insert(conversation, at: 0)
        return conversation.id
    }

    func renameConversation(_ id: UUID, title: String) {
        guard let idx = conversations.firstIndex(where: { $0.id == id }) else { return }
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        conversations[idx].title = trimmed.isEmpty ? "Untitled" : trimmed
    }

    func sendMessage(in id: UUID, text: String, attachments: [ChatAttachment] = []) async {
        guard let idx = conversations.firstIndex(where: { $0.id == id }) else { return }
        let priorMessages = conversations[idx].messages
        let userMsg = ChatMessage(role: .user, content: text, attachments: attachments)
        conversations[idx].messages.append(userMsg)
        conversations[idx].previewText = text.isEmpty ? attachmentPreview(for: attachments) : text
        if conversations[idx].title == "Untitled" {
            conversations[idx].title = AetherTitleGenerator.title(for: text, attachments: attachments)
        }
        conversations[idx].updatedAt = Date()

        let persona = conversations[idx].persona
        let messageSnapshot = conversations[idx].messages
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
            let webQuery = AetherWebSearchIntent.query(from: text, previousMessages: priorMessages)
            var webSearchContext: String?
            if let webQuery {
                generationStatusMessage = "Searching the web"
                do {
                    let searchResult = try await webSearch.search(query: webQuery)
                    webSearchContext = searchResult.context.isEmpty ? nil : searchResult.context
                } catch {
                    webSearchContext = nil
                }
            }
            generationStatusMessage = "Composing a response"
            let response = try await generateReply(
                persona: persona,
                messages: messageSnapshot,
                webSearchContext: webSearchContext,
                customAssistantName: customAssistantName,
                customSystemPrompt: customSystemPrompt
            )
            modelLoadingMessage = nil
            generationStatusMessage = nil
            appendAssistantMessage(to: id, content: response)
            notifyIfNeeded(conversationTitle: conversations.first(where: { $0.id == id })?.title ?? "Aether", response: response)
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
            notifyIfNeeded(conversationTitle: conversations.first(where: { $0.id == id })?.title ?? "Aether", response: errorMessage)
        }
    }

    private func generateReply(
        persona: AssistantPersona,
        messages: [ChatMessage],
        webSearchContext: String? = nil,
        customAssistantName: String = "",
        customSystemPrompt: String = ""
    ) async throws -> String {
        if selectedModel == AetherModelCatalog.aetherV1DisplayName {
            return try await onDevice.send(
                persona: persona,
                messages: messages,
                webSearchContext: webSearchContext,
                customAssistantName: customAssistantName,
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
            customAssistantName: customAssistantName,
            customSystemPrompt: customSystemPrompt
        )
    }

    private func appendAssistantMessage(to id: UUID, content: String) {
        guard let idx = conversations.firstIndex(where: { $0.id == id }) else { return }
        let reply = ChatMessage(role: .assistant, content: content)
        conversations[idx].messages.append(reply)
        conversations[idx].previewText = reply.content
        conversations[idx].updatedAt = Date()
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
            return "Aether was interrupted while running local inference in the background. The local model has been reset; please resend the message."
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
        let cleaned = text
            .replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let source: String
        if cleaned.isEmpty, let first = attachments.first {
            source = first.isImage ? "Image Analysis" : first.displayName
        } else {
            source = cleaned
        }

        let stopwords: Set<String> = ["a", "an", "and", "are", "can", "do", "for", "how", "i", "in", "is", "it", "me", "of", "on", "or", "the", "to", "what", "with", "you"]
        let words = source
            .components(separatedBy: CharacterSet.alphanumerics.inverted)
            .filter { !$0.isEmpty }
            .filter { !stopwords.contains($0.lowercased()) }
            .prefix(5)

        let title = words.map { word in
            word.prefix(1).uppercased() + word.dropFirst()
        }.joined(separator: " ")

        return title.isEmpty ? "Untitled" : String(title.prefix(42))
    }
}

let sampleConversations: [Conversation] = [
    Conversation(title: "Morning Reflection", workspace: .personal, persona: .default,
                 isPinned: true, previewText: "What does a good day look like for you?", updatedAt: Date(),
                 messages: [
                     ChatMessage(role: .user, content: "I want to reflect on my goals this week."),
                     ChatMessage(role: .assistant, content: "What does a good day look like for you?")
                 ]),
    Conversation(title: "Q3 Strategy Deck", workspace: .work, persona: .analytical,
                 previewText: "Here are three frameworks for the executive summary...", updatedAt: Date().addingTimeInterval(-3600),
                 messages: [
                     ChatMessage(role: .user, content: "Help me structure the Q3 strategy deck"),
                     ChatMessage(role: .assistant, content: "Here are three frameworks for the executive summary...")
                 ]),
    Conversation(title: "Novel Outline", workspace: .creative, persona: .creative,
                 previewText: "The oak forest is a perfect metaphor for...", updatedAt: Date().addingTimeInterval(-7200),
                 messages: []),
    Conversation(title: "ML Paper Notes", workspace: .research, persona: .analytical,
                 previewText: "The attention mechanism in this paper differs from...", updatedAt: Date().addingTimeInterval(-14400),
                 messages: [])
]
