package com.nathanaelguitar.canopychat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import com.nathanaelguitar.canopychat.AppState
import com.nathanaelguitar.canopychat.core.Conversation
import com.nathanaelguitar.canopychat.core.InferenceProvider
import com.nathanaelguitar.canopychat.core.MessageRole
import java.util.UUID

// Compose counterparts of WelcomeView, ConversationListView, ChatView, and SettingsView
// from the iphone/AetherChat SwiftUI app. Deliberately compact for the first Android cut.

@Composable
fun WelcomeScreen(isDark: Boolean, onEnter: () -> Unit) {
    OakBackground(isDark = isDark) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .background(OakColors.oakMedium, RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("🌳", fontSize = 48.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "CanopyChat",
                fontSize = 42.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.Serif,
                color = if (isDark) OakColors.oakCream else OakColors.oakDark
            )
            Text(
                "Rooted Intelligence",
                fontSize = 18.sp,
                fontFamily = FontFamily.Serif,
                color = OakColors.oakLight
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Private conversations that stay close.\nOn-device intelligence, built to tread lightly.",
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                color = if (isDark) OakColors.warmGray400 else OakColors.warmGray600
            )
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = onEnter,
                colors = ButtonDefaults.buttonColors(containerColor = OakColors.oakMedium),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Enter Your Grove", fontSize = 17.sp, color = Color.White)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    state: AppState,
    onOpen: (UUID) -> Unit,
    onSettings: () -> Unit
) {
    val conversations by state.conversations.collectAsState()
    val isDark by state.isDarkTheme.collectAsState()

    OakBackground(isDark = isDark) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Conversations",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Serif,
                    color = if (isDark) OakColors.oakCream else OakColors.oakDark,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = OakColors.oakMedium)
                }
                IconButton(onClick = {
                    val id = state.createConversation("Untitled", state.availableWorkspaces.first(), state.availablePersonas.first())
                    onOpen(id)
                }) {
                    Icon(Icons.Filled.Add, contentDescription = "New conversation", tint = OakColors.oakMedium)
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    ConversationRow(conversation, isDark) { onOpen(conversation.id) }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, isDark: Boolean, onClick: () -> Unit) {
    val cardColor = if (isDark) OakColors.warmGray900.copy(alpha = 0.78f) else Color.White.copy(alpha = 0.74f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(cardColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                conversation.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isDark) OakColors.oakCream else OakColors.oakDark,
                modifier = Modifier.weight(1f)
            )
            if (conversation.isPinned) Text("📌", fontSize = 12.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            conversation.previewText.ifEmpty { "No messages yet" },
            fontSize = 13.sp,
            maxLines = 2,
            color = OakColors.warmGray500
        )
    }
}

@Composable
fun ChatScreen(state: AppState, conversationId: UUID, onBack: () -> Unit) {
    val conversations by state.conversations.collectAsState()
    val isDark by state.isDarkTheme.collectAsState()
    val isSending by state.isSending.collectAsState()
    val status by state.generationStatus.collectAsState()
    val conversation = conversations.firstOrNull { it.id == conversationId }
    var input by remember { mutableStateOf("") }

    OakBackground(isDark = isDark) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = OakColors.oakMedium)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        conversation?.title ?: "Chat",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDark) OakColors.oakCream else OakColors.oakDark
                    )
                    Text(
                        "with ${conversation?.persona?.name ?: "Canopy"}",
                        fontSize = 11.sp,
                        color = OakColors.oakMedium
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(conversation?.messages ?: emptyList(), key = { it.id }) { message ->
                    MessageBubble(
                        content = message.content,
                        isUser = message.role == MessageRole.USER,
                        isDark = isDark
                    )
                }
                if (isSending) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = OakColors.oakMedium
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(status ?: "Composing a response", fontSize = 13.sp, color = OakColors.warmGray500)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message CanopyChat") },
                    shape = RoundedCornerShape(20.dp),
                    maxLines = 5
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val text = input.trim()
                        if (text.isNotEmpty() && !isSending) {
                            state.sendMessage(conversationId, text)
                            input = ""
                        }
                    },
                    enabled = input.isNotBlank() && !isSending,
                    modifier = Modifier
                        .size(48.dp)
                        .background(OakColors.oakMedium, CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(content: String, isUser: Boolean, isDark: Boolean) {
    val bubbleColor = when {
        isUser -> OakColors.oakMedium
        isDark -> OakColors.warmGray900.copy(alpha = 0.85f)
        else -> Color.White.copy(alpha = 0.85f)
    }
    val textColor = when {
        isUser -> Color.White
        isDark -> OakColors.oakCream
        else -> OakColors.oakDark
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        if (isUser) Spacer(Modifier.weight(0.2f))
        Box(
            modifier = Modifier
                .weight(0.8f, fill = false)
                .background(bubbleColor, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(content, color = textColor, fontSize = 15.sp)
        }
        if (!isUser) Spacer(Modifier.weight(0.2f))
    }
}

@Composable
fun SettingsScreen(state: AppState, onBack: () -> Unit) {
    val isDark by state.isDarkTheme.collectAsState()
    val provider by state.inferenceProvider.collectAsState()
    val endpoint by state.apiEndpoint.collectAsState()
    var endpointDraft by remember(endpoint) { mutableStateOf(endpoint) }

    OakBackground(isDark = isDark) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = OakColors.oakMedium)
                }
                Text(
                    "Settings",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDark) OakColors.oakCream else OakColors.oakDark
                )
            }

            SettingsCard(isDark) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Dark Mode", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("Oak-toned dark theme", fontSize = 12.sp, color = OakColors.warmGray500)
                    }
                    Switch(checked = isDark, onCheckedChange = { state.setDarkTheme(it) })
                }
            }

            SettingsCard(isDark) {
                Column {
                    Text("Inference", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "On-device llama.cpp is not wired up on Android yet; the Backend provider talks to any OpenAI-compatible endpoint (see android/README.md).",
                        fontSize = 12.sp,
                        color = OakColors.warmGray500
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InferenceProvider.entries.forEach { option ->
                            TextButton(onClick = { state.setInferenceProvider(option) }) {
                                Text(
                                    option.rawValue,
                                    color = if (provider == option) OakColors.oakMedium else OakColors.warmGray500,
                                    fontWeight = if (provider == option) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = endpointDraft,
                        onValueChange = { endpointDraft = it },
                        label = { Text("Backend endpoint") },
                        placeholder = { Text("http://10.0.2.2:8787") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { state.setApiEndpoint(endpointDraft.trim()) }) {
                        Text("Save endpoint", color = OakColors.oakMedium)
                    }
                }
            }

            SettingsCard(isDark) {
                Column {
                    Text("CanopyChat", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Version 1.0.0 · Rooted Intelligence", fontSize = 12.sp, color = OakColors.warmGray500)
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(isDark: Boolean, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isDark) OakColors.warmGray900.copy(alpha = 0.78f) else Color.White.copy(alpha = 0.74f),
                RoundedCornerShape(12.dp)
            )
            .padding(16.dp)
    ) {
        content()
    }
}
