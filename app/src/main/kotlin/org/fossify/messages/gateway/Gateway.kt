package org.fossify.messages.gateway

import android.content.Context
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import okhttp3.OkHttpClient
import org.fossify.messages.BuildConfig
import org.fossify.messages.gateway.core.AttachmentReader
import org.fossify.messages.gateway.core.Clock
import org.fossify.messages.gateway.core.DrainCoordinator
import org.fossify.messages.gateway.core.FeatureGate
import org.fossify.messages.gateway.core.GatewayApi
import org.fossify.messages.gateway.core.HandleNormalizer
import org.fossify.messages.gateway.core.InboundUploader
import org.fossify.messages.gateway.core.OutboundProcessor
import org.fossify.messages.gateway.core.OutboxDrainer
import org.fossify.messages.gateway.core.OwnNumbers
import org.fossify.messages.gateway.core.QueueStats
import org.fossify.messages.gateway.core.Reporters
import org.fossify.messages.gateway.core.ServerConfig
import org.fossify.messages.gateway.core.TYPE_MMS
import org.fossify.messages.gateway.core.TYPE_SMS
import org.fossify.messages.gateway.data.GatewayDatabase
import org.fossify.messages.gateway.data.RoomInboundStore
import org.fossify.messages.gateway.data.RoomOutboundStore
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Process-wide wiring for the gateway. The service, the watchdog and the receivers all go through this one
 * instance, so they share one database, one HTTP client and one drain lock.
 */
@Suppress("TooManyFunctions")
class Gateway private constructor(context: Context) {
    val app: Context = context.applicationContext
    val settings = GatewaySettings(app)
    val vault = TokenVault(app)
    val status = GatewayStatus(app)
    val device = DeviceStatus(app)
    private val db = GatewayDatabase.get(app)
    val inboundStore = RoomInboundStore(db)
    val outboundStore = RoomOutboundStore(db)
    val reader = ProviderReader(app)
    val features = FeatureGate(Clock.SYSTEM, status)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    val api = GatewayApi(httpClient, ::serverConfig, onUnauthorized = {
        status.tokenRejected = true
        status.recordError("the server rejected the token (401); enter a new one")
    })

    val sender = GatewaySender(app, settings, reader, ::handles)
    val processor = OutboundProcessor(outboundStore, api, sender, ::handles, status)
    val drainer = OutboxDrainer(api, processor, status)

    /** Shared by the service and the watchdog, so they never send at the same time. */
    val drainLock = DrainCoordinator()

    val reporters = Reporters(api, features, status)
    val events = InboundEventFactory(app, reader, ::handles, status)
    val scanner = CatchUpScanner(settings, reader, events, inboundStore, onNewGroupThread = { requestThreads() })

    val uploader = InboundUploader(
        store = inboundStore,
        api = api,
        reader = ::readAttachment,
        features = features,
        status = status,
        onUploaded = { records ->
            settings.advanceCursors(
                sms = records.filter { it.sourceType == TYPE_SMS }.maxOfOrNull { it.sourceId },
                mms = records.filter { it.sourceType == TYPE_MMS }.maxOfOrNull { it.sourceId },
            )
        },
    )

    /** Provider reads and uploads, in order. */
    private val io: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "gateway-io") }

    /** Outbox drains and confirmations. */
    private val outbound: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "gateway-outbound") }

    @Volatile
    private var handlesCache: Pair<String, Handles>? = null

    init {
        // A 401 stops all traffic until a new token is entered, even across restarts.
        if (status.tokenRejected) api.halt()
    }

    fun serverConfig(): ServerConfig? {
        val url = GatewayApi.parseBaseUrl(settings.baseUrl, allowLocalHttp = BuildConfig.DEBUG).getOrNull()
            ?: return null
        val token = vault.get() ?: return null
        return ServerConfig(url, token)
    }

    /** The normaliser for the SIM's country and the operator's own numbers, rebuilt when either changes. */
    fun handles(): Handles {
        val region = device.simCountry(settings.subscriptionId).orEmpty()
        val numbers = settings.ownNumbers
        val key = region + "|" + numbers.joinToString(",")
        handlesCache?.takeIf { it.first == key }?.let { return it.second }
        val normalizer = HandleNormalizer(region)
        return (normalizer to OwnNumbers(numbers, normalizer)).also { handlesCache = key to it }
    }

    private fun readAttachment(record: org.fossify.messages.gateway.core.AttachmentRecord): AttachmentReader.Result =
        try {
            app.contentResolver.openInputStream(record.partUri.toUri())?.use { input ->
                val bytes = input.readNBytesCompat(MAX_ATTACHMENT_BYTES + 1)
                if (bytes.size > MAX_ATTACHMENT_BYTES) {
                    AttachmentReader.Result.TooLarge
                } else {
                    AttachmentReader.Result.Data(bytes)
                }
            } ?: AttachmentReader.Result.Missing
        } catch (_: FileNotFoundException) {
            AttachmentReader.Result.Missing
        } catch (_: IOException) {
            AttachmentReader.Result.Missing
        } catch (_: SecurityException) {
            AttachmentReader.Result.Missing
        }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    fun queueStats(): QueueStats {
        val inbound = inboundStore.stats()
        val oldest = inbound.oldestPendingReceivedAt
        val age = if (oldest == null) 0 else ((System.currentTimeMillis() - oldest) / MILLIS).coerceAtLeast(0)
        return QueueStats(inbound.pending, outboundStore.activeCount(), age)
    }

    /** Scan the provider, then upload. Returns a future the watchdog can wait on. */
    fun requestScanAndUpload(): Future<*> = io.submit {
        guarded("catch-up") {
            scanner.scan()
            uploader.run()
        }
    }

    fun requestUpload(): Future<*> = io.submit { guarded("upload") { uploader.run() } }

    /** Fetch the outbox until it's empty. If a drain is already running it goes round once more instead. */
    fun requestDrain(): Future<*> = outbound.submit { drainOnce() }

    /** Runs a drain on the calling thread, under the shared lock. Null if another drain ran it instead. */
    fun drainOnce(waitSeconds: Int? = null): OutboxDrainer.Result? {
        var result: OutboxDrainer.Result? = null
        guarded("outbox") { drainLock.request { result = drainer.drain(waitSeconds) } }
        return result
    }

    fun requestFlush(): Future<*> = outbound.submit {
        guarded("confirmations") {
            processor.flush()
            processor.resolveStale()
        }
    }

    fun heartbeat(force: Boolean = false) = guarded("heartbeat") {
        val failures = status.mmsDownloadFailuresSinceHeartbeat
        val outcome = reporters.heartbeat({
            device.heartbeat(settings.subscriptionId, queueStats(), failures, status.lastError)
        }, force)
        if (outcome == Reporters.Outcome.SENT) status.onHeartbeat(System.currentTimeMillis(), failures)
    }

    fun requestThreads(force: Boolean = false): Future<*> = io.submit { sendThreads(force) }

    private fun sendThreads(force: Boolean) = guarded("thread list") {
        val snapshot = events.threadsSnapshot()
        if (reporters.threads({ snapshot }, force) == Reporters.Outcome.SENT) {
            settings.lastThreadsSentAt = System.currentTimeMillis()
            settings.knownGroupThreads = snapshot.threads.map { it.id }.toSet()
        }
    }

    /** True when the thread list is due: every 6 hours, or hourly while the route is missing. */
    fun threadsDue(now: Long) = now - settings.lastThreadsSentAt >= THREADS_INTERVAL_MILLIS

    /** After the operator saves the form. Only a new token lifts a 401 stop. */
    fun onConfigChanged(newToken: Boolean) {
        handlesCache = null
        if (newToken) {
            api.onNewToken()
            status.tokenRejected = false
        }
    }

    /** Turning the gateway on reports messages from now on only, never the phone's history. */
    fun enable() {
        if (!settings.enabled) {
            settings.enabledAt = System.currentTimeMillis()
            settings.smsCursor = reader.maxSmsId()
            settings.mmsCursor = reader.maxMmsId()
            settings.knownGroupThreads = emptySet()
            settings.lastThreadsSentAt = 0
        }
        settings.enabled = true
        scheduleWatchdog()
        GatewayService.start(app)
    }

    fun disable() {
        settings.enabled = false
        WorkManager.getInstance(app).cancelUniqueWork(GatewayWatchdogWorker.NAME)
        GatewayService.stop(app)
    }

    fun scheduleWatchdog() {
        val request = PeriodicWorkRequestBuilder<GatewayWatchdogWorker>(WATCHDOG_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(app)
            .enqueueUniquePeriodicWork(GatewayWatchdogWorker.NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Drops settled rows after two weeks; the catch-up scanner only looks back two days. */
    fun prune() = guarded("prune") {
        val before = System.currentTimeMillis() - RETENTION_MILLIS
        inboundStore.prune(before)
        outboundStore.prune(before)
    }

    /** Never let the gateway take down a Fossify receiver or the service thread. */
    fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            status.recordError("$what: ${e.javaClass.simpleName}")
            GatewayLog.e("$what failed", e)
        }
    }

    companion object {
        const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024
        const val WATCHDOG_MINUTES = 15L
        const val THREADS_INTERVAL_MILLIS = 6 * 60 * 60 * 1000L
        private const val RETENTION_MILLIS = 14 * 24 * 60 * 60 * 1000L
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val WRITE_TIMEOUT_SECONDS = 60L
        private const val BUFFER_BYTES = 64 * 1024
        private const val MILLIS = 1000L

        @Volatile
        private var instance: Gateway? = null

        fun get(context: Context): Gateway = instance ?: synchronized(this) {
            instance ?: Gateway(context).also { instance = it }
        }
    }
}
