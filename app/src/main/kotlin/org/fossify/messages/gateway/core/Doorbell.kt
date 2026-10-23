package org.fossify.messages.gateway.core

import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

enum class DoorbellState { STOPPED, CONNECTING, CONNECTED, RECONNECTING, TOKEN_REJECTED }

/**
 * The Server-Sent Events doorbell (contract 2.2). It carries no content: each `ready` event just means "fetch
 * the outbox". Silence is detected by the streaming client's 60 second read timeout, since OkHttp's SSE reader
 * swallows the `: still here` comment lines but every byte still resets the timeout.
 *
 * After any drop it reconnects with backoff (1–60 s, jittered), resetting after a connection that stayed up
 * for a minute. If [FALLBACK_AFTER_FAILURES] attempts in a row fail without such a healthy minute, it asks
 * for the `?wait=20` long-poll fallback; that ends as soon as a stream opens again.
 *
 * All state lives on [executor], which must be single-threaded.
 */
class Doorbell(
    private val api: GatewayApi,
    private val executor: ScheduledExecutorService,
    private val listener: Listener,
    private val clock: Clock = Clock.SYSTEM,
    private val backoff: Backoff = Backoff.doorbell(),
    private val healthyMillis: Long = HEALTHY_MILLIS,
) {
    interface Listener {
        fun onReady()
        fun onStateChanged(state: DoorbellState)

        /** True while the stream can't stay up and the outbox should be long-polled instead. */
        fun onFallbackChanged(useLongPoll: Boolean)
    }

    private var state = DoorbellState.STOPPED
    private var generation = 0
    private var source: EventSource? = null
    private var reconnect: ScheduledFuture<*>? = null
    private var openedAt = 0L
    private var failuresInARow = 0
    private var fallback = false

    fun start() = executor.execute {
        if (state == DoorbellState.STOPPED || state == DoorbellState.TOKEN_REJECTED) {
            failuresInARow = 0
            connect()
        }
    }

    fun stop() = executor.execute {
        tearDown()
        setFallback(false)
        setState(DoorbellState.STOPPED)
    }

    /** The network came back: the old connection is dead, so reconnect at once with a fresh backoff. */
    fun onNetworkAvailable() = executor.execute {
        if (state == DoorbellState.STOPPED || state == DoorbellState.TOKEN_REJECTED) return@execute
        failuresInARow = 0
        tearDown()
        connect()
    }

    private fun connect() {
        reconnect?.cancel(false)
        reconnect = null
        val request = api.signalsRequest()
        if (request == null) {
            // Not configured, or stopped after a 401.
            if (api.isStopped) {
                tearDown()
                setFallback(false)
                setState(DoorbellState.TOKEN_REJECTED)
            } else {
                failuresInARow++
                scheduleReconnect()
            }
            return
        }

        val myGeneration = ++generation
        setState(if (failuresInARow == 0) DoorbellState.CONNECTING else DoorbellState.RECONNECTING)
        source = EventSources.createFactory(api.streamingClient).newEventSource(
            request,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) = onMine(myGeneration) {
                    openedAt = clock.now()
                    setFallback(false)
                    setState(DoorbellState.CONNECTED)
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) =
                    onMine(myGeneration) {
                        if (type == EVENT_READY) listener.onReady()
                    }

                override fun onClosed(eventSource: EventSource) = onMine(myGeneration) { onDropped(null) }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) =
                    onMine(myGeneration) { onDropped(response?.code) }
            }
        )
    }

    private fun onMine(myGeneration: Int, block: () -> Unit) {
        executor.execute {
            if (myGeneration == generation && state != DoorbellState.STOPPED) block()
        }
    }

    private fun onDropped(httpCode: Int?) {
        val wasOpenFor = if (openedAt > 0) clock.now() - openedAt else 0
        openedAt = 0
        source = null
        if (httpCode == HTTP_UNAUTHORIZED) {
            api.reportUnauthorized()
            tearDown()
            setFallback(false)
            setState(DoorbellState.TOKEN_REJECTED)
            return
        }

        // A connection that stayed up for a healthy minute resets the backoff; this drop is then the first.
        failuresInARow = (if (wasOpenFor >= healthyMillis) 0 else failuresInARow) + 1
        if (failuresInARow >= FALLBACK_AFTER_FAILURES) setFallback(true)
        scheduleReconnect()
    }

    /** Waits 1 s after the first failure, doubling up to 60 s, with jitter. */
    private fun scheduleReconnect() {
        setState(DoorbellState.RECONNECTING)
        val delay = backoff.delayFor((failuresInARow - 1).coerceAtLeast(0))
        reconnect?.cancel(false)
        reconnect = executor.schedule({
            if (state == DoorbellState.RECONNECTING) connect()
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun tearDown() {
        generation++
        reconnect?.cancel(false)
        reconnect = null
        source?.cancel()
        source = null
        openedAt = 0
    }

    private fun setState(newState: DoorbellState) {
        if (state != newState) {
            state = newState
            listener.onStateChanged(newState)
        }
    }

    private fun setFallback(useLongPoll: Boolean) {
        if (fallback != useLongPoll) {
            fallback = useLongPoll
            listener.onFallbackChanged(useLongPoll)
        }
    }

    companion object {
        const val EVENT_READY = "ready"
        const val FALLBACK_AFTER_FAILURES = 3
        const val HEALTHY_MILLIS = 60_000L
        private const val HTTP_UNAUTHORIZED = 401
    }
}
