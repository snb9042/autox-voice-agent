# AI Clarification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the voice agent can't determine an action, or determines one whose target isn't found at execution time, it asks one clarifying question, listens for a spoken answer, and retries exactly once with the combined information before falling back to today's terminal failure toast.

**Architecture:** `ClaudeActionPlanner.plan()`'s return type changes from a plain nullable to a three-way `PlanResult` (`Action`/`NeedsClarification`/`Failed`), so `AgentBubble` can distinguish "Claude has a real question" from "the call just failed." `AgentBubble.runVoiceTurn()` gains an `isRetry` flag to enforce the one-round cap, and `listenForInstruction()` gains a pluggable continuation so the clarification answer can route to a retry instead of starting a brand-new turn.

**Tech Stack:** Kotlin, the existing `ClaudeActionPlanner`/`AgentBubble` pipeline.

**Spec:** `docs/superpowers/specs/2026-11-09-ai-clarification-design.md`

## Global Constraints

- One clarification round, hard cap - a `NeedsClarification` result (from the planner, or a second execution failure) on a retry attempt is treated exactly like today's terminal failure, never asked again.
- A hard `Failed` result (network/parse/request-building errors - no `reason` text available) is never a clarification opportunity - it keeps today's exact `text_agent_no_action` toast, immediately, no retry offered.
- The clarification answer is captured via the walkie-talkie's extended, pause-tolerant listening (`listenForInstruction(walkieTalkie = true)`), reused as-is - not a new listening mechanism.
- No TTS - the clarifying question is toast-only, consistent with this feature's existing no-speech-output posture.
- All existing mutual-exclusion, session-epoch-staleness, and addAction-only-on-confirmed-success guarantees apply identically to the retry attempt.

---

### Task 1: `PlanResult` and the updated planner

**Files:**
- Modify: `autojs/src/main/java/com/stardust/autojs/core/agent/ClaudeActionPlanner.kt`
- Modify: `autojs/src/test/kotlin/com/stardust/autojs/core/agent/ClaudeActionPlannerTest.kt`

**Interfaces:**
- Consumes: `SemanticAction`, `ActionType`, `MatchBy`, `ScrollDirection`, `Bounds`, `VisibleText`, `ScreenDescription` (all pre-existing, unchanged).
- Produces: `sealed class PlanResult { data class Action(val action: SemanticAction) : PlanResult(); data class NeedsClarification(val question: String) : PlanResult(); object Failed : PlanResult() }` and `ClaudeActionPlanner.plan(instruction, screen, apiKey): PlanResult` (changed from `SemanticAction?`) - Task 2 branches on this.

Grounded during planning: the full current `ClaudeActionPlannerTest.kt` (15 tests) was read in full - every test using `assertNull(...)`/`action?.type` needs updating to the new three-way result, not just the production code. The existing tests split cleanly: `action none` and `a target not present on screen` and both `a fill with ... value` tests currently return `null` but DO have a `reason` in their input - these become `NeedsClarification`. `a response with no tool_use block`, `a response whose content array contains non-object elements`, and `a response with invalid JSON` currently return `null` with NO reason available (the response never got that far) - these become `Failed`.

- [ ] **Step 1: Write the failing tests**

Replace the entire contents of `ClaudeActionPlannerTest.kt` with:

```kotlin
package com.stardust.autojs.core.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
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

    @Test
    fun `the reason field's description instructs Claude to ask a question when action is none`() {
        val body = ClaudeActionPlanner.buildRequestBody("tap login", screen)
        val reasonDescription = body.getJSONArray("tools").getJSONObject(0)
            .getJSONObject("input_schema").getJSONObject("properties")
            .getJSONObject("reason").getString("description")
        assertTrue(reasonDescription.contains("question"))
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

    private fun actionOf(result: PlanResult): SemanticAction = (result as PlanResult.Action).action

    @Test
    fun `click_text on a known target produces a matching CLICK_TEXT action`() {
        val response = toolUseResponse(
            JSONObject().put("action", "click_text").put("target", "Login").put("reason", "user asked to log in")
        )
        val action = actionOf(ClaudeActionPlanner.parseResponse(response, screen))
        assertEquals(ActionType.CLICK_TEXT, action.type)
        assertEquals("Login", action.targetText)
        assertEquals(MatchBy.TEXT, action.matchBy)
        assertEquals(Bounds(100, 200, 300, 260), action.bounds)
    }

    @Test
    fun `click_desc on a known target produces a DESC-matched CLICK_TEXT action`() {
        val response = toolUseResponse(
            JSONObject().put("action", "click_desc").put("target", "More options").put("reason", "open the menu")
        )
        val action = actionOf(ClaudeActionPlanner.parseResponse(response, screen))
        assertEquals(ActionType.CLICK_TEXT, action.type)
        assertEquals(MatchBy.DESC, action.matchBy)
    }

    @Test
    fun `fill_desc carries the value through`() {
        val response = toolUseResponse(
            JSONObject().put("action", "fill_desc").put("target", "Email").put("value", "a@b.com").put("reason", "fill the email field")
        )
        val action = actionOf(ClaudeActionPlanner.parseResponse(response, screen))
        assertEquals(ActionType.FILL_TEXT, action.type)
        assertEquals("a@b.com", action.fillValue)
        assertEquals(MatchBy.DESC, action.matchBy)
    }

    @Test
    fun `a fill with no value asks for clarification rather than blanking the field`() {
        // An empty-string fill would *succeed* at execution time - wiping the
        // target field - and then be saved as a successful step.
        val response = toolUseResponse(
            JSONObject().put("action", "fill_desc").put("target", "Email").put("reason", "fill the email field")
        )
        val result = ClaudeActionPlanner.parseResponse(response, screen)
        assertEquals(PlanResult.NeedsClarification("fill the email field"), result)
    }

    @Test
    fun `a fill with an explicitly empty value asks for clarification too`() {
        val response = toolUseResponse(
            JSONObject().put("action", "fill_text").put("target", "Login").put("value", "").put("reason", "fill it")
        )
        val result = ClaudeActionPlanner.parseResponse(response, screen)
        assertEquals(PlanResult.NeedsClarification("fill it"), result)
    }

    @Test
    fun `wait_for_text produces a WAIT action`() {
        val response = toolUseResponse(
            JSONObject().put("action", "wait_for_text").put("target", "Login").put("reason", "waiting for the page")
        )
        val action = actionOf(ClaudeActionPlanner.parseResponse(response, screen))
        assertEquals(ActionType.WAIT, action.type)
        assertEquals("Login", action.targetText)
    }

    @Test
    fun `long_click_text sets isLongClick`() {
        val response = toolUseResponse(
            JSONObject().put("action", "long_click_text").put("target", "Login").put("reason", "long press to select")
        )
        val action = actionOf(ClaudeActionPlanner.parseResponse(response, screen))
        assertEquals(ActionType.LONG_CLICK, action.type)
        assertTrue(action.isLongClick)
    }

    @Test
    fun `scroll uses the screen bounds, not a matched target`() {
        val response = toolUseResponse(
            JSONObject().put("action", "scroll").put("direction", "down").put("reason", "see more content")
        )
        val action = actionOf(ClaudeActionPlanner.parseResponse(response, screen))
        assertEquals(ActionType.SCROLL, action.type)
        assertEquals(ScrollDirection.DOWN, action.scrollDirection)
        assertEquals(screen.screenBounds, action.bounds)
    }

    @Test
    fun `action none asks for clarification using Claude's reason as the question`() {
        val response = toolUseResponse(
            JSONObject().put("action", "none").put("reason", "Which button do you mean - Submit or Cancel?")
        )
        val result = ClaudeActionPlanner.parseResponse(response, screen)
        assertEquals(PlanResult.NeedsClarification("Which button do you mean - Submit or Cancel?"), result)
    }

    @Test
    fun `a target not present on screen asks for clarification rather than a guessed bounds`() {
        val response = toolUseResponse(
            JSONObject().put("action", "click_text").put("target", "Nonexistent Button").put("reason", "guess")
        )
        val result = ClaudeActionPlanner.parseResponse(response, screen)
        assertEquals(PlanResult.NeedsClarification("guess"), result)
    }

    @Test
    fun `a response with no tool_use block fails rather than asking for clarification`() {
        val response = JSONObject().put("content", org.json.JSONArray()).toString()
        assertEquals(PlanResult.Failed, ClaudeActionPlanner.parseResponse(response, screen))
    }

    @Test
    fun `a response whose content array contains non-object elements fails gracefully`() {
        val response = JSONObject()
            .put("content", org.json.JSONArray().put("not an object").put(123).put(true))
            .toString()
        assertEquals(PlanResult.Failed, ClaudeActionPlanner.parseResponse(response, screen))
    }

    @Test
    fun `a response with invalid JSON fails gracefully`() {
        val invalidJson = "{ broken json without closing"
        assertEquals(PlanResult.Failed, ClaudeActionPlanner.parseResponse(invalidJson, screen))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :autojs:testDebugUnitTest --tests "com.stardust.autojs.core.agent.ClaudeActionPlannerTest"`
Expected: FAIL to compile - `PlanResult` does not exist yet, and `parseResponse`/`actionFromInput` still return `SemanticAction?`.

- [ ] **Step 3: Add the `PlanResult` sealed class**

In `ClaudeActionPlanner.kt`, add directly below the existing `ScreenDescription` data class (after line 14):

```kotlin
sealed class PlanResult {
    data class Action(val action: SemanticAction) : PlanResult()
    data class NeedsClarification(val question: String) : PlanResult()
    object Failed : PlanResult()
}
```

- [ ] **Step 4: Update `plan()`'s return type and failure paths**

Replace the existing `plan()` method:

```kotlin
    fun plan(instruction: String, screen: ScreenDescription, apiKey: String): SemanticAction? {
        return try {
            // Request building is inside the try on purpose: addHeader()
            // throws IllegalArgumentException on an api key containing
            // whitespace or any non-printable-ASCII character, and that must
            // be reported as "no action" like any other failure rather than
            // propagate out of this method.
            val request = Request.Builder()
                .url(API_URL)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", API_VERSION)
                .addHeader("content-type", "application/json")
                .post(buildRequestBody(instruction, screen).toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                parseResponse(body, screen)
            }
        } catch (e: Exception) {
            // Deliberately broader than IOException: building the request can
            // fail too - addHeader() throws IllegalArgumentException on a key
            // with whitespace or non-printable-ASCII characters in it - and
            // this method's contract is to return null on any failure rather
            // than propagate to a caller running on a background thread where
            // the global crash handler would swallow it.
            null
        }
    }
```

with:

```kotlin
    fun plan(instruction: String, screen: ScreenDescription, apiKey: String): PlanResult {
        return try {
            // Request building is inside the try on purpose: addHeader()
            // throws IllegalArgumentException on an api key containing
            // whitespace or any non-printable-ASCII character, and that must
            // be reported as Failed like any other failure rather than
            // propagate out of this method.
            val request = Request.Builder()
                .url(API_URL)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", API_VERSION)
                .addHeader("content-type", "application/json")
                .post(buildRequestBody(instruction, screen).toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return PlanResult.Failed
                val body = response.body?.string() ?: return PlanResult.Failed
                parseResponse(body, screen)
            }
        } catch (e: Exception) {
            // Deliberately broader than IOException: building the request can
            // fail too - addHeader() throws IllegalArgumentException on a key
            // with whitespace or non-printable-ASCII characters in it - and
            // this method's contract is to return Failed on any failure rather
            // than propagate to a caller running on a background thread where
            // the global crash handler would swallow it.
            PlanResult.Failed
        }
    }
```

Also update the method's doc comment directly above it:

```kotlin
    /**
     * One Claude call: given what's currently visible and one spoken
     * instruction, returns the action Claude chose, a clarifying question if
     * Claude couldn't determine one but explained why, or Failed if the
     * request couldn't even be built (a malformed api key), the HTTP call
     * failed, or the response didn't match the expected shape. Never throws.
     * Blocking - call off the main thread.
     */
```

- [ ] **Step 5: Update `parseResponse()` and `actionFromInput()`**

Replace the existing `parseResponse()` method:

```kotlin
        /** Pure, independently testable: Claude's response JSON -> one SemanticAction, or null. */
        fun parseResponse(responseBody: String, screen: ScreenDescription): SemanticAction? {
            return try {
                val content = JSONObject(responseBody).optJSONArray("content") ?: return null
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i) ?: continue
                    if (block.optString("type") != "tool_use") continue
                    val input = block.optJSONObject("input") ?: return null
                    return actionFromInput(input, screen)
                }
                null
            } catch (e: JSONException) {
                null
            }
        }
```

with:

```kotlin
        /** Pure, independently testable: Claude's response JSON -> a PlanResult. */
        fun parseResponse(responseBody: String, screen: ScreenDescription): PlanResult {
            return try {
                val content = JSONObject(responseBody).optJSONArray("content") ?: return PlanResult.Failed
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i) ?: continue
                    if (block.optString("type") != "tool_use") continue
                    val input = block.optJSONObject("input") ?: return PlanResult.Failed
                    return actionFromInput(input, screen)
                }
                PlanResult.Failed
            } catch (e: JSONException) {
                PlanResult.Failed
            }
        }
```

Replace the existing `actionFromInput()` method:

```kotlin
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
                "scroll" -> scrollDirectionFrom(input)?.let {
                    SemanticAction(ActionType.SCROLL, screen.screenBounds, scrollDirection = it)
                }
                else -> null
            }
        }
```

with:

```kotlin
        private fun actionFromInput(input: JSONObject, screen: ScreenDescription): PlanResult {
            val action = input.optString("action")
            val reason = input.optString("reason")
                .ifBlank { "I couldn't quite work that out - could you clarify?" }
            if (action.isEmpty() || action == "none") return PlanResult.NeedsClarification(reason)
            val targetName = input.optString("target").takeIf { it.isNotBlank() }
            val matched = targetName?.let { name -> screen.visibleText.firstOrNull { it.text == name } }
            val semanticAction = when (action) {
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
                "scroll" -> scrollDirectionFrom(input)?.let {
                    SemanticAction(ActionType.SCROLL, screen.screenBounds, scrollDirection = it)
                }
                else -> null
            }
            return semanticAction?.let { PlanResult.Action(it) } ?: PlanResult.NeedsClarification(reason)
        }
```

- [ ] **Step 6: Update the tool schema's `reason` field description**

In `buildInputSchema()`, replace:

```kotlin
                .put(
                    "reason", JSONObject().put("type", "string")
                        .put("description", "One sentence: why this action, or why none could be determined.")
                )
```

with:

```kotlin
                .put(
                    "reason", JSONObject().put("type", "string")
                        .put(
                            "description",
                            "If action is \"none\": a short, direct question to ask the user to get the detail you're missing (e.g. \"Which button do you mean - the blue 'Submit' or the gray 'Cancel'?\"), not an internal explanation. Otherwise: one sentence explaining why you chose this action."
                        )
                )
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :autojs:testDebugUnitTest --tests "com.stardust.autojs.core.agent.ClaudeActionPlannerTest"`
Expected: PASS (all 16 tests).

- [ ] **Step 8: Commit**

```bash
git add autojs/src/main/java/com/stardust/autojs/core/agent/ClaudeActionPlanner.kt autojs/src/test/kotlin/com/stardust/autojs/core/agent/ClaudeActionPlannerTest.kt
git commit -m "feat(agent): return a 3-way PlanResult so a clarifying question can be told apart from a hard failure"
```

---

### Task 2: One clarification round in `AgentBubble`

**Files:**
- Modify: `app/src/main/java/org/autojs/autojs/ui/agent/AgentBubble.kt`
- Modify: `app/src/main/res-i18n/values-en/strings.xml`
- Modify: `app/src/main/res-i18n/values/strings.xml`

**Interfaces:**
- Consumes: `ClaudeActionPlanner.PlanResult`/`.Action`/`.NeedsClarification`/`.Failed` (Task 1).
- Produces: no new public API - purely new private behavior on the existing `AgentBubble` class.

No isolated unit test (real `SpeechRecognizer`/network-dependent control flow) - verified by compile plus Task 3's on-device check.

- [ ] **Step 1: Add the new strings**

In `app/src/main/res-i18n/values-en/strings.xml`, add directly below the existing `text_agent_target_not_found` line:

```xml
  <string name="text_agent_clarify_target_not_found">Couldn\'t find \"%1$s\" — can you describe it differently?</string>
```

In `app/src/main/res-i18n/values/strings.xml`, add directly below the existing `text_agent_target_not_found` line:

```xml
    <string name="text_agent_clarify_target_not_found">未找到"%1$s"——能换种方式描述一下吗？</string>
```

- [ ] **Step 2: Give `listenForInstruction` a pluggable continuation**

Replace the signature and body of `listenForInstruction` (the `onResults` branch specifically) - find:

```kotlin
    private fun listenForInstruction(walkieTalkie: Boolean = false) {
        AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_listening))
        val recognizer = SpeechRecognizer.createSpeechRecognizer(mContext).also { mSpeechRecognizer = it }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                mWalkieTalkieHandler.removeCallbacks(mWalkieTalkieSafetyStopRunnable)
                recognizer.destroy()
                mSpeechRecognizer = null
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) {
                    AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_speech))
                } else {
                    runVoiceTurn(text)
                }
            }
```

and replace just the signature line and the `onResults` body's final branch:

```kotlin
    private fun listenForInstruction(walkieTalkie: Boolean = false, onTranscribed: (String) -> Unit = { runVoiceTurn(it) }) {
        AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_listening))
        val recognizer = SpeechRecognizer.createSpeechRecognizer(mContext).also { mSpeechRecognizer = it }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                mWalkieTalkieHandler.removeCallbacks(mWalkieTalkieSafetyStopRunnable)
                recognizer.destroy()
                mSpeechRecognizer = null
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) {
                    AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_speech))
                } else {
                    onTranscribed(text)
                }
            }
```

(The rest of `listenForInstruction` - `onError`, the other `RecognitionListener` overrides, the `Intent` construction, `startListening`, and the walkie-talkie safety-timer scheduling - is unchanged.)

- [ ] **Step 3: Restructure `runVoiceTurn` to branch on `PlanResult` and support one retry**

Replace the entire existing `runVoiceTurn` method:

```kotlin
    private fun runVoiceTurn(instruction: String) {
        AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_thinking))
        // Captured on the main thread, before this turn is handed off - see
        // mVoiceSessionEpoch's doc comment. Stop (or a fresh Talk tap) can
        // end/replace this session while this turn is still describing,
        // planning, or executing on the background thread below.
        val sessionEpoch = mVoiceSessionEpoch
        voiceTurnExecutor.execute {
            // Nothing below may be allowed to throw out of this lambda. App
            // installs a global CrashHandler that catches uncaught
            // background-thread exceptions and uploads them to a remote crash
            // service with no toast or dialog, so an unguarded failure here
            // would make the turn vanish behind a "Thinking..." toast that
            // never resolves - and could carry the API key off-device inside
            // a stack trace. The most reachable trigger is OkHttp's
            // addHeader(), which throws IllegalArgumentException on a key with
            // a stray newline or any non-printable-ASCII character.
            try {
                // This turn may have been queued behind a slower one while the
                // user tapped Stop. Bail before doing any describe / plan /
                // execute work at all - see mVoiceSessionEpoch's doc comment.
                if (mVoiceSessionEpoch != sessionEpoch) return@execute
                val service = AccessibilityService.instance
                if (service == null) {
                    AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_action))
                    return@execute
                }
                val screen = mScreenStateReader.describeScreen(service)
                val action = mPlanner.plan(instruction, screen, Pref.getClaudeApiKey())
                if (action == null) {
                    AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_action))
                    return@execute
                }
                // Describing the screen and the Claude round trip both take
                // real time. Re-check before physically touching the screen,
                // so a Stop during that window doesn't still perform an action.
                if (mVoiceSessionEpoch != sessionEpoch) return@execute
                val succeeded = executeAction(action)
                if (mVoiceSessionEpoch != sessionEpoch) {
                    // The session this turn belongs to was stopped, or a new one
                    // started, while this turn was still in flight. Its result
                    // is stale and must not be folded into whatever session is
                    // live now - drop it silently rather than calling
                    // addAction() or reporting on it.
                    return@execute
                }
                if (succeeded) {
                    mVoiceSession.addAction(action)
                    AutoJs.getInstance().uiHandler.toast(
                        mContext.getString(R.string.text_agent_action_performed, instruction)
                    )
                } else {
                    val label = action.targetText ?: instruction
                    AutoJs.getInstance().uiHandler.toast(
                        mContext.getString(R.string.text_agent_target_not_found, label)
                    )
                }
            } catch (e: InterruptedException) {
                // close() interrupted this turn via shutdownNow(). The bubble
                // is going away, so there is no longer anyone to report to -
                // toasting here would surface a failure after the UI is gone.
            } catch (e: Exception) {
                AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_action))
            }
        }
    }
```

with:

```kotlin
    /**
     * [isRetry] caps clarification at exactly one round: a true turn is
     * itself the retry after the user answered a clarifying question, so a
     * further NeedsClarification (from the planner, or a second execution
     * failure) is reported as today's terminal failure instead of asking
     * again - see [askForClarification].
     */
    private fun runVoiceTurn(instruction: String, isRetry: Boolean = false) {
        AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_thinking))
        // Captured on the main thread, before this turn is handed off - see
        // mVoiceSessionEpoch's doc comment. Stop (or a fresh Talk tap) can
        // end/replace this session while this turn is still describing,
        // planning, or executing on the background thread below.
        val sessionEpoch = mVoiceSessionEpoch
        voiceTurnExecutor.execute {
            // Nothing below may be allowed to throw out of this lambda. App
            // installs a global CrashHandler that catches uncaught
            // background-thread exceptions and uploads them to a remote crash
            // service with no toast or dialog, so an unguarded failure here
            // would make the turn vanish behind a "Thinking..." toast that
            // never resolves - and could carry the API key off-device inside
            // a stack trace. The most reachable trigger is OkHttp's
            // addHeader(), which throws IllegalArgumentException on a key with
            // a stray newline or any non-printable-ASCII character.
            try {
                // This turn may have been queued behind a slower one while the
                // user tapped Stop. Bail before doing any describe / plan /
                // execute work at all - see mVoiceSessionEpoch's doc comment.
                if (mVoiceSessionEpoch != sessionEpoch) return@execute
                val service = AccessibilityService.instance
                if (service == null) {
                    AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_action))
                    return@execute
                }
                val screen = mScreenStateReader.describeScreen(service)
                when (val result = mPlanner.plan(instruction, screen, Pref.getClaudeApiKey())) {
                    is PlanResult.Failed -> {
                        AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_action))
                    }
                    is PlanResult.NeedsClarification -> {
                        if (isRetry) {
                            AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_action))
                        } else {
                            askForClarification(instruction, result.question, sessionEpoch)
                        }
                    }
                    is PlanResult.Action -> {
                        // Describing the screen and the Claude round trip both
                        // take real time. Re-check before physically touching
                        // the screen, so a Stop during that window doesn't
                        // still perform an action.
                        if (mVoiceSessionEpoch != sessionEpoch) return@execute
                        val succeeded = executeAction(result.action)
                        if (mVoiceSessionEpoch != sessionEpoch) {
                            // The session this turn belongs to was stopped, or a
                            // new one started, while this turn was still in
                            // flight. Its result is stale and must not be folded
                            // into whatever session is live now - drop it
                            // silently rather than calling addAction() or
                            // reporting/asking about it.
                            return@execute
                        }
                        if (succeeded) {
                            mVoiceSession.addAction(result.action)
                            AutoJs.getInstance().uiHandler.toast(
                                mContext.getString(R.string.text_agent_action_performed, instruction)
                            )
                        } else if (isRetry) {
                            val label = result.action.targetText ?: instruction
                            AutoJs.getInstance().uiHandler.toast(
                                mContext.getString(R.string.text_agent_target_not_found, label)
                            )
                        } else {
                            val label = result.action.targetText ?: instruction
                            askForClarification(
                                instruction,
                                mContext.getString(R.string.text_agent_clarify_target_not_found, label),
                                sessionEpoch
                            )
                        }
                    }
                }
            } catch (e: InterruptedException) {
                // close() interrupted this turn via shutdownNow(). The bubble
                // is going away, so there is no longer anyone to report to -
                // toasting here would surface a failure after the UI is gone.
            } catch (e: Exception) {
                AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_action))
            }
        }
    }

    /**
     * Shows [question], then listens for one spoken answer and retries the
     * turn with it folded into the original instruction - at most once, see
     * [runVoiceTurn]'s [isRetry] parameter. Called from the background
     * [voiceTurnExecutor] thread; hops to the main thread itself since
     * [listenForInstruction] requires it.
     */
    private fun askForClarification(originalInstruction: String, question: String, sessionEpoch: Int) {
        AutoJs.getInstance().uiHandler.toast(question)
        mWalkieTalkieHandler.post {
            if (mVoiceSessionEpoch != sessionEpoch) return@post
            if (mSpeechRecognizer != null) return@post
            listenForInstruction(walkieTalkie = true) { answer ->
                runVoiceTurn("$originalInstruction ($answer)", isRetry = true)
            }
        }
    }
```

- [ ] **Step 4: Add the `PlanResult` import**

`PlanResult` is a top-level sealed class in `ClaudeActionPlanner.kt` (defined alongside `VisibleText`/`ScreenDescription`, not nested inside the `ClaudeActionPlanner` class or its companion) - the `when` block in Step 3 references it unqualified, so it needs its own import. Add to `AgentBubble.kt`'s existing imports, alongside `import com.stardust.autojs.core.agent.ClaudeActionPlanner`:

```kotlin
import com.stardust.autojs.core.agent.PlanResult
```

- [ ] **Step 5: Compile to verify**

Run: `./gradlew :app:compileCommonDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/autojs/autojs/ui/agent/AgentBubble.kt app/src/main/res-i18n/values-en/strings.xml app/src/main/res-i18n/values/strings.xml
git commit -m "feat(agent): ask one clarifying question before giving up on an unclear or failed instruction"
```

---

### Task 3: Full build verification and manual on-device check

**Files:** none (verification only).

- [ ] **Step 1: Run the full unit test suite**

Run: `./gradlew :autojs:testDebugUnitTest :app:testCommonDebugUnitTest`
Expected: BUILD SUCCESSFUL - all 16 `ClaudeActionPlannerTest` tests (the rewritten 15 plus the new tool-schema-description test) passing alongside every other existing test.

- [ ] **Step 2: Assemble the debug APK**

Run: `./gradlew :app:assembleCommonDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual on-device verification**

With a Claude API key configured:

1. Speak an instruction that's genuinely ambiguous on the current screen (e.g. two visibly similar buttons, "tap the button"). Confirm you get a specific clarifying question (not the old generic "couldn't work out what to do") and that it starts listening again automatically afterward.
2. Answer the clarifying question. Confirm the combined instruction is retried and either performs the right action or reports a normal failure - not a second question.
3. Speak an instruction naming something that sounds plausible but isn't actually on screen (so the generated script's target-not-found path is reached). Confirm you get the "couldn't find X - can you describe it differently?" clarification, not the old plain "couldn't find X" toast.
4. Repeat step 3 but, after the clarifying question, give an answer that's still wrong/unresolvable. Confirm this time you get a terminal failure toast - no third question.
5. Disable Wi-Fi/mobile data to force a hard network failure, then speak any instruction. Confirm you get the plain "couldn't work out what to do" toast immediately, no clarifying question offered for this case.
6. Tap Stop while a clarifying question's follow-up listening is active. Confirm nothing further happens - no stray action performed, no crash.

If any check fails, return to the relevant task, fix the issue, re-run its build step, and re-check here before considering this plan complete.
