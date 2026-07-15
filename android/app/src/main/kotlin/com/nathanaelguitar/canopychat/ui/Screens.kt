package com.nathanaelguitar.canopychat.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.nathanaelguitar.canopychat.AppState
import com.nathanaelguitar.canopychat.core.CanopyFeedback
import com.nathanaelguitar.canopychat.core.CanopyLegal
import com.nathanaelguitar.canopychat.core.CanopySubscriptionManager
import com.nathanaelguitar.canopychat.core.ChatAttachment
import com.nathanaelguitar.canopychat.core.Conversation
import com.nathanaelguitar.canopychat.core.InferenceProvider
import com.nathanaelguitar.canopychat.core.MessageRole
import com.nathanaelguitar.canopychat.core.Workspace
import java.util.UUID

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
    var selectedWorkspace by remember { mutableStateOf<Workspace?>(null) }
    var showNewChat by remember { mutableStateOf(false) }
    val filteredConversations = remember(conversations, selectedWorkspace) {
        val scoped = selectedWorkspace?.let { workspace ->
            conversations.filter { it.workspace.id == workspace.id }
        } ?: conversations
        scoped.sortedWith(compareByDescending<Conversation> { it.isPinned }.thenByDescending { it.updatedAtMillis })
    }

    OakBackground(isDark = isDark) {
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Your Grove",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.Serif,
                    color = if (isDark) OakColors.oakCream else OakColors.oakDark,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = OakColors.oakMedium)
                }
                IconButton(onClick = {
                    showNewChat = true
                }) {
                    Icon(Icons.Filled.Add, contentDescription = "New conversation", tint = OakColors.oakMedium)
                }
            }

            WorkspaceChips(
                workspaces = state.availableWorkspaces,
                selectedWorkspace = selectedWorkspace,
                isDark = isDark,
                onSelect = { selectedWorkspace = it }
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 24.dp,
                    top = 18.dp,
                    end = 24.dp,
                    bottom = 104.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (filteredConversations.isEmpty()) {
                    item {
                        EmptyGrove(isDark = isDark) {
                            showNewChat = true
                        }
                    }
                }
                items(filteredConversations, key = { it.id }) { conversation ->
                    ConversationRow(conversation, isDark) { onOpen(conversation.id) }
                }
            }
            }

            Button(
                onClick = {
                    showNewChat = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = OakColors.oakMedium),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .height(56.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("New Chat", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
        if (showNewChat) {
            NewChatDialog(
                state = state,
                initialWorkspace = selectedWorkspace ?: state.defaultWorkspace,
                onDismiss = { showNewChat = false },
                onCreate = { workspace, persona, title ->
                    showNewChat = false
                    onOpen(state.createConversation(title, workspace, persona))
                }
            )
        }
    }
}

@Composable
private fun NewChatDialog(
    state: AppState,
    initialWorkspace: Workspace,
    onDismiss: () -> Unit,
    onCreate: (Workspace, com.nathanaelguitar.canopychat.core.AssistantPersona, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var workspace by remember { mutableStateOf(initialWorkspace) }
    var persona by remember { mutableStateOf(state.availablePersonas.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New conversation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Workspace", fontSize = 12.sp, color = OakColors.warmGray500)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.availableWorkspaces.forEach { option ->
                        TextButton(onClick = { workspace = option }) {
                            Text(option.name, color = if (workspace.id == option.id) OakColors.oakMedium else OakColors.warmGray500)
                        }
                    }
                }
                Text("Assistant", fontSize = 12.sp, color = OakColors.warmGray500)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.availablePersonas.forEach { option ->
                        TextButton(onClick = { persona = option }) {
                            Text(option.name, color = if (persona.id == option.id) OakColors.oakMedium else OakColors.warmGray500)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(workspace, persona, title) }) {
                Text("Create", color = OakColors.oakMedium, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun WorkspaceChips(
    workspaces: List<Workspace>,
    selectedWorkspace: Workspace?,
    isDark: Boolean,
    onSelect: (Workspace?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        WorkspaceChip(
            label = "All",
            selected = selectedWorkspace == null,
            color = OakColors.oakMedium,
            isDark = isDark
        ) { onSelect(null) }
        workspaces.forEach { workspace ->
            WorkspaceChip(
                label = workspace.name,
                selected = selectedWorkspace?.id == workspace.id,
                color = Color(android.graphics.Color.parseColor("#${workspace.colorHex}")),
                isDark = isDark
            ) { onSelect(workspace) }
        }
    }
}

@Composable
private fun WorkspaceChip(label: String, selected: Boolean, color: Color, isDark: Boolean, onClick: () -> Unit) {
    val background = if (selected) color else color.copy(alpha = if (isDark) 0.22f else 0.14f)
    val foreground = if (selected) Color.White else color
    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Text(label, color = foreground, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, isDark: Boolean, onClick: () -> Unit) {
    val cardColor = if (isDark) OakColors.warmGray900.copy(alpha = 0.78f) else Color.White.copy(alpha = 0.74f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(cardColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(conversation.workspaceColor().copy(alpha = 0.14f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(conversation.workspaceIcon(), fontSize = 24.sp, color = conversation.workspaceColor())
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conversation.title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDark) OakColors.oakCream else OakColors.oakDark,
                    modifier = Modifier.weight(1f)
                )
                if (conversation.isPinned) Text("Pinned", fontSize = 11.sp, color = OakColors.oakMedium)
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
}

@Composable
private fun EmptyGrove(isDark: Boolean, onCreate: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Your grove is quiet", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = if (isDark) OakColors.oakCream else OakColors.warmGray600)
        Spacer(Modifier.height(8.dp))
        Text("Start a new conversation to begin", fontSize = 15.sp, color = OakColors.warmGray500)
        Spacer(Modifier.height(24.dp))
        TextButton(
            onClick = onCreate,
            modifier = Modifier.border(1.dp, OakColors.oakMedium, RoundedCornerShape(18.dp))
        ) {
            Text("Plant a new seed", color = OakColors.oakMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ChatScreen(state: AppState, conversationId: UUID, onBack: () -> Unit) {
    val context = LocalContext.current
    val conversations by state.conversations.collectAsState()
    val isDark by state.isDarkTheme.collectAsState()
    val isSending by state.isSending.collectAsState()
    val status by state.generationStatus.collectAsState()
    val conversation = conversations.firstOrNull { it.id == conversationId }
    val messages = conversation?.messages ?: emptyList()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<ChatAttachment>>(emptyList()) }
    var pendingLocationText by remember { mutableStateOf<String?>(null) }
    var pendingLocationAttachments by remember { mutableStateOf<List<ChatAttachment>>(emptyList()) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        val text = pendingLocationText
        val pendingAttachments = pendingLocationAttachments
        pendingLocationText = null
        pendingLocationAttachments = emptyList()
        if (!text.isNullOrBlank()) {
            state.sendMessage(conversationId, text, pendingAttachments)
        }
    }
    val attachmentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val loaded = uris.mapNotNull { readAttachment(context, it) }
        attachments = (attachments + loaded).takeLast(3)
    }

    fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty() || isSending) return
        val outgoingAttachments = attachments
        input = ""
        attachments = emptyList()
        if (state.needsLocationPermissionFor(trimmed)) {
            pendingLocationText = trimmed
            pendingLocationAttachments = outgoingAttachments
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        } else {
            state.sendMessage(conversationId, trimmed, outgoingAttachments)
        }
    }

    LaunchedEffect(messages.size, isSending) {
        val extraTypingRow = if (isSending) 1 else 0
        val lastIndex = messages.size + extraTypingRow - 1
        if (lastIndex >= 0) {
            listState.animateScrollToItem(lastIndex)
        }
    }

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
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        conversation = conversation,
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
                    .background(if (isDark) OakColors.warmGray900.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.88f))
                .padding(12.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                IconButton(onClick = { attachmentLauncher.launch(arrayOf("image/*", "text/*", "application/pdf")) }) {
                    Text("+", color = OakColors.oakMedium, fontSize = 24.sp, fontWeight = FontWeight.Light)
                }
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
                        if ((text.isNotEmpty() || attachments.isNotEmpty()) && !isSending) {
                            sendText(text)
                        }
                    },
                    enabled = (input.isNotBlank() || attachments.isNotEmpty()) && !isSending,
                    modifier = Modifier
                        .size(48.dp)
                        .background(OakColors.oakMedium, CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                }
            }
            if (attachments.isNotEmpty()) {
                Text(
                    "${attachments.size} attachment${if (attachments.size == 1) "" else "s"} ready",
                    fontSize = 12.sp,
                    color = OakColors.oakMedium,
                    modifier = Modifier.padding(start = 56.dp, bottom = 4.dp)
                )
            }
        }
    }
}

private fun Conversation.workspaceColor(): Color =
    Color(android.graphics.Color.parseColor("#${workspace.colorHex}"))

private fun Conversation.workspaceIcon(): String = when (workspace.id) {
    "work" -> "Brief"
    "creative" -> "Idea"
    "research" -> "Read"
    else -> "You"
}

private fun readAttachment(context: android.content.Context, uri: Uri): ChatAttachment? = runCatching {
    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
    val filename = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "Attachment"
    val data = context.contentResolver.openInputStream(uri)?.use { input -> input.readBytes().take(8 * 1024 * 1024).toByteArray() }
        ?: return@runCatching null
    val extractedText = if (mimeType.startsWith("text/") || mimeType == "application/json" || mimeType == "application/xml") {
        data.toString(Charsets.UTF_8).take(24_000)
    } else null
    ChatAttachment(data = data, mimeType = mimeType, filename = filename, extractedText = extractedText)
}.getOrNull()

@Composable
private fun MessageBubble(
    message: com.nathanaelguitar.canopychat.core.ChatMessage,
    conversation: Conversation?,
    content: String,
    isUser: Boolean,
    isDark: Boolean
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
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
        Column(
            modifier = Modifier.weight(0.8f, fill = false),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .background(bubbleColor, RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                MarkdownishText(content, color = textColor)
            }
            if (content.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(content))
                    }) {
                        Text("Copy", color = OakColors.oakMedium, fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, content)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share message"))
                    }) {
                        Text("Share", color = OakColors.oakMedium, fontSize = 12.sp)
                    }
                    if (!isUser) {
                        TextButton(onClick = {
                            val feedback = CanopyFeedback.modelFeedback(message, conversation)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "message/rfc822"
                                putExtra(Intent.EXTRA_EMAIL, arrayOf(com.nathanaelguitar.canopychat.core.CanopyLegal.SUPPORT_EMAIL))
                                putExtra(Intent.EXTRA_SUBJECT, "CanopyChat model feedback")
                                putExtra(Intent.EXTRA_TEXT, feedback)
                                clipData = ClipData.newPlainText("feedback", feedback)
                            }
                            context.startActivity(Intent.createChooser(intent, "Report model issue"))
                        }) {
                            Text("Report", color = OakColors.oakMedium, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        if (!isUser) Spacer(Modifier.weight(0.2f))
    }
}

@Composable
private fun MarkdownishText(content: String, color: Color) {
    val body = content.substringBeforeLast("\nSources\n", content)
    Text(
        body
            .replace(Regex("(?m)^#{1,6}\\s+"), "")
            .replace("**", "")
            .trim(),
        color = color,
        fontSize = 15.sp,
        lineHeight = 22.sp
    )
}

@Composable
fun SettingsScreen(state: AppState, onBack: () -> Unit, onSubscription: () -> Unit) {
    val context = LocalContext.current
    val isDark by state.isDarkTheme.collectAsState()
    val provider by state.inferenceProvider.collectAsState()
    val endpoint by state.apiEndpoint.collectAsState()
    val customPrompt by state.customSystemPrompt.collectAsState()
    val defaultWorkspaceId by state.defaultWorkspaceId.collectAsState()
    var endpointDraft by remember(endpoint) { mutableStateOf(endpoint) }
    var promptDraft by remember(customPrompt) { mutableStateOf(customPrompt) }
    var workspaceDraft by remember { mutableStateOf("") }
    var personaDraft by remember { mutableStateOf("") }
    var personaInstructionsDraft by remember { mutableStateOf("") }

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
                    Text("Default workspace", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("New conversations start here", fontSize = 12.sp, color = OakColors.warmGray500)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.availableWorkspaces.forEach { workspace ->
                            TextButton(onClick = { state.setDefaultWorkspace(workspace) }) {
                                Text(
                                    workspace.name,
                                    color = if (defaultWorkspaceId == workspace.id) OakColors.oakMedium else OakColors.warmGray500,
                                    fontWeight = if (defaultWorkspaceId == workspace.id) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            SettingsCard(isDark) {
                Column {
                    Text("Your workspaces and assistants", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("Create reusable context for new chats", fontSize = 12.sp, color = OakColors.warmGray500)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = workspaceDraft,
                        onValueChange = { workspaceDraft = it },
                        label = { Text("New workspace") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(
                        onClick = { state.createCustomWorkspace(workspaceDraft); workspaceDraft = "" },
                        enabled = workspaceDraft.isNotBlank()
                    ) { Text("Add workspace", color = OakColors.oakMedium) }
                    OutlinedTextField(
                        value = personaDraft,
                        onValueChange = { personaDraft = it },
                        label = { Text("New assistant") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = personaInstructionsDraft,
                        onValueChange = { personaInstructionsDraft = it },
                        label = { Text("Assistant instructions (optional)") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(
                        onClick = {
                            state.createCustomPersona(personaDraft, "Custom assistant", personaInstructionsDraft)
                            personaDraft = ""
                            personaInstructionsDraft = ""
                        },
                        enabled = personaDraft.isNotBlank()
                    ) { Text("Add assistant", color = OakColors.oakMedium) }
                }
            }

            SettingsCard(isDark) {
                Column {
                    Text("Inference", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "Canopy V1 uses llama.cpp when the native runtime is bundled. Until then, Backend uses any OpenAI-compatible endpoint.",
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
                    Text("System preferences", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("Persistent tone, formatting, and verbosity preferences", fontSize = 12.sp, color = OakColors.warmGray500)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = promptDraft,
                        onValueChange = { promptDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 8,
                        placeholder = { Text("e.g. Use concise bullet points and a warm tone.") }
                    )
                    Spacer(Modifier.height(4.dp))
                    Row {
                        TextButton(onClick = { promptDraft = ""; state.setCustomSystemPrompt("") }) {
                            Text("Clear", color = OakColors.error)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { state.setCustomSystemPrompt(promptDraft) }) {
                            Text("Save preferences", color = OakColors.oakMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            SettingsCard(isDark) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("CanopyChat Plus", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("Subscription and restore options", fontSize = 12.sp, color = OakColors.warmGray500)
                    }
                    TextButton(onClick = onSubscription) {
                        Text("View", color = OakColors.oakMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            SettingsCard(isDark) {
                Column {
                    Text("Support", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = android.net.Uri.parse("mailto:${CanopyLegal.SUPPORT_EMAIL}")
                            putExtra(Intent.EXTRA_SUBJECT, "CanopyChat issue report")
                            putExtra(Intent.EXTRA_TEXT, CanopyFeedback.appIssue())
                        }
                        runCatching { context.startActivity(intent) }
                    }) { Text("Report an issue", color = OakColors.oakMedium) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(CanopyLegal.PRIVACY_POLICY_URL)))
                        }) { Text("Privacy", color = OakColors.oakMedium, fontSize = 12.sp) }
                        TextButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(CanopyLegal.TERMS_OF_USE_URL)))
                        }) { Text("Terms", color = OakColors.oakMedium, fontSize = 12.sp) }
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
fun PaywallScreen(subscription: CanopySubscriptionManager, onBack: () -> Unit) {
    val context = LocalContext.current
    val products by subscription.products.collectAsState()
    val subscribed by subscription.isSubscribed.collectAsState()
    val loading by subscription.isLoading.collectAsState()
    val error by subscription.errorMessage.collectAsState()
    var testCode by remember { mutableStateOf("") }
    val isDark = false

    OakBackground(isDark = isDark) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = OakColors.oakMedium)
                }
                Text("CanopyChat Plus", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(24.dp))
            Text("Private intelligence, rooted close.", fontSize = 25.sp, fontFamily = FontFamily.Serif, textAlign = TextAlign.Center, color = OakColors.oakDark)
            Spacer(Modifier.height(12.dp))
            Text("On-device inference, privacy-first conversations, and grounded web search when you need it.", textAlign = TextAlign.Center, color = OakColors.warmGray600)
            Spacer(Modifier.height(24.dp))
            SettingsCard(isDark) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Included", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("• Canopy V1 on-device AI when the native runtime is available")
                    Text("• Private local conversation memory")
                    Text("• Current information with grounded web search")
                }
            }
            Spacer(Modifier.height(16.dp))
            products.forEach { product ->
                val offer = product.subscriptionOfferDetails?.firstOrNull()
                val price = offer?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice ?: "Price unavailable"
                Button(
                    onClick = { (context as? Activity)?.let { subscription.purchase(it, product) } },
                    enabled = !loading && offer != null,
                    colors = ButtonDefaults.buttonColors(containerColor = OakColors.oakMedium),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("${if (product.productId == CanopySubscriptionManager.YEARLY_PRODUCT_ID) "Yearly" else "Monthly"} · $price", color = Color.White)
                }
            }
            if (products.isEmpty()) {
                Text("Subscription products will appear here after Google Play setup.", fontSize = 13.sp, color = OakColors.warmGray500, textAlign = TextAlign.Center)
            }
            TextButton(onClick = subscription::restorePurchases, enabled = !loading) {
                Text(if (subscribed) "Subscription active" else "Restore purchases", color = OakColors.oakMedium)
            }
            if (com.nathanaelguitar.canopychat.BuildConfig.DEBUG) {
                OutlinedTextField(
                    value = testCode,
                    onValueChange = { testCode = it },
                    label = { Text("Test access code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = { if (subscription.redeemTestAccessCode(testCode)) testCode = "" }) {
                    Text("Redeem test access", color = OakColors.warmGray500)
                }
            }
            if (error != null) Text(error!!, color = OakColors.error, fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(CanopyLegal.PRIVACY_POLICY_URL))) }) { Text("Privacy", color = OakColors.oakMedium, fontSize = 12.sp) }
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(CanopyLegal.TERMS_OF_USE_URL))) }) { Text("Terms", color = OakColors.oakMedium, fontSize = 12.sp) }
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
