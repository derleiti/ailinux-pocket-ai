package me.ailinux.pocket

import android.content.Context
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Transaction
import androidx.room3.Upsert
import java.util.UUID

@Entity(tableName = "pocket_conversations",
    indices = [Index("updatedAt"), Index("pinned")])
data class PocketConversation(
    @PrimaryKey val id: String,
    val title: String = "Neuer Chat",
    val modelName: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val userNamed: Boolean = false
)

@Entity(tableName = "pocket_chat_messages",
    primaryKeys = ["conversationId", "sequence"],
    foreignKeys = [ForeignKey(
        entity = PocketConversation::class,
        parentColumns = ["id"], childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId")])
data class PocketChatLine(
    val conversationId: String, val sequence: Int,
    val isUser: Boolean, val content: String,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Tombstones prevent an older queued streaming snapshot from resurrecting a
 * conversation after a user explicitly confirmed its deletion.
 */
@Entity(tableName = "pocket_deleted_conversations")
data class PocketDeletedConversation(
    @PrimaryKey val id: String,
    val deletedAt: Long = System.currentTimeMillis()
)

@Dao
interface PocketChatDao {
    @Query("SELECT * FROM pocket_conversations WHERE id = :id LIMIT 1")
    suspend fun getConversation(id: String): PocketConversation?

    @Query("SELECT * FROM pocket_conversations WHERE archived = 0 " +
        "ORDER BY pinned DESC, updatedAt DESC LIMIT 200")
    suspend fun recent(): List<PocketConversation>

    @Query("SELECT * FROM pocket_conversations WHERE archived = 1 " +
        "ORDER BY updatedAt DESC LIMIT 200")
    suspend fun archived(): List<PocketConversation>

    @Query("SELECT * FROM pocket_conversations WHERE archived = 0 " +
        "AND (title LIKE '%' || :term || '%' OR EXISTS (" +
        "SELECT 1 FROM pocket_chat_messages m WHERE m.conversationId = pocket_conversations.id " +
        "AND m.content LIKE '%' || :term || '%')) " +
        "ORDER BY pinned DESC, updatedAt DESC LIMIT 200")
    suspend fun search(term: String): List<PocketConversation>

    @Query("SELECT * FROM pocket_chat_messages WHERE conversationId = :id " +
        "ORDER BY sequence ASC")
    suspend fun lines(id: String): List<PocketChatLine>

    @Upsert
    suspend fun upsertConversation(value: PocketConversation)

    @Upsert
    suspend fun upsertLines(items: List<PocketChatLine>)

    @Query("DELETE FROM pocket_chat_messages WHERE conversationId = :id AND sequence >= :count")
    suspend fun trimLines(id: String, count: Int)

    @Query("UPDATE pocket_conversations SET title = :title, userNamed = 1 WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("UPDATE pocket_conversations SET archived = :value WHERE id = :id")
    suspend fun archive(id: String, value: Boolean)

    @Query("UPDATE pocket_conversations SET pinned = :value WHERE id = :id")
    suspend fun pin(id: String, value: Boolean)

    @Query("DELETE FROM pocket_conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT EXISTS(SELECT 1 FROM pocket_deleted_conversations WHERE id = :id)")
    suspend fun isDeleted(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun tombstone(value: PocketDeletedConversation)

    @Transaction
    suspend fun permanentlyDelete(id: String) {
        tombstone(PocketDeletedConversation(id))
        delete(id)
    }

    /** All messages and metadata change in one SQL transaction. */
    @Transaction
    suspend fun saveSnapshot(id: String, model: String, messages: List<ChatMessage>) {
        if (isDeleted(id)) return
        val old = getConversation(id)
        val now = System.currentTimeMillis()
        val title = old?.title?.takeIf { old.userNamed || it != "Neuer Chat" }
            ?: messages.firstOrNull { it.isUser }?.content
                ?.replace(Regex("\\s+"), " ")?.trim()?.take(65)
                ?.takeIf { it.isNotBlank() }
            ?: "Neuer Chat"
        val meta = (old ?: PocketConversation(id = id)).copy(
            title = title, modelName = model.ifBlank { old?.modelName.orEmpty() },
            updatedAt = now
        )
        upsertConversation(meta)
        upsertLines(messages.mapIndexed { index, item ->
            PocketChatLine(id, index, item.isUser, item.content)
        })
        trimLines(id, messages.size)
    }
}

@Database(entities = [PocketConversation::class, PocketChatLine::class,
        PocketDeletedConversation::class],
    version = 1, exportSchema = true)
abstract class PocketChatHistoryDatabase : RoomDatabase() {
    abstract fun dao(): PocketChatDao
}

internal object PocketChatHistory {
    @Volatile private var instance: PocketChatHistoryDatabase? = null
    fun dao(context: Context): PocketChatDao {
        val db = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder<PocketChatHistoryDatabase>(
                context.applicationContext, "pocket_chats.db"
            ).build().also { instance = it }
        }
        return db.dao()
    }

    fun newId(): String = UUID.randomUUID().toString()
}
