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
    static let aetherV1GGUFFilename = "Qwen3.5-2B.Q4_K_M.gguf"
    static let aetherV1ContextTokens: Int32 = 4096
    static let aetherV1MaxOutputTokens: Int32 = 512

    static let aetherV1DownloadURL = URL(
        string: "https://huggingface.co/\(requestedGGUFRepository)/resolve/main/\(aetherV1GGUFFilename)"
    )!

    static var aetherV1RuntimeMessage: String {
        "Aether V1 runs locally with llama.cpp using \(aetherV1GGUFFilename). The first on-device reply downloads about 1 GB, then caches the model on this iPhone."
    }
}
