package darkmatter.androiduse.app.selectors

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import darkmatter.androiduse.app.network.RpcErrorCode
import darkmatter.androiduse.app.network.RpcParseResult
import darkmatter.androiduse.app.network.RpcProtocol
import darkmatter.androiduse.app.network.RpcProtocolError
import darkmatter.androiduse.app.network.RpcProtocolException

/**
 * Wire selector union shared with the TypeScript `selectorSchema`. A selector is
 * resolved against a live snapshot at action time; it is never a durable node
 * handle. The sealed hierarchy uses `type` as the JSON discriminator.
 */
@Serializable
sealed interface Selector {
    val value: String

    @Serializable
    @SerialName("text")
    data class Text(override val value: String, val exact: Boolean? = null) : Selector

    @Serializable
    @SerialName("resourceId")
    data class ResourceId(override val value: String) : Selector

    @Serializable
    @SerialName("description")
    data class Description(override val value: String) : Selector

    @Serializable
    @SerialName("role")
    data class Role(override val value: String, val name: String? = null) : Selector

    @Serializable
    @SerialName("ref")
    data class Ref(override val value: String, val revision: Int) : Selector
}

/**
 * Strict JSON codec for [Selector]. Unknown variants, cross-variant fields,
 * empty values, empty role names, and negative ref revisions are rejected with
 * `INVALID_MESSAGE`, matching the host schema.
 */
object SelectorCodec {

    fun parse(text: String): RpcParseResult<Selector> {
        val element = try {
            RpcProtocol.json.parseToJsonElement(text)
        } catch (exception: SerializationException) {
            return failure("selector is not valid JSON")
        } catch (exception: IllegalArgumentException) {
            return failure("selector is not valid JSON")
        }
        return parse(element)
    }

    fun parse(element: JsonElement): RpcParseResult<Selector> {
        validateRaw(element)?.let { return RpcParseResult.Failure(it) }

        val selector = try {
            RpcProtocol.json.decodeFromJsonElement(
                Selector.serializer(),
                normalizeIntegralRevision(element),
            )
        } catch (exception: SerializationException) {
            return failure("invalid selector: ${exception.message}")
        } catch (exception: IllegalArgumentException) {
            return failure("invalid selector: ${exception.message}")
        }

        val error = validate(selector)
        return if (error == null) RpcParseResult.Success(selector) else RpcParseResult.Failure(error)
    }

    fun serialize(selector: Selector): String {
        validate(selector)?.let { throw RpcProtocolException(it) }
        return RpcProtocol.json.encodeToString(Selector.serializer(), selector)
    }

    private fun validate(selector: Selector): RpcProtocolError? {
        if (selector.value.isEmpty()) {
            return invalid("selector value must not be empty")
        }
        return when (selector) {
            is Selector.Role -> {
                if (selector.name != null && selector.name.isEmpty()) {
                    invalid("role name must not be empty")
                } else {
                    null
                }
            }

            is Selector.Ref -> {
                if (selector.revision < 0) {
                    invalid("ref revision must be non-negative")
                } else {
                    null
                }
            }

            else -> null
        }
    }

    /**
     * kotlinx.serialization coerces quoted numbers to `Int`; validate the raw
     * JSON types so Kotlin rejects exactly what the Zod schema rejects.
     */
    private fun validateRaw(element: JsonElement): RpcProtocolError? {
        val obj = element as? JsonObject ?: return invalid("selector must be a JSON object")
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return invalid("selector type must be a string")
        val value = (obj["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (value.isNullOrEmpty()) {
            return invalid("selector value must be a non-empty string")
        }

        return when (type) {
            "text" -> validateOptionalBoolean(obj["exact"], "text exact")
            "role" -> validateOptionalString(obj["name"], "role name")
            "ref" -> validateRevision(obj["revision"])
            else -> null
        }
    }

    private fun validateOptionalBoolean(element: JsonElement?, label: String): RpcProtocolError? {
        if (element == null) {
            return null
        }
        val primitive = element as? JsonPrimitive
        if (primitive == null || primitive.isString || primitive.content !in setOf("true", "false")) {
            return invalid("$label must be a boolean")
        }
        return null
    }

    private fun validateOptionalString(element: JsonElement?, label: String): RpcProtocolError? {
        if (element == null) {
            return null
        }
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString || primitive.content.isEmpty()) {
            return invalid("$label must be a non-empty string")
        }
        return null
    }

    private fun validateRevision(element: JsonElement?): RpcProtocolError? {
        if (element == null) {
            return invalid("ref revision must be an integer")
        }
        val primitive = element as? JsonPrimitive
        if (primitive == null || integralRevision(primitive) == null) {
            return invalid("ref revision must be an integer")
        }
        return null
    }

    /**
     * JSON numbers are not lexically integers: `7.0`, `7.000`, and `7e0` all
     * denote the integral mathematical value 7. Match the Zod `.int()` contract
     * by accepting any non-negative finite JSON number whose value is integral
     * and exactly representable as an `Int`. Quoted and fractional values stay
     * invalid.
     */
    private fun integralRevision(primitive: JsonPrimitive): Int? {
        if (primitive.isString) {
            return null
        }
        val value = primitive.content.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value < 0.0 || value > Int.MAX_VALUE.toDouble()) {
            return null
        }
        if (kotlin.math.floor(value) != value) {
            return null
        }
        val revision = value.toInt()
        return revision.takeIf { it.toDouble() == value }
    }

    /**
     * kotlinx.serialization decodes an `Int` field only from an integer JSON
     * literal, so replace an accepted integral spelling with its canonical
     * integer before decoding. Serialization always emits the canonical form.
     */
    private fun normalizeIntegralRevision(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (type != "ref") {
            return element
        }
        val revision = obj["revision"] as? JsonPrimitive ?: return element
        val normalized = integralRevision(revision) ?: return element
        return JsonObject(obj + ("revision" to JsonPrimitive(normalized)))
    }

    private fun invalid(message: String): RpcProtocolError =
        RpcProtocolError(RpcErrorCode.INVALID_MESSAGE, message)

    private fun failure(message: String): RpcParseResult.Failure =
        RpcParseResult.Failure(invalid(message))
}
