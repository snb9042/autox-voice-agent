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
