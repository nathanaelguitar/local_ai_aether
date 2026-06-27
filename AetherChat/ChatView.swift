import SwiftUI

struct ChatView: View {
    @EnvironmentObject var state: AppState
    let conversationId: UUID

    @State private var inputText = ""
    @State private var isSending = false
    @FocusState private var inputFocused: Bool

    var conversation: Conversation? {
        state.conversations.first { $0.id == conversationId }
    }

    var body: some View {
        OakBackground {
            VStack(spacing: 0) {
                // Messages
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 8) {
                            if conversation?.messages.isEmpty ?? true {
                                ChatEmptyState(personaName: conversation?.persona.name ?? "Aether",
                                               isDark: state.isDarkTheme)
                            }
                            ForEach(conversation?.messages ?? []) { msg in
                                MessageBubble(message: msg, isDark: state.isDarkTheme)
                                    .id(msg.id)
                            }
                            if isSending {
                                TypingIndicator(isDark: state.isDarkTheme)
                                    .id("typing")
                            }
                        }
                        .padding(.vertical, 16)
                    }
                    .scrollDismissesKeyboard(.interactively)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        inputFocused = false
                    }
                    .onChange(of: conversation?.messages.count) {
                        if let last = conversation?.messages.last {
                            withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                        }
                    }
                    .onChange(of: isSending) {
                        if isSending { withAnimation { proxy.scrollTo("typing", anchor: .bottom) } }
                    }
                }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            InputBar(text: $inputText, isSending: isSending, isDark: state.isDarkTheme, focused: $inputFocused) {
                send()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    Text(conversation?.title ?? "Chat")
                        .font(.system(size: 16, weight: .semibold))
                    if let persona = conversation?.persona {
                        Text("with \(persona.name)")
                            .font(.system(size: 11))
                            .foregroundColor(AetherColors.oakMedium)
                    }
                }
            }
        }
        .preferredColorScheme(state.isDarkTheme ? .dark : .light)
    }

    func send() {
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isSending else { return }
        inputText = ""
        inputFocused = false
        isSending = true
        Task {
            await state.sendMessage(in: conversationId, text: text)
            isSending = false
        }
    }
}

struct MessageBubble: View {
    let message: ChatMessage
    let isDark: Bool

    var isUser: Bool { message.role == .user }

    var body: some View {
        HStack {
            if isUser { Spacer(minLength: 60) }
            Text(message.content)
                .font(.system(size: 15))
                .foregroundColor(isDark ? AetherColors.warmGray200 : AetherColors.warmBlack)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .background(bubbleColor)
                .clipShape(
                    RoundedCornerShape(
                        topLeft: 20, topRight: 20,
                        bottomLeft: isUser ? 20 : 4,
                        bottomRight: isUser ? 4 : 20
                    )
                )
            if !isUser { Spacer(minLength: 60) }
        }
        .padding(.horizontal, 16)
    }

    var bubbleColor: Color {
        if isUser { return isDark ? AetherColors.oakMedium : AetherColors.oakMedium.opacity(0.85) }
        return isDark ? AetherColors.warmGray800 : Color.white
    }
}

struct TypingIndicator: View {
    let isDark: Bool
    @State private var phase = 0.0

    var body: some View {
        HStack {
            HStack(spacing: 5) {
                ForEach(0..<3, id: \.self) { i in
                    Circle()
                        .fill(isDark ? AetherColors.warmGray400 : AetherColors.warmGray500)
                        .frame(width: 7, height: 7)
                        .offset(y: sin(phase + Double(i) * 0.8) * 3)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(isDark ? AetherColors.warmGray800 : Color.white)
            .clipShape(Capsule())
            .padding(.leading, 16)
            Spacer()
        }
        .onAppear {
            withAnimation(.linear(duration: 1.0).repeatForever(autoreverses: false)) {
                phase = .pi * 2
            }
        }
    }
}

struct InputBar: View {
    @Binding var text: String
    let isSending: Bool
    let isDark: Bool
    var focused: FocusState<Bool>.Binding
    let onSend: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            if focused.wrappedValue {
                Button {
                    focused.wrappedValue = false
                } label: {
                    Image(systemName: "keyboard.chevron.compact.down")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(AetherColors.oakMedium)
                        .frame(width: 34, height: 44)
                        .contentShape(Rectangle())
                }
                .transition(.move(edge: .leading).combined(with: .opacity))
            }

            TextField("Message your assistant...", text: $text, axis: .vertical)
                .font(.system(size: 15))
                .lineLimit(1...5)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(isDark ? AetherColors.warmGray800 : AetherColors.warmGray100)
                .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                .focused(focused)

            Button(action: onSend) {
                ZStack {
                    Circle()
                        .fill(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                              ? AetherColors.warmGray200 : AetherColors.oakMedium)
                        .frame(width: 44, height: 44)
                    if isSending {
                        ProgressView()
                            .tint(.white)
                            .scaleEffect(0.8)
                    } else {
                        Image(systemName: "arrow.up")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundColor(.white)
                    }
                }
            }
            .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isSending)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(isDark ? AetherColors.warmGray900 : Color.white)
        .shadow(color: .black.opacity(0.08), radius: 12, y: -2)
        .animation(.easeInOut(duration: 0.18), value: focused.wrappedValue)
    }
}

struct ChatEmptyState: View {
    let personaName: String
    let isDark: Bool
    var body: some View {
        VStack(spacing: 16) {
            Spacer(minLength: 80)
            Text("🌿")
                .font(.system(size: 60))
            Text("Begin your conversation with \(personaName)")
                .font(.system(size: 15))
                .foregroundColor(isDark ? AetherColors.warmGray500 : AetherColors.warmGray600)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 32)
    }
}

// Custom shape for message bubble corners
struct RoundedCornerShape: Shape {
    var topLeft: CGFloat = 0
    var topRight: CGFloat = 0
    var bottomLeft: CGFloat = 0
    var bottomRight: CGFloat = 0

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let w = rect.size.width, h = rect.size.height
        let tl = min(topLeft, min(w, h) / 2)
        let tr = min(topRight, min(w, h) / 2)
        let bl = min(bottomLeft, min(w, h) / 2)
        let br = min(bottomRight, min(w, h) / 2)

        path.move(to: CGPoint(x: rect.minX + tl, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - tr, y: rect.minY))
        path.addArc(center: CGPoint(x: rect.maxX - tr, y: rect.minY + tr), radius: tr, startAngle: .degrees(-90), endAngle: .degrees(0), clockwise: false)
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - br))
        path.addArc(center: CGPoint(x: rect.maxX - br, y: rect.maxY - br), radius: br, startAngle: .degrees(0), endAngle: .degrees(90), clockwise: false)
        path.addLine(to: CGPoint(x: rect.minX + bl, y: rect.maxY))
        path.addArc(center: CGPoint(x: rect.minX + bl, y: rect.maxY - bl), radius: bl, startAngle: .degrees(90), endAngle: .degrees(180), clockwise: false)
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + tl))
        path.addArc(center: CGPoint(x: rect.minX + tl, y: rect.minY + tl), radius: tl, startAngle: .degrees(180), endAngle: .degrees(270), clockwise: false)
        path.closeSubpath()
        return path
    }
}
