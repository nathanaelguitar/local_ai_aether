import SwiftUI

struct OnboardingView: View {
    let onPreload: (@escaping @Sendable (String?) async -> Void) async -> Void
    let onComplete: (String, String) -> Void

    @State private var step = 0
    @State private var userName = ""
    @State private var assistantName = ""
    @State private var preloadStatus = "Preparing Aether V1"
    @State private var modelReady = false
    @State private var visible = false
    @FocusState private var fieldFocused: Bool

    var body: some View {
        OakBackground {
            VStack(spacing: 0) {
                Spacer(minLength: 72)

                VStack(spacing: 18) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 30, style: .continuous)
                            .fill(AetherColors.oakMedium)
                            .frame(width: 94, height: 94)
                        Image(systemName: "leaf.fill")
                            .font(.system(size: 44, weight: .semibold))
                            .foregroundColor(AetherColors.oakCream)
                    }

                    Text("Aether")
                        .font(.system(size: 44, weight: .thin, design: .serif))
                        .foregroundColor(AetherColors.oakDark)
                }
                .opacity(visible ? 1 : 0)
                .offset(y: visible ? 0 : 18)

                Spacer(minLength: 42)

                VStack(alignment: .leading, spacing: 18) {
                    Text(question)
                        .font(.system(size: 26, weight: .semibold, design: .serif))
                        .foregroundColor(AetherColors.warmBlack)
                        .fixedSize(horizontal: false, vertical: true)

                    Text(subtitle)
                        .font(.system(size: 14))
                        .foregroundColor(AetherColors.warmGray600)

                    TextField(placeholder, text: step == 0 ? $userName : $assistantName)
                        .font(.system(size: 18, weight: .medium))
                        .foregroundColor(AetherColors.warmBlack)
                        .tint(AetherColors.oakMedium)
                        .textInputAutocapitalization(.words)
                        .padding(.horizontal, 16)
                        .frame(height: 54)
                        .background(Color.white.opacity(0.92))
                        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                        .focused($fieldFocused)

                    Button(action: advance) {
                        Text(step == 0 ? "Continue" : "Enter Aether")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 54)
                            .background(canAdvance ? AetherColors.oakMedium : AetherColors.warmGray400)
                            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    }
                    .disabled(!canAdvance)
                }
                .padding(22)
                .background(Color.white.opacity(0.58))
                .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 28, style: .continuous)
                        .stroke(AetherColors.oakPale.opacity(0.38), lineWidth: 1)
                )
                .padding(.horizontal, 24)
                .opacity(visible ? 1 : 0)
                .offset(y: visible ? 0 : 24)
                .environment(\.colorScheme, .light)

                Spacer(minLength: 34)

                HStack(spacing: 10) {
                    ProgressView()
                        .tint(AetherColors.oakMedium)
                        .opacity(modelReady ? 0 : 1)
                    Text(modelReady ? "Aether V1 is ready" : preloadStatus)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(AetherColors.oakMedium)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 11)
                .background(Color.white.opacity(0.72))
                .clipShape(Capsule())
                .padding(.bottom, 40)
                .opacity(visible ? 1 : 0)
            }
        }
        .onAppear {
            withAnimation(.easeOut(duration: 0.45)) {
                visible = true
            }
            fieldFocused = true
        }
        .task {
            await onPreload { message in
                await MainActor.run {
                    if let message {
                        preloadStatus = message
                    } else {
                        modelReady = true
                        preloadStatus = "Aether V1 is ready"
                    }
                }
            }
        }
    }

    private var question: String {
        step == 0 ? "What should I call you?" : "What do you want to call me?"
    }

    private var subtitle: String {
        step == 0
            ? "Use a first name, nickname, or whatever feels natural."
            : "This becomes the default assistant name in new conversations."
    }

    private var placeholder: String {
        step == 0 ? "Your name or nickname" : "Assistant name"
    }

    private var canAdvance: Bool {
        let value = step == 0 ? userName : assistantName
        return !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func advance() {
        if step == 0 {
            withAnimation(.easeInOut(duration: 0.22)) {
                step = 1
            }
            fieldFocused = true
        } else {
            onComplete(userName, assistantName)
        }
    }
}
