import SwiftUI

struct ContentView: View {
    @StateObject private var state = AppState()
    @StateObject private var subscription = CanopySubscriptionManager()
    @State private var showConversations = false
    @State private var showingContributorDisclosure = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
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
        .sheet(isPresented: $showingContributorDisclosure) {
            ContributorDisclosureSheet {
                CanopyContributorProgram.acknowledgeDisclosure()
                enterApp()
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(32)
        }
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

private struct ContributorDisclosureSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.openURL) private var openURL

    let onContinue: () -> Void

    private var panelBackground: Color {
        colorScheme == .dark ? AetherColors.warmGray900 : AetherColors.oakCream
    }

    private var primaryText: Color {
        colorScheme == .dark ? AetherColors.oakCream : AetherColors.warmBlack
    }

    private var secondaryText: Color {
        colorScheme == .dark ? AetherColors.warmGray400 : AetherColors.warmGray600
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 14) {
                    ZStack {
                        Circle()
                            .fill(AetherColors.forestMedium.opacity(colorScheme == .dark ? 0.35 : 0.16))
                            .frame(width: 52, height: 52)
                        Image(systemName: "leaf.fill")
                            .font(.system(size: 23, weight: .semibold))
                            .foregroundStyle(AetherColors.forestMedium)
                    }

                    VStack(alignment: .leading, spacing: 3) {
                        Text("CANOPYCHAT CONTRIBUTOR BETA")
                            .font(.system(size: 11, weight: .bold, design: .rounded))
                            .tracking(1.1)
                            .foregroundStyle(AetherColors.forestMedium)
                        Text("Help shape the next model")
                            .font(.system(size: 26, weight: .semibold, design: .serif))
                            .foregroundStyle(primaryText)
                    }
                }

                Text("You get early access while helping us find where CanopyChat can be more accurate, useful, and reliable.")
                    .font(.system(size: 16, weight: .regular))
                    .foregroundStyle(secondaryText)
                    .lineSpacing(3)
                    .padding(.top, 20)

                VStack(spacing: 10) {
                    DisclosureDetailRow(
                        icon: "arrow.up.doc.fill",
                        title: "Selected examples are shared",
                        detail: "We collect failures, corrections, regenerations, web-search signals, and a small comparison sample."
                    )
                    DisclosureDetailRow(
                        icon: "lock.fill",
                        title: "Not your entire history",
                        detail: "Attachments and full chat histories are not included in contributor uploads."
                    )
                    DisclosureDetailRow(
                        icon: "slider.horizontal.3",
                        title: "You stay in control",
                        detail: "You can stop contributing in Settings at any time. Unsent contributor data is deleted."
                    )
                }
                .padding(.top, 22)

                Text("By continuing, you agree to share selected interactions for model evaluation and improvement during this Contributor Beta.")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(secondaryText)
                    .lineSpacing(2)
                    .padding(.top, 20)

                Button {
                    openURL(CanopyLegal.privacyPolicyURL)
                } label: {
                    Label("Read the Contributor Privacy Policy", systemImage: "arrow.up.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(AetherColors.forestMedium)
                }
                .padding(.top, 10)

                Button {
                    dismiss()
                    DispatchQueue.main.async {
                        onContinue()
                    }
                } label: {
                    Text("Join the Contributor Beta")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .frame(height: 56)
                        .background(AetherColors.oakMedium)
                        .clipShape(RoundedRectangle(cornerRadius: 17, style: .continuous))
                }
                .padding(.top, 26)

                Button("Not now") {
                    dismiss()
                }
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(secondaryText)
                .frame(maxWidth: .infinity)
                .padding(.top, 16)
                .padding(.bottom, 8)
            }
            .padding(.horizontal, 24)
            .padding(.top, 18)
        }
        .background(panelBackground)
    }
}

private struct DisclosureDetailRow: View {
    @Environment(\.colorScheme) private var colorScheme
    let icon: String
    let title: String
    let detail: String

    var body: some View {
        HStack(alignment: .top, spacing: 13) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(AetherColors.oakMedium)
                .frame(width: 34, height: 34)
                .background(AetherColors.oakPale.opacity(colorScheme == .dark ? 0.14 : 0.45))
                .clipShape(Circle())

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(colorScheme == .dark ? AetherColors.oakCream : AetherColors.warmBlack)
                Text(detail)
                    .font(.system(size: 13, weight: .regular))
                    .foregroundStyle(colorScheme == .dark ? AetherColors.warmGray400 : AetherColors.warmGray600)
                    .lineSpacing(2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(colorScheme == .dark ? AetherColors.warmGray800.opacity(0.8) : Color.white.opacity(0.56))
        .overlay {
            RoundedRectangle(cornerRadius: 17, style: .continuous)
                .stroke(AetherColors.oakPale.opacity(colorScheme == .dark ? 0.12 : 0.65), lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 17, style: .continuous))
    }
}

#Preview {
    ContentView()
}
