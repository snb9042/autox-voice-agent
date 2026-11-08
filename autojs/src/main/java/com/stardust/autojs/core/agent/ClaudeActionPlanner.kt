package com.stardust.autojs.core.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
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
