package darkmatter.androiduse.app.screenshots

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

sealed interface ScreenshotResult {
    data class Success(
        val bytes: ByteArray,
        val mimeType: String,
        val width: Int,
        val height: Int,
        val timestampMillis: Long,
        val deviceId: String,
        val windowId: String,
    ) : ScreenshotResult
    data class Failure(val reason: ScreenshotFailure) : ScreenshotResult
}

enum class ScreenshotFailure { UNSUPPORTED, UNAVAILABLE, PROTECTED_CONTENT, CAPTURE_FAILED, SIZE_LIMIT }

data class ScreenshotPayload(val bytes: ByteArray, val width: Int, val height: Int, val mimeType: String)

object ScreenshotPolicy {
    // Base64 expansion and JSON overhead keep the WebSocket frame below 2 MiB.
    const val MAX_IMAGE_BYTES = 1_400_000
    const val CALLBACK_TIMEOUT_MILLIS = 5_000L

    fun validate(
        apiLevel: Int,
        serviceAvailable: Boolean,
        capabilityAvailable: Boolean,
        byteCount: Int,
        width: Int,
        protectedContent: Boolean = false,
        height: Int = width,
    ): ScreenshotFailure? = when {
        apiLevel < 30 -> ScreenshotFailure.UNSUPPORTED
        !serviceAvailable -> ScreenshotFailure.UNAVAILABLE
        !capabilityAvailable -> ScreenshotFailure.UNSUPPORTED
        protectedContent -> ScreenshotFailure.PROTECTED_CONTENT
        width <= 0 || height <= 0 -> ScreenshotFailure.CAPTURE_FAILED
        byteCount <= 0 -> ScreenshotFailure.CAPTURE_FAILED
        byteCount > MAX_IMAGE_BYTES -> ScreenshotFailure.SIZE_LIMIT
        else -> null
    }
}

object ScreenshotCapture {
    fun capture(
        service: AccessibilityService,
        deviceId: String,
        windowId: String,
        hideDecorations: () -> AutoCloseable,
    ): ScreenshotResult {
        val canCapture = Build.VERSION.SDK_INT >= 30 &&
            (service.serviceInfo?.capabilities ?: 0) and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0
        if (Build.VERSION.SDK_INT < 30 || !canCapture) {
            return ScreenshotResult.Failure(
                ScreenshotPolicy.validate(Build.VERSION.SDK_INT, true, canCapture, 1, 1) ?: ScreenshotFailure.UNSUPPORTED,
            )
        }
        val suppression = runCatching(hideDecorations).getOrElse { return ScreenshotResult.Failure(ScreenshotFailure.CAPTURE_FAILED) }
        return try {
            val latch = CountDownLatch(1)
            var image: Bitmap? = null
            var error: Int? = null
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    try {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        image = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                        hardwareBuffer.close()
                    } catch (_: RuntimeException) {
                        error = -1
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    error = errorCode
                    latch.countDown()
                }
            })
            if (!latch.await(ScreenshotPolicy.CALLBACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                return ScreenshotResult.Failure(ScreenshotFailure.CAPTURE_FAILED)
            }
            val bitmap = image ?: return ScreenshotResult.Failure(
                if (error == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) ScreenshotFailure.PROTECTED_CONTENT
                else ScreenshotFailure.CAPTURE_FAILED,
            )
            encode(bitmap, deviceId, windowId)
        } catch (_: Exception) {
            ScreenshotResult.Failure(ScreenshotFailure.CAPTURE_FAILED)
        } finally {
            runCatching { suppression.close() }
        }
    }

    internal fun captureForTest(
        apiLevel: Int,
        serviceAvailable: Boolean,
        capabilityAvailable: Boolean,
        overlaySuppression: AutoCloseable,
        capture: () -> ScreenshotPayload,
        timestampMillis: Long,
        deviceId: String,
        windowId: String,
    ): ScreenshotResult {
        val gate = ScreenshotPolicy.validate(apiLevel, serviceAvailable, capabilityAvailable, 1, 1)
        if (gate != null) return ScreenshotResult.Failure(gate)
        return try {
            val payload = capture()
            val failure = ScreenshotPolicy.validate(
                apiLevel, serviceAvailable, capabilityAvailable, payload.bytes.size,
                payload.width, height = payload.height,
            )
            if (failure != null) ScreenshotResult.Failure(failure)
            else ScreenshotResult.Success(payload.bytes, payload.mimeType, payload.width, payload.height, timestampMillis, deviceId, windowId)
        } catch (_: Exception) {
            ScreenshotResult.Failure(ScreenshotFailure.CAPTURE_FAILED)
        } finally {
            runCatching { overlaySuppression.close() }
        }
    }

    private fun encode(bitmap: Bitmap, deviceId: String, windowId: String): ScreenshotResult {
        try {
            if (bitmap.width <= 0 || bitmap.height <= 0) return ScreenshotResult.Failure(ScreenshotFailure.CAPTURE_FAILED)
            var bytes: ByteArray? = null
            for (quality in listOf(82, 70, 58, 46, 35)) {
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                val encoded = output.toByteArray()
                if (encoded.size <= ScreenshotPolicy.MAX_IMAGE_BYTES) {
                    bytes = encoded
                    break
                }
            }
            val payload = bytes ?: return ScreenshotResult.Failure(ScreenshotFailure.SIZE_LIMIT)
            return ScreenshotResult.Success(payload, "image/jpeg", bitmap.width, bitmap.height, System.currentTimeMillis(), deviceId, windowId)
        } finally {
            bitmap.recycle()
        }
    }
}
