import Foundation

struct Conversation: Identifiable {
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

struct ChatMessage: Identifiable {
    let id: UUID
    let role: MessageRole
    var content: String
    let timestamp: Date

    init(id: UUID = UUID(), role: MessageRole, content: String, timestamp: Date = Date()) {
        self.id = id; self.role = role; self.content = content; self.timestamp = timestamp
    }
}

enum MessageRole { case user, assistant }

@MainActor
class AppState: ObservableObject {
    @Published var conversations: [Conversation] = sampleConversations
    @Published var isDarkTheme: Bool = false
    @Published var apiEndpoint: String = ""
    @Published var selectedModel: String = "Canopy V1"
    @Published var defaultWorkspace: Workspace = .personal

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

    func sendMessage(in id: UUID, text: String) {
        guard let idx = conversations.firstIndex(where: { $0.id == id }) else { return }
        let userMsg = ChatMessage(role: .user, content: text)
        conversations[idx].messages.append(userMsg)
        conversations[idx].previewText = text
        conversations[idx].updatedAt = Date()

        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
            let reply = ChatMessage(role: .assistant, content: "I hear you. Let me think about that for a moment and offer something rooted and considered. 🌿")
            self.conversations[idx].messages.append(reply)
            self.conversations[idx].previewText = reply.content
        }
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
