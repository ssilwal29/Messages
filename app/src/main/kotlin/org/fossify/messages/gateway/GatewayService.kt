package org.fossify.messages.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import org.fossify.messages.R
import org.fossify.messages.gateway.core.Doorbell
import org.fossify.messages.gateway.core.DoorbellState
import org.fossify.messages.gateway.core.Feature
import org.fossify.messages.gateway.core.GatewayApi
import org.fossify.messages.gateway.core.OutboxDrainer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps the gateway running: the doorbell, the outbox drains (or the long-poll fallback), uploads, heartbeat and
 * thread list, and a persistent notification with the connection state.
 *
 * Foreground service type `remoteMessaging`: the service relays text messages between this phone and the
 * TheDude server, and the type has no daily runtime limit (unlike `dataSync`, capped at 6 hours a day on
 * Android 15+) and may be started from BOOT_COMPLETED.
 */
@Suppress("TooManyFunctions")
class GatewayService : Service() {
    private lateinit var gateway: Gateway
    private lateinit var timers: ScheduledExecutorService
    private lateinit var doorbellExecutor: ScheduledExecutorService
    private lateinit var doorbell: Doorbell
    private var started = false

    @Volatile
    private var destroyed = false

    @Volatile
    private var longPoll: Thread? = null

    @Volatile
    private var knownNetwork: Network? = null
    private var pendingScan: ScheduledFuture<*>? = null
    private var pendingNotification: ScheduledFuture<*>? = null

    private val statusListener: () -> Unit = {
        // A 401 on any route stops the stream too, not just the next request.
        if (gateway.status.tokenRejected && gateway.api.isStopped) doorbell.stop()
        updateNotificationSoon()
    }

    private val doorbellListener = object : Doorbell.Listener {
        override fun onReady() {
            gateway.requestDrain()
        }

        override fun onStateChanged(state: DoorbellState) {
            gateway.status.doorbell = state
        }

        override fun onFallbackChanged(useLongPoll: Boolean) {
            gateway.status.longPolling = useLongPoll
            if (useLongPoll) startLongPoll()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        gateway = Gateway.get(this)
        createChannel()
        startInForeground()
        if (!gateway.settings.enabled) {
            stopSelf()
            return
        }
        started = true
        running = true
        // Left over if the process died mid-fallback.
        gateway.status.longPolling = false
        gateway.status.doorbell = DoorbellState.STOPPED

        timers = Executors.newSingleThreadScheduledExecutor { Thread(it, "gateway-timers") }
        doorbellExecutor = Executors.newSingleThreadScheduledExecutor { Thread(it, "gateway-doorbell") }
        doorbell = Doorbell(gateway.api, doorbellExecutor, doorbellListener)

        gateway.status.addListener(statusListener)
        registerNetworkCallback()
        registerSystemReceivers()
        contentResolver.registerContentObserver(MMS_SMS_URI, true, providerObserver)
        gateway.scheduleWatchdog()

        doorbell.start()
        gateway.requestScanAndUpload()
        gateway.requestDrain()
        gateway.requestFlush()
        gateway.requestThreads()

        every(0, TICK_SECONDS, TimeUnit.SECONDS, "tick", ::tick)
        every(FLUSH_SECONDS, FLUSH_SECONDS, TimeUnit.SECONDS, "flush") { gateway.requestFlush() }
        every(THREADS_CHECK_MINUTES, THREADS_CHECK_MINUTES, TimeUnit.MINUTES, "thread list", ::checkThreads)
        every(1, PRUNE_HOURS, TimeUnit.HOURS, "prune") { gateway.prune() }
    }

    /** A periodic task that keeps running after a failure (a scheduled task that throws is never run again). */
    private fun every(initialDelay: Long, period: Long, unit: TimeUnit, what: String, task: () -> Unit) {
        timers.scheduleWithFixedDelay({ gateway.guarded(what, task) }, initialDelay, period, unit)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started && intent?.action == ACTION_CONFIG_CHANGED) {
            doorbell.stop()
            doorbell.start()
            gateway.requestDrain()
        }
        return if (started) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        if (started) {
            running = false
            doorbell.stop()
            gateway.status.longPolling = false
            gateway.status.removeListener(statusListener)
            val connectivity = getSystemService(ConnectivityManager::class.java)
            runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
            runCatching { unregisterReceiver(systemReceiver) }
            contentResolver.unregisterContentObserver(providerObserver)
            timers.shutdownNow()
            doorbellExecutor.shutdown()
        }
        super.onDestroy()
    }

    /** Every minute: heartbeat, retry uploads that were waiting, notice a lost default-SMS role. */
    private fun tick() {
        gateway.heartbeat()
        gateway.requestUpload()
        updateNotificationSoon()
    }

    private fun checkThreads() {
        val missing = gateway.features.isUnavailable(Feature.THREADS)
        val due = if (missing) {
            gateway.features.shouldTry(Feature.THREADS)
        } else {
            gateway.threadsDue(System.currentTimeMillis())
        }
        if (due) gateway.requestThreads()
    }

    /** While the stream can't stay up: `GET /outbox?wait=20` in a loop until it's back. */
    private fun startLongPoll() {
        if (longPoll?.isAlive == true) return
        longPoll = Thread({
            while (!destroyed && gateway.status.longPolling) {
                val startedAt = System.currentTimeMillis()
                val pause = when (gateway.drainOnce(GatewayApi.LONG_POLL_WAIT_SECONDS)) {
                    OutboxDrainer.Result.EMPTY ->
                        // A server that ignores `wait` answers at once; don't spin on it.
                        if (System.currentTimeMillis() - startedAt < MIN_POLL_MILLIS) POLL_ERROR_PAUSE_MILLIS else 0L

                    null -> POLL_BUSY_PAUSE_MILLIS
                    OutboxDrainer.Result.BLOCKED, OutboxDrainer.Result.STOPPED -> POLL_BLOCKED_PAUSE_MILLIS
                    OutboxDrainer.Result.ERROR, OutboxDrainer.Result.STUCK -> POLL_ERROR_PAUSE_MILLIS
                }
                if (pause > 0) {
                    try {
                        Thread.sleep(pause)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }
        }, "gateway-longpoll").apply { start() }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (network == knownNetwork) return
            knownNetwork = network
            doorbell.onNetworkAvailable()
            onConnectivityChanged()
        }

        override fun onLost(network: Network) {
            if (network == knownNetwork) knownNetwork = null
            onConnectivityChanged()
        }
    }

    private fun registerNetworkCallback() {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        knownNetwork = connectivity.activeNetwork
        connectivity.registerDefaultNetworkCallback(networkCallback)
    }

    private fun onConnectivityChanged() {
        if (destroyed) return
        timers.execute {
            gateway.heartbeat()
            gateway.requestUpload()
            gateway.requestFlush()
        }
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (destroyed) return
            timers.execute {
                gateway.heartbeat()
                // Back to being the default SMS app: send what queued up meanwhile.
                if (gateway.device.isDefaultSmsApp()) gateway.requestDrain()
                updateNotificationSoon()
            }
        }
    }

    private fun registerSystemReceivers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED)
        }
        ContextCompat.registerReceiver(this, systemReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Anything written to the SMS/MMS provider: catch up a few seconds later, once writes settle. */
    private val providerObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (destroyed) return
            pendingScan?.cancel(false)
            pendingScan = timers.schedule({ gateway.requestScanAndUpload() }, SCAN_DEBOUNCE_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.gateway_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(stateText()), type)
    }

    private fun updateNotificationSoon() {
        if (destroyed || !started) return
        pendingNotification?.cancel(false)
        pendingNotification = timers.schedule({
            val stats = runCatching { gateway.queueStats() }.getOrNull()
            val queue = stats?.let { getString(R.string.gateway_notification_queue, it.inbound, it.outbound) }
            val text = stateText() + queue.orEmpty()
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
        }, NOTIFICATION_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun stateText(): String {
        val status = gateway.status
        return when {
            !gateway.device.isDefaultSmsApp() -> getString(R.string.gateway_state_not_default_sms)
            status.tokenRejected -> getString(R.string.gateway_state_token_rejected)
            status.longPolling -> getString(R.string.gateway_state_long_polling)
            else -> getString(doorbellLabel(status.doorbell))
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, GatewayActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(getString(R.string.gateway_app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val ACTION_CONFIG_CHANGED = "org.fossify.messages.gateway.CONFIG_CHANGED"
        private const val CHANNEL_ID = "dude_gateway_service"
        private const val NOTIFICATION_ID = 0x6A7E
        private const val TICK_SECONDS = 60L
        private const val FLUSH_SECONDS = 30L
        private const val THREADS_CHECK_MINUTES = 15L
        private const val PRUNE_HOURS = 24L
        private const val SCAN_DEBOUNCE_SECONDS = 3L
        private const val NOTIFICATION_DEBOUNCE_MILLIS = 500L
        private const val MIN_POLL_MILLIS = 2_000L
        private const val POLL_BUSY_PAUSE_MILLIS = 1_000L
        private const val POLL_ERROR_PAUSE_MILLIS = 5_000L
        private const val POLL_BLOCKED_PAUSE_MILLIS = 30_000L
        private val MMS_SMS_URI = "content://mms-sms/".toUri()

        @Volatile
        var running = false
            private set

        fun doorbellLabel(state: DoorbellState) = when (state) {
            DoorbellState.CONNECTED -> R.string.gateway_doorbell_connected
            DoorbellState.CONNECTING, DoorbellState.RECONNECTING -> R.string.gateway_doorbell_reconnecting
            DoorbellState.TOKEN_REJECTED -> R.string.gateway_state_token_rejected
            DoorbellState.STOPPED -> R.string.gateway_doorbell_stopped
        }

        /** Starts the service if the gateway is on. From the background this needs the battery exemption. */
        fun start(context: Context, action: String? = null) {
            if (!GatewaySettings.isEnabled(context)) return
            val intent = Intent(context, GatewayService::class.java).setAction(action)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: started from the background without an exemption.
                Gateway.get(context).status.recordError(
                    "couldn't start the service from the background (${e.javaClass.simpleName}); " +
                        "open the app or allow it to ignore battery optimisation"
                )
            } catch (e: SecurityException) {
                Gateway.get(context).status.recordError("couldn't start the service: ${e.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GatewayService::class.java))
        }
    }
}
