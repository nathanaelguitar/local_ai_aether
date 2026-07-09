package com.nathanaelguitar.canopychat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.nathanaelguitar.canopychat.ui.ChatScreen
import com.nathanaelguitar.canopychat.ui.ConversationListScreen
import com.nathanaelguitar.canopychat.ui.SettingsScreen
import com.nathanaelguitar.canopychat.ui.WelcomeScreen
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val state: AppState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CanopyNavHost(state)
            }
        }
    }
}

private sealed interface Screen {
    data object Welcome : Screen
    data object Conversations : Screen
    data object Settings : Screen
    data class Chat(val conversationId: UUID) : Screen
}

@Composable
private fun CanopyNavHost(state: AppState) {
    val isDark by state.isDarkTheme.collectAsState()
    var entered by rememberSaveable { mutableStateOf(false) }
    var screen by androidx.compose.runtime.remember { mutableStateOf<Screen>(if (entered) Screen.Conversations else Screen.Welcome) }

    // System back from Chat/Settings returns to the conversation list instead of exiting.
    androidx.activity.compose.BackHandler(enabled = screen is Screen.Chat || screen is Screen.Settings) {
        screen = Screen.Conversations
    }

    when (val current = screen) {
        Screen.Welcome -> WelcomeScreen(isDark = isDark) {
            entered = true
            screen = Screen.Conversations
        }
        Screen.Conversations -> ConversationListScreen(
            state = state,
            onOpen = { screen = Screen.Chat(it) },
            onSettings = { screen = Screen.Settings }
        )
        Screen.Settings -> SettingsScreen(state = state, onBack = { screen = Screen.Conversations })
        is Screen.Chat -> ChatScreen(
            state = state,
            conversationId = current.conversationId,
            onBack = { screen = Screen.Conversations }
        )
    }
}
