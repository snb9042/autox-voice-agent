# Re-enable OCR with Bounds Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OCR-read text re-enters the voice agent's screen description and becomes actually clickable, by routing OCR-sourced targets to a fixed-coordinate tap instead of a text selector that could never find them - while fixing the memory leak and duplicate-entry issues that were found alongside the original OCR work.

**Architecture:** `VisibleText` gains an `isOcrSourced` flag; `ClaudeActionPlanner.actionFromInput` branches on it to choose `CLICK_BOUNDS` (already-working codegen, reused as-is) over the text-selector path for click/long-click, and routes fill/wait on an OCR-only target into the existing clarification path instead of generating an unfindable step. `ScreenStateReader` re-enables its already-written `ocrFallback()`, now deduplicating against the accessibility-sourced list and recycling its screen capture.

**Tech Stack:** Kotlin, the existing `ClaudeActionPlanner`/`ScreenStateReader` pipeline.

**Spec:** `docs/superpowers/specs/2026-11-09-ocr-reenable-design.md`

## Global Constraints

- `MatchBy` is not touched - it stays TEXT-vs-DESC selector style only; OCR origin is a separate, new field on `VisibleText`.
- No change to `ActionScriptGenerator.kt` - `clickBoundsStep` already correctly handles bare-bounds click/long-click; this plan is pure routing, not new codegen.
- No new user-facing error states - an OCR-only target that can't support fill/wait reaches the exact `NeedsClarification` path sub-project 3 already built for an unmatched/unusable target.
- No cap on total `visibleText` entry count - out of scope, a pre-existing concern unrelated to OCR specifically.

---

### Task 1: OCR-aware routing in `ClaudeActionPlanner`

**Files:**
- Modify: `autojs/src/main/java/com/stardust/autojs/core/agent/ClaudeActionPlanner.kt`
- Modify: `autojs/src/test/kotlin/com/stardust/autojs/core/agent/ClaudeActionPlannerTest.kt`

**Interfaces:**
- Consumes: `ActionType.CLICK_BOUNDS` (pre-existing), `SemanticAction` (pre-existing, all new-field defaults already present - `targetText`/`matchBy` default to `null`, `isLongClick` defaults to `false`).
- Produces: `VisibleText.isOcrSourced: Boolean` (new field, default `false`) - Task 2 sets this `true` when constructing OCR-derived entries.

Grounded during planning: the full current `ClaudeActionPlanner.kt` was read after sub-project 3's edits - `VisibleText` is `data class VisibleText(val text: String, val matchBy: MatchBy, val bounds: Bounds)`, and `actionFromInput`'s four relevant branches (click_text/click_desc, long_click_text/long_click_desc, fill_text/fill_desc, wait_for_text/wait_for_desc) all currently go through a single `matched?.let { ... }` each.

- [ ] **Step 1: Write the failing tests**

Add to `ClaudeActionPlannerTest.kt`, directly below the existing `screen` fixture (after its closing `)`):

```kotlin
    private val screenWithOcrTarget = ScreenDescription(
        screenBounds = Bounds(0, 0, 1080, 2400),
        visibleText = listOf(
            VisibleText("Settings", MatchBy.TEXT, Bounds(700, 1000, 900, 1080), isOcrSourced = true)
        )
    )
```

Add these four tests anywhere after the existing `actionOf` helper function:

```kotlin
    @Test
    fun `click_text on an OCR-sourced target produces a CLICK_BOUNDS action, not a text selector`() {
        val response = toolUseResponse(
            JSONObject().put("action", "click_text").put("target", "Settings").put("reason", "open settings")
        )
        val action = actionOf(ClaudeActionPlanner.parseResponse(response, screenWithOcrTarget))
        assertEquals(ActionType.CLICK_BOUNDS, action.type)
        assertEquals(Bounds(700, 1000, 900, 1080), action.bounds)
        assertEquals(null, action.targetText)
    }

    @Test
    fun `long_click_text on an OCR-sourced target produces a CLICK_BOUNDS action with isLongClick`() {
        val response = toolUseResponse(
            JSONObject().put("action", "long_click_text").put("target", "Settings").put("reason", "long press settings")
        )
        val action = actionOf(ClaudeActionPlanner.parseResponse(response, screenWithOcrTarget))
        assertEquals(ActionType.CLICK_BOUNDS, action.type)
        assertTrue(action.isLongClick)
    }

    @Test
    fun `fill_text on an OCR-sourced target asks for clarification instead of generating an unfindable selector`() {
        val response = toolUseResponse(
            JSONObject().put("action", "fill_text").put("target", "Settings").put("value", "hello").put("reason", "fill it")
        )
        val result = ClaudeActionPlanner.parseResponse(response, screenWithOcrTarget)
        assertEquals(PlanResult.NeedsClarification("fill it"), result)
    }

    @Test
    fun `wait_for_text on an OCR-sourced target asks for clarification`() {
        val response = toolUseResponse(
            JSONObject().put("action", "wait_for_text").put("target", "Settings").put("reason", "waiting")
        )
        val result = ClaudeActionPlanner.parseResponse(response, screenWithOcrTarget)
        assertEquals(PlanResult.NeedsClarification("waiting"), result)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :autojs:testDebugUnitTest --tests "com.stardust.autojs.core.agent.ClaudeActionPlannerTest"`
Expected: FAIL to compile - `VisibleText`'s constructor has no `isOcrSourced` parameter yet, and `CLICK_BOUNDS` is never produced by `actionFromInput`.

- [ ] **Step 3: Add the `isOcrSourced` field**

In `ClaudeActionPlanner.kt`, change:

```kotlin
data class VisibleText(val text: String, val matchBy: MatchBy, val bounds: Bounds)
```

to:

```kotlin
data class VisibleText(val text: String, val matchBy: MatchBy, val bounds: Bounds, val isOcrSourced: Boolean = false)
```

- [ ] **Step 4: Route click/long-click/fill/wait on OCR-sourced targets**

Replace the four relevant branches inside `actionFromInput`'s `when (action)`:

```kotlin
                "click_text", "click_desc" -> matched?.let {
                    SemanticAction(ActionType.CLICK_TEXT, it.bounds, targetText = it.text, matchBy = it.matchBy)
                }
                "long_click_text", "long_click_desc" -> matched?.let {
                    SemanticAction(
                        ActionType.LONG_CLICK, it.bounds,
                        targetText = it.text, matchBy = it.matchBy, isLongClick = true
                    )
                }
                "fill_text", "fill_desc" -> {
                    // optString returns "" for an omitted value, and an
                    // empty-string fill *succeeds* - it blanks the target
                    // field and then gets saved as a successful step. Treat a
                    // valueless fill as an action Claude couldn't determine.
                    val value = input.optString("value").takeIf { it.isNotEmpty() }
                    if (value == null) null else matched?.let {
                        SemanticAction(
                            ActionType.FILL_TEXT, it.bounds,
                            targetText = it.text, matchBy = it.matchBy, fillValue = value
                        )
                    }
                }
                "wait_for_text", "wait_for_desc" -> matched?.let {
                    SemanticAction(ActionType.WAIT, it.bounds, targetText = it.text, matchBy = it.matchBy)
                }
```

with:

```kotlin
                "click_text", "click_desc" -> matched?.let {
                    if (it.isOcrSourced) {
                        // No real accessibility node to re-find at execution
                        // time - tap exactly where OCR saw it instead of
                        // generating a selector that would never match.
                        SemanticAction(ActionType.CLICK_BOUNDS, it.bounds)
                    } else {
                        SemanticAction(ActionType.CLICK_TEXT, it.bounds, targetText = it.text, matchBy = it.matchBy)
                    }
                }
                "long_click_text", "long_click_desc" -> matched?.let {
                    if (it.isOcrSourced) {
                        SemanticAction(ActionType.CLICK_BOUNDS, it.bounds, isLongClick = true)
                    } else {
                        SemanticAction(
                            ActionType.LONG_CLICK, it.bounds,
                            targetText = it.text, matchBy = it.matchBy, isLongClick = true
                        )
                    }
                }
                "fill_text", "fill_desc" -> {
                    // optString returns "" for an omitted value, and an
                    // empty-string fill *succeeds* - it blanks the target
                    // field and then gets saved as a successful step. Treat a
                    // valueless fill as an action Claude couldn't determine.
                    val value = input.optString("value").takeIf { it.isNotEmpty() }
                    // An OCR-sourced target has no real accessibility node to
                    // type into either - FILL_TEXT's codegen requires an
                    // editable(...) selector that doesn't exist for one.
                    if (value == null) null else matched?.takeUnless { it.isOcrSourced }?.let {
                        SemanticAction(
                            ActionType.FILL_TEXT, it.bounds,
                            targetText = it.text, matchBy = it.matchBy, fillValue = value
                        )
                    }
                }
                "wait_for_text", "wait_for_desc" -> matched?.takeUnless { it.isOcrSourced }?.let {
                    // Same reasoning as fill above - WAIT's codegen needs a
                    // pollable selector, not a one-shot coordinate.
                    SemanticAction(ActionType.WAIT, it.bounds, targetText = it.text, matchBy = it.matchBy)
                }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :autojs:testDebugUnitTest --tests "com.stardust.autojs.core.agent.ClaudeActionPlannerTest"`
Expected: PASS (all 20 tests - the existing 16 plus these 4 new ones).

- [ ] **Step 6: Commit**

```bash
git add autojs/src/main/java/com/stardust/autojs/core/agent/ClaudeActionPlanner.kt autojs/src/test/kotlin/com/stardust/autojs/core/agent/ClaudeActionPlannerTest.kt
git commit -m "feat(agent): route OCR-sourced click/long-click to a bounds tap, ask for clarification on fill/wait"
```

---

### Task 2: Re-enable `ocrFallback` with dedup and recycling

**Files:**
- Modify: `autojs/src/main/java/com/stardust/autojs/core/agent/ScreenStateReader.kt`

**Interfaces:**
- Consumes: `VisibleText.isOcrSourced` (Task 1).
- Produces: no new public API - `describeScreen()`'s signature and return type are unchanged; only what it returns changes (now includes deduplicated OCR entries).

Grounded during planning: the full current `ScreenStateReader.kt` was read - `ocrFallback()` already exists, unused, with `@Suppress("unused")`, a cached `paddle` field, and correctly skips requesting screen-capture permission. Its doc comment and `describeScreen()`'s doc comment both already name the exact three blockers this task resolves. `ImageWrapper.recycle()` (confirmed to exist in `ImageWrapper.java`) is the right call for the leak fix. `Bounds` has no `contains`-style method - this task adds one as a private extension local to this file rather than modifying the shared `Bounds` class. No isolated unit test (real OCR/screen-capture) - verified by compile plus Task 3's on-device check.

- [ ] **Step 1: Add a bounds-overlap helper**

In `ScreenStateReader.kt`, add near the top of the file (after the imports, before the `class ScreenStateReader` declaration):

```kotlin
private fun Bounds.containsPoint(x: Int, y: Int): Boolean = x in left..right && y in top..bottom
```

- [ ] **Step 2: Update the `paddle` field's doc comment**

Replace:

```kotlin
    /**
     * Shared OCR instance for [ocrFallback], which is currently disabled - so
     * nothing reaches this at the moment. `Paddle`'s underlying `Predictor`
     * does its real native model-load work on first *use*, not on
     * construction, so holding an unused instance costs nothing; it is cached
     * rather than constructed per call so that re-enabling OCR doesn't pay a
     * full model load on every voice turn instead of just an inference cost.
     */
    private val paddle = Paddle()
```

with:

```kotlin
    /**
     * Shared OCR instance for [ocrFallback]. `Paddle`'s underlying `Predictor`
     * does its real native model-load work on first *use*, not on
     * construction, so it's cached here rather than constructed per call - a
     * fresh instance per voice turn would pay a full model load every time
     * instead of just an inference cost.
     */
    private val paddle = Paddle()
```

- [ ] **Step 3: Re-enable OCR in `describeScreen()`, with dedup**

Replace the existing `describeScreen()` method and its doc comment:

```kotlin
    /**
     * Everything visible right now, for the voice agent to reason over: every
     * accessibility node's text or content-description, with its bounds.
     *
     * The OCR pass ([ocrFallback]) is deliberately **disabled** in this
     * version, for three reasons:
     *
     *  1. It didn't work. OCR-sourced entries were tagged [MatchBy.TEXT], but
     *     an OCR-only target has no accessibility `getText()` /
     *     `getContentDescription()` for the generated script's
     *     `__findTextNear` / `__findDescNear` selectors to match against at
     *     execution time - so an OCR-matched action always burned its full
     *     10s find timeout and then failed, defeating the point of the
     *     fallback. Making these targets actionable needs CLICK_BOUNDS
     *     routing for label-less targets, which does not exist yet;
     *     **re-enabling OCR requires building that routing first.**
     *  2. Each call captured a full-screen bitmap (and its native Mat) that
     *     was never recycled, leaking memory on every OCR-triggering turn.
     *  3. It appended an unbounded number of extra entries to the prompt.
     *
     * The accepted consequence is that targets which are readable on screen
     * but carry no accessibility label are not voice-addressable in this
     * version.
     *
     * Still call off the main thread: the accessibility walk is not work for
     * the accessibility-event thread, the same reason template capture
     * already runs on [captureExecutor].
     */
    fun describeScreen(service: AccessibilityService): ScreenDescription {
        val root = service.rootInActiveWindow
            ?: return ScreenDescription(Bounds(0, 0, 0, 0), emptyList())
        val screenBounds = Bounds.fromRect(AccessibilityNodeInfoHelper.getBoundsInScreen(root))
        val visible = mutableListOf<VisibleText>()
        collectVisibleText(root, visible)
        root.recycle()
        return ScreenDescription(screenBounds, visible)
    }
```

with:

```kotlin
    /**
     * Everything visible right now, for the voice agent to reason over: every
     * accessibility node's text or content-description with its bounds, plus
     * on-screen text OCR can read that has no accessibility label of its own
     * - tagged [VisibleText.isOcrSourced] so the planner routes an action on
     * one of these to a fixed-coordinate tap instead of a selector that would
     * never find it (see [ClaudeActionPlanner]'s `actionFromInput`). An OCR
     * hit whose center falls inside an already-collected accessibility
     * entry's bounds is skipped, so an already-labeled widget's text doesn't
     * appear twice.
     *
     * Still call off the main thread: the accessibility walk and OCR
     * inference are not work for the accessibility-event thread, the same
     * reason template capture already runs on [captureExecutor].
     */
    fun describeScreen(service: AccessibilityService): ScreenDescription {
        val root = service.rootInActiveWindow
            ?: return ScreenDescription(Bounds(0, 0, 0, 0), emptyList())
        val screenBounds = Bounds.fromRect(AccessibilityNodeInfoHelper.getBoundsInScreen(root))
        val visible = mutableListOf<VisibleText>()
        collectVisibleText(root, visible)
        root.recycle()
        val ocrEntries = ocrFallback().filterNot { ocrEntry ->
            visible.any { it.bounds.containsPoint(ocrEntry.bounds.centerX(), ocrEntry.bounds.centerY()) }
        }
        visible.addAll(ocrEntries)
        return ScreenDescription(screenBounds, visible)
    }
```

- [ ] **Step 4: Fix the leak and tag OCR origin in `ocrFallback`**

Replace:

```kotlin
    /**
     * **Currently unused.** [describeScreen] no longer calls this; see its doc
     * comment for why OCR is disabled and what has to be built before it can
     * be switched back on. Kept, rather than deleted, so re-enabling it is a
     * one-line change once CLICK_BOUNDS routing exists for label-less targets.
     *
     * When it was live it returned whole-screen OCR text unioned into
     * [describeScreen]'s accessibility-sourced list - not, despite an earlier
     * version of this comment, a gated fallback scoped to unlabeled widgets:
     * it was unconditional and undeduplicated, so an already-labeled widget's
     * text could appear twice. It never requests screen-capture permission -
     * that belongs to an explicit user action (recording an unlabeled tap),
     * not a side effect of speaking an instruction - and returns nothing if
     * permission isn't already available. Note it also does not recycle the
     * bitmap it captures (finding 4b); that must be fixed before re-enabling.
     */
    @Suppress("unused")
    private fun ocrFallback(): List<VisibleText> {
        val capturer = screenCaptureManager.screenCapture?.takeIf { it.available } ?: return emptyList()
        return try {
            runBlocking {
                val screen = capturer.captureImageWrapper() ?: return@runBlocking emptyList()
                paddle.ocr(screen).map {
                    VisibleText(it.text, MatchBy.TEXT, Bounds.fromRect(it.bounds))
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
```

with:

```kotlin
    /**
     * Whole-screen OCR text, for labels with no accessibility text or
     * content-description of their own. Every result is tagged
     * [VisibleText.isOcrSourced] so the planner knows to route an action on
     * it to a fixed-coordinate tap rather than a selector that has nothing
     * real to match against. Never requests screen-capture permission - that
     * belongs to an explicit user action (recording an unlabeled tap), not a
     * side effect of speaking an instruction - and returns nothing if
     * permission isn't already available, or if OCR fails for any reason.
     * Deduplication against already-collected accessibility entries happens
     * in [describeScreen], not here - this method is just the raw OCR pass.
     */
    private fun ocrFallback(): List<VisibleText> {
        val capturer = screenCaptureManager.screenCapture?.takeIf { it.available } ?: return emptyList()
        return try {
            runBlocking {
                val screen = capturer.captureImageWrapper() ?: return@runBlocking emptyList()
                try {
                    paddle.ocr(screen).map {
                        VisibleText(it.text, MatchBy.TEXT, Bounds.fromRect(it.bounds), isOcrSourced = true)
                    }
                } finally {
                    screen.recycle()
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
```

- [ ] **Step 5: Compile to verify**

Run: `./gradlew :autojs:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add autojs/src/main/java/com/stardust/autojs/core/agent/ScreenStateReader.kt
git commit -m "feat(agent): re-enable OCR screen reading with dedup against accessibility entries and proper recycling"
```

---

### Task 3: Full build verification and manual on-device check

**Files:** none (verification only).

- [ ] **Step 1: Run the full unit test suite**

Run: `./gradlew :autojs:testDebugUnitTest :app:testCommonDebugUnitTest`
Expected: BUILD SUCCESSFUL - all 20 `ClaudeActionPlannerTest` tests (the existing 16 plus the 4 new ones) passing alongside every other existing test.

- [ ] **Step 2: Assemble the debug APK**

Run: `./gradlew :app:assembleCommonDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual on-device verification**

With a Claude API key configured, on a screen containing at least one icon-only control with no accessible label but real-looking on-screen text nearby or on it (or, failing that, any screen where OCR can read something the accessibility tree can't):

1. Speak an instruction naming that OCR-only text (e.g. "tap Settings" where "Settings" is only readable via OCR, not an accessibility label). Confirm it's actually tapped - not a 10-second timeout followed by "couldn't find X" like before this sub-project.
2. Check device memory/logcat isn't accumulating obvious leaked bitmaps over several consecutive OCR-triggering voice turns (a rough sanity check, not a rigorous profiling session).
3. On a screen where the same text appears both as a real accessibility label AND would also be OCR-readable (e.g. a normal labeled button), speak an instruction targeting it. Confirm only one entry was effectively available to Claude - i.e. the click still works correctly and there's no evidence of a duplicate/confused match (this is best confirmed indirectly, since there's no direct way to inspect `ScreenDescription` from outside the running app).
4. Say "fill the <OCR-only label> field with some text" (or similar) targeting an OCR-only control. Confirm you get a clarifying question (sub-project 3's flow) rather than a silent failure or crash.
5. Confirm ordinary accessibility-labeled click/fill/scroll/wait instructions (unrelated to OCR) still work exactly as before - this sub-project must not have regressed the existing, already-working targeting path.

If any check fails, return to the relevant task, fix the issue, re-run its build step, and re-check here before considering this plan complete.
