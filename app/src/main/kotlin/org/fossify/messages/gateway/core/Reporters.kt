package org.fossify.messages.gateway.core

/**
 * Sends the heartbeat (2.7) and the thread list (2.8). Both routes are new on the server: a 404 turns the
 * feature off and it is tried again an hour later (2.9).
 */
class Reporters(
    private val api: GatewayApi,
    private val features: FeatureGate,
    private val status: StatusSink,
) {
    enum class Outcome { SENT, UNSUPPORTED, SKIPPED, FAILED, STOPPED }

    /** [force] sends even while the route is believed missing (the operator's "Send test heartbeat"). */
    fun heartbeat(build: () -> Heartbeat, force: Boolean = false): Outcome =
        send(Feature.HEARTBEAT, force, "heartbeat") { api.heartbeat(build()) }

    fun threads(build: () -> ThreadsSnapshot, force: Boolean = false): Outcome =
        send(Feature.THREADS, force, "thread list") { api.putThreads(build()) }

    private fun send(feature: Feature, force: Boolean, what: String, call: () -> ApiResult<Unit>): Outcome {
        if (!force && !features.shouldTry(feature)) return Outcome.SKIPPED
        return when (val result = call()) {
            is ApiResult.Ok -> {
                features.supported(feature)
                Outcome.SENT
            }

            ApiResult.NotFound -> {
                features.unsupported(feature)
                Outcome.UNSUPPORTED
            }

            ApiResult.Unauthorized, ApiResult.NotConfigured -> Outcome.STOPPED
            is ApiResult.BadRequest -> fail("$what rejected: ${result.message}")
            is ApiResult.HttpError -> fail("$what: ${result.message}")
            is ApiResult.NetworkError -> Outcome.FAILED
        }
    }

    private fun fail(message: String): Outcome {
        status.recordError(message)
        return Outcome.FAILED
    }
}
