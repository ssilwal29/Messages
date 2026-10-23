package org.fossify.messages.gateway

import android.content.Context
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.getNameAndPhotoFromPhoneNumber
import org.fossify.messages.gateway.core.AttachmentRecord
import org.fossify.messages.gateway.core.ChatRef
import org.fossify.messages.gateway.core.Clock
import org.fossify.messages.gateway.core.EventIds
import org.fossify.messages.gateway.core.GatewayJson
import org.fossify.messages.gateway.core.HandleNormalizer
import org.fossify.messages.gateway.core.InboundEvent
import org.fossify.messages.gateway.core.InboundRecord
import org.fossify.messages.gateway.core.InboundStore
import org.fossify.messages.gateway.core.InboundUploader
import org.fossify.messages.gateway.core.MmsAddresses
import org.fossify.messages.gateway.core.OwnNumbers
import org.fossify.messages.gateway.core.Participant
import org.fossify.messages.gateway.core.StatusSink
import org.fossify.messages.gateway.core.ThreadInfo
import org.fossify.messages.gateway.core.ThreadsSnapshot
import org.fossify.messages.gateway.core.TYPE_MMS
import org.fossify.messages.gateway.core.TYPE_SMS
import org.fossify.messages.gateway.core.rfc3339

typealias Handles = Pair<HandleNormalizer, OwnNumbers>

/** Builds [InboundEvent]s (contract 2.1) and thread lists (2.8) from what the provider stored. */
class InboundEventFactory(
    private val context: Context,
    private val reader: ProviderReader,
    private val handles: () -> Handles,
    private val status: StatusSink,
    private val clock: Clock = Clock.SYSTEM,
) {
    class Built(val record: InboundRecord, val attachments: List<AttachmentRecord>, val isGroup: Boolean)

    /** Null for messages this phone sent to itself, or rows without a usable sender. */
    fun fromSms(row: ProviderReader.SmsRow): Built? {
        val (normalizer, own) = handles()
        val from = normalizer.normalize(row.address)
        if (from.isEmpty() || from in own) return null
        val fromName = contactName(from)
        val id = EventIds.sms(row.id, row.date)
        val event = InboundEvent(
            id = id,
            chat = ChatRef(row.threadId.toString(), chatName(row.threadId), listOf(Participant(from, fromName))),
            from = from,
            fromName = fromName,
            body = row.body.take(InboundUploader.MAX_BODY_CHARS),
            type = TYPE_SMS,
            receivedAt = rfc3339(row.date),
        )
        return Built(record(event, TYPE_SMS, row.id, row.threadId, row.date), emptyList(), isGroup = false)
    }

    fun fromMms(row: ProviderReader.MmsRow): Built? {
        val (normalizer, own) = handles()
        val parties = MmsAddresses.parse(reader.mmsAddresses(row.id), normalizer, own)
        val from = parties.from?.takeIf { it.isNotEmpty() }
        if (from == null) {
            status.recordError("MMS ${row.id} in thread ${row.threadId} has no usable sender; not reported")
            return null
        }
        if (from in own) return null

        val id = EventIds.mms(row.id, row.dateMillis)
        val parts = reader.mmsParts(row.id)
        val text = parts.filter { it.contentType.equals(TEXT_PLAIN, ignoreCase = true) }
            .mapNotNull { it.text?.takeIf(String::isNotEmpty) }
            .joinToString("\n")
        val attachments = parts
            .filterNot { it.contentType.equals(TEXT_PLAIN, true) || it.contentType.equals(SMIL, true) }
            .map {
                AttachmentRecord(
                    key = EventIds.attachmentKey(id, it.id),
                    eventId = id,
                    partUri = reader.partUri(it.id).toString(),
                    contentType = it.contentType.ifBlank { OCTET_STREAM },
                    name = it.name,
                )
            }
        val participants = parties.participants.ifEmpty { listOf(from) }.take(MAX_PARTICIPANTS)
        val event = InboundEvent(
            id = id,
            chat = ChatRef(row.threadId.toString(), chatName(row.threadId), participants.map(::participant)),
            from = from,
            fromName = contactName(from),
            body = text.take(InboundUploader.MAX_BODY_CHARS),
            type = TYPE_MMS,
            receivedAt = rfc3339(row.dateMillis),
        )
        val record = record(event, TYPE_MMS, row.id, row.threadId, row.dateMillis)
        return Built(record, attachments, isGroup = participants.size >= 2)
    }

    /** This phone's threads with two or more participants: the [limit] most recent. */
    fun threadsSnapshot(limit: Int = MAX_THREADS): ThreadsSnapshot {
        val (normalizer, own) = handles()
        val threads = reader.recentThreads(Int.MAX_VALUE).asSequence()
            .map { row -> row to normalizer.normalizeAll(row.addresses).filterNot { it in own } }
            .filter { (_, participants) -> participants.size >= 2 }
            .take(limit)
            .map { (row, participants) ->
                ThreadInfo(
                    id = row.id.toString(),
                    name = chatName(row.id),
                    participants = participants.take(MAX_PARTICIPANTS).map(::participant),
                    lastMessageAt = rfc3339(row.dateMillis),
                )
            }
            .toList()
        return ThreadsSnapshot(threads)
    }

    private fun record(event: InboundEvent, type: String, sourceId: Long, threadId: Long, receivedAt: Long) =
        InboundRecord(
            id = event.id,
            payload = GatewayJson.encodeToString(InboundEvent.serializer(), event),
            sourceType = type,
            sourceId = sourceId,
            threadId = threadId,
            receivedAt = receivedAt,
            createdAt = clock.now(),
        )

    private fun participant(handle: String) = Participant(handle, contactName(handle))

    private fun contactName(handle: String): String? {
        if (handle.contains('@')) return null
        return try {
            context.getNameAndPhotoFromPhoneNumber(handle).name.takeIf { it.isNotBlank() && it != handle }
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Only conversations the operator named in Fossify have a name. */
    private fun chatName(threadId: Long): String? = try {
        context.conversationsDB.getConversationWithThreadId(threadId)
            ?.takeIf { it.usesCustomTitle }
            ?.title
            ?.takeIf { it.isNotBlank() }
    } catch (_: RuntimeException) {
        null
    }

    companion object {
        const val MAX_PARTICIPANTS = 50
        const val MAX_THREADS = 500
        private const val TEXT_PLAIN = "text/plain"
        private const val SMIL = "application/smil"
        private const val OCTET_STREAM = "application/octet-stream"
    }
}

/**
 * Fills the inbound queue from the provider, so nothing received while the app was dead is lost. Reads past the
 * highest uploaded ids, and also re-reads the last two days so a row stored just before a crash is never skipped.
 * Messages dated before the gateway was turned on are never reported.
 */
class CatchUpScanner(
    private val settings: GatewaySettings,
    private val reader: ProviderReader,
    private val factory: InboundEventFactory,
    private val store: InboundStore,
    private val onNewGroupThread: () -> Unit,
    private val clock: Clock = Clock.SYSTEM,
) {
    /**
     * Only one scan at a time. The receivers' [enqueueSms]/[enqueueMms] don't wait for it (an SMS broadcast must
     * finish quickly): inserting is idempotent, and every path skips a provider row that already has an event.
     * That also covers an MMS whose date mmslib rewrites right after storing it, which would otherwise give the
     * same row two ids.
     */
    private val scanLock = Any()
    private val groupThreadsLock = Any()

    fun scan(): Int = synchronized(scanLock) {
        if (!settings.enabled) return 0
        val since = settings.enabledAt
        val recent = clock.now() - LOOKBACK_MILLIS
        var added = 0
        reader.inboxSms(since, settings.smsCursor, recent).forEach { row ->
            if (!store.containsSource(TYPE_SMS, row.id) && enqueue(factory.fromSms(row))) added++
        }
        reader.inboxMms(since, settings.mmsCursor, recent).forEach { row ->
            if (!store.containsSource(TYPE_MMS, row.id) && enqueue(factory.fromMms(row))) added++
        }
        added
    }

    /** From SmsReceiver, right after the message is stored. */
    fun enqueueSms(id: Long): Boolean {
        val row = reader.sms(id)?.takeIf { it.isInbox } ?: return false
        if (row.date < settings.enabledAt || store.containsSource(TYPE_SMS, row.id)) return false
        return enqueue(factory.fromSms(row))
    }

    /** From MmsReceiver, once the downloaded MMS is stored. */
    fun enqueueMms(id: Long): Boolean {
        // Never report messages this phone sent: only downloaded inbox MMS.
        val row = reader.mms(id)?.takeIf { it.isInbox } ?: return false
        if (row.dateMillis < settings.enabledAt || store.containsSource(TYPE_MMS, row.id)) return false
        return enqueue(factory.fromMms(row))
    }

    private fun enqueue(built: InboundEventFactory.Built?): Boolean {
        built ?: return false
        val added = store.enqueue(built.record, built.attachments)
        if (added && built.isGroup) noteGroupThread(built.record.threadId)
        return added
    }

    private fun noteGroupThread(threadId: Long) {
        val isNew = synchronized(groupThreadsLock) {
            val known = settings.knownGroupThreads
            val thread = threadId.toString()
            (thread !in known).also { if (it) settings.knownGroupThreads = known + thread }
        }
        if (isNew) onNewGroupThread()
    }

    companion object {
        const val LOOKBACK_MILLIS = 48 * 60 * 60 * 1000L
    }
}
