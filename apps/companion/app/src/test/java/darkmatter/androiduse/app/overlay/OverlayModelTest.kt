package darkmatter.androiduse.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import darkmatter.androiduse.app.actions.ActionLogEntry
import darkmatter.androiduse.app.network.RpcErrorCode
import darkmatter.androiduse.app.network.UiBounds
import darkmatter.androiduse.app.network.UiElement
import darkmatter.androiduse.app.network.UiSnapshot

class OverlayModelTest {
    @Test
    fun classifiesActionableCategoriesAndUsesFailureThenTargetPrecedence() {
        val editable = element("editable", editable = true, clickable = true)
        val clickable = element("clickable", clickable = true)
        val scrollable = element("scrollable", scrollable = true)
        val plain = element("plain")
        val snapshot = snapshot(editable, clickable, scrollable, plain)

        val ordinary = OverlayModel.from(snapshot, emptyList(), options(), display())
        assertEquals(
            listOf(OverlayCategory.EDITABLE, OverlayCategory.CLICKABLE, OverlayCategory.SCROLLABLE, OverlayCategory.OTHER),
            ordinary.elements.map { it.category },
        )
        assertEquals(
            listOf(0xFFFFB300.toInt(), 0xFF43A047.toInt(), 0xFF8E24AA.toInt(), 0xFF78909C.toInt()),
            ordinary.elements.map { it.category.argb },
        )

        val target = ActionLogEntry(
            actionId = "action-1", action = "click", targetBounds = clickable.bounds,
            accepted = true, verified = null, revisionBefore = 1, revisionAfter = 1,
            timestampMillis = 1L, sensitive = false,
        )
        val targetFrame = OverlayModel.from(snapshot, listOf(target), options(), display())
        assertEquals(OverlayCategory.TARGET, targetFrame.elements[1].category)

        val failure = target.copy(accepted = false, errorCode = RpcErrorCode.INTERNAL)
        val failureFrame = OverlayModel.from(snapshot, listOf(failure), options(), display())
        assertEquals(OverlayCategory.FAILURE, failureFrame.elements[1].category)
    }

    @Test
    fun usesCompactSafeLabelsAndNeverRendersEditableOrSensitiveText() {
        val ordinary = element("ordinary", text = "Continue", contentDescription = "Continue button")
        val password = element(
            "password", text = "correct-horse-battery-staple", contentDescription = "secret field",
            resourceId = "private.password", editable = true,
        )
        val snapshot = snapshot(ordinary, password, compactText = "correct-horse-battery-staple")
        val sensitiveAction = ActionLogEntry(
            actionId = "action-2", action = "clear", selectorKind = "text", targetResourceId = "private.password",
            accepted = false, verified = false, revisionBefore = 1, revisionAfter = 1,
            timestampMillis = 2L, errorCode = RpcErrorCode.INTERNAL, errorMessage = "secret field value",
            sensitive = true,
        )

        val frame = OverlayModel.from(snapshot, listOf(sensitiveAction), options(), display())
        assertEquals("Continue", frame.elements[0].label)
        assertEquals("Editable field", frame.elements[1].label)
        val serializedLabels = frame.elements.joinToString(" ") { it.label.orEmpty() } + frame.actionTrace.joinToString()
        assertFalse(serializedLabels.contains("correct-horse"))
        assertFalse(serializedLabels.contains("secret field"))
        assertFalse(serializedLabels.contains("private.password"))
        assertFalse(serializedLabels.contains("secret field value"))
        assertEquals("Editable field", frame.elements.first { it.ref == "password" }.label)
    }

    @Test
    fun scalesLabelsByDensityAndMapsBoundsForEachDisplayRotation() {
        val source = snapshot(element("target", bounds = UiBounds(10, 20, 30, 40)), width = 100, height = 200)
        val expected = mapOf(
            0 to (OverlayDisplay(200, 400, 2f, 0) to UiBounds(20, 40, 60, 80)),
            90 to (OverlayDisplay(400, 200, 2f, 90) to UiBounds(320, 20, 360, 60)),
            180 to (OverlayDisplay(200, 400, 2f, 180) to UiBounds(140, 320, 180, 360)),
            270 to (OverlayDisplay(400, 200, 2f, 270) to UiBounds(40, 140, 80, 180)),
        )

        for ((_, fixture) in expected) {
            val (display, bounds) = fixture
            val frame = OverlayModel.from(source, emptyList(), options(), display)
            assertEquals(bounds, frame.elements.single().bounds)
            assertEquals(24f, frame.labelTextSizePx)
        }
    }

    @Test
    fun labelsKeepMappedPlacementWhenRectangleLayerIsDisabled() {
        val source = snapshot(element("target", text = "Continue", bounds = UiBounds(10, 20, 30, 40)))
        val frame = OverlayModel.from(
            source,
            emptyList(),
            options().copy(bounds = false),
            display(width = 200, height = 400),
        )

        assertEquals("Continue", frame.elements.single().label)
        assertEquals(UiBounds(20, 40, 60, 80), frame.elements.single().bounds)
    }

    @Test
    fun cursorUsesSafeCoordinateGesturePointWhenNoElementTargetExists() {
        val action = ActionLogEntry(
            actionId = "gesture-1", action = "swipe", gestureX = 15, gestureY = 25,
            accepted = true, verified = null, revisionBefore = 1, revisionAfter = 1,
            timestampMillis = 1, sensitive = false,
        )

        val frame = OverlayModel.from(snapshot(element("plain")), listOf(action), options(), display())

        assertEquals(OverlayPoint(15, 25), frame.cursor)
    }

    @Test
    fun rejectsInvalidDisplayDimensionsDensityAndRotation() {
        val source = snapshot(element("target"))
        val invalid = listOf(
            OverlayDisplay(0, 100, 1f, 0),
            OverlayDisplay(100, -1, 1f, 0),
            OverlayDisplay(100, 100, 0f, 0),
            OverlayDisplay(100, 100, Float.NaN, 0),
            OverlayDisplay(100, 100, 1f, 45),
        )
        for (display in invalid) {
            assertTrue(runCatching { OverlayModel.from(source, emptyList(), options(), display) }.isFailure)
        }
    }

    private fun options() = OverlayOptions(inspector = true, bounds = true, labels = true, actionTrace = true, cursor = true)

    private fun display(width: Int = 100, height: Int = 200, density: Float = 1f, rotation: Int = 0) =
        OverlayDisplay(width, height, density, rotation)

    private fun snapshot(vararg elements: UiElement, width: Int = 100, height: Int = 200, compactText: String? = null) =
        UiSnapshot(
            deviceId = "device-1", windowId = "window-1", revision = 1,
            width = width, height = height, elements = elements.toList(), compactText = compactText, truncated = false,
        )

    private fun element(
        ref: String,
        text: String? = null,
        contentDescription: String? = null,
        resourceId: String? = null,
        bounds: UiBounds = UiBounds(10, 20, 30, 40),
        clickable: Boolean = false,
        scrollable: Boolean = false,
        editable: Boolean = false,
    ) = UiElement(
        ref = ref, text = text, contentDescription = contentDescription, resourceId = resourceId,
        bounds = bounds, clickable = clickable, scrollable = scrollable, editable = editable,
        enabled = true, focused = false, visible = true, supportedActions = emptyList(),
    )
}
