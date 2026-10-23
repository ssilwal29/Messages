package org.fossify.messages.gateway.core

import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class DoorbellTest {
    private lateinit var fixture: ApiFixture
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val events = LinkedBlockingQueue<String>()
    private val states = CopyOnWriteArrayList<DoorbellState>()
    private val fallback = CopyOnWriteArrayList<Boolean>()
    private lateinit var doorbell: Doorbell

    private val listener = object : Doorbell.Listener {
        override fun onReady() {
            events += "ready"
        }

        override fun onStateChanged(state: DoorbellState) {
            states += state
            events += "state:$state"
        }

        override fun onFallbackChanged(useLongPoll: Boolean) {
            fallback += useLongPoll
            events += "fallback:$useLongPoll"
        }
    }

    @Before
    fun setUp() {
        fixture = ApiFixture()
        // Tiny backoff so reconnects happen quickly in the test.
        doorbell = Doorbell(fixture.api, executor, listener, backoff = Backoff(10, 50, Random(1)))
    }

    @After
    fun tearDown() {
        doorbell.stop()
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)
        fixture.close()
    }

    private fun sse(body: String): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/event-stream")
        .body(body)
        .build()

    private fun awaitEvent(expected: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (events.poll(100, TimeUnit.MILLISECONDS) == expected) return
        }
        throw AssertionError("never saw $expected; states=$states")
    }

    @Test
    fun `ready events ring and the stream reconnects after a drop`() {
        val connections = AtomicInteger()
        fixture.routes.on("GET", "/v1/devices/signals") {
            connections.incrementAndGet()
            // The server says "ready", sends a comment, then the connection drops.
            sse("event: ready\ndata: {}\n\n: still here\n\n")
        }
        doorbell.start()

        awaitEvent("ready")
        awaitEvent("ready") // reconnected and rang again
        assertTrue(connections.get() >= 2)
        val request = fixture.routes.requestsTo("GET", "/v1/devices/signals").first()
        assertEquals("Bearer $TOKEN", request.headers["Authorization"])
        assertEquals("text/event-stream", request.headers["Accept"])
        assertEquals(null, request.headers["Origin"])
        assertTrue(states.contains(DoorbellState.CONNECTED))
        assertTrue(states.contains(DoorbellState.RECONNECTING))
    }

    @Test
    fun `stream that can't stay up switches to long polling until it opens again`() {
        val failing = AtomicInteger(4)
        fixture.routes.on("GET", "/v1/devices/signals") {
            if (failing.getAndDecrement() > 0) json(503) else sse("event: ready\ndata: {}\n\n")
        }
        doorbell.start()

        awaitEvent("fallback:true")
        awaitEvent("fallback:false")
        awaitEvent("ready")
    }

    @Test
    fun `401 rejects the token and stops reconnecting`() {
        fixture.routes.on("GET", "/v1/devices/signals") { json(401) }
        doorbell.start()

        awaitEvent("state:TOKEN_REJECTED")
        assertTrue(fixture.api.isStopped)
        Thread.sleep(300)
        assertEquals(1, fixture.routes.requestsTo("GET", "/v1/devices/signals").size)
    }

    @Test
    fun `network coming back reconnects at once`() {
        fixture.routes.on("GET", "/v1/devices/signals") {
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(": still here\n\n")
                .bodyDelay(30, TimeUnit.SECONDS)
                .build()
        }
        doorbell.start()
        awaitEvent("state:CONNECTED")
        doorbell.onNetworkAvailable()
        awaitEvent("state:CONNECTED")
        assertEquals(2, fixture.routes.requestsTo("GET", "/v1/devices/signals").size)
    }
}

class ReportersTest {
    private val clock = FakeClock()
    private val status = RecordingStatus()

    private fun heartbeat() = Heartbeat(
        appVersion = "1.0.0",
        defaultSmsApp = true,
        airplaneMode = false,
        network = "wifi",
        signal = null,
        battery = Battery(82, true),
        queue = QueueStats(0, 0, 0),
        mmsDownloadFailures = 0,
        lastError = null,
    )

    @Test
    fun `heartbeat sends explicit nulls`() {
        ApiFixture().use { fixture ->
            fixture.routes.on("POST", "/v1/devices/heartbeat") { json(200) }
            val reporters = Reporters(fixture.api, FeatureGate(clock, status), status)
            assertEquals(Reporters.Outcome.SENT, reporters.heartbeat(::heartbeat))
            assertEquals(
                """{"appVersion":"1.0.0","defaultSmsApp":true,"airplaneMode":false,"network":"wifi","signal":null,""" +
                    """"battery":{"percent":82,"charging":true},"queue":{"inbound":0,"outbound":0,""" +
                    """"oldestInboundSeconds":0},"mmsDownloadFailures":0,"lastError":null}""",
                fixture.routes.requests.single().bodyText()
            )
        }
    }

    @Test
    fun `404 turns heartbeat and thread list off for an hour`() {
        ApiFixture().use { fixture ->
            fixture.routes.on("POST", "/v1/devices/heartbeat") { json(404) }
            fixture.routes.on("PUT", "/v1/devices/threads") { json(404) }
            val features = FeatureGate(clock, status)
            val reporters = Reporters(fixture.api, features, status)
            val thread = ThreadInfo("42", "Weekend crew", listOf(Participant("+15551234567")), rfc3339(0))
            val threads = { ThreadsSnapshot(listOf(thread)) }

            assertEquals(Reporters.Outcome.UNSUPPORTED, reporters.heartbeat(::heartbeat))
            assertEquals(Reporters.Outcome.UNSUPPORTED, reporters.threads(threads))
            assertEquals(false, status.features[Feature.HEARTBEAT])
            assertEquals(Reporters.Outcome.SKIPPED, reporters.heartbeat(::heartbeat))
            assertEquals(Reporters.Outcome.SKIPPED, reporters.threads(threads))
            assertEquals(2, fixture.routes.requests.size)

            fixture.routes.on("POST", "/v1/devices/heartbeat") { json(200) }
            clock.advance(FeatureGate.RETRY_AFTER_MILLIS)
            assertEquals(Reporters.Outcome.SENT, reporters.heartbeat(::heartbeat))
            assertEquals(true, status.features[Feature.HEARTBEAT])
        }
    }
}

class BaseUrlTest {
    @Test
    fun `release builds require https`() {
        assertTrue(GatewayApi.parseBaseUrl("https://dude.example.com", allowLocalHttp = false).isSuccess)
        assertTrue(GatewayApi.parseBaseUrl("http://dude.example.com", allowLocalHttp = false).isFailure)
        assertTrue(GatewayApi.parseBaseUrl("http://localhost:8070", allowLocalHttp = false).isFailure)
    }

    @Test
    fun `debug builds may use http to localhost only`() {
        assertTrue(GatewayApi.parseBaseUrl("http://localhost:8070", allowLocalHttp = true).isSuccess)
        assertTrue(GatewayApi.parseBaseUrl("http://127.0.0.1:8070/", allowLocalHttp = true).isSuccess)
        assertTrue(GatewayApi.parseBaseUrl("http://10.0.2.2:8070", allowLocalHttp = true).isFailure)
    }

    @Test
    fun `token never goes in the url`() {
        assertTrue(GatewayApi.parseBaseUrl("https://dude.example.com/?token=hd_x", allowLocalHttp = false).isFailure)
        assertTrue(GatewayApi.parseBaseUrl("https://hd_x@dude.example.com", allowLocalHttp = false).isFailure)
    }

    @Test
    fun `base url with a path prefix keeps it`() {
        ApiFixture().use { fixture ->
            val base = fixture.server.url("/dude/")
            val api = GatewayApi(okhttp3.OkHttpClient(), { ServerConfig(base, TOKEN) })
            fixture.routes.on("GET", "/dude/v1/devices/outbox") { json(200, """{"messages":[]}""") }
            assertTrue(api.fetchOutbox() is ApiResult.Ok)
            assertEquals("/dude/v1/devices/outbox", fixture.routes.requests.single().url.encodedPath)
        }
    }

    @Test
    fun `server config never prints the token`() {
        val config = ServerConfig(GatewayApi.parseBaseUrl("https://x.example", false).getOrThrow(), "hd_secret")
        assertTrue(!config.toString().contains("hd_secret"))
    }
}
