import Foundation

enum InferenceProvider: String, CaseIterable, Identifiable {
    case onDevice = "On-device"
    case backend = "Backend"

    var id: String { rawValue }
}

enum AetherModelCatalog {
    static let aetherV1DisplayName = "Aether V1"
    static let requestedGGUFRepository = "Jackrong/Qwen3.5-2B-Claude-4.6-Opus-Reasoning-Distilled-GGUF"
    static let requestedGGUFQuantization = "Q4_K_M"

    // MLX Swift cannot load GGUF directly. Set this to a converted MLX repo when available.
    static let defaultMLXRepository = UserDefaults.standard.string(forKey: "aetherV1MLXRepository") ?? ""

    static var aetherV1NeedsConversionMessage: String {
        """
        Aether V1 is configured for \(requestedGGUFRepository):\(requestedGGUFQuantization), but that artifact is GGUF. MLX on iPhone needs an MLX-format repository. Convert or publish the model as MLX, then set its repo ID in Settings.
        """
    }
}
