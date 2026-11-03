package darkmatter.androiduse.app.overlay

import kotlin.math.roundToInt
import darkmatter.androiduse.app.actions.ActionLogEntry
import darkmatter.androiduse.app.network.UiBounds
import darkmatter.androiduse.app.network.UiElement
import darkmatter.androiduse.app.network.UiSnapshot

data class OverlayOptions(
    val inspector: Boolean,
    val bounds: Boolean,
    val labels: Boolean,
    val actionTrace: Boolean,
    val cursor: Boolean,
) {
    fun withInspectorEnabled(enabled: Boolean): OverlayOptions = if (enabled) {
        copy(inspector = true)
    } else {
        copy(inspector = false, bounds = false, labels = false, actionTrace = false, cursor = false)
    }
}

data class OverlayDisplay(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val rotationDegrees: Int,
)

enum class OverlayCategory(val argb: Int) {
    FAILURE(0xFFE53935.toInt()),
    TARGET(0xFF2962FF.toInt()),
    EDITABLE(0xFFFFB300.toInt()),
    CLICKABLE(0xFF43A047.toInt()),
    SCROLLABLE(0xFF8E24AA.toInt()),
    OTHER(0xFF78909C.toInt()),
}

data class OverlayElement(
    val ref: String,
    val bounds: UiBounds,
    val category: OverlayCategory,
    val label: String?,
)

data class OverlayFrame(
    val widthPx: Int,
    val heightPx: Int,
    val elements: List<OverlayElement>,
    val actionTrace: List<String>,
    val cursor: OverlayPoint?,
    val labelTextSizePx: Float,
)

data class OverlayPoint(val x: Int, val y: Int)

/** Pure projection from copied snapshots/log records to renderable overlay state. */
object OverlayModel {
    private const val MAX_LABEL_CHARS = 40
    private const val MAX_TRACE_ENTRIES = 5
    private const val LABEL_TEXT_SIZE_DP = 12f
    private val safeActions = setOf(
        "click", "longClick", "fill", "clear", "scroll", "tap", "longPress", "swipe",
        "back", "home", "recents", "notifications",
    )

    fun from(
        snapshot: UiSnapshot,
        recentActions: List<ActionLogEntry>,
        options: OverlayOptions,
        display: OverlayDisplay,
    ): OverlayFrame {
        require(snapshot.width > 0 && snapshot.height > 0) { "snapshot dimensions must be positive" }
        require(display.widthPx > 0 && display.heightPx > 0) { "display dimensions must be positive" }
        require(display.density.isFinite() && display.density > 0f) { "display density must be positive and finite" }
        require(display.rotationDegrees in setOf(0, 90, 180, 270)) { "rotation must be 0, 90, 180, or 270 degrees" }

        if (!options.inspector) {
            return OverlayFrame(
                display.widthPx, display.heightPx, emptyList(), trace(recentActions, options), null,
                LABEL_TEXT_SIZE_DP * display.density,
            )
        }

        val latestTarget = recentActions.firstOrNull { it.targetBounds != null }
        val latestCursorAction = recentActions.firstOrNull {
            it.targetBounds != null || (it.gestureX != null && it.gestureY != null)
        }
        val cursor = if (options.cursor) {
            latestCursorAction?.let { action ->
                action.targetBounds?.let { targetBounds ->
                    val mapped = mapBounds(targetBounds, snapshot, display)
                    OverlayPoint((mapped.left + mapped.right) / 2, (mapped.top + mapped.bottom) / 2)
                } ?: mapBounds(
                    UiBounds(action.gestureX!!, action.gestureY!!, action.gestureX, action.gestureY),
                    snapshot,
                    display,
                ).let { OverlayPoint(it.left, it.top) }
            }
        } else {
            null
        }
        val elements = snapshot.elements.map { element ->
            val isLatestTarget = latestTarget?.targetBounds == element.bounds
            val failedTarget = isLatestTarget && (!latestTarget.accepted || latestTarget.errorCode != null)
            val category = when {
                failedTarget -> OverlayCategory.FAILURE
                isLatestTarget -> OverlayCategory.TARGET
                element.editable -> OverlayCategory.EDITABLE
                element.clickable -> OverlayCategory.CLICKABLE
                element.scrollable -> OverlayCategory.SCROLLABLE
                else -> OverlayCategory.OTHER
            }
            OverlayElement(
                ref = element.ref,
                // Bounds remain mapped for label placement; InspectorOverlay gates rectangle drawing.
                bounds = mapBounds(element.bounds, snapshot, display),
                category = category,
                label = if (options.labels) safeLabel(element) else null,
            )
        }
        return OverlayFrame(
            widthPx = display.widthPx,
            heightPx = display.heightPx,
            elements = elements,
            actionTrace = trace(recentActions, options),
            cursor = cursor,
            labelTextSizePx = LABEL_TEXT_SIZE_DP * display.density,
        )
    }

    private fun safeLabel(element: UiElement): String = when {
        element.editable -> "Editable field"
        else -> (element.text ?: element.contentDescription)
            ?.filterNot { it.isISOControl() }
            ?.trim()
            ?.take(MAX_LABEL_CHARS)
            ?.takeIf { it.isNotEmpty() }
            ?: element.role?.take(MAX_LABEL_CHARS)
            ?: "Element"
    }

    private fun trace(actions: List<ActionLogEntry>, options: OverlayOptions): List<String> {
        if (!options.actionTrace) return emptyList()
        return actions.take(MAX_TRACE_ENTRIES).map { entry ->
            val action = entry.action.takeIf { it in safeActions } ?: "action"
            val outcome = if (entry.accepted && entry.errorCode == null) "accepted" else "failed"
            "$action: $outcome"
        }
    }

    private fun mapBounds(bounds: UiBounds, snapshot: UiSnapshot, display: OverlayDisplay): UiBounds {
        val rotated = when (display.rotationDegrees) {
            0 -> bounds
            90 -> UiBounds(snapshot.height - bounds.bottom, bounds.left, snapshot.height - bounds.top, bounds.right)
            180 -> UiBounds(
                snapshot.width - bounds.right,
                snapshot.height - bounds.bottom,
                snapshot.width - bounds.left,
                snapshot.height - bounds.top,
            )
            else -> UiBounds(bounds.top, snapshot.width - bounds.right, bounds.bottom, snapshot.width - bounds.left)
        }
        val rotatedWidth = if (display.rotationDegrees == 90 || display.rotationDegrees == 270) snapshot.height else snapshot.width
        val rotatedHeight = if (display.rotationDegrees == 90 || display.rotationDegrees == 270) snapshot.width else snapshot.height
        val scaleX = display.widthPx.toDouble() / rotatedWidth
        val scaleY = display.heightPx.toDouble() / rotatedHeight
        return UiBounds(
            left = (rotated.left * scaleX).roundToInt(),
            top = (rotated.top * scaleY).roundToInt(),
            right = (rotated.right * scaleX).roundToInt(),
            bottom = (rotated.bottom * scaleY).roundToInt(),
        )
    }
}
