package org.fossify.messages.gateway.core

/**
 * The check that runs before every send (contract 2.3). It is what stops one group's message going into
 * another group's thread, so a failure is always permanent.
 */
object RecipientCheck {

    sealed interface Result {
        data object Ok : Result
        data class Mismatch(val reason: String) : Result
    }

    /**
     * @param threadAddresses the thread's recipient set as stored by the provider, or null if the thread
     * doesn't exist. Normalised here and stripped of the phone's own numbers.
     */
    @Suppress("ReturnCount")
    fun check(
        type: String,
        recipients: List<String>,
        threadAddresses: List<String>?,
        normalizer: HandleNormalizer,
        own: OwnNumbers,
    ): Result {
        if (threadAddresses == null) return Result.Mismatch("thread does not exist")

        val expected = recipients.toSet()
        if (expected.size != recipients.size) return Result.Mismatch("recipients contain duplicates")

        val ownInRecipients = expected.filter { it in own }
        if (ownInRecipients.isNotEmpty()) {
            return Result.Mismatch("recipients include this phone's own number ${ownInRecipients.joinToString()}")
        }

        when (type) {
            TYPE_SMS -> if (expected.size != 1) {
                return Result.Mismatch("type sms needs exactly one recipient, got ${expected.size}")
            }

            TYPE_MMS -> if (expected.size < 2) {
                return Result.Mismatch("type mms needs two or more recipients, got ${expected.size}")
            }

            else -> return Result.Mismatch("unknown type '$type'")
        }

        val actual = normalizer.normalizeAll(threadAddresses).filterNot { it in own }.toSet()
        if (actual == expected) return Result.Ok

        val missing = expected - actual
        val extra = actual - expected
        val parts = buildList {
            if (missing.isNotEmpty()) add("not in thread: ${missing.sorted().joinToString()}")
            if (extra.isNotEmpty()) add("also in thread: ${extra.sorted().joinToString()}")
        }
        return Result.Mismatch("recipient mismatch (${parts.joinToString("; ")})")
    }
}
