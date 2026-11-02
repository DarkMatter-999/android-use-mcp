package darkmatter.androiduse.app.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteOverlayPolicyTest {
    @Test
    fun `screen stays awake only when preference remote control and service are active`() {
        assertTrue(RemoteOverlayPolicy.shouldKeepScreenOn(
            preferenceEnabled = true,
            remoteEnabled = true,
            serviceConnected = true,
        ))
        assertFalse(RemoteOverlayPolicy.shouldKeepScreenOn(
            preferenceEnabled = false,
            remoteEnabled = true,
            serviceConnected = true,
        ))
        assertFalse(RemoteOverlayPolicy.shouldKeepScreenOn(
            preferenceEnabled = true,
            remoteEnabled = false,
            serviceConnected = true,
        ))
        assertFalse(RemoteOverlayPolicy.shouldKeepScreenOn(
            preferenceEnabled = true,
            remoteEnabled = true,
            serviceConnected = false,
        ))
    }

    @Test
    fun `screen-on overlay can attach without visual overlays`() {
        assertTrue(RemoteOverlayPolicy.shouldAttach(
            inspectorEnabled = false,
            traceEnabled = false,
            keepScreenOn = true,
        ))
        assertFalse(RemoteOverlayPolicy.shouldAttach(
            inspectorEnabled = false,
            traceEnabled = false,
            keepScreenOn = false,
        ))
    }
}
