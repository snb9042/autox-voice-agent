# Live Voice Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add push-to-talk voice control as a 5th item on the existing bubble: speak one instruction, Claude picks one action from what's visible on screen, the action runs immediately via the existing script engine, and the session's performed actions can be saved as a script through the same flow Record already uses.

**Architecture:** A new `ClaudeActionPlanner` (pure request/response logic + a thin OkHttp call) turns a screen description + transcribed instruction into one `SemanticAction`, reusing Spec 1's `ActionScriptGenerator`/script-engine pipeline to actually perform it. A new `VoiceAgentSession` accumulates performed actions the same way `SemanticRecorder` accumulates recorded ones, converging on the same Stop → optimize → Preview/Save/Discard dialog.

**Tech Stack:** Kotlin, OkHttp (already a dependency of the `autojs` module), Android `SpeechRecognizer`, Claude Messages API (tool use), this project's existing script engine.

**Spec:** `docs/superpowers/specs/2026-11-08-live-voice-agent-design.md`

## Global Constraints

- No new recording concept, and no modification to `SemanticRecorder`, its self-package filter, or the save/relocate flow — this feature is purely additive, converging on the same `SemanticAction` → `ActionOptimizer` → `ActionScriptGenerator` → Preview/Save/Discard pipeline Record already populates.
- Single action per spoken instruction. No multi-step autonomous chains.
- Text/description targeting only — no image-template matching in voice mode (no physical tap exists to crop a template from).
- Never block the main thread with network calls, OCR, or script-execution waits — mirroring the precedent already established for screen capture in `ScreenStateReader`.
- Follow existing patterns verified during planning: accessibility-event/script-engine code lives in `autojs/src/main/java/com/stardust/autojs/core/agent/`; UI/permission/settings glue lives in the `app` module; runtime permissions other than Accessibility/overlay go through the existing `com.stardust.autojs.core.permission.Permissions` helper (`Permissions.getPermissionsNeedToRequest(context, names)` / `Permissions.requestPermissions(context, names)` — fire-and-forget, the caller re-checks on the next user action, exactly like Spec 1 already does for the Accessibility Service); main-thread UI feedback from a background thread goes through `AutoJs.getInstance().uiHandler` (`.toast(message)`, `.post { ... }`).

---

### Task 1: `ActionType.WAIT` and its codegen

**Files:**
- Modify: `autojs/src/main/java/com/stardust/autojs/core/agent/SemanticAction.kt`
- Modify: `autojs/src/main/java/com/stardust/autojs/core/agent/ActionScriptGenerator.kt`
- Modify: `autojs/src/test/kotlin/com/stardust/autojs/core/agent/ActionScriptGeneratorTest.kt`

**Interfaces:**
- Consumes: the existing `ActionType`, `SemanticAction`, `MatchBy`, `Bounds` (Spec 1, already on this branch).
- Produces: `ActionType.WAIT` — a new enum value. A `SemanticAction` with `type = WAIT` must have `targetText` and `matchBy` set (the text/description to wait for); no click is performed. Later tasks (`ClaudeActionPlanner`) construct these directly.

This task has no Android dependency — it is a pure addition to logic already covered by `ActionScriptGeneratorTest.kt`.

- [ ] **Step 1: Write the failing tests**

Add to `ActionScriptGeneratorTest.kt` (alongside the existing tests — match whatever import/setup the file already has for `bounds`):

```kotlin
    @Test
    fun `WAIT with a text label polls without clicking`() {
        val script = ActionScriptGenerator.generate(
            listOf(SemanticAction(ActionType.WAIT, bounds, targetText = "Loading complete", matchBy = MatchBy.TEXT))
        )
        assertTrue(script.contains("__findTextNear(\"Loading complete\""))
        assertFalse(script.contains(".click()"))
        assertFalse(script.contains(".longClick()"))
        assertFalse(script.contains(".setText("))
        assertTrue(script.contains("if (!step0) throw Error(\"Step 0: target \\\"Loading complete\\\" not found\");"))
    }

    @Test
    fun `WAIT with a desc label uses findDescNear`() {
        val script = ActionScriptGenerator.generate(
            listOf(SemanticAction(ActionType.WAIT, bounds, targetText = "Submit button", matchBy = MatchBy.DESC))
        )
        assertTrue(script.contains("__findDescNear(\"Submit button\""))
    }

    @Test
    fun `WAIT with no targetText throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            ActionScriptGenerator.generate(listOf(SemanticAction(ActionType.WAIT, bounds)))
        }
    }
```

(If the existing test file doesn't already import `assertFalse`/`assertThrows`, add `import org.junit.Assert.assertFalse` and `import org.junit.Assert.assertThrows` alongside its existing `org.junit.Assert.*` imports.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :autojs:testDebugUnitTest --tests "com.stardust.autojs.core.agent.ActionScriptGeneratorTest"`
Expected: FAIL to compile — `ActionType.WAIT` does not exist yet, and `generateStep`'s `when` has no branch for it.

- [ ] **Step 3: Add `ActionType.WAIT`**

In `SemanticAction.kt`, change:

```kotlin
enum class ActionType { CLICK_TEXT, CLICK_IMAGE, CLICK_BOUNDS, LONG_CLICK, FILL_TEXT, SCROLL }
```

to:

```kotlin
enum class ActionType { CLICK_TEXT, CLICK_IMAGE, CLICK_BOUNDS, LONG_CLICK, FILL_TEXT, SCROLL, WAIT }
```

- [ ] **Step 4: Add the codegen branch**

In `ActionScriptGenerator.kt`, add a branch to `generateStep`'s `when`:

```kotlin
    private fun generateStep(action: SemanticAction, index: Int): String = when (action.type) {
        ActionType.CLICK_TEXT -> clickOrLongClickByText(action, index, "click")
        ActionType.LONG_CLICK -> clickOrLongClickByText(action, index, "longClick")
        ActionType.FILL_TEXT -> fillTextStep(action, index)
        ActionType.CLICK_IMAGE -> clickImageStep(action, index)
        ActionType.CLICK_BOUNDS -> clickBoundsStep(action, index)
        ActionType.SCROLL -> scrollStep(action, index)
        ActionType.WAIT -> waitStep(action, index)
    }
```

and a new private function, placed next to `clickOrLongClickByText`:

```kotlin
    private fun waitStep(action: SemanticAction, index: Int): String {
        val text = requireNotNull(action.targetText) { "Step $index (WAIT) has no targetText" }
        val finder = finderFor(action.matchBy)
        return """
            |// Step $index: wait for "${comment(text)}"
            |var step$index = $finder("${esc(text)}", ${action.bounds.centerX()}, ${action.bounds.centerY()}, $FIND_TIMEOUT_MS);
            |if (!step$index) throw Error("Step $index: target \"${esc(text)}\" not found");
        """.trimMargin()
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :autojs:testDebugUnitTest --tests "com.stardust.autojs.core.agent.ActionScriptGeneratorTest"`
Expected: PASS (all tests, old and new).

- [ ] **Step 6: Commit**

```bash
git add autojs/src/main/java/com/stardust/autojs/core/agent/SemanticAction.kt autojs/src/main/java/com/stardust/autojs/core/agent/ActionScriptGenerator.kt autojs/src/test/kotlin/com/stardust/autojs/core/agent/ActionScriptGeneratorTest.kt
git commit -m "feat(agent): add a WAIT action type for the voice agent"
```

---

### Task 2: `VoiceAgentSession`

**Files:**
- Create: `autojs/src/main/java/com/stardust/autojs/core/agent/VoiceAgentSession.kt`
- Test: `autojs/src/test/kotlin/com/stardust/autojs/core/agent/VoiceAgentSessionTest.kt`

**Interfaces:**
- Consumes: `SemanticAction` (Spec 1).
- Produces: `class VoiceAgentSession { enum class State { IDLE, ACTIVE }; val state: State; fun start(); fun addAction(action: SemanticAction); fun stop(): List<SemanticAction> }`. Task 6 (`AgentBubble`) holds one instance of this, checks `.state` the same way it already checks `SemanticRecorder.state` for mutual exclusion, and calls `.stop()` to feed the same summary-dialog flow Record already uses.

Deliberately mirrors `SemanticRecorder`'s shape (state enum + action list + start/stop) so the two read the same way at call sites. Purely in-memory bookkeeping — no Android dependency, fully unit-testable.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.stardust.autojs.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentSessionTest {

    private val action = SemanticAction(ActionType.CLICK_TEXT, Bounds(0, 0, 10, 10), targetText = "Login", matchBy = MatchBy.TEXT)

    @Test
    fun `starts idle`() {
        assertEquals(VoiceAgentSession.State.IDLE, VoiceAgentSession().state)
    }

    @Test
    fun `start moves to ACTIVE`() {
        val session = VoiceAgentSession()
        session.start()
        assertEquals(VoiceAgentSession.State.ACTIVE, session.state)
    }

    @Test
    fun `stop returns accumulated actions in order and moves back to IDLE`() {
        val session = VoiceAgentSession()
        session.start()
        val second = action.copy(targetText = "Submit")
        session.addAction(action)
        session.addAction(second)
        val result = session.stop()
        assertEquals(listOf(action, second), result)
        assertEquals(VoiceAgentSession.State.IDLE, session.state)
    }

    @Test
    fun `starting again after a stop clears the previous session's actions`() {
        val session = VoiceAgentSession()
        session.start()
        session.addAction(action)
        session.stop()
        session.start()
        assertTrue(session.stop().isEmpty())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :autojs:testDebugUnitTest --tests "com.stardust.autojs.core.agent.VoiceAgentSessionTest"`
Expected: FAIL to compile — `VoiceAgentSession` does not exist yet.

- [ ] **Step 3: Implement `VoiceAgentSession`**

```kotlin
package com.stardust.autojs.core.agent

import java.util.concurrent.CopyOnWriteArrayList

class VoiceAgentSession {

    enum class State { IDLE, ACTIVE }

    @Volatile
    var state: State = State.IDLE
        private set

    private val actions = CopyOnWriteArrayList<SemanticAction>()

    fun start() {
        actions.clear()
        state = State.ACTIVE
    }

    fun addAction(action: SemanticAction) {
        actions.add(action)
    }

    fun stop(): List<SemanticAction> {
        state = State.IDLE
        return actions.toList()
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :autojs:testDebugUnitTest --tests "com.stardust.autojs.core.agent.VoiceAgentSessionTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add autojs/src/main/java/com/stardust/autojs/core/agent/VoiceAgentSession.kt autojs/src/test/kotlin/com/stardust/autojs/core/agent/VoiceAgentSessionTest.kt
git commit -m "feat(agent): add VoiceAgentSession to accumulate live-agent actions"
```

---

### Task 3: `ClaudeActionPlanner`

**Files:**
- Create: `autojs/src/main/java/com/stardust/autojs/core/agent/ClaudeActionPlanner.kt`
- Test: `autojs/src/test/kotlin/com/stardust/autojs/core/agent/ClaudeActionPlannerTest.kt`

**Interfaces:**
- Consumes: `SemanticAction`, `ActionType`, `ScrollDirection`, `MatchBy`, `Bounds` (Spec 1 + Task 1).
- Produces:
  - `data class VisibleText(val text: String, val matchBy: MatchBy, val bounds: Bounds)`
  - `data class ScreenDescription(val screenBounds: Bounds, val visibleText: List<VisibleText>)`
  - `class ClaudeActionPlanner(httpClient: OkHttpClient = ...) { fun plan(instruction: String, screen: ScreenDescription, apiKey: String): SemanticAction? }` — the one method Task 6 calls. Returns `null` if Claude couldn't determine an action, the call failed, or the response didn't parse.
  - `companion object { fun buildRequestBody(instruction: String, screen: ScreenDescription): JSONObject; fun parseResponse(responseBody: String, screen: ScreenDescription): SemanticAction? }` — the two pure functions this task's tests exercise directly; `plan()` is a thin wrapper gluing them to a real HTTP call, not itself unit-tested.
  - Task 4 (`ScreenStateReader.describeScreen`) produces a `ScreenDescription`; Task 6 (`AgentBubble`) calls `plan()` with it.

**Verified API details (don't re-derive, use as given):**
- OkHttp is already a runtime dependency of the `autojs` module (`autojs/build.gradle.kts`: `api(libs.okhttp)`, version 4.12.0) — no new dependency needed.
- `org.json.JSONObject`/`JSONArray` are part of the Android platform SDK, always available, no import beyond `org.json.*`.
- Claude Messages API: `POST https://api.anthropic.com/v1/messages`, headers `x-api-key: <key>`, `anthropic-version: 2023-06-01`, `content-type: application/json`. Model: `claude-haiku-4-5-20251001`.
- A tool-use response's `content` array contains a block with `"type": "tool_use"` and an `"input"` object matching the tool's `input_schema`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.stardust.autojs.core.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeActionPlannerTest {

    private val screen = ScreenDescription(
        screenBounds = Bounds(0, 0, 1080, 2400),
        visibleText = listOf(
            VisibleText("Login", MatchBy.TEXT, Bounds(100, 200, 300, 260)),
            VisibleText("More options", MatchBy.DESC, Bounds(900, 50, 980, 120)),
            VisibleText("Email", MatchBy.DESC, Bounds(50, 400, 500, 460))
        )
    )

    // --- buildRequestBody ---

    @Test
    fun `request body targets the configured model and names every visible entry`() {
        val body = ClaudeActionPlanner.buildRequestBody("tap login", screen)
        assertEquals("claude-haiku-4-5-20251001", body.getString("model"))
        val content = body.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(content.contains("text: \"Login\""))
        assertTrue(content.contains("desc: \"More options\""))
        assertTrue(content.contains("tap login"))
    }

    @Test
    fun `request body forces the perform_action tool`() {
        val body = ClaudeActionPlanner.buildRequestBody("tap login", screen)
        assertEquals("perform_action", body.getJSONObject("tool_choice").getString("name"))
        assertEquals("perform_action", body.getJSONArray("tools").getJSONObject(0).getString("name"))
    }

    // --- parseResponse ---

    private fun toolUseResponse(input: JSONObject): String =
        JSONObject()
            .put(
                "content", org.json.JSONArray().put(
                    JSONObject().put("type", "tool_use").put("input", input)
                )
            )
            .toString()

    @Test
    fun `click_text on a known target produces a matching CLICK_TEXT action`() {
        val response = toolUseResponse(
            JSONObject().put("action", "click_text").put("target", "Login").put("reason", "user asked to log in")
        )
        val action = ClaudeActionPlanner.parseResponse(response, screen)
        assertEquals(ActionType.CLICK_TEXT, action?.type)
        assertEquals("Login", action?.targetText)
        assertEquals(MatchBy.TEXT, action?.matchBy)
        assertEquals(Bounds(100, 200, 300, 260), action?.bounds)
    }

    @Test
    fun `click_desc on a known target produces a DESC-matched CLICK_TEXT action`() {
        val response = toolUseResponse(
            JSONObject().put("action", "click_desc").put("target", "More options").put("reason", "open the menu")
        )
        val action = ClaudeActionPlanner.parseResponse(response, screen)
        assertEquals(ActionType.CLICK_TEXT, action?.type)
        assertEquals(MatchBy.DESC, action?.matchBy)
    }

    @Test
    fun `fill_desc carries the value through`() {
        val response = toolUseResponse(
            JSONObject().put("action", "fill_desc").put("target", "Email").put("value", "a@b.com").put("reason", "fill the email field")
        )
        val action = ClaudeActionPlanner.parseResponse(response, screen)
        assertEquals(ActionType.FILL_TEXT, action?.type)
        assertEquals("a@b.com", action?.fillValue)
        assertEquals(MatchBy.DESC, action?.matchBy)
    }

    @Test
    fun `wait_for_text produces a WAIT action`() {
        val response = toolUseResponse(
            JSONObject().put("action", "wait_for_text").put("target", "Login").put("reason", "waiting for the page")
        )
        val action = ClaudeActionPlanner.parseResponse(response, screen)
        assertEquals(ActionType.WAIT, action?.type)
        assertEquals("Login", action?.targetText)
    }

    @Test
    fun `long_click_text sets isLongClick`() {
        val response = toolUseResponse(
            JSONObject().put("action", "long_click_text").put("target", "Login").put("reason", "long press to select")
        )
        val action = ClaudeActionPlanner.parseResponse(response, screen)
        assertEquals(ActionType.LONG_CLICK, action?.type)
        assertTrue(action?.isLongClick == true)
    }

    @Test
    fun `scroll uses the screen bounds, not a matched target`() {
        val response = toolUseResponse(
            JSONObject().put("action", "scroll").put("direction", "down").put("reason", "see more content")
        )
        val action = ClaudeActionPlanner.parseResponse(response, screen)
        assertEquals(ActionType.SCROLL, action?.type)
        assertEquals(ScrollDirection.DOWN, action?.scrollDirection)
        assertEquals(screen.screenBounds, action?.bounds)
    }

    @Test
    fun `action none returns null`() {
        val response = toolUseResponse(JSONObject().put("action", "none").put("reason", "nothing on screen matches"))
        assertNull(ClaudeActionPlanner.parseResponse(response, screen))
    }

    @Test
    fun `a target not present on screen returns null rather than a guessed bounds`() {
        val response = toolUseResponse(
            JSONObject().put("action", "click_text").put("target", "Nonexistent Button").put("reason", "guess")
        )
        assertNull(ClaudeActionPlanner.parseResponse(response, screen))
    }

    @Test
    fun `a response with no tool_use block returns null`() {
        val response = JSONObject().put("content", org.json.JSONArray()).toString()
        assertNull(ClaudeActionPlanner.parseResponse(response, screen))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :autojs:testDebugUnitTest --tests "com.stardust.autojs.core.agent.ClaudeActionPlannerTest"`
Expected: FAIL to compile — none of these types exist yet.

- [ ] **Step 3: Implement `ClaudeActionPlanner`**

```kotlin
package com.stardust.autojs.core.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class VisibleText(val text: String, val matchBy: MatchBy, val bounds: Bounds)

data class ScreenDescription(val screenBounds: Bounds, val visibleText: List<VisibleText>)

class ClaudeActionPlanner(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    /**
     * One Claude call: given what's currently visible and one spoken
     * instruction, returns the single action Claude chose, or null if Claude
     * couldn't determine one, the HTTP call failed, or the response didn't
     * match the expected shape. Blocking - call off the main thread.
     */
    fun plan(instruction: String, screen: ScreenDescription, apiKey: String): SemanticAction? {
        val request = Request.Builder()
            .url(API_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("content-type", "application/json")
            .post(buildRequestBody(instruction, screen).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                parseResponse(body, screen)
            }
        } catch (e: IOException) {
            null
        }
    }

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        private const val MODEL = "claude-haiku-4-5-20251001"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** Pure, independently testable: the request JSON Claude expects. */
        fun buildRequestBody(instruction: String, screen: ScreenDescription): JSONObject {
            val screenDescription = screen.visibleText.joinToString("\n") { entry ->
                val kind = if (entry.matchBy == MatchBy.TEXT) "text" else "desc"
                "$kind: \"${entry.text}\""
            }
            val tool = JSONObject()
                .put("name", "perform_action")
                .put(
                    "description",
                    "Perform exactly one action on the current Android screen based on the user's spoken instruction."
                )
                .put("input_schema", buildInputSchema())
            return JSONObject()
                .put("model", MODEL)
                .put("max_tokens", 256)
                .put("tools", JSONArray().put(tool))
                .put("tool_choice", JSONObject().put("type", "tool").put("name", "perform_action"))
                .put(
                    "messages", JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                "Visible on screen right now:\n$screenDescription\n\nInstruction: \"$instruction\""
                            )
                    )
                )
        }

        private fun buildInputSchema(): JSONObject {
            val actionEnum = JSONArray(
                listOf(
                    "click_text", "click_desc",
                    "long_click_text", "long_click_desc",
                    "fill_text", "fill_desc",
                    "scroll",
                    "wait_for_text", "wait_for_desc",
                    "none"
                )
            )
            val properties = JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", actionEnum))
                .put(
                    "target", JSONObject().put("type", "string").put(
                        "description",
                        "The exact visible text/description to act on, copied verbatim from the screen description. Required for every action except scroll and none."
                    )
                )
                .put(
                    "value", JSONObject().put("type", "string")
                        .put("description", "Text to type. Required only for fill_text/fill_desc.")
                )
                .put(
                    "direction", JSONObject().put("type", "string")
                        .put("enum", JSONArray(listOf("up", "down", "left", "right")))
                        .put("description", "Required only for scroll.")
                )
                .put(
                    "reason", JSONObject().put("type", "string")
                        .put("description", "One sentence: why this action, or why none could be determined.")
                )
            return JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", JSONArray(listOf("action", "reason")))
        }

        /** Pure, independently testable: Claude's response JSON -> one SemanticAction, or null. */
        fun parseResponse(responseBody: String, screen: ScreenDescription): SemanticAction? {
            val content = JSONObject(responseBody).optJSONArray("content") ?: return null
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") != "tool_use") continue
                val input = block.optJSONObject("input") ?: return null
                return actionFromInput(input, screen)
            }
            return null
        }

        private fun actionFromInput(input: JSONObject, screen: ScreenDescription): SemanticAction? {
            val action = input.optString("action")
            if (action.isEmpty() || action == "none") return null
            val targetName = input.optString("target").takeIf { it.isNotBlank() }
            val matched = targetName?.let { name -> screen.visibleText.firstOrNull { it.text == name } }
            return when (action) {
                "click_text", "click_desc" -> matched?.let {
                    SemanticAction(ActionType.CLICK_TEXT, it.bounds, targetText = it.text, matchBy = it.matchBy)
                }
                "long_click_text", "long_click_desc" -> matched?.let {
                    SemanticAction(
                        ActionType.LONG_CLICK, it.bounds,
                        targetText = it.text, matchBy = it.matchBy, isLongClick = true
                    )
                }
                "fill_text", "fill_desc" -> matched?.let {
                    SemanticAction(
                        ActionType.FILL_TEXT, it.bounds,
                        targetText = it.text, matchBy = it.matchBy, fillValue = input.optString("value")
                    )
                }
                "wait_for_text", "wait_for_desc" -> matched?.let {
                    SemanticAction(ActionType.WAIT, it.bounds, targetText = it.text, matchBy = it.matchBy)
                }
                "scroll" -> scrollDirectionFrom(input)?.let {
                    SemanticAction(ActionType.SCROLL, screen.screenBounds, scrollDirection = it)
                }
                else -> null
            }
        }

        private fun scrollDirectionFrom(input: JSONObject): ScrollDirection? = when (input.optString("direction")) {
            "up" -> ScrollDirection.UP
            "down" -> ScrollDirection.DOWN
            "left" -> ScrollDirection.LEFT
            "right" -> ScrollDirection.RIGHT
            else -> null
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :autojs:testDebugUnitTest --tests "com.stardust.autojs.core.agent.ClaudeActionPlannerTest"`
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

```bash
git add autojs/src/main/java/com/stardust/autojs/core/agent/ClaudeActionPlanner.kt autojs/src/test/kotlin/com/stardust/autojs/core/agent/ClaudeActionPlannerTest.kt
git commit -m "feat(agent): add ClaudeActionPlanner turning one instruction into one action"
```

---

### Task 4: `ScreenStateReader.describeScreen()`

**Files:**
- Modify: `autojs/src/main/java/com/stardust/autojs/core/agent/ScreenStateReader.kt`

**Interfaces:**
- Consumes: `VisibleText`, `ScreenDescription` (Task 3); `Bounds`, `MatchBy` (Spec 1); the existing `ScreenStateReader.screenCaptureManager` field (already present on this class).
- Produces: `fun describeScreen(service: AccessibilityService): ScreenDescription` — Task 6 (`AgentBubble`) calls this once per voice turn, off the main thread.

This task is Android-framework-dependent (`AccessibilityNodeInfo` tree traversal, screen capture, OCR) — like the rest of `ScreenStateReader`, it has no isolated unit test and is verified manually in Task 8.

**Verified API details:**
- `Paddle().ocr(image: ImageWrapper, useSlim: Boolean = true): List<OcrResult>` (`autojs/src/main/java/com/stardust/autojs/runtime/api/Paddle.kt`) — `OcrResult(confidence: Float, text: String, bounds: Rect)` (`...runtime/api/OcrResult` — actually `com.baidu.paddle.lite.demo.ocr.OcrResult`, the `paddleocr` module). `Paddle` is already a dependency of the `autojs` module (the `paddleocr` module is already included in this project's Gradle build).
- `AccessibilityNodeInfoHelper.getBoundsInScreen(node): Rect` is already used elsewhere in this file.

- [ ] **Step 1: Add `describeScreen()` and its helpers**

Add to `ScreenStateReader.kt` (new imports: `android.view.accessibility.AccessibilityNodeInfo` is already imported; add `com.baidu.paddle.lite.demo.ocr.Paddle` — actually `com.stardust.autojs.runtime.api.Paddle` — verify the exact package by checking the file you just read in planning, `autojs/src/main/java/com/stardust/autojs/runtime/api/Paddle.kt` starts with `package com.stardust.autojs.runtime.api`, so import `com.stardust.autojs.runtime.api.Paddle`):

```kotlin
    /**
     * Everything visible right now, for the voice agent to reason over: every
     * accessibility node's text/content-description with its bounds, plus (when
     * screen-capture is already available - this never prompts for it) OCR text
     * for labels with no accessible text. Call off the main thread: OCR on a
     * full screenshot is not fast enough to run on the accessibility-event
     * thread, the same reason template capture already runs on
     * [captureExecutor].
     */
    fun describeScreen(service: AccessibilityService): ScreenDescription {
        val root = service.rootInActiveWindow
            ?: return ScreenDescription(Bounds(0, 0, 0, 0), emptyList())
        val screenBounds = Bounds.fromRect(AccessibilityNodeInfoHelper.getBoundsInScreen(root))
        val visible = mutableListOf<VisibleText>()
        collectVisibleText(root, visible)
        root.recycle()
        visible.addAll(ocrFallback())
        return ScreenDescription(screenBounds, visible)
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo, out: MutableList<VisibleText>) {
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            val bounds = Bounds.fromRect(AccessibilityNodeInfoHelper.getBoundsInScreen(node))
            when {
                text != null -> out.add(VisibleText(text, MatchBy.TEXT, bounds))
                desc != null -> out.add(VisibleText(desc, MatchBy.DESC, bounds))
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectVisibleText(child, out)
            child.recycle()
        }
    }

    /**
     * Best-effort text for labels with no accessible text/description. Never
     * requests screen-capture permission - that belongs to an explicit user
     * action (recording an unlabeled tap), not a side effect of speaking an
     * instruction. Returns nothing if permission isn't already available.
     */
    private fun ocrFallback(): List<VisibleText> {
        val capturer = screenCaptureManager.screenCapture?.takeIf { it.available } ?: return emptyList()
        return try {
            runBlocking {
                val screen = capturer.captureImageWrapper() ?: return@runBlocking emptyList()
                com.stardust.autojs.runtime.api.Paddle().ocr(screen).map {
                    VisibleText(it.text, MatchBy.TEXT, Bounds.fromRect(it.bounds))
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
```

(`screenCaptureManager` is the existing private field on this class; `runBlocking` is already imported in this file.)

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew :autojs:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add autojs/src/main/java/com/stardust/autojs/core/agent/ScreenStateReader.kt
git commit -m "feat(agent): add ScreenStateReader.describeScreen() for the voice agent"
```

---

### Task 5: Claude API key settings

**Files:**
- Modify: `app/src/main/java/org/autojs/autojs/Pref.java`
- Modify: `app/src/main/res/values/preference_keys.xml`
- Modify: `app/src/main/res/xml/preferences.xml`
- Modify: `app/src/main/res-i18n/values/strings.xml`
- Modify: `app/src/main/res-i18n/values-en/strings.xml`

**Interfaces:**
- Produces: `Pref.getClaudeApiKey(): String` (empty string if unset) — Task 6 calls this before starting a voice turn.

This project already has a standard preferences screen (`app/src/main/res/xml/preferences.xml`, backed by `SettingsActivity`/`PreferenceFragment`, reachable from the drawer's existing "Set up" entry) using `EditTextPreference` for free-text settings (see `key_max_length_for_code_completion`) — this task adds to that existing screen rather than building a new one, which is simpler and more consistent with how this app already does settings than a bespoke screen would be.

- [ ] **Step 1: Add the preference key**

In `app/src/main/res/values/preference_keys.xml`, add inside `<resources>`:

```xml
    <string name="key_claude_api_key" translatable="false">key_claude_api_key</string>
```

- [ ] **Step 2: Add the title/summary strings**

In `app/src/main/res-i18n/values-en/strings.xml`, add (near the other `text_a*`/settings-related entries, matching the file's existing alphabetical-ish clustering):

```xml
    <string name="text_ai_agent">AI Agent</string>
    <string name="text_claude_api_key">Claude API key</string>
    <string name="summary_claude_api_key">Used by the voice agent to decide what to do from your spoken instructions</string>
```

In `app/src/main/res-i18n/values/strings.xml` (the Chinese default), add the same three keys at the matching position, in the terminology style of this file's neighbouring entries:

```xml
    <string name="text_ai_agent">AI 智能体</string>
    <string name="text_claude_api_key">Claude API 密钥</string>
    <string name="summary_claude_api_key">语音智能体根据您的语音指令决定操作时使用</string>
```

- [ ] **Step 3: Add the preference entry**

In `app/src/main/res/xml/preferences.xml`, add a new `PreferenceCategory` (placing it directly after the existing `text_script_running` category and before `text_edit` is fine — any position between existing categories is fine, just don't disturb the existing ones):

```xml
    <PreferenceCategory
        android:layout="@layout/preference_category_custom"
        android:title="@string/text_ai_agent">

        <EditTextPreference
            android:key="@string/key_claude_api_key"
            android:layout="@layout/preference_custom"
            android:summary="@string/summary_claude_api_key"
            android:title="@string/text_claude_api_key" />
    </PreferenceCategory>
```

- [ ] **Step 4: Add the `Pref` getter**

In `app/src/main/java/org/autojs/autojs/Pref.java`, add (near the other simple getters — e.g. next to `isAgentBubbleShown()`):

```java
    public static String getClaudeApiKey() {
        return def().getString(getString(R.string.key_claude_api_key), "");
    }
```

(This file already has a `getString(int)` helper resolving a string resource via the app context, and a `def()` helper returning the default `SharedPreferences` — both used by the existing getters right above this one; follow the exact same two calls, don't reimplement either.)

- [ ] **Step 5: Build to verify it compiles**

Run: `./gradlew :app:compileCommonDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/autojs/autojs/Pref.java app/src/main/res/values/preference_keys.xml app/src/main/res/xml/preferences.xml app/src/main/res-i18n/values/strings.xml app/src/main/res-i18n/values-en/strings.xml
git commit -m "feat(agent): add a Claude API key setting for the voice agent"
```

---

### Task 6: Wire the mic into `AgentBubble`

**Files:**
- Create: `app/src/main/res/drawable/ic_mic.xml`
- Modify: `app/src/main/res/layout/agent_bubble_action_menu.xml`
- Modify: `app/src/main/res-i18n/values/strings.xml`
- Modify: `app/src/main/res-i18n/values-en/strings.xml`
- Modify: `app/src/main/java/org/autojs/autojs/ui/agent/AgentBubble.kt`

**Interfaces:**
- Consumes: `VoiceAgentSession`, `ClaudeActionPlanner`, `ScreenDescription` (Tasks 2-4); `ScreenStateReader.describeScreen()` (Task 4); `Pref.getClaudeApiKey()` (Task 5); `ActionOptimizer`, `ActionScriptGenerator`, `SemanticAction` (Spec 1, already wired into this file); `com.stardust.autojs.core.permission.Permissions` (verified during planning: `getPermissionsNeedToRequest(Context, Array<String>): Array<String>`, `requestPermissions(Context, Array<String>)`); `AutoJs.getInstance().uiHandler` (`UiHandler.toast(String)`, inherited `Handler.post(Runnable)`); `com.stardust.view.accessibility.AccessibilityService.instance` (the running service singleton, nullable); `AutoJs.getInstance().scriptEngineService.execute(source, listener, config)`.
- Produces: no new public API — this task makes the mic button on the already-shipped bubble actually work.

This task is Android-UI-and-network-dependent, with no isolated unit test, verified manually in Task 8.

- [ ] **Step 1: Add a mic icon**

Create `app/src/main/res/drawable/ic_mic.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FF000000"
        android:pathData="M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zm5.3,-3c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z"/>
</vector>
```

- [ ] **Step 2: Add the 5th menu item**

In `app/src/main/res/layout/agent_bubble_action_menu.xml`, add a 5th `ImageView` after `agent_stop_all_scripts` and before the closing `</org.autojs.autojs.ui.floating.CircularActionMenu>` tag:

```xml
    <ImageView
        android:id="@+id/agent_talk"
        android:layout_width="42dp"
        android:layout_height="42dp"
        android:layout_gravity="center"
        android:background="@drawable/circle_blue"
        android:contentDescription="@string/text_agent_talk"
        android:foreground="?selectableItemBackgroundBorderless"
        android:padding="8dp"
        android:src="@drawable/ic_mic" />
```

(`circle_blue` is an existing drawable, already used elsewhere in this project's circular menus — check `common/src/main/res/drawable/circle_blue.xml` exists before using it; it was referenced in `circular_action_menu.xml` during Spec 1 planning, so it should already be present.)

- [ ] **Step 3: Add the new strings**

In `app/src/main/res-i18n/values-en/strings.xml`:

```xml
    <string name="text_agent_talk">Talk</string>
    <string name="text_agent_listening">Listening…</string>
    <string name="text_agent_thinking">Thinking…</string>
    <string name="text_agent_no_api_key">Set a Claude API key in Settings first</string>
    <string name="text_agent_no_speech">Didn\'t catch that</string>
    <string name="text_agent_no_action">Couldn\'t work out what to do</string>
    <string name="text_agent_target_not_found">Couldn\'t find \"%1$s\"</string>
    <string name="text_agent_action_performed">%1$s</string>
```

In `app/src/main/res-i18n/values/strings.xml` (Chinese default), the same keys with Chinese text matching this file's tone:

```xml
    <string name="text_agent_talk">说话</string>
    <string name="text_agent_listening">正在聆听…</string>
    <string name="text_agent_thinking">思考中…</string>
    <string name="text_agent_no_api_key">请先在设置中填写 Claude API 密钥</string>
    <string name="text_agent_no_speech">没有听清</string>
    <string name="text_agent_no_action">无法确定要执行的操作</string>
    <string name="text_agent_target_not_found">未找到“%1$s”</string>
    <string name="text_agent_action_performed">%1$s</string>
```

- [ ] **Step 4: Wire `AgentBubble.kt`**

This step has several parts to the same file; apply them together since they interact (the existing `stopRecording()` is being generalized into `stopActive()`, and the menu wiring changes which method the Stop button calls).

**4a. New imports.** Add to the top of `AgentBubble.kt`:

```kotlin
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.stardust.autojs.core.agent.ClaudeActionPlanner
import com.stardust.autojs.core.agent.VoiceAgentSession
import com.stardust.autojs.execution.ExecutionConfig
import com.stardust.autojs.execution.ScriptExecution
import com.stardust.autojs.execution.ScriptExecutionListener
import com.stardust.autojs.script.JavaScriptSource
import com.stardust.view.accessibility.AccessibilityService
import java.io.Reader
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
```

**4b. New fields**, alongside the existing `mRecorder` field:

```kotlin
    private val mVoiceSession = VoiceAgentSession()
    private val mPlanner = ClaudeActionPlanner()
    private var mSpeechRecognizer: SpeechRecognizer? = null
    /**
     * One voice turn (screen description + the Claude call + running the
     * resulting script) is network- and OCR-bound and must never run on the
     * main thread - the same reasoning that already keeps template capture
     * off it in ScreenStateReader. Single-threaded so overlapping turns (a
     * second mic tap before the first finishes) queue rather than race.
     */
    private val voiceTurnExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "agent-voice-turn").apply { isDaemon = true }
    }
```

**4c. Wire the 5th menu item**, in `inflateMenuItems`:

```kotlin
                    menu.findViewById<View>(R.id.agent_stop_all_scripts)
                        ?.setOnClickListener { stopAllScripts() }
                    menu.findViewById<View>(R.id.agent_talk)?.setOnClickListener { startVoiceTurn() }
                    return menu
```

**4d. Generalize Stop.** Replace the existing `stopRecording()` method with:

```kotlin
    private fun stopActive() {
        mWindow?.collapse()
        when {
            mRecorder.state == SemanticRecorder.State.RECORDING -> {
                val actions = mRecorder.stop()
                updateIcon()
                finishSession(actions)
            }
            mVoiceSession.state == VoiceAgentSession.State.ACTIVE -> {
                finishSession(mVoiceSession.stop())
            }
            // Nothing active - don't optimize, generate, or show a "0
            // step(s) recorded" dialog for an accidental Stop tap.
        }
    }

    private fun finishSession(actions: List<SemanticAction>) {
        val optimized = ActionOptimizer.optimize(actions)
        // Templates belonging to steps the optimizer dropped can never be
        // referenced again.
        discardTemplates(actions.filterNot { optimized.contains(it) })
        showSummaryDialog(optimized)
    }
```

and update the menu wiring in `inflateMenuItems` (from Step 4c's surrounding block) to call it:

```kotlin
                    menu.findViewById<View>(R.id.agent_stop)?.setOnClickListener { stopActive() }
```

(This replaces the old `{ stopRecording() }` wiring - `stopRecording()` no longer exists as a separate method, its body is now inside `stopActive()`'s first branch via `finishSession`.)

**4e. Mutual exclusion on Record.** In `startRecording()`, extend the existing early-return guard:

```kotlin
    private fun startRecording() {
        mWindow?.collapse()
        // Starting again would clear the in-progress recording, silently
        // discarding everything captured so far. A voice turn and a
        // recording must not run concurrently either - they'd both be
        // writing into the picture of "what happened this session".
        if (mRecorder.state == SemanticRecorder.State.RECORDING) return
        if (mVoiceSession.state == VoiceAgentSession.State.ACTIVE) return
        if (!AccessibilityServiceTool.isAccessibilityServiceEnabled(mContext)) {
            AccessibilityServiceTool.goToAccessibilitySetting()
            return
        }
        mRecorder.start()
        updateIcon()
    }
```

**4f. The voice turn itself.** Add these new methods:

```kotlin
    private fun startVoiceTurn() {
        mWindow?.collapse()
        if (mRecorder.state == SemanticRecorder.State.RECORDING) return
        if (!AccessibilityServiceTool.isAccessibilityServiceEnabled(mContext)) {
            AccessibilityServiceTool.goToAccessibilitySetting()
            return
        }
        if (Pref.getClaudeApiKey().isBlank()) {
            AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_api_key))
            return
        }
        val missing = Permissions.getPermissionsNeedToRequest(mContext, arrayOf("RECORD_AUDIO"))
        if (missing.isNotEmpty()) {
            Permissions.requestPermissions(mContext, missing)
            return
        }
        if (mVoiceSession.state != VoiceAgentSession.State.ACTIVE) {
            mVoiceSession.start()
        }
        listenForInstruction()
    }

    /** Must be called on the main thread - SpeechRecognizer requires it. */
    private fun listenForInstruction() {
        AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_listening))
        val recognizer = SpeechRecognizer.createSpeechRecognizer(mContext).also { mSpeechRecognizer = it }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: android.os.Bundle) {
                recognizer.destroy()
                mSpeechRecognizer = null
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) {
                    AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_speech))
                } else {
                    runVoiceTurn(text)
                }
            }

            override fun onError(error: Int) {
                recognizer.destroy()
                mSpeechRecognizer = null
                AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_speech))
            }

            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        recognizer.startListening(intent)
    }

    /** Runs the describe-plan-execute sequence on [voiceTurnExecutor]. */
    private fun runVoiceTurn(instruction: String) {
        AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_thinking))
        voiceTurnExecutor.execute {
            val service = AccessibilityService.instance
            if (service == null) {
                AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_action))
                return@execute
            }
            val screen = com.stardust.autojs.core.agent.ScreenStateReader().describeScreen(service)
            val action = mPlanner.plan(instruction, screen, Pref.getClaudeApiKey())
            if (action == null) {
                AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_action))
                return@execute
            }
            val succeeded = executeAction(action)
            if (succeeded) {
                mVoiceSession.addAction(action)
                AutoJs.getInstance().uiHandler.toast(instruction)
            } else {
                val label = action.targetText ?: instruction
                AutoJs.getInstance().uiHandler.toast(
                    mContext.getString(R.string.text_agent_target_not_found, label)
                )
            }
        }
    }

    /** Runs [action] as a one-step script and blocks (on the calling background thread) for the result. */
    private fun executeAction(action: SemanticAction): Boolean {
        val script = ActionScriptGenerator.generate(listOf(action))
        val source = object : JavaScriptSource("voice-turn") {
            override val script: String = script
            override val scriptReader: Reader? = null
        }
        val latch = CountDownLatch(1)
        var succeeded = false
        val listener = object : ScriptExecutionListener {
            override fun onStart(execution: ScriptExecution) {}
            override fun onSuccess(execution: ScriptExecution, result: Any?) {
                succeeded = true
                latch.countDown()
            }

            override fun onException(execution: ScriptExecution, e: Throwable) {
                succeeded = false
                latch.countDown()
            }
        }
        AutoJs.getInstance().scriptEngineService.execute(
            source, listener, ExecutionConfig(workingDirectory = Pref.getScriptDirPath())
        )
        latch.await(VOICE_TURN_EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return succeeded
    }
```

**4g. Add the timeout constant**, in the existing `companion object` block, alongside `TEMPLATE_STAGING_DIR_NAME`:

```kotlin
        /**
         * How long one voice turn's script is given to finish. The script's
         * own internal polling already times out after 10s (FIND_TIMEOUT_MS
         * in ActionScriptGenerator); this leaves headroom for script startup.
         */
        private const val VOICE_TURN_EXECUTION_TIMEOUT_MS = 15_000L
```

**4h. Clean up the recognizer on close.** In the existing `close()` method, add before the `mRecorder` check:

```kotlin
    fun close() {
        mSpeechRecognizer?.destroy()
        mSpeechRecognizer = null
        // ...the rest of this method is unchanged...
```

- [ ] **Step 5: Build to verify it compiles**

Run: `./gradlew :app:compileCommonDebugKotlin`
Expected: BUILD SUCCESSFUL. If `Permissions`/`PermissionRequestActivity` or `JavaScriptSource`'s constructor signature doesn't match what's written above, re-check the actual source under `autojs/src/main/java/com/stardust/autojs/core/permission/` and `autojs/src/main/java/com/stardust/autojs/script/JavaScriptSource.kt` and adjust to match - this step's code was verified against this branch's source during planning, but verify again before assuming a compile error is yours to chase elsewhere.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/drawable/ic_mic.xml app/src/main/res/layout/agent_bubble_action_menu.xml app/src/main/res-i18n/values/strings.xml app/src/main/res-i18n/values-en/strings.xml app/src/main/java/org/autojs/autojs/ui/agent/AgentBubble.kt
git commit -m "feat(agent): wire push-to-talk voice control into the bubble"
```

---

### Task 7: Full build verification

**Files:** none (verification only).

- [ ] **Step 1: Run the full unit test suite**

Run: `./gradlew :autojs:testDebugUnitTest :app:testCommonDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests from Tasks 1-3 passing alongside every pre-existing test (Spec 1's `ActionOptimizerTest`, `ActionScriptGeneratorTest`, `BoundsTest`, and the rest).

- [ ] **Step 2: Assemble the debug APK**

Run: `./gradlew :app:assembleCommonDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit (only if Steps 1-2 required fixes)**

If any fix was needed, stage exactly the files touched and commit:

```bash
git add -A
git commit -m "fix(agent): resolve build issues found in full verification"
```

If no fixes were needed, skip this step.

---

### Task 8: Manual end-to-end verification

**Files:** none (manual verification only - this feature is Android/network/voice dependent in ways no unit test can cover).

- [ ] **Step 1: Install and configure**

Install the debug APK (`./gradlew :app:installCommonDebug`). Open AutoX.js Settings → AI Agent, paste a real Claude API key. Confirm the Accessibility Service and overlay permission are still granted from Spec 1's testing (they should be, both survive an app update).

- [ ] **Step 2: Grant the microphone permission**

Tap the bubble's mic (5th item). Confirm a system permission prompt for microphone access appears (first use only), and that tapping the mic again after granting it starts listening instead of re-prompting.

- [ ] **Step 3: A simple click instruction**

In a labeled third-party app screen, tap the mic and say an instruction naming something visible by its on-screen text (e.g. "tap Settings"). Confirm: a "Listening…" toast, then "Thinking…", then either the action actually happens on screen or a clear "couldn't find X" toast - never a silent no-op.

- [ ] **Step 4: A fill instruction**

Say an instruction like "fill the email field with test at example dot com" (or whatever STT renders it as) on a screen with a visible, labeled text field. Confirm the field gets the typed value.

- [ ] **Step 5: A wait instruction**

Say "wait for [some text that will appear shortly, e.g. after navigating]" and confirm the agent polls without performing a click - no tap should land anywhere on screen for this turn.

- [ ] **Step 6: Save the voice session as a script**

After a couple of successful turns, tap Stop. Confirm the same "N step(s) recorded" summary dialog Record uses appears, Preview shows a script that looks like what Record's Preview produces (the same `__findTextNear`/`__findDescNear`/`auto.waitFor()` prelude), and Save writes it successfully.

- [ ] **Step 7: Confirm mutual exclusion with Record**

Start a recording (Record), then tap the mic - confirm nothing happens (no listening starts). Stop the recording, then start a voice turn, then tap Record mid-turn - confirm the same.

- [ ] **Step 8: Confirm isolation from Spec 1's recorder**

Confirm a voice-driven click does not also get picked up as a *recorded* step if a recording happens to be active at the same time (it shouldn't be reachable per Step 7's guard, but if it is, verify the self-package filter and recorder state guards still behave correctly) - and that Record/Stop/Play/Stop-all-scripts all still work exactly as they did at the end of Spec 1's testing.

If any step fails, return to the relevant task, fix the issue, and re-run that task's build/test step before re-attempting this checklist.
