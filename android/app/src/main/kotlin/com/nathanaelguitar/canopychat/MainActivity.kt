package com.nathanaelguitar.canopychat

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.nathanaelguitar.canopychat.core.CanopyNotifications
import com.nathanaelguitar.canopychat.core.CanopySubscriptionManager
import com.nathanaelguitar.canopychat.ui.ChatScreen
import com.nathanaelguitar.canopychat.ui.ConversationListScreen
import com.nathanaelguitar.canopychat.ui.SettingsScreen
import com.nathanaelguitar.canopychat.ui.PaywallScreen
import com.nathanaelguitar.canopychat.ui.WelcomeScreen
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val state: AppState by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opt in explicitly so the window stops resizing itself for the keyboard; the
        // Compose layouts consume the IME inset themselves via imePadding().
        enableEdgeToEdge()
        CanopyNotifications.ensureChannel(this)
        requestNotificationPermissionIfNeeded()
        setContent {
            MaterialTheme {
                CanopyNavHost(state)
            }
        }
    }

    // Mirrors AppState.appIsActive on iOS, which gates the background-reply notification.
    override fun onStart() {
        super.onStart()
        state.setAppIsActive(true)
    }

    override fun onStop() {
        super.onStop()
        state.setAppIsActive(false)
    }

    /** iOS asks via UNUserNotificationCenter.requestAuthorization; Android needs a runtime grant on 13+. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (CanopyNotifications.hasPermission(this)) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private sealed interface Screen {
    data object Welcome : Screen
    data object Conversations : Screen
    data object Settings : Screen
    data object Paywall : Screen
    data class Chat(val conversationId: UUID) : Screen
}

@Composable
private fun CanopyNavHost(state: AppState) {
    val context = LocalContext.current
    val subscription = remember { CanopySubscriptionManager(context) }
    val isDark by state.isDarkTheme.collectAsState()
    var entered by rememberSaveable { mutableStateOf(false) }
    var screen by remember { mutableStateOf<Screen>(if (entered) Screen.Conversations else Screen.Welcome) }

    // System back from Chat/Settings returns to the conversation list instead of exiting.
    androidx.activity.compose.BackHandler(enabled = screen is Screen.Chat || screen is Screen.Settings || screen is Screen.Paywall) {
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
        Screen.Settings -> SettingsScreen(
            state = state,
            subscription = subscription,
            onBack = { screen = Screen.Conversations },
            onSubscription = { screen = Screen.Paywall }
        )
        Screen.Paywall -> PaywallScreen(
            subscription = subscription,
            isDark = isDark,
            onBack = { screen = Screen.Settings }
        )
        is Screen.Chat -> ChatScreen(
            state = state,
            conversationId = current.conversationId,
            onBack = { screen = Screen.Conversations }
        )
    }
}
