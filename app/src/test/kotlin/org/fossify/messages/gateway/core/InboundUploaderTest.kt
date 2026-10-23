package org.fossify.messages.gateway.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InboundUploaderTest {
    private lateinit var fixture: ApiFixture
    private val store = InMemoryInboundStore()
    private val status = RecordingStatus()
    private val clock = FakeClock()
    private val features = FeatureGate(clock, status)
    private val reads = mutableListOf<String>()
    private lateinit var uploader: InboundUploader

    @Before
    fun setUp() {
        fixture = ApiFixture()
        val reader = AttachmentReader { record ->
            reads += record.key
            AttachmentReader.Result.Data(byteArrayOf(1, 2, 3))
        }
        uploader = InboundUploader(store, fixture.api, reader, features, status, clock, noJitter())
    }

    @After
    fun tearDown() = fixture.close()

    private fun enqueue(n: Long, body: String = "message $n", parts: List<Long> = emptyList(), receivedAt: Long = n) {
        val id = if (parts.isEmpty()) EventIds.sms(n, receivedAt) else EventIds.mms(n, receivedAt)
        val event = InboundEvent(
            id = id,
            chat = ChatRef(
                "42",
                participants = listOf(Participant("+15551234567", "Sarah"), Participant("+15557654321")),
            ),
            from = "+15551234567",
            fromName = "Sarah",
            body = body,
            type = if (parts.isEmpty()) TYPE_SMS else TYPE_MMS,
            receivedAt = rfc3339(receivedAt),
        )
        val attachments = parts.map {
            AttachmentRecord(EventIds.attachmentKey(id, it), id, "content://mms/part/$it", "image/jpeg", "IMG_$it.jpg")
        }
        store.enqueue(
            InboundRecord(
                id = id,
                payload = GatewayJson.encodeToString(InboundEvent.serializer(), event),
                sourceType = event.type,
                sourceId = n,
                threadId = 42,
                receivedAt = receivedAt,
                createdAt = 0,
            ),
            attachments
        )
    }

    private fun eventPosts() = fixture.routes.requestsTo("POST", "/v1/devices/events")

    private fun postedIds(index: Int) =
        GatewayJson.decodeFromString(EventsRequest.serializer(), eventPosts()[index].bodyText()).events.map { it.id }

    @Test
    fun `events go up oldest first in batches of at most 100`() {
        fixture.routes.on("POST", "/v1/devices/events") { json(200, """{"accepted":100}""") }
        (250L downTo 1L).forEach { enqueue(it) }

        assertEquals(InboundUploader.Result.IDLE, uploader.run())
        assertEquals(listOf(100, 100, 50), eventPosts().indices.map { postedIds(it).size })
        assertEquals(EventIds.sms(1, 1), postedIds(0).first())
        assertEquals(EventIds.sms(250, 250), postedIds(2).last())
        assertEquals(0, store.stats().pending)
    }

    @Test
    fun `wire format matches the contract`() {
        fixture.routes.on("POST", "/v1/devices/events") { json(200, """{"accepted":1}""") }
        enqueue(1)
        uploader.run()
        val body = eventPosts().single().bodyText()
        assertEquals(
            """{"events":[{"id":"sms:1:1","chat":{"id":"42","participants":""" +
                """[{"handle":"+15551234567","name":"Sarah"},""" +
                """"+15557654321"]},"from":"+15551234567","fromName":"Sarah","body":"message 1","type":"sms",""" +
                """"receivedAt":"1970-01-01T00:00:00.001Z"}]}""",
            body
        )
    }

    @Test
    fun `batches respect the 1 MB limit`() {
        fixture.routes.on("POST", "/v1/devices/events") { json(200) }
        (1L..30L).forEach { enqueue(it, body = "x".repeat(InboundUploader.MAX_BODY_CHARS * 5)) }
        uploader.run()
        assertTrue(eventPosts().size > 1)
        eventPosts().forEach { assertTrue(it.bodySize <= InboundUploader.MAX_BYTES) }
    }

    @Test
    fun `500 retries the same batch with backoff`() {
        var failures = 1
        fixture.routes.on("POST", "/v1/devices/events") { if (failures-- > 0) json(500) else json(200) }
        (1L..3L).forEach { enqueue(it) }

        assertEquals(InboundUploader.Result.WAITING, uploader.run())
        assertEquals(1, eventPosts().size)
        assertEquals(InboundUploader.Result.WAITING, uploader.run()) // backoff not over yet
        assertEquals(1, eventPosts().size)

        clock.advance(120_000)
        assertEquals(InboundUploader.Result.IDLE, uploader.run())
        assertEquals(postedIds(0), postedIds(1))
    }

    @Test
    fun `400 keeps the event, sets it aside and doesn't loop`() {
        fixture.routes.on("POST", "/v1/devices/events") { request ->
            if (request.bodyText().contains("sms:2:2")) json(400, """{"error":"body too odd"}""") else json(200)
        }
        (1L..3L).forEach { enqueue(it) }

        assertEquals(InboundUploader.Result.IDLE, uploader.run())
        assertEquals(InboundState.REJECTED, store.events[EventIds.sms(2, 2)]!!.state)
        assertEquals(InboundState.UPLOADED, store.events[EventIds.sms(1, 1)]!!.state)
        assertEquals(InboundState.UPLOADED, store.events[EventIds.sms(3, 3)]!!.state)
        assertTrue(status.errors.any { it.contains("body too odd") })

        val posts = eventPosts().size
        clock.advance(3_600_000)
        uploader.run()
        assertEquals(posts, eventPosts().size)
    }

    @Test
    fun `attachments are uploaded before the event that refers to them`() {
        fixture.routes.on("PUT", "/v1/devices/attachments/") { json(201) }
        fixture.routes.on("POST", "/v1/devices/events") { json(200) }
        enqueue(812, body = "look", parts = listOf(3, 4), receivedAt = 1_729_600_000_000)

        uploader.run()
        val order = fixture.routes.requests.map { "${it.method} ${it.url.encodedPath}" }
        assertEquals(
            listOf(
                "PUT /v1/devices/attachments/mms%3A812%3A1729600000000%3Apart%3A3",
                "PUT /v1/devices/attachments/mms%3A812%3A1729600000000%3Apart%3A4",
                "POST /v1/devices/events",
            ),
            order
        )
        val put = fixture.routes.requests.first()
        assertEquals("image/jpeg", put.headers["Content-Type"])
        assertEquals(3L, put.bodySize)
        val request = GatewayJson.decodeFromString(EventsRequest.serializer(), eventPosts().single().bodyText())
        val event = request.events.single()
        assertEquals(
            listOf("mms:812:1729600000000:part:3", "mms:812:1729600000000:part:4"),
            event.attachments!!.map { it.key }
        )
    }

    @Test
    fun `attachment route missing - text is reported without attachments, text-less events wait`() {
        fixture.routes.on("PUT", "/v1/devices/attachments/") { json(404) }
        fixture.routes.on("POST", "/v1/devices/events") { json(200) }
        enqueue(1, body = "", parts = listOf(10))
        enqueue(2, body = "with a photo", parts = listOf(20))
        enqueue(3, body = "plain")

        assertEquals(InboundUploader.Result.IDLE, uploader.run())
        assertEquals(false, status.features[Feature.ATTACHMENTS])
        val sent = GatewayJson.decodeFromString(EventsRequest.serializer(), eventPosts().single().bodyText()).events
        assertEquals(listOf(EventIds.mms(2, 2), EventIds.sms(3, 3)), sent.map { it.id })
        assertNull(sent.first().attachments)
        assertEquals(InboundState.PENDING, store.events[EventIds.mms(1, 1)]!!.state)

        // Within the hour nothing is retried.
        val puts = fixture.routes.requestsTo("PUT", "/v1/devices/attachments/").size
        uploader.run()
        assertEquals(puts, fixture.routes.requestsTo("PUT", "/v1/devices/attachments/").size)

        // An hour later the server has shipped the route: the waiting event goes up with its attachment.
        fixture.routes.on("PUT", "/v1/devices/attachments/") { json(200) }
        clock.advance(FeatureGate.RETRY_AFTER_MILLIS)
        uploader.run()
        assertEquals(true, status.features[Feature.ATTACHMENTS])
        assertEquals(InboundState.UPLOADED, store.events[EventIds.mms(1, 1)]!!.state)
        val last = GatewayJson.decodeFromString(EventsRequest.serializer(), eventPosts().last().bodyText()).events
        assertEquals(EventIds.attachmentKey(EventIds.mms(1, 1), 10), last.single().attachments!!.single().key)
    }

    @Test
    fun `failed attachment upload holds back later events to keep order`() {
        fixture.routes.on("PUT", "/v1/devices/attachments/") { json(503) }
        fixture.routes.on("POST", "/v1/devices/events") { json(200) }
        enqueue(1, body = "photo", parts = listOf(10))
        enqueue(2)

        assertEquals(InboundUploader.Result.WAITING, uploader.run())
        assertTrue(eventPosts().isEmpty())
    }

    @Test
    fun `401 stops uploads`() {
        fixture.routes.on("POST", "/v1/devices/events") { json(401) }
        enqueue(1)
        assertEquals(InboundUploader.Result.STOPPED, uploader.run())
        assertEquals(InboundUploader.Result.STOPPED, uploader.run())
        assertEquals(1, eventPosts().size)
    }
}
