package darkmatter.androiduse.app.network

import darkmatter.androiduse.app.pairing.HostRecord
import darkmatter.androiduse.app.actions.ActionCancellation
import darkmatter.androiduse.app.pairing.PairingManager
import darkmatter.androiduse.app.pairing.PairingStore
import java.net.InetAddress
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcServerTest {

    @Test
    fun rejectsNonPositiveResourceLimitsAndProvidesBoundedDefaults() {
        val defaults = RpcServerLimits()
        assertEquals(8, defaults.maxConnections)
        assertEquals(8, defaults.requestWorkers)
        assertEquals(128, defaults.requestQueueCapacity)
        assertEquals(10_000, defaults.handshakeTimeoutMillis)
        assertEquals(10_000, defaults.writeTimeoutMillis)
        assertEquals(60_000, defaults.idleTimeoutMillis)
        assertEquals(32, defaults.maxInflightPerConnection)
        assertIllegalArgument { RpcServerLimits(maxConnections = 0) }
        assertIllegalArgument { RpcServerLimits(requestWorkers = 0) }
        assertIllegalArgument { RpcServerLimits(requestQueueCapacity = 0) }
        assertIllegalArgument { RpcServerLimits(handshakeTimeoutMillis = 0) }
        assertIllegalArgument { RpcServerLimits(writeTimeoutMillis = 0) }
        assertIllegalArgument { RpcServerLimits(idleTimeoutMillis = 0) }
        assertIllegalArgument { RpcServerLimits(maxInflightPerConnection = 0) }
    }

    @Test
    fun bindPolicyRejectsWildcardAndLoopbackWifiButAllowsExplicitIpv4() {
        assertIllegalArgument { BindMode.Wifi(InetAddress.getByName("0.0.0.0")) }
        assertIllegalArgument { BindMode.Wifi(InetAddress.getByName("127.0.0.1")) }
        assertEquals(InetAddress.getByName("192.0.2.12"), BindMode.Wifi(InetAddress.getByName("192.0.2.12")).address)
    }

    @Test
    fun endpointIsNotPublishedUntilSuccessfulStartAndStopClosesListener() {
        val server = newServer()
        try {
            assertNull(server.endpoint)
            val endpoint = server.start(BindMode.Loopback, 0)
            assertEquals(InetAddress.getByName("127.0.0.1"), endpoint.address)
            assertTrue(endpoint.port > 0)
            assertEquals(endpoint, server.endpoint)
            server.stop()
            assertNull(server.endpoint)
            val rebound = java.net.ServerSocket(endpoint.port, 1, endpoint.address)
            rebound.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun preApprovalBusinessMethodsNeverReachDispatcherButStatusIsReachable() {
        val pairing = newPairingManager()
        val dispatchCount = AtomicInteger()
        val handler = CompanionRpcHandler(
            pairing,
            { JsonObject(emptyMap()) },
            businessDispatcher = { request, _ ->
                dispatchCount.incrementAndGet()
                RpcResponse(PROTOCOL_VERSION, request.requestId)
            },
        )
        val session = SessionIdentity(hostCertificate())

        val status = handler.handle(request("device.status"), session)
        val business = handler.handle(request("session.start"), session)

        assertEquals(null, status.error)
        assertEquals(RpcErrorCode.PAIRING_REQUIRED, business.error?.code)
        assertEquals(0, dispatchCount.get())
    }

    @Test
    fun pairingCodeSubmissionRemainsPendingUntilPhoneApproves() {
        val pairing = newPairingManager()
        val certificate = hostCertificate()
        val challenge = pairing.begin()
        val handler = CompanionRpcHandler(
            pairing,
            businessDispatcher = { request, _ -> RpcResponse(PROTOCOL_VERSION, request.requestId) },
        )
        val params = buildJsonObject {
            put("code", challenge.code)
            put("hostId", HOST_ID)
            put("hostName", "Test Host")
        }

        val response = handler.handle(request("pairing.begin", params), SessionIdentity(certificate))

        assertEquals("WAITING", (response.result as JsonObject)["status"]?.let { (it as JsonPrimitive).content })
        assertEquals(false, pairing.isApproved(SessionIdentity(certificate).fingerprintSha256))
    }

    @Test
    fun deviceStatusCanQueryPairingApprovalWithoutAddingProtocolMethod() {
        val pairing = newPairingManager()
        val certificate = hostCertificate()
        val challenge = pairing.begin()
        pairing.submit(challenge.code, HOST_ID, "Test Host", certificate)
        val handler = CompanionRpcHandler(
            pairing,
            { JsonPrimitive("device-state") },
            businessDispatcher = { request, _ -> RpcResponse(PROTOCOL_VERSION, request.requestId) },
        )

        val response = handler.handle(
            request("device.status", buildJsonObject { put("pairingChallengeId", challenge.challengeId) }),
            SessionIdentity(certificate),
        )

        val pairingResult = (response.result as? JsonObject)?.get("pairing") as? JsonObject
        assertEquals(JsonPrimitive(challenge.challengeId), pairingResult?.get("challengeId"))
        assertEquals(JsonPrimitive("WAITING"), pairingResult?.get("status"))
        assertEquals(null, pairingResult?.get("code"))
    }

    @Test
    fun localApprovalAllowsBusinessDispatchButRemoteApprovalAndRevokeRemainForbidden() {
        val pairing = newPairingManager()
        val certificate = hostCertificate()
        val challenge = pairing.begin()
        pairing.submit(challenge.code, HOST_ID, "Test Host", certificate)
        pairing.approve(HOST_ID)
        val dispatched = AtomicInteger()
        val cancelled = AtomicInteger()
        val handler = CompanionRpcHandler(
            pairing,
            businessDispatcher = { request, _ ->
                dispatched.incrementAndGet()
                RpcResponse(PROTOCOL_VERSION, request.requestId, result = JsonPrimitive(true))
            },
            cancelAction = { cancelled.incrementAndGet(); true },
        )
        val session = SessionIdentity(certificate)

        val business = handler.handle(request("session.start"), session)
        val approve = handler.handle(request("pairing.approve"), session)
        val revoke = handler.handle(request("pairing.revoke"), session)
        val cancel = handler.handle(
            request("rpc.cancel", buildJsonObject { put("cancelRequestId", REQUEST_ID) }),
            session,
        )

        assertEquals(JsonPrimitive(true), business.result)
        assertEquals(RpcErrorCode.UNAUTHORIZED, approve.error?.code)
        assertEquals(RpcErrorCode.UNAUTHORIZED, revoke.error?.code)
        assertEquals(JsonPrimitive(true), cancel.result)
        assertEquals(1, dispatched.get())
        assertEquals(1, cancelled.get())
    }

    @Test
    fun rpcCancelTargetsOnlyTheMatchingActionRequest() {
        val registry = ActionCancellationRegistry()
        val first = ActionCancellation()
        val second = ActionCancellation()
        assertTrue(registry.register(REQUEST_ID, first))
        assertTrue(registry.register("00000000-0000-4000-8000-000000000001", second))

        assertTrue(registry.cancel(REQUEST_ID))

        assertTrue(first.isCancelled())
        assertEquals(false, second.isCancelled())
        assertEquals(false, registry.cancel("00000000-0000-4000-8000-000000000002"))
        assertTrue(registry.remove(REQUEST_ID, first))
    }

    private fun newServer(): RpcServer {
        val pairing = newPairingManager()
        val handler = CompanionRpcHandler(
            pairing,
            { JsonObject(emptyMap()) },
            businessDispatcher = { _, _ ->
                RpcResponse(PROTOCOL_VERSION, "00000000-0000-4000-8000-000000000000")
            },
        )
        return RpcServer(SSLContext.getDefault(), pairing, handler)
    }

    private fun newPairingManager(): PairingManager {
        val store = object : PairingStore {
            private var records = emptyList<HostRecord>()
            override fun load(): List<HostRecord> = records
            override fun save(records: List<HostRecord>) {
                this.records = records.toList()
            }
        }
        return PairingManager(store)
    }

    private fun request(method: String, params: JsonObject = JsonObject(emptyMap())) = RpcRequest(
        PROTOCOL_VERSION,
        REQUEST_ID,
        method,
        params,
    )

    private fun hostCertificate(): X509Certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(javaClass.getResourceAsStream("/darkmatter/androiduse/app/security/host-pairing-test.pem")!!)
        as X509Certificate

    private companion object {
        const val HOST_ID = "11111111-1111-4111-8111-111111111111"
        const val REQUEST_ID = "00000000-0000-4000-8000-000000000000"
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected invalid server policy to be rejected")
        } catch (_: IllegalArgumentException) {
            // Invalid listener bounds and address modes fail before binding.
        }
    }
}
