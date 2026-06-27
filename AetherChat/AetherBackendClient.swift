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
        messages: [ChatMessage]
    ) async throws -> String {
        var request = URLRequest(url: try chatURL(from: endpoint))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 120
        request.httpBody = try encoder.encode(
            ChatCompletionRequest(
                model: model,
                messages: makeMessages(persona: persona, messages: messages),
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

    private func makeMessages(persona: AssistantPersona, messages: [ChatMessage]) -> [OpenAIMessage] {
        let system = OpenAIMessage(
            role: "system",
            content: "You are \(persona.name), \(persona.description). Reply in a grounded, helpful tone."
        )
        return [system] + messages.suffix(20).map { message in
            OpenAIMessage(role: message.role.apiRole, content: message.content)
        }
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
    let messages: [OpenAIMessage]
    let temperature: Double
    let maxTokens: Int
    let stream: Bool

    enum CodingKeys: String, CodingKey {
        case model, messages, temperature, stream
        case maxTokens = "max_tokens"
    }
}

private struct OpenAIMessage: Codable {
    let role: String
    let content: String
}

private struct ChatCompletionResponse: Decodable {
    let choices: [Choice]

    struct Choice: Decodable {
        let message: OpenAIMessage
    }
}
