import SwiftUI

struct ContentView: View {
    @StateObject private var state = AppState()
    @State private var showConversations = false

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
    }
}

#Preview {
    ContentView()
}
