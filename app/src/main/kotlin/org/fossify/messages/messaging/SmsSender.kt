package org.fossify.messages.messaging

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.telephony.PhoneNumberUtils
import org.fossify.commons.helpers.isSPlus
import org.fossify.messages.gateway.GatewayHooks
import org.fossify.messages.messaging.SmsException.Companion.EMPTY_DESTINATION_ADDRESS
import org.fossify.messages.messaging.SmsException.Companion.ERROR_SENDING_MESSAGE
import org.fossify.messages.receivers.SendStatusReceiver
import org.fossify.messages.receivers.SmsStatusDeliveredReceiver
import org.fossify.messages.receivers.SmsStatusSentReceiver

/** Class that sends chat message via SMS. */
class SmsSender(val app: Application) {

    // not sure what to do about this yet. this is the default as per android-smsmms
    private val sendMultipartSmsAsSeparateMessages = false

    // This should be called from a RequestWriter queue thread
    // gatewayOutboxId: set by Dude Gateway only, so its sent and delivered results can be matched to the reply.
    fun sendMessage(
        subId: Int, destination: String, body: String, serviceCenter: String?,
        requireDeliveryReport: Boolean, messageUri: Uri, gatewayOutboxId: String? = null
    ) {
        var dest = destination
        if (body.isEmpty()) {
            throw IllegalArgumentException("SmsSender: empty text message")
        }
        // remove spaces and dashes from destination number
        // (e.g. "801 555 1212" -> "8015551212")
        // (e.g. "+8211-123-4567" -> "+82111234567")
        dest = PhoneNumberUtils.stripSeparators(dest)

        if (dest.isEmpty()) {
            throw SmsException(EMPTY_DESTINATION_ADDRESS)
        }
        // Divide the input message by SMS length limit
        val smsManager = getSmsManager(subId)
        val messages = smsManager.divideMessage(body)
        if (messages == null || messages.size < 1) {
            throw SmsException(ERROR_SENDING_MESSAGE)
        }
        // Actually send the sms
        sendInternal(
            subId, dest, messages, serviceCenter, requireDeliveryReport, messageUri, gatewayOutboxId
        )
    }

    // Actually sending the message using SmsManager
    private fun sendInternal(
        subId: Int, dest: String,
        messages: ArrayList<String>, serviceCenter: String?,
        requireDeliveryReport: Boolean, messageUri: Uri, gatewayOutboxId: String?
    ) {
        val smsManager = getSmsManager(subId)
        val messageCount = messages.size
        val deliveryIntents = ArrayList<PendingIntent?>(messageCount)
        val sentIntents = ArrayList<PendingIntent>(messageCount)

        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (isSPlus()) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }

        for (i in 0 until messageCount) {
            // Make pending intents different for each message part
            val partId = if (messageCount <= 1) 0 else i + 1
            if (requireDeliveryReport && i == messageCount - 1) {
                deliveryIntents.add(
                    PendingIntent.getBroadcast(
                        app,
                        partId,
                        getDeliveredStatusIntent(messageUri, subId, gatewayOutboxId),
                        flags
                    )
                )
            } else {
                deliveryIntents.add(null)
            }
            sentIntents.add(
                PendingIntent.getBroadcast(
                    app,
                    partId,
                    getSendStatusIntent(messageUri, subId, gatewayOutboxId),
                    flags
                )
            )
        }
        try {
            if (sendMultipartSmsAsSeparateMessages) {
                // If multipart sms is not supported, send them as separate messages
                for (i in 0 until messageCount) {
                    smsManager.sendTextMessage(
                        dest,
                        serviceCenter,
                        messages[i],
                        sentIntents[i],
                        deliveryIntents[i]
                    )
                }
            } else {
                smsManager.sendMultipartTextMessage(
                    dest, serviceCenter, messages, sentIntents, deliveryIntents
                )
            }
        } catch (e: Exception) {
            throw SmsException(ERROR_SENDING_MESSAGE, e)
        }
    }

    private fun getSendStatusIntent(requestUri: Uri, subId: Int, gatewayOutboxId: String?): Intent {
        val intent = Intent(SendStatusReceiver.SMS_SENT_ACTION, requestUri, app, SmsStatusSentReceiver::class.java)
        intent.putExtra(SendStatusReceiver.EXTRA_SUB_ID, subId)
        gatewayOutboxId?.let { intent.putExtra(GatewayHooks.EXTRA_OUTBOX_ID, it) }
        return intent
    }

    private fun getDeliveredStatusIntent(requestUri: Uri, subId: Int, gatewayOutboxId: String?): Intent {
        val intent = Intent(SendStatusReceiver.SMS_DELIVERED_ACTION, requestUri, app, SmsStatusDeliveredReceiver::class.java)
        intent.putExtra(SendStatusReceiver.EXTRA_SUB_ID, subId)
        gatewayOutboxId?.let { intent.putExtra(GatewayHooks.EXTRA_OUTBOX_ID, it) }
        return intent
    }

    companion object {
        private var instance: SmsSender? = null
        fun getInstance(app: Application): SmsSender {
            if (instance == null) {
                instance = SmsSender(app)
            }
            return instance!!
        }
    }
}
