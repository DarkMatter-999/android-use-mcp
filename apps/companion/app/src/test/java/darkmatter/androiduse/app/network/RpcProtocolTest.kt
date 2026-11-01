package darkmatter.androiduse.app.network

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import darkmatter.androiduse.app.selectors.Selector
import darkmatter.androiduse.app.selectors.SelectorCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcProtocolTest {

    private val requestId = "3f8c8b1e-2e4a-4f6b-9c7d-5a1b2c3d4e5f"
    private val snapshotRequestId = "c2f0a7d4-6b1e-4a83-9f52-7d8e1c0b3a69"
    private val actionRequestId = "8e7d6c5b-4a39-4281-9e0f-1a2b3c4d5e6f"
    private val sharedFixtureNames = listOf(
        "status-request.json",
        "snapshot-response.json",
        "action-error.json",
        "action-unverified.json",
        "element-action-role.json",
    )

    private val framePrefix =
        "{\"protocolVersion\":1,\"requestId\":\"$requestId\",\"method\":\"device.status\",\"params\":{\"pad\":\""
    private val frameSuffix = "\"}}"

    // ---------------------------------------------------------------- fixtures

    private fun fixturePath(name: String): File {
        val workingDirectory = System.getProperty("user.dir") ?: "."
        var directory: File? = File(workingDirectory).absoluteFile
        while (directory != null) {
            val candidate = File(directory, "packages/protocol/fixtures/$name")
            if (candidate.isFile) {
                return candidate
            }
            directory = directory.parentFile
        }
        throw AssertionError(
            "Cannot find shared protocol fixtures from ${System.getProperty("user.dir")}; " +
                "expected packages/protocol/fixtures/$name in a parent directory",
        )
    }

    private fun fixture(name: String): String = fixturePath(name).readText(Charsets.UTF_8)

    private fun fixtureObject(name: String): JsonObject =
        Json.parseToJsonElement(fixture(name)).jsonObject

    private fun mutate(name: String, block: MutableMap<String, kotlinx.serialization.json.JsonElement>.() -> Unit): String {
        val values = fixtureObject(name).toMutableMap()
        values.block()
        return JsonObject(values).toString()
    }

    private fun <T> RpcParseResult<T>.expectSuccess(): T =
        (this as? RpcParseResult.Success)?.value
            ?: throw AssertionError("expected a successful parse, got ${(this as RpcParseResult.Failure).error}")

    private fun <T> RpcParseResult<T>.expectFailure(): RpcProtocolError =
        (this as? RpcParseResult.Failure)?.error
            ?: throw AssertionError("expected a failed parse, got success")

    private fun requestFailure(text: String): RpcErrorCode = RpcProtocol.parseRequest(text).expectFailure().code

    private fun responseFailure(text: String): RpcErrorCode = RpcProtocol.parseResponse(text).expectFailure().code

    private fun selectorFailure(text: String): RpcErrorCode = when (val result = SelectorCodec.parse(text)) {
        is RpcParseResult.Failure -> result.error.code
        is RpcParseResult.Success -> throw AssertionError("expected selector failure, got ${result.value}")
    }

    private fun frameOfBytes(bytes: Int): String {
        val contentBytes =
            bytes - framePrefix.toByteArray(Charsets.UTF_8).size - frameSuffix.toByteArray(Charsets.UTF_8).size
        return framePrefix + "a".repeat(contentBytes) + frameSuffix
    }

    private enum class FixtureKind { REQUEST, RESPONSE }

    private data class Mutation(
        val name: String,
        val code: RpcErrorCode,
        val apply: MutableMap<String, kotlinx.serialization.json.JsonElement>.() -> Unit,
    )

    private fun fixtureKind(value: JsonObject): FixtureKind =
        if ((value["method"] as? JsonPrimitive)?.isString == true) FixtureKind.REQUEST else FixtureKind.RESPONSE

    private fun parse(kind: FixtureKind, text: String): RpcParseResult<*> = when (kind) {
        FixtureKind.REQUEST -> RpcProtocol.parseRequest(text)
        FixtureKind.RESPONSE -> RpcProtocol.parseResponse(text)
    }

    private fun failureCode(kind: FixtureKind, text: String): RpcErrorCode = when (val result = parse(kind, text)) {
        is RpcParseResult.Failure -> result.error.code
        is RpcParseResult.Success -> throw AssertionError("expected a failed parse, got ${result.value}")
    }

    // --------------------------------------------------- fixture parity matrix

    @Test
    fun `accepts every shared fixture`() {
        for (name in sharedFixtureNames) {
            val kind = fixtureKind(fixtureObject(name))
            when (val result = parse(kind, fixture(name))) {
                is RpcParseResult.Success -> Unit
                is RpcParseResult.Failure -> throw AssertionError("$name should parse: ${result.error}")
            }
        }
    }

    @Test
    fun `rejects the same shared mutations as the host`() {
        val mutations = listOf(
            Mutation("unknown-version", RpcErrorCode.UNSUPPORTED_VERSION) {
                this["protocolVersion"] = JsonPrimitive(2)
            },
            Mutation("missing-request-id", RpcErrorCode.INVALID_MESSAGE) {
                remove("requestId")
            },
            Mutation("malformed-request-id", RpcErrorCode.INVALID_MESSAGE) {
                this["requestId"] = JsonPrimitive("not-a-uuid")
            },
            Mutation("extra-envelope-field", RpcErrorCode.INVALID_MESSAGE) {
                this["extra"] = JsonPrimitive(true)
            },
            Mutation("unknown-error-code", RpcErrorCode.INVALID_MESSAGE) {
                this["error"] = buildJsonObject {
                    put("code", "NEW_CODE")
                    put("message", "from a future version")
                }
            },
        )

        for (name in sharedFixtureNames) {
            val kind = fixtureKind(fixtureObject(name))

            for (mutation in mutations) {
                val values = fixtureObject(name).toMutableMap()
                mutation.apply(values)
                assertEquals(
                    "$name rejected ${mutation.name}",
                    mutation.code,
                    failureCode(kind, JsonObject(values).toString()),
                )
            }

            val oversized = fixtureObject(name).toMutableMap()
            if (kind == FixtureKind.REQUEST) {
                val params = (oversized["params"] as JsonObject).toMutableMap()
                params["pad"] = JsonPrimitive("a".repeat(MAX_FRAME_BYTES))
                oversized["params"] = JsonObject(params)
            } else {
                oversized["result"] = buildJsonObject { put("pad", "a".repeat(MAX_FRAME_BYTES)) }
            }
            val oversizedText = JsonObject(oversized).toString()
            assertTrue("$name oversized frame", oversizedText.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES)
            assertEquals("$name oversized frame", RpcErrorCode.MESSAGE_TOO_LARGE, failureCode(kind, oversizedText))

            assertEquals(
                "$name malformed JSON",
                RpcErrorCode.INVALID_MESSAGE,
                failureCode(kind, fixture(name) + " trailing"),
            )
        }
    }

    @Test
    fun `method list matches the frozen v1 list`() {
        assertEquals(
            listOf(
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
                "app.launch",
                "rpc.cancel",
            ),
            RpcProtocol.RPC_METHODS,
        )
    }

    // ------------------------------------------------------------- constants

    @Test
    fun `error codes match the frozen v1 list`() {
        assertEquals(
            listOf(
                "INVALID_MESSAGE",
                "UNSUPPORTED_VERSION",
                "MESSAGE_TOO_LARGE",
                "UNAUTHORIZED",
                "PAIRING_REQUIRED",
                "STALE_REF",
                "AMBIGUOUS_TARGET",
                "NOT_FOUND",
                "NOT_ACTIONABLE",
                "UNSUPPORTED",
                "TIMEOUT",
                "CANCELLED",
                "INTERNAL",
            ),
            RpcErrorCode.entries.map { it.name },
        )
    }

    @Test
    fun `frame and method limits match the host`() {
        assertEquals(2 * 1024 * 1024, MAX_FRAME_BYTES)
        assertEquals(128, MAX_METHOD_LENGTH)
        assertEquals(1, PROTOCOL_VERSION)
    }

    // -------------------------------------------------------- shared fixtures

    @Test
    fun `accepts the shared status request fixture`() {
        val request = RpcProtocol.parseRequest(fixture("status-request.json")).expectSuccess()

        assertEquals(1, request.protocolVersion)
        assertEquals(requestId, request.requestId)
        assertEquals("device.status", request.method)
        assertTrue(request.params.isEmpty())
    }

    @Test
    fun `accepts the shared snapshot response fixture and decodes a typed snapshot`() {
        val raw = fixture("snapshot-response.json")
        val response = RpcProtocol.parseResponse(raw).expectSuccess()
        val snapshot = RpcProtocol.decodeSnapshot(requireNotNull(response.result))

        assertEquals(snapshotRequestId, response.requestId)
        assertEquals("emulator-5554", snapshot.deviceId)
        assertEquals("window-42", snapshot.windowId)
        assertEquals(7, snapshot.revision)
        assertEquals("com.android.settings", snapshot.packageName)
        assertEquals("Settings", snapshot.windowTitle)
        assertEquals(1080, snapshot.width)
        assertEquals(2400, snapshot.height)
        assertEquals("[w42r7n0] text \"Network & internet\"", snapshot.compactText)
        assertEquals(false, snapshot.truncated)
        assertEquals(1, snapshot.elements.size)

        val element = snapshot.elements.first()
        assertEquals("w42r7n0", element.ref)
        assertEquals("android.widget.TextView", element.className)
        assertEquals("text", element.role)
        assertEquals("Network & internet", element.text)
        assertEquals("com.android.settings:id/network_dashboard", element.resourceId)
        assertEquals(UiBounds(left = 0, top = 220, right = 1080, bottom = 340), element.bounds)
        assertTrue(element.clickable)
        assertTrue(!element.scrollable)
        assertTrue(!element.editable)
        assertTrue(element.enabled)
        assertTrue(!element.focused)
        assertTrue(element.visible)
        assertEquals(listOf("click"), element.supportedActions)
        assertEquals(emptyList<UiElement>(), element.children)
    }

    @Test
    fun `accepts the shared action error fixture`() {
        val response = RpcProtocol.parseResponse(fixture("action-error.json")).expectSuccess()
        val error = requireNotNull(response.error)

        assertEquals(actionRequestId, response.requestId)
        assertNull(response.result)
        assertEquals(RpcErrorCode.STALE_REF, error.code)
        assertEquals("Snapshot revision 7 is no longer current", error.message)
        assertEquals(7, error.details?.get("revision")?.jsonPrimitive?.int)
    }

    @Test
    fun `action-unverified fixture keeps the required verified key as JSON null`() {
        val response = RpcProtocol.parseResponse(fixture("action-unverified.json")).expectSuccess()
        val result = RpcProtocol.decodeActionResult(requireNotNull(response.result))

        assertEquals("act-20261028-0001", result.actionId)
        assertEquals("click", result.action)
        assertTrue(result.accepted)
        assertNull(result.verified)
        assertNull(result.verification)
        assertEquals("w42r7n0", result.target?.ref)
        assertEquals(listOf(0, 220, 1080, 340), result.target?.bounds)
        assertEquals(7, result.revisionBefore)
        assertEquals(8, result.revisionAfter)

        val encoded = RpcProtocol.encodeActionResult(result).jsonObject
        assertTrue("verified" in encoded)
        assertEquals(JsonNull, encoded["verified"])
        assertEquals(fixtureObject("action-unverified.json")["result"], encoded)

        val rebuilt = response.copy(result = encoded)
        assertEquals(fixtureObject("action-unverified.json"), Json.parseToJsonElement(RpcProtocol.serialize(rebuilt)))
    }

    @Test
    fun `element-action-role fixture carries the frozen role selector`() {
        val request = RpcProtocol.parseRequest(fixture("element-action-role.json")).expectSuccess()
        val selectorJson = requireNotNull(request.params["selector"]).jsonObject

        assertEquals("element.action", request.method)
        assertEquals("click", request.params["action"]?.jsonPrimitive?.content)
        assertEquals(Selector.Role(value = "button", name = "Save"), SelectorCodec.parse(selectorJson).expectSuccess())
        assertEquals(
            selectorJson,
            Json.parseToJsonElement(SelectorCodec.serialize(Selector.Role(value = "button", name = "Save"))),
        )
    }

    // --------------------------------------------------------------- selectors

    @Test
    fun `accepts all five selector variants`() {
        val variants = listOf(
            """{"type":"text","value":"Save"}""" to Selector.Text(value = "Save"),
            """{"type":"text","value":"Save","exact":true}""" to Selector.Text(value = "Save", exact = true),
            """{"type":"resourceId","value":"com.example:id/save"}""" to
                Selector.ResourceId(value = "com.example:id/save"),
            """{"type":"description","value":"Save changes"}""" to
                Selector.Description(value = "Save changes"),
            """{"type":"role","value":"button"}""" to Selector.Role(value = "button"),
            """{"type":"role","value":"button","name":"Save"}""" to
                Selector.Role(value = "button", name = "Save"),
            """{"type":"ref","value":"w42r7n0","revision":7}""" to Selector.Ref(value = "w42r7n0", revision = 7),
        )

        for ((text, expected) in variants) {
            assertEquals(expected, SelectorCodec.parse(text).expectSuccess())
            assertEquals(Json.parseToJsonElement(text), Json.parseToJsonElement(SelectorCodec.serialize(expected)))
        }
    }

    @Test
    fun `accepts integral JSON number spellings for ref revision`() {
        val accepted = listOf(
            """{"type":"ref","value":"w42r7n0","revision":7.0}""",
            """{"type":"ref","value":"w42r7n0","revision":7.000}""",
            """{"type":"ref","value":"w42r7n0","revision":7e0}""",
            """{"type":"ref","value":"w42r7n0","revision":0.0}""",
            """{"type":"ref","value":"w42r7n0","revision":1e1}""",
        )

        for (text in accepted) {
            val expected = when {
                text.contains("0.0") -> Selector.Ref(value = "w42r7n0", revision = 0)
                text.contains("1e1") -> Selector.Ref(value = "w42r7n0", revision = 10)
                else -> Selector.Ref(value = "w42r7n0", revision = 7)
            }
            assertEquals(text, expected, SelectorCodec.parse(text).expectSuccess())
            assertEquals(
                text,
                Json.parseToJsonElement("""{"type":"ref","value":"w42r7n0","revision":${expected.revision}}"""),
                Json.parseToJsonElement(SelectorCodec.serialize(expected)),
            )
        }
    }

    @Test
    fun `rejects malformed selector variants`() {
        val invalid = listOf(
            "null",
            "[]",
            "\"text\"",
            "{}",
            """{"type":"unknown","value":"x"}""",
            """{"type":"text"}""",
            """{"type":"text","value":""}""",
            """{"type":"text","value":"x","exact":"yes"}""",
            """{"type":"text","value":"x","name":"extra"}""",
            """{"type":"resourceId"}""",
            """{"type":"resourceId","value":"x","exact":true}""",
            """{"type":"description","value":7}""",
            """{"type":"role","value":"button","name":""}""",
            """{"type":"role","value":"button","extra":true}""",
            """{"type":"ref","value":"x"}""",
            """{"type":"ref","value":"x","revision":1.5}""",
            """{"type":"ref","value":"x","revision":-1}""",
            """{"type":"ref","value":"x","revision":-1.0}""",
            """{"type":"ref","value":"x","revision":"7"}""",
            """{"type":"ref","value":"x","revision":2147483648}""",
            """{"type":"ref","value":"x","revision":1e999}""",
        )

        for (text in invalid) {
            assertEquals(text, RpcErrorCode.INVALID_MESSAGE, selectorFailure(text))
        }
    }

    // ------------------------------------------------------------- serializing

    @Test
    fun `serializes a request equal to the shared fixture`() {
        val request = RpcRequest(
            protocolVersion = 1,
            requestId = requestId,
            method = "device.status",
            params = JsonObject(emptyMap()),
        )

        assertEquals(fixtureObject("status-request.json"), Json.parseToJsonElement(RpcProtocol.serialize(request)))
    }

    @Test
    fun `serializes a snapshot response equal to the shared fixture`() {
        val snapshot = UiSnapshot(
            deviceId = "emulator-5554",
            windowId = "window-42",
            revision = 7,
            packageName = "com.android.settings",
            windowTitle = "Settings",
            width = 1080,
            height = 2400,
            elements = listOf(
                UiElement(
                    ref = "w42r7n0",
                    className = "android.widget.TextView",
                    role = "text",
                    text = "Network & internet",
                    resourceId = "com.android.settings:id/network_dashboard",
                    bounds = UiBounds(left = 0, top = 220, right = 1080, bottom = 340),
                    clickable = true,
                    scrollable = false,
                    editable = false,
                    enabled = true,
                    focused = false,
                    visible = true,
                    supportedActions = listOf("click"),
                    children = emptyList(),
                ),
            ),
            compactText = "[w42r7n0] text \"Network & internet\"",
            truncated = false,
        )
        val response = RpcResponse(
            protocolVersion = 1,
            requestId = snapshotRequestId,
            result = RpcProtocol.encodeSnapshot(snapshot),
        )

        assertEquals(fixtureObject("snapshot-response.json"), Json.parseToJsonElement(RpcProtocol.serialize(response)))
    }

    @Test
    fun `serializes an action error equal to the shared fixture`() {
        val error = RpcError(
            code = RpcErrorCode.STALE_REF,
            message = "Snapshot revision 7 is no longer current",
            details = buildJsonObject {
                put("ref", "w42r7n0")
                put("revision", 7)
                put("currentRevision", 9)
            },
        )
        val response = RpcResponse(protocolVersion = 1, requestId = actionRequestId, error = error)

        assertEquals(fixtureObject("action-error.json"), Json.parseToJsonElement(RpcProtocol.serialize(response)))
    }

    @Test
    fun `refuses to serialize an invalid or oversized local message`() {
        val wrongVersion = assertThrows(RpcProtocolException::class.java) {
            RpcProtocol.serialize(RpcResponse(protocolVersion = 2, requestId = requestId))
        }
        assertEquals(RpcErrorCode.UNSUPPORTED_VERSION, wrongVersion.error.code)

        val oversized = RpcRequest(
            protocolVersion = 1,
            requestId = requestId,
            method = "device.status",
            params = buildJsonObject { put("pad", "a".repeat(MAX_FRAME_BYTES)) },
        )
        val tooLarge = assertThrows(RpcProtocolException::class.java) { RpcProtocol.serialize(oversized) }
        assertEquals(RpcErrorCode.MESSAGE_TOO_LARGE, tooLarge.error.code)
    }

    // --------------------------------------------------------------- envelopes

    @Test
    fun `accepts a bounded unknown method so dispatch can answer UNSUPPORTED`() {
        val text = mutate("status-request.json") { this["method"] = JsonPrimitive("future.method") }

        assertEquals("future.method", RpcProtocol.parseRequest(text).expectSuccess().method)
    }

    @Test
    fun `accepts explicit nulls for optional envelope fields`() {
        val responseWithNullError = mutate("snapshot-response.json") { this["error"] = JsonNull }
        assertNull(RpcProtocol.parseResponse(responseWithNullError).expectSuccess().error)

        val errorWithNullDetails = mutate("action-error.json") {
            val error = this["error"]!!.jsonObject.toMutableMap()
            error["details"] = JsonNull
            this["error"] = JsonObject(error)
        }
        val error = RpcProtocol.parseResponse(errorWithNullDetails).expectSuccess().error
        assertNotNull(error)
        assertNull(error?.details)
    }

    @Test
    fun `preserves an explicit null result as a present JSON null`() {
        val response = RpcProtocol.parseResponse(
            mutate("snapshot-response.json") { this["result"] = JsonNull },
        ).expectSuccess()

        assertEquals(JsonNull, response.result)

        val serialized = Json.parseToJsonElement(RpcProtocol.serialize(response)).jsonObject
        assertTrue("result" in serialized)
        assertEquals(JsonNull, serialized["result"])
    }

    @Test
    fun `rejects unknown fields nested inside an error object`() {
        val text = mutate("action-error.json") {
            val error = this["error"]!!.jsonObject.toMutableMap()
            error["extra"] = JsonPrimitive(true)
            this["error"] = JsonObject(error)
        }

        assertEquals(RpcErrorCode.INVALID_MESSAGE, responseFailure(text))
    }

    @Test
    fun `rejects unknown protocol versions for requests and responses`() {
        assertEquals(
            RpcErrorCode.UNSUPPORTED_VERSION,
            requestFailure(mutate("status-request.json") { this["protocolVersion"] = JsonPrimitive(2) }),
        )
        assertEquals(
            RpcErrorCode.UNSUPPORTED_VERSION,
            responseFailure(mutate("snapshot-response.json") { this["protocolVersion"] = JsonPrimitive(0) }),
        )
    }

    @Test
    fun `rejects missing or malformed request IDs`() {
        assertEquals(
            RpcErrorCode.INVALID_MESSAGE,
            requestFailure(mutate("status-request.json") { remove("requestId") }),
        )
        assertEquals(
            RpcErrorCode.INVALID_MESSAGE,
            requestFailure(mutate("status-request.json") { this["requestId"] = JsonPrimitive("not-a-uuid") }),
        )
        assertEquals(
            RpcErrorCode.INVALID_MESSAGE,
            responseFailure(mutate("action-error.json") { remove("requestId") }),
        )
    }

    @Test
    fun `rejects method names outside the 1-128 character bounds`() {
        assertEquals(
            RpcErrorCode.INVALID_MESSAGE,
            requestFailure(mutate("status-request.json") { this["method"] = JsonPrimitive("") }),
        )
        assertEquals(
            RpcErrorCode.INVALID_MESSAGE,
            requestFailure(mutate("status-request.json") { this["method"] = JsonPrimitive("a".repeat(MAX_METHOD_LENGTH + 1)) }),
        )
        assertEquals(
            RpcErrorCode.INVALID_MESSAGE,
            requestFailure(mutate("status-request.json") { this["method"] = JsonPrimitive(12) }),
        )
        assertEquals(
            RpcErrorCode.INVALID_MESSAGE,
            requestFailure(mutate("status-request.json") { this["method"] = JsonObject(emptyMap()) }),
        )
        assertEquals(
            MAX_METHOD_LENGTH,
            RpcProtocol.parseRequest(
                mutate("status-request.json") { this["method"] = JsonPrimitive("a".repeat(MAX_METHOD_LENGTH)) },
            ).expectSuccess().method.length,
        )
    }

    @Test
    fun `requires params to be a JSON object`() {
        assertEquals(
            RpcErrorCode.INVALID_MESSAGE,
            requestFailure(mutate("status-request.json") { this["params"] = JsonArray(emptyList()) }),
        )
        assertEquals(
            RpcErrorCode.INVALID_MESSAGE,
            requestFailure(mutate("status-request.json") { remove("params") }),
        )
        assertEquals(
            RpcErrorCode.INVALID_MESSAGE,
            requestFailure(mutate("status-request.json") { this["params"] = JsonPrimitive("x") }),
        )
        assertEquals(
            RpcErrorCode.INVALID_MESSAGE,
            requestFailure(mutate("status-request.json") { this["params"] = JsonPrimitive(3) }),
        )
    }

    @Test
    fun `rejects unknown envelope fields`() {
        assertEquals(
            RpcErrorCode.INVALID_MESSAGE,
            requestFailure(mutate("status-request.json") { this["extra"] = JsonPrimitive(true) }),
        )
        assertEquals(
            RpcErrorCode.INVALID_MESSAGE,
            responseFailure(mutate("snapshot-response.json") { this["method"] = JsonPrimitive("device.status") }),
        )
    }

    @Test
    fun `rejects malformed JSON`() {
        assertEquals(RpcErrorCode.INVALID_MESSAGE, requestFailure("{"))
        assertEquals(RpcErrorCode.INVALID_MESSAGE, requestFailure(""))
        assertEquals(RpcErrorCode.INVALID_MESSAGE, requestFailure("[]"))
        assertEquals(RpcErrorCode.INVALID_MESSAGE, responseFailure("null"))
        assertEquals(RpcErrorCode.INVALID_MESSAGE, requestFailure(fixture("status-request.json") + " trailing"))
    }

    @Test
    fun `rejects malformed error envelopes and unknown codes`() {
        assertEquals(
            RpcErrorCode.INVALID_MESSAGE,
            responseFailure(mutate("action-error.json") { this["error"] = buildJsonObject { put("message", "missing code") } }),
        )
        assertEquals(
            RpcErrorCode.INVALID_MESSAGE,
            responseFailure(mutate("action-error.json") { this["error"] = buildJsonObject { put("code", "STALE_REF") } }),
        )
        assertEquals(
            RpcErrorCode.INVALID_MESSAGE,
            responseFailure(
                mutate("action-error.json") {
                    this["error"] = buildJsonObject {
                        put("code", "NEW_CODE")
                        put("message", "from a future version")
                    }
                },
            ),
        )
        assertEquals(
            RpcErrorCode.INVALID_MESSAGE,
            responseFailure(
                mutate("action-error.json") {
                    this["error"] = buildJsonObject {
                        put("code", "INTERNAL")
                        put("message", "x")
                        put("details", JsonPrimitive(3))
                    }
                },
            ),
        )
    }

    // ------------------------------------------------------------ cancellation

    @Test
    fun `requires rpc cancel to name the active operation`() {
        val cancelId = "c2f0a7d4-6b1e-4a83-9f52-7d8e1c0b3a69"
        val valid = mutate("status-request.json") {
            this["method"] = JsonPrimitive(RPC_CANCEL_METHOD)
            this["params"] = buildJsonObject { put("cancelRequestId", cancelId) }
        }

        assertEquals(cancelId, RpcProtocol.parseRequest(valid).expectSuccess().params["cancelRequestId"]?.jsonPrimitive?.content)

        assertEquals(
            RpcErrorCode.INVALID_MESSAGE,
            requestFailure(
                mutate("status-request.json") {
                    this["method"] = JsonPrimitive(RPC_CANCEL_METHOD)
                    this["params"] = JsonObject(emptyMap())
                },
            ),
        )
        assertEquals(
            RpcErrorCode.INVALID_MESSAGE,
            requestFailure(
                mutate("status-request.json") {
                    this["method"] = JsonPrimitive(RPC_CANCEL_METHOD)
                    this["params"] = buildJsonObject { put("cancelRequestId", "active-op") }
                },
            ),
        )
        assertEquals(
            RpcErrorCode.INVALID_MESSAGE,
            requestFailure(
                mutate("status-request.json") {
                    this["method"] = JsonPrimitive(RPC_CANCEL_METHOD)
                    this["params"] = buildJsonObject { put("cancelRequestId", 7) }
                },
            ),
        )
        assertEquals(
            RpcErrorCode.INVALID_MESSAGE,
            requestFailure(
                mutate("status-request.json") {
                    this["method"] = JsonPrimitive(RPC_CANCEL_METHOD)
                    this["params"] = JsonObject(emptyMap())
                    this["cancelRequestId"] = JsonPrimitive(cancelId)
                },
            ),
        )
    }

    // ------------------------------------------------------------ frame limits

    @Test
    fun `accepts a frame of exactly 2 MiB`() {
        val text = frameOfBytes(MAX_FRAME_BYTES)

        assertEquals(MAX_FRAME_BYTES, text.toByteArray(Charsets.UTF_8).size)
        assertEquals("device.status", RpcProtocol.parseRequest(text).expectSuccess().method)
    }

    @Test
    fun `rejects a valid frame of 2 MiB plus 1 byte with MESSAGE_TOO_LARGE`() {
        val text = frameOfBytes(MAX_FRAME_BYTES + 1)

        assertEquals(MAX_FRAME_BYTES + 1, text.toByteArray(Charsets.UTF_8).size)
        assertEquals(RpcErrorCode.MESSAGE_TOO_LARGE, requestFailure(text))
    }

    @Test
    fun `measures the cap in UTF-8 bytes before JSON parsing`() {
        val contentBytes =
            MAX_FRAME_BYTES + 1 - framePrefix.toByteArray(Charsets.UTF_8).size - frameSuffix.toByteArray(Charsets.UTF_8).size
        val text = framePrefix + "€".repeat((contentBytes + 2) / 3) + frameSuffix

        assertTrue(text.length < MAX_FRAME_BYTES)
        assertTrue(text.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES)
        assertEquals(RpcErrorCode.MESSAGE_TOO_LARGE, requestFailure(text))
    }

    @Test
    fun `checks the size before parsing malformed JSON`() {
        assertEquals(RpcErrorCode.MESSAGE_TOO_LARGE, requestFailure(frameOfBytes(MAX_FRAME_BYTES + 1) + "x"))
        assertEquals(RpcErrorCode.MESSAGE_TOO_LARGE, responseFailure("x".repeat(MAX_FRAME_BYTES + 1)))
    }
}
