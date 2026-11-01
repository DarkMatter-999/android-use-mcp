package darkmatter.androiduse.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlToggleTest {

    @Test
    fun `notification visibility is denied when app notifications are disabled`() {
        assertFalse(
            NotificationVisibilityPolicy.canShow(
                permissionGranted = true,
                appNotificationsEnabled = false,
                channelEnabled = true,
            ),
        )
    }

    @Test
    fun `notification visibility is denied when the session channel is blocked`() {
        assertFalse(
            NotificationVisibilityPolicy.canShow(
                permissionGranted = true,
                appNotificationsEnabled = true,
                channelEnabled = false,
            ),
        )
    }

    @Test
    fun `notification visibility requires runtime permission on supported Android versions`() {
        assertFalse(
            NotificationVisibilityPolicy.canShow(
                permissionGranted = false,
                appNotificationsEnabled = true,
                channelEnabled = true,
            ),
        )
    }

    private class Recorder {
        val persisted = mutableListOf<Boolean>()
        var notificationPosted = false
        var notificationCancelled = false
    }

    private fun apply(
        enabled: Boolean,
        accessibilityEnabled: Boolean = true,
        connected: Boolean = true,
        notificationAllowed: Boolean = true,
        poster: () -> Boolean = { true },
    ): Pair<Boolean, Recorder> {
        val recorder = Recorder()
        val accepted = RemoteControlToggle.apply(
            enabled = enabled,
            accessibilityEnabled = accessibilityEnabled,
            connected = connected,
            notificationAllowed = notificationAllowed,
            persistRemoteEnabled = { recorder.persisted += it },
            postNotification = {
                recorder.notificationPosted = true
                poster()
            },
            cancelNotification = { recorder.notificationCancelled = true },
        )
        return accepted to recorder
    }

    @Test
    fun `enabling rolls back and fails closed when the notification post returns false`() {
        val (accepted, recorder) = apply(enabled = true, poster = { false })

        assertFalse(accepted)
        assertEquals(listOf(true, false), recorder.persisted)
        assertTrue(recorder.notificationPosted)
        assertTrue(recorder.notificationCancelled)
    }

    @Test
    fun `enabling rolls back when the notification poster throws`() {
        val (accepted, recorder) = apply(enabled = true, poster = { error("post failed") })

        assertFalse(accepted)
        assertEquals(listOf(true, false), recorder.persisted)
        assertTrue(recorder.notificationCancelled)
    }

    @Test
    fun `enabling succeeds only after the persistent notification is posted`() {
        val (accepted, recorder) = apply(enabled = true)

        assertTrue(accepted)
        assertEquals(listOf(true), recorder.persisted)
        assertTrue(recorder.notificationPosted)
        assertFalse(recorder.notificationCancelled)
    }

    @Test
    fun `enabling is rejected before persistence when prerequisites are missing`() {
        listOf(
            Triple(false, true, true),
            Triple(true, false, true),
            Triple(true, true, false),
        ).forEach { (accessibilityEnabled, connected, notificationAllowed) ->
            val (accepted, recorder) = apply(
                enabled = true,
                accessibilityEnabled = accessibilityEnabled,
                connected = connected,
                notificationAllowed = notificationAllowed,
            )

            assertFalse(accepted)
            assertTrue(recorder.persisted.isEmpty())
            assertFalse(recorder.notificationPosted)
            assertFalse(recorder.notificationCancelled)
        }
    }

    @Test
    fun `disabling persists off and cancels the notification`() {
        val (accepted, recorder) = apply(enabled = false)

        assertTrue(accepted)
        assertEquals(listOf(false), recorder.persisted)
        assertFalse(recorder.notificationPosted)
        assertTrue(recorder.notificationCancelled)
    }
}
