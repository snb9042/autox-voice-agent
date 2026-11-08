package com.stardust.autojs.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionOptimizerTest {

    private val fieldBounds = Bounds(0, 100, 200, 140)

    @Test
    fun `collapses repeated FILL_TEXT on the same field into the last value`() {
        val actions = listOf(
            SemanticAction(ActionType.FILL_TEXT, fieldBounds, targetText = "Email", matchBy = MatchBy.TEXT, fillValue = "a"),
            SemanticAction(ActionType.FILL_TEXT, fieldBounds, targetText = "Email", matchBy = MatchBy.TEXT, fillValue = "ab"),
            SemanticAction(ActionType.FILL_TEXT, fieldBounds, targetText = "Email", matchBy = MatchBy.TEXT, fillValue = "abc")
        )
        val result = ActionOptimizer.optimize(actions)
        assertEquals(1, result.size)
        assertEquals("abc", result[0].fillValue)
    }

    @Test
    fun `does not collapse FILL_TEXT on different fields`() {
        val otherBounds = Bounds(0, 200, 200, 240)
        val actions = listOf(
            SemanticAction(ActionType.FILL_TEXT, fieldBounds, targetText = "Email", matchBy = MatchBy.TEXT, fillValue = "a"),
            SemanticAction(ActionType.FILL_TEXT, otherBounds, targetText = "Password", matchBy = MatchBy.TEXT, fillValue = "b")
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
        val click = SemanticAction(ActionType.CLICK_TEXT, fieldBounds, targetText = "Login", matchBy = MatchBy.TEXT)
        val fill = SemanticAction(ActionType.FILL_TEXT, fieldBounds, targetText = "Email", matchBy = MatchBy.TEXT, fillValue = "a")
        val result = ActionOptimizer.optimize(listOf(click, fill))
        assertEquals(listOf(click, fill), result)
    }
}
