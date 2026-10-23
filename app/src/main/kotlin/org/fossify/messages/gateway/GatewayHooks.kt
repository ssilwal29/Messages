package org.fossify.messages.gateway

import android.content.Context
import android.content.Intent
import android.provider.Telephony.Sms
import android.telephony.SmsManager
import android.util.Log
import org.fossify.messages.BuildConfig
import org.fossify.messages.gateway.core.DeliveryOutcome
import org.fossify.messages.receivers.SendStatusReceiver

/**
 * The only entry points Fossify's receivers call. Each one is a single line in the Fossify file, does nothing
 * unless the gateway is on (or the broadcast carries a gateway outbox id), and never throws into the receiver.
 */
@Suppress("TooManyFunctions")
object GatewayHooks {
    /** Attached to the SMS sent/delivered intents and the MMS sent broadcast of every gateway send. */
    const val EXTRA_OUTBOX_ID = "gateway_outbox_id"

    private const val EXTRA_CONTENT_URI = "content_uri"

    /** While on, every received message is stored and reported: blocking and keyword filters are bypassed. */
    fun isEnabled(context: Context) = GatewaySettings.isEnabled(context)

    /** SmsReceiver, right after `insertNewSMS` returned [smsId], in the same background block. */
    fun onSmsStored(context: Context, smsId: Long) = ifEnabled(context, "received SMS") { gateway ->
        // insertNewSMS returns 0 when the provider refused the row; then there is nothing stored to report.
        if (smsId > 0) gateway.scanner.enqueueSms(smsId)
        gateway.requestUpload()
    }

    /** MmsReceiver, once the downloaded MMS is stored. */
    fun onMmsStored(context: Context, mmsId: Long) = ifEnabled(context, "received MMS") { gateway ->
        gateway.scanner.enqueueMms(mmsId)
        gateway.requestUpload()
    }

    fun onMmsDownloadFailed(context: Context, error: String) = ifEnabled(context, "MMS download") { gateway ->
        gateway.status.onMmsDownloadFailed(error)
    }

    /** SmsStatusSentReceiver: one call per SMS part. */
    fun onSmsSent(context: Context, intent: Intent, resultCode: Int) {
        val id = intent.getStringExtra(EXTRA_OUTBOX_ID) ?: return
        val errorCode = intent.getIntExtra(SendStatusReceiver.EXTRA_ERROR_CODE, SendStatusReceiver.NO_ERROR_CODE)
        val error = SendResults.sms(resultCode, errorCode)
        withGateway(context, "SMS sent result") { gateway ->
            gateway.processor.onSmsPartSent(id, intent.data?.toString(), error)
        }
    }

    /** MmsSentReceiver. */
    fun onMmsSent(context: Context, intent: Intent, resultCode: Int) {
        val id = intent.getStringExtra(EXTRA_OUTBOX_ID) ?: return
        val providerUri = intent.getStringExtra(EXTRA_CONTENT_URI) ?: intent.data?.toString()
        val error = SendResults.mms(resultCode, intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0))
        withGateway(context, "MMS sent result") { gateway ->
            gateway.processor.onMmsSent(id, providerUri, error)
            checkThread(gateway, id, providerUri)
        }
    }

    /** Group MMS should land in the thread it was addressed to; say so on the operator screen if it didn't. */
    private fun checkThread(gateway: Gateway, id: String, providerUri: String?) {
        val expected = gateway.outboundStore.get(id)?.chat?.toLongOrNull() ?: return
        val actual = providerUri?.let(gateway.reader::threadOf) ?: return
        if (actual != expected) {
            gateway.status.recordError("reply $id was filed in thread $actual instead of $expected")
        }
    }

    /** SmsStatusDeliveredReceiver, with the status it worked out (GSM TP-Status, or CDMA mapped to it). */
    fun onSmsDelivered(context: Context, intent: Intent, status: Int) {
        val id = intent.getStringExtra(EXTRA_OUTBOX_ID) ?: return
        val outcome = when {
            status == Sms.STATUS_NONE -> return
            status < Sms.STATUS_PENDING -> DeliveryOutcome.Delivered
            status < Sms.STATUS_FAILED -> DeliveryOutcome.Pending
            else -> DeliveryOutcome.Failed("delivery failed (status $status)")
        }
        withGateway(context, "delivery report") { gateway -> gateway.processor.onSmsDelivery(id, outcome) }
    }

    /** RescheduleAlarmsReceiver: BOOT_COMPLETED and MY_PACKAGE_REPLACED may start a foreground service. */
    fun onBootOrUpdate(context: Context, action: String?) {
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        ifEnabled(context, "start-up") { gateway ->
            gateway.scheduleWatchdog()
            GatewayService.start(gateway.app)
        }
    }

    private fun ifEnabled(context: Context, what: String, block: (Gateway) -> Unit) {
        if (isEnabled(context)) withGateway(context, what, block)
    }

    private fun withGateway(context: Context, what: String, block: (Gateway) -> Unit) {
        try {
            val gateway = Gateway.get(context)
            gateway.guarded(what) { block(gateway) }
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            GatewayLog.e("$what failed", e)
        }
    }
}

/**
 * Logcat for the gateway. Debug builds only: release builds log nothing, so message text, phone numbers and the
 * token can't end up in logcat. Callers never put those in messages anyway.
 */
object GatewayLog {
    private const val TAG = "DudeGateway"

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    fun e(message: String, error: Throwable? = null) {
        // Exception messages can contain provider data, so only the type is logged.
        if (BuildConfig.DEBUG) Log.e(TAG, message + (error?.let { ": ${it.javaClass.name}" } ?: ""))
    }
}
