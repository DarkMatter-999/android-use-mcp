package darkmatter.androiduse.app.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/** Protocol version carried by every RPC envelope. */
const val PROTOCOL_VERSION: Int = 1

/** Maximum UTF-8 encoded frame size, measured before JSON parsing. */
const val MAX_FRAME_BYTES: Int = 2 * 1024 * 1024

/** Maximum RPC method-name length. */
const val MAX_METHOD_LENGTH: Int = 128

/** Method that cancels an in-flight operation; its params name the cancelled request. */
const val RPC_CANCEL_METHOD: String = "rpc.cancel"

/**
 * Typed protocol error codes shared with the TypeScript host. The set is closed
 * for protocol version 1; adding a code requires a protocol-version change.
 */
@Serializable
enum class RpcErrorCode {
    @SerialName("INVALID_MESSAGE") INVALID_MESSAGE,
    @SerialName("UNSUPPORTED_VERSION") UNSUPPORTED_VERSION,
    @SerialName("MESSAGE_TOO_LARGE") MESSAGE_TOO_LARGE,
    @SerialName("UNAUTHORIZED") UNAUTHORIZED,
    @SerialName("PAIRING_REQUIRED") PAIRING_REQUIRED,
    @SerialName("STALE_REF") STALE_REF,
    @SerialName("AMBIGUOUS_TARGET") AMBIGUOUS_TARGET,
    @SerialName("NOT_FOUND") NOT_FOUND,
    @SerialName("NOT_ACTIONABLE") NOT_ACTIONABLE,
    @SerialName("UNSUPPORTED") UNSUPPORTED,
    @SerialName("TIMEOUT") TIMEOUT,
    @SerialName("CANCELLED") CANCELLED,
    @SerialName("INTERNAL") INTERNAL,
}

@Serializable
data class RpcError(
    val code: RpcErrorCode,
    val message: String,
    val details: JsonObject? = null,
)

@Serializable
data class RpcRequest(
    val protocolVersion: Int,
    val requestId: String,
    val method: String,
    val params: JsonObject,
)

@Serializable(with = RpcResponseSerializer::class)
data class RpcResponse(
    val protocolVersion: Int,
    val requestId: String,
    /**
     * `null` means the `result` key was absent; [JsonNull] means the frame
     * carried an explicit `"result": null`. The custom serializer preserves
     * that distinction, which a generated nullable serializer would collapse.
     */
    val result: JsonElement? = null,
    val error: RpcError? = null,
)

/**
 * Manual JSON serializer for [RpcResponse]. The generated serializer cannot
 * tell an absent `result` from an explicit JSON null because both decode to a
 * null reference, and it would also omit an explicit null `error` instead of
 * normalizing it away. This serializer keeps the envelope strict (unknown
 * fields rejected) and the null semantics identical to the TypeScript schema.
 */
object RpcResponseSerializer : KSerializer<RpcResponse> {

    private val allowedKeys = setOf("protocolVersion", "requestId", "result", "error")

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("RpcResponse") {
        element<Int>("protocolVersion")
        element<String>("requestId")
        element<JsonElement>("result", isOptional = true)
        element<RpcError>("error", isOptional = true)
    }

    override fun deserialize(decoder: Decoder): RpcResponse {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("RpcResponse requires a JSON decoder")
        val envelope = input.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("RpcResponse must be a JSON object")

        val unknown = envelope.keys.firstOrNull { it !in allowedKeys }
        if (unknown != null) {
            throw SerializationException("unknown RpcResponse field '$unknown'")
        }

        val version = (envelope["protocolVersion"] as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.content
            ?.toIntOrNull()
            ?: throw SerializationException("protocolVersion must be an integer")
        val requestId = (envelope["requestId"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?: throw SerializationException("requestId must be a string")
        val error = envelope["error"]
            ?.takeUnless { it is JsonNull }
            ?.let { input.json.decodeFromJsonElement(RpcError.serializer(), it) }

        // `envelope["result"]` is null only when the key is absent; an explicit
        // JSON null stays a JsonNull value and is preserved on re-serialization.
        return RpcResponse(
            protocolVersion = version,
            requestId = requestId,
            result = envelope["result"],
            error = error,
        )
    }

    override fun serialize(encoder: Encoder, value: RpcResponse) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("RpcResponse requires a JSON encoder")
        val envelope = buildJsonObject {
            put("protocolVersion", value.protocolVersion)
            put("requestId", value.requestId)
            value.result?.let { put("result", it) }
            value.error?.let { put("error", output.json.encodeToJsonElement(RpcError.serializer(), it)) }
        }
        output.encodeJsonElement(envelope)
    }
}

/** Typed protocol failure returned by the parser or thrown by the serializer. */
data class RpcProtocolError(
    val code: RpcErrorCode,
    val message: String,
    val details: JsonObject? = null,
) {
    fun asRpcError(): RpcError = RpcError(code = code, message = message, details = details)
}

/** Thrown only when a locally constructed message cannot be serialized as a valid frame. */
class RpcProtocolException(val error: RpcProtocolError) : Exception(error.message)

/** Parsers return a typed result instead of leaking serializer exceptions. */
sealed interface RpcParseResult<out T> {
    data class Success<T>(val value: T) : RpcParseResult<T>

    data class Failure(val error: RpcProtocolError) : RpcParseResult<Nothing>
}

// ---------------------------------------------------------------------------
// Snapshot and action result DTOs frozen by the cross-platform contract.
// ---------------------------------------------------------------------------

@Serializable
data class UiBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

@Serializable
data class UiElement(
    val ref: String,
    val className: String? = null,
    val role: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val bounds: UiBounds,
    val clickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val visible: Boolean,
    val checked: Boolean? = null,
    val supportedActions: List<String>,
    val children: List<UiElement>? = null,
)

@Serializable
data class UiSnapshot(
    val deviceId: String,
    val windowId: String,
    val revision: Int,
    val packageName: String? = null,
    val windowTitle: String? = null,
    val width: Int,
    val height: Int,
    val elements: List<UiElement>,
    val compactText: String? = null,
    val truncated: Boolean,
)

@Serializable
data class ActionResultVerification(
    val passed: Boolean,
    val message: String? = null,
)

@Serializable
data class ActionResultTarget(
    val ref: String? = null,
    val text: String? = null,
    /** Left, top, right, bottom in screen pixels. */
    val bounds: List<Int>? = null,
)

@Serializable
data class ActionResultError(
    val code: RpcErrorCode,
    val message: String,
)

/**
 * Outcome of one action. `verified` is `null` unless a separate postcondition
 * was evaluated; a dispatched action is not proof of task success.
 */
@Serializable
data class ActionResult(
    val actionId: String,
    val action: String,
    val accepted: Boolean,
    /**
     * Required on the wire as `true`, `false`, or JSON `null`. No default: a
     * dispatched-but-unverified action must serialize `"verified": null`
     * instead of omitting the key.
     */
    val verified: Boolean?,
    val verification: ActionResultVerification? = null,
    val target: ActionResultTarget? = null,
    val revisionBefore: Int,
    val revisionAfter: Int,
    val error: ActionResultError? = null,
)

/**
 * Versioned, strictly validated RPC envelope codec for the Android companion.
 * Mirrors `packages/protocol/src/schema.ts`; the shared JSON fixtures under
 * `packages/protocol/fixtures/` are parsed by both test suites.
 */
object RpcProtocol {

    /** Method names frozen for protocol version 1. */
    val RPC_METHODS: List<String> = listOf(
        "device.status",
        "pairing.begin",
        "pairing.approve",
        "pairing.revoke",
        "session.start",
        "session.stop",
        "snapshot.get",
        "element.query",
        "element.action",
        "gesture.perform",
        "screenshot.capture",
        RPC_CANCEL_METHOD,
    )

    private val UUID_PATTERN =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

    /**
     * `ignoreUnknownKeys = false` is required: unknown envelope fields are a
     * protocol error, while method-specific params remain an arbitrary object.
     */
    val json: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
        encodeDefaults = false
        explicitNulls = true
    }

    fun parseRequest(text: String): RpcParseResult<RpcRequest> = runParse {
        val request = decode(RpcRequest.serializer(), decodeEnvelope(text))
        validateRequest(request)
        request
    }

    fun parseResponse(text: String): RpcParseResult<RpcResponse> = runParse {
        val response = decode(RpcResponse.serializer(), decodeEnvelope(text))
        validateRequestId(response.requestId)
        response
    }

    fun serialize(request: RpcRequest): String {
        validateRequest(request)
        return encodeFrame(request, RpcRequest.serializer())
    }

    fun serialize(response: RpcResponse): String {
        validateResponse(response)
        return encodeFrame(response, RpcResponse.serializer())
    }

    fun encodeSnapshot(snapshot: UiSnapshot): JsonElement =
        json.encodeToJsonElement(UiSnapshot.serializer(), snapshot)

    fun decodeSnapshot(element: JsonElement): UiSnapshot =
        json.decodeFromJsonElement(UiSnapshot.serializer(), element)

    fun encodeActionResult(result: ActionResult): JsonElement =
        json.encodeToJsonElement(ActionResult.serializer(), result)

    fun decodeActionResult(element: JsonElement): ActionResult =
        json.decodeFromJsonElement(ActionResult.serializer(), element)

    // ------------------------------------------------------------- internals

    private inline fun <T> runParse(block: () -> T): RpcParseResult<T> = try {
        RpcParseResult.Success(block())
    } catch (error: RpcProtocolException) {
        RpcParseResult.Failure(error.error)
    }

    private fun failure(code: RpcErrorCode, message: String, details: JsonObject? = null): Nothing =
        throw RpcProtocolException(RpcProtocolError(code, message, details))

    private fun decodeEnvelope(text: String): JsonObject {
        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_FRAME_BYTES) {
            failure(
                RpcErrorCode.MESSAGE_TOO_LARGE,
                "RPC frame is $bytes UTF-8 bytes; the maximum is $MAX_FRAME_BYTES",
            )
        }

        val element = try {
            json.parseToJsonElement(text)
        } catch (exception: SerializationException) {
            failure(RpcErrorCode.INVALID_MESSAGE, "RPC frame is not valid JSON")
        } catch (exception: IllegalArgumentException) {
            failure(RpcErrorCode.INVALID_MESSAGE, "RPC frame is not valid JSON")
        }

        val envelope = element as? JsonObject
            ?: failure(RpcErrorCode.INVALID_MESSAGE, "RPC frame must be a JSON object")

        requireSupportedVersion(envelope)
        return envelope
    }

    private fun requireSupportedVersion(envelope: JsonObject) {
        val element = envelope["protocolVersion"]
            ?: failure(RpcErrorCode.INVALID_MESSAGE, "protocolVersion is required")

        val primitive = element as? JsonPrimitive
        val supported = primitive != null &&
            !primitive.isString &&
            primitive.content.toDoubleOrNull() == PROTOCOL_VERSION.toDouble()

        if (!supported) {
            failure(
                RpcErrorCode.UNSUPPORTED_VERSION,
                "unsupported protocolVersion ${element.toString()}; expected $PROTOCOL_VERSION",
            )
        }
    }

    private fun <T> decode(serializer: KSerializer<T>, envelope: JsonObject): T = try {
        json.decodeFromJsonElement(serializer, envelope)
    } catch (exception: SerializationException) {
        failure(RpcErrorCode.INVALID_MESSAGE, "invalid RPC envelope: ${exception.message}")
    } catch (exception: IllegalArgumentException) {
        failure(RpcErrorCode.INVALID_MESSAGE, "invalid RPC envelope: ${exception.message}")
    }

    private fun validateRequest(request: RpcRequest) {
        if (request.protocolVersion != PROTOCOL_VERSION) {
            failure(
                RpcErrorCode.UNSUPPORTED_VERSION,
                "unsupported protocolVersion ${request.protocolVersion}; expected $PROTOCOL_VERSION",
            )
        }
        validateRequestId(request.requestId)
        if (request.method.length !in 1..MAX_METHOD_LENGTH) {
            failure(
                RpcErrorCode.INVALID_MESSAGE,
                "method length must be between 1 and $MAX_METHOD_LENGTH characters",
            )
        }
        if (request.method == RPC_CANCEL_METHOD) {
            val cancelRequestId = (request.params["cancelRequestId"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
            if (cancelRequestId == null || !UUID_PATTERN.matches(cancelRequestId)) {
                failure(
                    RpcErrorCode.INVALID_MESSAGE,
                    "$RPC_CANCEL_METHOD requires a canonical UUID params.cancelRequestId",
                )
            }
        }
    }

    private fun validateResponse(response: RpcResponse) {
        if (response.protocolVersion != PROTOCOL_VERSION) {
            failure(
                RpcErrorCode.UNSUPPORTED_VERSION,
                "unsupported protocolVersion ${response.protocolVersion}; expected $PROTOCOL_VERSION",
            )
        }
        validateRequestId(response.requestId)
    }

    private fun validateRequestId(requestId: String) {
        if (!UUID_PATTERN.matches(requestId)) {
            failure(RpcErrorCode.INVALID_MESSAGE, "requestId must be a canonical UUID")
        }
    }

    private fun <T> encodeFrame(value: T, serializer: KSerializer<T>): String {
        val text = try {
            json.encodeToString(serializer, value)
        } catch (exception: SerializationException) {
            failure(RpcErrorCode.INVALID_MESSAGE, "RPC message is not serializable: ${exception.message}")
        } catch (exception: IllegalArgumentException) {
            failure(RpcErrorCode.INVALID_MESSAGE, "RPC message is not serializable: ${exception.message}")
        }

        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_FRAME_BYTES) {
            failure(
                RpcErrorCode.MESSAGE_TOO_LARGE,
                "RPC frame is $bytes UTF-8 bytes; the maximum is $MAX_FRAME_BYTES",
            )
        }
        return text
    }
}
