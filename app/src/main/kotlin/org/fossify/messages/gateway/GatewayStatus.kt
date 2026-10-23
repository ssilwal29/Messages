package org.fossify.messages.gateway

import android.content.Context
import androidx.core.content.edit
import org.fossify.messages.gateway.core.DoorbellState
import org.fossify.messages.gateway.core.Feature
import org.fossify.messages.gateway.core.StatusSink
import java.util.concurrent.CopyOnWriteArraySet

/**
 * What the operator screen, the service notification and the heartbeat show. Persisted so it survives the
 * process being killed. Holds no message text, and phone numbers only inside server error messages.
 */
@Suppress("TooManyFunctions")
class GatewayStatus(context: Context) : StatusSink {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun changed() = listeners.forEach { it() }

    var doorbell: DoorbellState
        get() = prefs.getString(DOORBELL, null)?.let { runCatching { DoorbellState.valueOf(it) }.getOrNull() }
            ?: DoorbellState.STOPPED
        set(value) {
            prefs.edit { putString(DOORBELL, value.name) }
            changed()
        }

    var longPolling: Boolean
        get() = prefs.getBoolean(LONG_POLLING, false)
        set(value) {
            prefs.edit { putBoolean(LONG_POLLING, value) }
            changed()
        }

    var tokenRejected: Boolean
        get() = prefs.getBoolean(TOKEN_REJECTED, false)
        set(value) {
            prefs.edit { putBoolean(TOKEN_REJECTED, value) }
            changed()
        }

    val serverOutOfDateAt get() = prefs.getLong(SERVER_OUT_OF_DATE_AT, 0)
    val lastUploadAt get() = prefs.getLong(LAST_UPLOAD_AT, 0)
    val lastSendAt get() = prefs.getLong(LAST_SEND_AT, 0)
    val lastHeartbeatAt get() = prefs.getLong(LAST_HEARTBEAT_AT, 0)
    val lastError get() = prefs.getString(LAST_ERROR, null)
    val lastErrorAt get() = prefs.getLong(LAST_ERROR_AT, 0)
    val mmsDownloadFailuresTotal get() = prefs.getInt(MMS_FAILURES_TOTAL, 0)

    /** Download failures since the last heartbeat that went through. */
    val mmsDownloadFailuresSinceHeartbeat
        get() = prefs.getInt(MMS_FAILURES_TOTAL, 0) - prefs.getInt(MMS_FAILURES_REPORTED, 0)

    /** Routes the server doesn't have yet, with the time each is tried again. */
    fun unavailableFeatures(): Map<Feature, Long> = Feature.entries.mapNotNull { feature ->
        prefs.getLong(FEATURE_PREFIX + feature.name, 0).takeIf { it > 0 }?.let { feature to it }
    }.toMap()

    override fun recordError(message: String) {
        prefs.edit {
            putString(LAST_ERROR, message.take(MAX_ERROR_LENGTH))
            putLong(LAST_ERROR_AT, System.currentTimeMillis())
        }
        changed()
    }

    fun clearError() {
        prefs.edit {
            remove(LAST_ERROR)
            remove(LAST_ERROR_AT)
        }
        changed()
    }

    override fun onServerOutOfDate() {
        prefs.edit { putLong(SERVER_OUT_OF_DATE_AT, System.currentTimeMillis()) }
        changed()
    }

    override fun onUploaded(at: Long) {
        prefs.edit { putLong(LAST_UPLOAD_AT, at) }
        changed()
    }

    override fun onSent(at: Long) {
        prefs.edit { putLong(LAST_SEND_AT, at) }
        changed()
    }

    fun onHeartbeat(at: Long, reportedFailures: Int) {
        prefs.edit {
            putLong(LAST_HEARTBEAT_AT, at)
            putInt(MMS_FAILURES_REPORTED, prefs.getInt(MMS_FAILURES_REPORTED, 0) + reportedFailures)
        }
        changed()
    }

    @Synchronized
    fun onMmsDownloadFailed(error: String) {
        prefs.edit { putInt(MMS_FAILURES_TOTAL, prefs.getInt(MMS_FAILURES_TOTAL, 0) + 1) }
        recordError("MMS download failed: $error")
    }

    override fun onFeatureChanged(feature: Feature, available: Boolean, retryAt: Long) {
        prefs.edit { putLong(FEATURE_PREFIX + feature.name, if (available) 0 else retryAt) }
        changed()
    }

    companion object {
        private const val PREFS = "gateway_status"
        private const val DOORBELL = "doorbell"
        private const val LONG_POLLING = "long_polling"
        private const val TOKEN_REJECTED = "token_rejected"
        private const val SERVER_OUT_OF_DATE_AT = "server_out_of_date_at"
        private const val LAST_UPLOAD_AT = "last_upload_at"
        private const val LAST_SEND_AT = "last_send_at"
        private const val LAST_HEARTBEAT_AT = "last_heartbeat_at"
        private const val LAST_ERROR = "last_error"
        private const val LAST_ERROR_AT = "last_error_at"
        private const val MMS_FAILURES_TOTAL = "mms_failures_total"
        private const val MMS_FAILURES_REPORTED = "mms_failures_reported"
        private const val FEATURE_PREFIX = "feature_unavailable_"
        private const val MAX_ERROR_LENGTH = 500
    }
}
