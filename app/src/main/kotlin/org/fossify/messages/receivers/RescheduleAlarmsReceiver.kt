package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.rescheduleAllScheduledMessages
import org.fossify.messages.gateway.GatewayHooks

/**
 * Reschedules alarms after boot/package updates, and starts the Dude Gateway service.
 */
class RescheduleAlarmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        ensureBackgroundThread {
            context.rescheduleAllScheduledMessages()
            GatewayHooks.onBootOrUpdate(context, intent.action)
            pendingResult.finish()
        }
    }
}
