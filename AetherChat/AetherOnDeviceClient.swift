import Foundation

#if canImport(MLXLLM) && canImport(MLXLMCommon) && canImport(MLXHuggingFace) && canImport(HuggingFace) && canImport(Tokenizers)
import HuggingFace
import MLXHuggingFace
import MLXLLM
@preconcurrency import MLXLMCommon
import Tokenizers
#endif

@MainActor
final class AetherOnDeviceClient {
    private var sessionByModel: [String: Any] = [:]

    func send(modelRepository: String, persona: AssistantPersona, text: String) async throws -> String {
        let repository = modelRepository.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !repository.isEmpty else {
            throw AetherOnDeviceError.missingMLXRepository
        }

        #if canImport(MLXLLM) && canImport(MLXLMCommon) && canImport(MLXHuggingFace) && canImport(HuggingFace) && canImport(Tokenizers)
        let session = try await chatSession(repository: repository, persona: persona)
        nonisolated(unsafe) let unsafeSession = session
        return try await unsafeSession.respond(to: text)
        #else
        throw AetherOnDeviceError.mlxUnavailable
        #endif
    }

    #if canImport(MLXLLM) && canImport(MLXLMCommon) && canImport(MLXHuggingFace) && canImport(HuggingFace) && canImport(Tokenizers)
    private func chatSession(repository: String, persona: AssistantPersona) async throws -> ChatSession {
        let key = "\(repository)|\(persona.id)"
        if let existing = sessionByModel[key] as? ChatSession {
            return existing
        }

        let container = try await loadModelContainer(
            from: #hubDownloader(),
            using: #huggingFaceTokenizerLoader(),
            id: repository
        )
        let session = ChatSession(
            container,
            instructions: "You are \(persona.name), \(persona.description). Reply clearly and do not expose hidden reasoning.",
            generateParameters: GenerateParameters(maxTokens: 768, temperature: 0.7)
        )
        sessionByModel[key] = session
        return session
    }
    #endif
}

enum AetherOnDeviceError: LocalizedError {
    case missingMLXRepository
    case mlxUnavailable

    var errorDescription: String? {
        switch self {
        case .missingMLXRepository:
            return AetherModelCatalog.aetherV1NeedsConversionMessage
        case .mlxUnavailable:
            return "MLX Swift LM is not linked into this build."
        }
    }
}
