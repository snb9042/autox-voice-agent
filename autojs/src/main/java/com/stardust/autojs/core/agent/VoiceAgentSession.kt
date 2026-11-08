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

    fun addAction(action: SemanticAction) {
        actions.add(action)
    }

    fun stop(): List<SemanticAction> {
        state = State.IDLE
        return actions.toList()
    }
}
