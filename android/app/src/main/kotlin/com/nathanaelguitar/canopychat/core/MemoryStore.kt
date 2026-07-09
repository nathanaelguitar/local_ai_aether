package com.nathanaelguitar.canopychat.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.UUID

// Port of AetherMemoryStore from iphone/AetherChat/AetherMemoryStore.swift.
// Uses FTS4 instead of FTS5 because FTS4 is guaranteed on all Android SQLite builds;
// ranking falls back to recency instead of bm25.
class MemoryStore(context: Context) : SQLiteOpenHelper(context, "canopy.sqlite", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                workspace_json TEXT NOT NULL,
                persona_json TEXT NOT NULL,
                is_pinned INTEGER NOT NULL,
                preview_text TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                memory_summary TEXT NOT NULL
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                sort_index INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                attachments_json TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
            """
        )
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts4(message_id, conversation_id, role, content)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_messages_conversation_sort ON messages(conversation_id, sort_index)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun loadConversations(): List<Conversation> {
        val conversations = mutableListOf<Conversation>()
        readableDatabase.rawQuery(
            """
            SELECT id, title, workspace_json, persona_json, is_pinned, preview_text, updated_at, memory_summary
            FROM conversations
            ORDER BY is_pinned DESC, updated_at DESC
            """,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                try {
                    val id = UUID.fromString(cursor.getString(0))
                    conversations.add(
                        Conversation(
                            id = id,
                            title = cursor.getString(1),
                            workspace = Workspace.fromJson(JSONObject(cursor.getString(2))),
                            persona = AssistantPersona.fromJson(JSONObject(cursor.getString(3))),
                            isPinned = cursor.getInt(4) != 0,
                            previewText = cursor.getString(5),
                            updatedAtMillis = cursor.getLong(6),
                            messages = loadMessages(id),
                            memorySummary = cursor.getString(7)
                        )
                    )
                } catch (_: Exception) {
                    // Skip malformed rows rather than failing the whole load.
                }
            }
        }
        return conversations
    }

    private fun loadMessages(conversationId: UUID): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        readableDatabase.rawQuery(
            """
            SELECT id, role, content, attachments_json, timestamp
            FROM messages
            WHERE conversation_id = ?
            ORDER BY sort_index ASC
            """,
            arrayOf(conversationId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                try {
                    messages.add(
                        ChatMessage(
                            id = UUID.fromString(cursor.getString(0)),
                            role = MessageRole.from(cursor.getString(1)),
                            content = cursor.getString(2),
                            attachments = attachmentsFromJsonArray(cursor.getString(3)),
                            timestampMillis = cursor.getLong(4)
                        )
                    )
                } catch (_: Exception) {
                }
            }
        }
        return messages
    }

    fun saveConversation(conversation: Conversation) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            save(db, conversation)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun saveAll(conversations: List<Conversation>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            conversations.forEach { save(db, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteConversation(id: UUID) {
        val db = writableDatabase
        db.delete("message_fts", "conversation_id = ?", arrayOf(id.toString()))
        db.delete("messages", "conversation_id = ?", arrayOf(id.toString()))
        db.delete("conversations", "id = ?", arrayOf(id.toString()))
    }

    private fun save(db: SQLiteDatabase, conversation: Conversation) {
        db.insertWithOnConflict(
            "conversations", null,
            ContentValues().apply {
                put("id", conversation.id.toString())
                put("title", conversation.title)
                put("workspace_json", conversation.workspace.toJson().toString())
                put("persona_json", conversation.persona.toJson().toString())
                put("is_pinned", if (conversation.isPinned) 1 else 0)
                put("preview_text", conversation.previewText)
                put("updated_at", conversation.updatedAtMillis)
                put("memory_summary", conversation.memorySummary)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )

        db.delete("message_fts", "conversation_id = ?", arrayOf(conversation.id.toString()))
        db.delete("messages", "conversation_id = ?", arrayOf(conversation.id.toString()))
        conversation.messages.forEachIndexed { index, message ->
            db.insertWithOnConflict(
                "messages", null,
                ContentValues().apply {
                    put("id", message.id.toString())
                    put("conversation_id", conversation.id.toString())
                    put("sort_index", index)
                    put("role", message.role.rawValue)
                    put("content", message.content)
                    put("attachments_json", jsonArrayOfAttachments(message.attachments).toString())
                    put("timestamp", message.timestampMillis)
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
            db.insert(
                "message_fts", null,
                ContentValues().apply {
                    put("message_id", message.id.toString())
                    put("conversation_id", conversation.id.toString())
                    put("role", message.role.rawValue)
                    put("content", searchableText(message))
                }
            )
        }
    }

    fun relevantMessages(
        conversationId: UUID,
        query: String,
        excluding: Set<UUID>,
        limit: Int = 6
    ): List<MemoryHit> {
        val ftsQuery = ftsQuery(query) ?: return emptyList()
        val hits = mutableListOf<MemoryHit>()
        try {
            readableDatabase.rawQuery(
                """
                SELECT m.id, m.role, m.content, m.timestamp
                FROM message_fts f
                JOIN messages m ON m.id = f.message_id
                WHERE f.conversation_id = ? AND message_fts MATCH ?
                ORDER BY m.timestamp DESC
                LIMIT ?
                """,
                arrayOf(conversationId.toString(), ftsQuery, (limit * 3).toString())
            ).use { cursor ->
                while (cursor.moveToNext() && hits.size < limit) {
                    val id = UUID.fromString(cursor.getString(0))
                    if (id in excluding) continue
                    hits.add(
                        MemoryHit(
                            messageId = id,
                            role = MessageRole.from(cursor.getString(1)),
                            content = cursor.getString(2),
                            timestampMillis = cursor.getLong(3)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return hits
    }

    private fun ftsQuery(text: String): String? {
        val stopWords = setOf(
            "what", "about", "with", "from", "that", "this", "have",
            "would", "could", "should", "please", "tell", "there", "where", "when"
        )
        val terms = text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in stopWords }
            .distinct()
            .take(8)
        if (terms.isEmpty()) return null
        return terms.joinToString(" OR ") { "$it*" }
    }

    private fun searchableText(message: ChatMessage): String {
        val parts = mutableListOf(message.content)
        message.attachments.mapNotNullTo(parts) { it.extractedText }
        return parts.joinToString("\n")
    }
}
