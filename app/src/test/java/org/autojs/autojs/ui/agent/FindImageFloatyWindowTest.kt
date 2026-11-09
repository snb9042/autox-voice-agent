package org.autojs.autojs.ui.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FindImageFloatyWindowTest {

    @Test
    fun `matches the callsign, case-insensitively and trimmed`() {
        assertTrue(FindImageFloatyWindow.matchesCallsign("find image"))
        assertTrue(FindImageFloatyWindow.matchesCallsign("Find Image"))
        assertTrue(FindImageFloatyWindow.matchesCallsign("  FIND IMAGE  "))
    }

    @Test
    fun `does not match an unrelated or merely similar instruction`() {
        assertFalse(FindImageFloatyWindow.matchesCallsign("find the Login button"))
        assertFalse(FindImageFloatyWindow.matchesCallsign("find an image of a cat"))
        assertFalse(FindImageFloatyWindow.matchesCallsign(""))
    }
}
