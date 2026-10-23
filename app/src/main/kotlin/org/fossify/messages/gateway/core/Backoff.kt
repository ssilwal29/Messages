package org.fossify.messages.gateway.core

import kotlin.math.min
import kotlin.random.Random

fun interface Clock {
    fun now(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/**
 * Exponential backoff with jitter: attempt 0 waits about [baseMillis], each further attempt doubles that, up to
 * [maxMillis]. The wait is drawn from the upper half of the window ("equal jitter"), so it never drops below
 * half the nominal delay and never below [baseMillis].
 */
class Backoff(
    private val baseMillis: Long,
    private val maxMillis: Long,
    private val random: Random = Random.Default,
) {
    fun delayFor(attempt: Int): Long {
        val shift = attempt.coerceIn(0, MAX_SHIFT)
        val nominal = min(maxMillis, baseMillis shl shift)
        val half = nominal / 2
        val jittered = half + if (half > 0) random.nextLong(half + 1) else 0
        return jittered.coerceIn(baseMillis, maxMillis)
    }

    companion object {
        private const val MAX_SHIFT = 30

        private const val DOORBELL_MIN_MILLIS = 1_000L
        private const val DOORBELL_MAX_MILLIS = 60_000L
        private const val RETRY_MIN_MILLIS = 5_000L
        private const val RETRY_MAX_MILLIS = 15 * 60_000L

        /** Doorbell reconnects: 1 s to 60 s. */
        fun doorbell(random: Random = Random.Default) = Backoff(DOORBELL_MIN_MILLIS, DOORBELL_MAX_MILLIS, random)

        /** Uploads and confirmations: 5 s to 15 min. */
        fun retries(random: Random = Random.Default) = Backoff(RETRY_MIN_MILLIS, RETRY_MAX_MILLIS, random)
    }
}
