# "Tap on Mark" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Say "tap on mark" (or "tap the mark"), tap the exact point you mean on a tinted overlay, see it confirmed with an X, and that point gets tapped - no AI targeting involved at all.

**Architecture:** A new `TapOnMarkFloatyWindow` (extending the existing `FullScreenFloatyWindow` base class) shows a tinted, touch-capturing overlay and hands back a marked point's bounds. `AgentBubble` recognizes the callsign locally (before the transcribed instruction would otherwise reach Claude), opens the overlay, and on a marked point runs the exact same `CLICK_BOUNDS` execute/session pipeline every other voice action already uses.

**Tech Stack:** Kotlin, Android `View`/`Canvas`/`WindowManager`, the existing `enhancedfloaty` window library already vendored in this project.

**Spec:** `docs/superpowers/specs/2026-11-09-tap-on-mark-design.md`

## Global Constraints

- No AI call for this feature at all - purely local phrase recognition and a UI interaction.
- Reuses `ActionType.CLICK_BOUNDS` and `ActionScriptGenerator.clickBoundsStep` exactly as they already exist - no codegen changes.
- The marked point's bounds must be computed in true screen coordinates (via `MotionEvent.rawX`/`rawY`), not the overlay view's own local coordinates - grounded during planning against the existing `LayoutBoundsView` precedent, whose drawing code applies a `-statusBarHeight` offset to screen-sourced bounds, confirming this overlay's own view-local coordinate origin is not guaranteed to coincide with true screen (0,0). Drawing the X still uses view-local coordinates (`event.x`/`event.y`), since `Canvas` drawing is naturally in that space - only the actual tap target needs the raw/true-screen values.
- Stop and closing the bubble must dismiss an open mark-overlay, consistent with every other in-flight voice interaction this program has built.

---

### Task 1: `TapOnMarkFloatyWindow`

**Files:**
- Create: `app/src/main/java/org/autojs/autojs/ui/agent/TapOnMarkFloatyWindow.kt`
- Create: `app/src/test/java/org/autojs/autojs/ui/agent/TapOnMarkFloatyWindowTest.kt`
- Modify: `app/src/main/res-i18n/values-en/strings.xml`
- Modify: `app/src/main/res-i18n/values/strings.xml`

**Interfaces:**
- Consumes: `com.stardust.autojs.core.agent.Bounds` (pre-existing), `org.autojs.autojs.ui.floating.FullScreenFloatyWindow` (pre-existing base class, confirmed via decompiling the vendored `enhancedfloaty` AAR during planning: its parent `FloatyWindow` provides a working, callable `close()` and an abstract `onCreateView(FloatyService): View` this task must override), `com.stardust.enhancedfloaty.FloatyService` (pre-existing, `addWindow(FloatyWindow)`/`removeWindow(FloatyWindow)` confirmed via the same decompile).
- Produces: `class TapOnMarkFloatyWindow(onMarked: (Bounds) -> Unit) : FullScreenFloatyWindow()` and `companion object { fun matchesCallsign(instruction: String): Boolean }` - Task 2 constructs one, adds it via `FloatyService.addWindow(...)`, and calls `matchesCallsign` on every transcribed instruction before it would otherwise reach the voice-turn pipeline.

Grounded during planning: `FullScreenFloatyWindow` already supplies a correct `onCreateWindowLayoutParams()` (`MATCH_PARENT`/`MATCH_PARENT`, translucent, `FLAG_LAYOUT_NO_LIMITS`) - this task only needs to override `onCreateView`. The existing `LayoutBoundsFloatyWindow`/`LayoutBoundsView` pair is the direct precedent for both the window-subclassing pattern and the `dispatchKeyEvent`-based back-to-cancel pattern. A plain `View` subclass skips `onDraw()` by default unless `setWillNotDraw(false)` is called - confirmed as a real, easy-to-miss requirement, not assumed.

- [ ] **Step 1: Write the failing test**

```kotlin
package org.autojs.autojs.ui.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapOnMarkFloatyWindowTest {

    @Test
    fun `matches both phrasings of the callsign, case-insensitively and trimmed`() {
        assertTrue(TapOnMarkFloatyWindow.matchesCallsign("tap on mark"))
        assertTrue(TapOnMarkFloatyWindow.matchesCallsign("Tap On Mark"))
        assertTrue(TapOnMarkFloatyWindow.matchesCallsign("  tap the mark  "))
        assertTrue(TapOnMarkFloatyWindow.matchesCallsign("TAP THE MARK"))
    }

    @Test
    fun `does not match an unrelated or merely similar instruction`() {
        assertFalse(TapOnMarkFloatyWindow.matchesCallsign("tap on Login"))
        assertFalse(TapOnMarkFloatyWindow.matchesCallsign("tap on the mark button"))
        assertFalse(TapOnMarkFloatyWindow.matchesCallsign(""))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testCommonDebugUnitTest --tests "org.autojs.autojs.ui.agent.TapOnMarkFloatyWindowTest"`
Expected: FAIL to compile - `TapOnMarkFloatyWindow` does not exist yet.

- [ ] **Step 3: Add the new strings**

In `app/src/main/res-i18n/values-en/strings.xml`, add alongside the other `text_agent_*` strings:

```xml
  <string name="text_agent_tap_on_mark_prompt">Tap the screen where you want to tap</string>
  <string name="text_agent_tap_on_mark_performed">Tapped the marked point</string>
```

In `app/src/main/res-i18n/values/strings.xml`, add alongside the other `text_agent_*` strings:

```xml
    <string name="text_agent_tap_on_mark_prompt">请点按屏幕上你想要点击的位置</string>
    <string name="text_agent_tap_on_mark_performed">已点击标记的位置</string>
```

- [ ] **Step 4: Implement `TapOnMarkFloatyWindow`**

```kotlin
package org.autojs.autojs.ui.agent

import android.content.Context
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
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testCommonDebugUnitTest --tests "org.autojs.autojs.ui.agent.TapOnMarkFloatyWindowTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Compile the full module to verify the Android-dependent parts**

Run: `./gradlew :app:compileCommonDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/autojs/autojs/ui/agent/TapOnMarkFloatyWindow.kt app/src/test/java/org/autojs/autojs/ui/agent/TapOnMarkFloatyWindowTest.kt app/src/main/res-i18n/values-en/strings.xml app/src/main/res-i18n/values/strings.xml
git commit -m "feat(agent): add a tap-on-mark overlay for manually pointing at an untargetable spot"
```

---

### Task 2: Wire "tap on mark" into `AgentBubble`

**Files:**
- Modify: `app/src/main/java/org/autojs/autojs/ui/agent/AgentBubble.kt`

**Interfaces:**
- Consumes: `TapOnMarkFloatyWindow`/`TapOnMarkFloatyWindow.matchesCallsign` (Task 1, same package as `AgentBubble.kt` - no new import needed for the class itself).
- Produces: no new public API - purely new private behavior.

No isolated unit test (real window/touch-dependent control flow) - verified by compile plus Task 3's on-device check.

- [ ] **Step 1: Add the `Bounds` import**

Add to `AgentBubble.kt`'s existing imports, alongside the other `com.stardust.autojs.core.agent.*` imports:

```kotlin
import com.stardust.autojs.core.agent.Bounds
```

- [ ] **Step 2: Add the tracking field**

Add alongside the existing `mTapOnMarkWindow`-adjacent fields - directly below `mVolumeUpHoldListener`'s declaration:

```kotlin
    private var mTapOnMarkWindow: TapOnMarkFloatyWindow? = null
```

- [ ] **Step 3: Recognize the callsign before it would reach the voice-turn pipeline**

Find `listenForInstruction`'s signature:

```kotlin
    private fun listenForInstruction(walkieTalkie: Boolean = false, onTranscribed: (String) -> Unit = { runVoiceTurn(it) }) {
```

Replace with:

```kotlin
    private fun listenForInstruction(
        walkieTalkie: Boolean = false,
        onTranscribed: (String) -> Unit = { text ->
            if (TapOnMarkFloatyWindow.matchesCallsign(text)) startTapOnMark() else runVoiceTurn(text)
        }
    ) {
```

- [ ] **Step 4: Add `startTapOnMark` and `onMarkTapped`**

Add these two methods directly after `onVolumeUpHoldEnd()`:

```kotlin
    /** Invoked on the main thread once "tap on mark"/"tap the mark" is transcribed. */
    private fun startTapOnMark() {
        mWindow?.collapse()
        // Same mutual-exclusion as startVoiceTurn() - this begins a voice
        // session's action the same way any other targeted tap would.
        if (mRecorder.state == SemanticRecorder.State.RECORDING) return
        if (!AccessibilityServiceTool.isAccessibilityServiceEnabled(mContext)) {
            AccessibilityServiceTool.goToAccessibilitySetting()
            return
        }
        AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_tap_on_mark_prompt))
        if (mVoiceSession.state != VoiceAgentSession.State.ACTIVE) {
            // A new session identity starts here too - see
            // mVoiceSessionEpoch's doc comment.
            mVoiceSessionEpoch++
            mVoiceSession.start()
        }
        val sessionEpoch = mVoiceSessionEpoch
        val window = TapOnMarkFloatyWindow { bounds -> onMarkTapped(bounds, sessionEpoch) }
        mTapOnMarkWindow = window
        FloatyService.addWindow(window)
    }

    /** Invoked on the main thread by [TapOnMarkFloatyWindow] once a point is marked. */
    private fun onMarkTapped(bounds: Bounds, sessionEpoch: Int) {
        mTapOnMarkWindow = null
        if (mVoiceSessionEpoch != sessionEpoch) return
        val action = SemanticAction(ActionType.CLICK_BOUNDS, bounds)
        voiceTurnExecutor.execute {
            try {
                if (mVoiceSessionEpoch != sessionEpoch) return@execute
                val succeeded = executeAction(action)
                if (mVoiceSessionEpoch != sessionEpoch) return@execute
                if (succeeded) {
                    mVoiceSession.addAction(action)
                    AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_tap_on_mark_performed))
                } else {
                    AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_action))
                }
            } catch (e: InterruptedException) {
                // close() interrupted this turn via shutdownNow(). The bubble
                // is going away, so there is no longer anyone to report to.
            } catch (e: Exception) {
                AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_action))
            }
        }
    }
```

- [ ] **Step 5: Dismiss an open mark-overlay on Stop**

Find `stopActive()`:

```kotlin
    private fun stopActive() {
        mWindow?.collapse()
        when {
```

Replace with:

```kotlin
    private fun stopActive() {
        mWindow?.collapse()
        mTapOnMarkWindow?.let {
            it.close()
            mTapOnMarkWindow = null
        }
        when {
```

(The rest of `stopActive()`'s `when` block is unchanged.)

- [ ] **Step 6: Dismiss an open mark-overlay on close**

Find `close()`:

```kotlin
    fun close() {
        mSpeechRecognizer?.destroy()
        mSpeechRecognizer = null
        mWalkieTalkieHandler.removeCallbacks(mWalkieTalkieSafetyStopRunnable)
        GlobalKeyObserver.getSingleton().removeVolumeUpHoldListener(mVolumeUpHoldListener)
```

Replace with:

```kotlin
    fun close() {
        mSpeechRecognizer?.destroy()
        mSpeechRecognizer = null
        mWalkieTalkieHandler.removeCallbacks(mWalkieTalkieSafetyStopRunnable)
        GlobalKeyObserver.getSingleton().removeVolumeUpHoldListener(mVolumeUpHoldListener)
        mTapOnMarkWindow?.let {
            it.close()
            mTapOnMarkWindow = null
        }
```

(The rest of `close()` is unchanged.)

- [ ] **Step 7: Compile to verify**

Run: `./gradlew :app:compileCommonDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/org/autojs/autojs/ui/agent/AgentBubble.kt
git commit -m "feat(agent): recognize the tap-on-mark callsign and wire it into the voice-turn pipeline"
```

---

### Task 3: Full build verification and manual on-device check

**Files:** none (verification only).

- [ ] **Step 1: Run the full unit test suite**

Run: `./gradlew :autojs:testDebugUnitTest :app:testCommonDebugUnitTest`
Expected: BUILD SUCCESSFUL - the 2 new `TapOnMarkFloatyWindowTest` tests passing alongside every other existing test.

- [ ] **Step 2: Assemble the debug APK**

Run: `./gradlew :app:assembleCommonDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual on-device verification**

1. Speak "tap on mark". Confirm the screen tints and a prompt toast appears.
2. Tap a specific, identifiable point (e.g. a corner of a visible icon). Confirm a red X appears exactly where you tapped, briefly, before the overlay disappears.
3. Confirm the real tap actually landed at that same point in the underlying app (not offset - this is the specific risk the raw-vs-local coordinate handling in Task 1 is meant to prevent; check especially on a screen where the status bar is visible).
4. Say "tap on mark" again, then press the device Back button before tapping anywhere. Confirm the overlay closes with no action performed.
5. Say "tap on mark", then tap Stop on the bubble before tapping the overlay. Confirm the overlay closes with no action performed and no stray session entry.
6. After a successful mark-and-tap, tap Stop and confirm the step shows up correctly in the saved-script preview (a fixed-coordinate tap, same as a recorded unlabeled tap or an OCR-routed one).
7. Confirm "tap on mark" works whether triggered via the mic button or the volume-up hold (sub-project 2) - both funnel through the same recognition point.

If any check fails, return to the relevant task, fix the issue, re-run its build step, and re-check here before considering this plan complete.
