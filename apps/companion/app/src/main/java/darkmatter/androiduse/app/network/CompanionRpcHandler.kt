package darkmatter.androiduse.app.network

import darkmatter.androiduse.app.actions.ActionCancellation
import darkmatter.androiduse.app.pairing.PairingManager
import darkmatter.androiduse.app.pairing.PairingRateLimitException
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class SessionIdentity(
    val peerCertificate: X509Certificate,
    val fingerprintSha256: String = MessageDigest.getInstance("SHA-256")
        .digest(peerCertificate.encoded).joinToString("") { "%02X".format(it) },
)

/** Correlates RPC cancellation requests with the exact in-flight action token. */
class ActionCancellationRegistry {
    private val actions = ConcurrentHashMap<String, ActionCancellation>()

    fun register(requestId: String, cancellation: ActionCancellation): Boolean =
        actions.putIfAbsent(requestId, cancellation) == null

    fun cancel(requestId: String): Boolean = actions[requestId]?.let {
        it.cancel()
        true
    } ?: false

    fun remove(requestId: String, cancellation: ActionCancellation): Boolean =
        actions.remove(requestId, cancellation)
}

/** Restricts pre-approval methods and leaves platform-specific business dispatch injectable. */
class CompanionRpcHandler(
    private val pairingManager: PairingManager,
    private val deviceStatus: (SessionIdentity) -> JsonElement = { JsonObject(emptyMap()) },
    private val businessDispatcher: (RpcRequest, SessionIdentity) -> RpcResponse,
    val cancellationRegistry: ActionCancellationRegistry = ActionCancellationRegistry(),
    private val cancelAction: ((String) -> Boolean)? = null,
    private val pairingChanged: () -> Unit = {},
) {
    fun handle(request: RpcRequest, session: SessionIdentity): RpcResponse {
        if (request.method == "pairing.approve" || request.method == "pairing.revoke") {
            return error(request, RpcErrorCode.UNAUTHORIZED, "pairing approval is phone-local")
        }
        if (request.method == "device.status") {
            val challengeId = request.params.string("pairingChallengeId")
            if (challengeId != null) {
                val status = pairingManager.approvalStatus(challengeId)
                return success(request, buildJsonObject {
                    put("pairing", buildJsonObject {
                        put("challengeId", challengeId)
                        put("status", status.name)
                    })
                })
            }
            return success(request, deviceStatus(session))
        }
        if (request.method == "pairing.begin") return submitPairing(request, session)
        if (request.method !in RpcProtocol.RPC_METHODS) {
            return error(request, RpcErrorCode.UNSUPPORTED, "unknown RPC method")
        }
        if (!pairingManager.isApproved(session.fingerprintSha256)) {
            return error(request, RpcErrorCode.PAIRING_REQUIRED, "host approval is required")
        }
        if (request.method == RPC_CANCEL_METHOD) return cancel(request)
        return try {
            businessDispatcher(request, session)
        } catch (_: Exception) {
            error(request, RpcErrorCode.INTERNAL, "request failed")
        }
    }

    private fun submitPairing(request: RpcRequest, session: SessionIdentity): RpcResponse {
        val code = request.params.string("code")
            ?: return error(request, RpcErrorCode.INVALID_MESSAGE, "pairing code is required")
        val hostId = request.params.string("hostId")
            ?: return error(request, RpcErrorCode.INVALID_MESSAGE, "hostId is required")
        val hostName = request.params.string("hostName")
            ?: return error(request, RpcErrorCode.INVALID_MESSAGE, "hostName is required")
        return try {
            val submitted = pairingManager.submit(code, hostId, hostName, session.peerCertificate)
            pairingChanged()
            success(request, buildJsonObject {
                put("challengeId", submitted.challengeId)
                put("status", submitted.status.name)
            })
        } catch (_: PairingRateLimitException) {
            error(request, RpcErrorCode.UNAUTHORIZED, "pairing attempts are temporarily limited")
        }
    }

    private fun cancel(request: RpcRequest): RpcResponse {
        val requestId = request.params.string("cancelRequestId")
            ?: return error(request, RpcErrorCode.INVALID_MESSAGE, "cancelRequestId is required")
        val cancelled = runCatching {
            cancelAction?.invoke(requestId) ?: cancellationRegistry.cancel(requestId)
        }.getOrDefault(false)
        return success(request, JsonPrimitive(cancelled))
    }

    private fun success(request: RpcRequest, result: JsonElement): RpcResponse =
        RpcResponse(PROTOCOL_VERSION, request.requestId, result = result)

    private fun error(request: RpcRequest, code: RpcErrorCode, message: String): RpcResponse =
        RpcResponse(PROTOCOL_VERSION, request.requestId, error = RpcError(code, message))

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
