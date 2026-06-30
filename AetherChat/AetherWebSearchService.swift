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

        let searchQuery = Self.enhancedQuery(cleaned)
        var components = URLComponents(string: "https://r.jina.ai/http://lite.duckduckgo.com/lite/")!
        components.queryItems = [
            URLQueryItem(name: "q", value: searchQuery)
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
            searchQuery: searchQuery,
            documents: Self.rankedDocuments(from: raw),
            fallbackBody: Self.extractContext(from: raw, maxCharacters: maxCharacters)
        )
        return AetherWebSearchResult(query: cleaned, context: context)
    }

    private static func enhancedQuery(_ query: String) -> String {
        let lowercased = query.lowercased()
        let isMarketQuery = ["ipo", "stock", "ticker", "public", "nasdaq", "nyse", "shares"].contains { lowercased.contains($0) }
        guard isMarketQuery else { return query }
        return "\(query) SEC Nasdaq Reuters"
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

    private static func formatContext(
        query: String,
        searchQuery: String,
        documents: [AetherSearchDocument],
        fallbackBody: String
    ) -> String {
        if !documents.isEmpty {
            let resultText = documents.prefix(6).enumerated().map { index, document in
                """
                [\(index + 1)] \(document.title)
                Source: \(document.source)
                URL: \(document.url)
                Snippet: \(document.snippet)
                """
            }.joined(separator: "\n\n")

            return """
            Web search was performed for: \(query)
            Search query used: \(searchQuery)

            Grounding rules:
            - Prefer higher-ranked sources first. Reuters, SEC, Nasdaq, AP, CNBC, Yahoo Finance, and official company/investor pages outrank SEO blogs, ads, and anonymous trackers.
            - For public-company, IPO, ticker, stock, price, and date questions, answer only what these sources explicitly support.
            - If sources conflict, say that the results conflict and summarize the strongest source rather than inventing a compromise.
            - Do not repeat claims from sponsored links or low-ranked snippets when a higher-ranked source disagrees.

            Ranked search results:
            \(resultText)
            """
        }

        let trimmed = fallbackBody.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        return """
        Web search was performed for: \(query)
        Search query used: \(searchQuery)

        Grounding rules:
        - Answer only facts explicitly present in the search text below.
        - If the search text is noisy or contradictory, say that and avoid inventing dates, tickers, prices, or amounts.

        Search results:
        \(trimmed)
        """
    }

    private static func rankedDocuments(from raw: String) -> [AetherSearchDocument] {
        let body = extractContext(from: raw, maxCharacters: 16_000)
        let lines = body
            .components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }

        var documents = [AetherSearchDocument]()
        var currentTitle: String?
        var currentURL: String?
        var currentSnippet = [String]()

        func flush() {
            guard let title = currentTitle, let url = currentURL else {
                currentTitle = nil
                currentURL = nil
                currentSnippet.removeAll()
                return
            }

            let snippet = cleanMarkdown(currentSnippet.joined(separator: " "))
            let document = AetherSearchDocument(title: cleanMarkdown(title), url: decodedResultURL(url), snippet: snippet)
            if document.isUsable {
                documents.append(document)
            }
            currentTitle = nil
            currentURL = nil
            currentSnippet.removeAll()
        }

        let pattern = #"^\d+\.\s*(?:##\s*)?\[(.+?)\]\((.+?)\)"#
        let regex = try? NSRegularExpression(pattern: pattern)

        for line in lines where !line.isEmpty {
            let range = NSRange(line.startIndex..<line.endIndex, in: line)
            if let match = regex?.firstMatch(in: line, range: range),
               let titleRange = Range(match.range(at: 1), in: line),
               let urlRange = Range(match.range(at: 2), in: line) {
                flush()
                currentTitle = String(line[titleRange])
                currentURL = String(line[urlRange])
            } else if currentTitle != nil {
                currentSnippet.append(line)
            }
        }
        flush()

        return documents
            .sorted { left, right in
                if left.score == right.score {
                    return left.title < right.title
                }
                return left.score > right.score
            }
    }

    private static func decodedResultURL(_ raw: String) -> String {
        guard let components = URLComponents(string: raw),
              let encodedURL = components.queryItems?.first(where: { $0.name == "uddg" })?.value,
              let decoded = encodedURL.removingPercentEncoding else {
            return raw
        }
        return decoded
    }

    private static func cleanMarkdown(_ text: String) -> String {
        text
            .replacingOccurrences(of: "**", with: "")
            .replacingOccurrences(of: #"\[(.*?)\]\(.*?\)"#, with: "$1", options: .regularExpression)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

private struct AetherSearchDocument: Sendable {
    let title: String
    let url: String
    let snippet: String

    var source: String {
        guard let host = URL(string: url)?.host else { return url }
        return host.replacingOccurrences(of: "www.", with: "")
    }

    var isUsable: Bool {
        let combined = "\(title) \(snippet) \(source)".lowercased()
        guard !combined.contains("sponsored link"), !combined.contains("viewing ads") else {
            return false
        }
        return !title.isEmpty && !url.isEmpty && !snippet.isEmpty
    }

    var score: Int {
        let host = source.lowercased()
        let combined = "\(title) \(snippet)".lowercased()
        var value = 0

        if host.hasSuffix("sec.gov") { value += 120 }
        if host.hasSuffix("reuters.com") { value += 110 }
        if host.hasSuffix("nasdaq.com") { value += 100 }
        if host.hasSuffix("apnews.com") { value += 95 }
        if host.hasSuffix("cnbc.com") { value += 85 }
        if host.hasSuffix("finance.yahoo.com") { value += 80 }
        if host.hasSuffix("abcnews.com") { value += 70 }
        if host.hasSuffix("investors.com") { value += 65 }
        if host.hasSuffix("forbes.com") { value += 45 }
        if host.hasSuffix("wikipedia.org") { value += 30 }

        if combined.contains("sec") { value += 16 }
        if combined.contains("nasdaq") || combined.contains("nyse") { value += 14 }
        if combined.contains("ticker") { value += 10 }
        if combined.contains(" ipo") || combined.contains("initial public offering") { value += 10 }
        if combined.contains("priced") || combined.contains("completed") || combined.contains("raised") { value += 8 }
        if combined.contains("preparing") || combined.contains("expected") || combined.contains("could") || combined.contains("plans") {
            value -= 8
        }
        if host.contains("duckduckgo.com") || host.contains("clickguard") { value -= 100 }

        return value
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
