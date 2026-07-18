import Foundation

struct AetherWebSearchResult: Sendable {
    let query: String
    let context: String
    let citations: [AetherWebCitation]

    var sourcesMarkdown: String? {
        let lines = citations.prefix(4).map { citation in
            "- [\(citation.markdownTitle)](\(citation.url))"
        }
        guard !lines.isEmpty else { return nil }
        return "Sources\n" + lines.joined(separator: "\n")
    }
}

struct AetherWebCitation: Sendable {
    let title: String
    let url: String
    let source: String

    var markdownTitle: String {
        let cleaned = title
            .replacingOccurrences(of: "[", with: "\\[")
            .replacingOccurrences(of: "]", with: "\\]")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty else { return source }
        return "\(cleaned) - \(source)"
    }
}

struct AetherWebSearchSuggestion: Identifiable, Sendable {
    let id = UUID()
    let conversationID: UUID
    let query: String
}

enum AetherWebSearchError: Error {
    case unavailable
    case emptyResults
}

struct AetherWebSearchService: Sendable {
    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func search(query: String, maxCharacters: Int = 8_000) async throws -> AetherWebSearchResult {
        let cleaned = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty else {
            return AetherWebSearchResult(query: cleaned, context: "", citations: [])
        }

        let searchQuery = Self.enhancedQuery(cleaned)
        if let directResult = try? await directDuckDuckGoSearch(cleaned: cleaned, searchQuery: searchQuery) {
            return directResult
        }

        var components = URLComponents(string: "https://r.jina.ai/http://lite.duckduckgo.com/lite/")!
        components.queryItems = [
            URLQueryItem(name: "q", value: searchQuery)
        ]
        guard let url = components.url else {
            return AetherWebSearchResult(query: cleaned, context: "", citations: [])
        }

        var request = URLRequest(url: url)
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148", forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 25

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw AetherWebSearchError.unavailable
        }

        let raw = String(data: data, encoding: .utf8) ?? ""
        guard !raw.localizedCaseInsensitiveContains("AuthenticationRequiredError") else {
            throw AetherWebSearchError.unavailable
        }
        guard !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw AetherWebSearchError.emptyResults
        }
        let documents = Self.rankDocuments(Self.rankedDocuments(from: raw), for: cleaned)
        let context = Self.formatContext(
            query: cleaned,
            searchQuery: searchQuery,
            documents: documents,
            fallbackBody: Self.extractContext(from: raw, maxCharacters: maxCharacters)
        )
        guard !context.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw AetherWebSearchError.emptyResults
        }
        let citations = documents.prefix(4).map {
            AetherWebCitation(title: $0.title, url: $0.url, source: $0.source)
        }
        return AetherWebSearchResult(query: cleaned, context: context, citations: citations)
    }

    private func directDuckDuckGoSearch(cleaned: String, searchQuery: String) async throws -> AetherWebSearchResult {
        var components = URLComponents(string: "https://lite.duckduckgo.com/lite/")!
        components.queryItems = [
            URLQueryItem(name: "q", value: searchQuery)
        ]
        guard let url = components.url else {
            throw AetherWebSearchError.unavailable
        }

        var request = URLRequest(url: url)
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148", forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 18

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw AetherWebSearchError.unavailable
        }

        let html = String(data: data, encoding: .utf8) ?? ""
        let documents = Self.rankDocuments(Self.duckDuckGoLiteDocuments(fromHTML: html), for: cleaned)
        guard !documents.isEmpty else {
            throw AetherWebSearchError.emptyResults
        }

        let context = Self.formatContext(
            query: cleaned,
            searchQuery: searchQuery,
            documents: documents,
            fallbackBody: ""
        )
        let citations = documents.prefix(4).map {
            AetherWebCitation(title: $0.title, url: $0.url, source: $0.source)
        }
        return AetherWebSearchResult(query: cleaned, context: context, citations: citations)
    }

    private static func enhancedQuery(_ query: String) -> String {
        let lowercased = query.lowercased()
        let isMarketQuery = ["ipo", "stock", "ticker", "public", "nasdaq", "nyse", "shares"].contains { lowercased.contains($0) }
        if isMarketQuery {
            return "\(query) SEC Nasdaq Reuters completed priced raised trading latest"
        }

        if isSportsTournamentQuery(lowercased) {
            return "\(query) FIFA official current result winner status host countries tournament dates ESPN CBS Sports bracket remaining teams \(currentDateString())"
        }

        return query
    }

    private static func isSportsTournamentQuery(_ lowercased: String) -> Bool {
        let hasTournament = [
            "world cup", "fifa", "tournament", "match", "game", "quarterfinal",
            "semifinal", "final", "round of", "bracket"
        ].contains { lowercased.contains($0) }
        let hasSportsIntent = [
            "teams left", "who is left", "who's left", "left in", "remain",
            "remaining", "qualified", "qualify", "eliminated", "knocked out",
            "lost", "won", "win", "score", "schedule", "playing", "against"
        ].contains { lowercased.contains($0) }
        return hasTournament && hasSportsIntent
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

            let sportsRules = isSportsTournamentQuery(query.lowercased()) ? """
            - For sports tournament questions, answer the exact question only. If the user asks who/what teams are left, list only teams explicitly supported by the ranked sources.
            - Do not add FIFA rankings, power rankings, contenders, host facts, final-site facts, predictions, or favorites unless the user explicitly asks for them and a ranked source explicitly supports them.
            - If a ranked source says a team lost, was eliminated, or was knocked out, do not also describe that team as remaining or a top contender.
            - If the ranked sources do not provide a reliable complete list, say the search results do not provide a reliable complete list instead of filling gaps.
            """ : ""

            return """
            Web search was performed for: \(query)
            Search query used: \(searchQuery)
            Current date: \(Self.currentDateString()).

            Grounding rules:
            - Prefer higher-ranked sources first. Reuters, SEC, Nasdaq, AP, CNBC, Yahoo Finance, and official company/investor pages outrank SEO blogs, ads, and anonymous trackers.
            - For public-company, IPO, ticker, stock, price, and date questions, answer only what these sources explicitly support.
            \(sportsRules)
            - Do not use general knowledge to fill a gap in the search results. If the results do not explicitly establish a winner, location, date, score, or status, say that the search results do not establish it.
            - Treat "planned", "targeted", "expected", and "projected" claims as stale when stronger sources say the event priced, raised money, listed, began trading, or completed.
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
        Current date: \(Self.currentDateString()).

        Grounding rules:
        - Answer only facts explicitly present in the search text below.
        - If the search text is noisy or contradictory, say that and avoid inventing dates, tickers, prices, or amounts.
        - Do not use general knowledge to fill a gap in the search results. If the results do not explicitly establish the answer, say so.

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

    private static func rankDocuments(_ documents: [AetherSearchDocument], for query: String) -> [AetherSearchDocument] {
        documents.sorted { left, right in
            let leftScore = left.score(for: query)
            let rightScore = right.score(for: query)
            if leftScore == rightScore {
                return left.title < right.title
            }
            return leftScore > rightScore
        }
    }

    private static func duckDuckGoLiteDocuments(fromHTML html: String) -> [AetherSearchDocument] {
        let pattern = #"<a\s+rel="nofollow"\s+href="([^"]+)"\s+class='result-link'>(.*?)</a>.*?<td\s+class='result-snippet'\s*>(.*?)</td>"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.dotMatchesLineSeparators, .caseInsensitive]) else {
            return []
        }

        let range = NSRange(html.startIndex..<html.endIndex, in: html)
        return regex.matches(in: html, range: range).compactMap { match in
            guard let urlRange = Range(match.range(at: 1), in: html),
                  let titleRange = Range(match.range(at: 2), in: html),
                  let snippetRange = Range(match.range(at: 3), in: html) else {
                return nil
            }

            let document = AetherSearchDocument(
                title: cleanHTML(String(html[titleRange])),
                url: decodedResultURL(cleanHTML(String(html[urlRange]))),
                snippet: cleanHTML(String(html[snippetRange]))
            )
            return document.isUsable ? document : nil
        }
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

    private static func cleanHTML(_ text: String) -> String {
        text
            .replacingOccurrences(of: #"<[^>]+>"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#x27;", with: "'")
            .replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func currentDateString(date: Date = Date()) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateStyle = .long
        formatter.timeStyle = .none
        return formatter.string(from: date)
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
        baseScore
    }

    func score(for query: String) -> Int {
        let lowercasedQuery = query.lowercased()
        let combined = "\(title) \(snippet) \(source)".lowercased()
        var value = baseScore

        let asksRemainingTeams = [
            "teams left", "who is left", "who's left", "left in", "remain", "remaining",
            "qualified", "eliminated", "knocked out"
        ].contains { lowercasedQuery.contains($0) }

        if asksRemainingTeams {
            if combined.contains("eliminated") || combined.contains("knocked out") || combined.contains("lost to") || combined.contains("exit door") {
                value += 28
            }
            if combined.contains("remaining") || combined.contains("teams left") || combined.contains("still in") || combined.contains("bracket") || combined.contains("quarterfinal") {
                value += 24
            }
            if combined.contains("fifa ranking") || combined.contains("ranked #") || combined.contains("power ranking") || combined.contains("top contenders") || combined.contains("favorites") {
                value -= 50
            }
            if combined.contains("round of 32") || combined.contains("group stage") {
                value -= 18
            }
        }

        return value
    }

    private var baseScore: Int {
        let host = source.lowercased()
        let combined = "\(title) \(snippet)".lowercased()
        var value = 0

        if host.hasSuffix("fifa.com") { value += 115 }
        if host.hasSuffix("espn.com") { value += 105 }
        if host.hasSuffix("cbssports.com") { value += 95 }
        if host.hasSuffix("nbcsports.com") { value += 92 }
        if host.hasSuffix("sportingnews.com") { value += 88 }
        if host.hasSuffix("si.com") { value += 84 }
        if host.hasSuffix("usatoday.com") { value += 76 }
        if host.hasSuffix("foxsports.com") { value += 72 }
        if host.hasSuffix("olympics.com") { value += 65 }
        if host.hasSuffix("worldcupwiki.com") { value += 36 }
        if host.hasSuffix("worldcuppass.com") { value += 28 }

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
        if combined.contains("priced") || combined.contains("completed") || combined.contains("raised") || combined.contains("went public") || combined.contains("began trading") || combined.contains("closed") {
            value += 28
        }
        if combined.contains("preparing") || combined.contains("expected") || combined.contains("could") || combined.contains("plans") || combined.contains("planned") || combined.contains("projected") || combined.contains("target") || combined.contains("aims") || combined.contains("set to") {
            value -= 24
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
        "what happened",
        "último",
        "última",
        "actual",
        "hoy",
        "ahora",
        "noticias",
        "precio",
        "clima",
        "qué pasó"
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

    private static let entityLookupPrefixes = [
        "who is ", "who are ", "who was ", "where is ", "when is ", "which ",
        "tell me about ", "sabe quién ", "quién es ", "quiénes son ", "conoces a ",
        "cuáles artistas ", "qué artistas ", "dime sobre "
    ]

    private static let conversationalPhrases = [
        "hello", "hi ", "hey ", "hola", "gracias", "thank you", "thanks",
        "do you speak ", "can you speak ", "are you ", "who are you", "how are you",
        "what do you do", "what can you do", "can you help", "hablas español",
        "habla español", "tú hablas", "tu hablas", "puedes hablar"
    ]

    private static let sourceRequestPhrases = [
        "cite sources", "provide sources", "include sources", "with sources",
        "con fuentes", "cita fuentes", "fuentes"
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
        let hasSearchTrigger = triggerPhrases.contains { lc.contains($0) }
        if let stripped = strippedSearchText(from: current), !stripped.isEmpty {
            guard explicitSearch || hasSearchTrigger || isLikelyInformationRequest(lc) else {
                return nil
            }
            if explicitSearch, isWeakFollowUp(stripped), let prior = contextualPreviousQuery(from: previousMessages) {
                return prior
            }
            if let inherited = inheritedSearchDomainQuery(for: stripped, previousMessages: previousMessages) {
                return inherited
            }
            return contextualizedQuery(stripped, previousMessages: previousMessages)
        }

        if hasSearchTrigger {
            if let prior = contextualPreviousQuery(from: previousMessages) {
                return prior
            }
            return nil
        }

        return nil
    }

    static func explicitQuery(from currentText: String, previousMessages: [ChatMessage]) -> String? {
        let lowercased = currentText.lowercased()
        guard explicitSearchPhrases.contains(where: { lowercased.contains($0) }) else { return nil }
        return query(from: currentText, previousMessages: previousMessages)
    }

    private static func isLikelyInformationRequest(_ lowercased: String) -> Bool {
        let normalized = lowercased
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: .punctuationCharacters)
        if conversationalPhrases.contains(where: { normalized.contains($0) }) {
            return false
        }
        return entityLookupPrefixes.contains(where: { normalized.hasPrefix($0) })
            || sourceRequestPhrases.contains(where: { normalized.contains($0) })
    }

    static func offlineContext(for query: String, includeUnavailableNotice: Bool = true) -> String {
        let cleaned = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let noticeRule = includeUnavailableNotice
            ? "- You may briefly explain that live web access is unavailable if that helps set expectations, but do not repeat a stock disclaimer on every follow-up."
            : "- Live web access is unavailable for this turn, but do not repeat that fact unless the user asks about freshness or the answer genuinely depends on current information."
        return """
        Web search was requested for: \(cleaned)
        Current date: \(currentDateString()).

        Network status: offline. CanopyChat does not currently have access to the web, likely because the device is in Airplane Mode or has no internet connection.

        Offline response rules:
        \(noticeRule)
        - Do not claim that web search was performed.
        - Do not cite sources or mention search results.
        - If this is about current events, weather, prices, stocks, sports results, schedules, restaurants, local places, or anything that needs live information, say you do not have enough current information to answer reliably.
        - If you can still provide useful non-current background from general knowledge, make it clear that it may be outdated.
        """
    }

    private static func currentDateString(date: Date = Date()) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateStyle = .long
        formatter.timeStyle = .none
        return formatter.string(from: date)
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

    private static func inheritedSearchDomainQuery(for query: String, previousMessages: [ChatMessage]) -> String? {
        guard let domain = recentSearchDomain(from: previousMessages) else { return nil }
        let lc = query.lowercased()
        let isLocationFollowUp = lc.hasPrefix("what about")
            || lc.hasPrefix("how about")
            || lc.hasPrefix("and in")
            || lc.hasPrefix("in ")
            || lc.contains(" about in ")
        guard isLocationFollowUp else { return nil }

        var location = query
            .replacingOccurrences(of: #"(?i)\bwhat\s+about\b"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"(?i)\bhow\s+about\b"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"(?i)\band\s+in\b"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"(?i)\bin\b"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard location.count >= 2 else { return nil }

        if domain == "weather" {
            location = location.replacingOccurrences(of: #"(?i)\bweather\b"#, with: " ", options: .regularExpression)
                .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            return "weather \(location)"
        }
        return "\(domain) \(location)"
    }

    private static func recentSearchDomain(from messages: [ChatMessage]) -> String? {
        for message in messages.reversed() where message.role == .user {
            let lc = message.content.lowercased()
            if lc.contains("weather") || lc.contains("forecast") || lc.contains("temperature") {
                return "weather"
            }
            if lc.contains("ipo") || lc.contains("public") || lc.contains("stock") || lc.contains("ticker") {
                return "IPO"
            }
            if lc.contains("price") {
                return "price"
            }
        }
        return nil
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
