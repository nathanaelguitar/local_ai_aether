import SwiftUI

struct ContentView: View {
    @StateObject private var state = AppState()
    @State private var showConversations = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            if showConversations {
                ConversationListView()
                    .environmentObject(state)
                    .transition(.asymmetric(
                        insertion: .move(edge: .trailing),
                        removal: .move(edge: .leading)
                    ))
            } else {
                WelcomeView(onEnter: {
                    withAnimation(.easeInOut(duration: 0.4)) {
                        showConversations = true
                    }
                })
                .transition(.asymmetric(
                    insertion: .move(edge: .leading),
                    removal: .move(edge: .leading)
                ))
            }
        }
        .animation(.easeInOut(duration: 0.4), value: showConversations)
        .task {
            await AetherNotifications.requestAuthorization()
        }
        .onChange(of: scenePhase) { _, phase in
            state.appIsActive = phase == .active
        }
    }
}

#Preview {
    ContentView()
}
