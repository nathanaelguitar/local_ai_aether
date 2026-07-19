import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var state: AppState
    @EnvironmentObject var subscription: CanopySubscriptionManager
    @Environment(\.dismiss) var dismiss
    @State private var showingSystemPreferences = false
    @State private var showingRecentlyDeleted = false
    @State private var sharePayload: SharePayload?
    @State private var betaTelemetryEnabled = AetherBetaTelemetry.shared.isEnabled
    @State private var showingContributorConsent = false

    var body: some View {
        NavigationStack {
            OakBackground {
                ScrollView {
                    VStack(spacing: 20) {
                        // Appearance
                        SettingsSection(title: "Appearance") {
                            VStack(spacing: 0) {
                                SettingsSwitchRowContent(icon: "moon.fill", title: "Dark Mode",
                                                         subtitle: "Oak-toned dark theme",
                                                         isOn: $state.isDarkTheme)
                                Divider().padding(.leading, 56)
                                FontSizeSettingsRow(fontScale: $state.messageFontScale)
                            }
                            .background(Color(UIColor.secondarySystemBackground))
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }

                        // Default workspace
                        SettingsSection(title: "Default Workspace") {
                            VStack(spacing: 0) {
                                ForEach(state.availableWorkspaces) { ws in
                                    WorkspacePickerRow(
                                        workspace: ws,
                                        isSelected: state.defaultWorkspace == ws,
                                        onSelect: { state.defaultWorkspace = ws },
                                        onDelete: { state.deleteWorkspace(ws) }
                                    )
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 12)
                                    if ws != state.availableWorkspaces.last {
                                        Divider().padding(.leading, 56)
                                    }
                                }
                            }
                            .background(Color(UIColor.secondarySystemBackground))
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }

                        // Chats
                        SettingsSection(title: "Chats") {
                            SettingsNavRow(
                                icon: "trash",
                                title: "Recently Deleted",
                                subtitle: state.recentlyDeleted.isEmpty
                                    ? "Deleted chats are kept for \(AppState.deletedRetentionDays) days"
                                    : "\(state.recentlyDeleted.count) chat\(state.recentlyDeleted.count == 1 ? "" : "s") \u{00B7} kept \(AppState.deletedRetentionDays) days"
                            ) {
                                showingRecentlyDeleted = true
                            }
                            .background(Color(UIColor.secondarySystemBackground))
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }

                        // AI config
                        SettingsSection(title: "AI Configuration") {
                            VStack(spacing: 0) {
                                SettingsInfoRow(icon: "cpu", title: "Model", subtitle: AetherModelCatalog.aetherV1DisplayName)
                                Divider().padding(.leading, 56)
                                SettingsInfoRow(icon: "iphone.gen3", title: "Inference", subtitle: "On device")
                                Divider().padding(.leading, 56)
                                SettingsNavRow(
                                    icon: "person.crop.circle.badge.gearshape",
                                    title: "System Preferences",
                                    subtitle: state.customSystemPrompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Default response behavior" : "Global preferences enabled"
                                ) {
                                    showingSystemPreferences = true
                                }
                            }
                            .background(Color(UIColor.secondarySystemBackground))
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }

                        // Subscription
                        SettingsSection(title: "Subscription") {
                            VStack(spacing: 0) {
                                SettingsInfoRow(
                                    icon: "leaf.circle.fill",
                                    title: "CanopyChat Plus",
                                    subtitle: subscription.hasPremiumAccess ? "Active" : "Not active"
                                )
                                Divider().padding(.leading, 56)
                                Button {
                                    Task { await subscription.restorePurchases() }
                                } label: {
                                    SettingsRowLabel(
                                        icon: "arrow.clockwise.circle",
                                        title: "Restore Purchases",
                                        subtitle: subscription.errorMessage ?? "Recover an active Apple subscription"
                                    )
                                }
                                .buttonStyle(.plain)
                                if subscription.canRedeemTestAccessCode, subscription.testAccessUnlocked {
                                    Divider().padding(.leading, 56)
                                    Button {
                                        subscription.resetTestAccess()
                                    } label: {
                                        SettingsRowLabel(
                                            icon: "lock.open",
                                            title: "Reset Test Access",
                                            subtitle: "Show the paywall again for screenshots"
                                        )
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .background(Color(UIColor.secondarySystemBackground))
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }

                        // Feedback
                        SettingsSection(title: "Feedback") {
                            Button {
                                reportIssue()
                            } label: {
                                SettingsRowLabel(
                                    icon: "exclamationmark.bubble",
                                    title: "Report Issue",
                                    subtitle: "Send app or model feedback"
                                )
                            }
                            .buttonStyle(.plain)
                            .background(Color(UIColor.secondarySystemBackground))
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }

                        if CanopyContributorProgram.isContributorBuild {
                            SettingsSection(title: "Beta Program") {
                                VStack(spacing: 0) {
                                    Toggle(isOn: contributorTelemetryBinding) {
                                        SettingsRowLabel(
                                            icon: "chart.bar.xaxis",
                                            title: "Help improve CanopyChat",
                                            subtitle: "Share selected beta failures and a small comparison sample"
                                        )
                                    }
                                    .tint(AetherColors.oakMedium)
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 12)
                                }
                                .background(Color(UIColor.secondarySystemBackground))
                                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                            }
                        }

                        // About
                        SettingsSection(title: "About") {
                            HStack(spacing: 14) {
                                ZStack {
                                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                                        .fill(AetherColors.oakMedium)
                                        .frame(width: 38, height: 38)
                                    Image(systemName: "tree.fill")
                                        .foregroundColor(AetherColors.oakCream)
                                        .font(.system(size: 18))
                                }
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("CanopyChat").font(.system(size: 15, weight: .semibold))
                                    Text("Version 1.0.0 · Rooted Intelligence")
                                        .font(.system(size: 12))
                                        .foregroundColor(AetherColors.warmGray500)
                                }
                            }
                            .padding(16)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color(UIColor.secondarySystemBackground))
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }

                        Spacer(minLength: 40)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .foregroundColor(AetherColors.oakMedium)
                }
            }
        }
        .preferredColorScheme(state.isDarkTheme ? .dark : .light)
        .sheet(isPresented: $showingSystemPreferences) {
            SystemPreferencesSheet(
                prompt: $state.customSystemPrompt,
                isDark: state.isDarkTheme
            )
        }
        .sheet(item: $sharePayload) { payload in
            ActivityView(items: payload.activityItems)
        }
        .sheet(isPresented: $showingRecentlyDeleted) {
            RecentlyDeletedView()
        }
        .confirmationDialog(
            "Help improve CanopyChat?",
            isPresented: $showingContributorConsent,
            titleVisibility: .visible
        ) {
            Button("Share selected beta data") {
                betaTelemetryEnabled = true
                CanopyContributorProgram.join()
            }
            Button("Not now", role: .cancel) {}
        } message: {
            Text("CanopyChat will send selected prompts and answers when you rate an answer poorly, regenerate it, or hit a model failure, plus a small random comparison sample. Attachments and full chat history are not included. You can stop at any time; unsent beta data will be deleted.")
        }
        .onAppear {
            state.inferenceProvider = .onDevice
            state.selectedModel = AetherModelCatalog.aetherV1DisplayName
            betaTelemetryEnabled = AetherBetaTelemetry.shared.isEnabled
        }
    }

    private func reportIssue() {
        let body = CanopyFeedback.appIssue()
        AetherBetaTelemetry.shared.record(.issueReported)
        guard let url = CanopyFeedback.mailURL(subject: "CanopyChat issue report — help us improve", body: body) else {
            sharePayload = SharePayload(feedbackText: body)
            return
        }
        UIApplication.shared.open(url) { opened in
            if !opened {
                Task { @MainActor in
                    sharePayload = SharePayload(feedbackText: body)
                }
            }
        }
    }

    private var contributorTelemetryBinding: Binding<Bool> {
        Binding(
            get: { betaTelemetryEnabled },
            set: { enabled in
                if enabled {
                    showingContributorConsent = true
                } else {
                    betaTelemetryEnabled = false
                    CanopyContributorProgram.stopContributing()
                }
            }
        )
    }
}

struct SettingsSection<Content: View>: View {
    let title: String
    let content: Content
    init(title: String, @ViewBuilder content: () -> Content) {
        self.title = title; self.content = content()
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title.uppercased())
                .font(.system(size: 11, weight: .semibold))
                .foregroundColor(AetherColors.oakMedium)
                .padding(.leading, 4)
            content
        }
    }
}

struct SettingsSwitchRow: View {
    let icon: String
    let title: String
    let subtitle: String
    @Binding var isOn: Bool

    var body: some View {
        SettingsSwitchRowContent(icon: icon, title: title, subtitle: subtitle, isOn: $isOn)
            .background(Color(UIColor.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

struct SettingsSwitchRowContent: View {
    let icon: String
    let title: String
    let subtitle: String
    @Binding var isOn: Bool

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .frame(width: 28)
                .foregroundColor(AetherColors.oakMedium)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 15, weight: .medium))
                Text(subtitle).font(.system(size: 12)).foregroundColor(AetherColors.warmGray500)
            }
            Spacer()
            Toggle("", isOn: $isOn)
                .labelsHidden()
                .tint(AetherColors.oakMedium)
        }
        .padding(16)
    }
}

struct FontSizeSettingsRow: View {
    @Binding var fontScale: Double

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 14) {
                Image(systemName: "textformat.size")
                    .frame(width: 28)
                    .foregroundColor(AetherColors.oakMedium)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Chat Text Size")
                        .font(.system(size: 15, weight: .medium))
                    Text(label)
                        .font(.system(size: 12))
                        .foregroundColor(AetherColors.warmGray500)
                }
                Spacer()
                Text("\(Int(fontScale * 100))%")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(AetherColors.oakMedium)
            }
            Slider(value: $fontScale, in: 0.85...1.35, step: 0.05)
                .tint(AetherColors.oakMedium)
                .padding(.leading, 42)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private var label: String {
        switch fontScale {
        case ..<0.95:
            return "Compact"
        case 0.95..<1.1:
            return "Standard"
        case 1.1..<1.25:
            return "Large"
        default:
            return "Extra large"
        }
    }
}

struct SettingsNavRow: View {
    let icon: String
    let title: String
    let subtitle: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .frame(width: 28)
                    .foregroundColor(AetherColors.oakMedium)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 15, weight: .medium)).foregroundColor(.primary)
                    Text(subtitle).font(.system(size: 12)).foregroundColor(AetherColors.warmGray500)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(AetherColors.warmGray400)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
    }
}

struct SettingsInfoRow: View {
    let icon: String
    let title: String
    let subtitle: String

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .frame(width: 28)
                .foregroundColor(AetherColors.oakMedium)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundColor(.primary)
                Text(subtitle)
                    .font(.system(size: 12))
                    .foregroundColor(AetherColors.warmGray500)
            }
            Spacer()
            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 16, weight: .semibold))
                .foregroundColor(AetherColors.oakMedium)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}

struct SettingsRowLabel: View {
    let icon: String
    let title: String
    let subtitle: String

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .frame(width: 28)
                .foregroundColor(AetherColors.oakMedium)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundColor(.primary)
                Text(subtitle)
                    .font(.system(size: 12))
                    .foregroundColor(AetherColors.warmGray500)
                    .lineLimit(2)
            }
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}

struct WorkspacePickerRow: View {
    let workspace: Workspace
    let isSelected: Bool
    let onSelect: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: workspace.icon)
                .frame(width: 28)
                .foregroundColor(workspace.color)

            Text(workspace.rawValue)
                .foregroundColor(.primary)

            Spacer()

            if isSelected {
                Image(systemName: "checkmark")
                    .foregroundColor(workspace.color)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: onSelect)
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if !workspace.isBuiltIn {
                Button(role: .destructive, action: onDelete) {
                    Label("Delete", systemImage: "trash")
                }
            }
        }
    }
}

struct ApiConfigSheet: View {
    @Binding var endpoint: String
    @Environment(\.dismiss) var dismiss
    @State private var temp = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("API Endpoint") {
                    TextField("https://api.example.com/v1", text: $temp)
                        .autocapitalization(.none)
                        .keyboardType(.URL)
                }
            }
            .navigationTitle("API Configuration")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") { endpoint = temp; dismiss() }
                        .foregroundColor(AetherColors.oakMedium)
                }
            }
        }
        .onAppear { temp = endpoint }
    }
}

struct ModelConfigSheet: View {
    @Binding var model: String
    @Environment(\.dismiss) var dismiss
    @State private var temp = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Model") {
                    TextField("aether-local", text: $temp)
                        .autocapitalization(.none)
                        .textInputAutocapitalization(.never)
                }
                Section("Aether V1") {
                    Button(AetherModelCatalog.aetherV1DisplayName) {
                        temp = AetherModelCatalog.aetherV1DisplayName
                    }
                    Text(AetherModelCatalog.aetherV1RuntimeMessage)
                        .font(.system(size: 12))
                        .foregroundColor(AetherColors.warmGray500)
                }
            }
            .navigationTitle("Model")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") {
                        model = temp.trimmingCharacters(in: .whitespacesAndNewlines)
                        dismiss()
                    }
                    .foregroundColor(AetherColors.oakMedium)
                    .disabled(temp.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
        .onAppear {
            temp = model
        }
    }
}

struct SystemPreferencesSheet: View {
    @Binding var prompt: String
    let isDark: Bool
    @Environment(\.dismiss) var dismiss
    @State private var draftPrompt = ""

    var body: some View {
        NavigationStack {
            OakBackground {
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        SettingsSection(title: "Global Preferences") {
                            VStack(alignment: .leading, spacing: 8) {
                                TextEditor(text: $draftPrompt)
                                    .frame(minHeight: 180)
                                    .padding(8)
                                    .scrollContentBackground(.hidden)
                                    .background(Color(UIColor.secondarySystemBackground))
                                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                Text("Use this for persistent response preferences, such as tone, formatting, verbosity, or how you like answers structured. Per-chat assistant instructions, web grounding, and safety rules still take priority.")
                                    .font(.system(size: 12))
                                    .foregroundColor(AetherColors.warmGray500)
                            }
                        }

                        Button(role: .destructive) {
                            draftPrompt = ""
                        } label: {
                            Label("Clear Preferences", systemImage: "trash")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .tint(AetherColors.error)
                    }
                    .padding(16)
                }
            }
            .navigationTitle("System Preferences")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") {
                        prompt = draftPrompt.trimmingCharacters(in: .whitespacesAndNewlines)
                        dismiss()
                    }
                    .foregroundColor(AetherColors.oakMedium)
                }
            }
        }
        .preferredColorScheme(isDark ? .dark : .light)
        .onAppear {
            draftPrompt = prompt
        }
    }
}

struct NewChatSheet: View {
    @State private var title = ""
    @State private var workspace: Workspace = .personal
    @State private var persona: AssistantPersona = .default
    @State private var showingCreateAssistant = false
    @State private var editingAssistant: AssistantPersona?
    @State private var showingCreateWorkspace = false
    @State private var workspaceName = ""
    @Environment(\.dismiss) var dismiss
    let workspaces: [Workspace]
    let personas: [AssistantPersona]
    let isDark: Bool
    let onCreateWorkspace: (String) -> Workspace
    let onDeleteWorkspace: (Workspace) -> Void
    let onCreatePersona: (String, String, String) -> AssistantPersona
    let onUpdatePersona: (String, String, String, String) -> AssistantPersona?
    let onDeletePersona: (AssistantPersona) -> Void
    let onCreate: (String, Workspace, AssistantPersona) -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section("Title") {
                    TextField("What's this about?", text: $title)
                }
                Section("Workspace") {
                    ForEach(workspaces) { ws in
                        WorkspacePickerRow(
                            workspace: ws,
                            isSelected: workspace == ws,
                            onSelect: { workspace = ws },
                            onDelete: {
                                onDeleteWorkspace(ws)
                                if workspace == ws {
                                    workspace = .personal
                                }
                            }
                        )
                    }
                    Button {
                        workspaceName = ""
                        showingCreateWorkspace = true
                    } label: {
                        Label("Add Workspace", systemImage: "plus.circle")
                            .foregroundColor(AetherColors.oakMedium)
                    }
                }
                Section("Assistant") {
                    ForEach(personas) { p in
                        AssistantPickerRow(
                            persona: p,
                            isSelected: persona == p,
                            onSelect: { persona = p },
                            onEdit: { editingAssistant = p },
                            onDelete: {
                                onDeletePersona(p)
                                if persona.id == p.id {
                                    persona = .default
                                }
                            }
                        )
                    }
                    Button {
                        showingCreateAssistant = true
                    } label: {
                        Label("Create Assistant", systemImage: "plus.circle")
                            .foregroundColor(AetherColors.oakMedium)
                    }
                }
            }
            .navigationTitle("New Conversation")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Create") { onCreate(title, workspace, persona) }
                        .foregroundColor(AetherColors.oakMedium)
                }
            }
        }
        .sheet(isPresented: $showingCreateAssistant) {
            AssistantEditorSheet(
                title: "Create Assistant",
                saveTitle: "Save",
                isDark: isDark,
                initialName: "",
                initialDescription: "",
                initialInstructions: ""
            ) { name, description, instructions in
                persona = onCreatePersona(name, description, instructions)
                showingCreateAssistant = false
            }
        }
        .sheet(item: $editingAssistant) { assistant in
            AssistantEditorSheet(
                title: "Edit Assistant",
                saveTitle: "Save Changes",
                isDark: isDark,
                initialName: assistant.name,
                initialDescription: assistant.description,
                initialInstructions: assistant.instructions
            ) { name, description, instructions in
                if let updated = onUpdatePersona(assistant.id, name, description, instructions) {
                    persona = updated
                }
                editingAssistant = nil
            }
        }
        .alert("Add Workspace", isPresented: $showingCreateWorkspace) {
            TextField("Workspace name", text: $workspaceName)
            Button("Cancel", role: .cancel) {}
            Button("Add") {
                workspace = onCreateWorkspace(workspaceName)
            }
        } message: {
            Text("Create a workspace for a new group of conversations.")
        }
    }
}

struct AssistantEditorSheet: View {
    let title: String
    let saveTitle: String
    let isDark: Bool
    let onSave: (String, String, String) -> Void
    @Environment(\.dismiss) var dismiss
    @State private var name: String
    @State private var description: String
    @State private var instructions: String

    init(
        title: String,
        saveTitle: String,
        isDark: Bool,
        initialName: String,
        initialDescription: String,
        initialInstructions: String,
        onSave: @escaping (String, String, String) -> Void
    ) {
        self.title = title
        self.saveTitle = saveTitle
        self.isDark = isDark
        self.onSave = onSave
        _name = State(initialValue: initialName)
        _description = State(initialValue: initialDescription)
        _instructions = State(initialValue: initialInstructions)
    }

    var body: some View {
        NavigationStack {
            OakBackground {
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        SettingsSection(title: "Assistant") {
                            VStack(alignment: .leading, spacing: 10) {
                                TextField("Name, e.g. Architect", text: $name)
                                    .textInputAutocapitalization(.words)
                                    .padding(12)
                                    .background(Color(UIColor.secondarySystemBackground))
                                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                                TextField("Short description", text: $description)
                                    .textInputAutocapitalization(.sentences)
                                    .padding(12)
                                    .background(Color(UIColor.secondarySystemBackground))
                                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                            }
                        }

                        SettingsSection(title: "Instructions") {
                            VStack(alignment: .leading, spacing: 8) {
                                TextEditor(text: $instructions)
                                    .frame(minHeight: 180)
                                    .padding(8)
                                    .scrollContentBackground(.hidden)
                                    .background(Color(UIColor.secondarySystemBackground))
                                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                                Text("These instructions apply only when this assistant is selected for a conversation.")
                                    .font(.system(size: 12))
                                    .foregroundColor(AetherColors.warmGray500)
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(saveTitle) {
                        onSave(name, description, instructions)
                        dismiss()
                    }
                    .foregroundColor(AetherColors.oakMedium)
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
        .preferredColorScheme(isDark ? .dark : .light)
    }
}

struct AssistantPickerRow: View {
    let persona: AssistantPersona
    let isSelected: Bool
    let onSelect: () -> Void
    let onEdit: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading) {
                Text(persona.name)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundColor(.primary)
                Text(persona.description)
                    .font(.system(size: 12))
                    .foregroundColor(AetherColors.warmGray500)
            }
            Spacer()
            if isSelected {
                Image(systemName: "checkmark")
                    .foregroundColor(AetherColors.oakMedium)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: onSelect)
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if persona.id.hasPrefix("custom-") {
                Button(action: onEdit) {
                    Label("Edit", systemImage: "pencil")
                }
                Button(role: .destructive, action: onDelete) {
                    Label("Delete", systemImage: "trash")
                }
            }
        }
    }
}

struct RecentlyDeletedView: View {
    @EnvironmentObject var state: AppState
    @Environment(\.dismiss) var dismiss
    @State private var showEmptyConfirm = false

    var body: some View {
        NavigationStack {
            OakBackground {
                if state.recentlyDeleted.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "trash.slash")
                            .font(.system(size: 44))
                            .foregroundColor(AetherColors.warmGray400)
                        Text("Nothing here")
                            .font(.system(size: 17, weight: .medium))
                            .foregroundColor(AetherColors.warmGray600)
                        Text("Deleted chats stay here for \(AppState.deletedRetentionDays) days before they're removed for good.")
                            .font(.system(size: 14))
                            .foregroundColor(AetherColors.warmGray500)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 40)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        ForEach(state.recentlyDeleted) { item in
                            RecentlyDeletedRow(
                                item: item,
                                isDark: state.isDarkTheme,
                                onRestore: { state.restoreDeleted(item.id) }
                            )
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                            .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button(role: .destructive) {
                                    state.permanentlyDeleteConversation(item.id)
                                } label: {
                                    Label("Delete Now", systemImage: "trash.fill")
                                }
                            }
                        }
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                }
            }
            .navigationTitle("Recently Deleted")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Done") { dismiss() }
                        .foregroundColor(AetherColors.oakMedium)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Empty") { showEmptyConfirm = true }
                        .foregroundColor(state.recentlyDeleted.isEmpty ? AetherColors.warmGray400 : AetherColors.error)
                        .disabled(state.recentlyDeleted.isEmpty)
                }
            }
            .confirmationDialog(
                "Permanently delete all recently deleted chats?",
                isPresented: $showEmptyConfirm,
                titleVisibility: .visible
            ) {
                Button("Delete All Forever", role: .destructive) {
                    state.emptyRecentlyDeleted()
                }
                Button("Cancel", role: .cancel) {}
            }
        }
        .preferredColorScheme(state.isDarkTheme ? .dark : .light)
    }
}

struct RecentlyDeletedRow: View {
    let item: DeletedConversation
    let isDark: Bool
    let onRestore: () -> Void

    private var daysLeft: Int {
        let elapsed = Int(Date().timeIntervalSince(item.deletedAt) / 86_400)
        return max(0, AppState.deletedRetentionDays - elapsed)
    }

    var body: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text(item.conversation.title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(isDark ? AetherColors.warmGray200 : AetherColors.warmBlack)
                    .lineLimit(1)
                Text(daysLeft == 0 ? "Removed for good today" : "Gone forever in \(daysLeft) day\(daysLeft == 1 ? "" : "s")")
                    .font(.system(size: 12))
                    .foregroundColor(AetherColors.warmGray500)
            }
            Spacer()
            Button(action: onRestore) {
                HStack(spacing: 6) {
                    Image(systemName: "arrow.uturn.backward")
                        .font(.system(size: 12, weight: .semibold))
                    Text("Restore")
                        .font(.system(size: 13, weight: .semibold))
                }
                .foregroundColor(AetherColors.oakMedium)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(AetherColors.oakMedium.opacity(0.12))
                .clipShape(Capsule())
            }
            .buttonStyle(.plain)
        }
        .padding(14)
        .background(isDark ? AetherColors.warmGray800 : Color.white)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
