package org.fossify.messages.gateway

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import androidx.core.net.toUri
import org.fossify.messages.gateway.core.MmsAddresses
import org.fossify.messages.gateway.core.ProviderProbe

/**
 * Reads the SMS/MMS provider for the gateway. Everything here is read-only and never shows toasts, unlike some
 * of Fossify's helpers, because it runs from the service and the receivers.
 */
@Suppress("TooManyFunctions")
class ProviderReader(private val context: Context) {
    private val resolver get() = context.contentResolver

    data class SmsRow(
        val id: Long,
        val threadId: Long,
        val address: String,
        val body: String,
        val date: Long,
        val type: Int = Sms.MESSAGE_TYPE_INBOX,
    ) {
        val isInbox get() = type == Sms.MESSAGE_TYPE_INBOX
    }

    data class MmsRow(val id: Long, val threadId: Long, val dateMillis: Long, val box: Int = Mms.MESSAGE_BOX_INBOX) {
        val isInbox get() = box == Mms.MESSAGE_BOX_INBOX
    }

    data class MmsPart(val id: Long, val contentType: String, val text: String?, val name: String?)

    data class ThreadRow(val id: Long, val dateMillis: Long, val addresses: List<String>)

    fun sms(id: Long): SmsRow? = querySms("${Sms._ID} = ?", arrayOf(id.toString()), null).firstOrNull()

    /** Received SMS rows dated at or after [since], that are past [afterId] or dated at or after [recentSince]. */
    fun inboxSms(since: Long, afterId: Long, recentSince: Long): List<SmsRow> = querySms(
        "${Sms.TYPE} = ${Sms.MESSAGE_TYPE_INBOX} AND ${Sms.DATE} >= ? AND (${Sms._ID} > ? OR ${Sms.DATE} >= ?)",
        arrayOf(since.toString(), afterId.toString(), recentSince.toString()),
        "${Sms._ID} ASC",
    )

    private fun querySms(selection: String, args: Array<String>, order: String?): List<SmsRow> {
        val projection = arrayOf(Sms._ID, Sms.THREAD_ID, Sms.ADDRESS, Sms.BODY, Sms.DATE, Sms.TYPE)
        return query(Sms.CONTENT_URI, projection, selection, args, order) {
            SmsRow(
                id = it.long(Sms._ID),
                threadId = it.long(Sms.THREAD_ID),
                address = it.string(Sms.ADDRESS).orEmpty(),
                body = it.string(Sms.BODY).orEmpty(),
                date = it.long(Sms.DATE),
                type = it.int(Sms.TYPE),
            )
        }
    }

    fun mms(id: Long): MmsRow? = queryMms("${Mms._ID} = ?", arrayOf(id.toString()), null).firstOrNull()

    /** Downloaded inbox MMS (M-Retrieve.conf), with the same window as [inboxSms]. Dates in milliseconds. */
    fun inboxMms(since: Long, afterId: Long, recentSince: Long): List<MmsRow> = queryMms(
        "${Mms.MESSAGE_BOX} = ${Mms.MESSAGE_BOX_INBOX} AND ${Mms.MESSAGE_TYPE} = $MESSAGE_TYPE_RETRIEVE_CONF " +
            "AND ${Mms.DATE} >= ? AND (${Mms._ID} > ? OR ${Mms.DATE} >= ?)",
        arrayOf(
            (since / MILLIS).toString(),
            afterId.toString(),
            (recentSince / MILLIS).toString(),
        ),
        "${Mms._ID} ASC",
    )

    private fun queryMms(selection: String, args: Array<String>, order: String?): List<MmsRow> {
        val projection = arrayOf(Mms._ID, Mms.THREAD_ID, Mms.DATE, Mms.MESSAGE_BOX)
        return query(Mms.CONTENT_URI, projection, selection, args, order) {
            MmsRow(it.long(Mms._ID), it.long(Mms.THREAD_ID), it.long(Mms.DATE) * MILLIS, it.int(Mms.MESSAGE_BOX))
        }
    }

    fun mmsAddresses(mmsId: Long): List<MmsAddresses.AddrRow> =
        query("${Mms.CONTENT_URI}/$mmsId/addr".toUri(), arrayOf(Mms.Addr.ADDRESS, Mms.Addr.TYPE), null, null, null) {
            MmsAddresses.AddrRow(it.string(Mms.Addr.ADDRESS).orEmpty(), it.int(Mms.Addr.TYPE))
        }

    fun mmsParts(mmsId: Long): List<MmsPart> {
        val projection = arrayOf(
            Mms.Part._ID, Mms.Part.CONTENT_TYPE, Mms.Part.TEXT, Mms.Part.NAME, Mms.Part.FILENAME,
            Mms.Part.CONTENT_LOCATION,
        )
        return query(PART_URI, projection, "${Mms.Part.MSG_ID} = ?", arrayOf(mmsId.toString()), "${Mms.Part._ID} ASC") {
            MmsPart(
                id = it.long(Mms.Part._ID),
                contentType = it.string(Mms.Part.CONTENT_TYPE).orEmpty(),
                text = it.string(Mms.Part.TEXT),
                name = listOf(Mms.Part.NAME, Mms.Part.FILENAME, Mms.Part.CONTENT_LOCATION)
                    .firstNotNullOfOrNull { column -> it.string(column)?.takeIf(String::isNotBlank) },
            )
        }
    }

    fun partUri(partId: Long): Uri = Uri.withAppendedPath(PART_URI, partId.toString())

    /** The thread's recipient addresses as the provider stores them, or null if the thread doesn't exist. */
    fun threadAddresses(threadId: Long): List<String>? {
        val ids = query(
            CONVERSATIONS_URI, arrayOf(RECIPIENT_IDS), "${Mms._ID} = ?", arrayOf(threadId.toString()), null
        ) { it.string(RECIPIENT_IDS).orEmpty() }.firstOrNull() ?: return null
        val recipientIds = ids.split(' ').mapNotNull { it.trim().toLongOrNull() }
        if (recipientIds.isEmpty()) return null
        val addresses = canonicalAddresses(recipientIds)
        return recipientIds.mapNotNull { addresses[it] }.takeIf { it.size == recipientIds.size }
    }

    /** The most recent threads, newest first. */
    fun recentThreads(limit: Int): List<ThreadRow> {
        val rows = query(
            CONVERSATIONS_URI, arrayOf(Mms._ID, Mms.DATE, RECIPIENT_IDS), "$MESSAGE_COUNT > 0", null, "${Mms.DATE} DESC"
        ) { Triple(it.long(Mms._ID), it.long(Mms.DATE), it.string(RECIPIENT_IDS).orEmpty()) }
        val addresses = canonicalAddresses(null)
        return rows.take(limit).map { (id, date, recipientIds) ->
            val dateMillis = if (date < MILLIS_THRESHOLD) date * MILLIS else date
            val ids = recipientIds.split(' ').mapNotNull { it.trim().toLongOrNull() }
            ThreadRow(id, dateMillis, ids.mapNotNull(addresses::get))
        }
    }

    private fun canonicalAddresses(ids: List<Long>?): Map<Long, String> {
        val selection = ids?.let { list -> "${Mms._ID} IN (${list.joinToString(",") { "?" }})" }
        val args = ids?.map { it.toString() }?.toTypedArray()
        return query(CANONICAL_ADDRESSES_URI, arrayOf(Mms._ID, Mms.Addr.ADDRESS), selection, args, null) {
            it.long(Mms._ID) to it.string(Mms.Addr.ADDRESS).orEmpty()
        }.toMap()
    }

    /** The thread a provider message ended up in, or null. */
    fun threadOf(providerUri: String): Long? = query(
        providerUri.toUri(), arrayOf(Sms.THREAD_ID), null, null, null
    ) { it.long(Sms.THREAD_ID) }.firstOrNull()

    /** What the provider recorded for an outgoing message whose sent result we never got. */
    fun probe(providerUri: String): ProviderProbe {
        val uri = providerUri.toUri()
        val isMms = uri.authority == "mms"
        val column = if (isMms) Mms.MESSAGE_BOX else Sms.TYPE
        val value = query(uri, arrayOf(column), null, null, null) { it.int(column) }.firstOrNull()
            ?: return ProviderProbe.MISSING
        return when {
            isMms && value == Mms.MESSAGE_BOX_SENT -> ProviderProbe.SENT
            !isMms && value == Sms.MESSAGE_TYPE_SENT -> ProviderProbe.SENT
            !isMms && value == Sms.MESSAGE_TYPE_FAILED -> ProviderProbe.FAILED
            isMms && value == MESSAGE_BOX_FAILED -> ProviderProbe.FAILED
            else -> ProviderProbe.PENDING
        }
    }

    fun maxSmsId(): Long = maxId(Sms.CONTENT_URI)

    fun maxMmsId(): Long = maxId(Mms.CONTENT_URI)

    private fun maxId(uri: Uri): Long =
        query(uri, arrayOf(Sms._ID), null, null, "${Sms._ID} DESC LIMIT 1") { it.long(Sms._ID) }.firstOrNull() ?: 0

    private fun <T> query(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        args: Array<String>?,
        order: String?,
        map: (Cursor) -> T,
    ): List<T> = try {
        resolver.query(uri, projection, selection, args, order)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(map(cursor)) }
        }.orEmpty()
    } catch (_: SecurityException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    } catch (_: android.database.SQLException) {
        emptyList()
    }

    private fun Cursor.long(column: String) =
        getColumnIndex(column).let { if (it < 0 || isNull(it)) 0L else getLong(it) }

    private fun Cursor.int(column: String) =
        getColumnIndex(column).let { if (it < 0 || isNull(it)) 0 else getInt(it) }

    private fun Cursor.string(column: String): String? =
        getColumnIndex(column).let { if (it < 0) null else getString(it) }

    companion object {
        private const val MILLIS = 1000L

        /** Thread dates below this are in seconds. */
        private const val MILLIS_THRESHOLD = 100_000_000_000L
        private const val MESSAGE_TYPE_RETRIEVE_CONF = 132
        private const val MESSAGE_BOX_FAILED = 5
        private const val RECIPIENT_IDS = "recipient_ids"
        private const val MESSAGE_COUNT = "message_count"
        private val PART_URI = "content://mms/part".toUri()
        private val CONVERSATIONS_URI = "content://mms-sms/conversations?simple=true".toUri()
        private val CANONICAL_ADDRESSES_URI = "content://mms-sms/canonical-addresses".toUri()
    }
}
