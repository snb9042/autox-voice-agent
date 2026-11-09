package com.stardust.autojs.core.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class VisibleText(val text: String, val matchBy: MatchBy, val bounds: Bounds, val isOcrSourced: Boolean = false)

data class ScreenDescription(val screenBounds: Bounds, val visibleText: List<VisibleText>)

sealed class PlanResult {
    data class Action(val action: SemanticAction) : PlanResult()
    data class NeedsClarification(val question: String) : PlanResult()
    object Failed : PlanResult()
}

class ClaudeActionPlanner(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    /**
     * One Claude call: given what's currently visible and one spoken
     * instruction, returns the action Claude chose, a clarifying question if
     * Claude couldn't determine one but explained why, or Failed if the
     * request couldn't even be built (a malformed api key), the HTTP call
     * failed, or the response didn't match the expected shape. Never throws.
     * Blocking - call off the main thread.
     */
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
                        .put(
                            "description",
                            "If action is \"none\": a short, direct question to ask the user to get the detail you're missing (e.g. \"Which button do you mean - the blue 'Submit' or the gray 'Cancel'?\"), not an internal explanation. Otherwise: one sentence explaining why you chose this action."
                        )
                )
            return JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", JSONArray(listOf("action", "reason")))
        }

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

        private fun actionFromInput(input: JSONObject, screen: ScreenDescription): PlanResult {
            val action = input.optString("action")
            val reason = input.optString("reason")
                .ifBlank { "I couldn't quite work that out - could you clarify?" }
            if (action.isEmpty() || action == "none") return PlanResult.NeedsClarification(reason)
            val targetName = input.optString("target").takeIf { it.isNotBlank() }
            val matched = targetName?.let { name -> screen.visibleText.firstOrNull { it.text == name } }
            val semanticAction = when (action) {
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
                "scroll" -> scrollDirectionFrom(input)?.let {
                    SemanticAction(ActionType.SCROLL, screen.screenBounds, scrollDirection = it)
                }
                else -> null
            }
            return semanticAction?.let { PlanResult.Action(it) } ?: PlanResult.NeedsClarification(reason)
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
