package org.autojs.autojs.ui.agent

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.stardust.autojs.core.agent.Bounds
import com.stardust.enhancedfloaty.FloatyService
import org.autojs.autojs.ui.floating.FullScreenFloatyWindow
import java.util.Locale

/**
 * A full-screen tinted overlay that waits for one drag gesture, draws a
 * growing rectangle outline as the user drags, then invokes [onRegionMarked]
 * with the final bounds (true screen coordinates) and closes itself. The
 * back key cancels - closes with no callback. See
 * docs/superpowers/specs/2026-11-09-find-image-design.md.
 */
class FindImageFloatyWindow(private val onRegionMarked: (Bounds) -> Unit) : FullScreenFloatyWindow() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreateView(floatyService: FloatyService): View {
        return object : View(floatyService) {
            private val tintPaint = Paint().apply { color = TINT_COLOR }
            private val rectPaint = Paint().apply {
                color = Color.RED
                style = Paint.Style.STROKE
                strokeWidth = RECT_STROKE_WIDTH_PX
            }
            private var startLocalX: Float? = null
            private var startLocalY: Float? = null
            private var startRawX: Float? = null
            private var startRawY: Float? = null
            private var currentLocalX: Float? = null
            private var currentLocalY: Float? = null
            private var finalized = false

            init {
                isFocusable = true
                isFocusableInTouchMode = true
                // A plain View skips onDraw() entirely unless told otherwise -
                // without this neither the tint nor the drag rectangle would
                // ever actually render.
                setWillNotDraw(false)
            }

            override fun onDraw(canvas: Canvas) {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tintPaint)
                val sx = startLocalX
                val sy = startLocalY
                val cx = currentLocalX
                val cy = currentLocalY
                if (sx != null && sy != null && cx != null && cy != null) {
                    canvas.drawRect(minOf(sx, cx), minOf(sy, cy), maxOf(sx, cx), maxOf(sy, cy), rectPaint)
                }
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (finalized) return true
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startLocalX = event.x
                        startLocalY = event.y
                        // Raw (true screen) coordinates captured here too, at
                        // the press point specifically - the final rectangle
                        // needs both corners in true screen space, and rawX/Y
                        // is only available on the event that reports it, not
                        // retroactively from a later one.
                        startRawX = event.rawX
                        startRawY = event.rawY
                        currentLocalX = event.x
                        currentLocalY = event.y
                        invalidate()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        currentLocalX = event.x
                        currentLocalY = event.y
                        invalidate()
                    }
                    MotionEvent.ACTION_UP -> {
                        finalized = true
                        val srx = startRawX
                        val sry = startRawY
                        if (srx == null || sry == null) return true
                        currentLocalX = event.x
                        currentLocalY = event.y
                        invalidate()
                        val endRawX = event.rawX
                        val endRawY = event.rawY
                        var left = minOf(srx, endRawX).toInt()
                        var top = minOf(sry, endRawY).toInt()
                        var right = maxOf(srx, endRawX).toInt()
                        var bottom = maxOf(sry, endRawY).toInt()
                        // A too-small drag (effectively a tap, not a real
                        // rectangle) is expanded to a small default box rather
                        // than producing a near-empty, useless template.
                        if (right - left < MIN_REGION_SIZE_PX) {
                            val centerX = (left + right) / 2
                            left = centerX - MIN_REGION_SIZE_PX / 2
                            right = centerX + MIN_REGION_SIZE_PX / 2
                        }
                        if (bottom - top < MIN_REGION_SIZE_PX) {
                            val centerY = (top + bottom) / 2
                            top = centerY - MIN_REGION_SIZE_PX / 2
                            bottom = centerY + MIN_REGION_SIZE_PX / 2
                        }
                        val bounds = Bounds(left, top, right, bottom)
                        mainHandler.postDelayed({
                            close()
                            onRegionMarked(bounds)
                        }, MARK_CONFIRM_DELAY_MS)
                    }
                }
                return true
            }

            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    close()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
    }

    companion object {
        private const val TINT_COLOR = 0x80000000.toInt()
        private const val RECT_STROKE_WIDTH_PX = 8f
        private const val MIN_REGION_SIZE_PX = 80
        private const val MARK_CONFIRM_DELAY_MS = 500L

        /** True if [instruction] is the "find image" callsign. */
        fun matchesCallsign(instruction: String): Boolean {
            return instruction.trim().lowercase(Locale.getDefault()) == "find image"
        }
    }
}
