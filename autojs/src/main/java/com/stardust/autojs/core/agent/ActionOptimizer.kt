package com.stardust.autojs.core.agent

object ActionOptimizer {

    fun optimize(actions: List<SemanticAction>): List<SemanticAction> {
        return mergeConsecutiveScrolls(collapseRepeatedFillText(actions))
    }

    private fun collapseRepeatedFillText(actions: List<SemanticAction>): List<SemanticAction> {
        val result = mutableListOf<SemanticAction>()
        for (action in actions) {
            val last = result.lastOrNull()
            if (action.type == ActionType.FILL_TEXT && last?.type == ActionType.FILL_TEXT && last.bounds == action.bounds) {
                result[result.lastIndex] = action
            } else {
                result.add(action)
            }
        }
        return result
    }

    private fun mergeConsecutiveScrolls(actions: List<SemanticAction>): List<SemanticAction> {
        val result = mutableListOf<SemanticAction>()
        for (action in actions) {
            val last = result.lastOrNull()
            val isDuplicateScroll = action.type == ActionType.SCROLL &&
                last?.type == ActionType.SCROLL &&
                last.scrollDirection == action.scrollDirection
            if (!isDuplicateScroll) {
                result.add(action)
            }
        }
        return result
    }
}
