package darkmatter.androiduse.app.overlay

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import darkmatter.androiduse.app.actions.ActionLogEntry
import darkmatter.androiduse.app.network.UiBounds

class OverlayLifecycleTest {
    @Test
    fun optionsControlIndependentLayersWithoutChangingRemoteControl() {
        val surface = RecordingSurface()
        val lifecycle = OverlayLifecycle(surface)
        val selected = OverlayOptions(
            inspector = true,
            bounds = false,
            labels = true,
            actionTrace = false,
            cursor = true,
        )

        lifecycle.setOptions(selected)
        lifecycle.update(frame())

        assertEquals(selected, surface.options)
        assertTrue(lifecycle.isVisible)
        assertEquals(false, surface.options?.actionTrace)
        assertEquals(false, surface.options?.bounds)
        lifecycle.close()
    }

    @Test
    fun accessibilityOverlayIsNonTouchableAndNonFocusable() {
        val flags: Int = InspectorOverlay.windowFlags

        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, InspectorOverlay.windowType)
    }

    @Test
    fun updatesAfterServiceDisconnectAreHarmlessAndRemoveTheWindow() {
        val surface = RecordingSurface()
        val lifecycle = OverlayLifecycle(surface)
        lifecycle.setOptions(options())
        lifecycle.update(frame())

        lifecycle.close()
        lifecycle.update(frame())
        lifecycle.setOptions(options())

        assertFalse(lifecycle.isVisible)
        assertEquals(1, surface.detachCount)
    }

    @Test
    fun captureSuppressionRestoresThePriorVisibilityAndCloseIsIdempotent() {
        val surface = RecordingSurface()
        val lifecycle = OverlayLifecycle(surface)
        lifecycle.setOptions(options())
        lifecycle.update(frame())

        val token = lifecycle.hideForCapture()
        assertFalse(lifecycle.isVisible)
        token.close()
        token.close()

        assertTrue(lifecycle.isVisible)
        assertEquals(2, surface.attachCount)
        lifecycle.close()
    }

    @Test
    fun captureFailureStillRestoresTheOverlayInFinally() {
        val surface = RecordingSurface()
        val lifecycle = OverlayLifecycle(surface)
        lifecycle.setOptions(options())
        lifecycle.update(frame())

        val failure = runCatching {
            lifecycle.withDecorationsHidden {
                error("capture failed")
            }
        }.exceptionOrNull()

        assertEquals("capture failed", failure?.message)
        assertTrue(lifecycle.isVisible)
        assertEquals(2, surface.attachCount)
        lifecycle.close()
    }

    @Test
    fun suppressionDoesNotShowAnOverlayThatWasHiddenBeforeCapture() {
        val surface = RecordingSurface()
        val lifecycle = OverlayLifecycle(surface)
        lifecycle.update(frame())

        lifecycle.withDecorationsHidden { "captured" }

        assertFalse(lifecycle.isVisible)
        assertEquals(0, surface.attachCount)
        lifecycle.close()
    }

    @Test
    fun semanticTargetProvidesCursorPointOnlyWhenCursorLayerIsEnabled() {
        val targetBounds = UiBounds(10, 20, 30, 40)
        val action = ActionLogEntry(
            actionId = "action-1", action = "click", targetBounds = targetBounds,
            accepted = true, verified = null, revisionBefore = 1, revisionAfter = 1,
            timestampMillis = 1L, sensitive = false,
        )
        val snapshot = OverlayFrameFixture.snapshot(targetBounds)

        val enabled = OverlayModel.from(snapshot, listOf(action), options(), OverlayDisplay(100, 200, 1f, 0))
        val disabled = OverlayModel.from(snapshot, listOf(action), options().copy(cursor = false), OverlayDisplay(100, 200, 1f, 0))

        assertEquals(OverlayPoint(20, 30), enabled.cursor)
        assertEquals(null, disabled.cursor)
    }

    private fun options() = OverlayOptions(true, true, true, true, true)

    private object OverlayFrameFixture {
        fun snapshot(bounds: UiBounds) = darkmatter.androiduse.app.network.UiSnapshot(
            deviceId = "device-1", windowId = "window-1", revision = 1,
            width = 100, height = 200,
            elements = listOf(darkmatter.androiduse.app.network.UiElement(
                ref = "r1-e0", bounds = bounds, clickable = true, scrollable = false, editable = false,
                enabled = true, focused = false, visible = true, supportedActions = emptyList(),
            )),
            truncated = false,
        )
    }

    private fun frame() = OverlayFrame(
        widthPx = 100,
        heightPx = 200,
        elements = listOf(OverlayElement("r1-e0", UiBounds(1, 2, 3, 4), OverlayCategory.CLICKABLE, "Continue")),
        actionTrace = emptyList(),
        cursor = null,
        labelTextSizePx = 12f,
    )

    private class RecordingSurface : OverlaySurface {
        var options: OverlayOptions? = null
        var attachCount = 0
        var detachCount = 0

        override fun attach(frame: OverlayFrame, options: OverlayOptions) {
            this.options = options
            attachCount += 1
        }

        override fun update(frame: OverlayFrame, options: OverlayOptions) {
            this.options = options
        }

        override fun detach() {
            detachCount += 1
        }
    }
}
