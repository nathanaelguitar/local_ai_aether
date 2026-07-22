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
        .confirmationDialog(
            "Join the CanopyChat Contributor Beta",
            isPresented: $showingContributorDisclosure,
            titleVisibility: .visible
        ) {
            Button("I understand — continue") {
                CanopyContributorProgram.acknowledgeDisclosure()
                enterApp()
            }
            Button("Not now", role: .cancel) {}
        } message: {
            Text(CanopyContributorProgram.disclosure)
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

#Preview {
    ContentView()
}
