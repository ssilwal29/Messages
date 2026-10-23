package org.fossify.messages.gateway

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runs every 15 minutes with a network, and survives the service being killed. Each run restarts the service if
 * needed, catches up on received messages, drains the outbox once and retries confirmations. It drains under the
 * same lock as the service, so the two never send at the same time.
 */
class GatewayWatchdogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val gateway = Gateway.get(applicationContext)
        if (!gateway.settings.enabled) return Result.success()

        if (!GatewayService.running) GatewayService.start(applicationContext)
        await(gateway.requestScanAndUpload())
        gateway.drainOnce()
        gateway.guarded("watchdog confirmations") {
            gateway.processor.flush()
            gateway.processor.resolveStale()
        }
        return Result.success()
    }

    private fun await(future: java.util.concurrent.Future<*>) {
        try {
            future.get(WAIT_MINUTES, TimeUnit.MINUTES)
        } catch (_: TimeoutException) {
            // Still running on the gateway's own thread; it carries on without us.
        } catch (_: ExecutionException) {
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        const val NAME = "dude-gateway-watchdog"
        private const val WAIT_MINUTES = 5L
    }
}

/**
 * Starts the gateway service "when the app opens" without touching Fossify's activities: created at process start,
 * it watches for the first activity to come to the foreground (when starting a foreground service is allowed).
 */
class GatewayStartupProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (!GatewayService.running) GatewayService.start(activity.applicationContext)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ) = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
