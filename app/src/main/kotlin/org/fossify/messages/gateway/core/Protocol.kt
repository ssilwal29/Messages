package org.fossify.messages.gateway.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Wire format of the TheDude device API. Optional fields are left out of the JSON when they are null,
 * except in [Heartbeat], whose nullable fields the server expects as explicit nulls.
 */
val GatewayJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

internal val ExplicitNullsJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
    encodeDefaults = true
}

const val TYPE_SMS = "sms"
const val TYPE_MMS = "mms"

/** RFC 3339 in UTC, e.g. 2026-10-22T18:30:00Z. */
fun rfc3339(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

/** A chat participant: sent as a bare handle, or as `{handle,name}` when Contacts knows a name. */
@Serializable(with = ParticipantSerializer::class)
data class Participant(val handle: String, val name: String? = null)

object ParticipantSerializer : KSerializer<Participant> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Participant) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("JSON only")
        val element = if (value.name.isNullOrBlank()) {
            JsonPrimitive(value.handle)
        } else {
            buildJsonObject {
                put("handle", value.handle)
                put("name", value.name)
            }
        }
        json.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): Participant {
        val json = decoder as? JsonDecoder ?: throw SerializationException("JSON only")
        return when (val element = json.decodeJsonElement()) {
            is JsonPrimitive -> Participant(element.content)
            is JsonObject -> Participant(
                handle = element["handle"]?.jsonPrimitive?.content.orEmpty(),
                name = element["name"]?.jsonPrimitive?.contentOrNull
            )

            else -> throw SerializationException("Unexpected participant: $element")
        }
    }
}

@Serializable
data class ChatRef(
    val id: String,
    val name: String? = null,
    val participants: List<Participant>,
)

@Serializable
data class AttachmentRef(
    val key: String,
    val contentType: String,
    val name: String? = null,
)

/** One received message, as reported to `POST /v1/devices/events`. */
@Serializable
data class InboundEvent(
    val id: String,
    val chat: ChatRef,
    val from: String,
    val fromName: String? = null,
    val body: String,
    val type: String,
    val receivedAt: String,
    val attachments: List<AttachmentRef>? = null,
)

@Serializable
data class EventsRequest(val events: List<InboundEvent>)

@Serializable
data class EventsResponse(val accepted: Int = 0)

/**
 * One reply from `GET /v1/devices/outbox`. [type] and [recipients] are new on the server; a message that
 * arrives without them must not be sent.
 */
@Serializable
data class OutboxMessage(
    val id: String,
    val chat: String,
    val type: String? = null,
    val recipients: List<String>? = null,
    val body: String = "",
)

@Serializable
data class OutboxResponse(val messages: List<OutboxMessage> = emptyList())

/** `POST /v1/devices/outbox/{id}` success body. The endpoint rejects unknown fields, so keep exactly these. */
@Serializable
data class ConfirmSent(
    val sent: Boolean = true,
    val providerId: String,
)

/** `POST /v1/devices/outbox/{id}` failure body. */
@Serializable
data class ConfirmFailed(
    val sent: Boolean = false,
    val error: String,
    val permanent: Boolean,
)

/** `POST /v1/devices/outbox/{id}/delivery` body. */
@Serializable
data class DeliveryReport(
    val delivered: Boolean,
    val error: String? = null,
)

@Serializable
data class Battery(val percent: Int, val charging: Boolean)

@Serializable
data class QueueStats(
    val inbound: Int,
    val outbound: Int,
    val oldestInboundSeconds: Long,
)

/** `POST /v1/devices/heartbeat` body. Serialise with [ExplicitNullsJson]. */
@Serializable
data class Heartbeat(
    val appVersion: String,
    val defaultSmsApp: Boolean,
    val airplaneMode: Boolean,
    val network: String,
    val signal: Int?,
    val battery: Battery,
    val queue: QueueStats,
    val mmsDownloadFailures: Int,
    val lastError: String?,
)

@Serializable
data class ThreadInfo(
    val id: String,
    val name: String? = null,
    val participants: List<Participant>,
    val lastMessageAt: String,
)

/** `PUT /v1/devices/threads` body. */
@Serializable
data class ThreadsSnapshot(val threads: List<ThreadInfo>)

/** Server error bodies are not specified; when one is JSON with an `error` field, show that. */
@Serializable
internal data class ErrorBody(@SerialName("error") val error: String? = null)
