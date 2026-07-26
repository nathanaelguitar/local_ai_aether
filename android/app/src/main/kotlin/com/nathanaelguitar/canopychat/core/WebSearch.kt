package com.nathanaelguitar.canopychat.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// Port of AetherWebSearchService and AetherWebSearchIntent from
// iphone/AetherChat/AetherWebSearchService.swift.

data class WebCitation(val title: String, val url: String, val source: String) {
    val markdownTitle: String
        get() {
            val cleaned = title.replace("[", "\\[").replace("]", "\\]").trim()
            return if (cleaned.isEmpty()) source else "$cleaned - $source"
        }
}

data class WebSearchResult(
    val query: String,
    val context: String,
    val citations: List<WebCitation>
) {
    val sourcesMarkdown: String?
        get() {
            val lines = citations.take(4).map { "- [${it.markdownTitle}](${it.url})" }
            return if (lines.isEmpty()) null else "Sources\n" + lines.joinToString("\n")
        }
}

private data class SearchDocument(val title: String, val url: String, val snippet: String) {
    val source: String
        get() = try {
            URL(url).host?.removePrefix("www.") ?: url
        } catch (_: Exception) {
            url
        }

    val isUsable: Boolean
        get() {
            val combined = "$title $snippet $source".lowercase()
            if ("sponsored link" in combined || "viewing ads" in combined) return false
            return title.isNotEmpty() && url.isNotEmpty() && snippet.isNotEmpty()
        }

    /**
     * Port of AetherSearchDocument.score(for:) on iOS — query-aware boosts layered on
     * top of the domain base score.
     */
    fun score(query: String): Int {
        val lowercasedQuery = query.lowercase()
        val combined = "$title $snippet $source".lowercase()
        var value = score

        val asksRemainingTeams = listOf(
            "teams left", "who is left", "who's left", "left in", "remain", "remaining",
            "qualified", "eliminated", "knocked out"
        ).any { it in lowercasedQuery }

        if (asksRemainingTeams) {
            if (listOf("eliminated", "knocked out", "lost to", "exit door").any { it in combined }) {
                value += 28
            }
            if (listOf("remaining", "teams left", "still in", "bracket", "quarterfinal").any { it in combined }) {
                value += 24
            }
            if (listOf(
                    "fifa ranking", "ranked #", "power ranking", "top contenders", "favorites"
                ).any { it in combined }
            ) {
                value -= 50
            }
            if ("round of 32" in combined || "group stage" in combined) value -= 18
        }

        return value
    }

    val score: Int
        get() {
            val host = source.lowercase()
            val combined = "$title $snippet".lowercase()
            var value = 0

            if (host.endsWith("fifa.com")) value += 115
            if (host.endsWith("espn.com")) value += 105
            if (host.endsWith("cbssports.com")) value += 95
            if (host.endsWith("nbcsports.com")) value += 92
            if (host.endsWith("sportingnews.com")) value += 88
            if (host.endsWith("si.com")) value += 84
            if (host.endsWith("usatoday.com")) value += 76
            if (host.endsWith("foxsports.com")) value += 72
            if (host.endsWith("olympics.com")) value += 65
            if (host.endsWith("worldcupwiki.com")) value += 36
            if (host.endsWith("worldcuppass.com")) value += 28

            if (host.endsWith("sec.gov")) value += 120
            if (host.endsWith("reuters.com")) value += 110
            if (host.endsWith("nasdaq.com")) value += 100
            if (host.endsWith("apnews.com")) value += 95
            if (host.endsWith("cnbc.com")) value += 85
            if (host.endsWith("finance.yahoo.com")) value += 80
            if (host.endsWith("abcnews.com")) value += 70
            if (host.endsWith("investors.com")) value += 65
            if (host.endsWith("forbes.com")) value += 45
            if (host.endsWith("wikipedia.org")) value += 30
            if ("sec" in combined) value += 16
            if ("nasdaq" in combined || "nyse" in combined) value += 14
            if ("ticker" in combined) value += 10
            if (" ipo" in combined || "initial public offering" in combined) value += 10
            if (listOf("priced", "completed", "raised", "went public", "began trading", "closed").any { it in combined }) value += 28
            if (listOf("preparing", "expected", "could", "plans", "planned", "projected", "target", "aims", "set to").any { it in combined }) value -= 24
            if ("duckduckgo.com" in host || "clickguard" in host) value -= 100
            return value
        }
}

class WebSearchService {

    private val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36"

    suspend fun search(query: String, maxCharacters: Int = 8_000): WebSearchResult = withContext(Dispatchers.IO) {
        val cleaned = query.trim()
        if (cleaned.isEmpty()) return@withContext WebSearchResult(cleaned, "", emptyList())

        val searchQuery = enhancedQuery(cleaned)

        // iOS tries DuckDuckGo Lite directly first and only falls back to the r.jina.ai
        // proxy when that fails, so the proxy is not a hard dependency.
        runCatching { directDuckDuckGoSearch(cleaned, searchQuery) }.getOrNull()?.let {
            return@withContext it
        }

        val encoded = URLEncoder.encode(searchQuery, "UTF-8")
        val url = URL("https://r.jina.ai/http://lite.duckduckgo.com/lite/?q=$encoded")

        val raw = try {
            (url.openConnection() as HttpURLConnection).run {
                setRequestProperty("User-Agent", USER_AGENT)
                connectTimeout = 25_000
                readTimeout = 25_000
                if (responseCode !in 200..299) return@withContext WebSearchResult(cleaned, "", emptyList())
                inputStream.bufferedReader().readText()
            }
        } catch (_: Exception) {
            return@withContext WebSearchResult(cleaned, "", emptyList())
        }

        // The proxy returns this in the body (not as an HTTP error) when it wants a key.
        if (raw.contains("AuthenticationRequiredError", ignoreCase = true) || raw.isBlank()) {
            return@withContext WebSearchResult(cleaned, "", emptyList())
        }

        val documents = rankDocuments(rankedDocuments(raw), cleaned)
        val context = formatContext(cleaned, searchQuery, documents, extractContext(raw, maxCharacters))
        val citations = documents.take(4).map { WebCitation(it.title, it.url, it.source) }
        WebSearchResult(cleaned, context, citations)
    }

    /** Port of directDuckDuckGoSearch in iphone/AetherChat/AetherWebSearchService.swift. */
    private fun directDuckDuckGoSearch(cleaned: String, searchQuery: String): WebSearchResult {
        val encoded = URLEncoder.encode(searchQuery, "UTF-8")
        val connection = URL("https://lite.duckduckgo.com/lite/?q=$encoded")
            .openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.connectTimeout = 18_000
        connection.readTimeout = 18_000
        if (connection.responseCode !in 200..299) throw IllegalStateException("Search unavailable")

        val html = connection.inputStream.bufferedReader().use { it.readText() }
        val documents = rankDocuments(duckDuckGoLiteDocuments(html), cleaned)
        if (documents.isEmpty()) throw IllegalStateException("No search results")

        return WebSearchResult(
            query = cleaned,
            context = formatContext(cleaned, searchQuery, documents, ""),
            citations = documents.take(4).map { WebCitation(it.title, it.url, it.source) }
        )
    }

    /** Port of duckDuckGoLiteDocuments(fromHTML:) on iOS. */
    private fun duckDuckGoLiteDocuments(html: String): List<SearchDocument> {
        val pattern = Regex(
            """<a\s+rel="nofollow"\s+href="([^"]+)"\s+class='result-link'>(.*?)</a>.*?<td\s+class='result-snippet'\s*>(.*?)</td>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        return pattern.findAll(html).mapNotNull { match ->
            val document = SearchDocument(
                title = cleanHtml(match.groupValues[2]),
                url = decodedResultUrl(cleanHtml(match.groupValues[1])),
                snippet = cleanHtml(match.groupValues[3])
            )
            document.takeIf { it.isUsable }
        }.toList()
    }

    /** Port of rankDocuments(_:for:) on iOS — ties break on title so ordering is stable. */
    private fun rankDocuments(documents: List<SearchDocument>, query: String): List<SearchDocument> =
        documents.sortedWith(
            compareByDescending<SearchDocument> { it.score(query) }.thenBy { it.title }
        )

    private fun cleanHtml(text: String): String = text
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun enhancedQuery(query: String): String {
        val lc = query.lowercase()
        val isMarket = listOf("ipo", "stock", "ticker", "public", "nasdaq", "nyse", "shares").any { it in lc }
        if (isMarket) return "$query SEC Nasdaq Reuters completed priced raised trading latest"

        if (isSportsTournamentQuery(lc)) {
            return "$query FIFA ESPN CBS Sports Sporting News Sports Illustrated eliminated " +
                "qualified bracket remaining teams ${currentDateString()}"
        }

        return query
    }

    /** Port of isSportsTournamentQuery on iOS. */
    private fun isSportsTournamentQuery(lowercased: String): Boolean {
        val hasTournament = listOf(
            "world cup", "fifa", "tournament", "match", "game", "quarterfinal",
            "semifinal", "final", "round of", "bracket"
        ).any { it in lowercased }
        val hasSportsIntent = listOf(
            "teams left", "who is left", "who's left", "left in", "remain",
            "remaining", "qualified", "qualify", "eliminated", "knocked out",
            "lost", "won", "win", "score", "schedule", "playing", "against"
        ).any { it in lowercased }
        return hasTournament && hasSportsIntent
    }

    private fun extractContext(raw: String, maxCharacters: Int): String {
        val normalized = raw.replace("\r\n", "\n").replace("\r", "\n")
        val marker = "Markdown Content:\n"
        val index = normalized.indexOf(marker)
        if (index < 0) return normalized.take(maxCharacters)
        return normalized.substring(index + marker.length).take(maxCharacters)
    }

    private fun rankedDocuments(raw: String): List<SearchDocument> {
        val body = extractContext(raw, 16_000)
        val lines = body.lines().map { it.trim() }

        val documents = mutableListOf<SearchDocument>()
        var currentTitle: String? = null
        var currentUrl: String? = null
        val currentSnippet = mutableListOf<String>()

        fun flush() {
            val title = currentTitle
            val url = currentUrl
            if (title != null && url != null) {
                val document = SearchDocument(
                    title = cleanMarkdown(title),
                    url = decodedResultUrl(url),
                    snippet = cleanMarkdown(currentSnippet.joinToString(" "))
                )
                if (document.isUsable) documents.add(document)
            }
            currentTitle = null
            currentUrl = null
            currentSnippet.clear()
        }

        val pattern = Regex("^\\d+\\.\\s*(?:##\\s*)?\\[(.+?)]\\((.+?)\\)")
        for (line in lines) {
            if (line.isEmpty()) continue
            val match = pattern.find(line)
            if (match != null) {
                flush()
                currentTitle = match.groupValues[1]
                currentUrl = match.groupValues[2]
            } else if (currentTitle != null) {
                currentSnippet.add(line)
            }
        }
        flush()

        return documents.sortedWith(compareByDescending<SearchDocument> { it.score }.thenBy { it.title })
    }

    private fun decodedResultUrl(raw: String): String = try {
        val query = URL(raw).query ?: return raw
        query.split("&")
            .map { it.split("=", limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == "uddg" }
            ?.let { URLDecoder.decode(it[1], "UTF-8") }
            ?: raw
    } catch (_: Exception) {
        raw
    }

    private fun cleanMarkdown(text: String): String = text
        .replace("**", "")
        .replace(Regex("\\[(.*?)]\\(.*?\\)"), "$1")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun currentDateString(): String =
        DateFormat.getDateInstance(DateFormat.LONG, Locale.US).format(Date())

    companion object {
        /**
         * Port of AetherWebSearchIntent.offlineContext on iOS. The unavailable notice is
         * only included the first time per conversation so follow-up turns don't repeat
         * a stock disclaimer.
         */
        fun offlineContext(query: String, includeUnavailableNotice: Boolean = true): String {
            val cleaned = query.trim()
            val noticeRule = if (includeUnavailableNotice) {
                "- You may briefly explain that live web access is unavailable if that helps set " +
                    "expectations, but do not repeat a stock disclaimer on every follow-up."
            } else {
                "- Live web access is unavailable for this turn, but do not repeat that fact unless " +
                    "the user asks about freshness or the answer genuinely depends on current information."
            }
            return """
            Web search was requested for: $cleaned
            Current date: ${DateFormat.getDateInstance(DateFormat.LONG, Locale.US).format(Date())}.

            Network status: offline. CanopyChat does not currently have access to the web, likely because the device is in Airplane Mode or has no internet connection.

            Offline response rules:
            $noticeRule
            - Do not claim that web search was performed.
            - Do not cite sources or mention search results.
            - If this is about current events, weather, prices, stocks, sports results, schedules, restaurants, local places, or anything that needs live information, say you do not have enough current information to answer reliably.
            - If you can still provide useful non-current background from general knowledge, make it clear that it may be outdated.
            """.trimIndent()
        }
    }

    private fun formatContext(
        query: String,
        searchQuery: String,
        documents: List<SearchDocument>,
        fallbackBody: String
    ): String {
        if (documents.isNotEmpty()) {
            val resultText = documents.take(6).mapIndexed { index, document ->
                """
                [${index + 1}] ${document.title}
                Source: ${document.source}
                URL: ${document.url}
                Snippet: ${document.snippet}
                """.trimIndent()
            }.joinToString("\n\n")

            return """
            Web search was performed for: $query
            Search query used: $searchQuery
            Current date: ${currentDateString()}.

            Grounding rules:
            - Prefer higher-ranked sources first. Reuters, SEC, Nasdaq, AP, CNBC, Yahoo Finance, and official company/investor pages outrank SEO blogs, ads, and anonymous trackers.
            - For public-company, IPO, ticker, stock, price, and date questions, answer only what these sources explicitly support.
            - Treat "planned", "targeted", "expected", and "projected" claims as stale when stronger sources say the event priced, raised money, listed, began trading, or completed.
            - If sources conflict, say that the results conflict and summarize the strongest source rather than inventing a compromise.
            - Do not repeat claims from sponsored links or low-ranked snippets when a higher-ranked source disagrees.

            Ranked search results:
            $resultText
            """.trimIndent()
        }

        val trimmed = fallbackBody.trim()
        if (trimmed.isEmpty()) return ""
        return """
        Web search was performed for: $query
        Search query used: $searchQuery
        Current date: ${currentDateString()}.

        Grounding rules:
        - Answer only facts explicitly present in the search text below.
        - If the search text is noisy or contradictory, say that and avoid inventing dates, tickers, prices, or amounts.

        Search results:
        $trimmed
        """.trimIndent()
    }
}

object WebSearchIntent {
    private val triggerPhrases = listOf(
        "search the web", "web search", "look it up", "look up", "search for",
        "find out", "research", "latest", "current", "today", "now", "news",
        "price", "ipo", "stock", "weather", "who won", "what happened"
    )

    private val stripPhrases = listOf(
        "search the web", "web search", "look it up", "look up", "search for",
        "find out", "research", "and get back to me", "and tell me", "please",
        "can you", "could you"
    )

    private val explicitSearchPhrases = listOf(
        "search the web", "web search", "look it up", "look up", "search for", "find out"
    )

    private val weakFollowUpWords = setOf(
        "are", "you", "sure", "really", "verify", "check", "confirm", "that", "this",
        "it", "they", "them", "he", "she", "their", "its", "did", "does", "do", "ipo"
    )

    fun query(currentText: String, previousMessages: List<ChatMessage>): String? {
        val current = currentText.trim()
        if (current.isEmpty()) return null

        val lc = current.lowercase()
        val explicitSearch = explicitSearchPhrases.any { it in lc }
        val hasTrigger = triggerPhrases.any { it in lc }

        // strippedSearchText() only removes search directives ("look up …") — it returns
        // non-null for ANY two-word message, so using it as the entry condition made
        // every ordinary sentence ("how are you") issue a web search. Require real
        // search intent first.
        if (!explicitSearch && !hasTrigger) {
            // One exception: a bare follow-up like "what about in Paris" carries no
            // trigger of its own but continues an active search domain.
            val followUp = strippedSearchText(current) ?: return null
            return inheritedSearchDomainQuery(followUp, previousMessages)
        }

        val stripped = strippedSearchText(current)
        if (!stripped.isNullOrEmpty()) {
            if (explicitSearch && isWeakFollowUp(stripped)) {
                contextualPreviousQuery(previousMessages)?.let { return it }
            }
            inheritedSearchDomainQuery(stripped, previousMessages)?.let { return it }
            return contextualizedQuery(stripped, previousMessages)
        }

        return contextualPreviousQuery(previousMessages)
    }

    private fun isSearchDirective(text: String): Boolean {
        val lc = text.lowercase()
        return triggerPhrases.any { it in lc } && strippedSearchText(text).isNullOrEmpty()
    }

    private fun strippedSearchText(text: String): String? {
        var cleaned = text
        for (phrase in stripPhrases) {
            cleaned = cleaned.replace(phrase, " ", ignoreCase = true)
        }
        cleaned = cleaned
            .replace(Regex("[^\\p{L}\\p{N}\\s\\-+#./:&]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.split(" ").size >= 2) cleaned else null
    }

    private fun contextualPreviousQuery(messages: List<ChatMessage>): String? {
        for (message in messages.reversed()) {
            if (message.role != MessageRole.USER) continue
            val candidate = message.content.trim()
            if (candidate.isNotEmpty() && !isSearchDirective(candidate) && candidate.length >= 6) {
                return contextualizedQuery(candidate, messages)
            }
        }
        return null
    }

    private fun contextualizedQuery(query: String, previousMessages: List<ChatMessage>): String {
        val cleaned = query.trim()
        if (!referencesPriorSubject(cleaned)) return cleaned
        val subject = recentSubject(previousMessages) ?: return cleaned
        return if (cleaned.lowercase().contains("ipo")) "$subject IPO" else "$subject $cleaned"
    }

    private fun inheritedSearchDomainQuery(query: String, previousMessages: List<ChatMessage>): String? {
        val domain = recentSearchDomain(previousMessages) ?: return null
        val lc = query.lowercase()
        val isLocationFollowUp = lc.startsWith("what about") || lc.startsWith("how about") ||
            lc.startsWith("and in") || lc.startsWith("in ") || lc.contains(" about in ")
        if (!isLocationFollowUp) return null

        var location = query
            .replace(Regex("(?i)\\bwhat\\s+about\\b"), " ")
            .replace(Regex("(?i)\\bhow\\s+about\\b"), " ")
            .replace(Regex("(?i)\\band\\s+in\\b"), " ")
            .replace(Regex("(?i)\\bin\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (location.length < 2) return null

        if (domain == "weather") {
            location = location.replace(Regex("(?i)\\bweather\\b"), " ").replace(Regex("\\s+"), " ").trim()
            return "weather $location"
        }
        return "$domain $location"
    }

    private fun recentSearchDomain(messages: List<ChatMessage>): String? {
        for (message in messages.reversed()) {
            if (message.role != MessageRole.USER) continue
            val lc = message.content.lowercase()
            if ("weather" in lc || "forecast" in lc || "temperature" in lc) return "weather"
            if ("ipo" in lc || "public" in lc || "stock" in lc || "ticker" in lc) return "IPO"
            if ("price" in lc) return "price"
        }
        return null
    }

    private fun referencesPriorSubject(text: String): Boolean {
        val words = text.lowercase().split(" ").toSet()
        return words.intersect(setOf("it", "its", "they", "them", "their", "he", "she")).isNotEmpty()
    }

    private fun isWeakFollowUp(text: String): Boolean {
        val words = text.lowercase().split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        return words.all { it in weakFollowUpWords }
    }

    private fun recentSubject(messages: List<ChatMessage>): String? {
        for (message in messages.reversed()) {
            val content = message.content
            for (known in listOf("SpaceX", "Tesla", "OpenAI")) {
                if (content.contains(known, ignoreCase = true)) return known
            }
            firstLikelyProperNoun(content)?.let { return it }
        }
        return null
    }

    private fun firstLikelyProperNoun(text: String): String? {
        val pattern = Regex("\\b[A-Z][A-Za-z0-9]*(?:\\s+[A-Z][A-Za-z0-9]*){0,2}\\b")
        val ignored = setOf("I", "No", "Yes", "The", "However", "Based", "Web", "Search")
        for (match in pattern.findAll(text)) {
            val candidate = match.value.trim()
            if (candidate !in ignored && candidate.length > 1) return candidate
        }
        return null
    }
}
