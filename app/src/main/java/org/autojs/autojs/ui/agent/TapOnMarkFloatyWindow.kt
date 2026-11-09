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
 * A full-screen tinted overlay that waits for one tap, shows an X at that
 * point, then invokes [onMarked] with a small bounds box centered on it and
 * closes itself. The back key cancels - closes with no callback. See
 * docs/superpowers/specs/2026-11-09-tap-on-mark-design.md.
 */
class TapOnMarkFloatyWindow(private val onMarked: (Bounds) -> Unit) : FullScreenFloatyWindow() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreateView(floatyService: FloatyService): View {
        return object : View(floatyService) {
            private val tintPaint = Paint().apply { color = TINT_COLOR }
            private val markPaint = Paint().apply {
                color = Color.RED
                strokeWidth = MARK_STROKE_WIDTH_PX
            }
            private var markedX: Float? = null
            private var markedY: Float? = null

            init {
                isFocusable = true
                isFocusableInTouchMode = true
                // A plain View skips onDraw() entirely unless told otherwise -
                // without this the tint and the X would never actually render.
                setWillNotDraw(false)
            }

            override fun onDraw(canvas: Canvas) {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tintPaint)
                val x = markedX
                val y = markedY
                if (x != null && y != null) {
                    canvas.drawLine(
                        x - MARK_RADIUS_PX, y - MARK_RADIUS_PX,
                        x + MARK_RADIUS_PX, y + MARK_RADIUS_PX, markPaint
                    )
                    canvas.drawLine(
                        x - MARK_RADIUS_PX, y + MARK_RADIUS_PX,
                        x + MARK_RADIUS_PX, y - MARK_RADIUS_PX, markPaint
                    )
                }
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_UP && markedX == null) {
                    // Local coordinates for drawing the X exactly under the
                    // finger; raw (true screen) coordinates for the actual tap
                    // target, since this view's own coordinate origin is not
                    // guaranteed to coincide with the real screen origin (the
                    // existing LayoutBoundsView precedent applies a
                    // -statusBarHeight correction for the same reason when
                    // drawing screen-sourced bounds) while rawX/rawY always are
                    // true screen coordinates regardless.
                    markedX = event.x
                    markedY = event.y
                    invalidate()
                    val screenX = event.rawX.toInt()
                    val screenY = event.rawY.toInt()
                    val bounds = Bounds(
                        screenX - MARK_RADIUS_PX.toInt(), screenY - MARK_RADIUS_PX.toInt(),
                        screenX + MARK_RADIUS_PX.toInt(), screenY + MARK_RADIUS_PX.toInt()
                    )
                    mainHandler.postDelayed({
                        close()
                        onMarked(bounds)
                    }, MARK_CONFIRM_DELAY_MS)
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
        private const val MARK_RADIUS_PX = 40f
        private const val MARK_STROKE_WIDTH_PX = 8f
        private const val MARK_CONFIRM_DELAY_MS = 500L

        /** True if [instruction] is the "tap on mark" callsign, in either phrasing. */
        fun matchesCallsign(instruction: String): Boolean {
            val normalized = instruction.trim().lowercase(Locale.getDefault())
            return normalized == "tap on mark" || normalized == "tap the mark"
        }
    }
}
