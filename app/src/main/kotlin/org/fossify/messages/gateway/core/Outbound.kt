package org.fossify.messages.gateway.core

import java.util.concurrent.ConcurrentHashMap

/** `received` → `checked` → `sending` → `sent` or `failed`; `sent` → `delivered`. */
enum class OutboundState {
    RECEIVED, CHECKED, SENDING, SENT, FAILED, DELIVERED;

    /** Once a job reaches `sending` it is never sent again for the same outbox id. */
    val isDispatched get() = this >= SENDING
}

enum class ConfirmState { NONE, PENDING, DONE, REJECTED }

enum class DeliveryState { NONE, PENDING, DONE }

/** One reply from the server's outbox, keyed by the outbox `id`. */
data class OutboundJob(
    val id: String,
    val chat: String,
    val type: String,
    val recipients: List<String>,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val state: OutboundState = OutboundState.RECEIVED,
    val providerUri: String? = null,
    val partsTotal: Int = 0,
    val partsOk: Int = 0,
    val partsFailed: Int = 0,
    val sendingSince: Long = 0,
    val sendAttempts: Int = 0,
    val lastError: String? = null,
    val permanent: Boolean = false,
    val confirm: ConfirmState = ConfirmState.NONE,
    val confirmAttempts: Int = 0,
    val nextConfirmAt: Long = 0,
    val delivery: DeliveryState = DeliveryState.NONE,
    val delivered: Boolean = false,
    val deliveryError: String? = null,
    val deliveryAttempts: Int = 0,
    val nextDeliveryAt: Long = 0,
)

interface OutboundStore {
    fun get(id: String): OutboundJob?

    /** Inserts [job] unless a job with its id exists. Returns false if it existed. */
    fun insert(job: OutboundJob): Boolean

    /** Atomically replaces the job with [transform]'s result. A null result leaves it unchanged. */
    fun update(id: String, transform: (OutboundJob) -> OutboundJob?): OutboundJob?

    fun dueConfirmations(now: Long, limit: Int): List<OutboundJob>
    fun dueDeliveryReports(now: Long, limit: Int): List<OutboundJob>
    fun sendingSince(before: Long): List<OutboundJob>

    /** Jobs not yet settled with the server. */
    fun activeCount(): Int

    /** Puts confirmations the server rejected with 400 back in the queue (operator's "Sync now"). */
    fun requeueRejectedConfirmations(): Int
}

/** A send that failed before or while being handed to the platform. */
class SendException(message: String, val permanent: Boolean, cause: Throwable? = null) : Exception(message, cause)

/** What [OutboundSender.prepare] wrote to the provider, and what [OutboundSender.dispatch] should send. */
data class PreparedSend(
    val providerUri: String?,
    val partsTotal: Int,
    val destinations: List<String>,
)

enum class ProviderProbe { SENT, FAILED, PENDING, MISSING }

/** The platform side of sending. Implemented by `GatewaySender`. */
interface OutboundSender {
    /** Why nothing can be sent right now (not the default SMS app, no SIM), or null. */
    fun blockedReason(): String?

    /** The provider's recipient addresses for [chatId] (a thread id), as stored, or null if there is no such thread. */
    fun threadAddresses(chatId: String): List<String>?

    /** Writes the outgoing message to the provider where needed, and says how many sent results to expect. */
    fun prepare(job: OutboundJob, threadAddresses: List<String>): PreparedSend

    /** Hands the message to the platform. Results arrive later through [OutboundProcessor]'s `on…` methods. */
    fun dispatch(job: OutboundJob, prepared: PreparedSend)

    /** What the provider says about a message whose sent result never arrived. */
    fun probe(providerUri: String): ProviderProbe
}

/** A send result as the system reported it. */
data class SendError(val message: String, val permanent: Boolean)

/** An SMS delivery report: delivered, failed, or still pending at the SMSC. */
sealed interface DeliveryOutcome {
    data object Delivered : DeliveryOutcome
    data object Pending : DeliveryOutcome
    data class Failed(val error: String) : DeliveryOutcome
}

/**
 * The outbound state machine (contract 2.3–2.5 and "Outbound" in the design):
 * - a job is inserted before anything is sent;
 * - it is marked `sending` before the provider or the radio is touched, so an id handed out again while it is
 *   `sending` or later is never sent again: its known result is confirmed again, or we keep waiting for it.
 *   That includes transient failures: a failure code doesn't prove nothing went out (an MMSC 5xx or an SMS
 *   generic failure can come after delivery), so a server retry needs a new outbox id;
 * - confirmations and delivery reports that fail on the network stay queued here and are retried.
 */
@Suppress("TooManyFunctions")
class OutboundProcessor(
    private val store: OutboundStore,
    private val api: GatewayApi,
    private val sender: OutboundSender,
    private val handles: () -> Pair<HandleNormalizer, OwnNumbers>,
    private val status: StatusSink,
    private val clock: Clock = Clock.SYSTEM,
    private val backoff: Backoff = Backoff.retries(),
) {
    enum class Handled { DISPATCHED, WAITING, RECONFIRMED, FAILED, SKIPPED_SERVER_OUT_OF_DATE, BLOCKED }

    fun blockedReason(): String? = sender.blockedReason()

    fun handle(message: OutboxMessage): Handled {
        val type = message.type
        val recipients = message.recipients
        if (type == null || recipients == null) {
            // Never guess where a message goes: leave it unconfirmed.
            status.onServerOutOfDate()
            return Handled.SKIPPED_SERVER_OUT_OF_DATE
        }

        val now = clock.now()
        val fresh = OutboundJob(
            id = message.id,
            chat = message.chat,
            type = type,
            recipients = recipients,
            body = message.body,
            createdAt = now,
        )
        val job = if (store.insert(fresh)) fresh else store.get(message.id) ?: return Handled.WAITING

        return when (job.state) {
            OutboundState.RECEIVED, OutboundState.CHECKED -> checkAndSend(job)
            OutboundState.SENDING -> {
                resolveStale(job, now)
                Handled.WAITING
            }

            OutboundState.SENT, OutboundState.DELIVERED, OutboundState.FAILED -> reconfirm(job)
        }
    }

    private fun reconfirm(job: OutboundJob): Handled {
        if (job.confirm == ConfirmState.REJECTED) return Handled.RECONFIRMED
        val queued = store.update(job.id) {
            it.copy(confirm = ConfirmState.PENDING, nextConfirmAt = 0, updatedAt = clock.now())
        }
        if (queued != null) confirm(queued)
        return Handled.RECONFIRMED
    }

    private fun checkAndSend(job: OutboundJob): Handled {
        sender.blockedReason()?.let {
            status.recordError(it)
            return Handled.BLOCKED
        }

        val threadAddresses = sender.threadAddresses(job.chat)
        val (normalizer, own) = handles()
        val check = RecipientCheck.check(job.type, job.recipients, threadAddresses, normalizer, own)
        if (check is RecipientCheck.Result.Mismatch) {
            fail(job.id, SendError("recipient check failed: ${check.reason}", permanent = true), fromState = null)
            return Handled.FAILED
        }

        val now = clock.now()
        // Mark `sending` before touching the provider or the radio. From here on this id is never sent again.
        val sending = store.update(job.id) {
            if (it.state.isDispatched) return@update null
            it.copy(
                state = OutboundState.SENDING,
                sendingSince = now,
                sendAttempts = it.sendAttempts + 1,
                updatedAt = now,
            )
        } ?: return Handled.WAITING

        return try {
            val prepared = sender.prepare(sending, threadAddresses!!)
            val ready = store.update(job.id) {
                it.copy(providerUri = prepared.providerUri ?: it.providerUri, partsTotal = prepared.partsTotal)
            } ?: sending
            sender.dispatch(ready, prepared)
            Handled.DISPATCHED
        } catch (e: SendException) {
            fail(job.id, SendError(e.message ?: "send failed", e.permanent), OutboundState.SENDING)
            Handled.FAILED
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            val error = SendError("send failed: ${e.javaClass.simpleName}", permanent = false)
            fail(job.id, error, OutboundState.SENDING)
            Handled.FAILED
        }
    }

    private fun fail(id: String, error: SendError, fromState: OutboundState?) {
        val failed = store.update(id) {
            if (fromState != null && it.state != fromState) return@update null
            if (fromState == null && it.state.isDispatched) return@update null
            it.copy(
                state = OutboundState.FAILED,
                lastError = error.message,
                permanent = error.permanent,
                confirm = ConfirmState.PENDING,
                nextConfirmAt = 0,
                updatedAt = clock.now(),
            )
        } ?: return
        status.recordError("reply ${failed.id}: ${error.message}")
        confirm(failed)
    }

    /** One part of an SMS reported its sent result. The job settles when every part has reported. */
    fun onSmsPartSent(id: String, providerUri: String?, error: SendError?) {
        val settled = store.update(id) { job ->
            if (job.state != OutboundState.SENDING) return@update null
            val ok = job.partsOk + if (error == null) 1 else 0
            val failed = job.partsFailed + if (error == null) 0 else 1
            val next = job.copy(
                partsOk = ok,
                partsFailed = failed,
                providerUri = job.providerUri ?: providerUri,
                lastError = error?.message ?: job.lastError,
                permanent = job.permanent || error?.permanent == true,
                updatedAt = clock.now(),
            )
            if (ok + failed < job.partsTotal.coerceAtLeast(1)) next else settle(next, failed == 0)
        } ?: return
        afterSettled(settled)
    }

    fun onMmsSent(id: String, providerUri: String?, error: SendError?) {
        val settled = store.update(id) { job ->
            if (job.state != OutboundState.SENDING) return@update null
            val next = job.copy(
                providerUri = providerUri ?: job.providerUri,
                partsOk = if (error == null) 1 else 0,
                partsFailed = if (error == null) 0 else 1,
                lastError = error?.message,
                permanent = error?.permanent == true,
                updatedAt = clock.now(),
            )
            settle(next, error == null)
        } ?: return
        afterSettled(settled)
    }

    private fun settle(job: OutboundJob, ok: Boolean) = job.copy(
        state = when {
            !ok -> OutboundState.FAILED
            // The delivery report can beat the last sent result.
            job.delivery != DeliveryState.NONE && job.delivered -> OutboundState.DELIVERED
            else -> OutboundState.SENT
        },
        lastError = if (ok) null else job.lastError ?: "send failed",
        permanent = !ok && job.permanent,
        confirm = ConfirmState.PENDING,
        nextConfirmAt = 0,
    )

    private fun afterSettled(job: OutboundJob) {
        when (job.state) {
            OutboundState.SENT, OutboundState.DELIVERED -> status.onSent(clock.now())
            OutboundState.FAILED -> status.recordError("reply ${job.id}: ${job.lastError}")
            else -> return
        }
        confirm(job)
    }

    /** An SMS delivery report. Fossify requests one for the last part only, so the first final report settles it. */
    fun onSmsDelivery(id: String, outcome: DeliveryOutcome) {
        if (outcome == DeliveryOutcome.Pending) return
        val updated = store.update(id) { job ->
            if (job.type != TYPE_SMS || job.delivery != DeliveryState.NONE) return@update null
            val delivered = outcome == DeliveryOutcome.Delivered
            job.copy(
                state = if (delivered && job.state == OutboundState.SENT) OutboundState.DELIVERED else job.state,
                delivery = DeliveryState.PENDING,
                delivered = delivered,
                deliveryError = (outcome as? DeliveryOutcome.Failed)?.error,
                nextDeliveryAt = 0,
                updatedAt = clock.now(),
            )
        } ?: return
        reportDelivery(updated)
    }

    /** Retries confirmations and delivery reports that haven't gone through. Returns false after a 401. */
    fun flush(limit: Int = FLUSH_LIMIT): Boolean {
        val now = clock.now()
        for (job in store.dueConfirmations(now, limit)) {
            if (!confirm(job)) return false
        }
        for (job in store.dueDeliveryReports(now, limit)) {
            if (!reportDelivery(job)) return false
        }
        return true
    }

    /**
     * Settles jobs stuck in `sending` because their sent result never arrived (the process died between marking
     * them `sending` and handing them over, or the broadcast was lost). Never resends: it only reads what the
     * provider recorded, and gives up with a permanent failure when that says nothing.
     */
    fun resolveStale() {
        val now = clock.now()
        store.sendingSince(now - STALE_AFTER_MILLIS).forEach { resolveStale(it, now) }
    }

    private fun resolveStale(job: OutboundJob, now: Long) {
        if (job.state != OutboundState.SENDING || now - job.sendingSince < STALE_AFTER_MILLIS) return
        val (ok, error) = staleOutcome(job, now) ?: return
        val settled = store.update(job.id) {
            if (it.state != OutboundState.SENDING) return@update null
            settle(it.copy(lastError = error?.message, permanent = error?.permanent == true, updatedAt = now), ok)
        } ?: return
        afterSettled(settled)
    }

    /** What a stale `sending` job really did: (sent, error), or null to keep waiting. */
    private fun staleOutcome(job: OutboundJob, now: Long): Pair<Boolean, SendError?>? {
        val uri = job.providerUri
        val givingUp = now - job.sendingSince >= GIVE_UP_AFTER_MILLIS
        val unknown = SendError("no send result; outcome unknown", permanent = true)
        return when {
            // SMS rows are written before dispatch, so an SMS without one was never handed to the radio.
            uri == null && job.type == TYPE_SMS -> false to SendError("not sent (interrupted before sending)", false)
            uri == null -> if (givingUp) false to unknown else null
            else -> when (sender.probe(uri)) {
                ProviderProbe.SENT -> true to null
                ProviderProbe.FAILED -> false to SendError("send failed (result recovered from provider)", true)
                ProviderProbe.MISSING -> false to SendError("provider row is gone; outcome unknown", true)
                ProviderProbe.PENDING -> if (givingUp) false to unknown else null
            }
        }
    }

    /** Ids with a confirmation on the wire: the service, the watchdog and the receivers may all try at once. */
    private val confirming: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Returns false only when traffic must stop (401 or not configured). */
    private fun confirm(job: OutboundJob): Boolean {
        if (job.confirm != ConfirmState.PENDING || !confirming.add(job.id)) return true
        try {
            return confirmNow(job)
        } finally {
            confirming.remove(job.id)
        }
    }

    private fun confirmNow(job: OutboundJob): Boolean {
        val result = when (job.state) {
            OutboundState.SENT, OutboundState.DELIVERED -> api.confirmSent(job.id, job.providerUri.orEmpty())
            OutboundState.FAILED -> api.confirmFailed(job.id, job.lastError ?: "send failed", job.permanent)
            else -> return true
        }
        val now = clock.now()
        return when (result) {
            is ApiResult.Ok, ApiResult.NotFound -> {
                // 404: the server already settled this message.
                store.update(job.id) { it.copy(confirm = ConfirmState.DONE, updatedAt = now) }
                true
            }

            ApiResult.Unauthorized, ApiResult.NotConfigured -> false
            is ApiResult.BadRequest -> {
                store.update(job.id) {
                    if (it.confirm != ConfirmState.PENDING) return@update null
                    it.copy(confirm = ConfirmState.REJECTED, updatedAt = now)
                }
                status.recordError("confirm for reply ${job.id} rejected: ${result.message}")
                true
            }

            is ApiResult.HttpError, is ApiResult.NetworkError -> {
                store.update(job.id) {
                    if (it.confirm != ConfirmState.PENDING) return@update null
                    it.copy(
                        confirmAttempts = it.confirmAttempts + 1,
                        nextConfirmAt = now + backoff.delayFor(it.confirmAttempts),
                    )
                }
                true
            }
        }.also { if (it && result is ApiResult.Ok) reportDeliveryIfQueued(job.id) }
    }

    private fun reportDeliveryIfQueued(id: String) {
        store.get(id)?.takeIf { it.delivery == DeliveryState.PENDING }?.let { reportDelivery(it) }
    }

    private fun reportDelivery(job: OutboundJob): Boolean {
        // Report delivery only once the server knows the message was sent.
        if (job.delivery != DeliveryState.PENDING || job.confirm != ConfirmState.DONE) return true
        val report = DeliveryReport(job.delivered, if (job.delivered) null else job.deliveryError ?: "not delivered")
        val now = clock.now()
        return when (val result = api.reportDelivery(job.id, report)) {
            is ApiResult.Ok, ApiResult.NotFound -> {
                store.update(job.id) { it.copy(delivery = DeliveryState.DONE, updatedAt = now) }
                true
            }

            ApiResult.Unauthorized, ApiResult.NotConfigured -> false
            is ApiResult.BadRequest -> {
                store.update(job.id) { it.copy(delivery = DeliveryState.DONE, updatedAt = now) }
                status.recordError("delivery report for reply ${job.id} rejected: ${result.message}")
                true
            }

            is ApiResult.HttpError, is ApiResult.NetworkError -> {
                store.update(job.id) {
                    it.copy(
                        deliveryAttempts = it.deliveryAttempts + 1,
                        nextDeliveryAt = now + backoff.delayFor(it.deliveryAttempts),
                    )
                }
                true
            }
        }
    }

    companion object {
        const val FLUSH_LIMIT = 50
        const val STALE_AFTER_MILLIS = 10 * 60 * 1000L
        const val GIVE_UP_AFTER_MILLIS = 60 * 60 * 1000L
    }
}
