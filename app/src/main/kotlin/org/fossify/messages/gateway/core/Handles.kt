package org.fossify.messages.gateway.core

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.ValidationResult
import java.util.Locale

/**
 * Turns provider addresses into the handles the server compares as exact strings, so one person always
 * produces one string:
 * - email-style addresses are trimmed and lower-cased;
 * - phone numbers that libphonenumber considers a possible full number become E.164, with [defaultRegion]
 *   (the SIM's country) used for national formats and a leading `00` read as the international prefix;
 * - anything else (short codes, alphanumeric senders) is kept as it is, minus whitespace and separators.
 */
class HandleNormalizer(defaultRegion: String?) {
    private val util = PhoneNumberUtil.getInstance()
    private val region = defaultRegion
        ?.trim()
        ?.uppercase(Locale.ROOT)
        ?.takeIf { it.length == 2 && util.supportedRegions.contains(it) }
        ?: UNKNOWN_REGION

    @Suppress("ReturnCount")
    fun normalize(raw: String): String {
        val address = raw.trim()
        if (address.isEmpty()) return address
        if (address.contains('@')) return address.lowercase(Locale.ROOT)
        if (address.any { it.isLetter() }) return address

        val stripped = address.filter { it.isDigit() || it == '+' }
        if (stripped.isEmpty()) return address

        toE164(stripped, region)?.let { return it }
        if (stripped.startsWith(INTERNATIONAL_00)) {
            toE164("+" + stripped.removePrefix(INTERNATIONAL_00), UNKNOWN_REGION)?.let { return it }
        }
        return stripped
    }

    fun normalizeAll(raw: Collection<String>): List<String> = raw.map(::normalize).filter { it.isNotEmpty() }.distinct()

    private fun toE164(number: String, region: String): String? {
        if (region == UNKNOWN_REGION && !number.startsWith("+")) return null
        return try {
            val parsed = util.parse(number, region)
            if (util.isPossibleNumberWithReason(parsed) == ValidationResult.IS_POSSIBLE) {
                util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
            } else {
                null
            }
        } catch (_: NumberParseException) {
            null
        }
    }

    companion object {
        private const val UNKNOWN_REGION = "ZZ"
        private const val INTERNATIONAL_00 = "00"
    }
}

/** The phone's own numbers, as the operator entered them, normalised like every other handle. */
class OwnNumbers(raw: Collection<String>, normalizer: HandleNormalizer) {
    val handles: Set<String> = normalizer.normalizeAll(raw).toSet()

    operator fun contains(handle: String) = handle in handles

    fun isEmpty() = handles.isEmpty()
}
