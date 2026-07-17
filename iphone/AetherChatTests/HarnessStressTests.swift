import XCTest
@testable import AetherChat

/// Production-readiness stress tests for the on-device pipeline:
/// SQLite memory store (RAG), prompt budgeting under attachment spam,
/// compaction behavior, and degradation before the 20k context window.
final class HarnessStressTests: XCTestCase {

    private var store: AetherMemoryStore!
    private var databaseURL: URL!

    override func setUp() {
        super.setUp()
        databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("stress-\(UUID().uuidString).sqlite")
        store = AetherMemoryStore(databaseURL: databaseURL)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: databaseURL)
        super.tearDown()
    }

    // MARK: - Helpers

    private func makeImageAttachment(kilobytes: Int) -> ChatAttachment {
        var bytes = [UInt8](repeating: 0, count: kilobytes * 1024)
        for index in bytes.indices {
            bytes[index] = UInt8(truncatingIfNeeded: index &* 31)
        }
        return ChatAttachment(data: Data(bytes), mimeType: "image/jpeg", filename: "photo.jpg")
    }

    private func makeConversation(messages: [ChatMessage] = []) -> Conversation {
        Conversation(title: "Stress", workspace: .work, persona: .default, messages: messages)
    }

    // MARK: - DB write throughput

    /// Sending in a long conversation must not slow down as history grows.
    /// The append fast path should keep per-send persistence roughly constant.
    func testAppendThroughputStaysFlat() {
        var conversation = makeConversation()
        var earlyTimes = [TimeInterval]()
        var lateTimes = [TimeInterval]()

        for turn in 0..<200 {
            let role: MessageRole = turn.isMultiple(of: 2) ? .user : .assistant
            conversation.messages.append(
                ChatMessage(role: role, content: "Turn \(turn): quarterly revenue grew \(turn)% because the launch checklist landed on time.")
            )
            let start = Date()
            store.saveConversation(conversation)
            let elapsed = Date().timeIntervalSince(start)
            if turn < 20 { earlyTimes.append(elapsed) }
            if turn >= 180 { lateTimes.append(elapsed) }
        }

        let earlyAverage = earlyTimes.reduce(0, +) / Double(earlyTimes.count)
        let lateAverage = lateTimes.reduce(0, +) / Double(lateTimes.count)
        // Allow noise, but O(n) rewrites would make late saves ~10x early ones.
        XCTAssertLessThan(lateAverage, max(earlyAverage * 5, 0.05),
                          "Append-time save degraded: early \(earlyAverage)s vs late \(lateAverage)s")

        let loaded = store.loadConversations()
        XCTAssertEqual(loaded.first?.messages.count, 200)
    }

    /// A photo-spam conversation: every message carries an image blob. Saves must stay
    /// fast (no re-encoding of all prior images) and everything must round-trip.
    func testAttachmentHeavyConversationSaveAndLoad() {
        var conversation = makeConversation()
        var lastSaveTimes = [TimeInterval]()

        for turn in 0..<40 {
            conversation.messages.append(
                ChatMessage(
                    role: .user,
                    content: "Photo \(turn)",
                    attachments: [makeImageAttachment(kilobytes: 300)]
                )
            )
            let start = Date()
            store.saveConversation(conversation)
            if turn >= 35 { lastSaveTimes.append(Date().timeIntervalSince(start)) }
        }

        let lateAverage = lastSaveTimes.reduce(0, +) / Double(lastSaveTimes.count)
        XCTAssertLessThan(lateAverage, 0.25,
                          "Appending one 300KB-image message took \(lateAverage)s once 35+ images were stored")

        let loaded = store.loadConversations()
        XCTAssertEqual(loaded.first?.messages.count, 40)
        XCTAssertEqual(loaded.first?.messages.last?.attachments.first?.data.count, 300 * 1024)
    }

    /// Edits truncate the tail in AppState; the store must fall back to a full rewrite
    /// and never serve stale content through the append fast path.
    func testEditAfterAppendRewritesCorrectly() {
        var conversation = makeConversation()
        for turn in 0..<10 {
            conversation.messages.append(ChatMessage(role: .user, content: "original \(turn)"))
            store.saveConversation(conversation)
        }

        // Simulate AppState.editUserMessage: change content at index 4, drop the tail.
        conversation.messages[4].content = "edited content"
        conversation.messages.removeSubrange(5...)
        store.saveConversation(conversation)

        conversation.messages.append(ChatMessage(role: .assistant, content: "new reply"))
        store.saveConversation(conversation)

        let loaded = store.loadConversations().first
        XCTAssertEqual(loaded?.messages.count, 6)
        XCTAssertEqual(loaded?.messages[4].content, "edited content")
        XCTAssertEqual(loaded?.messages.last?.content, "new reply")
    }

    // MARK: - RAG recall

    func testFTSRecallLatencyOnLargeCorpus() {
        var conversation = makeConversation()
        for turn in 0..<3_000 {
            let content = turn == 1_500
                ? "The wholesale espresso supplier in Cincinnati quoted 14 dollars per pound."
                : "Filler message number \(turn) about routine scheduling and reminders."
            conversation.messages.append(ChatMessage(role: turn.isMultiple(of: 2) ? .user : .assistant, content: content))
        }
        store.saveConversation(conversation)

        let start = Date()
        let hits = store.relevantMessages(
            conversationID: conversation.id,
            query: "what did the espresso supplier quote",
            excluding: [],
            limit: 6
        )
        let elapsed = Date().timeIntervalSince(start)

        XCTAssertLessThan(elapsed, 0.5, "FTS recall took \(elapsed)s on a 3k-message conversation")
        XCTAssertTrue(hits.contains { $0.content.contains("espresso supplier") },
                      "Semantic recall failed to surface the relevant turn")
    }

    func testFTSQueryHandlesEmojiCJKAndPunctuation() {
        var conversation = makeConversation()
        conversation.messages.append(ChatMessage(role: .user, content: "咖啡店的名字 should feel warm 🌳"))
        store.saveConversation(conversation)

        // None of these should crash or throw SQL errors.
        _ = store.relevantMessages(conversationID: conversation.id, query: "🌳🌳🌳", excluding: [], limit: 6)
        _ = store.relevantMessages(conversationID: conversation.id, query: "咖啡店的名字", excluding: [], limit: 6)
        _ = store.relevantMessages(conversationID: conversation.id, query: "\"; DROP TABLE messages; --", excluding: [], limit: 6)
        _ = store.relevantMessages(conversationID: conversation.id, query: String(repeating: "word ", count: 2_000), excluding: [], limit: 6)

        XCTAssertEqual(store.loadConversations().first?.messages.count, 1, "Store corrupted by hostile query text")
    }

    // MARK: - Prompt budgeting under attachment spam

    func testOfflineWebNoticeIsNotMandatoryOnEveryTurn() {
        let first = AetherWebSearchIntent.offlineContext(for: "latest weather", includeUnavailableNotice: true)
        let followUp = AetherWebSearchIntent.offlineContext(for: "latest weather", includeUnavailableNotice: false)

        XCTAssertTrue(first.contains("briefly explain that live web access is unavailable"))
        XCTAssertTrue(followUp.contains("do not repeat that fact"))
        XCTAssertFalse(followUp.contains("Start by saying"))
    }

    func testPromptPreservesLatestUserLanguageAndAvoidsUnpromptedOfflineClaim() {
        let messages = [ChatMessage(role: .user, content: "¿Cuáles artistas de reguetón son de Colombia?")]
        let prompt = AetherPromptBuilder.prompt(persona: .default, messages: messages)

        XCTAssertTrue(prompt.contains("same language as the latest user message"))
        XCTAssertTrue(prompt.contains("Do not claim that web access is unavailable"))
    }

    func testSpanishFreshnessTermsTriggerGrounding() {
        let query = AetherWebSearchIntent.query(
            from: "¿Cuál es el precio actual de este producto?",
            previousMessages: []
        )

        XCTAssertNotNil(query)
    }

    func testConversationalLanguageQuestionsDoNotTriggerGrounding() {
        XCTAssertNil(AetherWebSearchIntent.query(from: "¿Tú hablas español?", previousMessages: []))
        XCTAssertNil(AetherWebSearchIntent.query(from: "Do you speak Spanish?", previousMessages: []))
        XCTAssertNil(AetherWebSearchIntent.query(from: "No habla español", previousMessages: []))
    }

    func testSpanishInformationQuestionsStillTriggerGrounding() {
        let query = AetherWebSearchIntent.query(
            from: "¿Cuáles artistas de reguetón son de Colombia?",
            previousMessages: []
        )

        XCTAssertEqual(query, "Cuáles artistas de reguetón son de Colombia")
        XCTAssertNotNil(AetherWebSearchIntent.query(from: "Sabe quién es el cantante Blessd de Colombia", previousMessages: []))
    }

    func testStableKnowledgeQuestionsStayOnDevice() {
        XCTAssertNil(AetherWebSearchIntent.query(from: "What's the quadratic formula?", previousMessages: []))
        XCTAssertNil(AetherWebSearchIntent.query(from: "Explain recursion in Swift", previousMessages: []))
    }

    func testDisplayNormalizerConvertsCommonLatexWithoutTouchingCode() {
        let response = """
        The formula is:

        $$x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}$$

        ```text
        $$keep this literal$$
        ```
        """

        let normalized = AetherResponseNormalizer.displayText(response)
        XCTAssertTrue(normalized.contains("x = (-b ± √(b^2 - 4ac)) / (2a)"))
        XCTAssertTrue(normalized.contains("$$keep this literal$$"))
        XCTAssertFalse(normalized.contains("\\frac"))
    }

    func testTokenPiecesAreDecodedAsOneUTF8Stream() {
        let squareRoot = [
            [UInt8]([0xE2]),
            [UInt8]([0x88, 0x9A])
        ]
        let bytes = squareRoot.flatMap { $0 }

        XCTAssertEqual(String(decoding: bytes, as: UTF8.self), "√")
        XCTAssertNotEqual(String(decoding: squareRoot[0], as: UTF8.self), "√")
    }

    private let mediaMarker = "<__media__>"
    private var tokenBudget: Int {
        Int(AetherModelCatalog.aetherV1ContextTokens - AetherModelCatalog.aetherV1MaxOutputTokens) - 64
    }

    /// Spamming images must cap the encoded set and keep markers in lockstep with the
    /// bitmaps handed to llama.cpp (a mismatch is a native-side failure).
    func testImageSpamCapsMarkersAndStaysInSync() {
        var messages = [ChatMessage]()
        for turn in 0..<8 {
            let attachments = (0..<5).map { _ in makeImageAttachment(kilobytes: 50) }
            messages.append(ChatMessage(role: .user, content: "Look at these \(turn)", attachments: attachments))
        }

        for level in AetherPromptBuilder.degradationLevels {
            let included = AetherPromptBuilder.includedImages(from: messages, window: level.window)
            let prompt = AetherPromptBuilder.prompt(
                persona: .default,
                messages: messages,
                contentScale: level.scale,
                window: level.window
            )
            let markerCount = prompt.components(separatedBy: mediaMarker).count - 1

            XCTAssertLessThanOrEqual(included.count, AetherPromptBuilder.maxPromptImages)
            XCTAssertEqual(markerCount, included.count,
                           "Marker/bitmap mismatch at window \(level.window): \(markerCount) markers vs \(included.count) images")
            XCTAssertTrue(prompt.contains("omitted here to fit"),
                          "Excess images should degrade to placeholders, not vanish silently")
        }
    }

    /// The kept images must be the most recent ones, in prompt order.
    func testIncludedImagesAreMostRecentInOrder() {
        var messages = [ChatMessage]()
        var allImages = [ChatAttachment]()
        for turn in 0..<6 {
            let image = makeImageAttachment(kilobytes: 10)
            allImages.append(image)
            messages.append(ChatMessage(role: .user, content: "img \(turn)", attachments: [image]))
        }

        let included = AetherPromptBuilder.includedImages(from: messages)
        XCTAssertEqual(included.map(\.id), allImages.suffix(3).map(\.id))
    }

    /// Six 80k-character PDFs in the window must not blow the context: total file text
    /// is capped and the pre-flight estimate must fit at some degradation level.
    func testFileSpamRespectsTotalBudgetAndConverges() {
        let hugeText = String(repeating: "Clause 14.2 governs indemnification for supplier defaults. ", count: 1_400) // ~81k chars
        var messages = [ChatMessage]()
        for turn in 0..<6 {
            let file = ChatAttachment(data: Data(), mimeType: "application/pdf", filename: "contract-\(turn).pdf", extractedText: hugeText)
            messages.append(ChatMessage(role: .user, content: "Summarize contract \(turn)", attachments: [file]))
        }

        var fits = false
        for level in AetherPromptBuilder.degradationLevels {
            let prompt = AetherPromptBuilder.prompt(
                persona: .default,
                messages: messages,
                contentScale: level.scale,
                window: level.window
            )
            let estimate = AetherPromptBuilder.estimatedTokenCount(prompt: prompt, imageCount: 0)
            if estimate <= tokenBudget {
                fits = true
                break
            }
        }
        XCTAssertTrue(fits, "No degradation level fit six 80k-char files into the context budget")

        // Even at full scale the file text must respect the global cap (plus wrappers).
        let fullPrompt = AetherPromptBuilder.prompt(persona: .default, messages: messages)
        XCTAssertLessThan(fullPrompt.count, AetherPromptBuilder.maxTotalFileContextCharacters + 60_000,
                          "Full-scale prompt is \(fullPrompt.count) chars; file budget is not being enforced")
    }

    /// Worst realistic overload: max images + huge files + long pasted text + web search
    /// + memory context. The tightest level must always fit — this is what guarantees
    /// the engine's hard guard never fires in production.
    func testWorstCaseOverloadConvergesAtTightestLevel() {
        let hugeText = String(repeating: "word ", count: 20_000) // 100k chars
        let webContext = String(repeating: "Ranked search results with snippets. ", count: 210) // ~8k chars
        let memoryContext = String(repeating: "Earlier summary line. ", count: 180) // ~4k chars

        var messages = [ChatMessage]()
        for turn in 0..<8 {
            let images = (0..<6).map { _ in makeImageAttachment(kilobytes: 10) }
            let file = ChatAttachment(data: Data(), mimeType: "application/pdf", filename: "dump-\(turn).pdf", extractedText: hugeText)
            messages.append(ChatMessage(role: .user, content: hugeText, attachments: images + [file]))
        }

        guard let tightest = AetherPromptBuilder.degradationLevels.last else {
            return XCTFail("No degradation levels defined")
        }
        let prompt = AetherPromptBuilder.prompt(
            persona: .default,
            messages: messages,
            webSearchContext: webContext,
            memoryContext: memoryContext,
            contentScale: tightest.scale,
            window: tightest.window
        )
        let images = AetherPromptBuilder.includedImages(from: messages, window: tightest.window)
        let estimate = AetherPromptBuilder.estimatedTokenCount(prompt: prompt, imageCount: images.count)

        XCTAssertLessThanOrEqual(estimate, tokenBudget,
                                 "Tightest degradation level still estimates \(estimate) tokens against a \(tokenBudget) budget")
    }

    /// The token estimator must stay conservative for CJK-heavy text, where
    /// characters-per-token is close to 1.
    func testTokenEstimateIsConservativeForCJK() {
        let cjk = String(repeating: "咖啡店的名字应该温暖", count: 500) // 5,000 CJK chars
        let estimate = AetherPromptBuilder.estimatedTokenCount(prompt: cjk, imageCount: 0)
        XCTAssertGreaterThanOrEqual(estimate, 5_000, "CJK text must be counted ~1 token per character")
    }

    // MARK: - Compaction

    func testCompactHugeTextIsFastAndBounded() {
        let huge = String(repeating: "The launch plan depends on pricing because the decision affects revenue by 40 percent. ", count: 25_000) // ~2.2MB
        let start = Date()
        let compacted = AetherMemoryPlanner.compact(huge, targetCharacters: 6_000)
        let elapsed = Date().timeIntervalSince(start)

        XCTAssertLessThan(elapsed, 2.0, "Compacting 2MB of text took \(elapsed)s")
        XCTAssertLessThanOrEqual(compacted.count, 6_100, "Compaction exceeded its target: \(compacted.count) chars")
    }

    func testSummaryKicksInAndStaysBounded() {
        var messages = [ChatMessage]()
        var summary = ""
        for turn in 0..<60 {
            messages.append(ChatMessage(
                role: turn.isMultiple(of: 2) ? .user : .assistant,
                content: "Turn \(turn): the roadmap decision about pricing tiers matters because revenue depends on it."
            ))
            summary = AetherMemoryPlanner.summary(for: messages, existingSummary: summary)
        }
        XCTAssertFalse(summary.isEmpty, "Rolling summary never engaged on a 60-turn conversation")
        XCTAssertLessThan(summary.count, 8_000, "Rolling summary grew unbounded: \(summary.count) chars")
    }
}
