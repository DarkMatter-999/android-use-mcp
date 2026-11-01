package darkmatter.androiduse.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import darkmatter.androiduse.app.pairing.HostRecord
import darkmatter.androiduse.app.pairing.PairingManager
import darkmatter.androiduse.app.pairing.PairingStore
import kotlinx.serialization.json.JsonObject

class CompanionRpcHandlerTest {
    @Test fun screenshotIsRejectedBeforeHostApprovalAndDispatchedAfterApproval() {
        val identity = TestTlsIdentity.load(javaClass, "client-fixture")
        val manager = PairingManager(object : PairingStore {
            private var records = emptyList<HostRecord>()
            override fun load() = records
            override fun save(records: List<HostRecord>) { this.records = records }
        })
        val challenge = manager.begin()
        manager.submit(challenge.code, HOST_ID, "Test Host", identity.certificate)
        val dispatched = mutableListOf<String>()
        val handler = CompanionRpcHandler(manager, businessDispatcher = { request, _ ->
            dispatched += request.method
            RpcResponse(PROTOCOL_VERSION, request.requestId, result = JsonObject(emptyMap()))
        })
        val session = SessionIdentity(identity.certificate)
        val request = RpcRequest(PROTOCOL_VERSION, REQUEST_ID, "screenshot.capture", JsonObject(emptyMap()))

        assertEquals(RpcErrorCode.PAIRING_REQUIRED, handler.handle(request, session).error?.code)
        assertTrue(dispatched.isEmpty())
        manager.approve(HOST_ID)
        assertTrue(manager.isApproved(session.fingerprintSha256))
        assertEquals(null, handler.handle(request, session).error)
        assertEquals(listOf("screenshot.capture"), dispatched)
    }

    companion object {
        private const val HOST_ID = "11111111-1111-4111-8111-111111111111"
        private const val REQUEST_ID = "22222222-2222-4222-8222-222222222222"
    }
}
