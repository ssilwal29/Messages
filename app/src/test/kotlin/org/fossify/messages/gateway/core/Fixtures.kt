package org.fossify.messages.gateway.core

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

const val TOKEN = "hd_test_token"

class FakeClock(start: Long = 1_760_000_000_000L) : Clock {
    private val time = AtomicLong(start)
    override fun now() = time.get()
    fun advance(millis: Long) = time.addAndGet(millis)
}

class RecordingStatus : StatusSink {
    val errors = CopyOnWriteArrayList<String>()
    var serverOutOfDate = 0
    var uploads = 0
    var sends = 0
    val features = ConcurrentHashMap<Feature, Boolean>()

    override fun recordError(message: String) {
        errors += message
    }

    override fun onServerOutOfDate() {
        serverOutOfDate++
    }

    override fun onUploaded(at: Long) {
        uploads++
    }

    override fun onSent(at: Long) {
        sends++
    }

    override fun onFeatureChanged(feature: Feature, available: Boolean, retryAt: Long) {
        features[feature] = available
    }
}

/** Answers by route; anything unrouted gets a 500. Records every request. */
class RouteDispatcher : Dispatcher() {
    private val routes = CopyOnWriteArrayList<Pair<(RecordedRequest) -> Boolean, (RecordedRequest) -> MockResponse>>()
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    fun on(method: String, pathPrefix: String, respond: (RecordedRequest) -> MockResponse) {
        val matches = { r: RecordedRequest -> r.method == method && r.url.encodedPath.startsWith(pathPrefix) }
        routes.add(0, matches to respond)
    }

    fun requestsTo(method: String, pathPrefix: String) = synchronized(requests) {
        requests.filter { it.method == method && it.url.encodedPath.startsWith(pathPrefix) }
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val route = routes.firstOrNull { it.first(request) } ?: return json(500, """{"error":"unrouted"}""")
        return route.second(request)
    }
}

fun json(code: Int, body: String = "{}"): MockResponse = MockResponse.Builder()
    .code(code)
    .addHeader("Content-Type", "application/json")
    .body(body)
    .build()

fun RecordedRequest.bodyText() = body?.utf8().orEmpty()

class ApiFixture(allowNullConfig: Boolean = false) : AutoCloseable {
    val server = MockWebServer()
    val routes = RouteDispatcher()
    var unauthorizedCalls = 0
    @Volatile
    var configured = !allowNullConfig

    init {
        server.dispatcher = routes
        server.start()
    }

    val api = GatewayApi(
        baseClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build(),
        config = { if (configured) ServerConfig(server.url("/"), TOKEN) else null },
        onUnauthorized = { unauthorizedCalls++ },
    )

    override fun close() = server.close()
}

class InMemoryOutboundStore : OutboundStore {
    val jobs = ConcurrentHashMap<String, OutboundJob>()

    override fun get(id: String) = jobs[id]

    @Synchronized
    override fun insert(job: OutboundJob): Boolean = jobs.putIfAbsent(job.id, job) == null

    @Synchronized
    override fun update(id: String, transform: (OutboundJob) -> OutboundJob?): OutboundJob? {
        val current = jobs[id] ?: return null
        val next = transform(current) ?: return null
        jobs[id] = next
        return next
    }

    override fun dueConfirmations(now: Long, limit: Int) =
        jobs.values.filter { it.confirm == ConfirmState.PENDING && it.nextConfirmAt <= now }.take(limit)

    override fun dueDeliveryReports(now: Long, limit: Int) = jobs.values.filter {
        it.delivery == DeliveryState.PENDING && it.confirm == ConfirmState.DONE && it.nextDeliveryAt <= now
    }.take(limit)

    override fun sendingSince(before: Long) =
        jobs.values.filter { it.state == OutboundState.SENDING && it.sendingSince <= before }

    override fun activeCount() = jobs.values.count {
        !it.state.isDispatched || it.state == OutboundState.SENDING || it.confirm == ConfirmState.PENDING
    }

    override fun requeueRejectedConfirmations(): Int {
        var count = 0
        jobs.replaceAll { _, job ->
            if (job.confirm == ConfirmState.REJECTED) {
                count++
                job.copy(confirm = ConfirmState.PENDING, nextConfirmAt = 0)
            } else {
                job
            }
        }
        return count
    }
}

class InMemoryInboundStore : InboundStore {
    val events = ConcurrentHashMap<String, InboundRecord>()
    val parts = ConcurrentHashMap<String, AttachmentRecord>()

    @Synchronized
    override fun enqueue(event: InboundRecord, attachments: List<AttachmentRecord>): Boolean {
        if (events.putIfAbsent(event.id, event) != null) return false
        attachments.forEach { parts[it.key] = it }
        return true
    }

    override fun contains(id: String) = events.containsKey(id)

    override fun pending(limit: Int) = events.values
        .filter { it.state == InboundState.PENDING }
        .sortedWith(compareBy({ it.receivedAt }, { it.sourceId }))
        .take(limit)

    override fun attachments(eventId: String) = parts.values.filter { it.eventId == eventId }.sortedBy { it.key }

    override fun updateAttachment(record: AttachmentRecord) {
        parts[record.key] = record
    }

    override fun markUploaded(ids: List<String>) = ids.forEach { id ->
        events.computeIfPresent(id) { _, e -> e.copy(state = InboundState.UPLOADED) }
    }

    override fun markRejected(id: String, error: String) {
        events.computeIfPresent(id) { _, e -> e.copy(state = InboundState.REJECTED, lastError = error) }
    }

    override fun reschedule(ids: List<String>, nextAttemptAt: Long, error: String) = ids.forEach { id ->
        events.computeIfPresent(id) { _, e ->
            e.copy(attempts = e.attempts + 1, nextAttemptAt = nextAttemptAt, lastError = error)
        }
    }

    override fun stats(): InboundStats {
        val pending = events.values.filter { it.state == InboundState.PENDING }
        return InboundStats(pending.size, pending.minOfOrNull { it.receivedAt })
    }

    override fun requeueRejected(): Int {
        val rejected = events.values.filter { it.state == InboundState.REJECTED }
        rejected.forEach { events[it.id] = it.copy(state = InboundState.PENDING, nextAttemptAt = 0) }
        return rejected.size
    }
}

/** Stands in for GatewaySender: a provider with threads, and a radio that records what it was asked to send. */
class FakeSender : OutboundSender {
    val threads = ConcurrentHashMap<String, List<String>>()
    val dispatched = CopyOnWriteArrayList<Pair<OutboundJob, PreparedSend>>()
    val probes = ConcurrentHashMap<String, ProviderProbe>()
    var blocked: String? = null
    var partsPerSms = 1
    var failPrepare: SendException? = null
    private var nextRow = 100

    override fun blockedReason() = blocked

    override fun threadAddresses(chatId: String) = threads[chatId]

    override fun prepare(job: OutboundJob, threadAddresses: List<String>): PreparedSend {
        failPrepare?.let { throw it }
        return if (job.type == TYPE_SMS) {
            PreparedSend("content://sms/${nextRow++}", partsPerSms, threadAddresses)
        } else {
            PreparedSend(null, 1, threadAddresses)
        }
    }

    override fun dispatch(job: OutboundJob, prepared: PreparedSend) {
        dispatched += job to prepared
    }

    override fun probe(providerUri: String) = probes[providerUri] ?: ProviderProbe.PENDING
}

val US = HandleNormalizer("US")

fun handlesFor(vararg own: String) = { US to OwnNumbers(own.toList(), US) }

fun noJitter() = Backoff(1_000, 60_000, Random(0))
