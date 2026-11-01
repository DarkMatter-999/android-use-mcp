package darkmatter.androiduse.app.screenshots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotPolicyTest {
    @Test fun rejectsUnsupportedApiAndMissingCapability() {
        assertEquals(ScreenshotFailure.UNSUPPORTED, ScreenshotPolicy.validate(29, true, false, 1, 1))
        assertEquals(ScreenshotFailure.UNAVAILABLE, ScreenshotPolicy.validate(30, false, false, 1, 1))
        assertEquals(ScreenshotFailure.UNSUPPORTED, ScreenshotPolicy.validate(30, true, false, 1, 1))
    }

    @Test fun rejectsProtectedEmptyAndOversizedPayloads() {
        assertEquals(ScreenshotFailure.PROTECTED_CONTENT, ScreenshotPolicy.validate(30, true, true, 0, 1, true))
        assertEquals(ScreenshotFailure.CAPTURE_FAILED, ScreenshotPolicy.validate(30, true, true, 0, 1))
        assertEquals(ScreenshotFailure.SIZE_LIMIT, ScreenshotPolicy.validate(30, true, true, ScreenshotPolicy.MAX_IMAGE_BYTES + 1, 1))
    }

    @Test fun acceptsBoundedPositiveImageAndAlwaysRestoresDecorations() {
        var restored = false
        val result = ScreenshotCapture.captureForTest(
            apiLevel = 34,
            serviceAvailable = true,
            capabilityAvailable = true,
            overlaySuppression = AutoCloseable { restored = true },
            capture = { ScreenshotPayload(byteArrayOf(1, 2, 3), 12, 9, "image/png") },
            timestampMillis = 42L,
            deviceId = "device",
            windowId = "window",
        )
        assertTrue(result is ScreenshotResult.Success)
        assertTrue(restored)
        val success = result as ScreenshotResult.Success
        assertEquals(12, success.width)
        assertEquals(9, success.height)
        assertEquals(42L, success.timestampMillis)
    }

    @Test fun restoresDecorationsAfterCaptureFailure() {
        var restored = false
        val result = ScreenshotCapture.captureForTest(
            34, true, true, AutoCloseable { restored = true },
            capture = { throw IllegalStateException("platform failure") },
            timestampMillis = 42L, deviceId = "device", windowId = "window",
        )
        assertEquals(ScreenshotFailure.CAPTURE_FAILED, (result as ScreenshotResult.Failure).reason)
        assertTrue(restored)
    }
}
