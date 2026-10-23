package org.fossify.messages.gateway

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Telephony
import android.provider.Telephony.Sms
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.klinker.android.send_message.Message
import com.klinker.android.send_message.Settings
import com.klinker.android.send_message.Transaction
import org.fossify.messages.extensions.messagingUtils
import org.fossify.messages.extensions.smsSender
import org.fossify.messages.gateway.core.OutboundJob
import org.fossify.messages.gateway.core.OutboundSender
import org.fossify.messages.gateway.core.PreparedSend
import org.fossify.messages.gateway.core.ProviderProbe
import org.fossify.messages.gateway.core.SendError
import org.fossify.messages.gateway.core.SendException
import org.fossify.messages.gateway.core.TYPE_MMS
import org.fossify.messages.gateway.core.TYPE_SMS
import org.fossify.messages.messaging.SmsException
import org.fossify.messages.messaging.getSmsManager
import org.fossify.messages.receivers.MmsSentReceiver

/**
 * Sends the server's replies. Never goes through Fossify's `sendMessageCompat`, which reads the user's settings,
 * sends one SMS per person for a group when group MMS is off, and turns errors into toasts.
 *
 * - `mms`: one group MMS to the thread's stored addresses (so the provider files it in that same thread), through
 *   a [Transaction] with the gateway's own [Settings] and a sent broadcast carrying the outbox id.
 * - `sms`: one recipient, written to the thread with `insertSmsMessage` and sent with [org.fossify.messages
 *   .messaging.SmsSender]: multipart when long, never converted to MMS, with sent and delivered intents carrying
 *   the outbox id.
 */
class GatewaySender(
    private val context: Context,
    private val settings: GatewaySettings,
    private val reader: ProviderReader,
) : OutboundSender {

    override fun blockedReason(): String? = when {
        !settings.enabled -> "the gateway is off"
        Telephony.Sms.getDefaultSmsPackage(context) != context.packageName -> "not the default SMS app"
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ->
            "SMS permission missing"

        settings.subscriptionId == GatewaySettings.NO_SUBSCRIPTION -> "no SIM selected on the gateway screen"
        !isSubscriptionActive(settings.subscriptionId) -> "the selected SIM is not active"
        else -> null
    }

    private fun isSubscriptionActive(subId: Int): Boolean = try {
        context.getSystemService(SubscriptionManager::class.java)?.getActiveSubscriptionInfo(subId) != null
    } catch (_: SecurityException) {
        // Without READ_PHONE_STATE we can't tell; let the send itself report a SIM problem.
        true
    }

    override fun threadAddresses(chatId: String): List<String>? = chatId.toLongOrNull()?.let(reader::threadAddresses)

    override fun prepare(job: OutboundJob, threadAddresses: List<String>): PreparedSend {
        if (job.body.isEmpty()) throw SendException("empty text", permanent = true)
        return when (job.type) {
            TYPE_SMS -> {
                val destination = threadAddresses.single()
                val subId = settings.subscriptionId
                val uri = try {
                    context.messagingUtils.insertSmsMessage(
                        subId = subId,
                        dest = destination,
                        text = job.body,
                        timestamp = System.currentTimeMillis(),
                        threadId = job.chat.toLong(),
                    )
                } catch (e: SmsException) {
                    throw SendException("couldn't store the outgoing SMS", permanent = false, cause = e)
                }
                val parts = getSmsManager(subId).divideMessage(job.body)?.size ?: 1
                PreparedSend(uri.toString(), parts.coerceAtLeast(1), listOf(destination))
            }

            TYPE_MMS -> PreparedSend(null, 1, threadAddresses)
            else -> throw SendException("unknown type ${job.type}", permanent = true)
        }
    }

    override fun dispatch(job: OutboundJob, prepared: PreparedSend) = withWakeLock {
        when (job.type) {
            TYPE_SMS -> sendSms(job, prepared)
            TYPE_MMS -> sendMms(job, prepared)
            else -> throw SendException("unknown type ${job.type}", permanent = true)
        }
    }

    private fun sendSms(job: OutboundJob, prepared: PreparedSend) {
        val uri = prepared.providerUri!!.toUri()
        try {
            context.smsSender.sendMessage(
                subId = settings.subscriptionId,
                destination = prepared.destinations.single(),
                body = job.body,
                serviceCenter = null,
                requireDeliveryReport = true,
                messageUri = uri,
                gatewayOutboxId = job.id,
            )
        } catch (e: SmsException) {
            context.messagingUtils.updateSmsMessageSendingStatus(uri, Sms.MESSAGE_TYPE_FAILED)
            val permanent = e.errorCode == SmsException.EMPTY_DESTINATION_ADDRESS
            throw SendException("SMS not sent (error ${e.errorCode})", permanent, e)
        } catch (e: IllegalArgumentException) {
            context.messagingUtils.updateSmsMessageSendingStatus(uri, Sms.MESSAGE_TYPE_FAILED)
            throw SendException("SMS not sent: invalid argument", permanent = true, cause = e)
        }
    }

    private fun sendMms(job: OutboundJob, prepared: PreparedSend) {
        val mmsSettings = Settings().apply {
            useSystemSending = true
            group = true
            deliveryReports = true
            sendLongAsMms = false
            subscriptionId = settings.subscriptionId
        }
        val sentIntent = Intent(context, MmsSentReceiver::class.java).putExtra(GatewayHooks.EXTRA_OUTBOX_ID, job.id)
        try {
            // Transaction keeps its settings in a static field; don't let a send from Fossify's UI swap them mid-way.
            synchronized(Transaction::class.java) {
                val transaction = Transaction(context, mmsSettings)
                transaction.setExplicitBroadcastForSentMms(sentIntent)
                transaction.sendNewMessage(Message(job.body, prepared.destinations.toTypedArray()))
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            throw SendException("MMS not sent: ${e.javaClass.simpleName}", permanent = false, cause = e)
        }
    }

    override fun probe(providerUri: String): ProviderProbe = reader.probe(providerUri)

    private fun <T> withWakeLock(block: () -> T): T {
        val power = context.getSystemService(PowerManager::class.java)
        val lock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DudeGateway:send")
        lock?.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
        try {
            return block()
        } finally {
            if (lock?.isHeld == true) lock.release()
        }
    }

    companion object {
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 60_000L
    }
}

/** Turns platform send results into what the server needs: a short English error and whether retrying can help. */
object SendResults {
    private const val HTTP_CLIENT_ERROR = 400
    private const val HTTP_SERVER_ERROR = 500

    private val permanentSmsResults = setOf(
        SmsManager.RESULT_INVALID_ARGUMENTS,
        SmsManager.RESULT_RIL_INVALID_ARGUMENTS,
        SmsManager.RESULT_INVALID_SMS_FORMAT,
        SmsManager.RESULT_RIL_INVALID_SMS_FORMAT,
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED,
        SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED,
    )

    private val smsNames = mapOf(
        SmsManager.RESULT_ERROR_GENERIC_FAILURE to "generic failure",
        SmsManager.RESULT_ERROR_RADIO_OFF to "radio off",
        SmsManager.RESULT_ERROR_NULL_PDU to "null PDU",
        SmsManager.RESULT_ERROR_NO_SERVICE to "no service",
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED to "rate limit",
        SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE to "fixed dialing",
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED to "short code not allowed",
        SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED to "short code never allowed",
        SmsManager.RESULT_RADIO_NOT_AVAILABLE to "radio not available",
        SmsManager.RESULT_NETWORK_REJECT to "network reject",
        SmsManager.RESULT_INVALID_ARGUMENTS to "invalid arguments",
        SmsManager.RESULT_INVALID_STATE to "invalid state",
        SmsManager.RESULT_NO_MEMORY to "no memory",
        SmsManager.RESULT_INVALID_SMS_FORMAT to "invalid format",
        SmsManager.RESULT_SYSTEM_ERROR to "system error",
        SmsManager.RESULT_MODEM_ERROR to "modem error",
        SmsManager.RESULT_NETWORK_ERROR to "network error",
        SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED to "rate limit",
        SmsManager.RESULT_RIL_INVALID_ARGUMENTS to "invalid arguments",
        SmsManager.RESULT_RIL_INVALID_SMS_FORMAT to "invalid format",
        SmsManager.RESULT_NO_DEFAULT_SMS_APP to "not the default SMS app",
    )

    private val mmsNames = mapOf(
        SmsManager.MMS_ERROR_UNSPECIFIED to "unspecified",
        SmsManager.MMS_ERROR_INVALID_APN to "invalid APN",
        SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS to "can't connect to MMSC",
        SmsManager.MMS_ERROR_HTTP_FAILURE to "HTTP failure",
        SmsManager.MMS_ERROR_IO_ERROR to "I/O error",
        SmsManager.MMS_ERROR_RETRY to "retry",
        SmsManager.MMS_ERROR_CONFIGURATION_ERROR to "configuration error",
        SmsManager.MMS_ERROR_NO_DATA_NETWORK to "no data network",
        SmsManager.MMS_ERROR_INVALID_SUBSCRIPTION_ID to "invalid subscription",
        SmsManager.MMS_ERROR_INACTIVE_SUBSCRIPTION to "inactive subscription",
        SmsManager.MMS_ERROR_DATA_DISABLED to "mobile data disabled",
        SmsManager.MMS_ERROR_MMS_DISABLED_BY_CARRIER to "MMS disabled by carrier",
    )

    /** Null when the part went out. `errorCode` is the radio's `EXTRA_ERROR_CODE`, or -1. */
    fun sms(resultCode: Int, errorCode: Int): SendError? {
        if (resultCode == Activity.RESULT_OK) return null
        val name = smsNames[resultCode]?.let { " ($it)" }.orEmpty()
        val radio = if (errorCode >= 0) ", radio error $errorCode" else ""
        return SendError("SMS result $resultCode$name$radio", permanent = resultCode in permanentSmsResults)
    }

    /** Null when the MMS went out. An MMSC HTTP 4xx is permanent. */
    fun mms(resultCode: Int, httpStatus: Int): SendError? {
        if (resultCode == Activity.RESULT_OK) return null
        val name = mmsNames[resultCode]?.let { " ($it)" }.orEmpty()
        val http = if (httpStatus > 0) ", HTTP $httpStatus" else ""
        val permanent = httpStatus in HTTP_CLIENT_ERROR until HTTP_SERVER_ERROR
        return SendError("MMS result $resultCode$name$http", permanent)
    }
}
