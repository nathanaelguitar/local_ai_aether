import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var state: AppState
    @Environment(\.dismiss) var dismiss
    @State private var showingAssistantSheet = false

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
                                ForEach(Workspace.allCases) { ws in
                                    Button(action: { state.defaultWorkspace = ws }) {
                                        HStack {
                                            Image(systemName: ws.icon)
                                                .frame(width: 28)
                                                .foregroundColor(ws.color)
                                            Text(ws.rawValue)
                                                .foregroundColor(.primary)
                                            Spacer()
                                            if state.defaultWorkspace == ws {
                                                Image(systemName: "checkmark")
                                                    .foregroundColor(ws.color)
                                            }
                                        }
                                        .padding(.horizontal, 16)
                                        .padding(.vertical, 12)
                                    }
                                    if ws != Workspace.allCases.last {
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
                                    title: "Custom Assistant",
                                    subtitle: state.customSystemPrompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Default Aether behavior" : "Custom prompt enabled"
                                ) {
                                    showingAssistantSheet = true
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
        .sheet(isPresented: $showingAssistantSheet) {
            CustomAssistantSheet(
                name: $state.customAssistantName,
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

struct CustomAssistantSheet: View {
    @Binding var name: String
    @Binding var prompt: String
    let isDark: Bool
    @Environment(\.dismiss) var dismiss
    @State private var draftName = ""
    @State private var draftPrompt = ""

    var body: some View {
        NavigationStack {
            OakBackground {
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        SettingsSection(title: "Identity") {
                            VStack(alignment: .leading, spacing: 8) {
                                TextField("Assistant name, e.g. Grove", text: $draftName)
                                    .textInputAutocapitalization(.words)
                                    .padding(12)
                                    .background(Color(UIColor.secondarySystemBackground))
                                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                Text("Leave blank to keep using each conversation's selected assistant name.")
                                    .font(.system(size: 12))
                                    .foregroundColor(AetherColors.warmGray500)
                            }
                        }

                        SettingsSection(title: "System Prompt") {
                            VStack(alignment: .leading, spacing: 8) {
                                TextEditor(text: $draftPrompt)
                                    .frame(minHeight: 180)
                                    .padding(8)
                                    .scrollContentBackground(.hidden)
                                    .background(Color(UIColor.secondarySystemBackground))
                                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                Text("Use this for persistent style and behavior, such as tone, formatting preferences, or a specialized role. Web grounding and safety rules still take priority.")
                                    .font(.system(size: 12))
                                    .foregroundColor(AetherColors.warmGray500)
                            }
                        }

                        Button(role: .destructive) {
                            draftName = ""
                            draftPrompt = ""
                        } label: {
                            Label("Clear Custom Assistant", systemImage: "trash")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .tint(AetherColors.error)
                    }
                    .padding(16)
                }
            }
            .navigationTitle("Custom Assistant")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") {
                        name = draftName.trimmingCharacters(in: .whitespacesAndNewlines)
                        prompt = draftPrompt.trimmingCharacters(in: .whitespacesAndNewlines)
                        dismiss()
                    }
                    .foregroundColor(AetherColors.oakMedium)
                }
            }
        }
        .preferredColorScheme(isDark ? .dark : .light)
        .onAppear {
            draftName = name
            draftPrompt = prompt
        }
    }
}

struct NewChatSheet: View {
    @State private var title = ""
    @State private var workspace: Workspace = .personal
    @State private var persona: AssistantPersona = .default
    @Environment(\.dismiss) var dismiss
    let onCreate: (String, Workspace, AssistantPersona) -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section("Title") {
                    TextField("What's this about?", text: $title)
                }
                Section("Workspace") {
                    ForEach(Workspace.allCases) { ws in
                        Button(action: { workspace = ws }) {
                            HStack {
                                Image(systemName: ws.icon).foregroundColor(ws.color)
                                Text(ws.rawValue).foregroundColor(.primary)
                                Spacer()
                                if workspace == ws {
                                    Image(systemName: "checkmark").foregroundColor(ws.color)
                                }
                            }
                        }
                    }
                }
                Section("Assistant") {
                    ForEach(AssistantPersona.all) { p in
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
    }
}
