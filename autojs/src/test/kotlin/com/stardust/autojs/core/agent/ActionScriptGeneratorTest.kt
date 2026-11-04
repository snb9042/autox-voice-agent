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
