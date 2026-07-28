package com.nathanaelguitar.canopychat

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.painterResource
import com.nathanaelguitar.canopychat.core.CanopyNotifications
import com.nathanaelguitar.canopychat.core.CanopySubscriptionManager
import com.nathanaelguitar.canopychat.ui.ChatScreen
import com.nathanaelguitar.canopychat.ui.ConversationListScreen
import com.nathanaelguitar.canopychat.ui.OakColors
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
            val isDark by state.isDarkTheme.collectAsState()
            com.nathanaelguitar.canopychat.ui.CanopyTheme(isDark = isDark) {
                CanopyNavHost(state)
            }
        }
    }

    // Mirrors AppState.appIsActive on iOS, which gates the background-reply notification.
    override fun onStart() {
        super.onStart()
        state.setAppIsActive(true)
        // Mirrors ContentView.onChange(of: scenePhase) — a queued contributor batch
        // gets another upload opportunity when the app becomes active.
        state.flushContributorTelemetry()
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
    val hasPremium by subscription.hasPremiumAccess.collectAsState()
    var entered by rememberSaveable { mutableStateOf(false) }
    var screen by remember { mutableStateOf<Screen>(if (entered) Screen.Conversations else Screen.Welcome) }
    var showingContributorDisclosure by remember { mutableStateOf(false) }

    // System back from Chat/Settings returns to the conversation list instead of exiting.
    androidx.activity.compose.BackHandler(enabled = screen is Screen.Chat || screen is Screen.Settings || screen is Screen.Paywall) {
        screen = Screen.Conversations
    }

    fun enterApp() {
        entered = true
        screen = Screen.Conversations
    }

    Box {
        // Port of ContentView on iOS: after Welcome, non-subscribers are hard-gated to
        // the paywall; subscribers land on the conversation list.
        androidx.compose.animation.Crossfade(
            targetState = screen,
            animationSpec = androidx.compose.animation.core.tween(400),
            label = "nav"
        ) { current ->
            when (current) {
                Screen.Welcome -> WelcomeScreen(isDark = isDark) {
                    if (com.nathanaelguitar.canopychat.core.ContributorProgram.isContributorBuild &&
                        !com.nathanaelguitar.canopychat.core.ContributorProgram.hasAcknowledgedDisclosure(context)
                    ) {
                        showingContributorDisclosure = true
                    } else {
                        enterApp()
                    }
                }
                Screen.Conversations -> {
                    if (hasPremium) {
                        ConversationListScreen(
                            state = state,
                            onOpen = { screen = Screen.Chat(it) },
                            onSettings = { screen = Screen.Settings }
                        )
                    } else {
                        PaywallScreen(
                            subscription = subscription,
                            isDark = isDark,
                            gated = true,
                            onBack = null
                        )
                    }
                }
                Screen.Settings -> SettingsScreen(
                    state = state,
                    subscription = subscription,
                    onBack = { screen = Screen.Conversations },
                    onSubscription = { screen = Screen.Paywall }
                )
                Screen.Paywall -> PaywallScreen(
                    subscription = subscription,
                    isDark = isDark,
                    gated = false,
                    onBack = { screen = Screen.Settings }
                )
                is Screen.Chat -> {
                    if (hasPremium) {
                        ChatScreen(
                            state = state,
                            conversationId = current.conversationId,
                            onBack = { screen = Screen.Conversations },
                            onNewChat = { screen = Screen.Chat(it) }
                        )
                    } else {
                        PaywallScreen(
                            subscription = subscription,
                            isDark = isDark,
                            gated = true,
                            onBack = null
                        )
                    }
                }
            }
        }

        // Port of ContributorConsentOverlay in iphone/AetherChat/ContentView.swift.
        if (showingContributorDisclosure) {
            ContributorConsentOverlay(
                isDark = isDark,
                onAgree = {
                    com.nathanaelguitar.canopychat.core.ContributorProgram.acknowledgeDisclosure(context)
                    showingContributorDisclosure = false
                    enterApp()
                },
                onDismiss = { showingContributorDisclosure = false }
            )
        }
    }
}

/** Port of ContributorConsentOverlay in iphone/AetherChat/ContentView.swift. */
@Composable
private fun ContributorConsentOverlay(isDark: Boolean, onAgree: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(if (isDark) OakColors.warmGray900 else OakColors.oakCream)
                .border(
                    1.dp,
                    if (isDark) Color.White.copy(alpha = 0.1f) else OakColors.oakPale.copy(alpha = 0.7f),
                    RoundedCornerShape(24.dp)
                )
                .padding(22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(OakColors.forestMedium.copy(alpha = if (isDark) 0.35f else 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.ic_leaf_fill),
                        contentDescription = null,
                        tint = OakColors.forestMedium,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "CONTRIBUTOR BETA",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp,
                        color = OakColors.forestMedium
                    )
                    Text(
                        "Help improve CanopyChat",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Serif,
                        color = if (isDark) OakColors.oakCream else OakColors.warmBlack
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            ConsentPoint("Selected prompts, responses, failures, corrections, regenerations, and comparison samples may be collected to improve the model.", isDark)
            ConsentPoint("Attachments and full chat histories are never included.", isDark)
            ConsentPoint("Withdraw anytime in Settings — unsent contributor data is deleted immediately.", isDark)
            Spacer(Modifier.height(12.dp))
            Text(
                "Contributor Privacy Policy",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = OakColors.forestMedium,
                modifier = Modifier.clickable {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(com.nathanaelguitar.canopychat.core.CanopyLegal.PRIVACY_POLICY_URL)
                    )
                    context.startActivity(intent)
                }
            )
            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(
                        Brush.verticalGradient(listOf(OakColors.forestMedium, OakColors.forestDark))
                    )
                    .clickable(onClick = onAgree),
                contentAlignment = Alignment.Center
            ) {
                Text("I Understand — Continue", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Not now",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isDark) OakColors.warmGray400 else OakColors.warmGray600,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 6.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Port of ConsentPoint in iphone/AetherChat/ContentView.swift. */
@Composable
private fun ConsentPoint(text: String, isDark: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(OakColors.oakPale.copy(alpha = if (isDark) 0.14f else 0.45f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = OakColors.oakMedium,
                modifier = Modifier.size(12.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = if (isDark) OakColors.warmGray400 else OakColors.warmGray600
        )
    }
}
