package com.nathanaelguitar.canopychat.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EnergySavingsLeaf
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import com.nathanaelguitar.canopychat.R
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.nathanaelguitar.canopychat.AppState
import com.nathanaelguitar.canopychat.core.ActiveModelVersion
import com.nathanaelguitar.canopychat.core.AssistantPersona
import com.nathanaelguitar.canopychat.core.CanopyFeedback
import com.nathanaelguitar.canopychat.core.CanopyLegal
import com.nathanaelguitar.canopychat.core.CanopyShare
import com.nathanaelguitar.canopychat.core.CanopySubscriptionManager
import com.nathanaelguitar.canopychat.core.ChatAttachment
import com.nathanaelguitar.canopychat.core.ChatAttachmentLoader
import com.nathanaelguitar.canopychat.core.ChatMessage
import com.nathanaelguitar.canopychat.core.Conversation
import com.nathanaelguitar.canopychat.core.ImageNormalizer
import com.nathanaelguitar.canopychat.core.InferenceProvider
import com.nathanaelguitar.canopychat.core.MessageRole
import com.nathanaelguitar.canopychat.core.ModelCatalog
import com.nathanaelguitar.canopychat.core.Workspace
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Port of WelcomeView in iphone/AetherChat/WelcomeView.swift, including the staggered
 * spring entrance, gradient logo badge, tinted feature tiles, and gradient CTA.
 */
@Composable
fun WelcomeScreen(isDark: Boolean, onEnter: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    OakBackground(isDark = isDark) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))
            Box(
                modifier = Modifier
                    .graphicsLayer(
                        alpha = visibleAlpha(visible, 100, 600),
                        translationY = (1f - visibleAlpha(visible, 100, 600)) * 60f
                    )
                    .size(104.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(
                        Brush.verticalGradient(listOf(OakColors.oakLight, OakColors.oakMedium))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(R.drawable.ic_canopy_tree),
                    contentDescription = null,
                    tint = OakColors.oakCream,
                    modifier = Modifier.size(54.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "CanopyChat",
                fontSize = 44.sp,
                fontWeight = FontWeight.Thin,
                fontFamily = FontFamily.Serif,
                color = if (isDark) OakColors.oakCream else OakColors.warmBlack,
                modifier = Modifier.graphicsLayer(alpha = visibleAlpha(visible, 250, 600))
            )
            Text(
                "Rooted Intelligence",
                fontSize = 17.sp,
                fontFamily = FontFamily.Serif,
                color = OakColors.oakLight,
                modifier = Modifier.graphicsLayer(alpha = visibleAlpha(visible, 350, 600))
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Private conversations that stay on your phone. On-device intelligence, " +
                    "with web search when you need something current.",
                fontSize = 15.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center,
                color = if (isDark) OakColors.warmGray400 else OakColors.warmGray600,
                modifier = Modifier.graphicsLayer(alpha = visibleAlpha(visible, 450, 600))
            )
            Spacer(Modifier.height(40.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer(
                        alpha = visibleAlpha(visible, 550, 600),
                        translationY = (1f - visibleAlpha(visible, 550, 600)) * 40f
                    ),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                FeatureRow(
                    Icons.Filled.Lock,
                    OakColors.forestMedium,
                    "Privacy First",
                    "Conversations run locally on your phone by default — nothing leaves your device.",
                    isDark
                )
                FeatureRow(
                    Icons.Filled.EnergySavingsLeaf,
                    OakColors.copper,
                    "Eco-Friendly Intelligence",
                    "Use the model already in your hand instead of a data center.",
                    isDark
                )
                FeatureRow(
                    Icons.Filled.Public,
                    OakColors.info,
                    "Search When It Matters",
                    "Web-grounded, location-aware answers when you ask.",
                    isDark
                )
            }
            Spacer(Modifier.height(40.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer(alpha = visibleAlpha(visible, 700, 600))
                    .clip(RoundedCornerShape(17.dp))
                    .background(
                        Brush.verticalGradient(listOf(OakColors.oakLight, OakColors.oakMedium))
                    )
                    .clickable(onClick = onEnter)
                    .padding(vertical = 17.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Enter Your Grove", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

/** Time-delayed fade used by the welcome entrance, mirroring SwiftUI's delayed animations. */
@Composable
private fun visibleAlpha(visible: Boolean, delayMillis: Int, durationMillis: Int): Float {
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = durationMillis,
            delayMillis = delayMillis
        ),
        label = "entrance"
    )
    return alpha
}

/** Port of WelcomeFeatureRow in iphone/AetherChat/WelcomeView.swift. */
@Composable
private fun FeatureRow(icon: ImageVector, tint: Color, title: String, subtitle: String, isDark: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = if (isDark) 0.2f else 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isDark) OakColors.oakPale else OakColors.oakDark
            )
            Text(
                subtitle,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = if (isDark) OakColors.warmGray400 else OakColors.warmGray600
            )
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
    // (conversationId, currentTitle) for the rename dialog.
    var renaming by remember { mutableStateOf<Pair<UUID, String>?>(null) }
    // Port of undoDeleted in iphone/AetherChat/ConversationListView.swift (softDelete).
    var undoDeleted by remember { mutableStateOf<Conversation?>(null) }
    val undoScope = rememberCoroutineScope()
    val undoTask = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }

    fun softDelete(conversation: Conversation) {
        state.deleteConversation(conversation.id)
        undoDeleted = conversation
        undoTask[0]?.cancel()
        undoTask[0] = undoScope.launch {
            kotlinx.coroutines.delay(4_500)
            undoDeleted = null
        }
    }
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
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.Serif,
                    color = if (isDark) OakColors.oakCream else OakColors.oakDark,
                    modifier = Modifier.weight(1f)
                )
                // iOS gives the settings gear a tinted-circle background.
                IconButton(
                    onClick = onSettings,
                    modifier = Modifier
                        .size(38.dp)
                        .background(OakColors.oakMedium.copy(alpha = if (isDark) 0.22f else 0.12f), CircleShape)
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = OakColors.oakMedium, modifier = Modifier.size(20.dp))
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
                // iOS splits the list into Pinned and Recent sections.
                val pinned = filteredConversations.filter { it.isPinned }
                val recent = filteredConversations.filterNot { it.isPinned }

                if (pinned.isNotEmpty()) {
                    item { SectionHeader("Pinned", OakColors.amber) }
                    items(pinned, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            isDark = isDark,
                            onClick = { onOpen(conversation.id) },
                            onPin = { state.togglePin(conversation.id) },
                            onRename = { renaming = conversation.id to conversation.title },
                            onDelete = { softDelete(conversation) }
                        )
                    }
                }
                if (recent.isNotEmpty()) {
                    if (pinned.isNotEmpty()) {
                        item { SectionHeader("Recent", OakColors.oakMedium) }
                    }
                    items(recent, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            isDark = isDark,
                            onClick = { onOpen(conversation.id) },
                            onPin = { state.togglePin(conversation.id) },
                            onRename = { renaming = conversation.id to conversation.title },
                            onDelete = { softDelete(conversation) }
                        )
                    }
                }
            }
            }

            // Port of the undo-delete toast in iphone/AetherChat/ConversationListView.swift.
            androidx.compose.animation.AnimatedVisibility(
                visible = undoDeleted != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 100.dp),
                enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (isDark) OakColors.warmGray800 else OakColors.warmGray900)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Chat deleted", color = OakColors.oakCream, fontSize = 14.sp)
                    Text(
                        "Undo",
                        color = OakColors.amber,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            undoDeleted?.let { state.restoreDeleted(it.id) }
                            undoTask[0]?.cancel()
                            undoDeleted = null
                        }
                    )
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
        renaming?.let { (conversationId, currentTitle) ->
            RenameConversationDialog(
                currentTitle = currentTitle,
                onDismiss = { renaming = null },
                onRename = { newTitle ->
                    state.renameConversation(conversationId, newTitle)
                    renaming = null
                }
            )
        }
        if (showNewChat) {
            NewChatDialog(
                state = state,
                initialWorkspace = selectedWorkspace ?: state.defaultWorkspace,
                isDark = isDark,
                onDismiss = { showNewChat = false },
                onCreate = { workspace, persona, title ->
                    showNewChat = false
                    onOpen(state.createConversation(title, workspace, persona))
                }
            )
        }
    }
}

/**
 * Port of NewChatSheet in iphone/AetherChat/SettingsView.swift, including inline
 * workspace creation/deletion and assistant creation/editing/deletion.
 */
@Composable
private fun NewChatDialog(
    state: AppState,
    initialWorkspace: Workspace,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onCreate: (Workspace, AssistantPersona, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var workspace by remember { mutableStateOf(initialWorkspace) }
    var persona by remember { mutableStateOf(state.availablePersonas.first()) }
    var showingCreateWorkspace by remember { mutableStateOf(false) }
    var workspaceName by remember { mutableStateOf("") }
    var showingCreateAssistant by remember { mutableStateOf(false) }
    var editingAssistant by remember { mutableStateOf<AssistantPersona?>(null) }

    val customWorkspaces by state.workspacesFlow.collectAsState()
    val customPersonas by state.personasFlow.collectAsState()
    val workspaces = remember(customWorkspaces) { state.availableWorkspaces }
    val personas = remember(customPersonas) { state.availablePersonas }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Conversation") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("What's this about?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Workspace", fontSize = 12.sp, color = oakSubtitle())
                workspaces.forEach { option ->
                    WorkspacePickerRow(
                        workspace = option,
                        isSelected = workspace.id == option.id,
                        onSelect = { workspace = option },
                        onDelete = {
                            state.deleteCustomWorkspace(option)
                            if (workspace.id == option.id) workspace = Workspace.PERSONAL
                        }
                    )
                }
                TextButton(onClick = { workspaceName = ""; showingCreateWorkspace = true }) {
                    Text("+ Add Workspace", color = OakColors.oakMedium, fontSize = 13.sp)
                }

                Text("Assistant", fontSize = 12.sp, color = oakSubtitle())
                personas.forEach { option ->
                    AssistantPickerRow(
                        persona = option,
                        isSelected = persona.id == option.id,
                        onSelect = { persona = option },
                        onEdit = { editingAssistant = option },
                        onDelete = {
                            state.deleteCustomPersona(option)
                            if (persona.id == option.id) persona = AssistantPersona.DEFAULT
                        }
                    )
                }
                TextButton(onClick = { showingCreateAssistant = true }) {
                    Text("+ Create Assistant", color = OakColors.oakMedium, fontSize = 13.sp)
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

    if (showingCreateWorkspace) {
        AlertDialog(
            onDismissRequest = { showingCreateWorkspace = false },
            title = { Text("Add Workspace") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Create a workspace for a new group of conversations.",
                        fontSize = 13.sp,
                        color = oakSubtitle()
                    )
                    OutlinedTextField(
                        value = workspaceName,
                        onValueChange = { workspaceName = it },
                        label = { Text("Workspace name") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        workspace = state.createCustomWorkspace(workspaceName)
                        showingCreateWorkspace = false
                    },
                    enabled = workspaceName.isNotBlank()
                ) { Text("Add", color = OakColors.oakMedium) }
            },
            dismissButton = {
                TextButton(onClick = { showingCreateWorkspace = false }) { Text("Cancel") }
            }
        )
    }

    if (showingCreateAssistant) {
        AssistantEditorSheet(
            title = "Create Assistant",
            saveTitle = "Save",
            isDark = isDark,
            initialName = "",
            initialDescription = "",
            initialInstructions = "",
            onDismiss = { showingCreateAssistant = false },
            onSave = { name, description, instructions ->
                persona = state.createCustomPersona(name, description, instructions)
                showingCreateAssistant = false
            }
        )
    }

    editingAssistant?.let { assistant ->
        AssistantEditorSheet(
            title = "Edit Assistant",
            saveTitle = "Save Changes",
            isDark = isDark,
            initialName = assistant.name,
            initialDescription = assistant.description,
            initialInstructions = assistant.instructions,
            onDismiss = { editingAssistant = null },
            onSave = { name, description, instructions ->
                state.updateCustomPersona(assistant.id, name, description, instructions)?.let {
                    persona = it
                }
                editingAssistant = null
            }
        )
    }
}

/** Port of SectionHeader in iphone/AetherChat/ConversationListView.swift. */
@Composable
private fun SectionHeader(title: String, color: Color) {
    Text(
        title.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier.padding(top = 4.dp)
    )
}

/** Backs the "Rename" context-menu action on ConversationRow. */
@Composable
private fun RenameConversationDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var draft by remember { mutableStateOf(currentTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Conversation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Conversation title") },
                    singleLine = true
                )
                // iOS alert message: "Leave it blank to keep it as Untitled."
                Text(
                    "Leave it blank to keep it as Untitled.",
                    fontSize = 12.sp,
                    color = oakSubtitle()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(draft.trim().ifEmpty { "Untitled" }) }) {
                Text("Save", color = OakColors.oakMedium, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { draft = "" }) {
                    Text("Clear", color = OakColors.error)
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

/** Port of AssistantPickerRow in iphone/AetherChat/SettingsView.swift. */
@Composable
private fun AssistantPickerRow(
    persona: AssistantPersona,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(persona.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(persona.description, fontSize = 12.sp, color = oakSubtitle())
        }
        if (isSelected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Selected",
                tint = OakColors.oakMedium,
                modifier = Modifier.size(18.dp)
            )
        }
        // iOS exposes edit/delete via swipe actions on custom assistants only.
        if (persona.id.startsWith("custom-")) {
            TextButton(onClick = onEdit) { Text("Edit", color = OakColors.oakMedium, fontSize = 12.sp) }
            TextButton(onClick = onDelete) { Text("Delete", color = OakColors.error, fontSize = 12.sp) }
        }
    }
}

/** Port of AssistantEditorSheet in iphone/AetherChat/SettingsView.swift. */
@Composable
private fun AssistantEditorSheet(
    title: String,
    saveTitle: String,
    isDark: Boolean,
    initialName: String,
    initialDescription: String,
    initialInstructions: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    var instructions by remember { mutableStateOf(initialInstructions) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(if (isDark) OakColors.warmGray900 else OakColors.oakCream)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                title,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isDark) OakColors.oakCream else OakColors.warmBlack
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name, e.g. Architect") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Short description") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = instructions,
                onValueChange = { instructions = it },
                label = { Text("Instructions") },
                minLines = 5,
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp)
            )
            Text(
                "These instructions apply only when this assistant is selected for a conversation.",
                fontSize = 12.sp,
                color = oakSubtitle()
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = oakSubtitle()) }
                TextButton(
                    onClick = { onSave(name, description, instructions) },
                    enabled = name.isNotBlank()
                ) {
                    Text(saveTitle, color = OakColors.oakMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
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
@OptIn(ExperimentalFoundationApi::class)
private fun ConversationRow(
    conversation: Conversation,
    isDark: Boolean,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val cardColor = if (isDark) OakColors.warmGray800 else Color.White
    // iOS uses a long-press contextMenu; Compose's equivalent is combinedClickable + menu.
    var showMenu by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardColor, RoundedCornerShape(16.dp))
                .combinedClickable(onClick = onClick, onLongClick = { showMenu = true })
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(conversation.workspaceColor().copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                conversation.workspaceIcon(),
                contentDescription = conversation.workspace.name,
                tint = conversation.workspaceColor(),
                modifier = Modifier.size(22.dp)
            )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Title + pin claim the row minus the timestamp; nesting the weight
                    // here stops the title from being squeezed to half the width.
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            conversation.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isDark) OakColors.warmGray200 else OakColors.warmBlack,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (conversation.isPinned) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                            Icons.Filled.PushPin,
                            contentDescription = "Pinned",
                            tint = OakColors.amber,
                            modifier = Modifier.size(12.dp)
                        )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        relativeDate(conversation.updatedAtMillis),
                        fontSize = 11.sp,
                        color = OakColors.warmGray400
                    )
                }
                Text(
                    conversation.previewText.ifEmpty { "No messages yet" },
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = oakSubtitle()
                )
                Text(
                    conversation.persona.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = conversation.workspaceColor(),
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(conversation.workspaceColor().copy(alpha = 0.1f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text(if (conversation.isPinned) "Unpin" else "Pin to Top") },
                onClick = { showMenu = false; onPin() }
            )
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = { showMenu = false; onRename() }
            )
            DropdownMenuItem(
                text = { Text("Delete", color = OakColors.error) },
                onClick = { showMenu = false; onDelete() }
            )
        }
    }
}

/** Port of ConversationRow.relativeDate in iphone/AetherChat/ConversationListView.swift. */
private fun relativeDate(updatedAtMillis: Long): String {
    val diffSeconds = (System.currentTimeMillis() - updatedAtMillis) / 1000
    return when {
        diffSeconds < 60 -> "now"
        diffSeconds < 3600 -> "${diffSeconds / 60}m"
        diffSeconds < 86_400 -> "${diffSeconds / 3600}h"
        else -> "${diffSeconds / 86_400}d"
    }
}

/** Port of EmptyGrove in iphone/AetherChat/ConversationListView.swift. */
@Composable
private fun EmptyGrove(isDark: Boolean, onCreate: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(OakColors.forestMedium.copy(alpha = if (isDark) 0.2f else 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.EnergySavingsLeaf,
                contentDescription = null,
                tint = OakColors.forestMedium,
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Your grove is quiet",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Serif,
            color = if (isDark) OakColors.oakCream else OakColors.warmGray600
        )
        Spacer(Modifier.height(8.dp))
        Text("Start a new conversation to begin", fontSize = 15.sp, color = oakSubtitle())
        Spacer(Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(OakColors.oakMedium)
                .clickable(onClick = onCreate)
                .padding(horizontal = 22.dp, vertical = 12.dp)
        ) {
            Text("Plant a new seed", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

@Composable
fun ChatScreen(state: AppState, conversationId: UUID, onBack: () -> Unit, onNewChat: (UUID) -> Unit) {
    val context = LocalContext.current
    val conversations by state.conversations.collectAsState()
    val isDark by state.isDarkTheme.collectAsState()
    val isSending by state.isSending.collectAsState()
    val status by state.generationStatus.collectAsState()
    val modelLoadingMessage by state.modelLoadingMessage.collectAsState()
    val streamingPreview by state.streamingPreview.collectAsState()
    val webSearchEnabled by state.webSearchEnabled.collectAsState()
    val fontScale by state.messageFontScale.collectAsState()
    val conversation = conversations.firstOrNull { it.id == conversationId }
    val messages = conversation?.messages ?: emptyList()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<ChatAttachment>>(emptyList()) }
    // Port of PromptEditDraft on iOS: (messageId, originalText).
    var editDraft by remember { mutableStateOf<Pair<UUID, String>?>(null) }
    var renamingChat by remember { mutableStateOf(false) }
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
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var importingAttachments by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    val attachmentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        // Reading and decoding (a PDF especially) is far too slow for the main thread.
        scope.launch {
            importingAttachments = true
            try {
                val loaded = withContext(Dispatchers.IO) {
                    uris.mapNotNull { ChatAttachmentLoader.attachment(context, it) }
                }
                attachments = (attachments + loaded).takeLast(3)
            } finally {
                importingAttachments = false
            }
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            importingAttachments = true
            try {
                val loaded = withContext(Dispatchers.IO) {
                    uris.mapNotNull { ChatAttachmentLoader.attachment(context, it) }
                }
                attachments = (attachments + loaded).takeLast(3)
            } finally {
                importingAttachments = false
            }
        }
    }
    // Android substitute for CameraCaptureView (UIImagePickerController) on iOS.
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap == null) return@rememberLauncherForActivityResult
        scope.launch {
            importingAttachments = true
            try {
                val captured = withContext(Dispatchers.IO) { ImageNormalizer.attachmentFromBitmap(bitmap) }
                if (captured != null) attachments = (attachments + captured).takeLast(3)
            } finally {
                importingAttachments = false
            }
        }
    }

    fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty() || isSending) return
        val outgoingAttachments = attachments
        input = ""
        attachments = emptyList()
        // Drop focus so the keyboard retracts and the reply is actually visible.
        focusManager.clearFocus()
        keyboardController?.hide()
        if (state.needsLocationPermissionFor(trimmed)) {
            pendingLocationText = trimmed
            pendingLocationAttachments = outgoingAttachments
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        } else {
            state.sendMessage(conversationId, trimmed, outgoingAttachments)
        }
    }

    // Long model downloads and on-device generation must not be cut short by the
    // display sleeping and the process being throttled.
    val view = LocalView.current
    DisposableEffect(modelLoadingMessage, isSending) {
        view.keepScreenOn = modelLoadingMessage != null || isSending
        onDispose { view.keepScreenOn = false }
    }

    // Port of scrollDismissesKeyboard(.interactively) on iOS.
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && inputFocused) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    // Port of scrollToLatestMessage on iOS: a fresh assistant reply is anchored to its
    // top so long answers read from the beginning; anything else pins to the bottom.
    LaunchedEffect(messages.size, isSending) {
        val extraTypingRow = if (isSending) 1 else 0
        val last = messages.lastOrNull()
        if (last == null) {
            if (isSending) listState.animateScrollToItem(0)
            return@LaunchedEffect
        }
        if (last.role == MessageRole.ASSISTANT) {
            // The invisible "latest-response-start" anchor item sits right before the
            // trailing assistant bubble.
            listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
        } else {
            listState.animateScrollToItem(messages.size + extraTypingRow - 1)
        }
    }

    // Overlay and editor sheet must share a layout parent with the screen body.
    Box(modifier = Modifier.fillMaxSize()) {
    OakBackground(isDark = isDark) {
        // systemBars + ime, applied separately. safeDrawingPadding() also folds in the IME
        // inset, which double-counted the keyboard height and crushed the chat upward.
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = OakColors.oakMedium)
                }
                // Port of the tappable principal title on iOS, which opens rename.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { renamingChat = true }
                ) {
                    Text(
                        conversation?.title ?: "Chat",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isDark) OakColors.oakCream else OakColors.oakDark
                    )
                    Text(
                        "with ${conversation?.persona?.name ?: "Canopy"}",
                        fontSize = 11.sp,
                        color = OakColors.oakMedium
                    )
                }
                // Port of the frosted trailing capsule on iOS: share conversation +
                // new chat (inheriting workspace/persona).
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            (if (isDark) OakColors.warmGray800 else Color.White).copy(alpha = 0.82f)
                        )
                        .border(1.dp, OakColors.oakMedium.copy(alpha = 0.18f), CircleShape),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            conversation?.let {
                                CanopyShare.shareText(
                                    context,
                                    CanopyShare.conversationText(it),
                                    "Share conversation"
                                )
                            }
                        },
                        enabled = conversation?.messages?.isNotEmpty() == true
                    ) {
                        Icon(
                            Icons.Filled.IosShare,
                            contentDescription = "Share conversation",
                            tint = if (conversation?.messages?.isNotEmpty() == true) {
                                OakColors.oakMedium
                            } else {
                                OakColors.warmGray400
                            },
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(18.dp)
                            .background(OakColors.oakMedium.copy(alpha = 0.22f))
                    )
                    IconButton(onClick = {
                        conversation?.let { current ->
                            val persona = if (current.persona.id == AssistantPersona.DEFAULT.id) {
                                state.availablePersonas.first()
                            } else {
                                current.persona
                            }
                            onNewChat(
                                state.createConversation(
                                    title = "",
                                    workspace = current.workspace,
                                    persona = persona
                                )
                            )
                        }
                    }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "New chat",
                            tint = OakColors.oakMedium,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        })
                    },
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (messages.isEmpty() && !isSending) {
                    item { ChatEmptyState(conversation?.persona?.name ?: "Canopy", isDark) }
                }
                itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                    val isUser = message.role == MessageRole.USER
                    MessageBubble(
                        message = message,
                        conversation = conversation,
                        isUser = isUser,
                        isDark = isDark,
                        fontScale = fontScale,
                        // Only the trailing assistant turn can be regenerated, matching iOS.
                        canRegenerate = !isUser && index == messages.lastIndex && !isSending,
                        onRegenerate = {
                            state.recordResponseRegenerated(conversationId, message.id)
                            state.regenerateLastResponse(conversationId)
                        },
                        onEdit = if (isUser && !isSending) {
                            { editDraft = message.id to message.content }
                        } else {
                            null
                        },
                        onResend = if (isUser && !isSending) {
                            {
                                state.recordMessageResent(conversationId, message.id, message.content)
                                state.editUserMessage(conversationId, message.id, message.content)
                            }
                        } else {
                            null
                        },
                        onRating = { positive ->
                            state.recordResponseRating(
                                conversationId, message.id, message.content,
                                if (positive) "positive" else "negative"
                            )
                        },
                        onCorrection = { correction ->
                            state.recordUserCorrection(
                                conversationId, message.id,
                                conversation?.let { promptTextBefore(it, message.id) },
                                message.content, correction
                            )
                        }
                    )
                }
                if (isSending && modelLoadingMessage == null) {
                    // Port of the streaming branch in ChatView.messagesSection on iOS.
                    item {
                        val preview = streamingPreview
                        if (!preview.isNullOrEmpty()) {
                            StreamingBubble(preview, isDark, fontScale)
                        } else {
                            TypingIndicator(status, isDark)
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        // Port of the gradient fade behind InputBar on iOS.
                        Brush.verticalGradient(
                            listOf(
                                (if (isDark) OakColors.warmGray900 else OakColors.oakCream).copy(alpha = 0f),
                                (if (isDark) OakColors.warmGray900 else OakColors.oakCream).copy(alpha = 0.9f)
                            )
                        )
                    )
            ) {
                if (importingAttachments) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = OakColors.oakMedium
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Reading attachment…", fontSize = 12.sp, color = oakSubtitle())
                    }
                }
                if (attachments.isNotEmpty()) {
                    AttachmentTray(
                        attachments = attachments,
                        isDark = isDark,
                        onRemove = { target -> attachments = attachments.filterNot { it.id == target.id } }
                    )
                }
                // Port of the InputBar capsule in iphone/AetherChat/ChatView.swift,
                // including the attach popover's Web Search toggle.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 8.dp, bottom = 6.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(if (isDark) OakColors.warmGray800 else Color.White)
                        .border(
                            1.dp,
                            Brush.linearGradient(
                                if (isDark) {
                                    listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.03f))
                                } else {
                                    listOf(OakColors.oakPale.copy(alpha = 0.8f), OakColors.oakPale.copy(alpha = 0.3f))
                                }
                            ),
                            RoundedCornerShape(26.dp)
                        ),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Box {
                        IconButton(
                            onClick = { showAttachMenu = true },
                            enabled = !isSending
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Add attachment",
                                tint = if (isDark) OakColors.warmGray400 else OakColors.warmGray500
                            )
                        }
                        DropdownMenu(
                            expanded = showAttachMenu,
                            onDismissRequest = { showAttachMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Camera") },
                                leadingIcon = {
                                    Icon(Icons.Filled.PhotoCamera, contentDescription = null, tint = OakColors.oakMedium)
                                },
                                onClick = {
                                    showAttachMenu = false
                                    cameraLauncher.launch(null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Photo Library") },
                                leadingIcon = {
                                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null, tint = OakColors.oakMedium)
                                },
                                onClick = {
                                    showAttachMenu = false
                                    galleryLauncher.launch(arrayOf("image/*"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Files") },
                                leadingIcon = {
                                    Icon(Icons.Filled.InsertDriveFile, contentDescription = null, tint = OakColors.oakMedium)
                                },
                                onClick = {
                                    showAttachMenu = false
                                    attachmentLauncher.launch(arrayOf("text/*", "application/pdf", "*/*"))
                                }
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                            // Port of the Web Search toggle in InputBar's attach popover.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Public,
                                    contentDescription = null,
                                    tint = OakColors.oakMedium,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("Web Search", fontSize = 15.sp, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = webSearchEnabled,
                                    onCheckedChange = { state.setWebSearchEnabled(it) }
                                )
                            }
                        }
                    }
                    if (inputFocused) {
                        // iOS animates a keyboard-dismiss chevron in while the field is focused.
                        IconButton(onClick = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Dismiss keyboard",
                                tint = if (isDark) OakColors.warmGray400 else OakColors.warmGray500
                            )
                        }
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { inputFocused = it.isFocused },
                        placeholder = { Text("Message your assistant...") },
                        shape = RoundedCornerShape(20.dp),
                        maxLines = 5,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        )
                    )
                    val canSend = input.isNotBlank() || attachments.isNotEmpty()
                    val sendScale by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (canSend || isSending) 1f else 0.92f,
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = 0.7f,
                            stiffness = 400f
                        ),
                        label = "sendScale"
                    )
                    IconButton(
                        onClick = { if (isSending) state.stopSending() else sendText(input.trim()) },
                        enabled = canSend || isSending,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(36.dp)
                            .graphicsLayer(scaleX = sendScale, scaleY = sendScale)
                            .clip(CircleShape)
                            .background(
                                if (!canSend && !isSending) {
                                    Brush.verticalGradient(
                                        listOf(
                                            if (isDark) OakColors.warmGray700 else OakColors.warmGray200,
                                            if (isDark) OakColors.warmGray700 else OakColors.warmGray200
                                        )
                                    )
                                } else {
                                    Brush.verticalGradient(listOf(OakColors.oakLight, OakColors.oakMedium))
                                }
                            )
                    ) {
                        if (isSending) {
                            Icon(
                                Icons.Filled.Stop,
                                contentDescription = "Stop generating",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        } else {
                            Icon(
                                Icons.Filled.ArrowUpward,
                                contentDescription = "Send",
                                tint = if (canSend) Color.White else (if (isDark) OakColors.warmGray400 else OakColors.warmGray500),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }

    // Port of ModelLoadingOverlay in iphone/AetherChat/ChatView.swift.
    modelLoadingMessage?.let { message ->
        ModelLoadingOverlay(message, isDark)
    }

    // Port of PromptEditorSheet in iphone/AetherChat/ChatView.swift.
    editDraft?.let { (messageId, originalText) ->
        PromptEditorSheet(
            initialText = originalText,
            isDark = isDark,
            onDismiss = { editDraft = null },
            onSubmit = { updated ->
                editDraft = null
                state.editUserMessage(conversationId, messageId, updated)
            }
        )
    }

    // Port of the rename alert in ChatView.swift (tappable navigation title).
    if (renamingChat) {
        RenameConversationDialog(
            currentTitle = conversation?.title ?: "",
            onDismiss = { renamingChat = false },
            onRename = { newTitle ->
                state.renameConversation(conversationId, newTitle)
                renamingChat = false
            }
        )
    }
    }
}

private fun Conversation.workspaceColor(): Color =
    Color(android.graphics.Color.parseColor("#${workspace.colorHex}"))

// Mirrors Workspace.icon on iOS: person.fill / briefcase.fill / paintpalette.fill / book.fill
private fun Conversation.workspaceIcon(): ImageVector = when (workspace.id) {
    "work" -> Icons.Filled.Work
    "creative" -> Icons.Filled.Palette
    "research" -> Icons.Filled.MenuBook
    else -> Icons.Filled.Person
}

/**
 * Port of MessageBubble in iphone/AetherChat/ChatView.swift, including the per-message
 * action row (copy / share / regenerate / thumbs rating / report for assistant turns;
 * copy / edit / resend for user turns) and the negative-feedback follow-up alert.
 */
@Composable
private fun MessageBubble(
    message: ChatMessage,
    conversation: Conversation?,
    isUser: Boolean,
    isDark: Boolean,
    fontScale: Double,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onEdit: (() -> Unit)?,
    onResend: (() -> Unit)?,
    onRating: (Boolean) -> Unit,
    onCorrection: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var selectedRating by remember { mutableStateOf<Boolean?>(null) }
    var showingNegativeFeedback by remember { mutableStateOf(false) }
    var showingCorrectionEditor by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val hasText = message.content.isNotBlank()

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_200)
            copied = false
        }
    }

    fun reportIssue() {
        CanopyShare.shareFeedback(
            context,
            CanopyFeedback.modelFeedback(message, conversation)
        )
    }

    // iOS pushes the opposite side with an expanding Spacer(minLength: 60) and caps a user
    // bubble at 320pt. A fixed-weight spacer left short user messages stranded mid-row.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = if (isUser) Modifier.widthIn(max = 320.dp) else Modifier.fillMaxWidth(0.9f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .then(
                        if (isUser) {
                            Modifier
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 20.dp,
                                        topEnd = 20.dp,
                                        bottomStart = 20.dp,
                                        bottomEnd = 4.dp
                                    )
                                )
                                .background(
                                    if (isDark) OakColors.oakMedium else OakColors.oakMedium.copy(alpha = 0.92f)
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        } else {
                            Modifier.padding(horizontal = 2.dp, vertical = 4.dp)
                        }
                    ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                message.attachments.forEach { attachment ->
                    MessageAttachmentThumbnail(attachment, isDark)
                }

                if (hasText) {
                    if (isUser) {
                        Text(
                            message.content,
                            color = Color.White,
                            fontSize = (15 * fontScale).sp,
                            lineHeight = (21 * fontScale).sp
                        )
                    } else {
                        MarkdownMessageText(
                            content = message.content,
                            isDark = isDark,
                            fontScale = fontScale,
                            color = if (isDark) OakColors.warmGray100 else OakColors.warmBlack
                        )
                    }
                }
            }

            if (hasText) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MessageActionButton(if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy, "Copy message", isDark) {
                        clipboard.setText(AnnotatedString(message.content))
                        copied = true
                    }

                    if (!isUser) {
                        MessageActionButton(Icons.Filled.IosShare, "Share message", isDark) {
                            CanopyShare.shareText(
                                context,
                                CanopyShare.messageText(message.content),
                                "Share message"
                            )
                        }
                        if (canRegenerate) {
                            MessageActionButton(Icons.Filled.Refresh, "Regenerate response", isDark, onClick = onRegenerate)
                        }
                        // Port of the thumbs rating pair in iphone/AetherChat/ChatView.swift.
                        MessageActionButton(
                            if (selectedRating == true) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                            "Helpful response",
                            isDark
                        ) {
                            selectedRating = true
                            onRating(true)
                        }
                        MessageActionButton(
                            if (selectedRating == false) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                            "Unhelpful response",
                            isDark
                        ) {
                            selectedRating = false
                            scope.launch {
                                // iOS waits 180 ms before presenting the follow-up alert.
                                kotlinx.coroutines.delay(180)
                                showingNegativeFeedback = true
                            }
                        }
                        MessageActionButton(Icons.Filled.Feedback, "Report model issue", isDark) {
                            reportIssue()
                        }
                    } else if (onEdit != null) {
                        MessageActionButton(Icons.Filled.Edit, "Edit message", isDark, onClick = onEdit)
                        if (onResend != null) {
                            MessageActionButton(Icons.Filled.Refresh, "Resend message", isDark, onClick = onResend)
                        }
                    }
                }
            }
        }
    }

    // Port of the "Help improve CanopyChat?" alert in iphone/AetherChat/ChatView.swift.
    if (showingNegativeFeedback) {
        AlertDialog(
            onDismissRequest = { showingNegativeFeedback = false },
            title = { Text("Help improve CanopyChat?") },
            text = {
                Text(
                    "Thanks for letting us know. We work hard to provide the best service " +
                        "to our customers, and your feedback helps us improve the model."
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        showingNegativeFeedback = false
                        showingCorrectionEditor = true
                    }) { Text("Add a correction", color = OakColors.oakMedium) }
                    TextButton(onClick = {
                        showingNegativeFeedback = false
                        reportIssue()
                    }) { Text("Tell us what went wrong", color = OakColors.oakMedium) }
                    TextButton(onClick = {
                        showingNegativeFeedback = false
                        CanopyShare.shareText(
                            context,
                            CanopyFeedback.modelFeedback(message, conversation),
                            "Share this failure"
                        )
                    }) { Text("Share this failure", color = OakColors.oakMedium) }
                    TextButton(onClick = { showingNegativeFeedback = false }) { Text("Not now") }
                }
            }
        )
    }

    // Port of ContributorCorrectionSheet in iphone/AetherChat/ChatView.swift.
    if (showingCorrectionEditor) {
        CorrectionEditorSheet(
            isDark = isDark,
            onDismiss = { showingCorrectionEditor = false },
            onSubmit = { correction ->
                showingCorrectionEditor = false
                onCorrection(correction)
            }
        )
    }
}

/** Finds the user prompt preceding an assistant reply, mirroring promptText(for:) on iOS. */
private fun promptTextBefore(conversation: Conversation, assistantMessageId: UUID): String? {
    val index = conversation.messages.indexOfFirst { it.id == assistantMessageId }
    if (index < 0) return null
    return conversation.messages.take(index).lastOrNull { it.role == MessageRole.USER }?.content
}

/** Port of ContributorCorrectionSheet in iphone/AetherChat/ChatView.swift. */
@Composable
private fun CorrectionEditorSheet(
    isDark: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var correction by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(if (isDark) OakColors.warmGray900 else OakColors.oakCream)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "Add a correction",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isDark) OakColors.oakCream else OakColors.warmBlack
            )
            Text(
                "What should CanopyChat have said instead?",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (isDark) OakColors.oakCream else OakColors.warmBlack
            )
            Text(
                "A correction is especially useful for improving the Contributor Beta model.",
                fontSize = 13.sp,
                color = oakSubtitle()
            )
            OutlinedTextField(
                value = correction,
                onValueChange = { correction = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                minLines = 5
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = oakSubtitle()) }
                TextButton(
                    onClick = { onSubmit(correction.trim()) },
                    enabled = correction.isNotBlank()
                ) {
                    Text("Submit", color = OakColors.oakMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun MessageActionButton(
    icon: ImageVector,
    contentDescription: String,
    isDark: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background((if (isDark) OakColors.warmGray800 else Color.White).copy(alpha = 0.82f))
            .clickable(onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = OakColors.oakMedium,
            modifier = Modifier.size(15.dp)
        )
    }
}

/** Port of MessageAttachmentThumbnail in iphone/AetherChat/ChatView.swift. */
@Composable
private fun MessageAttachmentThumbnail(attachment: ChatAttachment, isDark: Boolean) {
    if (attachment.isImage) {
        val bitmap = remember(attachment.id) {
            runCatching {
                BitmapFactory.decodeByteArray(attachment.data, 0, attachment.data.size)?.asImageBitmap()
            }.getOrNull()
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = attachment.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(14.dp))
            )
            return
        }
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background((if (isDark) OakColors.warmGray800 else OakColors.warmGray200).copy(alpha = 0.8f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.InsertDriveFile,
            contentDescription = null,
            tint = OakColors.oakMedium,
            modifier = Modifier.size(15.dp)
        )
        Text(
            attachment.displayName,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (isDark) OakColors.warmGray100 else OakColors.oakDark
        )
    }
}

/**
 * Port of SettingsView in iphone/AetherChat/SettingsView.swift, section for section:
 * Appearance, Default Workspace, AI Configuration, Subscription, Feedback, About.
 */
@Composable
fun SettingsScreen(
    state: AppState,
    subscription: CanopySubscriptionManager,
    onBack: () -> Unit,
    onSubscription: () -> Unit
) {
    val context = LocalContext.current
    val isDark by state.isDarkTheme.collectAsState()
    val customPrompt by state.customSystemPrompt.collectAsState()
    val defaultWorkspaceId by state.defaultWorkspaceId.collectAsState()
    val fontScale by state.messageFontScale.collectAsState()
    // Observed so deleting a custom workspace recomposes this screen.
    val customWorkspaces by state.workspacesFlow.collectAsState()
    val workspaces = remember(customWorkspaces) { state.availableWorkspaces }
    val hasPremium by subscription.hasPremiumAccess.collectAsState()
    val testAccessUnlocked by subscription.testAccessUnlocked.collectAsState()
    val subscriptionError by subscription.errorMessage.collectAsState()
    val recentlyDeleted by state.recentlyDeleted.collectAsState()
    var showingSystemPreferences by remember { mutableStateOf(false) }
    var showingRecentlyDeleted by remember { mutableStateOf(false) }

    // Mirrors SettingsView.onAppear on iOS, which pins the shipping configuration.
    LaunchedEffect(Unit) {
        state.setSelectedModel(ModelCatalog.CANOPY_V1_DISPLAY_NAME)
    }

    if (showingRecentlyDeleted) {
        RecentlyDeletedScreen(state = state, isDark = isDark, onBack = { showingRecentlyDeleted = false })
        return
    }

    OakBackground(isDark = isDark) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
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

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                SettingsSection("Appearance", isDark) {
                    SettingsCard(isDark) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Dark Mode", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                    Text("Oak-toned dark theme", fontSize = 12.sp, color = oakSubtitle())
                                }
                                Switch(checked = isDark, onCheckedChange = { state.setDarkTheme(it) })
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                            FontSizeSettingsRow(fontScale) { state.setMessageFontScale(it) }
                        }
                    }
                }

                SettingsSection("Default Workspace", isDark) {
                    SettingsCard(isDark) {
                        Column {
                            workspaces.forEachIndexed { index, workspace ->
                                WorkspacePickerRow(
                                    workspace = workspace,
                                    isSelected = defaultWorkspaceId == workspace.id,
                                    onSelect = { state.setDefaultWorkspace(workspace) },
                                    onDelete = { state.deleteCustomWorkspace(workspace) }
                                )
                                if (index != workspaces.lastIndex) {
                                    HorizontalDivider(modifier = Modifier.padding(start = 40.dp))
                                }
                            }
                        }
                    }
                }

                SettingsSection("AI Configuration", isDark) {
                    SettingsCard(isDark) {
                        Column {
                            SettingsInfoRow("Model", "${ModelCatalog.CANOPY_V1_DISPLAY_NAME} · v${ActiveModelVersion.current}")
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            SettingsInfoRow("Inference", "On device")
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            SettingsNavRow(
                                title = "System Preferences",
                                subtitle = if (customPrompt.isBlank()) {
                                    "Default response behavior"
                                } else {
                                    "Global preferences enabled"
                                },
                                onClick = { showingSystemPreferences = true }
                            )
                        }
                    }
                }

                SettingsSection("Subscription", isDark) {
                    SettingsCard(isDark) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("CanopyChat Plus", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        if (hasPremium) "Active" else "Not active",
                                        fontSize = 12.sp,
                                        color = oakSubtitle()
                                    )
                                }
                                TextButton(onClick = onSubscription) {
                                    Text("View", color = OakColors.oakMedium, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            SettingsNavRow(
                                title = "Restore Purchases",
                                // iOS surfaces the subscription error in this subtitle slot.
                                subtitle = subscriptionError ?: "Recover an active Google Play subscription",
                                onClick = { subscription.restorePurchases() }
                            )
                            if (subscription.canRedeemTestAccessCode && testAccessUnlocked) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                SettingsNavRow(
                                    title = "Reset Test Access",
                                    subtitle = "Show the paywall again for screenshots",
                                    onClick = { subscription.resetTestAccess() }
                                )
                            }
                        }
                    }
                }

                SettingsSection("Chats", isDark) {
                    SettingsCard(isDark) {
                        SettingsNavRow(
                            title = "Recently Deleted",
                            subtitle = if (recentlyDeleted.isEmpty()) {
                                "Deleted chats stay here for ${AppState.DELETED_RETENTION_DAYS} days"
                            } else {
                                "${recentlyDeleted.size} chat${if (recentlyDeleted.size == 1) "" else "s"}"
                            },
                            onClick = { showingRecentlyDeleted = true }
                        )
                    }
                }

                SettingsSection("Feedback", isDark) {
                    SettingsCard(isDark) {
                        Column {
                            SettingsNavRow(
                                title = "Report Issue",
                                subtitle = "Send app or model feedback",
                                onClick = { CanopyShare.shareFeedback(context, CanopyFeedback.appIssue()) }
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { openUrl(context, CanopyLegal.PRIVACY_POLICY_URL) }) {
                                    Text("Privacy", color = OakColors.oakMedium, fontSize = 12.sp)
                                }
                                TextButton(onClick = { openUrl(context, CanopyLegal.TERMS_OF_USE_URL) }) {
                                    Text("Terms", color = OakColors.oakMedium, fontSize = 12.sp)
                                }
                                TextButton(onClick = { openUrl(context, CanopyLegal.SUPPORT_URL) }) {
                                    Text("Support", color = OakColors.oakMedium, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // Port of the Accuracy & Safety section in iphone/AetherChat/SettingsView.swift
                // (production builds only; contributor builds show Beta Program instead).
                if (!com.nathanaelguitar.canopychat.core.ContributorProgram.isContributorBuild) {
                    SettingsSection("Accuracy & Safety", isDark) {
                        SettingsCard(isDark) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.Warning,
                                        contentDescription = null,
                                        tint = OakColors.amber,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        "Canopy may make mistakes",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isDark) OakColors.oakCream else OakColors.warmBlack
                                    )
                                }
                                Text(
                                    "Responses may be inaccurate, incomplete, or outdated. Verify important " +
                                        "information independently. CanopyChat is not a substitute for medical, " +
                                        "legal, financial, safety, or emergency advice.",
                                    fontSize = 13.sp,
                                    color = oakSubtitle()
                                )
                            }
                        }
                    }
                }

                // Port of the Beta Program section in iphone/AetherChat/SettingsView.swift.
                if (com.nathanaelguitar.canopychat.core.ContributorProgram.isContributorBuild) {
                    val betaTelemetry = remember { com.nathanaelguitar.canopychat.core.BetaTelemetry.shared(context.applicationContext) }
                    var telemetryEnabled by remember { mutableStateOf(betaTelemetry.isEnabled) }
                    SettingsSection("Beta Program", isDark) {
                        SettingsCard(isDark) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Help improve CanopyChat", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                        Text(
                                            "Share selected prompts, responses, failures, corrections, " +
                                                "regenerations, and a small comparison sample",
                                            fontSize = 12.sp,
                                            color = oakSubtitle()
                                        )
                                    }
                                    Switch(
                                        checked = telemetryEnabled,
                                        onCheckedChange = { enabled ->
                                            betaTelemetry.setEnabled(enabled)
                                            telemetryEnabled = betaTelemetry.isEnabled
                                        }
                                    )
                                }
                                Text(
                                    "Turn this off anytime to stop contributing. Unsent contributor " +
                                        "data is deleted immediately.",
                                    fontSize = 12.sp,
                                    color = oakSubtitle()
                                )
                            }
                        }
                    }
                }

                SettingsSection("About", isDark) {
                    SettingsCard(isDark) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(OakColors.oakMedium),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_canopy_tree),
                                    contentDescription = null,
                                    tint = OakColors.oakCream,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text("CanopyChat", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Version 1.0.0 · Rooted Intelligence",
                                    fontSize = 12.sp,
                                    color = oakSubtitle()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showingSystemPreferences) {
        SystemPreferencesSheet(
            prompt = customPrompt,
            isDark = isDark,
            onDismiss = { showingSystemPreferences = false },
            onSave = {
                state.setCustomSystemPrompt(it)
                showingSystemPreferences = false
            }
        )
    }
}

/** Port of SettingsSection in iphone/AetherChat/SettingsView.swift. */
@Composable
private fun SettingsSection(title: String, isDark: Boolean, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title.uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isDark) OakColors.warmGray400 else OakColors.warmGray500
        )
        content()
    }
}

/** Port of SettingsInfoRow in iphone/AetherChat/SettingsView.swift. */
@Composable
private fun SettingsInfoRow(title: String, subtitle: String) {
    Column {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, fontSize = 12.sp, color = oakSubtitle())
    }
}

/** Port of SettingsNavRow in iphone/AetherChat/SettingsView.swift. */
@Composable
private fun SettingsNavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = oakSubtitle())
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = OakColors.warmGray400
        )
    }
}

/** Port of FontSizeSettingsRow in iphone/AetherChat/SettingsView.swift. */
@Composable
private fun FontSizeSettingsRow(fontScale: Double, onChange: (Double) -> Unit) {
    val label = when {
        fontScale < 0.95 -> "Compact"
        fontScale < 1.1 -> "Standard"
        fontScale < 1.25 -> "Large"
        else -> "Extra large"
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Chat Text Size", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(label, fontSize = 12.sp, color = oakSubtitle())
            }
            Text(
                "${(fontScale * 100).toInt()}%",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = OakColors.oakMedium
            )
        }
        Slider(
            value = fontScale.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = 0.85f..1.35f,
            // 0.85..1.35 in 0.05 steps == 10 interior stops.
            steps = 9,
            colors = SliderDefaults.colors(
                thumbColor = OakColors.oakMedium,
                activeTrackColor = OakColors.oakMedium,
                activeTickColor = OakColors.oakCream,
                inactiveTrackColor = OakColors.oakMedium.copy(alpha = 0.22f),
                inactiveTickColor = OakColors.oakMedium.copy(alpha = 0.4f)
            )
        )
    }
}

/** Port of WorkspacePickerRow in iphone/AetherChat/SettingsView.swift. */
@Composable
private fun WorkspacePickerRow(
    workspace: Workspace,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color(android.graphics.Color.parseColor("#${workspace.colorHex}")))
        )
        Text(workspace.name, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (isSelected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Selected",
                tint = Color(android.graphics.Color.parseColor("#${workspace.colorHex}")),
                modifier = Modifier.size(18.dp)
            )
        }
        // iOS exposes delete via swipe action; Compose lists use an explicit affordance.
        if (!workspace.isBuiltIn) {
            TextButton(onClick = onDelete) {
                Text("Delete", color = OakColors.error, fontSize = 12.sp)
            }
        }
    }
}

/** Port of SystemPreferencesSheet in iphone/AetherChat/SettingsView.swift. */
@Composable
private fun SystemPreferencesSheet(
    prompt: String,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var draft by remember { mutableStateOf(prompt) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(if (isDark) OakColors.warmGray900 else OakColors.oakCream)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "System Preferences",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isDark) OakColors.oakCream else OakColors.warmBlack
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                minLines = 6,
                placeholder = { Text("e.g. Use concise bullet points and a warm tone.") }
            )
            Text(
                "Use this for persistent response preferences, such as tone, formatting, " +
                    "verbosity, or how you like answers structured. Per-chat assistant " +
                    "instructions, web grounding, and safety rules still take priority.",
                fontSize = 12.sp,
                color = oakSubtitle()
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { draft = "" }) {
                    Text("Clear Preferences", color = OakColors.error, fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = oakSubtitle())
                }
                TextButton(onClick = { onSave(draft.trim()) }) {
                    Text("Save", color = OakColors.oakMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Port of PaywallView in iphone/AetherChat/PaywallView.swift, including the selectable
 * plan picker, gradient subscribe CTA, error card with Try Again, and single
 * "Have a test code?" disclosure. When [gated] is true (post-Welcome hard gate,
 * mirroring ContentView on iOS) there is no way back without subscribing.
 */
@Composable
fun PaywallScreen(
    subscription: CanopySubscriptionManager,
    isDark: Boolean,
    gated: Boolean,
    onBack: (() -> Unit)?
) {
    val context = LocalContext.current
    val products by subscription.products.collectAsState()
    val loading by subscription.isLoading.collectAsState()
    val error by subscription.errorMessage.collectAsState()
    var selectedPlan by remember { mutableStateOf(Plan.YEARLY) }
    var showingTestCodeField by remember { mutableStateOf(false) }
    var testAccessCode by remember { mutableStateOf("") }

    // Mirrors PaywallView.task on iOS.
    LaunchedEffect(Unit) { subscription.refresh() }

    val monthly = products.firstOrNull { it.productId == CanopySubscriptionManager.MONTHLY_PRODUCT_ID }
    val yearly = products.firstOrNull { it.productId == CanopySubscriptionManager.YEARLY_PRODUCT_ID }
    val monthlyPrice = monthly.formattedPrice() ?: "$9.99"
    val yearlyPrice = yearly.formattedPrice() ?: "$89.99"

    val cardBackground = if (isDark) OakColors.warmGray900.copy(alpha = 0.78f) else Color.White.copy(alpha = 0.74f)
    val fieldBackground = if (isDark) OakColors.warmGray800.copy(alpha = 0.86f) else Color.White.copy(alpha = 0.82f)

    OakBackground(isDark = isDark) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!gated && onBack != null) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = OakColors.oakMedium)
                    }
                }
            } else {
                Spacer(Modifier.height(12.dp))
            }

            // Header: green-gradient tree badge + Plus branding.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.verticalGradient(listOf(OakColors.forestMedium, OakColors.forestDark))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.ic_canopy_tree),
                        contentDescription = null,
                        tint = OakColors.oakCream,
                        modifier = Modifier.size(44.dp)
                    )
                }
                Text(
                    "CanopyChat Plus",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.Serif,
                    color = if (isDark) OakColors.oakCream else OakColors.oakDark
                )
                Text(
                    "On-device intelligence, without limits",
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Serif,
                    color = OakColors.oakLight
                )
            }

            Spacer(Modifier.height(22.dp))

            // Features card with tinted icon tiles.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(cardBackground)
                    .border(
                        1.dp,
                        if (isDark) Color.White.copy(alpha = 0.08f) else OakColors.oakPale.copy(alpha = 0.6f),
                        RoundedCornerShape(22.dp)
                    )
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PaywallFeature(Icons.Filled.PhoneAndroid, "On-device Intelligence", "Private local inference, right on your phone.", isDark)
                PaywallFeature(Icons.Filled.Lock, "Built for privacy", "Your conversations stay on your device by default.", isDark)
                PaywallFeature(Icons.Filled.Public, "Search when needed", "Web-grounded, location-aware answers when you ask.", isDark)
            }

            Spacer(Modifier.height(22.dp))

            // Plan picker.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PlanCard(
                    title = "Yearly",
                    price = yearlyPrice,
                    detail = "Billed once a year",
                    badge = "BEST VALUE — SAVE 25%",
                    selected = selectedPlan == Plan.YEARLY,
                    isDark = isDark,
                    onClick = { selectedPlan = Plan.YEARLY }
                )
                PlanCard(
                    title = "Monthly",
                    price = monthlyPrice,
                    detail = "Billed monthly",
                    badge = null,
                    selected = selectedPlan == Plan.MONTHLY,
                    isDark = isDark,
                    onClick = { selectedPlan = Plan.MONTHLY }
                )
            }

            Spacer(Modifier.height(22.dp))

            // Gradient subscribe CTA feeding the selected plan.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.verticalGradient(listOf(OakColors.oakLight, OakColors.oakMedium))
                    )
                    .clickable(enabled = !loading) {
                        (context as? Activity)?.let { activity ->
                            val product = if (selectedPlan == Plan.YEARLY) yearly else monthly
                            product?.let { subscription.purchase(activity, it) }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text(
                        "Subscribe — ${if (selectedPlan == Plan.YEARLY) yearlyPrice else monthlyPrice}",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Secondary actions.
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                TextButton(onClick = subscription::restorePurchases, enabled = !loading) {
                    Text(
                        "Restore Purchases",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDark) OakColors.oakLight else OakColors.oakMedium
                    )
                }
                if (subscription.canRedeemTestAccessCode) {
                    TextButton(onClick = {
                        showingTestCodeField = !showingTestCodeField
                        if (!showingTestCodeField) testAccessCode = ""
                    }) {
                        Text(
                            if (showingTestCodeField) "Hide Test Code" else "Have a test code?",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isDark) OakColors.warmGray400 else OakColors.warmGray500
                        )
                    }
                }
            }

            if (showingTestCodeField) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = testAccessCode,
                        onValueChange = { testAccessCode = it.uppercase() },
                        placeholder = { Text("Access code") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).background(fieldBackground, RoundedCornerShape(14.dp))
                    )
                    Button(
                        onClick = {
                            if (subscription.redeemTestAccessCode(testAccessCode)) {
                                testAccessCode = ""
                                showingTestCodeField = false
                            }
                        },
                        enabled = testAccessCode.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = OakColors.oakMedium),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(46.dp)
                    ) {
                        Text("Redeem", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }

            if (products.isEmpty() && !loading && error == null) {
                Text(
                    "Subscription products will appear here after Google Play setup.",
                    fontSize = 13.sp,
                    color = oakSubtitle(),
                    textAlign = TextAlign.Center
                )
            }

            // Error card with Try Again, mirroring errorBanner in PaywallView.swift.
            error?.let { message ->
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(OakColors.error.copy(alpha = if (isDark) 0.16f else 0.08f))
                        .border(1.dp, OakColors.error.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = OakColors.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            message,
                            fontSize = 13.sp,
                            color = if (isDark) OakColors.warmGray200 else OakColors.warmGray700
                        )
                    }
                    if (products.isEmpty() && !loading) {
                        Text(
                            "Try Again",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OakColors.error,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(OakColors.error.copy(alpha = 0.12f))
                                .clickable { subscription.refresh() }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            // Legal footer.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 44.dp)
            ) {
                Text(
                    "CanopyChat Plus is available as a monthly ($monthlyPrice/month) or yearly " +
                        "($yearlyPrice/year) auto-renewable subscription. It renews automatically unless " +
                        "cancelled at least 24 hours before the end of the current period. Manage or " +
                        "cancel anytime from your Google Play subscriptions.",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    textAlign = TextAlign.Center,
                    color = if (isDark) OakColors.warmGray400 else OakColors.warmGray600
                )
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    TextButton(onClick = { openUrl(context, CanopyLegal.PRIVACY_POLICY_URL) }) {
                        Text("Privacy Policy", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = OakColors.oakMedium)
                    }
                    TextButton(onClick = { openUrl(context, CanopyLegal.TERMS_OF_USE_URL) }) {
                        Text("Terms of Use", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = OakColors.oakMedium)
                    }
                }
            }
        }
    }
}

private enum class Plan { MONTHLY, YEARLY }

/** Port of planCard in iphone/AetherChat/PaywallView.swift. */
@Composable
private fun PlanCard(
    title: String,
    price: String,
    detail: String,
    badge: String?,
    selected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                when {
                    selected && isDark -> OakColors.warmGray800
                    selected -> Color.White.copy(alpha = 0.9f)
                    isDark -> Color.White.copy(alpha = 0.04f)
                    else -> Color.White.copy(alpha = 0.5f)
                }
            )
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) OakColors.oakMedium else {
                    if (isDark) Color.White.copy(alpha = 0.1f) else OakColors.oakPale.copy(alpha = 0.7f)
                },
                RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            tint = if (selected) OakColors.oakMedium else OakColors.warmGray400,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDark) OakColors.oakCream else OakColors.warmBlack
                )
                badge?.let {
                    Text(
                        it,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(OakColors.forestMedium)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Text(
                detail,
                fontSize = 12.sp,
                color = if (isDark) OakColors.warmGray400 else OakColors.warmGray500
            )
        }
        Text(
            price,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = if (isDark) OakColors.oakCream else OakColors.oakDark
        )
    }
}

private fun com.android.billingclient.api.ProductDetails?.formattedPrice(): String? =
    this?.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice

/**
 * Port of RecentlyDeletedView in iphone/AetherChat/SettingsView.swift, including the
 * empty state, per-row restore, delete-now, and the Empty-all confirmation.
 */
@Composable
private fun RecentlyDeletedScreen(state: AppState, isDark: Boolean, onBack: () -> Unit) {
    val recentlyDeleted by state.recentlyDeleted.collectAsState()
    var showEmptyConfirm by remember { mutableStateOf(false) }

    OakBackground(isDark = isDark) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Done", tint = OakColors.oakMedium)
                }
                Text(
                    "Recently Deleted",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDark) OakColors.oakCream else OakColors.oakDark,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { showEmptyConfirm = true },
                    enabled = recentlyDeleted.isNotEmpty()
                ) {
                    Text(
                        "Empty",
                        color = if (recentlyDeleted.isEmpty()) OakColors.warmGray400 else OakColors.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (recentlyDeleted.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .background(OakColors.warmGray400.copy(alpha = if (isDark) 0.18f else 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = OakColors.warmGray400,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Nothing here",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Serif,
                        color = if (isDark) OakColors.oakCream else OakColors.warmBlack
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Deleted chats stay here for ${AppState.DELETED_RETENTION_DAYS} days before they're removed for good.",
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        color = oakSubtitle()
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(recentlyDeleted, key = { it.id }) { item ->
                        RecentlyDeletedRow(
                            item = item,
                            isDark = isDark,
                            onRestore = { state.restoreDeleted(item.id) },
                            onDeleteNow = { state.permanentlyDeleteConversation(item.id) }
                        )
                    }
                }
            }
        }
    }

    if (showEmptyConfirm) {
        AlertDialog(
            onDismissRequest = { showEmptyConfirm = false },
            title = { Text("Permanently delete all recently deleted chats?") },
            confirmButton = {
                TextButton(onClick = {
                    state.emptyRecentlyDeleted()
                    showEmptyConfirm = false
                }) { Text("Delete All Forever", color = OakColors.error) }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

/** Port of RecentlyDeletedRow in iphone/AetherChat/SettingsView.swift. */
@Composable
private fun RecentlyDeletedRow(
    item: com.nathanaelguitar.canopychat.core.DeletedConversation,
    isDark: Boolean,
    onRestore: () -> Unit,
    onDeleteNow: () -> Unit
) {
    val daysLeft = (
        (AppState.DELETED_RETENTION_DAYS -
            (System.currentTimeMillis() - item.deletedAtMillis) / 86_400_000L)
        ).coerceAtLeast(0)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isDark) OakColors.warmGray800 else Color.White)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                item.conversation.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isDark) OakColors.warmGray200 else OakColors.warmBlack
            )
            Text(
                item.conversation.previewText.ifEmpty { "No messages" },
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = oakSubtitle()
            )
            Text(
                if (daysLeft > 0) "$daysLeft day${if (daysLeft == 1L) "" else "s"} left" else "Deleting soon",
                fontSize = 11.sp,
                color = OakColors.warmGray400
            )
        }
        Spacer(Modifier.width(12.dp))
        TextButton(onClick = onRestore) {
            Icon(
                Icons.Filled.Restore,
                contentDescription = null,
                tint = OakColors.oakMedium,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text("Restore", color = OakColors.oakMedium, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = onDeleteNow) {
            Text("Delete Now", color = OakColors.error, fontSize = 13.sp)
        }
    }
}

/** Port of PaywallFeature in iphone/AetherChat/PaywallView.swift. */
@Composable
private fun PaywallFeature(icon: ImageVector, title: String, subtitle: String, isDark: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(OakColors.oakMedium.copy(alpha = if (isDark) 0.18f else 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = OakColors.oakMedium, modifier = Modifier.size(16.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isDark) OakColors.oakCream else OakColors.warmBlack
            )
            Text(subtitle, fontSize = 13.sp, color = if (isDark) OakColors.warmGray400 else OakColors.warmGray500)
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
