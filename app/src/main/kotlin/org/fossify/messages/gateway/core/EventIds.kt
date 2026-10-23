package org.fossify.messages.gateway.core

/**
 * Event ids are stable forever: they are built only from the provider row id and its stored date, which is
 * the same whether the receiver or the catch-up scanner reports the message. SMS and MMS ids are separate
 * sequences, hence the prefix.
 *
 * Both dates are in milliseconds. The MMS provider stores seconds; callers pass `date * 1000`.
 */
object EventIds {
    const val MAX_LENGTH = 200

    fun sms(providerId: Long, dateMillis: Long) = "$TYPE_SMS:$providerId:$dateMillis"

    fun mms(providerId: Long, dateMillis: Long) = "$TYPE_MMS:$providerId:$dateMillis"

    fun attachmentKey(eventId: String, partId: Long) = "$eventId:part:$partId"
}
