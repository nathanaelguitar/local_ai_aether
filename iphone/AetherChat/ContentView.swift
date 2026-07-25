import SwiftUI

struct ContentView: View {
    @StateObject private var state = AppState()
    @StateObject private var subscription = CanopySubscriptionManager()
    @State private var showConversations = false
    @State private var showingContributorDisclosure = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            Group {
                if showConversations {
                    if subscription.hasPremiumAccess {
                        ConversationListView()
                            .environmentObject(state)
                            .environmentObject(subscription)
                            .transition(.asymmetric(
                                insertion: .move(edge: .trailing),
                                removal: .move(edge: .leading)
                            ))
                    } else {
                        PaywallView()
                            .environmentObject(subscription)
                            .transition(.opacity)
                    }
                } else {
                    WelcomeView(onEnter: {
                        if CanopyContributorProgram.isContributorBuild,
                           !CanopyContributorProgram.hasAcknowledgedDisclosure {
                            showingContributorDisclosure = true
                        } else {
                            enterApp()
                        }
                    })
                    .transition(.asymmetric(
                        insertion: .move(edge: .leading),
                        removal: .move(edge: .leading)
                    ))
                }
            }
            .animation(.easeInOut(duration: 0.4), value: showConversations)

            if showingContributorDisclosure {
                ContributorConsentOverlay(
                    onAgree: {
                        CanopyContributorProgram.acknowledgeDisclosure()
                        showingContributorDisclosure = false
                        enterApp()
                    },
                    onDismiss: {
                        showingContributorDisclosure = false
                    }
                )
                .transition(.opacity.combined(with: .scale(scale: 0.95)))
                .zIndex(2)
            }
        }
        .animation(.spring(response: 0.32, dampingFraction: 0.86), value: showingContributorDisclosure)
        .onChange(of: scenePhase) { _, phase in
            state.appIsActive = phase == .active
            if phase == .active {
                AetherBetaTelemetry.shared.flushPendingBatch()
            }
        }
    }

    private func enterApp() {
        withAnimation(.easeInOut(duration: 0.4)) {
            showConversations = true
        }
    }
}

/// Compact contributor consent card. Tap outside or "Not now" to dismiss without
/// consenting; only the explicit agree button acknowledges the disclosure.
private struct ContributorConsentOverlay: View {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.openURL) private var openURL

    let onAgree: () -> Void
    let onDismiss: () -> Void

    private var isDark: Bool { colorScheme == .dark }

    private var panelBackground: Color {
        isDark ? AetherColors.warmGray900 : AetherColors.oakCream
    }

    private var primaryText: Color {
        isDark ? AetherColors.oakCream : AetherColors.warmBlack
    }

    private var secondaryText: Color {
        isDark ? AetherColors.warmGray400 : AetherColors.warmGray600
    }

    var body: some View {
        ZStack {
            Color.black.opacity(isDark ? 0.6 : 0.4)
                .ignoresSafeArea()
                .onTapGesture(perform: onDismiss)
                .accessibilityLabel("Dismiss contributor consent")
                .accessibilityAddTraits(.isButton)

            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 12) {
                    ZStack {
                        Circle()
                            .fill(AetherColors.forestMedium.opacity(isDark ? 0.35 : 0.16))
                            .frame(width: 44, height: 44)
                        Image(systemName: "leaf.fill")
                            .font(.system(size: 19, weight: .semibold))
                            .foregroundStyle(AetherColors.forestMedium)
                    }
                    .accessibilityHidden(true)

                    VStack(alignment: .leading, spacing: 2) {
                        Text("CONTRIBUTOR BETA")
                            .font(.system(size: 10, weight: .bold, design: .rounded))
                            .tracking(1.1)
                            .foregroundStyle(AetherColors.forestMedium)
                        Text("Help improve CanopyChat")
                            .font(.system(size: 20, weight: .semibold, design: .serif))
                            .foregroundStyle(primaryText)
                            .minimumScaleFactor(0.8)
                    }
                }

                VStack(alignment: .leading, spacing: 10) {
                    ConsentPoint(
                        icon: "arrow.up.doc.fill",
                        text: "Selected prompts, responses, failures, corrections, regenerations, and comparison samples may be collected to improve the model.",
                        secondaryText: secondaryText
                    )
                    ConsentPoint(
                        icon: "lock.fill",
                        text: "Attachments and full chat histories are never included.",
                        secondaryText: secondaryText
                    )
                    ConsentPoint(
                        icon: "slider.horizontal.3",
                        text: "Withdraw anytime in Settings — unsent contributor data is deleted immediately.",
                        secondaryText: secondaryText
                    )
                }
                .padding(.top, 16)

                Button {
                    openURL(CanopyLegal.privacyPolicyURL)
                } label: {
                    Label("Contributor Privacy Policy", systemImage: "arrow.up.right")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(AetherColors.forestMedium)
                }
                .buttonStyle(OakQuietButtonStyle())
                .padding(.top, 12)

                Button(action: onAgree) {
                    Text("I Understand — Continue")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .frame(height: 50)
                        .background(
                            LinearGradient(
                                colors: [AetherColors.forestMedium, Color(hex: "2F5233")],
                                startPoint: .top,
                                endPoint: .bottom
                            )
                        )
                        .clipShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
                        .shadow(color: Color(hex: "2F5233").opacity(0.4), radius: 8, y: 3)
                }
                .buttonStyle(OakPrimaryButtonStyle())
                .accessibilityHint("Agrees to share selected interactions and opens CanopyChat")
                .padding(.top, 18)

                Button("Not now", action: onDismiss)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(secondaryText)
                    .buttonStyle(OakQuietButtonStyle())
                    .frame(maxWidth: .infinity)
                    .padding(.top, 10)
            }
            .padding(22)
            .frame(maxWidth: 340)
            .background(panelBackground)
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .strokeBorder(
                        isDark ? Color.white.opacity(0.1) : AetherColors.oakPale.opacity(0.7),
                        lineWidth: 1
                    )
            )
            .shadow(color: AetherColors.oakDark.opacity(isDark ? 0.6 : 0.25), radius: 30, y: 12)
            .padding(.horizontal, 28)
            .accessibilityAddTraits(.isModal)
        }
    }
}

private struct ConsentPoint: View {
    @Environment(\.colorScheme) private var colorScheme
    let icon: String
    let text: String
    let secondaryText: Color

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(AetherColors.oakMedium)
                .frame(width: 24, height: 24)
                .background(AetherColors.oakPale.opacity(colorScheme == .dark ? 0.14 : 0.45))
                .clipShape(Circle())
                .accessibilityHidden(true)

            Text(text)
                .font(.system(size: 13, weight: .regular))
                .foregroundStyle(secondaryText)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    ContentView()
}
