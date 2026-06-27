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
        let modelFiles = try await AetherModelStore.localAetherV1Files()
        let engine = try loadEngine(modelURL: modelFiles.modelURL, mmprojURL: modelFiles.mmprojURL)
        let prompt = AetherPromptBuilder.prompt(persona: persona, messages: messages)
        let promptAttachments = messages.suffix(16).flatMap(\.attachments)
        return try engine.generate(prompt: prompt, attachments: promptAttachments)
        #else
        throw AetherOnDeviceError.llamaUnavailable
        #endif
    }

    #if canImport(LlamaSwift)
    private func loadEngine(modelURL: URL, mmprojURL: URL) throws -> AetherLlamaEngine {
        if let engine {
            return engine
        }
        let engine = try AetherLlamaEngine(modelURL: modelURL, mmprojURL: mmprojURL)
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
    case unsupportedLocalModel(String)
    case visionUnsupported
    case projectorLoadFailed
    case imagePreprocessingFailed
    case multimodalTokenizationFailed

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
        case .unsupportedLocalModel(let model):
            return "\(model) is not available for on-device inference. Select Aether V1 or switch the provider to Backend."
        case .visionUnsupported:
            return "Aether V1 could not use image input in this build."
        case .projectorLoadFailed:
            return "Aether V1 vision projector could not be loaded."
        case .imagePreprocessingFailed:
            return "Aether V1 could not preprocess the attached image."
        case .multimodalTokenizationFailed:
            return "Aether V1 could not tokenize the multimodal prompt."
        }
    }
}

enum AetherModelStore {
    struct AetherV1Files {
        let modelURL: URL
        let mmprojURL: URL
    }

    static func localAetherV1Files() async throws -> AetherV1Files {
        let directory = try modelDirectory()
        return AetherV1Files(
            modelURL: try await localFile(
                directory: directory,
                filename: AetherModelCatalog.aetherV1GGUFFilename,
                remoteURL: AetherModelCatalog.aetherV1DownloadURL
            ),
            mmprojURL: try await localFile(
                directory: directory,
                filename: AetherModelCatalog.aetherV1MMProjFilename,
                remoteURL: AetherModelCatalog.aetherV1MMProjDownloadURL
            )
        )
    }

    private static func localFile(directory: URL, filename: String, remoteURL: URL) async throws -> URL {
        let destination = directory.appendingPathComponent(filename)
        if FileManager.default.fileExists(atPath: destination.path) {
            return destination
        }

        let (temporaryURL, response) = try await URLSession.shared.download(from: remoteURL)
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
            for _ in message.attachments {
                prompt += "\(AetherPromptBuilder.mediaMarker)\n"
            }
            prompt += message.content
            prompt += "<|im_end|>\n"
        }

        prompt += "<|im_start|>assistant\n<think>\n</think>\n\n"
        return prompt
    }

    private static let mediaMarker = "<__media__>"
}

#if canImport(LlamaSwift)
final class AetherLlamaEngine {
    private let model: OpaquePointer
    private let context: OpaquePointer
    private let mtmdContext: OpaquePointer
    private let vocab: OpaquePointer
    private let contextTokens: Int32

    init(modelURL: URL, mmprojURL: URL) throws {
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

        var mtmdParams = mtmd_context_params_default()
        mtmdParams.use_gpu = true
        mtmdParams.n_threads = Int32(max(2, min(6, ProcessInfo.processInfo.processorCount - 2)))
        guard let mtmdContext = mtmd_init_from_file(mmprojURL.path, model, mtmdParams), mtmd_support_vision(mtmdContext) else {
            llama_free(context)
            llama_model_free(model)
            throw AetherOnDeviceError.projectorLoadFailed
        }

        self.model = model
        self.context = context
        self.mtmdContext = mtmdContext
        self.vocab = llama_model_get_vocab(model)
        self.contextTokens = Int32(contextParams.n_ctx)
    }

    deinit {
        mtmd_free(mtmdContext)
        llama_free(context)
        llama_model_free(model)
        llama_backend_free()
    }

    func generate(prompt: String, attachments: [ChatAttachment]) throws -> String {
        llama_memory_clear(llama_get_memory(context), true)
        if attachments.isEmpty {
            let promptTokens = try tokenize(prompt)
            guard !promptTokens.isEmpty, Int32(promptTokens.count) < contextTokens else {
                throw AetherOnDeviceError.tokenizationFailed
            }

            var batch = llama_batch_init(512, 0, 1)
            defer { llama_batch_free(batch) }

            try decodePrompt(tokens: promptTokens, batch: &batch)
            return try generateCompletion(startPosition: Int32(promptTokens.count), previousToken: promptTokens.last ?? llama_vocab_bos(vocab))
        }

        let startPosition = try decodeMultimodalPrompt(prompt: prompt, attachments: attachments)
        return try generateCompletion(startPosition: startPosition, previousToken: llama_vocab_bos(vocab))
    }

    private func generateCompletion(startPosition: Int32, previousToken: llama_token) throws -> String {
        var generated = ""
        var position = startPosition
        var previousToken = previousToken
        var batch = llama_batch_init(512, 0, 1)
        defer { llama_batch_free(batch) }

        for _ in 0..<AetherModelCatalog.aetherV1MaxOutputTokens {
            let next = try sampleGreedy()
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

    private func decodeMultimodalPrompt(prompt: String, attachments: [ChatAttachment]) throws -> Int32 {
        let chunks = mtmd_input_chunks_init()
        guard let chunks else {
            throw AetherOnDeviceError.multimodalTokenizationFailed
        }
        defer { mtmd_input_chunks_free(chunks) }

        let bitmapPointers = try attachments.map { attachment in
            try makeBitmap(from: attachment)
        }
        defer {
            for bitmap in bitmapPointers {
                mtmd_bitmap_free(bitmap)
            }
        }

        var mutableBitmapPointers = bitmapPointers.map { Optional($0) }
        let tokenizeResult = prompt.withCString { promptCString in
            var input = mtmd_input_text(text: promptCString, add_special: true, parse_special: true)
            return mutableBitmapPointers.withUnsafeMutableBufferPointer { buffer in
                mtmd_tokenize(mtmdContext, chunks, &input, buffer.baseAddress, buffer.count)
            }
        }
        guard tokenizeResult == 0 else {
            throw AetherOnDeviceError.multimodalTokenizationFailed
        }

        var nPast = llama_pos(0)
        guard mtmd_helper_eval_chunks(
            mtmdContext,
            context,
            chunks,
            0,
            0,
            512,
            true,
            &nPast
        ) == 0 else {
            throw AetherOnDeviceError.decodeFailed
        }

        return Int32(nPast)
    }

    private func makeBitmap(from attachment: ChatAttachment) throws -> OpaquePointer {
        try attachment.data.withUnsafeBytes { bytes in
            guard let baseAddress = bytes.bindMemory(to: UInt8.self).baseAddress else {
                throw AetherOnDeviceError.imagePreprocessingFailed
            }
            let wrapper = mtmd_helper_bitmap_init_from_buf(mtmdContext, baseAddress, attachment.data.count, false)
            guard let bitmap = wrapper.bitmap else {
                throw AetherOnDeviceError.imagePreprocessingFailed
            }
            return bitmap
        }
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

    private func sampleGreedy() throws -> llama_token {
        guard let logits = llama_get_logits(context) else {
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
