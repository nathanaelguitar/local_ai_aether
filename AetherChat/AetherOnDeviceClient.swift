import Foundation

#if canImport(LlamaSwift)
import LlamaSwift
#endif

actor AetherOnDeviceClient {
    #if canImport(LlamaSwift)
    private var engine: AetherLlamaEngine?
    #endif

    func send(persona: AssistantPersona, messages: [ChatMessage]) async throws -> String {
        #if canImport(LlamaSwift)
        let modelURL = try await AetherModelStore.localAetherV1URL()
        let engine = try loadEngine(modelURL: modelURL)
        let prompt = AetherPromptBuilder.prompt(persona: persona, messages: messages)
        return try engine.generate(prompt: prompt)
        #else
        throw AetherOnDeviceError.llamaUnavailable
        #endif
    }

    #if canImport(LlamaSwift)
    private func loadEngine(modelURL: URL) throws -> AetherLlamaEngine {
        if let engine {
            return engine
        }
        let engine = try AetherLlamaEngine(modelURL: modelURL)
        self.engine = engine
        return engine
    }
    #endif
}

enum AetherOnDeviceError: LocalizedError {
    case llamaUnavailable
    case invalidModelURL
    case modelDownloadFailed
    case modelLoadFailed
    case contextLoadFailed
    case tokenizationFailed
    case decodeFailed
    case emptyResponse

    var errorDescription: String? {
        switch self {
        case .llamaUnavailable:
            return "llama.cpp is not linked into this build."
        case .invalidModelURL:
            return "Aether V1 model URL is invalid."
        case .modelDownloadFailed:
            return "Aether V1 GGUF model download failed."
        case .modelLoadFailed:
            return "Aether V1 GGUF model could not be loaded."
        case .contextLoadFailed:
            return "llama.cpp could not create an inference context for Aether V1."
        case .tokenizationFailed:
            return "Aether V1 could not tokenize the prompt."
        case .decodeFailed:
            return "Aether V1 local inference failed while decoding."
        case .emptyResponse:
            return "Aether V1 generated an empty response."
        }
    }
}

enum AetherModelStore {
    static func localAetherV1URL() async throws -> URL {
        let directory = try modelDirectory()
        let destination = directory.appendingPathComponent(AetherModelCatalog.aetherV1GGUFFilename)
        if FileManager.default.fileExists(atPath: destination.path) {
            return destination
        }

        let (temporaryURL, response) = try await URLSession.shared.download(from: AetherModelCatalog.aetherV1DownloadURL)
        guard let http = response as? HTTPURLResponse, 200..<300 ~= http.statusCode else {
            throw AetherOnDeviceError.modelDownloadFailed
        }

        try? FileManager.default.removeItem(at: destination)
        do {
            try FileManager.default.moveItem(at: temporaryURL, to: destination)
            return destination
        } catch {
            throw AetherOnDeviceError.modelDownloadFailed
        }
    }

    private static func modelDirectory() throws -> URL {
        let base = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = base.appendingPathComponent("Models", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
}

enum AetherPromptBuilder {
    static func prompt(persona: AssistantPersona, messages: [ChatMessage]) -> String {
        var prompt = "<|im_start|>system\n"
        prompt += "You are \(persona.name), \(persona.description). Reply clearly and concisely. Do not expose hidden reasoning."
        prompt += "<|im_end|>\n"

        for message in messages.suffix(16) {
            prompt += "<|im_start|>\(message.role.apiRole)\n"
            prompt += message.content
            prompt += "<|im_end|>\n"
        }

        prompt += "<|im_start|>assistant\n<think>\n</think>\n\n"
        return prompt
    }
}

#if canImport(LlamaSwift)
final class AetherLlamaEngine {
    private let model: OpaquePointer
    private let context: OpaquePointer
    private let vocab: OpaquePointer
    private let contextTokens: Int32

    init(modelURL: URL) throws {
        llama_backend_init()

        var modelParams = llama_model_default_params()
        modelParams.n_gpu_layers = 99

        guard let model = llama_model_load_from_file(modelURL.path, modelParams) else {
            throw AetherOnDeviceError.modelLoadFailed
        }

        var contextParams = llama_context_default_params()
        contextParams.n_ctx = UInt32(AetherModelCatalog.aetherV1ContextTokens)
        contextParams.n_batch = 512
        contextParams.n_threads = max(2, min(6, Int32(ProcessInfo.processInfo.processorCount - 2)))
        contextParams.n_threads_batch = contextParams.n_threads

        guard let context = llama_init_from_model(model, contextParams) else {
            llama_model_free(model)
            throw AetherOnDeviceError.contextLoadFailed
        }

        self.model = model
        self.context = context
        self.vocab = llama_model_get_vocab(model)
        self.contextTokens = Int32(contextParams.n_ctx)
    }

    deinit {
        llama_free(context)
        llama_model_free(model)
        llama_backend_free()
    }

    func generate(prompt: String) throws -> String {
        let promptTokens = try tokenize(prompt)
        guard !promptTokens.isEmpty, Int32(promptTokens.count) < contextTokens else {
            throw AetherOnDeviceError.tokenizationFailed
        }

        var batch = llama_batch_init(512, 0, 1)
        defer { llama_batch_free(batch) }

        try decodePrompt(tokens: promptTokens, batch: &batch)

        var generated = ""
        var position = Int32(promptTokens.count)
        var previousToken = promptTokens.last ?? llama_vocab_bos(vocab)

        for _ in 0..<AetherModelCatalog.aetherV1MaxOutputTokens {
            let next = try sampleGreedy(batch: batch)
            if next == llama_vocab_eos(vocab) || next == previousToken && generated.isEmpty {
                break
            }

            let piece = tokenPiece(next)
            generated += piece
            if generated.contains("<|im_end|>") {
                generated = generated.components(separatedBy: "<|im_end|>").first ?? generated
                break
            }

            try decode(tokens: [next], batch: &batch, startPosition: position, logitsForLastToken: true)
            position += 1
            previousToken = next
        }

        let cleaned = clean(generated)
        guard !cleaned.isEmpty else {
            throw AetherOnDeviceError.emptyResponse
        }
        return cleaned
    }

    private func tokenize(_ prompt: String) throws -> [llama_token] {
        let byteCount = prompt.utf8.count
        var tokens = [llama_token](repeating: 0, count: byteCount + 8)
        let count = llama_tokenize(
            vocab,
            prompt,
            Int32(byteCount),
            &tokens,
            Int32(tokens.count),
            true,
            true
        )
        guard count > 0 else {
            throw AetherOnDeviceError.tokenizationFailed
        }
        return Array(tokens.prefix(Int(count)))
    }

    private func decodePrompt(tokens: [llama_token], batch: inout llama_batch) throws {
        let chunkSize = 512
        var cursor = 0
        while cursor < tokens.count {
            let end = min(cursor + chunkSize, tokens.count)
            try decode(
                tokens: Array(tokens[cursor..<end]),
                batch: &batch,
                startPosition: Int32(cursor),
                logitsForLastToken: end == tokens.count
            )
            cursor = end
        }
    }

    private func decode(
        tokens: [llama_token],
        batch: inout llama_batch,
        startPosition: Int32,
        logitsForLastToken: Bool
    ) throws {
        batch.n_tokens = Int32(tokens.count)
        for (offset, token) in tokens.enumerated() {
            let index = Int(offset)
            batch.token[index] = token
            batch.pos[index] = startPosition + Int32(offset)
            batch.n_seq_id[index] = 1
            batch.seq_id[index]?[0] = 0
            batch.logits[index] = logitsForLastToken && offset == tokens.count - 1 ? 1 : 0
        }

        guard llama_decode(context, batch) == 0 else {
            throw AetherOnDeviceError.decodeFailed
        }
    }

    private func sampleGreedy(batch: llama_batch) throws -> llama_token {
        guard let logits = llama_get_logits_ith(context, batch.n_tokens - 1) else {
            throw AetherOnDeviceError.decodeFailed
        }

        let vocabSize = Int(llama_vocab_n_tokens(vocab))
        var bestToken = llama_token(0)
        var bestLogit = logits[0]

        for index in 1..<vocabSize {
            if logits[index] > bestLogit {
                bestLogit = logits[index]
                bestToken = llama_token(index)
            }
        }

        return bestToken
    }

    private func tokenPiece(_ token: llama_token) -> String {
        var buffer = [CChar](repeating: 0, count: 256)
        let count = llama_token_to_piece(vocab, token, &buffer, Int32(buffer.count), 0, false)
        guard count > 0 else {
            return ""
        }
        return String(decoding: buffer.prefix(Int(count)).map { UInt8(bitPattern: $0) }, as: UTF8.self)
    }

    private func clean(_ text: String) -> String {
        var output = text
        if output.contains("</think>") {
            output = output.components(separatedBy: "</think>").dropFirst().joined(separator: "")
        } else if let range = output.range(of: "<think>") {
            output.removeSubrange(range.lowerBound..<output.endIndex)
        }
        output = output.replacingOccurrences(of: "<think>", with: "")
        return output.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
#endif
