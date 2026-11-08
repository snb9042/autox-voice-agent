package com.stardust.autojs.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionScriptGeneratorTest {

    private val bounds = Bounds(10, 20, 110, 60)

    /** A single backslash, so expected JS escape sequences stay readable below. */
    private val bs = "\\"

    @Test
    fun `prelude waits for the accessibility service before anything else`() {
        val script = ActionScriptGenerator.generate(listOf(SemanticAction(ActionType.CLICK_BOUNDS, bounds)))
        assertTrue(script.trimStart().startsWith("auto.waitFor();"))
    }

    @Test
    fun `CLICK_TEXT generates a text-polling click step`() {
        val script = ActionScriptGenerator.generate(
            listOf(SemanticAction(ActionType.CLICK_TEXT, bounds, targetText = "Login", matchBy = MatchBy.TEXT))
        )
        assertTrue(script.contains("__findTextNear(\"Login\""))
        assertTrue(script.contains("if (!step0.click()) throw Error("))
    }

    @Test
    fun `CLICK_TEXT with a desc-sourced label uses the desc finder`() {
        val script = ActionScriptGenerator.generate(
            listOf(SemanticAction(ActionType.CLICK_TEXT, bounds, targetText = "More options", matchBy = MatchBy.DESC))
        )
        assertTrue(script.contains("__findDescNear(\"More options\""))
        assertFalse(script.contains("__findTextNear(\"More options\""))
        // The desc finder in the prelude must select on desc(), not text().
        assertTrue(script.contains("desc(targetText).find()"))
    }

    @Test
    fun `LONG_CLICK generates a longClick call`() {
        val script = ActionScriptGenerator.generate(
            listOf(SemanticAction(ActionType.LONG_CLICK, bounds, targetText = "Menu", matchBy = MatchBy.TEXT))
        )
        assertTrue(script.contains("__findTextNear(\"Menu\""))
        assertTrue(script.contains("if (!step0.longClick()) throw Error("))
    }

    @Test
    fun `FILL_TEXT with a label uses text matching and escapes quotes`() {
        val script = ActionScriptGenerator.generate(
            listOf(
                SemanticAction(
                    ActionType.FILL_TEXT,
                    bounds,
                    targetText = "Email",
                    fillValue = "a\"b",
                    matchBy = MatchBy.TEXT
                )
            )
        )
        assertTrue(script.contains("__findTextNear(\"Email\""))
        assertTrue(script.contains("if (!step0.setText(\"a\\\"b\")) throw Error("))
    }

    @Test
    fun `FILL_TEXT with a desc-sourced label uses the desc finder`() {
        val script = ActionScriptGenerator.generate(
            listOf(
                SemanticAction(
                    ActionType.FILL_TEXT,
                    bounds,
                    targetText = "Search",
                    fillValue = "cats",
                    matchBy = MatchBy.DESC
                )
            )
        )
        assertTrue(script.contains("__findDescNear(\"Search\""))
        assertFalse(script.contains("__findTextNear(\"Search\""))
    }

    @Test
    fun `FILL_TEXT without a label falls back to tolerant bounds matching`() {
        val script = ActionScriptGenerator.generate(
            listOf(SemanticAction(ActionType.FILL_TEXT, bounds, fillValue = "value"))
        )
        assertTrue(script.contains("__findBounds(10, 20, 110, 60"))
        assertTrue(script.contains("if (!step0.setText(\"value\")) throw Error("))
        // The bounds finder must tolerate small layout shifts rather than
        // requiring a pixel-exact rect match.
        assertTrue(script.contains("editable(true).boundsInside(l - m, t - m, r + m, b + m)"))
    }

    @Test
    fun `escapes newlines tabs and quotes in labels and values`() {
        val script = ActionScriptGenerator.generate(
            listOf(
                SemanticAction(
                    ActionType.FILL_TEXT,
                    bounds,
                    targetText = "Line1\nLine2",
                    fillValue = "a\r\nb\tc",
                    matchBy = MatchBy.TEXT
                )
            )
        )
        assertTrue(script.contains("__findTextNear(\"Line1${bs}nLine2\""))
        assertTrue(script.contains("setText(\"a${bs}r${bs}nb${bs}tc\")"))
        // A raw newline would terminate the comment early, so the comment line
        // must carry the escaped form too.
        val commentLine = script.lines().first { it.startsWith("// Step 0") }
        assertTrue(commentLine.contains("fill \"Line1${bs}nLine2\""))
    }

    @Test
    fun `escapes JS line and paragraph separators`() {
        val lineSep = 0x2028.toChar()
        val paraSep = 0x2029.toChar()
        val script = ActionScriptGenerator.generate(
            listOf(SemanticAction(ActionType.FILL_TEXT, bounds, fillValue = "a${lineSep}b${paraSep}c"))
        )
        assertTrue(script.contains("setText(\"a${bs}u2028b${bs}u2029c\")"))
        assertFalse(script.contains(lineSep))
        assertFalse(script.contains(paraSep))
    }

    @Test
    fun `CLICK_IMAGE generates a polling image match and click`() {
        val script = ActionScriptGenerator.generate(
            listOf(SemanticAction(ActionType.CLICK_IMAGE, bounds, imageTemplatePath = "template0.png"))
        )
        assertTrue(script.contains("images.read(\"template0.png\")"))
        assertTrue(script.contains("images.findImage("))
        assertTrue(script.contains("if (!click(step0Point.x, step0Point.y)) throw Error("))
    }

    @Test
    fun `CLICK_IMAGE requests screen capture permission exactly once up front`() {
        val script = ActionScriptGenerator.generate(
            listOf(
                SemanticAction(ActionType.CLICK_IMAGE, bounds, imageTemplatePath = "a.png"),
                SemanticAction(ActionType.CLICK_IMAGE, bounds, imageTemplatePath = "b.png")
            )
        )
        val guard = "if (!images.requestScreenCapture()) throw Error("
        assertEquals(1, script.occurrencesOf(guard))
        // The guard must precede the first capture attempt.
        assertTrue(script.indexOf(guard) < script.indexOf("images.captureScreen()"))
    }

    @Test
    fun `no screen capture request when no image steps exist`() {
        val script = ActionScriptGenerator.generate(
            listOf(SemanticAction(ActionType.CLICK_TEXT, bounds, targetText = "Login", matchBy = MatchBy.TEXT))
        )
        assertFalse(script.contains("images.requestScreenCapture()"))
    }

    @Test
    fun `CLICK_IMAGE honours a recorded long press`() {
        val script = ActionScriptGenerator.generate(
            listOf(SemanticAction(ActionType.CLICK_IMAGE, bounds, imageTemplatePath = "t.png", isLongClick = true))
        )
        assertTrue(script.contains("if (!longClick(step0Point.x, step0Point.y)) throw Error("))
    }

    @Test
    fun `CLICK_BOUNDS generates a fallback coordinate click with a warning comment`() {
        val script = ActionScriptGenerator.generate(listOf(SemanticAction(ActionType.CLICK_BOUNDS, bounds)))
        assertTrue(script.contains("fallback"))
        assertTrue(script.contains("if (!click(60, 40)) throw Error("))
    }

    @Test
    fun `CLICK_BOUNDS honours a recorded long press`() {
        val script = ActionScriptGenerator.generate(
            listOf(SemanticAction(ActionType.CLICK_BOUNDS, bounds, isLongClick = true))
        )
        assertTrue(script.contains("if (!longClick(60, 40)) throw Error("))
    }

    @Test
    fun `SCROLL DOWN swipes from the bottom of the control toward the top`() {
        val script = ActionScriptGenerator.generate(
            listOf(SemanticAction(ActionType.SCROLL, bounds, scrollDirection = ScrollDirection.DOWN))
        )
        assertTrue(script.contains("swipe(60, 60, 60, 20, 300)"))
    }

    @Test
    fun `SCROLL UP swipes from the top of the control toward the bottom`() {
        val script = ActionScriptGenerator.generate(
            listOf(SemanticAction(ActionType.SCROLL, bounds, scrollDirection = ScrollDirection.UP))
        )
        assertTrue(script.contains("swipe(60, 20, 60, 60, 300)"))
    }

    @Test
    fun `SCROLL RIGHT swipes from the right edge toward the left`() {
        val script = ActionScriptGenerator.generate(
            listOf(SemanticAction(ActionType.SCROLL, bounds, scrollDirection = ScrollDirection.RIGHT))
        )
        assertTrue(script.contains("swipe(110, 40, 10, 40, 300)"))
    }

    @Test
    fun `SCROLL LEFT swipes from the left edge toward the right`() {
        val script = ActionScriptGenerator.generate(
            listOf(SemanticAction(ActionType.SCROLL, bounds, scrollDirection = ScrollDirection.LEFT))
        )
        assertTrue(script.contains("swipe(10, 40, 110, 40, 300)"))
    }

    @Test
    fun `missing required field throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            ActionScriptGenerator.generate(listOf(SemanticAction(ActionType.CLICK_TEXT, bounds)))
        }
    }

    private fun String.occurrencesOf(needle: String): Int {
        var count = 0
        var from = indexOf(needle)
        while (from >= 0) {
            count++
            from = indexOf(needle, from + needle.length)
        }
        return count
    }
}
