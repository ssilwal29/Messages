package org.fossify.messages.gateway.core

/** Sender and participants of a received MMS, worked out from its `addr` rows. */
object MmsAddresses {
    // PduHeaders address types, as stored in content://mms/<id>/addr.
    const val TYPE_BCC = 0x81
    const val TYPE_CC = 0x82
    const val TYPE_FROM = 0x89
    const val TYPE_TO = 0x97

    data class AddrRow(val address: String, val type: Int)

    data class Parties(val from: String?, val participants: List<String>)

    private val placeholders = setOf("insert-address-token", "")

    /**
     * The sender is the FROM row; participants are FROM, TO and CC (the people a reply-all reaches), normalised,
     * without the phone's own numbers and without duplicates, in row order. BCC rows are never participants.
     */
    fun parse(rows: List<AddrRow>, normalizer: HandleNormalizer, own: OwnNumbers): Parties {
        val usable = rows.filter { it.address.trim().lowercase() !in placeholders }
        val from = usable.firstOrNull { it.type == TYPE_FROM }?.let { normalizer.normalize(it.address) }
        val participants = usable
            .filter { it.type == TYPE_FROM || it.type == TYPE_TO || it.type == TYPE_CC }
            .map { normalizer.normalize(it.address) }
            .filter { it.isNotEmpty() && it !in own }
            .distinct()
        return Parties(from, participants)
    }
}
