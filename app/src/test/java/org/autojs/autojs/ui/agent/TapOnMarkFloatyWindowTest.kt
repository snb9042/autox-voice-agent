package org.autojs.autojs.ui.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapOnMarkFloatyWindowTest {

    @Test
    fun `matches both phrasings of the callsign, case-insensitively and trimmed`() {
        assertTrue(TapOnMarkFloatyWindow.matchesCallsign("tap on mark"))
        assertTrue(TapOnMarkFloatyWindow.matchesCallsign("Tap On Mark"))
        assertTrue(TapOnMarkFloatyWindow.matchesCallsign("  tap the mark  "))
        assertTrue(TapOnMarkFloatyWindow.matchesCallsign("TAP THE MARK"))
    }

    @Test
    fun `does not match an unrelated or merely similar instruction`() {
        assertFalse(TapOnMarkFloatyWindow.matchesCallsign("tap on Login"))
        assertFalse(TapOnMarkFloatyWindow.matchesCallsign("tap on the mark button"))
        assertFalse(TapOnMarkFloatyWindow.matchesCallsign(""))
    }
}
