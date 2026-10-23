package org.fossify.messages.gateway.core

import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

enum class InboundState { PENDING, UPLOADED, REJECTED }

enum class AttachmentState { PENDING, UPLOADED, SKIPPED }

/** One received message waiting to be reported, with the [InboundEvent] it becomes stored as JSON. */
data class InboundRecord(
    val id: String,
    val payload: String,
    val sourceType: String,
    val sourceId: Long,
    val threadId: Long,
    val receivedAt: Long,
    val createdAt: Long,
    val state: InboundState = InboundState.PENDING,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val lastError: String? = null,
)

/** One MMS part to upload before its event. */
data class AttachmentRecord(
    val key: String,
    val eventId: String,
    val partUri: String,
    val contentType: String,
    val name: String?,
    val state: AttachmentState = AttachmentState.PENDING,
    val attempts: Int = 0,
    val lastError: String? = null,
)

data class InboundStats(val pending: Int, val oldestPendingReceivedAt: Long?)

@Suppress("TooManyFunctions")
interface InboundStore {
    /** Inserts the event and its attachments unless an event with that id exists. Returns false if it existed. */
    fun enqueue(event: InboundRecord, attachments: List<AttachmentRecord>): Boolean

    fun contains(id: String): Boolean

    /** Whether an event was already built from this provider row, under any id. */
    fun containsSource(sourceType: String, sourceId: Long): Boolean

    /** Pending events, oldest first. */
    fun pending(limit: Int): List<InboundRecord>
    fun attachments(eventId: String): List<AttachmentRecord>
    fun updateAttachment(record: AttachmentRecord)
    fun markUploaded(ids: List<String>)
    fun markRejected(id: String, error: String)
    /** Counts one more attempt for each event and holds it until [nextAttemptAt]. */
    fun reschedule(ids: List<String>, nextAttemptAt: Long, error: String)
    fun stats(): InboundStats

    /** Puts events the server rejected with 400 back in the queue (operator's "Sync now"). */
    fun requeueRejected(): Int
}

/** Reads an MMS part's bytes from the provider. */
fun interface AttachmentReader {
    sealed interface Result {
        class Data(val bytes: ByteArray) : Result
        data object Missing : Result
        data object TooLarge : Result
    }

    fun read(record: AttachmentRecord): Result
}

/**
 * Uploads queued events oldest first (contract 2.1, 2.6, 2.9): each event's attachments, then the event, in
 * batches of at most 100 events and 1 MB.
 *
 * Order is kept: an event waiting for a retry holds back the ones after it. The one exception is an event with
 * no text whose attachments can't be uploaded because the server doesn't have the route yet: it stays queued
 * until uploads work, without holding back the rest.
 */
@Suppress("TooManyFunctions")
class InboundUploader(
    private val store: InboundStore,
    private val api: GatewayApi,
    private val reader: AttachmentReader,
    private val features: FeatureGate,
    private val status: StatusSink,
    private val clock: Clock = Clock.SYSTEM,
    private val backoff: Backoff = Backoff.retries(),
    private val onUploaded: (List<InboundRecord>) -> Unit = {},
) {
    enum class Result { IDLE, WAITING, STOPPED }

    private sealed interface Prepared {
        class Ready(val record: InboundRecord, val event: InboundEvent, val unsent: List<AttachmentRecord>) : Prepared
        data object Parked : Prepared
        class RetryLater(val error: String) : Prepared
        data object Stop : Prepared
    }

    private enum class Posted { DONE, RETRY, STOP }

    /** The next batch, oldest first; [waiting] when an event waiting for a retry cut it short. */
    private class Batch(val items: List<Prepared.Ready>, val waiting: Boolean = false, val stop: Boolean = false)

    fun run(): Result {
        while (true) {
            if (api.isStopped) return Result.STOPPED
            val batch = collectBatch(clock.now())
            val result = when {
                batch.stop -> Result.STOPPED
                batch.items.isEmpty() -> if (batch.waiting) Result.WAITING else Result.IDLE
                else -> when (post(batch.items)) {
                    Posted.DONE -> null
                    Posted.RETRY -> Result.WAITING
                    Posted.STOP -> Result.STOPPED
                }
            }
            if (result != null) return result
        }
    }

    @Suppress("ReturnCount")
    private fun collectBatch(now: Long): Batch {
        val items = mutableListOf<Prepared.Ready>()
        var bytes = ENVELOPE_BYTES
        for (record in store.pending(SCAN_LIMIT)) {
            if (record.nextAttemptAt > now) return Batch(items, waiting = true)
            when (val prepared = prepare(record)) {
                is Prepared.Ready -> {
                    val size = GatewayJson.encodeToString(InboundEvent.serializer(), prepared.event)
                        .toByteArray().size + 1
                    if (items.size >= MAX_EVENTS || items.isNotEmpty() && bytes + size > MAX_BYTES) return Batch(items)
                    items += prepared
                    bytes += size
                }

                Prepared.Parked -> Unit
                is Prepared.RetryLater -> {
                    retryLater(listOf(record), prepared.error)
                    return Batch(items, waiting = true)
                }

                Prepared.Stop -> return Batch(items, stop = true)
            }
        }
        return Batch(items)
    }

    private fun prepare(record: InboundRecord): Prepared {
        val event = try {
            GatewayJson.decodeFromString(InboundEvent.serializer(), record.payload)
        } catch (e: SerializationException) {
            store.markRejected(record.id, "unreadable queued event: ${e.message}")
            return Prepared.Parked
        }

        uploadPendingAttachments(record.id)?.let { return it }

        val current = store.attachments(record.id)
        val unsent = current.filter { it.state == AttachmentState.PENDING }
        if (unsent.isNotEmpty() && event.body.isEmpty()) {
            // Uploads are unavailable and there's no text to report on its own: keep it until uploads work.
            return Prepared.Parked
        }

        val refs = current.filter { it.state == AttachmentState.UPLOADED }
            .map { AttachmentRef(it.key, it.contentType, it.name) }
        return Prepared.Ready(record, event.copy(attachments = refs.ifEmpty { null }), unsent)
    }

    /** Uploads the event's attachments that aren't up yet. Null unless the event has to wait or traffic stops. */
    private fun uploadPendingAttachments(eventId: String): Prepared? {
        for (attachment in store.attachments(eventId).filter { it.state == AttachmentState.PENDING }) {
            // Also stops after a 404 turned attachments off part-way through.
            if (!features.shouldTry(Feature.ATTACHMENTS)) return null
            when (val outcome = upload(attachment)) {
                is Prepared.RetryLater -> return outcome
                Prepared.Stop -> return Prepared.Stop
                else -> Unit
            }
        }
        return null
    }

    private fun upload(attachment: AttachmentRecord): Prepared? {
        val data = when (val read = reader.read(attachment)) {
            is AttachmentReader.Result.Data -> read.bytes
            AttachmentReader.Result.Missing -> return skip(attachment, "part no longer in the provider")
            AttachmentReader.Result.TooLarge -> return skip(attachment, "part is larger than 10 MB")
        }
        val body = data.toRequestBody(attachment.contentType.toMediaTypeOrNull())
        return when (val result = api.putAttachment(attachment.key, body)) {
            is ApiResult.Ok -> {
                features.supported(Feature.ATTACHMENTS)
                store.updateAttachment(attachment.copy(state = AttachmentState.UPLOADED, lastError = null))
                null
            }

            ApiResult.NotFound -> {
                features.unsupported(Feature.ATTACHMENTS)
                null
            }

            ApiResult.Unauthorized, ApiResult.NotConfigured -> Prepared.Stop
            is ApiResult.BadRequest -> skip(attachment, "rejected: ${result.message}")
            is ApiResult.HttpError -> if (result.retryable) {
                store.updateAttachment(attachment.copy(attempts = attachment.attempts + 1, lastError = result.message))
                Prepared.RetryLater("attachment upload: ${result.message}")
            } else {
                skip(attachment, "rejected: ${result.message}")
            }

            is ApiResult.NetworkError -> {
                store.updateAttachment(attachment.copy(attempts = attachment.attempts + 1))
                Prepared.RetryLater("attachment upload: ${result.error.javaClass.simpleName}")
            }
        }
    }

    private fun skip(attachment: AttachmentRecord, reason: String): Prepared? {
        store.updateAttachment(attachment.copy(state = AttachmentState.SKIPPED, lastError = reason))
        status.recordError("attachment ${attachment.key}: $reason")
        return null
    }

    private fun post(batch: List<Prepared.Ready>): Posted {
        return when (val result = api.postEvents(batch.map { it.event })) {
            is ApiResult.Ok -> {
                uploaded(batch)
                Posted.DONE
            }

            is ApiResult.BadRequest -> if (batch.size > 1) postOneByOne(batch) else {
                rejected(batch.single(), result.message)
                Posted.DONE
            }

            ApiResult.Unauthorized, ApiResult.NotConfigured -> Posted.STOP
            ApiResult.NotFound -> {
                retryLater(batch.map { it.record }, "events route answered 404")
                Posted.RETRY
            }

            is ApiResult.HttpError -> {
                retryLater(batch.map { it.record }, result.message)
                Posted.RETRY
            }

            is ApiResult.NetworkError -> {
                retryLater(batch.map { it.record }, "network: ${result.error.javaClass.simpleName}")
                Posted.RETRY
            }
        }
    }

    /** A 400 on a batch: send each event alone once, so only the event the server rejects is set aside. */
    private fun postOneByOne(batch: List<Prepared.Ready>): Posted {
        for ((index, item) in batch.withIndex()) {
            when (val result = api.postEvents(listOf(item.event))) {
                is ApiResult.Ok -> uploaded(listOf(item))
                is ApiResult.BadRequest -> rejected(item, result.message)
                ApiResult.Unauthorized, ApiResult.NotConfigured -> return Posted.STOP
                else -> {
                    retryLater(batch.drop(index).map { it.record }, "upload failed after a 400 on its batch")
                    return Posted.RETRY
                }
            }
        }
        return Posted.DONE
    }

    private fun uploaded(items: List<Prepared.Ready>) {
        store.markUploaded(items.map { it.record.id })
        items.flatMap { it.unsent }.forEach {
            store.updateAttachment(it.copy(state = AttachmentState.SKIPPED, lastError = "reported without it"))
        }
        status.onUploaded(clock.now())
        onUploaded(items.map { it.record })
    }

    private fun rejected(item: Prepared.Ready, message: String) {
        store.markRejected(item.record.id, message)
        status.recordError("event ${item.record.id} rejected: $message")
    }

    private fun retryLater(records: List<InboundRecord>, error: String) {
        val attempts = records.maxOf { it.attempts }
        store.reschedule(records.map { it.id }, clock.now() + backoff.delayFor(attempts), error)
        status.recordError("upload: $error")
    }

    companion object {
        const val MAX_EVENTS = 100
        const val MAX_BYTES = 1_000_000
        private const val ENVELOPE_BYTES = 16
        private const val SCAN_LIMIT = 500

        /** Longer text is ignored by the server anyway; capping keeps one event far below the 1 MB batch limit. */
        const val MAX_BODY_CHARS = 8_000
    }
}
