package me.ailinux.pocket

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/**
 * Private, offline-only conversation database. This intentionally has an explicit
 * schema version and never drops user tables during upgrades. No MCP credentials
 * or server tokens belong in this database.
 *
 * Queries must be called from Dispatchers.IO; the UI does not own persistence.
 */
internal class ChatHistoryRepository(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "pocket_conversations.db", null, 1) {

    data class Conversation(
        val id: String,
        val title: String,
        val model: String,
        val createdAt: Long,
        val updatedAt: Long,
        val pinned: Boolean,
        val archived: Boolean
    )
    data class Message(val index: Int, val isUser: Boolean, val content: String)
    data class Snapshot(val conversationId: String, val model: String, val messages: List<Message>)

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE conversations(
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                model TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                pinned INTEGER NOT NULL DEFAULT 0,
                archived INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE chat_messages(
                conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                position INTEGER NOT NULL,
                is_user INTEGER NOT NULL,
                body TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (conversation_id, position)
            )
        """.trimIndent())
        // A confirmed deletion must stay deleted even if a delayed streaming
        // snapshot from the former Activity arrives out of order.
        db.execSQL("CREATE TABLE deleted_conversations(id TEXT PRIMARY KEY NOT NULL)")
        db.execSQL("CREATE INDEX conversation_recent ON conversations(archived, pinned, updated_at DESC)")
        db.execSQL("CREATE INDEX message_position ON chat_messages(conversation_id, position)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Never silently delete a local conversation during app updates.
        throw IllegalStateException("Keine Chat-Datenmigration definiert: $oldVersion -> $newVersion")
    }

    fun create(model: String = "", title: String = "Neuer Chat"): Conversation {
        val now = System.currentTimeMillis()
        val conversation = Conversation(UUID.randomUUID().toString(), title, model, now, now, false, false)
        writableDatabase.insertOrThrow("conversations", null, ContentValues().apply {
            put("id", conversation.id); put("title", title); put("model", model)
            put("created_at", now); put("updated_at", now)
        })
        return conversation
    }

    fun ensure(id: String, model: String = ""): Conversation {
        require(!isDeleted(id)) { "Chat wurde gelöscht" }
        get(id)?.let { return it }
        val now = System.currentTimeMillis()
        writableDatabase.insertWithOnConflict("conversations", null, ContentValues().apply {
            put("id", id); put("title", "Neuer Chat"); put("model", model)
            put("created_at", now); put("updated_at", now)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return checkNotNull(get(id))
    }

    fun get(id: String): Conversation? =
        readableDatabase.query("conversations", null, "id = ?", arrayOf(id),
            null, null, null, "1").use { c ->
            if (c.moveToFirst()) read(c) else null
        }

    fun list(search: String = "", includeArchived: Boolean = false, limit: Int = 100, offset: Int = 0): List<Conversation> {
        require(limit in 1..250 && offset >= 0)
        val trimmed = search.trim().take(100)
        val term = trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val where = buildString {
            append(if (includeArchived) "1=1" else "archived = 0")
            if (term.isNotBlank()) append(" AND (title LIKE ? ESCAPE '\\' OR model LIKE ? ESCAPE '\\')")
        }
        val args = if (term.isEmpty()) null else arrayOf("%$term%", "%$term%")
        return readableDatabase.query("conversations", null, where, args, null, null,
            "pinned DESC, updated_at DESC", "$offset, $limit").use { c ->
            buildList { while (c.moveToNext()) add(read(c)) }
        }
    }

    fun messages(id: String, limit: Int = 1000): List<Message> {
        require(limit in 1..5000)
        return readableDatabase.rawQuery(
            """SELECT position, is_user, body FROM chat_messages WHERE conversation_id = ?
               AND position >= (SELECT COALESCE(MAX(position) - ?, 0)
               FROM chat_messages WHERE conversation_id = ?) ORDER BY position ASC""",
            arrayOf(id, (limit - 1).toString(), id)
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Message(c.getInt(0), c.getInt(1) == 1, c.getString(2)))
            }
        }
    }

    @Synchronized fun persist(snapshot: Snapshot) {
        if (isDeleted(snapshot.conversationId)) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = ensure(snapshot.conversationId, snapshot.model)
            val now = System.currentTimeMillis()
            // Only changed entries are written, but upsert is safe across process death.
            val statement = db.compileStatement("""
                INSERT INTO chat_messages(conversation_id, position, is_user, body, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(conversation_id, position) DO UPDATE SET
                  is_user=excluded.is_user, body=excluded.body, updated_at=excluded.updated_at
                WHERE chat_messages.body != excluded.body OR chat_messages.is_user != excluded.is_user
            """.trimIndent())
            try {
                snapshot.messages.forEach { message ->
                    require(message.index >= 0 && message.content.length <= 500_000)
                    statement.clearBindings()
                    statement.bindString(1, snapshot.conversationId)
                    statement.bindLong(2, message.index.toLong())
                    statement.bindLong(3, if (message.isUser) 1 else 0)
                    statement.bindString(4, message.content)
                    statement.bindLong(5, now)
                    statement.executeInsert()
                }
            } finally { statement.close() }
            val firstUser = snapshot.messages.firstOrNull { it.isUser && it.content.isNotBlank() }
            val title = if (existing.title == "Neuer Chat" && firstUser != null)
                ChatHistoryText.title(firstUser.content) else existing.title
            db.update("conversations", ContentValues().apply {
                put("title", title)
                put("model", existing.model.ifBlank { snapshot.model })
                put("updated_at", now)
            }, "id = ?", arrayOf(snapshot.conversationId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun rename(id: String, title: String) {
        val cleaned = ChatHistoryText.title(title)
        writableDatabase.update("conversations", ContentValues().apply {
            put("title", cleaned)
        }, "id = ?", arrayOf(id))
    }

    fun setPinned(id: String, pinned: Boolean) = flag(id, "pinned", pinned)
    fun setArchived(id: String, archived: Boolean) = flag(id, "archived", archived)

    private fun flag(id: String, column: String, enabled: Boolean) {
        check(column == "pinned" || column == "archived")
        writableDatabase.update("conversations", ContentValues().apply {
            put(column, if (enabled) 1 else 0)
        }, "id = ?", arrayOf(id))
    }

    @Synchronized fun delete(id: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            db.insertWithOnConflict("deleted_conversations", null, ContentValues().apply {
                put("id", id)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            val deleted = db.delete("conversations", "id = ?", arrayOf(id)) == 1
            db.setTransactionSuccessful()
            deleted
        } finally { db.endTransaction() }
    }

    private fun isDeleted(id: String): Boolean = readableDatabase.query(
        "deleted_conversations", arrayOf("id"), "id = ?", arrayOf(id),
        null, null, null, "1"
    ).use { it.moveToFirst() }

    private fun read(c: android.database.Cursor) = Conversation(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        model = c.getString(c.getColumnIndexOrThrow("model")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
        pinned = c.getInt(c.getColumnIndexOrThrow("pinned")) == 1,
        archived = c.getInt(c.getColumnIndexOrThrow("archived")) == 1
    )
}

/** Separate from Android SQLite, so title and search safety can be unit-tested. */
internal object ChatHistoryText {
    fun title(input: String): String =
        input.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().take(80).ifEmpty { "Neuer Chat" }
}
