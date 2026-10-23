package org.fossify.messages.gateway.core

import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Where to reach the server, and with what token. */
data class ServerConfig(val baseUrl: HttpUrl, val token: String) {
    override fun toString() = "ServerConfig(baseUrl=$baseUrl, token=<redacted>)"
}

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T, val code: Int) : ApiResult<T>

    /** 401: the token is wrong or revoked. All traffic stops until a new token is entered. */
    data object Unauthorized : ApiResult<Nothing>

    /** 404: settled (confirm), dropped (delivery), or the route doesn't exist yet (new routes). */
    data object NotFound : ApiResult<Nothing>

    /** 400: a gateway bug. Keep the data, show the error, don't retry in a loop. */
    data class BadRequest(val message: String) : ApiResult<Nothing>

    /** Any other HTTP failure. 5xx and 429 are worth retrying; other 4xx are not. */
    data class HttpError(val code: Int, val message: String) : ApiResult<Nothing> {
        val retryable get() = code >= HTTP_SERVER_ERROR || code == HTTP_TOO_MANY_REQUESTS || code == HTTP_TIMEOUT
    }

    data class NetworkError(val error: IOException) : ApiResult<Nothing>

    /** No base URL or token configured, or traffic is stopped after a 401. */
    data object NotConfigured : ApiResult<Nothing>

    companion object {
        const val HTTP_TIMEOUT = 408
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
    }
}

/**
 * Client for the device routes. Rules from the contract that live here:
 * - every request carries `Authorization: Bearer hd_…`; the token is never put in a URL or logged;
 * - no `Origin` header is ever sent;
 * - a 401 on any call stops all traffic until [onNewToken] is called.
 */
@Suppress("TooManyFunctions")
class GatewayApi(
    baseClient: OkHttpClient,
    private val config: () -> ServerConfig?,
    private val onUnauthorized: () -> Unit = {},
) {
    private val stopped = AtomicBoolean(false)

    private val client: OkHttpClient = baseClient.newBuilder()
        .addInterceptor(DeviceHeaders(config))
        .build()

    /** Used for the doorbell: 60 seconds without a byte (the server comments every 25) means a dead stream. */
    val streamingClient: OkHttpClient = client.newBuilder()
        .readTimeout(STREAM_SILENCE_SECONDS, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val longPollClient: OkHttpClient = client.newBuilder()
        .readTimeout(LONG_POLL_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    val isStopped get() = stopped.get()

    /** Called after the operator enters a new token. */
    fun onNewToken() = stopped.set(false)

    /** Keeps traffic stopped across restarts until a new token is entered. */
    fun halt() = stopped.set(true)

    fun signalsRequest(): Request? = request("v1/devices/signals")
        ?.header("Accept", "text/event-stream")
        ?.get()
        ?.build()

    /** To be called by the doorbell when the stream itself answers 401. */
    fun reportUnauthorized() {
        if (stopped.compareAndSet(false, true)) onUnauthorized()
    }

    fun postEvents(events: List<InboundEvent>): ApiResult<EventsResponse> {
        val body = GatewayJson.encodeToString(EventsRequest.serializer(), EventsRequest(events)).toJsonBody()
        return execute(request("v1/devices/events")?.post(body)) { decode(it, EventsResponse.serializer()) }
    }

    fun fetchOutbox(waitSeconds: Int? = null): ApiResult<OutboxResponse> {
        val builder = request("v1/devices/outbox") { url ->
            if (waitSeconds != null) url.addQueryParameter("wait", waitSeconds.toString())
        }?.get()
        val callClient = if (waitSeconds != null) longPollClient else client
        return execute(builder, callClient) { decode(it, OutboxResponse.serializer()) }
    }

    fun confirmSent(outboxId: String, providerId: String): ApiResult<Unit> {
        val json = GatewayJson.encodeToString(ConfirmSent.serializer(), ConfirmSent(providerId = providerId))
        return execute(request(listOf("v1", "devices", "outbox", outboxId))?.post(json.toJsonBody())) { }
    }

    fun confirmFailed(outboxId: String, error: String, permanent: Boolean): ApiResult<Unit> {
        val body = ConfirmFailed(error = error.take(MAX_ERROR_LENGTH), permanent = permanent)
        val json = GatewayJson.encodeToString(ConfirmFailed.serializer(), body)
        return execute(request(listOf("v1", "devices", "outbox", outboxId))?.post(json.toJsonBody())) { }
    }

    fun reportDelivery(outboxId: String, report: DeliveryReport): ApiResult<Unit> {
        val json = GatewayJson.encodeToString(DeliveryReport.serializer(), report)
        val segments = listOf("v1", "devices", "outbox", outboxId, "delivery")
        return execute(request(segments)?.post(json.toJsonBody())) { }
    }

    fun putAttachment(key: String, body: RequestBody): ApiResult<Unit> {
        val builder = request("v1/devices/attachments") { url ->
            url.addEncodedPathSegment(percentEncodeSegment(key))
        }?.put(body)
        return execute(builder) { }
    }

    fun heartbeat(heartbeat: Heartbeat): ApiResult<Unit> {
        val json = ExplicitNullsJson.encodeToString(Heartbeat.serializer(), heartbeat)
        return execute(request("v1/devices/heartbeat")?.post(json.toJsonBody())) { }
    }

    fun putThreads(snapshot: ThreadsSnapshot): ApiResult<Unit> {
        val json = GatewayJson.encodeToString(ThreadsSnapshot.serializer(), snapshot)
        return execute(request("v1/devices/threads")?.put(json.toJsonBody())) { }
    }

    private fun request(path: String, extra: (HttpUrl.Builder) -> Unit = {}): Request.Builder? {
        return request(path.split('/'), extra)
    }

    private fun request(segments: List<String>, extra: (HttpUrl.Builder) -> Unit = {}): Request.Builder? {
        if (stopped.get()) return null
        val server = config() ?: return null
        val url = server.baseUrl.newBuilder()
        segments.forEach { url.addPathSegment(it) }
        extra(url)
        return Request.Builder().url(url.build())
    }

    private fun <T> execute(
        builder: Request.Builder?,
        callClient: OkHttpClient = client,
        parse: (Response) -> T,
    ): ApiResult<T> {
        if (builder == null) return ApiResult.NotConfigured
        val call: Call = callClient.newCall(builder.build())
        return try {
            call.execute().use { response -> toResult(response, parse) }
        } catch (e: IOException) {
            ApiResult.NetworkError(e)
        }
    }

    private fun <T> toResult(response: Response, parse: (Response) -> T): ApiResult<T> = when (response.code) {
        in HTTP_OK_RANGE -> try {
            ApiResult.Ok(parse(response), response.code)
        } catch (e: SerializationException) {
            ApiResult.HttpError(response.code, "unreadable response: ${e.message}")
        } catch (e: IllegalArgumentException) {
            ApiResult.HttpError(response.code, "unreadable response: ${e.message}")
        }

        HTTP_UNAUTHORIZED -> {
            reportUnauthorized()
            ApiResult.Unauthorized
        }

        HTTP_NOT_FOUND -> ApiResult.NotFound
        HTTP_BAD_REQUEST -> ApiResult.BadRequest(errorMessage(response))
        else -> ApiResult.HttpError(response.code, errorMessage(response))
    }

    private fun <T> decode(response: Response, serializer: kotlinx.serialization.KSerializer<T>): T {
        val text = response.body.string()
        return GatewayJson.decodeFromString(serializer, text.ifBlank { "{}" })
    }

    private fun errorMessage(response: Response): String {
        val text = try {
            response.body.string().take(MAX_ERROR_LENGTH)
        } catch (_: IOException) {
            ""
        }
        val fromJson = try {
            GatewayJson.decodeFromString(ErrorBody.serializer(), text).error
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        return "HTTP ${response.code}" + (fromJson ?: text).let { if (it.isBlank()) "" else ": $it" }
    }

    private class DeviceHeaders(private val config: () -> ServerConfig?) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val token = config()?.token ?: throw IOException("gateway not configured")
            val request = chain.request().newBuilder()
                .removeHeader("Origin")
                .header("Authorization", "Bearer $token")
                .build()
            return chain.proceed(request)
        }
    }

    companion object {
        const val STREAM_SILENCE_SECONDS = 60L
        const val LONG_POLL_WAIT_SECONDS = 20
        private const val LONG_POLL_READ_TIMEOUT_SECONDS = 40L
        private const val MAX_ERROR_LENGTH = 500
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_NOT_FOUND = 404
        private val HTTP_OK_RANGE = 200..299
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun String.toJsonBody() = toRequestBody(JSON)

        /** Percent-encodes everything outside RFC 3986's unreserved set, so `:` becomes `%3A`. */
        fun percentEncodeSegment(value: String): String = buildString {
            value.toByteArray(Charsets.UTF_8).forEach { byte ->
                val c = byte.toInt() and BYTE_MASK
                val ch = c.toChar()
                if (ch.isLetterOrDigit() && c < ASCII_LIMIT || ch in "-._~") {
                    append(ch)
                } else {
                    append('%').append(HEX[c shr NIBBLE]).append(HEX[c and LOW_NIBBLE])
                }
            }
        }

        private const val BYTE_MASK = 0xFF
        private const val ASCII_LIMIT = 0x80
        private const val NIBBLE = 4
        private const val LOW_NIBBLE = 0x0F
        private const val HEX = "0123456789ABCDEF"

        /**
         * Release builds talk HTTPS only. Debug builds may also use plain HTTP to localhost, for
         * `adb reverse tcp:8070 tcp:8070`.
         */
        fun parseBaseUrl(raw: String, allowLocalHttp: Boolean): Result<HttpUrl> {
            val url = raw.trim().toHttpUrlOrNull()
                ?: return Result.failure(IllegalArgumentException("not a valid http(s) URL"))
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
                return Result.failure(IllegalArgumentException("credentials don't belong in the URL"))
            }
            if (url.query != null || url.fragment != null) {
                return Result.failure(IllegalArgumentException("the base URL can't have a query or fragment"))
            }
            val isLocal = url.host == "localhost" || url.host == "127.0.0.1"
            return when {
                url.isHttps -> Result.success(url)
                allowLocalHttp && isLocal -> Result.success(url)
                allowLocalHttp -> Result.failure(IllegalArgumentException("plain HTTP is only allowed for localhost"))
                else -> Result.failure(IllegalArgumentException("release builds require HTTPS"))
            }
        }
    }
}
