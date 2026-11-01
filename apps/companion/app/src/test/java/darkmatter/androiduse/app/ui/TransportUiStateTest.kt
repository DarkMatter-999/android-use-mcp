package darkmatter.androiduse.app.ui

import darkmatter.androiduse.app.accessibility.ServiceState
import darkmatter.androiduse.app.accessibility.TransportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportUiStateTest {
    @Test
    fun `enabled remote control is not described as an authenticated connection`() {
        assertEquals(
            "Remote control is on. Waiting for an authenticated host.",
            remoteConnectionStatus(ServiceState(remoteEnabled = true)),
        )
        assertEquals(
            "Connected to Example Host.",
            remoteConnectionStatus(ServiceState(remoteEnabled = true, sessionActive = true, activeHost = "Example Host")),
        )
    }

    @Test
    fun `service and home state diagnostic strings omit pairing code and certificate`() {
        val code = "ABCD2345"
        val certificate = "-----BEGIN CERTIFICATE-----PRIVATE-LOOKING-TEST-----END CERTIFICATE-----"
        val service = ServiceState(pairingCode = code, deviceCertificatePem = certificate)
        val ui = HomeUiState(
            deviceModel = "test", apiLevel = 36, accessibilityEnabled = true, accessibilityConnected = true,
            remoteEnabled = true, activeHost = null, transport = darkmatter.androiduse.app.accessibility.TransportKind.NONE,
            sessionActive = false, recentActions = emptyList(), firstRunExplanationVisible = false,
            notificationPermissionMissing = false, statusMessage = "ready", pairingCode = code,
            deviceCertificatePem = certificate,
        )
        assertFalse(service.toString().contains(code))
        assertFalse(service.toString().contains(certificate))
        assertFalse(ui.toString().contains(code))
        assertFalse(ui.toString().contains(certificate))
        assertTrue(ui.statusMessage.contains("ready"))
    }

    @Test
    fun `only a live wifi endpoint is displayed as an address and port`() {
        assertEquals("192.168.1.20:43210", listenerEndpointLabel(TransportKind.WIFI, "192.168.1.20", 43210))
        assertEquals("Wi-Fi listener unavailable", listenerEndpointLabel(TransportKind.WIFI, null, null))
        assertEquals("Loopback (local only)", listenerEndpointLabel(TransportKind.NONE, "127.0.0.1", 43210))
        assertEquals("Not listening", listenerEndpointLabel(TransportKind.NONE, null, null))
    }
}
