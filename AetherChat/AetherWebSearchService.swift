import Foundation

struct AetherWebSearchResult: Sendable {
    let query: String
    let context: String
}

struct AetherWebSearchService: Sendable {
    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func search(query: String, maxCharacters: Int = 8_000) async throws -> AetherWebSearchResult {
        let cleaned = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty else {
            return AetherWebSearchResult(query: cleaned, context: "")
        }

        var components = URLComponents(string: "https://r.jina.ai/http://www.bing.com/search")!
        components.queryItems = [
            URLQueryItem(name: "q", value: cleaned),
            URLQueryItem(name: "setlang", value: "en-US")
        ]
        guard let url = components.url else {
            return AetherWebSearchResult(query: cleaned, context: "")
        }

        var request = URLRequest(url: url)
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148", forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 25

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            return AetherWebSearchResult(query: cleaned, context: "")
        }

        let raw = String(data: data, encoding: .utf8) ?? ""
        let context = Self.extractContext(from: raw, maxCharacters: maxCharacters)
        return AetherWebSearchResult(query: cleaned, context: context)
    }

    private static func extractContext(from raw: String, maxCharacters: Int) -> String {
        let normalized = raw
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")

        guard let markerRange = normalized.range(of: "Markdown Content:\n") else {
            return String(normalized.prefix(maxCharacters))
        }

        let body = String(normalized[markerRange.upperBound...])
        return String(body.prefix(maxCharacters))
    }
}

enum AetherWebSearchIntent {
    private static let triggerPhrases = [
        "search the web",
        "web search",
        "look it up",
        "look up",
        "search for",
        "find out",
        "research",
        "latest",
        "current",
        "today",
        "now",
        "news",
        "price",
        "ipo",
        "stock",
        "weather",
        "who won",
        "what happened"
    ]

    private static let stripPhrases = [
        "search the web",
        "web search",
        "look it up",
        "look up",
        "search for",
        "find out",
        "research",
        "and get back to me",
        "and tell me",
        "please",
        "can you",
        "could you"
    ]

    static func query(from currentText: String, previousMessages: [ChatMessage]) -> String? {
        let current = currentText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !current.isEmpty else { return nil }

        let lc = current.lowercased()
        if let stripped = strippedSearchText(from: current), !stripped.isEmpty {
            return stripped
        }

        if triggerPhrases.contains(where: { lc.contains($0) }) {
            for message in previousMessages.reversed() where message.role == .user {
                let candidate = message.content.trimmingCharacters(in: .whitespacesAndNewlines)
                if !candidate.isEmpty, !isSearchDirective(candidate), candidate.count >= 6 {
                    return candidate
                }
            }
            return nil
        }

        return nil
    }

    private static func isSearchDirective(_ text: String) -> Bool {
        let lc = text.lowercased()
        return triggerPhrases.contains { lc.contains($0) } && (strippedSearchText(from: text)?.isEmpty ?? true)
    }

    private static func strippedSearchText(from text: String) -> String? {
        var cleaned = text.lowercased()
        for phrase in stripPhrases {
            cleaned = cleaned.replacingOccurrences(of: phrase, with: " ")
        }
        cleaned = cleaned
            .replacingOccurrences(of: #"[^\p{L}\p{N}\s\-\+\#\./:&]"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)

        let words = cleaned.split(separator: " ")
        guard words.count >= 2 else { return nil }
        return cleaned
    }
}
