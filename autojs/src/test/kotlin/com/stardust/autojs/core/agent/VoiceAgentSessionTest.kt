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
    fun `addAction is ignored while still idle`() {
        val session = VoiceAgentSession()
        session.addAction(action)
        // Not start()ed in between: start() clears, which would mask the
        // guard. Reading straight back out is what proves nothing landed.
        assertTrue(session.stop().isEmpty())
    }

    @Test
    fun `addAction is ignored after stop, so a stopped session cannot grow`() {
        // A voice turn's work runs on a background thread and can resolve
        // after the user has already tapped Stop; the list stop() handed back
        // must not gain a step behind the caller's back.
        val session = VoiceAgentSession()
        session.start()
        session.addAction(action)
        assertEquals(listOf(action), session.stop())
        session.addAction(action.copy(targetText = "Late"))
        assertEquals(listOf(action), session.stop())
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
