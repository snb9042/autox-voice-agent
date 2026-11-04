# Smart Recorder + Bubble UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a floating bubble (Record / Stop / Play) to AutoX.js that records user actions as resilient, text/image-based steps (not raw coordinates or timing), auto-optimizes them, and generates a runnable Auto.js script.

**Architecture:** A new, isolated `com.stardust.autojs.core.agent` package in the `autojs` library module holds the pure action model (`SemanticAction`), the optimizer, and the script generator — all plain Kotlin, unit-testable with no Android dependency. A separate Android-dependent layer (`ScreenStateReader`, `SemanticRecorder`) listens to accessibility events and turns them into `SemanticAction`s, registered as a new `AccessibilityDelegate` alongside (never replacing) the existing recorder. A new `app`-module package (`org.autojs.autojs.ui.agent`) adds a second, independent floating bubble reusing the existing `CircularMenuWindow`/`FloatyService` overlay plumbing.

**Tech Stack:** Kotlin, Android Accessibility Service API, existing AutoX.js script engine (Auto.js-compatible JS), JUnit4.

**Spec:** `docs/superpowers/specs/2026-11-04-smart-recorder-bubble-design.md`

## Global Constraints

- New code lives in two places only: `autojs/src/main/java/com/stardust/autojs/core/agent/` (library, pure+Android logic) and `app/src/main/java/org/autojs/autojs/ui/agent/` (UI). Do not modify `GlobalActionRecorder`, `AccessibilityActionRecorder`, `AccessibilityActionConverter`, or `CircularMenu` — this feature is additive and isolated from them.
- No action ever records a fixed delay/sleep. Every generated step polls for its condition (text/image/bounds present) up to a timeout, then raises a clear error naming the step if the timeout elapses. Never generate a step that can block forever.
- Pure logic (`SemanticAction`, `ActionOptimizer`, `ActionScriptGenerator`, `Bounds`) must have zero Android framework dependency so it is testable with plain JUnit4 in `autojs/src/test/kotlin`.
- Follow existing code style/package conventions exactly as found in the codebase (verified during planning): accessibility delegates register via `AccessibilityService.Companion.addDelegate(priority, delegate)` from `AutoJs.kt`'s `addAccessibilityServiceDelegates()`; the floating bubble reuses `CircularMenuWindow`/`CircularMenuFloaty`/`CircularActionMenu`/`FloatyService` from `org.autojs.autojs.ui.floating`; script execution reuses `org.autojs.autojs.model.script.Scripts.run(ScriptFile)`; script saving reuses `org.autojs.autojs.ui.common.ScriptOperations.newScriptFileForScript(String)`.

---

### Task 1: `Bounds` and `SemanticAction` model

**Files:**
- Create: `autojs/src/main/java/com/stardust/autojs/core/agent/Bounds.kt`
- Create: `autojs/src/main/java/com/stardust/autojs/core/agent/SemanticAction.kt`
- Test: `autojs/src/test/kotlin/com/stardust/autojs/core/agent/BoundsTest.kt`

**Interfaces:**
- Produces: `data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)` with `centerX(): Int`, `centerY(): Int`, `distanceTo(other: Bounds): Double`, and `companion object { fun fromRect(rect: android.graphics.Rect): Bounds; fun closestTo(target: Bounds, candidates: List<Bounds>): Bounds? }`.
- Produces: `enum class ActionType { CLICK_TEXT, CLICK_IMAGE, CLICK_BOUNDS, LONG_CLICK, FILL_TEXT, SCROLL }`.
- Produces: `enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }`.
- Produces: `data class SemanticAction(val type: ActionType, val bounds: Bounds, val targetText: String? = null, val imageTemplatePath: String? = null, val fillValue: String? = null, val scrollDirection: ScrollDirection? = null)`.

- [ ] **Step 1: Write the failing test for `Bounds`**

```kotlin
package com.stardust.autojs.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundsTest {

    @Test
    fun `centerX and centerY compute the midpoint`() {
        val bounds = Bounds(0, 0, 100, 50)
        assertEquals(50, bounds.centerX())
        assertEquals(25, bounds.centerY())
    }

    @Test
    fun `distanceTo is zero for identical bounds`() {
        val a = Bounds(10, 10, 20, 20)
        val b = Bounds(10, 10, 20, 20)
        assertEquals(0.0, a.distanceTo(b), 0.0001)
    }

    @Test
    fun `closestTo picks the candidate nearest the target center`() {
        val target = Bounds(100, 100, 120, 120)
        val near = Bounds(105, 105, 125, 125)
        val far = Bounds(500, 500, 520, 520)
        val result = Bounds.closestTo(target, listOf(far, near))
        assertEquals(near, result)
    }

    @Test
    fun `closestTo returns null for an empty candidate list`() {
        assertNull(Bounds.closestTo(Bounds(0, 0, 10, 10), emptyList()))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run (from the repo root): `./gradlew :autojs:testDebugUnitTest --tests "com.stardust.autojs.core.agent.BoundsTest"`
Expected: FAIL to compile — `Bounds` does not exist yet.

- [ ] **Step 3: Implement `Bounds`**

```kotlin
package com.stardust.autojs.core.agent

import android.graphics.Rect

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    fun centerX(): Int = (left + right) / 2
    fun centerY(): Int = (top + bottom) / 2

    fun distanceTo(other: Bounds): Double {
        val dx = (centerX() - other.centerX()).toDouble()
        val dy = (centerY() - other.centerY()).toDouble()
        return dx * dx + dy * dy
    }

    companion object {
        fun fromRect(rect: Rect): Bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom)

        fun closestTo(target: Bounds, candidates: List<Bounds>): Bounds? =
            candidates.minByOrNull { it.distanceTo(target) }
    }
}
```

- [ ] **Step 4: Implement `SemanticAction` and its enums**

```kotlin
package com.stardust.autojs.core.agent

enum class ActionType { CLICK_TEXT, CLICK_IMAGE, CLICK_BOUNDS, LONG_CLICK, FILL_TEXT, SCROLL }

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

data class SemanticAction(
    val type: ActionType,
    val bounds: Bounds,
    val targetText: String? = null,
    val imageTemplatePath: String? = null,
    val fillValue: String? = null,
    val scrollDirection: ScrollDirection? = null
)
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :autojs:testDebugUnitTest --tests "com.stardust.autojs.core.agent.BoundsTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add autojs/src/main/java/com/stardust/autojs/core/agent/Bounds.kt autojs/src/main/java/com/stardust/autojs/core/agent/SemanticAction.kt autojs/src/test/kotlin/com/stardust/autojs/core/agent/BoundsTest.kt
git commit -m "feat(agent): add Bounds and SemanticAction model"
```

---

### Task 2: `ActionOptimizer`

**Files:**
- Create: `autojs/src/main/java/com/stardust/autojs/core/agent/ActionOptimizer.kt`
- Test: `autojs/src/test/kotlin/com/stardust/autojs/core/agent/ActionOptimizerTest.kt`

**Interfaces:**
- Consumes: `SemanticAction`, `ActionType`, `ScrollDirection`, `Bounds` (Task 1).
- Produces: `object ActionOptimizer { fun optimize(actions: List<SemanticAction>): List<SemanticAction> }`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.stardust.autojs.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionOptimizerTest {

    private val fieldBounds = Bounds(0, 100, 200, 140)

    @Test
    fun `collapses repeated FILL_TEXT on the same field into the last value`() {
        val actions = listOf(
            SemanticAction(ActionType.FILL_TEXT, fieldBounds, targetText = "Email", fillValue = "a"),
            SemanticAction(ActionType.FILL_TEXT, fieldBounds, targetText = "Email", fillValue = "ab"),
            SemanticAction(ActionType.FILL_TEXT, fieldBounds, targetText = "Email", fillValue = "abc")
        )
        val result = ActionOptimizer.optimize(actions)
        assertEquals(1, result.size)
        assertEquals("abc", result[0].fillValue)
    }

    @Test
    fun `does not collapse FILL_TEXT on different fields`() {
        val otherBounds = Bounds(0, 200, 200, 240)
        val actions = listOf(
            SemanticAction(ActionType.FILL_TEXT, fieldBounds, targetText = "Email", fillValue = "a"),
            SemanticAction(ActionType.FILL_TEXT, otherBounds, targetText = "Password", fillValue = "b")
        )
        assertEquals(2, ActionOptimizer.optimize(actions).size)
    }

    @Test
    fun `merges consecutive scrolls in the same direction`() {
        val actions = listOf(
            SemanticAction(ActionType.SCROLL, fieldBounds, scrollDirection = ScrollDirection.DOWN),
            SemanticAction(ActionType.SCROLL, fieldBounds, scrollDirection = ScrollDirection.DOWN),
            SemanticAction(ActionType.SCROLL, fieldBounds, scrollDirection = ScrollDirection.DOWN)
        )
        assertEquals(1, ActionOptimizer.optimize(actions).size)
    }

    @Test
    fun `does not merge scrolls in different directions`() {
        val actions = listOf(
            SemanticAction(ActionType.SCROLL, fieldBounds, scrollDirection = ScrollDirection.DOWN),
            SemanticAction(ActionType.SCROLL, fieldBounds, scrollDirection = ScrollDirection.UP)
        )
        assertEquals(2, ActionOptimizer.optimize(actions).size)
    }

    @Test
    fun `leaves unrelated actions untouched and in order`() {
        val click = SemanticAction(ActionType.CLICK_TEXT, fieldBounds, targetText = "Login")
        val fill = SemanticAction(ActionType.FILL_TEXT, fieldBounds, targetText = "Email", fillValue = "a")
        val result = ActionOptimizer.optimize(listOf(click, fill))
        assertEquals(listOf(click, fill), result)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :autojs:testDebugUnitTest --tests "com.stardust.autojs.core.agent.ActionOptimizerTest"`
Expected: FAIL to compile — `ActionOptimizer` does not exist yet.

- [ ] **Step 3: Implement `ActionOptimizer`**

```kotlin
package com.stardust.autojs.core.agent

object ActionOptimizer {

    fun optimize(actions: List<SemanticAction>): List<SemanticAction> {
        return mergeConsecutiveScrolls(collapseRepeatedFillText(actions))
    }

    private fun collapseRepeatedFillText(actions: List<SemanticAction>): List<SemanticAction> {
        val result = mutableListOf<SemanticAction>()
        for (action in actions) {
            val last = result.lastOrNull()
            if (action.type == ActionType.FILL_TEXT && last?.type == ActionType.FILL_TEXT && last.bounds == action.bounds) {
                result[result.lastIndex] = action
            } else {
                result.add(action)
            }
        }
        return result
    }

    private fun mergeConsecutiveScrolls(actions: List<SemanticAction>): List<SemanticAction> {
        val result = mutableListOf<SemanticAction>()
        for (action in actions) {
            val last = result.lastOrNull()
            val isDuplicateScroll = action.type == ActionType.SCROLL &&
                last?.type == ActionType.SCROLL &&
                last.scrollDirection == action.scrollDirection
            if (!isDuplicateScroll) {
                result.add(action)
            }
        }
        return result
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :autojs:testDebugUnitTest --tests "com.stardust.autojs.core.agent.ActionOptimizerTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add autojs/src/main/java/com/stardust/autojs/core/agent/ActionOptimizer.kt autojs/src/test/kotlin/com/stardust/autojs/core/agent/ActionOptimizerTest.kt
git commit -m "feat(agent): add ActionOptimizer to collapse dead recorder noise"
```

---

### Task 3: `ActionScriptGenerator`

**Files:**
- Create: `autojs/src/main/java/com/stardust/autojs/core/agent/ActionScriptGenerator.kt`
- Test: `autojs/src/test/kotlin/com/stardust/autojs/core/agent/ActionScriptGeneratorTest.kt`

**Interfaces:**
- Consumes: `SemanticAction`, `ActionType`, `ScrollDirection`, `Bounds` (Task 1).
- Produces: `object ActionScriptGenerator { fun generate(actions: List<SemanticAction>): String }`. The generated script always begins with two helper functions, `__findTextNear(targetText, cx, cy, timeoutMs)` and `__findBounds(l, t, r, b, timeoutMs)`, used by every text/bounds-based step to poll with a timeout and disambiguate multiple matches by proximity to the recorded center — this is how later tasks (the bubble UI) can treat `generate()`'s output as a complete, runnable script.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.stardust.autojs.core.agent

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionScriptGeneratorTest {

    private val bounds = Bounds(10, 20, 110, 60)

    @Test
    fun `CLICK_TEXT generates a text-polling click step`() {
        val script = ActionScriptGenerator.generate(
            listOf(SemanticAction(ActionType.CLICK_TEXT, bounds, targetText = "Login"))
        )
        assertTrue(script.contains("__findTextNear(\"Login\""))
        assertTrue(script.contains(".click();"))
    }

    @Test
    fun `LONG_CLICK generates a longClick call`() {
        val script = ActionScriptGenerator.generate(
            listOf(SemanticAction(ActionType.LONG_CLICK, bounds, targetText = "Menu"))
        )
        assertTrue(script.contains(".longClick();"))
    }

    @Test
    fun `FILL_TEXT with a label uses text matching and escapes quotes`() {
        val script = ActionScriptGenerator.generate(
            listOf(SemanticAction(ActionType.FILL_TEXT, bounds, targetText = "Email", fillValue = "a\"b"))
        )
        assertTrue(script.contains("__findTextNear(\"Email\""))
        assertTrue(script.contains(".setText(\"a\\\"b\");"))
    }

    @Test
    fun `FILL_TEXT without a label falls back to bounds matching`() {
        val script = ActionScriptGenerator.generate(
            listOf(SemanticAction(ActionType.FILL_TEXT, bounds, fillValue = "value"))
        )
        assertTrue(script.contains("__findBounds(10, 20, 110, 60"))
        assertTrue(script.contains(".setText(\"value\");"))
    }

    @Test
    fun `CLICK_IMAGE generates a polling image match and click`() {
        val script = ActionScriptGenerator.generate(
            listOf(SemanticAction(ActionType.CLICK_IMAGE, bounds, imageTemplatePath = "/data/template0.png"))
        )
        assertTrue(script.contains("images.read(\"/data/template0.png\")"))
        assertTrue(script.contains("images.findImage("))
    }

    @Test
    fun `CLICK_BOUNDS generates a fallback coordinate click with a warning comment`() {
        val script = ActionScriptGenerator.generate(listOf(SemanticAction(ActionType.CLICK_BOUNDS, bounds)))
        assertTrue(script.contains("fallback"))
        assertTrue(script.contains("click(60, 40);"))
    }

    @Test
    fun `SCROLL generates a swipe call`() {
        val script = ActionScriptGenerator.generate(
            listOf(SemanticAction(ActionType.SCROLL, bounds, scrollDirection = ScrollDirection.DOWN))
        )
        assertTrue(script.contains("swipe("))
    }

    @Test
    fun `missing required field throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            ActionScriptGenerator.generate(listOf(SemanticAction(ActionType.CLICK_TEXT, bounds)))
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :autojs:testDebugUnitTest --tests "com.stardust.autojs.core.agent.ActionScriptGeneratorTest"`
Expected: FAIL to compile — `ActionScriptGenerator` does not exist yet.

- [ ] **Step 3: Implement `ActionScriptGenerator`**

```kotlin
package com.stardust.autojs.core.agent

object ActionScriptGenerator {

    private const val FIND_TIMEOUT_MS = 10_000
    private const val POLL_INTERVAL_MS = 300

    private val PRELUDE = """
        |function __findTextNear(targetText, cx, cy, timeoutMs) {
        |    var start = Date.now();
        |    while (Date.now() - start < timeoutMs) {
        |        var matches = text(targetText).find();
        |        if (!matches.empty()) {
        |            var best = matches.get(0);
        |            var bestDist = Infinity;
        |            for (var i = 0; i < matches.size(); i++) {
        |                var b = matches.get(i).bounds();
        |                var dx = (b.left + b.right) / 2 - cx;
        |                var dy = (b.top + b.bottom) / 2 - cy;
        |                var dist = dx * dx + dy * dy;
        |                if (dist < bestDist) { bestDist = dist; best = matches.get(i); }
        |            }
        |            return best;
        |        }
        |        sleep($POLL_INTERVAL_MS);
        |    }
        |    return null;
        |}
        |
        |function __findBounds(l, t, r, b, timeoutMs) {
        |    var start = Date.now();
        |    while (Date.now() - start < timeoutMs) {
        |        var matches = bounds(l, t, r, b).find();
        |        if (!matches.empty()) return matches.get(0);
        |        sleep($POLL_INTERVAL_MS);
        |    }
        |    return null;
        |}
    """.trimMargin()

    fun generate(actions: List<SemanticAction>): String {
        val body = StringBuilder()
        actions.forEachIndexed { index, action ->
            body.append(generateStep(action, index)).append("\n\n")
        }
        return PRELUDE + "\n\n" + body.toString()
    }

    private fun generateStep(action: SemanticAction, index: Int): String = when (action.type) {
        ActionType.CLICK_TEXT -> clickOrLongClickByText(action, index, "click")
        ActionType.LONG_CLICK -> clickOrLongClickByText(action, index, "longClick")
        ActionType.FILL_TEXT -> fillTextStep(action, index)
        ActionType.CLICK_IMAGE -> clickImageStep(action, index)
        ActionType.CLICK_BOUNDS -> clickBoundsStep(action, index)
        ActionType.SCROLL -> scrollStep(action, index)
    }

    private fun esc(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun clickOrLongClickByText(action: SemanticAction, index: Int, method: String): String {
        val text = requireNotNull(action.targetText) { "Step $index (${action.type}) has no targetText" }
        return """
            |// Step $index: $method "${esc(text)}"
            |var step$index = __findTextNear("${esc(text)}", ${action.bounds.centerX()}, ${action.bounds.centerY()}, $FIND_TIMEOUT_MS);
            |if (!step$index) throw Error("Step $index: text target \"${esc(text)}\" not found");
            |step$index.$method();
        """.trimMargin()
    }

    private fun fillTextStep(action: SemanticAction, index: Int): String {
        val value = esc(action.fillValue.orEmpty())
        val (l, t, r, b) = action.bounds
        return if (action.targetText != null) {
            """
                |// Step $index: fill "${esc(action.targetText)}"
                |var step$index = __findTextNear("${esc(action.targetText)}", ${action.bounds.centerX()}, ${action.bounds.centerY()}, $FIND_TIMEOUT_MS);
                |if (!step$index) throw Error("Step $index: text target \"${esc(action.targetText)}\" not found");
                |step$index.setText("$value");
            """.trimMargin()
        } else {
            """
                |// Step $index: fill field at bounds
                |var step$index = __findBounds($l, $t, $r, $b, $FIND_TIMEOUT_MS);
                |if (!step$index) throw Error("Step $index: field at bounds ($l, $t, $r, $b) not found");
                |step$index.setText("$value");
            """.trimMargin()
        }
    }

    private fun clickImageStep(action: SemanticAction, index: Int): String {
        val templatePath = requireNotNull(action.imageTemplatePath) { "Step $index (CLICK_IMAGE) has no imageTemplatePath" }
        return """
            |// Step $index: click image target
            |var step${index}Template = images.read("${esc(templatePath)}");
            |var step${index}Point = null;
            |var step${index}Start = Date.now();
            |while (!step${index}Point && Date.now() - step${index}Start < $FIND_TIMEOUT_MS) {
            |    var step${index}Screen = images.captureScreen();
            |    step${index}Point = images.findImage(step${index}Screen, step${index}Template);
            |    if (!step${index}Point) sleep($POLL_INTERVAL_MS);
            |}
            |if (!step${index}Point) throw Error("Step $index: image target not found on screen");
            |click(step${index}Point.x, step${index}Point.y);
        """.trimMargin()
    }

    private fun clickBoundsStep(action: SemanticAction, index: Int): String {
        return """
            |// Step $index: fallback fixed-coordinate click - consider re-recording with screen-capture allowed
            |click(${action.bounds.centerX()}, ${action.bounds.centerY()});
        """.trimMargin()
    }

    private fun scrollStep(action: SemanticAction, index: Int): String {
        val direction = requireNotNull(action.scrollDirection) { "Step $index (SCROLL) has no scrollDirection" }
        val bounds = action.bounds
        val (startX, startY, endX, endY) = when (direction) {
            ScrollDirection.UP -> listOf(bounds.centerX(), bounds.bottom, bounds.centerX(), bounds.top)
            ScrollDirection.DOWN -> listOf(bounds.centerX(), bounds.top, bounds.centerX(), bounds.bottom)
            ScrollDirection.LEFT -> listOf(bounds.right, bounds.centerY(), bounds.left, bounds.centerY())
            ScrollDirection.RIGHT -> listOf(bounds.left, bounds.centerY(), bounds.right, bounds.centerY())
        }
        return "// Step $index: scroll $direction\nswipe($startX, $startY, $endX, $endY, 300);"
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :autojs:testDebugUnitTest --tests "com.stardust.autojs.core.agent.ActionScriptGeneratorTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add autojs/src/main/java/com/stardust/autojs/core/agent/ActionScriptGenerator.kt autojs/src/test/kotlin/com/stardust/autojs/core/agent/ActionScriptGeneratorTest.kt
git commit -m "feat(agent): add ActionScriptGenerator producing polling, timeout-safe scripts"
```

---

### Task 4: `ScreenStateReader` and `SemanticRecorder`, registered in `AutoJs`

**Files:**
- Create: `autojs/src/main/java/com/stardust/autojs/core/agent/ScreenStateReader.kt`
- Create: `autojs/src/main/java/com/stardust/autojs/core/agent/SemanticRecorder.kt`
- Modify: `autojs/src/main/java/com/stardust/autojs/AutoJs.kt` (add field + delegate registration)

**Interfaces:**
- Consumes: `SemanticAction`, `ActionType`, `ScrollDirection`, `Bounds` (Task 1); `AccessibilityDelegate` interface (`com.stardust.view.accessibility.AccessibilityDelegate`, verified at `automator/src/main/java/com/stardust/view/accessibility/AccessibilityDelegate.kt` — `val eventTypes: Set<Int>?`, `fun onAccessibilityEvent(service: AccessibilityService, event: AccessibilityEvent): Boolean`); `AccessibilityNodeInfoHelper.getBoundsInScreen(node): Rect` (verified at `automator/.../AccessibilityNodeInfoHelper.kt`); `ScreenCaptureManager` + `ImageWrapper` (verified at `autojs/.../core/image/capture/ScreenCaptureManager.kt` and `autojs/.../core/image/ImageWrapper.java` — `ofBitmap(bitmap).saveTo(path)`).
- Produces: `class ScreenStateReader { fun describeClickTarget(service: AccessibilityService, node: AccessibilityNodeInfo): TargetDescription; fun describeEditableTarget(service: AccessibilityService, node: AccessibilityNodeInfo): TargetDescription }` where `data class TargetDescription(val bounds: Bounds, val text: String?, val imageTemplatePath: String?)`.
- Produces: `class SemanticRecorder : AccessibilityDelegate { fun start(); fun stop(): List<SemanticAction> }` — later tasks (bubble UI) call `start()`/`stop()` on the singleton exposed as `AutoJs.getInstance().semanticRecorder`.

This task is Android-framework-dependent (`AccessibilityNodeInfo`, `AccessibilityService`, screen capture) and has no isolated unit test — it is verified manually in Task 8's end-to-end checklist, consistent with how the rest of this project's accessibility/UI code is tested (no existing instrumented test suite to extend, per the approved spec).

- [ ] **Step 1: Implement `ScreenStateReader`**

```kotlin
package com.stardust.autojs.core.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.stardust.autojs.core.image.ImageWrapper
import com.stardust.autojs.core.image.capture.ScreenCaptureManager
import com.stardust.view.accessibility.AccessibilityNodeInfoHelper
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

class ScreenStateReader {

    data class TargetDescription(val bounds: Bounds, val text: String?, val imageTemplatePath: String?)

    private val screenCaptureManager = ScreenCaptureManager()
    private var templateDir: File? = null

    fun describeClickTarget(service: AccessibilityService, node: AccessibilityNodeInfo): TargetDescription {
        val bounds = Bounds.fromRect(AccessibilityNodeInfoHelper.getBoundsInScreen(node))
        val label = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        if (label != null) {
            return TargetDescription(bounds, label, null)
        }
        return TargetDescription(bounds, null, captureTemplate(service, bounds))
    }

    fun describeEditableTarget(service: AccessibilityService, node: AccessibilityNodeInfo): TargetDescription {
        val bounds = Bounds.fromRect(AccessibilityNodeInfoHelper.getBoundsInScreen(node))
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.takeIf { it.isNotBlank() }
        } else null
        val label = hint ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        return TargetDescription(bounds, label, null)
    }

    private fun captureTemplate(service: AccessibilityService, bounds: Bounds): String? {
        return try {
            runBlocking {
                if (screenCaptureManager.screenCapture?.available != true) {
                    screenCaptureManager.requestScreenCapture(
                        service.applicationContext,
                        service.resources.configuration.orientation
                    )
                }
                val screen = screenCaptureManager.screenCapture?.captureImageWrapper() ?: return@runBlocking null
                val cropped = cropToBounds(screen, bounds) ?: return@runBlocking null
                val file = File(templateDirFor(service), "${UUID.randomUUID()}.png")
                ImageWrapper.ofBitmap(cropped).saveTo(file.absolutePath)
                file.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun cropToBounds(image: ImageWrapper, bounds: Bounds): Bitmap? {
        val bitmap = image.bitmap
        val left = bounds.left.coerceIn(0, bitmap.width - 1)
        val top = bounds.top.coerceIn(0, bitmap.height - 1)
        val width = (bounds.right - bounds.left).coerceIn(1, bitmap.width - left)
        val height = (bounds.bottom - bounds.top).coerceIn(1, bitmap.height - top)
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    private fun templateDirFor(service: AccessibilityService): File {
        return templateDir ?: File(service.applicationContext.filesDir, "agent_templates").also {
            it.mkdirs()
            templateDir = it
        }
    }
}
```

- [ ] **Step 2: Implement `SemanticRecorder`**

```kotlin
package com.stardust.autojs.core.agent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.stardust.view.accessibility.AccessibilityDelegate
import com.stardust.view.accessibility.AccessibilityNodeInfoHelper
import java.util.concurrent.CopyOnWriteArrayList

class SemanticRecorder(
    private val screenStateReader: ScreenStateReader = ScreenStateReader()
) : AccessibilityDelegate {

    enum class State { IDLE, RECORDING }

    override val eventTypes: Set<Int> = setOf(
        AccessibilityEvent.TYPE_VIEW_CLICKED,
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        AccessibilityEvent.TYPE_VIEW_SCROLLED
    )

    @Volatile
    var state: State = State.IDLE
        private set

    private val recordedActions = CopyOnWriteArrayList<SemanticAction>()

    fun start() {
        recordedActions.clear()
        state = State.RECORDING
    }

    fun stop(): List<SemanticAction> {
        state = State.IDLE
        return recordedActions.toList()
    }

    override fun onAccessibilityEvent(service: AccessibilityService, event: AccessibilityEvent): Boolean {
        if (state != State.RECORDING) return false
        val source = event.source ?: return false
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> recordClick(service, source, ActionType.CLICK_TEXT)
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> recordClick(service, source, ActionType.LONG_CLICK)
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> recordFillText(service, source, event)
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> recordScroll(source, event)
            }
        } finally {
            source.recycle()
        }
        return false
    }

    private fun recordClick(service: AccessibilityService, source: AccessibilityNodeInfo, clickType: ActionType) {
        val target = screenStateReader.describeClickTarget(service, source)
        val action = when {
            target.text != null -> SemanticAction(type = clickType, bounds = target.bounds, targetText = target.text)
            target.imageTemplatePath != null -> SemanticAction(
                type = ActionType.CLICK_IMAGE,
                bounds = target.bounds,
                imageTemplatePath = target.imageTemplatePath
            )
            else -> SemanticAction(type = ActionType.CLICK_BOUNDS, bounds = target.bounds)
        }
        recordedActions.add(action)
    }

    private fun recordFillText(service: AccessibilityService, source: AccessibilityNodeInfo, event: AccessibilityEvent) {
        val target = screenStateReader.describeEditableTarget(service, source)
        recordedActions.add(
            SemanticAction(
                type = ActionType.FILL_TEXT,
                bounds = target.bounds,
                targetText = target.text,
                fillValue = event.text?.joinToString("").orEmpty()
            )
        )
    }

    private fun recordScroll(source: AccessibilityNodeInfo, event: AccessibilityEvent) {
        val bounds = Bounds.fromRect(AccessibilityNodeInfoHelper.getBoundsInScreen(source))
        val direction = when {
            event.scrollDeltaY > 0 -> ScrollDirection.DOWN
            event.scrollDeltaY < 0 -> ScrollDirection.UP
            event.scrollDeltaX > 0 -> ScrollDirection.RIGHT
            else -> ScrollDirection.LEFT
        }
        recordedActions.add(SemanticAction(type = ActionType.SCROLL, bounds = bounds, scrollDirection = direction))
    }
}
```

- [ ] **Step 3: Register `SemanticRecorder` in `AutoJs`**

In `autojs/src/main/java/com/stardust/autojs/AutoJs.kt`, add the import and field next to the existing `accessibilityActionRecorder` (around line 14 and line 46):

```kotlin
import com.stardust.autojs.core.agent.SemanticRecorder
```

```kotlin
    val semanticRecorder: SemanticRecorder = SemanticRecorder()
```

(Note: unlike `accessibilityActionRecorder`, this field must be `public val` — not `private val` — because the `app` module's bubble UI in Task 5 needs `AutoJs.getInstance().semanticRecorder`, the same way it already reaches `AutoJs.getInstance().layoutInspector`.)

Then in `addAccessibilityServiceDelegates()` (around line 150-154), add a new priority after the existing ones:

```kotlin
    private fun addAccessibilityServiceDelegates() {
        addDelegate(100, infoProvider)
        addDelegate(200, mNotificationObserver)
        addDelegate(300, accessibilityActionRecorder)
        addDelegate(400, semanticRecorder)
    }
```

- [ ] **Step 4: Build to verify it compiles**

Run: `./gradlew :autojs:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add autojs/src/main/java/com/stardust/autojs/core/agent/ScreenStateReader.kt autojs/src/main/java/com/stardust/autojs/core/agent/SemanticRecorder.kt autojs/src/main/java/com/stardust/autojs/AutoJs.kt
git commit -m "feat(agent): add ScreenStateReader/SemanticRecorder and register as a new delegate"
```

---

### Task 5: Bubble UI shell — `AgentBubble` with Record / Stop / Play

**Files:**
- Create: `app/src/main/java/org/autojs/autojs/ui/agent/AgentBubble.kt`
- Create: `app/src/main/res/layout/agent_bubble_action_view.xml`
- Create: `app/src/main/res/layout/agent_bubble_action_menu.xml`

**Interfaces:**
- Consumes: `AutoJs.getInstance().semanticRecorder` (Task 4); `ActionOptimizer.optimize()`, `ActionScriptGenerator.generate()` (Tasks 2-3); `CircularMenuWindow`, `CircularMenuFloaty`, `CircularActionMenu`, `FloatyService` (verified at `app/src/main/java/org/autojs/autojs/ui/floating/`); `ScriptOperations(context, view).newScriptFileForScript(script)` (verified at `app/.../ui/common/ScriptOperations.java:87`); `Scripts.run(file: ScriptFile): ScriptExecution?` (verified at `app/.../model/script/Scripts.kt:107`); `AccessibilityServiceTool.isAccessibilityServiceEnabled`/`goToAccessibilitySetting` (used the same way in the existing `CircularMenu.kt`).
- Produces: `class AgentBubble(context: Context) { fun close() }` with `companion object { fun show(context: Context); fun hide(); fun isShowing(): Boolean }` — Task 7's entry point calls `AgentBubble.show(context)` / `AgentBubble.hide()` / `AgentBubble.isShowing()`.

This task is Android-UI-dependent (floating overlay) and is verified manually in Task 8.

- [ ] **Step 1: Create the action-view layout** (`app/src/main/res/layout/agent_bubble_action_view.xml`, mirrors the existing `circular_action_view.xml`)

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
              xmlns:app="http://schemas.android.com/apk/res-auto"
              android:layout_width="wrap_content"
              android:layout_height="wrap_content"
              android:orientation="vertical">

    <com.makeramen.roundedimageview.RoundedImageView
        android:id="@+id/agent_icon"
        android:layout_width="42dp"
        android:layout_height="42dp"
        android:background="@drawable/circle_white"
        android:padding="@dimen/padding_circular_menu_normal"
        android:src="@drawable/ic_record"
        app:riv_corner_radius="16dp"
        app:riv_mutate_background="true"
        app:riv_oval="true"/>
</LinearLayout>
```

- [ ] **Step 2: Create the action-menu layout** (`app/src/main/res/layout/agent_bubble_action_menu.xml`, mirrors the existing `circular_action_menu.xml` with three items)

```xml
<?xml version="1.0" encoding="utf-8"?>
<org.autojs.autojs.ui.floating.CircularActionMenu xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:clickable="false"
    app:cam_angle="140"
    app:cam_radius="80dp">

    <ImageView
        android:id="@+id/agent_record"
        android:layout_width="42dp"
        android:layout_height="42dp"
        android:layout_gravity="center"
        android:background="@drawable/circle_red"
        android:foreground="?selectableItemBackgroundBorderless"
        android:padding="8dp"
        android:src="@drawable/ic_record" />

    <ImageView
        android:id="@+id/agent_stop"
        android:layout_width="42dp"
        android:layout_height="42dp"
        android:layout_gravity="center"
        android:background="@drawable/circle_gray"
        android:foreground="?selectableItemBackgroundBorderless"
        android:padding="8dp"
        android:src="@drawable/ic_stop" />

    <ImageView
        android:id="@+id/agent_play"
        android:layout_width="42dp"
        android:layout_height="42dp"
        android:layout_gravity="center"
        android:background="@drawable/circle_green"
        android:foreground="?selectableItemBackgroundBorderless"
        android:padding="8dp"
        android:src="@drawable/ic_play_fill" />

</org.autojs.autojs.ui.floating.CircularActionMenu>
```

- [ ] **Step 3: Add the two string resources used by the dialog in this task**

In `app/src/main/res/values/strings.xml`, add:

```xml
    <string name="text_agent_recorded_steps">%1$d step(s) recorded</string>
    <string name="text_preview">Preview</string>
```

- [ ] **Step 4: Implement `AgentBubble`**

```kotlin
package org.autojs.autojs.ui.agent

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import com.makeramen.roundedimageview.RoundedImageView
import com.stardust.app.DialogUtils
import com.stardust.autojs.core.agent.ActionOptimizer
import com.stardust.autojs.core.agent.ActionScriptGenerator
import com.stardust.autojs.core.agent.SemanticRecorder
import com.stardust.enhancedfloaty.FloatyService
import org.autojs.autojs.Pref
import org.autojs.autojs.autojs.AutoJs
import org.autojs.autojs.model.explorer.ExplorerDirPage
import org.autojs.autojs.model.explorer.Explorers
import org.autojs.autojs.model.script.Scripts
import org.autojs.autojs.theme.dialog.ThemeColorMaterialDialogBuilder
import org.autojs.autojs.tool.AccessibilityServiceTool
import org.autojs.autojs.ui.common.ScriptOperations
import org.autojs.autojs.ui.explorer.ExplorerViewKt
import org.autojs.autojs.ui.floating.CircularActionMenu
import org.autojs.autojs.ui.floating.CircularMenuFloaty
import org.autojs.autojs.ui.floating.CircularMenuWindow
import org.autojs.autoxjs.R

class AgentBubble(context: Context) {

    private val mContext: Context = ContextThemeWrapper(context, R.style.AppTheme)
    private var mWindow: CircularMenuWindow? = null
    private var mIcon: RoundedImageView? = null
    private val mRecorder: SemanticRecorder = AutoJs.getInstance().semanticRecorder

    init {
        initFloaty()
    }

    private fun initFloaty() {
        mWindow = CircularMenuWindow(
            mContext,
            object : CircularMenuFloaty {
                override fun inflateActionView(service: FloatyService, window: CircularMenuWindow): View {
                    val view = View.inflate(service, R.layout.agent_bubble_action_view, null)
                    mIcon = view.findViewById(R.id.agent_icon)
                    return view
                }

                override fun inflateMenuItems(service: FloatyService, window: CircularMenuWindow): CircularActionMenu {
                    val menu = View.inflate(
                        ContextThemeWrapper(service, R.style.AppTheme),
                        R.layout.agent_bubble_action_menu,
                        null
                    ) as CircularActionMenu
                    menu.findViewById<View>(R.id.agent_record)?.setOnClickListener { startRecording() }
                    menu.findViewById<View>(R.id.agent_stop)?.setOnClickListener { stopRecording() }
                    menu.findViewById<View>(R.id.agent_play)?.setOnClickListener { pickAndPlay() }
                    return menu
                }
            }
        )
        mWindow?.setOnActionViewClickListener {
            if (mWindow?.isExpanded == true) mWindow?.collapse() else mWindow?.expand()
        }
        FloatyService.addWindow(mWindow)
    }

    private fun startRecording() {
        mWindow?.collapse()
        if (!AccessibilityServiceTool.isAccessibilityServiceEnabled(mContext)) {
            AccessibilityServiceTool.goToAccessibilitySetting()
            return
        }
        mRecorder.start()
        mIcon?.setBackgroundResource(R.drawable.circle_red)
    }

    private fun stopRecording() {
        mWindow?.collapse()
        val actions = mRecorder.stop()
        mIcon?.setBackgroundResource(R.drawable.circle_white)
        val optimized = ActionOptimizer.optimize(actions)
        val script = ActionScriptGenerator.generate(optimized)
        showSummaryDialog(optimized.size, script)
    }

    private fun showSummaryDialog(stepCount: Int, script: String) {
        DialogUtils.showDialog(
            ThemeColorMaterialDialogBuilder(mContext)
                .title(R.string.text_recorded)
                .content(mContext.getString(R.string.text_agent_recorded_steps, stepCount))
                .items(
                    mContext.getString(R.string.text_preview),
                    mContext.getString(R.string.text_new_file)
                )
                .itemsCallback { _, _, position, _ ->
                    when (position) {
                        0 -> {
                            showPreviewDialog(script)
                            showSummaryDialog(stepCount, script)
                        }
                        1 -> ScriptOperations(mContext, null).newScriptFileForScript(script)
                    }
                }
                .negativeText(R.string.text_cancel)
                .canceledOnTouchOutside(false)
                .build()
        )
    }

    private fun showPreviewDialog(script: String) {
        val textView = TextView(mContext).apply {
            text = script
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(24, 24, 24, 24)
        }
        val scrollView = ScrollView(mContext).apply { addView(textView) }
        DialogUtils.showDialog(
            ThemeColorMaterialDialogBuilder(mContext)
                .title(R.string.text_preview)
                .customView(scrollView, false)
                .positiveText(R.string.ok)
                .build()
        )
    }

    private fun pickAndPlay() {
        mWindow?.collapse()
        val explorerView = ExplorerViewKt(mContext)
        explorerView.setExplorer(Explorers.workspace(), ExplorerDirPage.createRoot(Pref.getScriptDirPath()))
        explorerView.setDirectorySpanSize(2)
        val dialog = ThemeColorMaterialDialogBuilder(mContext)
            .title(R.string.text_run_script)
            .customView(explorerView, false)
            .positiveText(R.string.cancel)
            .build()
        explorerView.setOnItemOperatedListener { dialog.dismiss() }
        explorerView.setOnItemClickListener { _, item ->
            item?.let { Scripts.run(it.toScriptFile()) }
            dialog.dismiss()
        }
        DialogUtils.showDialog(dialog)
    }

    fun close() {
        mWindow?.close()
    }

    companion object {
        private var instance: AgentBubble? = null

        fun show(context: Context) {
            if (instance != null) return
            context.startService(Intent(context, FloatyService::class.java))
            instance = AgentBubble(context)
        }

        fun hide() {
            instance?.close()
            instance = null
        }

        fun isShowing(): Boolean = instance != null
    }
}
```

- [ ] **Step 5: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/autojs/autojs/ui/agent/AgentBubble.kt app/src/main/res/layout/agent_bubble_action_view.xml app/src/main/res/layout/agent_bubble_action_menu.xml app/src/main/res/values/strings.xml
git commit -m "feat(agent): add AgentBubble with Record/Stop/Play and optimize-on-stop flow"
```

---

### Task 6: Entry point — preference, drawer switch, restore-on-launch

**Files:**
- Modify: `app/src/main/java/org/autojs/autojs/Pref.java` (add `isAgentBubbleShown()` / `setAgentBubbleShown(boolean)`)
- Modify: `app/src/main/java/org/autojs/autojs/ui/main/drawer/DrawerPage.kt` (add `AgentBubbleSwitch()` composable + call site)
- Modify: `app/src/main/java/org/autojs/autojs/ui/main/MainActivity.kt` (restore bubble on launch, mirroring `Pref.isFloatingMenuShown()`)
- Modify: `app/src/main/res/values/strings.xml` (add `text_agent_bubble`)

**Interfaces:**
- Consumes: `AgentBubble.show(context)` / `hide()` / `isShowing()` (Task 5); `DrawOverlaysPermission.isCanDrawOverlays` / `launchCanDrawOverlaysSettings` (already used identically by the existing `FloatingWindowSwitch()` in the same file).

- [ ] **Step 1: Add the preference key and accessors**

In `app/src/main/java/org/autojs/autojs/Pref.java`, add near `KEY_FLOATING_MENU_SHOWN` (find it with the existing `private static final String KEY_FLOATING_MENU_SHOWN = ...` declaration) a sibling constant:

```java
    private static final String KEY_AGENT_BUBBLE_SHOWN = "agent_bubble_shown";
```

Then, next to `isFloatingMenuShown()`/`setFloatingMenuShown()` (around line 149-159), add:

```java
    public static boolean isAgentBubbleShown() {
        return def().getBoolean(KEY_AGENT_BUBBLE_SHOWN, false);
    }

    public static void setAgentBubbleShown(boolean checked) {
        def().edit().putBoolean(KEY_AGENT_BUBBLE_SHOWN, checked).apply();
    }
```

- [ ] **Step 2: Add the string resource**

In `app/src/main/res/values/strings.xml`, add:

```xml
    <string name="text_agent_bubble">Smart Recorder Bubble</string>
```

- [ ] **Step 3: Add the drawer switch composable**

In `app/src/main/java/org/autojs/autojs/ui/main/drawer/DrawerPage.kt`, add the import:

```kotlin
import org.autojs.autojs.ui.agent.AgentBubble
```

Add a new composable right after the existing `FloatingWindowSwitch()` function (after its closing brace, around line 580):

```kotlin
@Composable
private fun AgentBubbleSwitch() {
    val context = LocalContext.current

    var isAgentBubbleShowing by remember {
        mutableStateOf(AgentBubble.isShowing())
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = {
            if (DrawOverlaysPermission.isCanDrawOverlays(context)) AgentBubble.show(context)
            isAgentBubbleShowing = AgentBubble.isShowing()
        }
    )
    SwitchItem(
        icon = {
            MyIcon(
                painterResource(id = R.drawable.ic_record),
                null
            )
        },
        text = { Text(text = stringResource(id = R.string.text_agent_bubble)) },
        checked = isAgentBubbleShowing,
        onCheckedChange = {
            if (isAgentBubbleShowing) {
                AgentBubble.hide()
            } else {
                if (DrawOverlaysPermission.isCanDrawOverlays(context)) AgentBubble.show(context)
                else launcher.launchCanDrawOverlaysSettings(context.packageName)
            }
            isAgentBubbleShowing = AgentBubble.isShowing()
            Pref.setAgentBubbleShown(isAgentBubbleShowing)
        }
    )
}
```

Then call it right after the existing `FloatingWindowSwitch()` call site (line 106):

```kotlin
            FloatingWindowSwitch()
            AgentBubbleSwitch()
```

- [ ] **Step 4: Restore the bubble on app launch**

In `app/src/main/java/org/autojs/autojs/ui/main/MainActivity.kt`, add the import:

```kotlin
import org.autojs.autojs.ui.agent.AgentBubble
```

Right after the existing floating-menu restore block (after line 141's closing brace):

```kotlin
        if (Pref.isAgentBubbleShown() && !AgentBubble.isShowing()) {
            if (DrawOverlaysPermission.isCanDrawOverlays(this)) AgentBubble.show(this)
            else Pref.setAgentBubbleShown(false)
        }
```

- [ ] **Step 5: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/autojs/autojs/Pref.java app/src/main/java/org/autojs/autojs/ui/main/drawer/DrawerPage.kt app/src/main/java/org/autojs/autojs/ui/main/MainActivity.kt app/src/main/res/values/strings.xml
git commit -m "feat(agent): add drawer switch and launch-restore entry point for the bubble"
```

---

### Task 7: Full build verification

**Files:** none (verification only).

- [ ] **Step 1: Run the full unit test suite**

Run: `./gradlew :autojs:testDebugUnitTest :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests from Tasks 1-3 passing, no regressions in existing tests (e.g. `MultiLinePreprocessorTest`, `XmlConverterTest`).

- [ ] **Step 2: Assemble the debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit (only if Steps 1-2 required fixes)**

If any fix was needed to get a clean build, stage exactly the files touched to fix it and commit:

```bash
git add -A
git commit -m "fix(agent): resolve build issues found in full verification"
```

If no fixes were needed, skip this step — there is nothing to commit.

---

### Task 8: Manual end-to-end verification

**Files:** none (manual verification only — this task has no automated steps because it exercises the Accessibility Service, overlay permission, and screen-capture permission, none of which are mockable in a JVM unit test, consistent with the spec's testing section).

- [ ] **Step 1: Install and grant permissions**

Install the debug APK on a real device or emulator (`./gradlew :app:installDebug`). Open AutoX.js, enable the Accessibility Service when prompted (Settings → Accessibility), and grant the overlay/"draw over other apps" permission.

- [ ] **Step 2: Show the bubble**

Open the app drawer, toggle the new "Smart Recorder Bubble" switch on. Confirm a second, independent floating bubble appears (distinct from the existing floating console bubble) and that toggling it off removes it.

- [ ] **Step 3: Record a simple flow with labeled UI**

Tap the bubble, tap Record. In any other app, tap a clearly labeled button (e.g. a Settings menu item) and type into a labeled text field. Tap the bubble again, tap Stop. Confirm the summary dialog reports the expected step count, Preview shows readable `text(...)`/`__findTextNear`/`setText` steps (not raw coordinates), and Save writes a `.js` file via the usual filename prompt.

- [ ] **Step 4: Record a flow that needs the image-fallback path**

Record a tap on an unlabeled icon-only control (no text, no content description). Confirm the app prompts for screen-capture permission at that point (not before), and the resulting step in Preview reads from an image template (`images.read(...)` / `images.findImage(...)`) rather than a plain fixed-coordinate `click(x, y)` with the fallback-warning comment — the fallback comment should appear only when screen-capture permission is denied.

- [ ] **Step 5: Verify optimization**

Record a flow that includes several keystrokes into one text field (confirm only the final value ends up as one `setText(...)` step, not one per keystroke) and several scrolls in the same direction in a row (confirm they collapse to one `swipe(...)` step).

- [ ] **Step 6: Play back a saved script**

Tap the bubble, tap Play, pick the script saved in Step 3 from the file browser, and confirm it runs via the existing script engine and reproduces the recorded actions.

- [ ] **Step 7: Confirm isolation from the existing recorder**

Confirm the existing floating console bubble (with its own Record/script-list/settings menu) still works exactly as before, independently of the new bubble — recording with one does not start, stop, or otherwise affect the other.

If any step fails, return to the relevant task, fix the issue, and re-run that task's build/test step before re-attempting this checklist.
