package network.retalert.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import network.retalert.domain.ChatMessage
import network.retalert.domain.ChatRepository
import java.util.concurrent.CopyOnWriteArrayList

/** One chat message row. */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alertId: String,
    val peer: String,
    val outgoing: Boolean,
    val text: String,
    val ts: Double,
    val state: String,
    val batch: Long,
)

@Dao
interface ChatDao {
    @Insert fun insert(e: ChatMessageEntity): Long

    @Query("UPDATE chat_messages SET state = :state WHERE id = :id")
    fun setState(id: Long, state: String): Int

    @Query("SELECT * FROM chat_messages WHERE alertId = :alertId ORDER BY ts, id")
    fun thread(alertId: String): List<ChatMessageEntity>

    @Query("SELECT alertId FROM chat_messages WHERE id = :id")
    fun alertIdOf(id: Long): String?

    @Query("DELETE FROM chat_messages WHERE alertId = :alertId")
    fun deleteThread(alertId: String): Int
}

/**
 * Per-alert chat lives in its own database file, so adding it never touches
 * the main RetAlert database (which still uses a destructive migration).
 */
@Database(entities = [ChatMessageEntity::class], version = 1, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}

class RoomChatRepository(private val dao: ChatDao) : ChatRepository {
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    private fun changed(alertId: String) = listeners.forEach { runCatching { it(alertId) } }

    override fun add(message: ChatMessage): Long {
        val id = dao.insert(
            ChatMessageEntity(
                alertId = message.alertId, peer = message.peer.lowercase().trim(), outgoing = message.outgoing,
                text = message.text, ts = message.ts, state = message.state, batch = message.batch,
            ),
        )
        changed(message.alertId)
        return id
    }

    override fun setState(id: Long, state: String) {
        dao.setState(id, state)
        dao.alertIdOf(id)?.let(::changed)
    }

    override fun thread(alertId: String): List<ChatMessage> =
        dao.thread(alertId).map { ChatMessage(it.id, it.alertId, it.peer, it.outgoing, it.text, it.ts, it.state, it.batch) }

    override fun deleteThread(alertId: String) {
        dao.deleteThread(alertId)
        changed(alertId)
    }

    override fun observe(listener: (alertId: String) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
}
