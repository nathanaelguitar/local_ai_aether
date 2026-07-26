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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.gestures.detectTapGestures
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
                Icon(
                    painterResource(R.drawable.ic_canopy_tree),
                    contentDescription = null,
                    tint = OakColors.oakCream,
                    modifier = Modifier.size(58.dp)
                )
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
            Spacer(Modifier.height(32.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                FeatureRow(
                    "🔒",
                    "Privacy First",
                    "CanopyChat runs locally on your phone by default",
                    isDark
                )
                FeatureRow(
                    "🌿",
                    "Eco-Friendly Intelligence",
                    "Use the model already in your hand instead of a data center",
                    isDark
                )
                FeatureRow(
                    "🌳",
                    "Organized by Workspace",
                    "Separate Personal, Work, Creative, and Research conversations",
                    isDark
                )
            }
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
    // (conversationId, currentTitle) for the rename dialog.
    var renaming by remember { mutableStateOf<Pair<UUID, String>?>(null) }
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
                            onDelete = { state.deleteConversation(conversation.id) }
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
                            onDelete = { state.deleteConversation(conversation.id) }
                        )
                    }
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

                Text("Workspace", fontSize = 12.sp, color = OakColors.warmGray500)
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

                Text("Assistant", fontSize = 12.sp, color = OakColors.warmGray500)
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
                        color = OakColors.warmGray500
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

/** Port of FeatureRow in iphone/AetherChat/WelcomeView.swift. */
@Composable
private fun FeatureRow(icon: String, title: String, subtitle: String, isDark: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(icon, fontSize = 32.sp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isDark) OakColors.oakPale else OakColors.oakDark
            )
            Text(
                subtitle,
                fontSize = 13.sp,
                color = if (isDark) OakColors.warmGray400 else OakColors.warmGray600
            )
        }
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
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Title") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(draft) }, enabled = draft.isNotBlank()) {
                Text("Save", color = OakColors.oakMedium, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
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
            Text(persona.description, fontSize = 12.sp, color = OakColors.warmGray500)
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
                color = OakColors.warmGray500
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = OakColors.warmGray500) }
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
                    color = OakColors.warmGray500
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
    val modelLoadingMessage by state.modelLoadingMessage.collectAsState()
    val fontScale by state.messageFontScale.collectAsState()
    val conversation = conversations.firstOrNull { it.id == conversationId }
    val messages = conversation?.messages ?: emptyList()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<ChatAttachment>>(emptyList()) }
    // Port of PromptEditDraft on iOS: (messageId, originalText).
    var editDraft by remember { mutableStateOf<Pair<UUID, String>?>(null) }
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

    LaunchedEffect(messages.size, isSending) {
        val extraTypingRow = if (isSending) 1 else 0
        val lastIndex = messages.size + extraTypingRow - 1
        if (lastIndex >= 0) {
            listState.animateScrollToItem(lastIndex)
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
                        onRegenerate = { state.regenerateLastResponse(conversationId) },
                        onEdit = if (isUser && !isSending) {
                            { editDraft = message.id to message.content }
                        } else {
                            null
                        }
                    )
                }
                if (isSending) {
                    item { TypingIndicator(status, isDark) }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isDark) OakColors.warmGray900.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.88f))
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
                        Text("Reading attachment…", fontSize = 12.sp, color = OakColors.warmGray500)
                    }
                }
                if (attachments.isNotEmpty()) {
                    AttachmentTray(
                        attachments = attachments,
                        isDark = isDark,
                        onRemove = { target -> attachments = attachments.filterNot { it.id == target.id } }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Box {
                        IconButton(onClick = { showAttachMenu = true }) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Add attachment",
                                tint = OakColors.oakMedium
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
                        }
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
                    if (isSending) {
                        // Port of ChatView.stopSending on iOS.
                        IconButton(
                            onClick = { state.stopSending() },
                            modifier = Modifier.size(48.dp).background(OakColors.error, CircleShape)
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = "Stop generating", tint = Color.White)
                        }
                    } else {
                        IconButton(
                            onClick = { sendText(input.trim()) },
                            enabled = input.isNotBlank() || attachments.isNotEmpty(),
                            modifier = Modifier.size(48.dp).background(OakColors.oakMedium, CircleShape)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                        }
                    }
                }
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
 * action row (copy / share / regenerate / report for assistant turns; copy / edit for
 * user turns).
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
    onEdit: (() -> Unit)?
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val hasText = message.content.isNotBlank()

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_200)
            copied = false
        }
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
                        MessageActionButton(Icons.Filled.Feedback, "Report model issue", isDark) {
                            CanopyShare.shareFeedback(
                                context,
                                CanopyFeedback.modelFeedback(message, conversation)
                            )
                        }
                    } else if (onEdit != null) {
                        MessageActionButton(Icons.Filled.Edit, "Edit message", isDark, onClick = onEdit)
                    }
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
    var showingSystemPreferences by remember { mutableStateOf(false) }

    // Mirrors SettingsView.onAppear on iOS, which pins the shipping configuration.
    LaunchedEffect(Unit) {
        state.setSelectedModel(ModelCatalog.CANOPY_V1_DISPLAY_NAME)
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
                                    Text("Oak-toned dark theme", fontSize = 12.sp, color = OakColors.warmGray500)
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
                            SettingsInfoRow("Model", ModelCatalog.CANOPY_V1_DISPLAY_NAME)
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
                                        color = OakColors.warmGray500
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
                                    color = OakColors.warmGray500
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
        Text(subtitle, fontSize = 12.sp, color = OakColors.warmGray500)
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
            Text(subtitle, fontSize = 12.sp, color = OakColors.warmGray500)
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
                Text(label, fontSize = 12.sp, color = OakColors.warmGray500)
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
                color = OakColors.warmGray500
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { draft = "" }) {
                    Text("Clear Preferences", color = OakColors.error, fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = OakColors.warmGray500)
                }
                TextButton(onClick = { onSave(draft.trim()) }) {
                    Text("Save", color = OakColors.oakMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun PaywallScreen(
    subscription: CanopySubscriptionManager,
    isDark: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val products by subscription.products.collectAsState()
    val loading by subscription.isLoading.collectAsState()
    val error by subscription.errorMessage.collectAsState()
    var showingTestingOptions by remember { mutableStateOf(false) }
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = OakColors.oakMedium)
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .background(OakColors.oakMedium),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.ic_canopy_tree),
                        contentDescription = null,
                        tint = OakColors.oakCream,
                        modifier = Modifier.size(60.dp)
                    )
                }
                Text(
                    "CanopyChat",
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Thin,
                    fontFamily = FontFamily.Serif,
                    color = if (isDark) OakColors.oakCream else OakColors.oakDark
                )
                Text(
                    "Eco-Friendly Intelligence",
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Serif,
                    color = OakColors.oakLight
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(cardBackground)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PaywallFeature("\uD83D\uDCF1", "On-device AI", "Run private chats locally on your phone.")
                PaywallFeature("\uD83D\uDD12", "Built for privacy", "Your conversations stay on your device by default.")
                PaywallFeature("\uD83D\uDD0D", "Search when needed", "Use web grounding and location-aware answers when you ask.")
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { (context as? Activity)?.let { a -> yearly?.let { subscription.purchase(a, it) } } },
                    enabled = !loading && yearly != null,
                    colors = ButtonDefaults.buttonColors(containerColor = OakColors.oakMedium),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().height(64.dp)
                ) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$yearlyPrice/year", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text("Save 25%", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.8f))
                        }
                    }
                }

                OutlinedButton(
                    onClick = { (context as? Activity)?.let { a -> monthly?.let { subscription.purchase(a, it) } } },
                    enabled = !loading && monthly != null,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.5.dp, OakColors.oakMedium),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text("$monthlyPrice/month", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = OakColors.oakMedium)
                }

                if (products.isEmpty()) {
                    Text(
                        "Subscription products will appear here after Google Play setup.",
                        fontSize = 13.sp,
                        color = OakColors.warmGray500,
                        textAlign = TextAlign.Center
                    )
                }

                TextButton(onClick = subscription::restorePurchases, enabled = !loading) {
                    Text("Restore Purchases", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = OakColors.oakMedium)
                }

                // Port of the nested "Testing Options" disclosure on iOS.
                if (subscription.canRedeemTestAccessCode) {
                    TextButton(onClick = {
                        showingTestingOptions = !showingTestingOptions
                        if (!showingTestingOptions) {
                            showingTestCodeField = false
                            testAccessCode = ""
                        }
                    }) {
                        Text(
                            if (showingTestingOptions) "Hide Testing Options" else "Testing Options",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = OakColors.warmGray500.copy(alpha = 0.72f)
                        )
                    }

                    if (showingTestingOptions) {
                        TextButton(onClick = { showingTestCodeField = !showingTestCodeField }) {
                            Text(
                                if (showingTestCodeField) "Hide Test Code" else "Have a test code?",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = OakColors.warmGray500
                            )
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
                                    onClick = { if (subscription.redeemTestAccessCode(testAccessCode)) testAccessCode = "" },
                                    enabled = testAccessCode.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(containerColor = OakColors.oakMedium),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.height(46.dp)
                                ) {
                                    Text("Redeem", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            error?.let {
                Text(it, fontSize = 13.sp, color = OakColors.error, textAlign = TextAlign.Center)
            }

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
                    fontSize = 12.sp,
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

private fun com.android.billingclient.api.ProductDetails?.formattedPrice(): String? =
    this?.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice

/** Port of PaywallFeature in iphone/AetherChat/PaywallView.swift. */
@Composable
private fun PaywallFeature(glyph: String, title: String, subtitle: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(glyph, fontSize = 20.sp, modifier = Modifier.width(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 13.sp, color = OakColors.warmGray500)
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
