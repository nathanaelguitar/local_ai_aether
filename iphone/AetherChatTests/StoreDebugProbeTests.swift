import SQLite3
import XCTest
@testable import AetherChat

/// Temporary diagnostic: verifies the append fast path engages by checking row counts
/// and per-save timing directly against the SQLite file.
final class StoreDebugProbeTests: XCTestCase {

    func testProbeAppendFastPath() {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("probe-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: url) }
        let store = AetherMemoryStore(databaseURL: url)

        var conversation = Conversation(title: "Probe", workspace: .work, persona: .default)
        for turn in 0..<10 {
            conversation.messages.append(
                ChatMessage(role: .user, content: "msg \(turn)", attachments: [
                    ChatAttachment(data: Data(repeating: 7, count: 300 * 1024), mimeType: "image/jpeg", filename: "p.jpg")
                ])
            )
            let start = Date()
            store.saveConversation(conversation)
            print("PROBE save turn \(turn): \(Date().timeIntervalSince(start))s")
        }

        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &db), SQLITE_OK)
        defer { sqlite3_close(db) }

        func scalar(_ sql: String) -> Int {
            var statement: OpaquePointer?
            guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else { return -1 }
            defer { sqlite3_finalize(statement) }
            guard sqlite3_step(statement) == SQLITE_ROW else { return -2 }
            return Int(sqlite3_column_int(statement, 0))
        }

        let messageCount = scalar("SELECT COUNT(*) FROM messages")
        let ftsCount = scalar("SELECT COUNT(*) FROM message_fts")

        // Reproduce the storedMessageIDs query with a bound parameter.
        var boundStatement: OpaquePointer?
        XCTAssertEqual(sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM messages WHERE conversation_id = ?", -1, &boundStatement, nil), SQLITE_OK)
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        sqlite3_bind_text(boundStatement, 1, conversation.id.uuidString, -1, transient)
        XCTAssertEqual(sqlite3_step(boundStatement), SQLITE_ROW)
        print("PROBE bound-count=\(sqlite3_column_int(boundStatement, 0)) for id=\(conversation.id.uuidString)")
        sqlite3_finalize(boundStatement)

        var idStatement: OpaquePointer?
        sqlite3_prepare_v2(db, "SELECT DISTINCT conversation_id FROM messages LIMIT 3", -1, &idStatement, nil)
        while sqlite3_step(idStatement) == SQLITE_ROW {
            if let pointer = sqlite3_column_text(idStatement, 0) {
                print("PROBE stored conversation_id=\(String(cString: pointer))")
            }
        }
        sqlite3_finalize(idStatement)
        print("PROBE rows: messages=\(messageCount) fts=\(ftsCount)")
        XCTAssertEqual(messageCount, 10)
        XCTAssertEqual(ftsCount, 10, "FTS duplicates mean the fast path re-inserted existing rows")
    }
}
