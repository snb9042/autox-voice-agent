package com.stardust.autojs.core.agent

enum class ActionType { CLICK_TEXT, CLICK_IMAGE, CLICK_BOUNDS, LONG_CLICK, FILL_TEXT, SCROLL }

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/**
 * Which node attribute [SemanticAction.targetText] was read from. This matters
 * at script-generation time because Auto.js' `text(...)` selector only matches
 * [android.view.accessibility.AccessibilityNodeInfo.getText], never
 * `getContentDescription()` - a label sourced from a content description has to
 * be looked up with `desc(...)` instead.
 */
enum class MatchBy { TEXT, DESC }

data class SemanticAction(
    val type: ActionType,
    val bounds: Bounds,
    val targetText: String? = null,
    val imageTemplatePath: String? = null,
    val fillValue: String? = null,
    val scrollDirection: ScrollDirection? = null,
    /**
     * Null for actions that don't use a text-based match (CLICK_IMAGE,
     * CLICK_BOUNDS, SCROLL, and FILL_TEXT when falling back to bounds because
     * no usable label exists).
     */
    val matchBy: MatchBy? = null,
    /**
     * Set when the recorded gesture was a long press. [ActionType.LONG_CLICK]
     * already implies this for labeled targets; this flag carries the intent
     * for the image/bounds fallback types, which would otherwise silently
     * downgrade to a plain tap.
     */
    val isLongClick: Boolean = false
)
