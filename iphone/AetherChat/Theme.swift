import SwiftUI

struct AetherColors {
    static let oakDark     = Color(hex: "3D2914")
    static let oakMedium   = Color(hex: "6B4423")
    static let oakLight    = Color(hex: "A0784A")
    static let oakPale     = Color(hex: "D4B896")
    static let oakCream    = Color(hex: "F5EDE0")
    static let forestMedium = Color(hex: "4A7C4A")
    static let forestPale   = Color(hex: "C8DEC8")
    static let copper      = Color(hex: "B87333")
    static let amber       = Color(hex: "D4A017")
    static let warmGray100 = Color(hex: "F0EBE3")
    static let warmGray200 = Color(hex: "E0D8CC")
    static let warmGray400 = Color(hex: "B0A090")
    static let warmGray500 = Color(hex: "907868")
    static let warmGray600 = Color(hex: "706050")
    static let warmGray700 = Color(hex: "504030")
    static let warmGray800 = Color(hex: "302820")
    static let warmGray900 = Color(hex: "1A1612")
    static let info        = Color(hex: "4A7CB8")
    static let error       = Color(hex: "C84040")
    static let warmBlack   = Color(hex: "1A1208")
}

extension Color {
    init(hex: String) {
        let hex = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&int)
        let a, r, g, b: UInt64
        switch hex.count {
        case 3: (a, r, g, b) = (255, (int >> 8) * 17, (int >> 4 & 0xF) * 17, (int & 0xF) * 17)
        case 6: (a, r, g, b) = (255, int >> 16, int >> 8 & 0xFF, int & 0xFF)
        case 8: (a, r, g, b) = (int >> 24, int >> 16 & 0xFF, int >> 8 & 0xFF, int & 0xFF)
        default: (a, r, g, b) = (255, 0, 0, 0)
        }
        self.init(.sRGB, red: Double(r)/255, green: Double(g)/255, blue: Double(b)/255, opacity: Double(a)/255)
    }
}

struct Workspace: Identifiable, Equatable, Hashable, Codable, Sendable {
    let id: String
    let name: String
    let iconName: String
    let colorHex: String
    let isBuiltIn: Bool

    var rawValue: String { name }

    var icon: String {
        iconName
    }

    var color: Color {
        Color(hex: colorHex)
    }

    var paleColor: Color {
        color.opacity(0.18)
    }

    static let personal = Workspace(id: "personal", name: "Personal", iconName: "person.fill", colorHex: "6B4423", isBuiltIn: true)
    static let work = Workspace(id: "work", name: "Work", iconName: "briefcase.fill", colorHex: "4A7C4A", isBuiltIn: true)
    static let creative = Workspace(id: "creative", name: "Creative", iconName: "paintpalette.fill", colorHex: "B87333", isBuiltIn: true)
    static let research = Workspace(id: "research", name: "Research", iconName: "book.fill", colorHex: "4A7CB8", isBuiltIn: true)

    static let builtIns: [Workspace] = [.personal, .work, .creative, .research]
    static let allCases = builtIns

    static func custom(name: String) -> Workspace {
        Workspace(
            id: "custom-\(UUID().uuidString)",
            name: name,
            iconName: "folder.fill",
            colorHex: "A0784A",
            isBuiltIn: false
        )
    }
}

struct AssistantPersona: Identifiable, Equatable, Codable, Sendable {
    let id: String
    let name: String
    let description: String
    let instructions: String

    init(id: String, name: String, description: String, instructions: String = "") {
        self.id = id
        self.name = name
        self.description = description
        self.instructions = instructions
    }

    static let `default`    = AssistantPersona(id: "default",    name: "Canopy",     description: "Balanced, thoughtful assistant")
    static let analytical   = AssistantPersona(id: "analytical", name: "Sage",       description: "Deep analytical reasoning")
    static let creative     = AssistantPersona(id: "creative",   name: "Muse",       description: "Creative and imaginative thinking")
    static let concise      = AssistantPersona(id: "concise",    name: "Swift",      description: "Direct and to the point")

    static let all: [AssistantPersona] = [.default, .analytical, .creative, .concise]
}
