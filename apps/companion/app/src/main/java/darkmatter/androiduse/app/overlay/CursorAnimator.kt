package darkmatter.androiduse.app.overlay

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Best-effort cursor interpolation; callers may cancel or replace it at any time. */
class CursorAnimator(private val view: View) {
    private var animator: ValueAnimator? = null

    fun animateTo(from: OverlayPoint?, to: OverlayPoint, onFrame: (OverlayPoint) -> Unit) {
        cancel()
        val start = from ?: to
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_MILLIS
            interpolator = DecelerateInterpolator()
            addUpdateListener { value ->
                val progress = value.animatedValue as Float
                val point = OverlayPoint(
                    x = (start.x + (to.x - start.x) * progress).toInt(),
                    y = (start.y + (to.y - start.y) * progress).toInt(),
                )
                runCatching { onFrame(point) }
            }
            start()
        }
        view.invalidate()
    }

    fun cancel() {
        animator?.cancel()
        animator = null
    }

    companion object {
        private const val ANIMATION_MILLIS = 180L
    }
}
