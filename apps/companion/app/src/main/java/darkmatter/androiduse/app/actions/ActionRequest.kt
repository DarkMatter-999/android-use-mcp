package darkmatter.androiduse.app.actions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import darkmatter.androiduse.app.selectors.Selector

/** Action names frozen for the native action engine. */
object ActionNames {
    const val CLICK: String = "click"
    const val LONG_CLICK: String = "longClick"
    const val FILL: String = "fill"
    const val CLEAR: String = "clear"
    const val SCROLL: String = "scroll"
    const val TAP: String = "tap"
    const val LONG_PRESS: String = "longPress"
    const val SWIPE: String = "swipe"
    const val BACK: String = "back"
    const val HOME: String = "home"
    const val RECENTS: String = "recents"
    const val NOTIFICATIONS: String = "notifications"

    val nodeActions: Set<String> = setOf(CLICK, LONG_CLICK, FILL, CLEAR, SCROLL)

    val gestureActions: Set<String> = setOf(TAP, LONG_PRESS, SWIPE)

    val globalActions: Set<String> = setOf(BACK, HOME, RECENTS, NOTIFICATIONS)

    val supported: Set<String> = nodeActions + gestureActions + globalActions
}

/** Fixed, phone-side package allowlist; never populated by an RPC caller. */
object AppLaunchPolicy {
    val allowedPackages: Set<String> = setOf("com.android.settings", "darkmatter.androiduse.app")
    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun validate(packageName: String): AppLaunchValidation = when {
        !packagePattern.matches(packageName) -> AppLaunchValidation.INVALID
        packageName !in allowedPackages -> AppLaunchValidation.NOT_ALLOWLISTED
        else -> AppLaunchValidation.ALLOWED
    }
}

enum class AppLaunchValidation { ALLOWED, INVALID, NOT_ALLOWLISTED }

/** Scroll direction for a native node scroll action. */
@Serializable
enum class ScrollDirection {
    @SerialName("up") UP,
    @SerialName("down") DOWN,
    @SerialName("left") LEFT,
    @SerialName("right") RIGHT,
    @SerialName("forward") FORWARD,
    @SerialName("backward") BACKWARD,
}

/**
 * Optional, separately supplied postcondition. Verification is never inferred
 * from "the action call returned true". The postcondition is evaluated against
 * a fresh snapshot and only uses structured existence/filter facts, never
 * arbitrary mounted text.
 */
@Serializable
data class ActionPostcondition(
    val selector: Selector,
    val exists: Boolean = true,
    val clickable: Boolean? = null,
    val editable: Boolean? = null,
    val enabled: Boolean? = null,
    val visible: Boolean? = null,
)

/**
 * One action request for the native engine.
 *
 * [value] carries fill input. It is never logged, returned in an
 * `ActionResult`, or included in an error message; fill/clear records are
 * marked sensitive. Selector text is used only for matching against the current
 * snapshot and is likewise absent from logs/results.
 */
@Serializable
data class ActionRequest(
    val actionId: String,
    val action: String,
    val selector: Selector? = null,
    /** Fill input; intentionally never logged or echoed. */
    val value: String? = null,
    val direction: ScrollDirection? = null,
    /** Coordinate tap/long-press point, or swipe start point. */
    val x: Int? = null,
    val y: Int? = null,
    /** Swipe end point. */
    val endX: Int? = null,
    val endY: Int? = null,
    val durationMillis: Long? = null,
    /** Explicit duplicate-match selection; never defaults to the first match. */
    val index: Int? = null,
    val first: Boolean = false,
    /** Optional expected snapshot revision for non-ref selectors. */
    val expectedRevision: Int? = null,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val postcondition: ActionPostcondition? = null,
) {
    val isSensitive: Boolean
        get() = action == ActionNames.FILL || action == ActionNames.CLEAR

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 5_000
        const val MAX_TIMEOUT_MILLIS: Long = 30_000
        const val MAX_INPUT_CHARS: Int = 10_000
        const val MAX_GESTURE_MILLIS: Long = 10_000
        const val MAX_COORDINATE: Int = 100_000
    }
}
