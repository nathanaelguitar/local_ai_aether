import SwiftUI
import PhotosUI
import UIKit

struct ChatView: View {
    @EnvironmentObject var state: AppState
    let conversationId: UUID

    @State private var inputText = ""
    @State private var isSending = false
    @State private var attachments: [ChatAttachment] = []
    @State private var selectedPhotoItem: PhotosPickerItem?
    @State private var showingCamera = false
    @State private var sendTask: Task<Void, Never>?
    @FocusState private var inputFocused: Bool

    var conversation: Conversation? {
        state.conversations.first { $0.id == conversationId }
    }

    var body: some View {
        OakBackground {
            ZStack {
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
                                if isSending && state.modelLoadingMessage == nil {
                                    TypingIndicator(message: state.generationStatusMessage, isDark: state.isDarkTheme)
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

                if let loadingMessage = state.modelLoadingMessage {
                    ModelLoadingOverlay(message: loadingMessage, isDark: state.isDarkTheme)
                        .transition(.opacity.combined(with: .scale(scale: 0.96)))
                }
            }
            .animation(.easeInOut(duration: 0.22), value: state.modelLoadingMessage)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            InputBar(
                text: $inputText,
                attachments: $attachments,
                selectedPhotoItem: $selectedPhotoItem,
                isSending: isSending,
                isDark: state.isDarkTheme,
                focused: $inputFocused,
                onCamera: { showingCamera = true },
                onStop: { stopSending() }
            ) {
                send()
            }
        }
        .sheet(isPresented: $showingCamera) {
            CameraCaptureView { data in
                if let data {
                    attachments.append(ChatAttachment(data: data, filename: "camera.jpg"))
                }
                showingCamera = false
            }
        }
        .onChange(of: selectedPhotoItem) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self),
                   let normalized = normalizedJPEGData(from: data) {
                    attachments.append(ChatAttachment(data: normalized, filename: "photo.jpg"))
                }
                selectedPhotoItem = nil
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
        let outgoingAttachments = attachments
        guard (!text.isEmpty || !outgoingAttachments.isEmpty), !isSending else { return }
        inputText = ""
        attachments = []
        inputFocused = false
        isSending = true
        sendTask = Task {
            await state.sendMessage(in: conversationId, text: text, attachments: outgoingAttachments)
            if !Task.isCancelled {
                isSending = false
                sendTask = nil
            }
        }
    }

    func stopSending() {
        sendTask?.cancel()
        sendTask = nil
        isSending = false
        state.modelLoadingMessage = nil
        state.generationStatusMessage = nil
    }

    private func normalizedJPEGData(from data: Data) -> Data? {
        AetherImageNormalizer.jpegData(from: data)
    }
}

struct ModelLoadingOverlay: View {
    let message: String
    let isDark: Bool
    @State private var rotation = 0.0
    @State private var pulse = false

    var body: some View {
        ZStack {
            Rectangle()
                .fill((isDark ? Color.black : AetherColors.oakCream).opacity(isDark ? 0.58 : 0.46))
                .background(.ultraThinMaterial)
                .ignoresSafeArea()

            VStack(spacing: 18) {
                ZStack {
                    Circle()
                        .stroke(AetherColors.oakPale.opacity(0.28), lineWidth: 14)
                        .frame(width: 116, height: 116)

                    Circle()
                        .trim(from: 0.08, to: 0.72)
                        .stroke(
                            AngularGradient(
                                colors: [AetherColors.oakMedium, AetherColors.copper, AetherColors.amber, AetherColors.oakMedium],
                                center: .center
                            ),
                            style: StrokeStyle(lineWidth: 14, lineCap: .round)
                        )
                        .frame(width: 116, height: 116)
                        .rotationEffect(.degrees(rotation))

                    Image(systemName: "leaf.fill")
                        .font(.system(size: 34, weight: .semibold))
                        .foregroundColor(AetherColors.oakMedium)
                        .scaleEffect(pulse ? 1.08 : 0.94)
                }

                VStack(spacing: 8) {
                    Text("Rooting Aether V1")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundColor(isDark ? AetherColors.oakCream : AetherColors.warmBlack)
                    Text(message)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(isDark ? AetherColors.warmGray200 : AetherColors.warmGray600)
                        .multilineTextAlignment(.center)
                    Text("First launch can take a while while the model settles into local storage.")
                        .font(.system(size: 12))
                        .foregroundColor(isDark ? AetherColors.warmGray400 : AetherColors.warmGray500)
                        .multilineTextAlignment(.center)
                        .padding(.top, 2)
                }
            }
            .padding(.horizontal, 26)
            .padding(.vertical, 30)
            .frame(maxWidth: 310)
            .background(
                RoundedRectangle(cornerRadius: 30, style: .continuous)
                    .fill(isDark ? AetherColors.warmGray900.opacity(0.92) : Color.white.opacity(0.9))
                    .shadow(color: .black.opacity(0.18), radius: 28, y: 16)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 30, style: .continuous)
                    .stroke(AetherColors.oakPale.opacity(0.35), lineWidth: 1)
            )
        }
        .onAppear {
            withAnimation(.linear(duration: 1.15).repeatForever(autoreverses: false)) {
                rotation = 360
            }
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) {
                pulse = true
            }
        }
    }
}

struct MessageBubble: View {
    let message: ChatMessage
    let isDark: Bool

    var isUser: Bool { message.role == .user }

    var body: some View {
        HStack(alignment: .bottom) {
            if isUser { Spacer(minLength: 60) }
            VStack(alignment: .leading, spacing: 10) {
                if !message.attachments.isEmpty {
                    ForEach(message.attachments) { attachment in
                        MessageAttachmentThumbnail(attachment: attachment)
                    }
                }

                if !message.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    if isUser {
                        Text(message.content)
                            .font(.system(size: 15))
                            .foregroundColor(isDark ? AetherColors.warmGray200 : AetherColors.warmBlack)
                    } else {
                        MarkdownMessageText(message.content)
                            .foregroundColor(isDark ? AetherColors.warmGray200 : AetherColors.warmBlack)
                    }
                }
            }
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
        .textSelection(.enabled)
    }

    var bubbleColor: Color {
        if isUser { return isDark ? AetherColors.oakMedium : AetherColors.oakMedium.opacity(0.85) }
        return isDark ? AetherColors.warmGray800 : Color.white
    }
}

struct MarkdownMessageText: View {
    let content: String

    init(_ content: String) {
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            ForEach(Array(MarkdownBlockParser.parse(content).enumerated()), id: \.offset) { _, block in
                blockView(block)
            }
        }
        .font(.system(size: 15))
    }

    @ViewBuilder
    private func blockView(_ block: MarkdownBlock) -> some View {
        switch block {
        case .heading(let level, let text):
            inlineText(text)
                .font(.system(size: headingSize(for: level), weight: .semibold))
                .padding(.top, level == 1 ? 2 : 4)

        case .paragraph(let text):
            inlineText(text)
                .lineSpacing(4)

        case .bullet(let text):
            HStack(alignment: .firstTextBaseline, spacing: 7) {
                Text("•")
                    .font(.system(size: 14, weight: .bold))
                inlineText(text)
                    .lineSpacing(3)
            }

        case .numbered(let number, let text):
            HStack(alignment: .firstTextBaseline, spacing: 7) {
                Text("\(number).")
                    .font(.system(size: 14, weight: .semibold))
                inlineText(text)
                    .lineSpacing(3)
            }

        case .code(let code):
            ScrollView(.horizontal, showsIndicators: false) {
                Text(code)
                    .font(.system(size: 13, design: .monospaced))
                    .textSelection(.enabled)
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(AetherColors.warmGray100.opacity(0.95))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

        case .table(let rows):
            ScrollView(.horizontal, showsIndicators: false) {
                Text(rows.joined(separator: "\n"))
                    .font(.system(size: 13, design: .monospaced))
                    .lineSpacing(3)
                    .padding(10)
            }
            .background(AetherColors.warmGray100.opacity(0.95))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
    }

    private func inlineText(_ text: String) -> Text {
        let attributed = (try? AttributedString(
            markdown: text,
            options: AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        )) ?? AttributedString(text)
        return Text(attributed)
    }

    private func headingSize(for level: Int) -> CGFloat {
        switch level {
        case 1: return 19
        case 2: return 17
        default: return 16
        }
    }
}

enum MarkdownBlock {
    case heading(level: Int, text: String)
    case paragraph(String)
    case bullet(String)
    case numbered(Int, String)
    case code(String)
    case table([String])
}

enum MarkdownBlockParser {
    static func parse(_ content: String) -> [MarkdownBlock] {
        let lines = content
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .components(separatedBy: "\n")
        var blocks: [MarkdownBlock] = []
        var index = 0

        while index < lines.count {
            let rawLine = lines[index]
            let line = rawLine.trimmingCharacters(in: .whitespaces)

            if line.isEmpty {
                index += 1
                continue
            }

            if line.hasPrefix("```") {
                index += 1
                var codeLines: [String] = []
                while index < lines.count {
                    let next = lines[index]
                    if next.trimmingCharacters(in: .whitespaces).hasPrefix("```") {
                        index += 1
                        break
                    }
                    codeLines.append(next)
                    index += 1
                }
                blocks.append(.code(codeLines.joined(separator: "\n")))
                continue
            }

            if let heading = heading(from: line) {
                blocks.append(.heading(level: heading.level, text: heading.text))
                index += 1
                continue
            }

            if let bullet = bullet(from: line) {
                blocks.append(.bullet(bullet))
                index += 1
                continue
            }

            if let numbered = numbered(from: line) {
                blocks.append(.numbered(numbered.number, numbered.text))
                index += 1
                continue
            }

            if line.hasPrefix("|"), line.hasSuffix("|") {
                var rows: [String] = []
                while index < lines.count {
                    let tableLine = lines[index].trimmingCharacters(in: .whitespaces)
                    guard tableLine.hasPrefix("|"), tableLine.hasSuffix("|") else { break }
                    rows.append(tableLine)
                    index += 1
                }
                blocks.append(.table(rows))
                continue
            }

            var paragraphLines = [line]
            index += 1
            while index < lines.count {
                let next = lines[index].trimmingCharacters(in: .whitespaces)
                if next.isEmpty || next.hasPrefix("```") || heading(from: next) != nil || bullet(from: next) != nil || numbered(from: next) != nil || next.hasPrefix("|") {
                    break
                }
                paragraphLines.append(next)
                index += 1
            }
            blocks.append(.paragraph(paragraphLines.joined(separator: " ")))
        }

        return blocks.isEmpty ? [.paragraph(content)] : blocks
    }

    private static func heading(from line: String) -> (level: Int, text: String)? {
        let level = line.prefix { $0 == "#" }.count
        guard (1...6).contains(level),
              line.dropFirst(level).first == " " else {
            return nil
        }
        return (level, String(line.dropFirst(level + 1)))
    }

    private static func bullet(from line: String) -> String? {
        guard line.hasPrefix("- ") || line.hasPrefix("* ") else { return nil }
        return String(line.dropFirst(2))
    }

    private static func numbered(from line: String) -> (number: Int, text: String)? {
        guard let dot = line.firstIndex(of: ".") else { return nil }
        let numberText = String(line[..<dot])
        guard let number = Int(numberText) else { return nil }
        let rest = line[line.index(after: dot)...].trimmingCharacters(in: .whitespaces)
        guard !rest.isEmpty else { return nil }
        return (number, rest)
    }
}

enum AetherImageNormalizer {
    static let maxDimension: CGFloat = 768
    static let compressionQuality: CGFloat = 0.76

    static func jpegData(from data: Data) -> Data? {
        guard let image = UIImage(data: data) else { return data }
        return jpegData(from: image)
    }

    static func jpegData(from image: UIImage) -> Data? {
        let size = resizedSize(for: image.size)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        let rendered = renderer.image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        return rendered.jpegData(compressionQuality: compressionQuality)
    }

    private static func resizedSize(for size: CGSize) -> CGSize {
        let longest = max(size.width, size.height)
        guard longest > maxDimension, longest > 0 else {
            return CGSize(width: max(size.width, 1), height: max(size.height, 1))
        }
        let scale = maxDimension / longest
        return CGSize(width: max(size.width * scale, 1), height: max(size.height * scale, 1))
    }
}

struct MessageAttachmentThumbnail: View {
    let attachment: ChatAttachment

    var body: some View {
        if let image = UIImage(data: attachment.data) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: 180, height: 140)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(Color.white.opacity(0.3), lineWidth: 1)
                )
        }
    }
}

struct AttachmentTray: View {
    @Binding var attachments: [ChatAttachment]

    var body: some View {
        if !attachments.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(attachments) { attachment in
                        ZStack(alignment: .topTrailing) {
                            MessageAttachmentThumbnail(attachment: attachment)
                                .frame(width: 88, height: 68)
                                .clipped()
                            Button {
                                attachments.removeAll { $0.id == attachment.id }
                            } label: {
                                Image(systemName: "xmark")
                                    .font(.system(size: 10, weight: .bold))
                                    .foregroundColor(.white)
                                    .frame(width: 22, height: 22)
                                    .background(Color.black.opacity(0.55))
                                    .clipShape(Circle())
                            }
                            .offset(x: 6, y: -6)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 10)
            }
        }
    }
}

struct CameraCaptureView: UIViewControllerRepresentable {
    let onComplete: (Data?) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.delegate = context.coordinator
        picker.sourceType = UIImagePickerController.isSourceTypeAvailable(.camera) ? .camera : .photoLibrary
        picker.cameraCaptureMode = .photo
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onComplete: onComplete)
    }

    final class Coordinator: NSObject, UINavigationControllerDelegate, UIImagePickerControllerDelegate {
        let onComplete: (Data?) -> Void

        init(onComplete: @escaping (Data?) -> Void) {
            self.onComplete = onComplete
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onComplete(nil)
        }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            let image = info[.editedImage] as? UIImage ?? info[.originalImage] as? UIImage
            onComplete(image.flatMap { AetherImageNormalizer.jpegData(from: $0) })
        }
    }
}

struct InputBar: View {
    @Binding var text: String
    @Binding var attachments: [ChatAttachment]
    @Binding var selectedPhotoItem: PhotosPickerItem?
    let isSending: Bool
    let isDark: Bool
    var focused: FocusState<Bool>.Binding
    let onCamera: () -> Void
    let onStop: () -> Void
    let onSend: () -> Void

    var canSend: Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !attachments.isEmpty
    }

    var body: some View {
        VStack(spacing: 0) {
            AttachmentTray(attachments: $attachments)

            HStack(spacing: 10) {
                PhotosPicker(selection: $selectedPhotoItem, matching: .images) {
                    Image(systemName: "photo.on.rectangle.angled")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(AetherColors.oakMedium)
                        .frame(width: 36, height: 44)
                        .contentShape(Rectangle())
                }
                .disabled(isSending)

                Button(action: onCamera) {
                    Image(systemName: "camera.fill")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(AetherColors.oakMedium)
                        .frame(width: 36, height: 44)
                        .contentShape(Rectangle())
                }
                .disabled(isSending)

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

                Button(action: isSending ? onStop : onSend) {
                    ZStack {
                        Circle()
                            .fill(!canSend && !isSending ? AetherColors.warmGray200 : AetherColors.oakMedium)
                            .frame(width: 44, height: 44)
                        if isSending {
                            Image(systemName: "stop.fill")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundColor(.white)
                        } else {
                            Image(systemName: "arrow.up")
                                .font(.system(size: 17, weight: .semibold))
                                .foregroundColor(.white)
                        }
                    }
                }
                .disabled(!canSend && !isSending)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
        .background(isDark ? AetherColors.warmGray900 : Color.white)
        .shadow(color: .black.opacity(0.08), radius: 12, y: -2)
        .animation(.easeInOut(duration: 0.18), value: focused.wrappedValue)
        .animation(.easeInOut(duration: 0.18), value: attachments.count)
    }
}

struct TypingIndicator: View {
    let message: String?
    let isDark: Bool

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 7) {
                if let message, !message.isEmpty {
                    Text(message)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(isDark ? AetherColors.warmGray200 : AetherColors.warmGray600)
                }
                TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { timeline in
                    let time = timeline.date.timeIntervalSinceReferenceDate
                    HStack(spacing: 6) {
                        ForEach(0..<3, id: \.self) { index in
                            let phase = time * 5.2 - Double(index) * 0.62
                            let lift = max(0, sin(phase)) * -7
                            Circle()
                                .fill(isDark ? AetherColors.warmGray400 : AetherColors.warmGray500)
                                .frame(width: 8, height: 8)
                                .offset(y: lift)
                                .scaleEffect(1 + max(0, sin(phase)) * 0.22)
                                .opacity(0.62 + max(0, sin(phase)) * 0.38)
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(isDark ? AetherColors.warmGray800 : Color.white)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .padding(.leading, 16)
            Spacer()
        }
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
