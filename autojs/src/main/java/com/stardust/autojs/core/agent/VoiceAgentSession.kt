package com.stardust.autojs.core.agent

import java.util.concurrent.CopyOnWriteArrayList

class VoiceAgentSession {

    enum class State { IDLE, ACTIVE }

    @Volatile
    var state: State = State.IDLE
        private set

    private val actions = CopyOnWriteArrayList<SemanticAction>()

    fun start() {
        actions.clear()
        state = State.ACTIVE
    }

    /**
     * Appends a confirmed-successful action to this session. Silently does
     * nothing unless the session is [State.ACTIVE].
     *
     * A voice turn's work runs on a background thread and can still resolve
     * after the user has tapped Stop. The caller guards against that with its
     * own session-identity check, but that check lives outside this class;
     * this is an independent safety net ensuring a stopped session can never
     * be appended to no matter who calls, so the list [stop] already returned
     * cannot grow behind the caller's back.
     */
    fun addAction(action: SemanticAction) {
        if (state != State.ACTIVE) return
        actions.add(action)
    }

    fun stop(): List<SemanticAction> {
        state = State.IDLE
        return actions.toList()
    }
}
