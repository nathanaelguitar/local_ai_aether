import Foundation

enum InferenceProvider: String, CaseIterable, Identifiable {
    case onDevice = "On-device"
    case backend = "Backend"

    var id: String { rawValue }
}

enum AetherModelCatalog {
    static let aetherV1DisplayName = "Canopy V1"
    static let aetherV1ModelVersion = "1.1.1"
    static let legacyAetherV1DisplayName = "Aether V1"
    static let requestedGGUFRepository = "mradermacher/Qwen3.5-2b-Kimi-and-Opus-Distillation-GGUF"
    static let requestedGGUFQuantization = "Q4_K_M"
    static let aetherV1GGUFFilename = "Qwen3.5-2b-Kimi-and-Opus-Distillation.Q4_K_M.gguf"
    static let aetherV1MMProjFilename = "Qwen3.5-2b-Kimi-and-Opus-Distillation.mmproj-Q8_0.gguf"
    // A 20k KV cache is expensive on an iPhone and is rarely needed because the
    // prompt builder already degrades older turns and attachments.
    static let aetherV1ContextTokens: Int32 = 12288
    static let aetherV1BatchTokens: Int32 = 2048
    static let aetherV1ImageMaxTokens: Int32 = 768
    // 512 tokens was an observable hard stop for longer answers.
    static let aetherV1MaxOutputTokens: Int32 = 768

    static let aetherV1DownloadURL = URL(
        string: "https://huggingface.co/\(requestedGGUFRepository)/resolve/main/\(aetherV1GGUFFilename)"
    )!

    static let aetherV1MMProjDownloadURL = URL(
        string: "https://huggingface.co/\(requestedGGUFRepository)/resolve/main/\(aetherV1MMProjFilename)"
    )!

    static var aetherV1RuntimeMessage: String {
        "Canopy V1 runs locally with llama.cpp using \(aetherV1GGUFFilename) and the \(aetherV1MMProjFilename) vision projector. The first on-device reply downloads about 1.7 GB, then caches the files on this iPhone."
    }
}
