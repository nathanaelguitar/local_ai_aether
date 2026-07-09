import Foundation
import SQLite3

struct AetherMemoryHit: Sendable {
    let messageID: UUID
    let role: MessageRole
    let content: String
    let timestamp: Date
}

enum AetherMemoryPlanner {
    static func summary(for messages: [ChatMessage], existingSummary: String = "") -> String {
        let nonErrorMessages = messages.filter { !$0.content.hasPrefix("Inference error:") }
        guard nonErrorMessages.count > 12 else { return existingSummary }

        let older = nonErrorMessages.dropLast(8)
        let lines = older.suffix(12).map { message in
            let speaker = message.role == .user ? "User" : "Assistant"
            let text = compact(message.content, targetCharacters: message.role == .user ? 280 : 360)
            return "- \(speaker): \(text)"
        }
        guard !lines.isEmpty else { return existingSummary }
        return "Earlier conversation summary:\n" + lines.joined(separator: "\n")
    }

    static func memoryContext(summary: String, hits: [AetherMemoryHit]) -> String? {
        var parts = [String]()
        let trimmedSummary = summary.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedSummary.isEmpty {
            parts.append(trimmedSummary)
        }

        let retrieved = hits
            .filter { !$0.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .prefix(6)
            .map { hit -> String in
                let speaker = hit.role == .user ? "User" : "Assistant"
                return "- \(speaker): \(compact(hit.content, targetCharacters: 520))"
            }

        if !retrieved.isEmpty {
            parts.append("Relevant earlier turns retrieved from local memory:\n" + retrieved.joined(separator: "\n"))
        }

        guard !parts.isEmpty else { return nil }
        return parts.joined(separator: "\n\n")
    }

    static func compact(_ text: String, targetCharacters: Int) -> String {
        // Bound the work before the regex passes: on a multi-megabyte paste each pass is
        // a full scan and stalls the send pipeline, while only the head and tail can
        // survive compaction anyway.
        let headWindow = max(targetCharacters * 4, 24_000)
        let tailWindow = max(targetCharacters * 2, 12_000)
        let working = text.count > headWindow + tailWindow
            ? String(text.prefix(headWindow)) + " " + String(text.suffix(tailWindow))
            : text

        let cleaned = working
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard cleaned.count > targetCharacters else { return cleaned }

        let sentences = cleaned
            .components(separatedBy: CharacterSet(charactersIn: ".!?\n"))
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { $0.count >= 24 }
        let keySentences = sentences
            .sorted { score($0) > score($1) }
            .prefix(6)

        var parts = [String]()
        let headCount = max(120, targetCharacters / 4)
        let tailCount = max(100, targetCharacters / 5)
        parts.append("Opening: \(String(cleaned.prefix(headCount)))")
        if !keySentences.isEmpty {
            parts.append("Key points: " + keySentences.joined(separator: " | "))
        }
        parts.append("Ending: \(String(cleaned.suffix(tailCount)))")

        var compacted = "[Compacted summary of long content]\n" + parts.joined(separator: "\n")
        if compacted.count > targetCharacters {
            compacted = String(compacted.prefix(targetCharacters)) + "\n[Compacted to fit local context.]"
        }
        return compacted
    }

    private static func score(_ sentence: String) -> Int {
        let lc = sentence.lowercased()
        var value = min(sentence.count / 12, 18)
        let weightedTerms = [
            "because", "therefore", "important", "summary", "result", "decision",
            "error", "fix", "issue", "date", "price", "weather", "forecast",
            "requirement", "must", "should", "cannot", "can", "number", "total"
        ]
        for term in weightedTerms where lc.contains(term) {
            value += 8
        }
        if sentence.range(of: #"\d"#, options: .regularExpression) != nil {
            value += 6
        }
        return value
    }
}

final class AetherMemoryStore: @unchecked Sendable {
    static let shared = AetherMemoryStore()

    private let dbURL: URL
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private let queue = DispatchQueue(label: "aether.memory.store")

    init(databaseURL: URL? = nil) {
        if let databaseURL {
            self.dbURL = databaseURL
        } else {
            let base = try? FileManager.default.url(
                for: .applicationSupportDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            )
            let directory = (base ?? FileManager.default.temporaryDirectory).appendingPathComponent("AetherChat", isDirectory: true)
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            self.dbURL = directory.appendingPathComponent("aether.sqlite")
        }
        queue.sync {
            withDatabase { db in
                Self.setup(db)
            }
        }
    }

    func loadConversations() -> [Conversation] {
        queue.sync {
            withDatabase { db in
                Self.loadConversations(db: db, decoder: decoder)
            } ?? []
        }
    }

    func saveConversation(_ conversation: Conversation) {
        queue.sync {
            withDatabase { db in
                Self.execute(db, "BEGIN IMMEDIATE TRANSACTION")
                Self.saveConversation(conversation, db: db, encoder: encoder)
                Self.execute(db, "COMMIT")
            }
        }
    }

    func deleteConversation(id: UUID) {
        queue.sync {
            withDatabase { db in
                Self.bindAndStep(db, "DELETE FROM message_fts WHERE conversation_id = ?", [id.uuidString])
                Self.bindAndStep(db, "DELETE FROM messages WHERE conversation_id = ?", [id.uuidString])
                Self.bindAndStep(db, "DELETE FROM conversations WHERE id = ?", [id.uuidString])
            }
        }
    }

    func saveAll(_ conversations: [Conversation]) {
        queue.sync {
            withDatabase { db in
                Self.execute(db, "BEGIN IMMEDIATE TRANSACTION")
                for conversation in conversations {
                    Self.saveConversation(conversation, db: db, encoder: encoder)
                }
                Self.execute(db, "COMMIT")
            }
        }
    }

    func relevantMessages(conversationID: UUID, query: String, excluding excludedIDs: Set<UUID>, limit: Int = 6) -> [AetherMemoryHit] {
        queue.sync {
            withDatabase { db in
                Self.relevantMessages(db: db, conversationID: conversationID, query: query, excluding: excludedIDs, limit: limit)
            } ?? []
        }
    }

    private func withDatabase<T>(_ work: (OpaquePointer) -> T) -> T? {
        var db: OpaquePointer?
        guard sqlite3_open(dbURL.path, &db) == SQLITE_OK, let db else { return nil }
        defer { sqlite3_close(db) }
        sqlite3_exec(db, "PRAGMA foreign_keys = ON", nil, nil, nil)
        sqlite3_exec(db, "PRAGMA journal_mode = WAL", nil, nil, nil)
        return work(db)
    }

    private static func setup(_ db: OpaquePointer) {
        execute(db, """
        CREATE TABLE IF NOT EXISTS conversations (
            id TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            workspace_json BLOB NOT NULL,
            persona_json BLOB NOT NULL,
            is_pinned INTEGER NOT NULL,
            preview_text TEXT NOT NULL,
            updated_at REAL NOT NULL,
            memory_summary TEXT NOT NULL
        )
        """)
        execute(db, """
        CREATE TABLE IF NOT EXISTS messages (
            id TEXT PRIMARY KEY,
            conversation_id TEXT NOT NULL,
            sort_index INTEGER NOT NULL,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            attachments_json BLOB NOT NULL,
            timestamp REAL NOT NULL,
            FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
        )
        """)
        execute(db, """
        CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
            message_id UNINDEXED,
            conversation_id UNINDEXED,
            role UNINDEXED,
            content
        )
        """)
        execute(db, "CREATE INDEX IF NOT EXISTS idx_messages_conversation_sort ON messages(conversation_id, sort_index)")
    }

    private static func loadConversations(db: OpaquePointer, decoder: JSONDecoder) -> [Conversation] {
        var statement: OpaquePointer?
        let sql = """
        SELECT id, title, workspace_json, persona_json, is_pinned, preview_text, updated_at, memory_summary
        FROM conversations
        ORDER BY is_pinned DESC, updated_at DESC
        """
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(statement) }

        var conversations = [Conversation]()
        while sqlite3_step(statement) == SQLITE_ROW {
            guard
                let idText = string(statement, 0),
                let id = UUID(uuidString: idText),
                let title = string(statement, 1),
                let workspaceData = blob(statement, 2),
                let personaData = blob(statement, 3),
                let workspace = try? decoder.decode(Workspace.self, from: workspaceData),
                let persona = try? decoder.decode(AssistantPersona.self, from: personaData)
            else { continue }

            let messages = loadMessages(db: db, conversationID: id, decoder: decoder)
            let conversation = Conversation(
                id: id,
                title: title,
                workspace: workspace,
                persona: persona,
                isPinned: sqlite3_column_int(statement, 4) != 0,
                previewText: string(statement, 5) ?? "",
                updatedAt: Date(timeIntervalSince1970: sqlite3_column_double(statement, 6)),
                messages: messages,
                memorySummary: string(statement, 7) ?? ""
            )
            conversations.append(conversation)
        }
        return conversations
    }

    private static func loadMessages(db: OpaquePointer, conversationID: UUID, decoder: JSONDecoder) -> [ChatMessage] {
        var statement: OpaquePointer?
        let sql = """
        SELECT id, role, content, attachments_json, timestamp
        FROM messages
        WHERE conversation_id = ?
        ORDER BY sort_index ASC
        """
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(statement) }
        sqlite3_bind_text(statement, 1, conversationID.uuidString, -1, SQLITE_TRANSIENT)

        var messages = [ChatMessage]()
        while sqlite3_step(statement) == SQLITE_ROW {
            guard
                let idText = string(statement, 0),
                let id = UUID(uuidString: idText),
                let roleText = string(statement, 1),
                let role = MessageRole(rawValue: roleText),
                let content = string(statement, 2),
                let attachmentData = blob(statement, 3),
                let attachments = try? decoder.decode([ChatAttachment].self, from: attachmentData)
            else { continue }
            messages.append(ChatMessage(
                id: id,
                role: role,
                content: content,
                attachments: attachments,
                timestamp: Date(timeIntervalSince1970: sqlite3_column_double(statement, 4))
            ))
        }
        return messages
    }

    private static func saveConversation(_ conversation: Conversation, db: OpaquePointer, encoder: JSONEncoder) {
        let workspaceData = (try? encoder.encode(conversation.workspace)) ?? Data()
        let personaData = (try? encoder.encode(conversation.persona)) ?? Data()
        // UPSERT, not INSERT OR REPLACE: REPLACE deletes the existing row first, and the
        // messages table's ON DELETE CASCADE would silently wipe the whole conversation's
        // messages on every save.
        bindAndStep(db, """
        INSERT INTO conversations
        (id, title, workspace_json, persona_json, is_pinned, preview_text, updated_at, memory_summary)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
            title = excluded.title,
            workspace_json = excluded.workspace_json,
            persona_json = excluded.persona_json,
            is_pinned = excluded.is_pinned,
            preview_text = excluded.preview_text,
            updated_at = excluded.updated_at,
            memory_summary = excluded.memory_summary
        """, [
            conversation.id.uuidString,
            conversation.title,
            workspaceData,
            personaData,
            conversation.isPinned ? 1 : 0,
            conversation.previewText,
            conversation.updatedAt.timeIntervalSince1970,
            conversation.memorySummary
        ])

        // Append fast path: sends only add messages to the tail, so skip re-encoding and
        // rewriting every stored row (which is O(conversation length) in attachment
        // bytes). Any in-place edit in AppState also truncates the tail, which makes
        // stored.count >= messages.count and falls through to the full rewrite below.
        let storedIDs = storedMessageIDs(db: db, conversationID: conversation.id)
        if storedIDs.count < conversation.messages.count,
           conversation.messages.prefix(storedIDs.count).map({ $0.id.uuidString }) == storedIDs {
            for index in storedIDs.count..<conversation.messages.count {
                insertMessage(conversation.messages[index], at: index, conversationID: conversation.id, db: db, encoder: encoder)
            }
            return
        }

        bindAndStep(db, "DELETE FROM message_fts WHERE conversation_id = ?", [conversation.id.uuidString])
        bindAndStep(db, "DELETE FROM messages WHERE conversation_id = ?", [conversation.id.uuidString])
        for (index, message) in conversation.messages.enumerated() {
            insertMessage(message, at: index, conversationID: conversation.id, db: db, encoder: encoder)
        }
    }

    private static func insertMessage(
        _ message: ChatMessage,
        at index: Int,
        conversationID: UUID,
        db: OpaquePointer,
        encoder: JSONEncoder
    ) {
        let attachmentsData = (try? encoder.encode(message.attachments)) ?? Data()
        bindAndStep(db, """
        INSERT OR REPLACE INTO messages
        (id, conversation_id, sort_index, role, content, attachments_json, timestamp)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """, [
            message.id.uuidString,
            conversationID.uuidString,
            index,
            message.role.rawValue,
            message.content,
            attachmentsData,
            message.timestamp.timeIntervalSince1970
        ])
        bindAndStep(db, """
        INSERT INTO message_fts (message_id, conversation_id, role, content)
        VALUES (?, ?, ?, ?)
        """, [
            message.id.uuidString,
            conversationID.uuidString,
            message.role.rawValue,
            searchableText(for: message)
        ])
    }

    private static func storedMessageIDs(db: OpaquePointer, conversationID: UUID) -> [String] {
        var statement: OpaquePointer?
        let sql = "SELECT id FROM messages WHERE conversation_id = ? ORDER BY sort_index ASC"
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(statement) }
        sqlite3_bind_text(statement, 1, conversationID.uuidString, -1, SQLITE_TRANSIENT)

        var ids = [String]()
        while sqlite3_step(statement) == SQLITE_ROW {
            if let id = string(statement, 0) {
                ids.append(id)
            }
        }
        return ids
    }

    private static func relevantMessages(
        db: OpaquePointer,
        conversationID: UUID,
        query: String,
        excluding excludedIDs: Set<UUID>,
        limit: Int
    ) -> [AetherMemoryHit] {
        guard let ftsQuery = ftsQuery(from: query) else { return [] }
        var statement: OpaquePointer?
        let sql = """
        SELECT m.id, m.role, m.content, m.timestamp
        FROM message_fts f
        JOIN messages m ON m.id = f.message_id
        WHERE f.conversation_id = ? AND message_fts MATCH ?
        ORDER BY bm25(message_fts), m.timestamp DESC
        LIMIT ?
        """
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(statement) }
        sqlite3_bind_text(statement, 1, conversationID.uuidString, -1, SQLITE_TRANSIENT)
        sqlite3_bind_text(statement, 2, ftsQuery, -1, SQLITE_TRANSIENT)
        sqlite3_bind_int(statement, 3, Int32(limit * 3))

        var hits = [AetherMemoryHit]()
        while sqlite3_step(statement) == SQLITE_ROW, hits.count < limit {
            guard
                let idText = string(statement, 0),
                let id = UUID(uuidString: idText),
                !excludedIDs.contains(id),
                let roleText = string(statement, 1),
                let role = MessageRole(rawValue: roleText),
                let content = string(statement, 2)
            else { continue }
            hits.append(AetherMemoryHit(
                messageID: id,
                role: role,
                content: content,
                timestamp: Date(timeIntervalSince1970: sqlite3_column_double(statement, 3))
            ))
        }
        return hits
    }

    private static func ftsQuery(from text: String) -> String? {
        let words = text
            .lowercased()
            .components(separatedBy: CharacterSet.alphanumerics.inverted)
            .filter { $0.count >= 3 }
        let stopWords: Set<String> = ["what", "about", "with", "from", "that", "this", "have", "would", "could", "should", "please", "tell", "there", "where", "when"]
        let terms = Array(Set(words.filter { !stopWords.contains($0) })).prefix(8)
        guard !terms.isEmpty else { return nil }
        return terms.map { "\($0)*" }.joined(separator: " OR ")
    }

    private static func searchableText(for message: ChatMessage) -> String {
        var parts = [message.content]
        parts += message.attachments.compactMap(\.extractedText)
        return parts.joined(separator: "\n")
    }

    private static func bindAndStep(_ db: OpaquePointer, _ sql: String, _ values: [Any]) {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(statement) }
        for (index, value) in values.enumerated() {
            bind(value, to: statement, at: Int32(index + 1))
        }
        sqlite3_step(statement)
    }

    private static func execute(_ db: OpaquePointer, _ sql: String) {
        sqlite3_exec(db, sql, nil, nil, nil)
    }

    private static func bind(_ value: Any, to statement: OpaquePointer?, at index: Int32) {
        switch value {
        case let value as String:
            sqlite3_bind_text(statement, index, value, -1, SQLITE_TRANSIENT)
        case let value as Int:
            sqlite3_bind_int(statement, index, Int32(value))
        case let value as Double:
            sqlite3_bind_double(statement, index, value)
        case let value as Data:
            _ = value.withUnsafeBytes { buffer in
                sqlite3_bind_blob(statement, index, buffer.baseAddress, Int32(value.count), SQLITE_TRANSIENT)
            }
        default:
            sqlite3_bind_null(statement, index)
        }
    }

    private static func string(_ statement: OpaquePointer?, _ column: Int32) -> String? {
        guard let pointer = sqlite3_column_text(statement, column) else { return nil }
        return String(cString: pointer)
    }

    private static func blob(_ statement: OpaquePointer?, _ column: Int32) -> Data? {
        guard let bytes = sqlite3_column_blob(statement, column) else { return Data() }
        let count = Int(sqlite3_column_bytes(statement, column))
        return Data(bytes: bytes, count: count)
    }
}

private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
