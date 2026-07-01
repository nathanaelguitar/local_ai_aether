import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var state: AppState
    @Environment(\.dismiss) var dismiss
    @State private var showingSystemPreferences = false

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
                                        showsDeleteButton: !ws.isBuiltIn,
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

                        // About
                        SettingsSection(title: "About") {
                            HStack(spacing: 14) {
                                ZStack {
                                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                                        .fill(AetherColors.oakMedium)
                                        .frame(width: 38, height: 38)
                                    Image(systemName: "leaf.fill")
                                        .foregroundColor(AetherColors.oakCream)
                                        .font(.system(size: 18))
                                }
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Aether").font(.system(size: 15, weight: .semibold))
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
        .onAppear {
            state.inferenceProvider = .onDevice
            state.selectedModel = AetherModelCatalog.aetherV1DisplayName
        }
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

struct WorkspacePickerRow: View {
    let workspace: Workspace
    let isSelected: Bool
    let showsDeleteButton: Bool
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

            if showsDeleteButton {
                Button(role: .destructive, action: onDelete) {
                    Image(systemName: "trash")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(AetherColors.error)
                        .padding(.leading, 4)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Delete \(workspace.rawValue)")
            }
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: onSelect)
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if showsDeleteButton {
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
    @State private var showingCreateWorkspace = false
    @State private var workspaceName = ""
    @Environment(\.dismiss) var dismiss
    let workspaces: [Workspace]
    let personas: [AssistantPersona]
    let isDark: Bool
    let onCreateWorkspace: (String) -> Workspace
    let onDeleteWorkspace: (Workspace) -> Void
    let onCreatePersona: (String, String, String) -> AssistantPersona
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
                            showsDeleteButton: !ws.isBuiltIn,
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
                        Button(action: { persona = p }) {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(p.name).font(.system(size: 15, weight: .medium)).foregroundColor(.primary)
                                    Text(p.description).font(.system(size: 12)).foregroundColor(AetherColors.warmGray500)
                                }
                                Spacer()
                                if persona == p {
                                    Image(systemName: "checkmark").foregroundColor(AetherColors.oakMedium)
                                }
                            }
                        }
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
            CreateAssistantSheet(isDark: isDark) { name, description, instructions in
                persona = onCreatePersona(name, description, instructions)
                showingCreateAssistant = false
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

struct CreateAssistantSheet: View {
    let isDark: Bool
    let onSave: (String, String, String) -> Void
    @Environment(\.dismiss) var dismiss
    @State private var name = ""
    @State private var description = ""
    @State private var instructions = ""

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
            .navigationTitle("Create Assistant")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") {
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
