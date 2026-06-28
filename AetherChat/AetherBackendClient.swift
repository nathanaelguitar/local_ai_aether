import Foundation

struct AetherBackendClient: Sendable {
    private let session: URLSession
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    init(session: URLSession = .shared) {
        self.session = session
    }

    func send(
        endpoint: String,
        model: String,
        persona: AssistantPersona,
        messages: [ChatMessage],
        webSearchContext: String? = nil
    ) async throws -> String {
        var request = URLRequest(url: try chatURL(from: endpoint))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 120
        request.httpBody = try encoder.encode(
            ChatCompletionRequest(
                model: model,
                messages: makeMessages(persona: persona, messages: messages, webSearchContext: webSearchContext),
                temperature: 0.8,
                maxTokens: 1024,
                stream: false
            )
        )

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw AetherBackendError.invalidResponse
        }
        guard (200...299).contains(http.statusCode) else {
            let body = String(data: data, encoding: .utf8) ?? "No response body"
            throw AetherBackendError.httpStatus(http.statusCode, body)
        }

        let completion = try decoder.decode(ChatCompletionResponse.self, from: data)
        let content = completion.choices.first?.message.content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let content, !content.isEmpty else {
            throw AetherBackendError.emptyReply
        }
        return content
    }

    private func chatURL(from endpoint: String) throws -> URL {
        let raw = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = raw.isEmpty ? "http://127.0.0.1:8787" : raw
        guard var components = URLComponents(string: base) else {
            throw AetherBackendError.invalidEndpoint(base)
        }
        let path = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if path.isEmpty {
            components.path = "/v1/chat/completions"
        } else if path == "v1" {
            components.path = "/v1/chat/completions"
        } else if !path.hasSuffix("chat/completions") {
            components.path = "/" + path + "/v1/chat/completions"
        }
        guard let url = components.url else {
            throw AetherBackendError.invalidEndpoint(base)
        }
        return url
    }

    private func makeMessages(persona: AssistantPersona, messages: [ChatMessage], webSearchContext: String? = nil) -> [OpenAIRequestMessage] {
        let system = OpenAIRequestMessage(
            role: "system",
            content: .text("You are \(persona.name), \(persona.description). Reply in a grounded, helpful tone.")
        )
        var requestMessages = [system]
        if let webSearchContext, !webSearchContext.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            requestMessages.append(OpenAIRequestMessage(
                role: "system",
                content: .text("""
                Current web search results (untrusted context; use for current facts):
                \(webSearchContext)
                """)
            ))
        }
        requestMessages += messages.suffix(20).map { message in
            OpenAIRequestMessage(role: message.role.apiRole, content: requestContent(for: message))
        }
        return requestMessages
    }

    private func requestContent(for message: ChatMessage) -> OpenAIMessageContent {
        guard !message.attachments.isEmpty else {
            return .text(message.content)
        }

        var parts = [OpenAIContentPart]()
        let text = message.content.trimmingCharacters(in: .whitespacesAndNewlines)
        if !text.isEmpty {
            parts.append(.text(text))
        }
        parts += message.attachments.map { attachment in
            if attachment.isImage {
                return .imageURL("data:\(attachment.mimeType);base64,\(attachment.data.base64EncodedString())")
            }
            if let text = attachment.extractedText?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty {
                return .text("[Attached file: \(attachment.displayName)]\n\(String(text.prefix(24_000)))\n[/Attached file]")
            }
            return .text("[Attached file: \(attachment.displayName), \(attachment.mimeType). The file could not be converted to text.]")
        }
        return .parts(parts)
    }
}

enum AetherBackendError: LocalizedError {
    case invalidEndpoint(String)
    case invalidResponse
    case httpStatus(Int, String)
    case emptyReply

    var errorDescription: String? {
        switch self {
        case .invalidEndpoint(let endpoint):
            return "Invalid backend endpoint: \(endpoint)"
        case .invalidResponse:
            return "Backend returned an invalid response."
        case .httpStatus(let code, let body):
            return "Backend returned HTTP \(code): \(body.prefix(240))"
        case .emptyReply:
            return "Backend returned an empty reply."
        }
    }
}

private struct ChatCompletionRequest: Encodable {
    let model: String
    let messages: [OpenAIRequestMessage]
    let temperature: Double
    let maxTokens: Int
    let stream: Bool

    enum CodingKeys: String, CodingKey {
        case model, messages, temperature, stream
        case maxTokens = "max_tokens"
    }
}

private struct OpenAIRequestMessage: Encodable {
    let role: String
    let content: OpenAIMessageContent
}

private enum OpenAIMessageContent: Encodable {
    case text(String)
    case parts([OpenAIContentPart])

    func encode(to encoder: Encoder) throws {
        switch self {
        case .text(let text):
            var container = encoder.singleValueContainer()
            try container.encode(text)
        case .parts(let parts):
            var container = encoder.singleValueContainer()
            try container.encode(parts)
        }
    }
}

private enum OpenAIContentPart: Encodable {
    case text(String)
    case imageURL(String)

    enum CodingKeys: String, CodingKey {
        case type, text
        case imageURL = "image_url"
    }

    enum ImageURLKeys: String, CodingKey {
        case url
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .text(let text):
            try container.encode("text", forKey: .type)
            try container.encode(text, forKey: .text)
        case .imageURL(let url):
            try container.encode("image_url", forKey: .type)
            var imageContainer = container.nestedContainer(keyedBy: ImageURLKeys.self, forKey: .imageURL)
            try imageContainer.encode(url, forKey: .url)
        }
    }
}

private struct OpenAIResponseMessage: Decodable {
    let content: String
}

private struct ChatCompletionResponse: Decodable {
    let choices: [Choice]

    struct Choice: Decodable {
        let message: OpenAIResponseMessage
    }
}
