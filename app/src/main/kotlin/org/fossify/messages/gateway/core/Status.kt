package org.fossify.messages.gateway.core

import java.util.concurrent.ConcurrentHashMap

/** What the operator screen, the notification and the heartbeat show. Implemented on the Android side. */
interface StatusSink {
    fun recordError(message: String)

    /** An outbox message arrived without `type` or `recipients`. */
    fun onServerOutOfDate()
    fun onUploaded(at: Long)
    fun onSent(at: Long)
    fun onFeatureChanged(feature: Feature, available: Boolean, retryAt: Long)

    object None : StatusSink {
        override fun recordError(message: String) = Unit
        override fun onServerOutOfDate() = Unit
        override fun onUploaded(at: Long) = Unit
        override fun onSent(at: Long) = Unit
        override fun onFeatureChanged(feature: Feature, available: Boolean, retryAt: Long) = Unit
    }
}

/** Routes that are new on the server and answer 404 until it ships them (contract 2.9). */
enum class Feature { HEARTBEAT, THREADS, ATTACHMENTS }

/** A route that answered 404 is turned off and tried again an hour later. */
class FeatureGate(
    private val clock: Clock,
    private val status: StatusSink,
    private val retryAfterMillis: Long = RETRY_AFTER_MILLIS,
) {
    private val retryAt = ConcurrentHashMap<Feature, Long>()

    /** True when the route is believed to exist, or when it's time to try it again. */
    fun shouldTry(feature: Feature) = (retryAt[feature] ?: 0L) <= clock.now()

    fun isUnavailable(feature: Feature) = retryAt.containsKey(feature)

    fun unsupported(feature: Feature) {
        val at = clock.now() + retryAfterMillis
        retryAt[feature] = at
        status.onFeatureChanged(feature, false, at)
    }

    fun supported(feature: Feature) {
        if (retryAt.remove(feature) != null) status.onFeatureChanged(feature, true, 0)
    }

    companion object {
        const val RETRY_AFTER_MILLIS = 60 * 60 * 1000L
    }
}
