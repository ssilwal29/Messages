package org.fossify.messages.gateway.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.fossify.messages.gateway.core.AttachmentRecord
import org.fossify.messages.gateway.core.AttachmentState
import org.fossify.messages.gateway.core.Clock
import org.fossify.messages.gateway.core.ConfirmState
import org.fossify.messages.gateway.core.DeliveryState
import org.fossify.messages.gateway.core.GatewayJson
import org.fossify.messages.gateway.core.InboundRecord
import org.fossify.messages.gateway.core.InboundState
import org.fossify.messages.gateway.core.InboundStats
import org.fossify.messages.gateway.core.InboundStore
import org.fossify.messages.gateway.core.OutboundJob
import org.fossify.messages.gateway.core.OutboundState
import org.fossify.messages.gateway.core.OutboundStore

/** Room-backed [InboundStore]. */
@Suppress("TooManyFunctions")
class RoomInboundStore(private val db: GatewayDatabase, private val clock: Clock = Clock.SYSTEM) : InboundStore {
    private val dao = db.inbound()

    override fun enqueue(event: InboundRecord, attachments: List<AttachmentRecord>): Boolean =
        db.runInTransaction<Boolean> {
            if (dao.insertEvent(event.toEntity(clock.now())) == -1L) return@runInTransaction false
            dao.insertAttachments(attachments.map { it.toEntity() })
            true
        }

    override fun contains(id: String) = dao.exists(id)

    override fun containsSource(sourceType: String, sourceId: Long) = dao.existsSource(sourceType, sourceId)

    override fun pending(limit: Int) = dao.pending(limit).map { it.toRecord() }

    override fun attachments(eventId: String) = dao.attachments(eventId).map { it.toRecord() }

    override fun updateAttachment(record: AttachmentRecord) = dao.upsertAttachment(record.toEntity())

    override fun markUploaded(ids: List<String>) = ids.chunked(MAX_SQL_ARGS).forEach {
        dao.markUploaded(it, clock.now())
    }

    override fun markRejected(id: String, error: String) = dao.markRejected(id, error, clock.now())

    override fun reschedule(ids: List<String>, nextAttemptAt: Long, error: String) =
        ids.chunked(MAX_SQL_ARGS).forEach { dao.reschedule(it, nextAttemptAt, error, clock.now()) }

    override fun stats() = InboundStats(dao.pendingCount(), dao.oldestPendingReceivedAt())

    override fun requeueRejected() = dao.requeueRejected()

    fun rejectedCount() = dao.rejectedCount()

    fun prune(before: Long) = db.runInTransaction {
        dao.pruneAttachments(before)
        dao.pruneEvents(before)
    }

    private fun InboundRecord.toEntity(now: Long) = InboundEventEntity(
        id = id,
        payload = payload,
        sourceType = sourceType,
        sourceId = sourceId,
        threadId = threadId,
        receivedAt = receivedAt,
        createdAt = createdAt,
        state = state.name,
        attempts = attempts,
        nextAttemptAt = nextAttemptAt,
        lastError = lastError,
        updatedAt = now,
    )

    private fun InboundEventEntity.toRecord() = InboundRecord(
        id = id,
        payload = payload,
        sourceType = sourceType,
        sourceId = sourceId,
        threadId = threadId,
        receivedAt = receivedAt,
        createdAt = createdAt,
        state = InboundState.valueOf(state),
        attempts = attempts,
        nextAttemptAt = nextAttemptAt,
        lastError = lastError,
    )

    private fun AttachmentRecord.toEntity() = InboundAttachmentEntity(
        key = key,
        eventId = eventId,
        partUri = partUri,
        contentType = contentType,
        name = name,
        state = state.name,
        attempts = attempts,
        lastError = lastError,
    )

    private fun InboundAttachmentEntity.toRecord() = AttachmentRecord(
        key = key,
        eventId = eventId,
        partUri = partUri,
        contentType = contentType,
        name = name,
        state = AttachmentState.valueOf(state),
        attempts = attempts,
        lastError = lastError,
    )

    companion object {
        private const val MAX_SQL_ARGS = 500
    }
}

/** Room-backed [OutboundStore]. Every state change goes through [update], inside one SQLite transaction. */
@Suppress("TooManyFunctions")
class RoomOutboundStore(private val db: GatewayDatabase) : OutboundStore {
    private val dao = db.outbound()
    private val recipientsSerializer = ListSerializer(String.serializer())

    override fun get(id: String) = dao.get(id)?.toJob()

    override fun insert(job: OutboundJob) = dao.insert(job.toEntity()) != -1L

    override fun update(id: String, transform: (OutboundJob) -> OutboundJob?): OutboundJob? =
        db.runInTransaction<OutboundJob?> {
            val current = dao.get(id)?.toJob() ?: return@runInTransaction null
            val next = transform(current) ?: return@runInTransaction null
            dao.upsert(next.toEntity())
            next
        }

    override fun dueConfirmations(now: Long, limit: Int) = dao.dueConfirmations(now, limit).map { it.toJob() }

    override fun dueDeliveryReports(now: Long, limit: Int) = dao.dueDeliveryReports(now, limit).map { it.toJob() }

    override fun sendingSince(before: Long) = dao.sendingSince(before).map { it.toJob() }

    override fun activeCount() = dao.activeCount()

    override fun requeueRejectedConfirmations() = dao.requeueRejected()

    fun prune(before: Long) = dao.prune(before)

    private fun OutboundJob.toEntity() = OutboundJobEntity(
        id = id,
        chat = chat,
        type = type,
        recipients = GatewayJson.encodeToString(recipientsSerializer, recipients),
        body = body,
        createdAt = createdAt,
        updatedAt = updatedAt,
        state = state.name,
        providerUri = providerUri,
        partsTotal = partsTotal,
        partsOk = partsOk,
        partsFailed = partsFailed,
        sendingSince = sendingSince,
        sendAttempts = sendAttempts,
        lastError = lastError,
        permanent = permanent,
        confirm = confirm.name,
        confirmAttempts = confirmAttempts,
        nextConfirmAt = nextConfirmAt,
        delivery = delivery.name,
        delivered = delivered,
        deliveryError = deliveryError,
        deliveryAttempts = deliveryAttempts,
        nextDeliveryAt = nextDeliveryAt,
    )

    private fun OutboundJobEntity.toJob() = OutboundJob(
        id = id,
        chat = chat,
        type = type,
        recipients = GatewayJson.decodeFromString(recipientsSerializer, recipients),
        body = body,
        createdAt = createdAt,
        updatedAt = updatedAt,
        state = OutboundState.valueOf(state),
        providerUri = providerUri,
        partsTotal = partsTotal,
        partsOk = partsOk,
        partsFailed = partsFailed,
        sendingSince = sendingSince,
        sendAttempts = sendAttempts,
        lastError = lastError,
        permanent = permanent,
        confirm = ConfirmState.valueOf(confirm),
        confirmAttempts = confirmAttempts,
        nextConfirmAt = nextConfirmAt,
        delivery = DeliveryState.valueOf(delivery),
        delivered = delivered,
        deliveryError = deliveryError,
        deliveryAttempts = deliveryAttempts,
        nextDeliveryAt = nextDeliveryAt,
    )
}
