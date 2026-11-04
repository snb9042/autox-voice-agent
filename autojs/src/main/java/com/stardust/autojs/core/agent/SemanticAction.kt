package com.stardust.autojs.core.agent

enum class ActionType { CLICK_TEXT, CLICK_IMAGE, CLICK_BOUNDS, LONG_CLICK, FILL_TEXT, SCROLL }

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

data class SemanticAction(
    val type: ActionType,
    val bounds: Bounds,
    val targetText: String? = null,
    val imageTemplatePath: String? = null,
    val fillValue: String? = null,
    val scrollDirection: ScrollDirection? = null
)
