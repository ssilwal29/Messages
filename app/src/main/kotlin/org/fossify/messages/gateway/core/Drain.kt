package org.fossify.messages.gateway.core

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One drain at a time, shared by the service and the watchdog so they never send at the same time.
 * A request that arrives while a drain runs makes that drain go round once more before it stops.
 */
class DrainCoordinator {
    private val running = AtomicBoolean(false)
    private val requested = AtomicBoolean(false)

    val isRunning get() = running.get()

    /** Runs [drain] now, or leaves it to the drain already running. Returns true if this call ran it. */
    fun request(drain: () -> Unit): Boolean {
        requested.set(true)
        var ran = false
        while (requested.get() && running.compareAndSet(false, true)) {
            ran = true
            try {
                while (requested.getAndSet(false)) drain()
            } finally {
                running.set(false)
            }
        }
        return ran
    }
}

/**
 * Fetches the outbox and hands each message to the [OutboundProcessor] until the outbox is empty (contract 2.3).
 */
class OutboxDrainer(
    private val api: GatewayApi,
    private val processor: OutboundProcessor,
    private val status: StatusSink,
) {
    enum class Result { EMPTY, BLOCKED, STUCK, STOPPED, ERROR }

    @Suppress("ReturnCount")
    fun drain(waitSeconds: Int? = null): Result {
        val seen = HashSet<String>()
        var wait = waitSeconds
        repeat(MAX_ROUNDS) {
            if (processor.blockedReason() != null) return Result.BLOCKED
            val messages = when (val result = api.fetchOutbox(wait)) {
                is ApiResult.Ok -> result.value.messages
                ApiResult.Unauthorized, ApiResult.NotConfigured -> return Result.STOPPED
                ApiResult.NotFound -> return error("outbox route answered 404")
                is ApiResult.BadRequest -> return error("outbox fetch rejected: ${result.message}")
                is ApiResult.HttpError -> return error("outbox fetch: ${result.message}")
                is ApiResult.NetworkError -> return error("outbox fetch: ${result.error.javaClass.simpleName}")
            }
            if (messages.isEmpty()) return Result.EMPTY

            var progress = false
            for (message in messages) {
                if (seen.add(message.id)) progress = true
                processor.handle(message)
            }
            // The server handed out only ids we already handled in this drain: stop rather than spin.
            if (!progress) return Result.STUCK
            wait = null
        }
        return Result.STUCK
    }

    private fun error(message: String): Result {
        status.recordError(message)
        return Result.ERROR
    }

    companion object {
        private const val MAX_ROUNDS = 100
    }
}
