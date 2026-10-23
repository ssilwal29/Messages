package org.fossify.messages.gateway.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

/**
 * The gateway's own database. Kept apart from Fossify's `MessagesDatabase` so Fossify's schema migrations are
 * untouched and upstream changes merge cleanly.
 */
@Database(
    entities = [InboundEventEntity::class, InboundAttachmentEntity::class, OutboundJobEntity::class],
    version = 1,
)
abstract class GatewayDatabase : RoomDatabase() {
    abstract fun inbound(): InboundDao
    abstract fun outbound(): OutboundDao

    companion object {
        @Volatile
        private var instance: GatewayDatabase? = null

        fun get(context: Context): GatewayDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, GatewayDatabase::class.java, "gateway.db")
                .build()
                .also { instance = it }
        }
    }
}

@Entity(
    tableName = "inbound_events",
    indices = [Index(value = ["state", "received_at"])],
)
data class InboundEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "payload") val payload: String,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "attempts") val attempts: Int,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long,
    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "inbound_attachments",
    indices = [Index(value = ["event_id"])],
)
data class InboundAttachmentEntity(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "part_uri") val partUri: String,
    @ColumnInfo(name = "content_type") val contentType: String,
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "attempts") val attempts: Int,
    @ColumnInfo(name = "last_error") val lastError: String?,
)

@Entity(
    tableName = "outbound_jobs",
    indices = [Index(value = ["state"]), Index(value = ["confirm", "next_confirm_at"])],
)
data class OutboundJobEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "chat") val chat: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "recipients") val recipients: String,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "provider_uri") val providerUri: String?,
    @ColumnInfo(name = "parts_total") val partsTotal: Int,
    @ColumnInfo(name = "parts_ok") val partsOk: Int,
    @ColumnInfo(name = "parts_failed") val partsFailed: Int,
    @ColumnInfo(name = "sending_since") val sendingSince: Long,
    @ColumnInfo(name = "send_attempts") val sendAttempts: Int,
    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "permanent") val permanent: Boolean,
    @ColumnInfo(name = "confirm") val confirm: String,
    @ColumnInfo(name = "confirm_attempts") val confirmAttempts: Int,
    @ColumnInfo(name = "next_confirm_at") val nextConfirmAt: Long,
    @ColumnInfo(name = "delivery") val delivery: String,
    @ColumnInfo(name = "delivered") val delivered: Boolean,
    @ColumnInfo(name = "delivery_error") val deliveryError: String?,
    @ColumnInfo(name = "delivery_attempts") val deliveryAttempts: Int,
    @ColumnInfo(name = "next_delivery_at") val nextDeliveryAt: Long,
)

@Dao
@Suppress("TooManyFunctions")
interface InboundDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertEvent(event: InboundEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAttachments(attachments: List<InboundAttachmentEntity>)

    @Upsert
    fun upsertAttachment(attachment: InboundAttachmentEntity)

    @Query("SELECT COUNT(*) > 0 FROM inbound_events WHERE id = :id")
    fun exists(id: String): Boolean

    @Query(
        "SELECT * FROM inbound_events WHERE state = 'PENDING' ORDER BY received_at, source_type, source_id " +
            "LIMIT :limit"
    )
    fun pending(limit: Int): List<InboundEventEntity>

    @Query("SELECT * FROM inbound_attachments WHERE event_id = :eventId ORDER BY `key`")
    fun attachments(eventId: String): List<InboundAttachmentEntity>

    @Query("UPDATE inbound_events SET state = 'UPLOADED', last_error = NULL, updated_at = :now WHERE id IN (:ids)")
    fun markUploaded(ids: List<String>, now: Long)

    @Query("UPDATE inbound_events SET state = 'REJECTED', last_error = :error, updated_at = :now WHERE id = :id")
    fun markRejected(id: String, error: String, now: Long)

    @Query(
        "UPDATE inbound_events SET attempts = attempts + 1, next_attempt_at = :nextAttemptAt, last_error = :error, " +
            "updated_at = :now WHERE id IN (:ids)"
    )
    fun reschedule(ids: List<String>, nextAttemptAt: Long, error: String, now: Long)

    @Query("SELECT COUNT(*) FROM inbound_events WHERE state = 'PENDING'")
    fun pendingCount(): Int

    @Query("SELECT MIN(received_at) FROM inbound_events WHERE state = 'PENDING'")
    fun oldestPendingReceivedAt(): Long?

    @Query("SELECT COUNT(*) FROM inbound_events WHERE state = 'REJECTED'")
    fun rejectedCount(): Int

    @Query("UPDATE inbound_events SET state = 'PENDING', next_attempt_at = 0 WHERE state = 'REJECTED'")
    fun requeueRejected(): Int

    @Query(
        "DELETE FROM inbound_attachments WHERE event_id IN " +
            "(SELECT id FROM inbound_events WHERE state = 'UPLOADED' AND updated_at < :before)"
    )
    fun pruneAttachments(before: Long)

    @Query("DELETE FROM inbound_events WHERE state = 'UPLOADED' AND updated_at < :before")
    fun pruneEvents(before: Long)
}

@Dao
interface OutboundDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(job: OutboundJobEntity): Long

    @Upsert
    fun upsert(job: OutboundJobEntity)

    @Query("SELECT * FROM outbound_jobs WHERE id = :id")
    fun get(id: String): OutboundJobEntity?

    @Query(
        "SELECT * FROM outbound_jobs WHERE confirm = 'PENDING' AND next_confirm_at <= :now " +
            "ORDER BY created_at LIMIT :limit"
    )
    fun dueConfirmations(now: Long, limit: Int): List<OutboundJobEntity>

    @Query(
        "SELECT * FROM outbound_jobs WHERE delivery = 'PENDING' AND confirm = 'DONE' AND next_delivery_at <= :now " +
            "ORDER BY created_at LIMIT :limit"
    )
    fun dueDeliveryReports(now: Long, limit: Int): List<OutboundJobEntity>

    @Query("SELECT * FROM outbound_jobs WHERE state = 'SENDING' AND sending_since <= :before")
    fun sendingSince(before: Long): List<OutboundJobEntity>

    @Query(
        "SELECT COUNT(*) FROM outbound_jobs WHERE state IN ('RECEIVED', 'CHECKED', 'SENDING') OR confirm = 'PENDING'"
    )
    fun activeCount(): Int

    @Query("UPDATE outbound_jobs SET confirm = 'PENDING', next_confirm_at = 0 WHERE confirm = 'REJECTED'")
    fun requeueRejected(): Int

    @Query(
        "DELETE FROM outbound_jobs WHERE updated_at < :before AND state IN ('SENT', 'FAILED', 'DELIVERED') " +
            "AND confirm IN ('DONE', 'REJECTED') AND delivery != 'PENDING'"
    )
    fun prune(before: Long)
}
