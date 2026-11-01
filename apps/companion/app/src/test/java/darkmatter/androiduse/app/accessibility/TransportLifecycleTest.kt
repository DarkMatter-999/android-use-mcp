package darkmatter.androiduse.app.accessibility

import darkmatter.androiduse.app.network.BindMode
import darkmatter.androiduse.app.network.RpcEndpoint
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportLifecycleTest {
    @Test
    fun `service restart does not implicitly restore remote or wifi exposure`() {
        assertFalse(RemoteControlToggle.restoreAfterServiceRestart(savedRemoteEnabled = true))
        assertFalse(RemoteControlToggle.restoreAfterServiceRestart(savedRemoteEnabled = false))
    }

    @Test
    fun `listener starts only after notification and accessibility prerequisites`() {
        val calls = mutableListOf<String>()
        val lifecycle = TransportLifecycle(
            start = { mode -> calls += "start:$mode"; endpoint(mode) },
            stop = { calls += "stop" },
        )

        assertFalse(lifecycle.enable(accessibilityReady = false, notificationVisible = true))
        assertFalse(lifecycle.enable(accessibilityReady = true, notificationVisible = false))
        assertTrue(calls.isEmpty())
        assertTrue(lifecycle.enable(accessibilityReady = true, notificationVisible = true))
        assertEquals(listOf("start:${BindMode.Loopback}"), calls)
        assertEquals(TransportKind.NONE, lifecycle.state().transport)
    }

    @Test
    fun `listener startup failure rolls back enabled state and endpoint`() {
        var stopped = 0
        val lifecycle = TransportLifecycle(
            start = { throw IllegalStateException("listener unavailable") },
            stop = { stopped++ },
        )

        assertFalse(lifecycle.enable(accessibilityReady = true, notificationVisible = true))
        assertFalse(lifecycle.state().remoteEnabled)
        assertNull(lifecycle.state().endpoint)
        assertEquals(1, stopped)
    }

    @Test
    fun `wifi bind is independent explicit and teardown clears endpoint`() {
        val modes = mutableListOf<BindMode>()
        var stops = 0
        val lifecycle = TransportLifecycle(
            start = { mode -> modes += mode; endpoint(mode) },
            stop = { stops++ },
        )
        assertTrue(lifecycle.enable(accessibilityReady = true, notificationVisible = true))
        assertTrue(lifecycle.enableWifi(InetAddress.getByName("192.168.1.20")))
        assertEquals(2, modes.size)
        assertTrue(modes.last() is BindMode.Wifi)
        assertEquals(TransportKind.WIFI, lifecycle.state().transport)
        assertTrue(lifecycle.enableLoopback())
        assertEquals(TransportKind.NONE, lifecycle.state().transport)
        assertTrue(modes.last() === BindMode.Loopback)
        lifecycle.disable()
        assertEquals(3, stops)
        assertFalse(lifecycle.state().remoteEnabled)
        assertNull(lifecycle.state().endpoint)
        assertEquals(TransportKind.NONE, lifecycle.state().transport)
    }

    @Test
    fun `unbinding and destroying both stop an active listener idempotently`() {
        var stops = 0
        val lifecycle = TransportLifecycle(start = { endpoint(it) }, stop = { stops++ })
        assertTrue(lifecycle.enable(accessibilityReady = true, notificationVisible = true))
        lifecycle.onUnbind()
        lifecycle.onDestroy()
        assertEquals(1, stops)
        assertFalse(lifecycle.state().remoteEnabled)
    }

    @Test
    fun `home state distinguishes remote enabled from authenticated connection`() {
        val state = ServiceState(remoteEnabled = true, sessionActive = false)
        assertTrue(state.remoteEnabled)
        assertFalse(state.sessionActive)
        assertNull(state.activeHost)
    }

    @Test
    fun `wifi address selection accepts only concrete ipv4 addresses from wifi interfaces`() {
        val selected = WifiAddressSelector.select(
            listOf(
                "eth0" to InetAddress.getByName("10.0.0.3"),
                "wlan0" to InetAddress.getByName("192.168.1.20"),
                "wlan1" to InetAddress.getByName("127.0.0.1"),
                "wlan2" to InetAddress.getByName("169.254.1.1"),
            ),
        )
        assertEquals("192.168.1.20", selected?.hostAddress)
        assertNull(WifiAddressSelector.select(listOf("eth0" to InetAddress.getByName("10.0.0.3"))))
        assertNull(WifiAddressSelector.select(emptyList()))
    }

    @Test
    fun `wifi bind failure closes listener and never reports wifi active`() {
        var starts = 0
        var stops = 0
        val lifecycle = TransportLifecycle(
            start = { mode ->
                starts++
                if (mode is BindMode.Wifi) throw IllegalStateException("address disappeared")
                endpoint(mode)
            },
            stop = { stops++ },
        )
        assertTrue(lifecycle.enable(accessibilityReady = true, notificationVisible = true))

        assertFalse(lifecycle.enableWifi(InetAddress.getByName("192.168.1.20")))

        assertEquals(2, starts)
        assertEquals(2, stops)
        assertFalse(lifecycle.state().remoteEnabled)
        assertNull(lifecycle.state().endpoint)
        assertEquals(TransportKind.NONE, lifecycle.state().transport)
    }

    @Test
    fun `stopping remote control while wifi is active clears exposed endpoint`() {
        val lifecycle = TransportLifecycle(start = { endpoint(it) }, stop = {})
        assertTrue(lifecycle.enable(accessibilityReady = true, notificationVisible = true))
        assertTrue(lifecycle.enableWifi(InetAddress.getByName("192.168.1.20")))
        assertEquals(TransportKind.WIFI, lifecycle.state().transport)

        lifecycle.disable()

        assertFalse(lifecycle.state().remoteEnabled)
        assertNull(lifecycle.state().endpoint)
        assertEquals(TransportKind.NONE, lifecycle.state().transport)
    }

    @Test
    fun `late disconnect event cannot clear a newer controller session`() {
        val tracker = ControllerLeaseState()
        assertTrue(tracker.update("lease-old", active = true))
        assertTrue(tracker.update("lease-new", active = true))
        assertFalse(tracker.update("lease-old", active = false))
        assertEquals("lease-new", tracker.currentLeaseId())
        assertTrue(tracker.update("lease-new", active = false))
        assertNull(tracker.currentLeaseId())
    }

    private fun endpoint(mode: BindMode) = RpcEndpoint(
        address = if (mode is BindMode.Wifi) mode.address else InetAddress.getByName("127.0.0.1"),
        port = 43210,
        bindMode = mode,
    )
}
