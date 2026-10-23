package org.fossify.messages.gateway.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OutboundTest {
    private lateinit var fixture: ApiFixture
    private val store = InMemoryOutboundStore()
    private val sender = FakeSender()
    private val status = RecordingStatus()
    private val clock = FakeClock()
    private lateinit var processor: OutboundProcessor
    private lateinit var drainer: OutboxDrainer

    private val group = listOf("+15551234567", "+15557654321")

    @Before
    fun setUp() {
        fixture = ApiFixture()
        processor = OutboundProcessor(store, fixture.api, sender, handlesFor("+15550000000"), status, clock, noJitter())
        drainer = OutboxDrainer(fixture.api, processor, status)
        sender.threads["42"] = listOf("5551234567", "(555) 765-4321")
        sender.threads["7"] = listOf("+15551234567")
        fixture.routes.on("POST", "/v1/devices/outbox/") { json(200) }
    }

    @After
    fun tearDown() = fixture.close()

    private fun mms(id: String, chat: String = "42") = OutboxMessage(id, chat, TYPE_MMS, group, "Saturday works!")

    private fun confirmations() = fixture.routes.requestsTo("POST", "/v1/devices/outbox/")
        .filterNot { it.url.encodedPath.endsWith("/delivery") }

    @Test
    fun `job is stored before sending and sent once`() {
        assertEquals(OutboundProcessor.Handled.DISPATCHED, processor.handle(mms("a")))
        assertEquals(OutboundState.SENDING, store.get("a")!!.state)
        assertEquals(1, sender.dispatched.size)
        // MMS goes to the thread's stored addresses, as one group message.
        assertEquals(listOf("5551234567", "(555) 765-4321"), sender.dispatched.single().second.destinations)
    }

    @Test
    fun `an id handed out again while sending is never sent again`() {
        processor.handle(mms("a"))
        // The 60 second hold runs out during a slow MMS and the server hands the id out again.
        clock.advance(61_000)
        assertEquals(OutboundProcessor.Handled.WAITING, processor.handle(mms("a")))
        assertEquals(1, sender.dispatched.size)
        assertTrue(confirmations().isEmpty())

        processor.onMmsSent("a", "content://mms/9", null)
        val sent = store.get("a")!!
        assertEquals(OutboundState.SENT, sent.state)
        assertEquals(ConfirmState.DONE, sent.confirm)
        assertEquals("""{"sent":true,"providerId":"content://mms/9"}""", confirmations().single().bodyText())

        // Handed out again after it was sent: confirm the known result again, don't send.
        assertEquals(OutboundProcessor.Handled.RECONFIRMED, processor.handle(mms("a")))
        assertEquals(1, sender.dispatched.size)
        assertEquals(2, confirmations().size)
    }

    @Test
    fun `confirm bodies carry no extra fields`() {
        processor.handle(OutboxMessage("ok", "7", TYPE_SMS, listOf("+15551234567"), "hi"))
        processor.onSmsPartSent("ok", null, null)
        processor.handle(OutboxMessage("bad", "7", TYPE_MMS, listOf("+15551234567"), "hi"))

        val bodies = confirmations().associate { it.url.pathSegments.last() to it.bodyText() }
        assertEquals("""{"sent":true,"providerId":"content://sms/100"}""", bodies["ok"])
        assertEquals(
            """{"sent":false,"error":"recipient check failed: type mms needs two or more recipients, got 1",""" +
                """"permanent":true}""",
            bodies["bad"]
        )
        confirmations().forEach {
            assertEquals("Bearer $TOKEN", it.headers["Authorization"])
            assertEquals(null, it.headers["Origin"])
            assertTrue(it.headers["Content-Type"]!!.startsWith("application/json"))
        }
    }

    @Test
    fun `recipient mismatch is a permanent failure and nothing is sent`() {
        sender.threads["42"] = listOf("+15551234567", "+15557654321", "+15559998888")
        assertEquals(OutboundProcessor.Handled.FAILED, processor.handle(mms("a")))
        assertTrue(sender.dispatched.isEmpty())
        val body = confirmations().single().bodyText()
        assertTrue(body.contains("\"permanent\":true"))
        assertTrue(body.contains("also in thread: +15559998888"))
    }

    @Test
    fun `message without type or recipients is left unconfirmed`() {
        assertEquals(
            OutboundProcessor.Handled.SKIPPED_SERVER_OUT_OF_DATE,
            processor.handle(OutboxMessage("old", "42", body = "hi"))
        )
        assertEquals(1, status.serverOutOfDate)
        assertEquals(null, store.get("old"))
        assertTrue(sender.dispatched.isEmpty())
        assertTrue(confirmations().isEmpty())
    }

    @Test
    fun `multipart sms is sent only when every part is ok`() {
        sender.partsPerSms = 3
        processor.handle(OutboxMessage("long", "7", TYPE_SMS, listOf("+15551234567"), "x".repeat(400)))
        processor.onSmsPartSent("long", null, null)
        processor.onSmsPartSent("long", null, SendError("SMS result 4 (no service)", permanent = false))
        assertEquals(OutboundState.SENDING, store.get("long")!!.state)
        processor.onSmsPartSent("long", null, null)

        val job = store.get("long")!!
        assertEquals(OutboundState.FAILED, job.state)
        assertEquals("""{"sent":false,"error":"SMS result 4 (no service)","permanent":false}""",
            confirmations().single().bodyText())
        // Two parts went out, so a server retry must not send it again.
        assertEquals(OutboundProcessor.Handled.RECONFIRMED, processor.handle(job.let {
            OutboxMessage(it.id, it.chat, it.type, it.recipients, it.body)
        }))
        assertEquals(1, sender.dispatched.size)
    }

    @Test
    fun `a failed id handed out again is never sent again, even after a transient failure`() {
        processor.handle(mms("a"))
        // A transient code doesn't prove nothing went out: an MMSC 5xx can come after delivery.
        val error = SendError("MMS result 4 (HTTP failure), HTTP 503", permanent = false)
        processor.onMmsSent("a", "content://mms/9", error)
        assertEquals(ConfirmState.DONE, store.get("a")!!.confirm)

        assertEquals(OutboundProcessor.Handled.RECONFIRMED, processor.handle(mms("a")))
        assertEquals(1, sender.dispatched.size)
        assertEquals(2, confirmations().size)
        assertTrue(confirmations().last().bodyText().contains("\"permanent\":false"))
    }

    @Test
    fun `a 400 on a late duplicate confirm doesn't undo an accepted one`() {
        // Another thread read the job while it was still PENDING and confirms it again after this one succeeded.
        var stale: OutboundJob? = null
        val racyStore = object : InMemoryOutboundStore() {
            override fun dueConfirmations(now: Long, limit: Int) = listOfNotNull(stale.also { stale = null })
        }
        val racy = OutboundProcessor(racyStore, fixture.api, sender, handlesFor(), status, clock, noJitter())
        var calls = 0
        fixture.routes.on("POST", "/v1/devices/outbox/") { if (calls++ == 0) json(200) else json(400) }

        racy.handle(OutboxMessage("s", "7", TYPE_SMS, listOf("+15551234567"), "hi"))
        stale = racyStore.get("s")!!.copy(state = OutboundState.SENT, confirm = ConfirmState.PENDING)
        racy.onSmsPartSent("s", "content://sms/100", null)
        assertEquals(ConfirmState.DONE, racyStore.get("s")!!.confirm)

        racy.flush()
        assertEquals(2, calls)
        assertEquals(ConfirmState.DONE, racyStore.get("s")!!.confirm)
    }

    @Test
    fun `transient failure not yet confirmed is confirmed again, not resent`() {
        fixture.routes.on("POST", "/v1/devices/outbox/") { json(503) }
        processor.handle(mms("a"))
        processor.onMmsSent("a", "content://mms/9", SendError("MMS result 2", permanent = false))
        assertEquals(ConfirmState.PENDING, store.get("a")!!.confirm)

        assertEquals(OutboundProcessor.Handled.RECONFIRMED, processor.handle(mms("a")))
        assertEquals(1, sender.dispatched.size)
    }

    @Test
    fun `404 on confirm marks the job done`() {
        fixture.routes.on("POST", "/v1/devices/outbox/") { json(404) }
        processor.handle(mms("a"))
        processor.onMmsSent("a", "content://mms/9", null)
        assertEquals(ConfirmState.DONE, store.get("a")!!.confirm)
        assertTrue(processor.flush())
        assertEquals(1, confirmations().size)
    }

    @Test
    fun `confirmations that fail on the network are retried from the table`() {
        fixture.routes.on("POST", "/v1/devices/outbox/") { json(502) }
        processor.handle(mms("a"))
        processor.onMmsSent("a", "content://mms/9", null)
        assertEquals(ConfirmState.PENDING, store.get("a")!!.confirm)

        fixture.routes.on("POST", "/v1/devices/outbox/") { json(200) }
        processor.flush()
        assertEquals(1, confirmations().size) // not due yet
        clock.advance(60_000)
        processor.flush()
        assertEquals(ConfirmState.DONE, store.get("a")!!.confirm)
        assertEquals(2, confirmations().size)
    }

    @Test
    fun `401 stops everything`() {
        fixture.routes.on("GET", "/v1/devices/outbox") { json(200, """{"messages":[]}""") }
        fixture.routes.on("POST", "/v1/devices/outbox/") { json(401) }
        processor.handle(mms("a"))
        processor.onMmsSent("a", "content://mms/9", null)

        assertTrue(fixture.api.isStopped)
        assertEquals(1, fixture.unauthorizedCalls)
        val before = fixture.routes.requests.size
        assertEquals(false, processor.flush())
        assertEquals(OutboxDrainer.Result.STOPPED, drainer.drain())
        assertEquals(before, fixture.routes.requests.size)

        fixture.api.onNewToken()
        fixture.routes.on("POST", "/v1/devices/outbox/") { json(200) }
        assertTrue(processor.flush())
        assertEquals(ConfirmState.DONE, store.get("a")!!.confirm)
    }

    @Test
    fun `delivery report goes out once, after the sent confirmation`() {
        fixture.routes.on("POST", "/v1/devices/outbox/") { json(200) }
        processor.handle(OutboxMessage("s", "7", TYPE_SMS, listOf("+15551234567"), "hi"))
        processor.onSmsDelivery("s", DeliveryOutcome.Delivered) // arrives before the sent result
        assertTrue(fixture.routes.requestsTo("POST", "/v1/devices/outbox/s/delivery").isEmpty())

        processor.onSmsPartSent("s", "content://sms/100", null)
        val deliveries = fixture.routes.requestsTo("POST", "/v1/devices/outbox/s/delivery")
        assertEquals("""{"delivered":true}""", deliveries.single().bodyText())
        processor.onSmsDelivery("s", DeliveryOutcome.Failed("late duplicate"))
        processor.flush()
        assertEquals(1, fixture.routes.requestsTo("POST", "/v1/devices/outbox/s/delivery").size)
    }

    @Test
    fun `stale sending job is settled from the provider and never resent`() {
        processor.handle(OutboxMessage("s", "7", TYPE_SMS, listOf("+15551234567"), "hi"))
        sender.probes["content://sms/100"] = ProviderProbe.SENT
        clock.advance(OutboundProcessor.STALE_AFTER_MILLIS + 1)
        processor.resolveStale()
        assertEquals(OutboundState.SENT, store.get("s")!!.state)
        assertEquals(1, sender.dispatched.size)
    }

    @Test
    fun `blocked sender leaves the job for later`() {
        sender.blocked = "not the default SMS app"
        assertEquals(OutboundProcessor.Handled.BLOCKED, processor.handle(mms("a")))
        assertEquals(OutboundState.RECEIVED, store.get("a")!!.state)
        sender.blocked = null
        assertEquals(OutboundProcessor.Handled.DISPATCHED, processor.handle(mms("a")))
    }

    @Test
    fun `drain fetches until the outbox is empty`() {
        val pages = ArrayDeque(
            listOf(
                """{"messages":[{"id":"1","chat":"42","type":"mms",""" +
                    """"recipients":["+15551234567","+15557654321"],"body":"a"},""" +
                    """{"id":"2","chat":"7","type":"sms","recipients":["+15551234567"],"body":"b"}]}""",
                """{"messages":[{"id":"3","chat":"7","type":"sms","recipients":["+15551234567"],"body":"c"}]}""",
                """{"messages":[]}""",
            )
        )
        fixture.routes.on("GET", "/v1/devices/outbox") { json(200, synchronized(pages) { pages.removeFirst() }) }

        assertEquals(OutboxDrainer.Result.EMPTY, drainer.drain())
        assertEquals(listOf("1", "2", "3"), sender.dispatched.map { it.first.id })
        assertEquals(3, fixture.routes.requestsTo("GET", "/v1/devices/outbox").size)
    }

    @Test
    fun `drain stops when the server hands out the same ids again`() {
        fixture.routes.on("GET", "/v1/devices/outbox") {
            json(200, """{"messages":[{"id":"1","chat":"42","body":"no type yet"}]}""")
        }
        assertEquals(OutboxDrainer.Result.STUCK, drainer.drain())
        assertEquals(2, fixture.routes.requestsTo("GET", "/v1/devices/outbox").size)
    }

    @Test
    fun `long poll asks for wait=20`() {
        fixture.routes.on("GET", "/v1/devices/outbox") { json(200, """{"messages":[]}""") }
        drainer.drain(GatewayApi.LONG_POLL_WAIT_SECONDS)
        assertEquals("20", fixture.routes.requestsTo("GET", "/v1/devices/outbox").single().url.queryParameter("wait"))
    }
}
