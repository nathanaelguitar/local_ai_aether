import SwiftUI

struct ConversationListView: View {
    @EnvironmentObject var state: AppState
    @State private var selectedWorkspace: Workspace? = nil
    @State private var showNewChat = false
    @State private var selectedConversationId: UUID? = nil
    @State private var showSettings = false

    var filtered: [Conversation] {
        guard let ws = selectedWorkspace else { return state.conversations }
        return state.conversations.filter { $0.workspace == ws }
    }
    var pinned: [Conversation] { filtered.filter(\.isPinned) }
    var recent:  [Conversation] { filtered.filter { !$0.isPinned } }

    var body: some View {
        NavigationStack {
            OakBackground {
                VStack(spacing: 0) {
                    // Header
                    HStack {
                        Text("Your Grove")
                            .font(.system(size: 28, weight: .light, design: .serif))
                            .foregroundColor(AetherColors.oakDark)
                        Spacer()
                        Button(action: { showSettings = true }) {
                            Image(systemName: "gearshape.fill")
                                .font(.system(size: 20))
                                .foregroundColor(AetherColors.oakMedium)
                                .frame(width: 36, height: 36)
                                .background(AetherColors.oakMedium.opacity(0.12))
                                .clipShape(Circle())
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 60)
                    .padding(.bottom, 12)

                    // Workspace filter
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 10) {
                            WorkspaceChip(label: "All", isSelected: selectedWorkspace == nil, color: AetherColors.oakMedium) {
                                selectedWorkspace = nil
                            }
                            ForEach(Workspace.allCases) { ws in
                                WorkspaceChip(label: ws.rawValue, isSelected: selectedWorkspace == ws, color: ws.color) {
                                    selectedWorkspace = ws
                                }
                            }
                        }
                        .padding(.horizontal, 20)
                    }
                    .padding(.bottom, 12)

                    // List
                    ScrollView {
                        LazyVStack(spacing: 12, pinnedViews: []) {
                            if !pinned.isEmpty {
                                SectionHeader(title: "Pinned", color: AetherColors.amber)
                                ForEach(pinned) { conv in
                                    ConversationRow(conv: conv, onTap: { selectedConversationId = conv.id },
                                                    onPin: { state.togglePin(conv.id) },
                                                    onDelete: { state.delete(conv.id) })
                                }
                            }
                            if !recent.isEmpty {
                                SectionHeader(title: "Recent", color: AetherColors.warmGray500)
                                ForEach(recent) { conv in
                                    ConversationRow(conv: conv, onTap: { selectedConversationId = conv.id },
                                                    onPin: { state.togglePin(conv.id) },
                                                    onDelete: { state.delete(conv.id) })
                                }
                            }
                            if filtered.isEmpty {
                                EmptyGrove(onNewChat: { showNewChat = true })
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.bottom, 110)
                    }
                }

                VStack {
                    Spacer()
                    HStack {
                        Spacer()
                        Button(action: { showNewChat = true }) {
                            HStack(spacing: 8) {
                                Image(systemName: "plus")
                                    .font(.system(size: 16, weight: .semibold))
                                Text("New Chat")
                                    .font(.system(size: 15, weight: .semibold))
                            }
                            .foregroundColor(.white)
                            .padding(.horizontal, 20)
                            .padding(.vertical, 14)
                            .background(AetherColors.oakMedium)
                            .clipShape(Capsule())
                            .shadow(color: AetherColors.oakDark.opacity(0.3), radius: 8, y: 4)
                        }
                        .buttonStyle(.plain)
                        .padding(.trailing, 20)
                        .padding(.bottom, 36)
                    }
                }
            }
            .navigationBarHidden(true)
            .navigationDestination(item: $selectedConversationId) { id in
                ChatView(
                    conversationId: id,
                    onNewChat: {
                        let conversation = state.conversations.first(where: { $0.id == id })
                        let newId = state.createConversation(
                            title: "",
                            workspace: conversation?.workspace ?? state.defaultWorkspace,
                            persona: conversation?.persona ?? .default
                        )
                        selectedConversationId = newId
                    }
                )
            }
        }
        .sheet(isPresented: $showNewChat) {
            NewChatSheet(onCreate: { title, ws, persona in
                selectedConversationId = state.createConversation(title: title, workspace: ws, persona: persona)
                showNewChat = false
            })
        }
        .sheet(isPresented: $showSettings) {
            SettingsView()
        }
        .preferredColorScheme(state.isDarkTheme ? .dark : .light)
    }
}

struct WorkspaceChip: View {
    let label: String
    let isSelected: Bool
    let color: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(isSelected ? .white : color)
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(isSelected ? color : color.opacity(0.12))
                .clipShape(Capsule())
        }
    }
}

struct SectionHeader: View {
    let title: String
    let color: Color
    var body: some View {
        Text(title.uppercased())
            .font(.system(size: 11, weight: .semibold))
            .foregroundColor(color)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 8)
    }
}

struct ConversationRow: View {
    @EnvironmentObject var state: AppState
    let conv: Conversation
    let onTap: () -> Void
    let onPin: () -> Void
    let onDelete: () -> Void

    private var isCurrentlyPinned: Bool {
        state.conversations.first(where: { $0.id == conv.id })?.isPinned ?? conv.isPinned
    }

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 14) {
                // Workspace icon
                ZStack {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(conv.workspace.color.opacity(0.15))
                        .frame(width: 48, height: 48)
                    Image(systemName: conv.workspace.icon)
                        .font(.system(size: 20))
                        .foregroundColor(conv.workspace.color)
                }

                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(conv.title)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(state.isDarkTheme ? AetherColors.warmGray200 : AetherColors.warmBlack)
                            .lineLimit(1)
                        if conv.isPinned {
                            Image(systemName: "pin.fill")
                                .font(.system(size: 11))
                                .foregroundColor(AetherColors.amber)
                        }
                        Spacer()
                        Text(relativeDate(conv.updatedAt))
                            .font(.system(size: 11))
                            .foregroundColor(AetherColors.warmGray400)
                    }
                    Text(conv.previewText.isEmpty ? "No messages yet" : conv.previewText)
                        .font(.system(size: 13))
                        .foregroundColor(AetherColors.warmGray500)
                        .lineLimit(1)

                    Text(conv.persona.name)
                        .font(.system(size: 11, weight: .medium))
                        .foregroundColor(conv.workspace.color)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 2)
                        .background(conv.workspace.color.opacity(0.1))
                        .clipShape(Capsule())
                }
            }
            .padding(14)
            .background(state.isDarkTheme ? AetherColors.warmGray800 : Color.white)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .contextMenu {
            Button(action: onPin) {
                Label(isCurrentlyPinned ? "Unpin" : "Pin to Top", systemImage: isCurrentlyPinned ? "pin.slash" : "pin")
            }
            Button(role: .destructive, action: onDelete) {
                Label("Delete", systemImage: "trash")
            }
        }
    }

    func relativeDate(_ date: Date) -> String {
        let diff = Date().timeIntervalSince(date)
        if diff < 60 { return "now" }
        if diff < 3600 { return "\(Int(diff/60))m" }
        if diff < 86400 { return "\(Int(diff/3600))h" }
        return "\(Int(diff/86400))d"
    }
}

struct EmptyGrove: View {
    let onNewChat: () -> Void
    var body: some View {
        VStack(spacing: 16) {
            Spacer(minLength: 60)
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.system(size: 52))
                .foregroundColor(AetherColors.warmGray400)
            Text("Your grove is quiet")
                .font(.system(size: 17, weight: .medium))
                .foregroundColor(AetherColors.warmGray600)
            Text("Start a new conversation to begin")
                .font(.system(size: 14))
                .foregroundColor(AetherColors.warmGray500)
            Button(action: onNewChat) {
                Text("Plant a new seed")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(AetherColors.oakMedium)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 10)
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(AetherColors.oakMedium, lineWidth: 1.5))
            }
        }
        .frame(maxWidth: .infinity)
    }
}
