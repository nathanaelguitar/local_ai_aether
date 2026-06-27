import Foundation

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

    init(id: UUID = UUID(), data: Data, mimeType: String = "image/jpeg", filename: String = "image.jpg") {
        self.id = id
        self.data = data
        self.mimeType = mimeType
        self.filename = filename
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
    @Published var defaultWorkspace: Workspace = .personal
    private let backend = AetherBackendClient()
    private let onDevice = AetherOnDeviceClient()

    func togglePin(_ id: UUID) {
        guard let idx = conversations.firstIndex(where: { $0.id == id }) else { return }
        conversations[idx].isPinned.toggle()
    }

    func delete(_ id: UUID) {
        conversations.removeAll { $0.id == id }
    }

    func createConversation(title: String, workspace: Workspace, persona: AssistantPersona) {
        conversations.insert(Conversation(title: title, workspace: workspace, persona: persona), at: 0)
    }

    func sendMessage(in id: UUID, text: String, attachments: [ChatAttachment] = []) async {
        guard let idx = conversations.firstIndex(where: { $0.id == id }) else { return }
        let userMsg = ChatMessage(role: .user, content: text, attachments: attachments)
        conversations[idx].messages.append(userMsg)
        conversations[idx].previewText = text.isEmpty ? "\(attachments.count) image attachment\(attachments.count == 1 ? "" : "s")" : text
        conversations[idx].updatedAt = Date()

        let persona = conversations[idx].persona
        let messageSnapshot = conversations[idx].messages
        do {
            let response = try await generateReply(
                persona: persona,
                messages: messageSnapshot
            )
            appendAssistantMessage(to: id, content: response)
        } catch {
            appendAssistantMessage(to: id, content: "Inference error: \(error.localizedDescription)")
        }
    }

    private func generateReply(persona: AssistantPersona, messages: [ChatMessage]) async throws -> String {
        if selectedModel == AetherModelCatalog.aetherV1DisplayName {
            return try await onDevice.send(
                persona: persona,
                messages: messages
            )
        }

        if inferenceProvider == .onDevice {
            throw AetherOnDeviceError.unsupportedLocalModel(selectedModel)
        }

        return try await backend.send(
            endpoint: apiEndpoint,
            model: selectedModel,
            persona: persona,
            messages: messages
        )
    }

    private func appendAssistantMessage(to id: UUID, content: String) {
        guard let idx = conversations.firstIndex(where: { $0.id == id }) else { return }
        let reply = ChatMessage(role: .assistant, content: content)
        conversations[idx].messages.append(reply)
        conversations[idx].previewText = reply.content
        conversations[idx].updatedAt = Date()
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
