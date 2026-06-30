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
        let context = Self.formatContext(
            query: cleaned,
            body: Self.extractContext(from: raw, maxCharacters: maxCharacters)
        )
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

    private static func formatContext(query: String, body: String) -> String {
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        return """
        Web search was performed for: \(query)

        Search results:
        \(trimmed)
        """
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

    private static let explicitSearchPhrases = [
        "search the web",
        "web search",
        "look it up",
        "look up",
        "search for",
        "find out"
    ]

    private static let weakFollowUpWords: Set<String> = [
        "are", "you", "sure", "really", "verify", "check", "confirm", "that", "this",
        "it", "they", "them", "he", "she", "their", "its", "did", "does", "do", "ipo"
    ]

    static func query(from currentText: String, previousMessages: [ChatMessage]) -> String? {
        let current = currentText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !current.isEmpty else { return nil }

        let lc = current.lowercased()
        let explicitSearch = explicitSearchPhrases.contains { lc.contains($0) }
        if let stripped = strippedSearchText(from: current), !stripped.isEmpty {
            if explicitSearch, isWeakFollowUp(stripped), let prior = contextualPreviousQuery(from: previousMessages) {
                return prior
            }
            return contextualizedQuery(stripped, previousMessages: previousMessages)
        }

        if triggerPhrases.contains(where: { lc.contains($0) }) {
            if let prior = contextualPreviousQuery(from: previousMessages) {
                return prior
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
        var cleaned = text
        for phrase in stripPhrases {
            cleaned = cleaned.replacingOccurrences(of: phrase, with: " ", options: [.caseInsensitive])
        }
        cleaned = cleaned
            .replacingOccurrences(of: #"[^\p{L}\p{N}\s\-\+\#\./:&]"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)

        let words = cleaned.split(separator: " ")
        guard words.count >= 2 else { return nil }
        return cleaned
    }

    private static func contextualPreviousQuery(from messages: [ChatMessage]) -> String? {
        for message in messages.reversed() where message.role == .user {
            let candidate = message.content.trimmingCharacters(in: .whitespacesAndNewlines)
            if !candidate.isEmpty, !isSearchDirective(candidate), candidate.count >= 6 {
                return contextualizedQuery(candidate, previousMessages: messages)
            }
        }
        return nil
    }

    private static func contextualizedQuery(_ query: String, previousMessages: [ChatMessage]) -> String {
        let cleaned = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard referencesPriorSubject(cleaned), let subject = recentSubject(from: previousMessages) else {
            return cleaned
        }

        if cleaned.lowercased().contains("ipo") {
            return "\(subject) IPO"
        }
        return "\(subject) \(cleaned)"
    }

    private static func referencesPriorSubject(_ text: String) -> Bool {
        let words = Set(text.lowercased().split(separator: " ").map(String.init))
        return !words.intersection(["it", "its", "they", "them", "their", "he", "she"]).isEmpty
    }

    private static func isWeakFollowUp(_ text: String) -> Bool {
        let words = text.lowercased().split(separator: " ").map(String.init)
        guard !words.isEmpty else { return true }
        return words.allSatisfy { weakFollowUpWords.contains($0) }
    }

    private static func recentSubject(from messages: [ChatMessage]) -> String? {
        for message in messages.reversed() {
            let content = message.content
            if content.localizedCaseInsensitiveContains("SpaceX") {
                return "SpaceX"
            }
            if content.localizedCaseInsensitiveContains("Tesla") {
                return "Tesla"
            }
            if content.localizedCaseInsensitiveContains("OpenAI") {
                return "OpenAI"
            }
            if let properNoun = firstLikelyProperNoun(in: content) {
                return properNoun
            }
        }
        return nil
    }

    private static func firstLikelyProperNoun(in text: String) -> String? {
        let pattern = #"\b[A-Z][A-Za-z0-9]*(?:\s+[A-Z][A-Za-z0-9]*){0,2}\b"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let nsRange = NSRange(text.startIndex..<text.endIndex, in: text)
        let ignored: Set<String> = ["I", "No", "Yes", "The", "However", "Based", "Web", "Search"]
        for match in regex.matches(in: text, range: nsRange) {
            guard let range = Range(match.range, in: text) else { continue }
            let candidate = String(text[range]).trimmingCharacters(in: .whitespacesAndNewlines)
            if !ignored.contains(candidate), candidate.count > 1 {
                return candidate
            }
        }
        return nil
    }
}
