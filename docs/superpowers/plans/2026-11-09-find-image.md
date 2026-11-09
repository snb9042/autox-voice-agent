# "Find Image" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Say "find image", drag a rectangle around something on a tinted overlay, and the agent captures that region as a template, finds and taps it now, and (saved into a script) finds and taps it correctly again later even if it has moved.

**Architecture:** A new `FindImageFloatyWindow` (structurally parallel to `TapOnMarkFloatyWindow`, same `FullScreenFloatyWindow` base) captures a dragged rectangle in true screen coordinates. `AgentBubble` recognizes the callsign locally, feeds the marked region through the existing `ScreenStateReader.captureTemplateAsync` to produce a template file, and runs the resulting `CLICK_IMAGE` action through the exact same execute/session pipeline every other voice action already uses - no new image-matching or codegen.

**Tech Stack:** Kotlin, Android `View`/`Canvas`/`WindowManager`, the existing `enhancedfloaty` window library and `ScreenStateReader` capture machinery.

**Spec:** `docs/superpowers/specs/2026-11-09-find-image-design.md`

## Global Constraints

- No AI call for this feature - purely local phrase recognition and a UI interaction, same posture as "tap on mark".
- Reuses `ActionType.CLICK_IMAGE`, `ActionScriptGenerator.clickImageStep`, and `ScreenStateReader.captureTemplateAsync`/`deleteTemplate` exactly as they already exist - confirmed during planning that `clickImageStep` never reads `SemanticAction.bounds`, only `imageTemplatePath`, so the original capture position is irrelevant at execution time (this is what makes it "adaptive" already, with no new matching code).
- The marked region's bounds passed to `captureTemplateAsync` must be true screen coordinates (via `MotionEvent.rawX`/`rawY` captured at both the press and release points), not the overlay view's own local coordinates - same reasoning already applied in `TapOnMarkFloatyWindow`.
- A failed capture or a failed immediate click deletes the orphaned template file via the existing `deleteTemplate` rather than leaking it.
- Stop and closing the bubble must dismiss an open find-image overlay, consistent with "tap on mark".

---

### Task 1: `FindImageFloatyWindow`

**Files:**
- Create: `app/src/main/java/org/autojs/autojs/ui/agent/FindImageFloatyWindow.kt`
- Create: `app/src/test/java/org/autojs/autojs/ui/agent/FindImageFloatyWindowTest.kt`
- Modify: `app/src/main/res-i18n/values-en/strings.xml`
- Modify: `app/src/main/res-i18n/values/strings.xml`

**Interfaces:**
- Consumes: `com.stardust.autojs.core.agent.Bounds` (pre-existing), `org.autojs.autojs.ui.floating.FullScreenFloatyWindow` (pre-existing base class, same as used by `TapOnMarkFloatyWindow`).
- Produces: `class FindImageFloatyWindow(onRegionMarked: (Bounds) -> Unit) : FullScreenFloatyWindow()` and `companion object { fun matchesCallsign(instruction: String): Boolean }` - Task 2 constructs one, adds it via `FloatyService.addWindow(...)`, and checks `matchesCallsign` alongside the existing `TapOnMarkFloatyWindow.matchesCallsign` check.

Grounded during planning: identical window-lifecycle facts as `TapOnMarkFloatyWindow`'s own Task 1 (confirmed via the same `enhancedfloaty` AAR decompile earlier in this program - `FloatyWindow.close()` is callable, `onCreateView(FloatyService): View` is the required override, `FullScreenFloatyWindow` already supplies the window layout params). This task differs from `TapOnMarkFloatyWindow` only in tracking a drag (`ACTION_DOWN`→`ACTION_MOVE`→`ACTION_UP`) instead of a single tap, and in capturing raw coordinates at *both* the start and end points (not just one), since the final rectangle needs both corners in true screen space.

- [ ] **Step 1: Write the failing test**

```kotlin
package org.autojs.autojs.ui.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FindImageFloatyWindowTest {

    @Test
    fun `matches the callsign, case-insensitively and trimmed`() {
        assertTrue(FindImageFloatyWindow.matchesCallsign("find image"))
        assertTrue(FindImageFloatyWindow.matchesCallsign("Find Image"))
        assertTrue(FindImageFloatyWindow.matchesCallsign("  FIND IMAGE  "))
    }

    @Test
    fun `does not match an unrelated or merely similar instruction`() {
        assertFalse(FindImageFloatyWindow.matchesCallsign("find the Login button"))
        assertFalse(FindImageFloatyWindow.matchesCallsign("find an image of a cat"))
        assertFalse(FindImageFloatyWindow.matchesCallsign(""))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testCommonDebugUnitTest --tests "org.autojs.autojs.ui.agent.FindImageFloatyWindowTest"`
Expected: FAIL to compile - `FindImageFloatyWindow` does not exist yet.

- [ ] **Step 3: Add the new strings**

In `app/src/main/res-i18n/values-en/strings.xml`, add alongside the other `text_agent_*` strings:

```xml
  <string name="text_agent_find_image_prompt">Drag a box around what you want to find</string>
  <string name="text_agent_find_image_performed">Tapped the matched image</string>
```

In `app/src/main/res-i18n/values/strings.xml`, add alongside the other `text_agent_*` strings:

```xml
    <string name="text_agent_find_image_prompt">请在你想要查找的内容周围拖拽选框</string>
    <string name="text_agent_find_image_performed">已点击匹配的图像</string>
```

- [ ] **Step 4: Implement `FindImageFloatyWindow`**

```kotlin
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
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testCommonDebugUnitTest --tests "org.autojs.autojs.ui.agent.FindImageFloatyWindowTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Compile the full module to verify the Android-dependent parts**

Run: `./gradlew :app:compileCommonDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/autojs/autojs/ui/agent/FindImageFloatyWindow.kt app/src/test/java/org/autojs/autojs/ui/agent/FindImageFloatyWindowTest.kt app/src/main/res-i18n/values-en/strings.xml app/src/main/res-i18n/values/strings.xml
git commit -m "feat(agent): add a find-image overlay for marking a reusable visual target"
```

---

### Task 2: Wire "find image" into `AgentBubble`

**Files:**
- Modify: `app/src/main/java/org/autojs/autojs/ui/agent/AgentBubble.kt`

**Interfaces:**
- Consumes: `FindImageFloatyWindow`/`FindImageFloatyWindow.matchesCallsign` (Task 1, same package - no new import needed for the class itself), `ScreenStateReader.captureTemplateAsync`/`deleteTemplate` (pre-existing, confirmed signatures during planning), `ActionType.CLICK_IMAGE` (pre-existing).
- Produces: no new public API - purely new private behavior.

No isolated unit test (real window/touch/capture-dependent control flow) - verified by compile plus Task 3's on-device check.

- [ ] **Step 1: Add the tracking field**

Add directly below the existing `mTapOnMarkWindow` field:

```kotlin
    private var mTapOnMarkWindow: TapOnMarkFloatyWindow? = null
    private var mFindImageWindow: FindImageFloatyWindow? = null
```

- [ ] **Step 2: Recognize the second callsign alongside the first**

Find `listenForInstruction`'s signature:

```kotlin
    private fun listenForInstruction(
        walkieTalkie: Boolean = false,
        onTranscribed: (String) -> Unit = { text ->
            if (TapOnMarkFloatyWindow.matchesCallsign(text)) startTapOnMark() else runVoiceTurn(text)
        }
    ) {
```

Replace with:

```kotlin
    private fun listenForInstruction(
        walkieTalkie: Boolean = false,
        onTranscribed: (String) -> Unit = { text ->
            when {
                TapOnMarkFloatyWindow.matchesCallsign(text) -> startTapOnMark()
                FindImageFloatyWindow.matchesCallsign(text) -> startFindImage()
                else -> runVoiceTurn(text)
            }
        }
    ) {
```

- [ ] **Step 3: Add `startFindImage` and `onImageRegionMarked`**

Add these two methods directly after `onMarkTapped(bounds: Bounds, sessionEpoch: Int)` (the method added in the "tap on mark" plan):

```kotlin
    /** Invoked on the main thread once "find image" is transcribed. */
    private fun startFindImage() {
        mWindow?.collapse()
        // Same mutual-exclusion as startVoiceTurn()/startTapOnMark().
        if (mRecorder.state == SemanticRecorder.State.RECORDING) return
        if (!AccessibilityServiceTool.isAccessibilityServiceEnabled(mContext)) {
            AccessibilityServiceTool.goToAccessibilitySetting()
            return
        }
        AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_find_image_prompt))
        if (mVoiceSession.state != VoiceAgentSession.State.ACTIVE) {
            // A new session identity starts here too - see
            // mVoiceSessionEpoch's doc comment.
            mVoiceSessionEpoch++
            mVoiceSession.start()
        }
        val sessionEpoch = mVoiceSessionEpoch
        val window = FindImageFloatyWindow { bounds -> onImageRegionMarked(bounds, sessionEpoch) }
        mFindImageWindow = window
        FloatyService.addWindow(window)
    }

    /** Invoked on the main thread by [FindImageFloatyWindow] once a region is marked. */
    private fun onImageRegionMarked(bounds: Bounds, sessionEpoch: Int) {
        mFindImageWindow = null
        if (mVoiceSessionEpoch != sessionEpoch) return
        val service = AccessibilityService.instance
        if (service == null) {
            AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_action))
            return
        }
        mScreenStateReader.captureTemplateAsync(service, bounds) { path ->
            if (mVoiceSessionEpoch != sessionEpoch) {
                // The session this capture belonged to is already gone - the
                // template would never be referenced by anything; don't leave
                // it on disk.
                mScreenStateReader.deleteTemplate(path)
                return@captureTemplateAsync
            }
            if (path == null) {
                AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_action))
                return@captureTemplateAsync
            }
            val action = SemanticAction(ActionType.CLICK_IMAGE, bounds, imageTemplatePath = path)
            voiceTurnExecutor.execute {
                try {
                    if (mVoiceSessionEpoch != sessionEpoch) {
                        mScreenStateReader.deleteTemplate(path)
                        return@execute
                    }
                    val succeeded = executeAction(action)
                    if (mVoiceSessionEpoch != sessionEpoch) {
                        mScreenStateReader.deleteTemplate(path)
                        return@execute
                    }
                    if (succeeded) {
                        mVoiceSession.addAction(action)
                        AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_find_image_performed))
                    } else {
                        mScreenStateReader.deleteTemplate(path)
                        AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_action))
                    }
                } catch (e: InterruptedException) {
                    // close() interrupted this turn via shutdownNow(). The bubble
                    // is going away, so there is no longer anyone to report to.
                } catch (e: Exception) {
                    mScreenStateReader.deleteTemplate(path)
                    AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_action))
                }
            }
        }
    }
```

- [ ] **Step 4: Dismiss an open find-image overlay on Stop**

Find `stopActive()`:

```kotlin
    private fun stopActive() {
        mWindow?.collapse()
        mTapOnMarkWindow?.let {
            it.close()
            mTapOnMarkWindow = null
        }
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
        mFindImageWindow?.let {
            it.close()
            mFindImageWindow = null
        }
        when {
```

- [ ] **Step 5: Dismiss an open find-image overlay on close**

Find `close()`:

```kotlin
        GlobalKeyObserver.getSingleton().removeVolumeUpHoldListener(mVolumeUpHoldListener)
        mTapOnMarkWindow?.let {
            it.close()
            mTapOnMarkWindow = null
        }
```

Replace with:

```kotlin
        GlobalKeyObserver.getSingleton().removeVolumeUpHoldListener(mVolumeUpHoldListener)
        mTapOnMarkWindow?.let {
            it.close()
            mTapOnMarkWindow = null
        }
        mFindImageWindow?.let {
            it.close()
            mFindImageWindow = null
        }
```

- [ ] **Step 6: Compile to verify**

Run: `./gradlew :app:compileCommonDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/autojs/autojs/ui/agent/AgentBubble.kt
git commit -m "feat(agent): recognize the find-image callsign and wire it into the voice-turn pipeline"
```

---

### Task 3: Full build verification and manual on-device check

**Files:** none (verification only).

- [ ] **Step 1: Run the full unit test suite**

Run: `./gradlew :autojs:testDebugUnitTest :app:testCommonDebugUnitTest`
Expected: BUILD SUCCESSFUL - the 2 new `FindImageFloatyWindowTest` tests passing alongside every other existing test.

- [ ] **Step 2: Assemble the debug APK**

Run: `./gradlew :app:assembleCommonDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual on-device verification**

1. Speak "find image". Confirm the screen tints and a prompt toast appears.
2. Drag a rectangle around a specific, identifiable visual element (an icon, a logo, anything without needing to be a labeled button). Confirm the outline grows live as you drag, matching your finger's path.
3. Release. Confirm the marked element actually gets tapped - the correct one, in the correct place.
4. Scroll or navigate so the same visual element appears in a different position (or re-open the same app screen), then tap Stop, save the session as a script, and run it again (Play). Confirm it still finds and taps the right thing even though it moved - this is the specific "adaptive" behavior this sub-project exists to deliver.
5. Say "find image" again, then press Back before dragging anything. Confirm the overlay closes with no action performed and no stray file left behind.
6. Say "find image", drag a rectangle, then tap Stop before release finishes processing (if timing allows) or immediately after. Confirm no action lands and no orphaned template file-related issue occurs.
7. Try a deliberately bad case - drag a rectangle around a mostly-blank area with nothing distinctive in it. Confirm this reports a normal failure toast (not found) rather than crashing or matching something wrong, and that nothing gets added to the session.
8. Confirm "find image" works whether triggered via the mic button or the volume-up hold, and confirm "tap on mark" (sub-project 5) still works correctly alongside it - both callsigns must coexist cleanly.

If any check fails, return to the relevant task, fix the issue, re-run its build step, and re-check here before considering this plan complete.
