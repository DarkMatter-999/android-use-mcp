package darkmatter.androiduse.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.View
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean
import darkmatter.androiduse.app.network.UiBounds

interface OverlaySurface {
    fun attach(frame: OverlayFrame, options: OverlayOptions)
    fun update(frame: OverlayFrame, options: OverlayOptions)
    fun detach()
}

/** Owns visibility, suppression nesting, and failure isolation for an overlay surface. */
class OverlayLifecycle(
    private val surface: OverlaySurface,
    private val onError: (Exception) -> Unit = {},
) : AutoCloseable {
    private var options: OverlayOptions = OverlayOptions(false, false, false, false, false)
    private var frame: OverlayFrame? = null
    private var suppressionDepth = 0
    private var restoreAfterSuppression = false
    private var closed = false

    var isVisible: Boolean = false
        private set

    @Synchronized
    fun setOptions(options: OverlayOptions) {
        if (closed) return
        this.options = options
        synchronizeSurface()
    }

    @Synchronized
    fun update(frame: OverlayFrame) {
        if (closed) return
        this.frame = frame
        synchronizeSurface()
    }

    @Synchronized
    fun hideForCapture(): AutoCloseable {
        if (closed) return AutoCloseable { }
        if (suppressionDepth == 0) {
            restoreAfterSuppression = isVisible
            if (isVisible) detachSurface()
        }
        suppressionDepth += 1
        val released = AtomicBoolean(false)
        return AutoCloseable {
            if (!released.compareAndSet(false, true)) return@AutoCloseable
            synchronized(this) {
                if (suppressionDepth == 0) return@synchronized
                suppressionDepth -= 1
                if (suppressionDepth == 0) {
                    val shouldRestore = restoreAfterSuppression
                    restoreAfterSuppression = false
                    if (shouldRestore && options.inspector && frame != null && !closed) attachSurface()
                }
            }
        }
    }

    fun <T> withDecorationsHidden(block: () -> T): T {
        val token = hideForCapture()
        return try {
            block()
        } finally {
            token.close()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        suppressionDepth = 0
        restoreAfterSuppression = false
        detachSurface()
    }

    @Synchronized
    private fun synchronizeSurface() {
        if (closed || suppressionDepth > 0 || !options.inspector || frame == null) {
            if (isVisible) detachSurface()
            return
        }
        val current = frame ?: return
        try {
            if (isVisible) surface.update(current, options) else surface.attach(current, options)
            isVisible = true
        } catch (error: Exception) {
            onError(error)
            detachSurface()
        }
    }

    private fun attachSurface() {
        val current = frame ?: return
        try {
            surface.attach(current, options)
            isVisible = true
        } catch (error: Exception) {
            onError(error)
            detachSurface()
        }
    }

    private fun detachSurface() {
        if (!isVisible) return
        isVisible = false
        try {
            surface.detach()
        } catch (error: Exception) {
            onError(error)
        }
    }
}

/** Non-interactive accessibility overlay window; no overlay permission is requested. */
class InspectorOverlay(
    context: Context,
    windowManager: WindowManager = context.getSystemService(WindowManager::class.java),
    onError: (Exception) -> Unit = {},
) : AutoCloseable {
    private val surface = WindowSurface(context.applicationContext, windowManager)
    private val lifecycle = OverlayLifecycle(surface, onError)

    fun setOptions(options: OverlayOptions) = lifecycle.setOptions(options)
    fun update(frame: OverlayFrame) = lifecycle.update(frame)
    fun hideForCapture(): AutoCloseable = lifecycle.hideForCapture()
    fun <T> withDecorationsHidden(block: () -> T): T = lifecycle.withDecorationsHidden(block)

    fun showCursor(point: OverlayPoint) = surface.animateCursor(point)

    override fun close() = lifecycle.close()

    private class WindowSurface(
        private val context: Context,
        private val windowManager: WindowManager,
    ) : OverlaySurface {
        private var view: OverlayView? = null

        override fun attach(frame: OverlayFrame, options: OverlayOptions) {
            if (view != null) {
                update(frame, options)
                return
            }
            val overlayView = OverlayView(context).apply { render(frame, options) }
            windowManager.addView(overlayView, layoutParams())
            view = overlayView
        }

        override fun update(frame: OverlayFrame, options: OverlayOptions) {
            view?.render(frame, options)
        }

        override fun detach() {
            val current = view ?: return
            view = null
            current.cancelCursorAnimation()
            windowManager.removeView(current)
        }

        fun animateCursor(point: OverlayPoint) {
            view?.animateCursor(point)
        }

        private fun layoutParams() = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            windowFlags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "AndroidUse inspector"
            x = 0
            y = 0
        }
    }

    private class OverlayView(context: Context) : View(context) {
        private var frame: OverlayFrame? = null
        private var options: OverlayOptions = OverlayOptions(false, false, false, false, false)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val cursorAnimator = CursorAnimator(this)
        private var cursor: OverlayPoint? = null

        fun render(frame: OverlayFrame, options: OverlayOptions) {
            this.frame = frame
            this.options = options
            if (options.cursor && frame.cursor != null && frame.cursor != cursor) {
                animateCursor(frame.cursor)
            } else if (!options.cursor || frame.cursor == null) {
                cursorAnimator.cancel()
                cursor = null
            }
            invalidate()
        }

        fun animateCursor(point: OverlayPoint) {
            cursorAnimator.animateTo(cursor, point) { next -> cursor = next; postInvalidateOnAnimation() }
        }

        fun cancelCursorAnimation() = cursorAnimator.cancel()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val state = frame ?: return
            state.elements.forEach { element ->
                if (options.bounds && element.bounds != EMPTY_BOUNDS) {
                    strokePaint.color = element.category.argb
                    canvas.drawRect(element.bounds.asRectF(), strokePaint)
                }
                if (options.labels && element.label != null) {
                    textPaint.color = element.category.argb
                    textPaint.textSize = state.labelTextSizePx
                    val bounds = element.bounds
                    canvas.drawText(element.label, bounds.left.toFloat(), (bounds.top - 4).coerceAtLeast(state.labelTextSizePx.toInt()).toFloat(), textPaint)
                }
            }
            if (options.actionTrace) {
                textPaint.color = 0xFFFFFFFF.toInt()
                textPaint.textSize = state.labelTextSizePx
                state.actionTrace.forEachIndexed { index, line ->
                    canvas.drawText(line, 12f, state.labelTextSizePx * (index + 1), textPaint)
                }
            }
            if (options.cursor) cursor?.let { point ->
                strokePaint.color = OverlayCategory.TARGET.argb
                canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 18f, strokePaint)
            }
        }

        private fun UiBounds.asRectF() = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

        companion object {
            private val EMPTY_BOUNDS = UiBounds(0, 0, 0, 0)
        }
    }

    companion object {
        val windowFlags: Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val windowType: Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    }
}
