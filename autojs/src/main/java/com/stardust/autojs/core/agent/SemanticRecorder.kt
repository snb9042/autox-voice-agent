package com.stardust.autojs.core.agent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.stardust.view.accessibility.AccessibilityDelegate
import com.stardust.view.accessibility.AccessibilityNodeInfoHelper
import java.util.concurrent.CopyOnWriteArrayList

class SemanticRecorder(
    private val screenStateReader: ScreenStateReader = ScreenStateReader()
) : AccessibilityDelegate {

    enum class State { IDLE, RECORDING }

    override val eventTypes: Set<Int> = setOf(
        AccessibilityEvent.TYPE_VIEW_CLICKED,
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        AccessibilityEvent.TYPE_VIEW_SCROLLED
    )

    @Volatile
    var state: State = State.IDLE
        private set

    private val recordedActions = CopyOnWriteArrayList<SemanticAction>()

    fun start() {
        recordedActions.clear()
        state = State.RECORDING
    }

    fun stop(): List<SemanticAction> {
        state = State.IDLE
        return recordedActions.toList()
    }

    override fun onAccessibilityEvent(service: AccessibilityService, event: AccessibilityEvent): Boolean {
        if (state != State.RECORDING) return false
        val source = event.source ?: return false
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> recordClick(service, source, ActionType.CLICK_TEXT)
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> recordClick(service, source, ActionType.LONG_CLICK)
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> recordFillText(service, source, event)
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> recordScroll(source, event)
            }
        } finally {
            source.recycle()
        }
        return false
    }

    private fun recordClick(service: AccessibilityService, source: AccessibilityNodeInfo, clickType: ActionType) {
        val target = screenStateReader.describeClickTarget(service, source)
        val action = when {
            target.text != null -> SemanticAction(type = clickType, bounds = target.bounds, targetText = target.text)
            target.imageTemplatePath != null -> SemanticAction(
                type = ActionType.CLICK_IMAGE,
                bounds = target.bounds,
                imageTemplatePath = target.imageTemplatePath
            )
            else -> SemanticAction(type = ActionType.CLICK_BOUNDS, bounds = target.bounds)
        }
        recordedActions.add(action)
    }

    private fun recordFillText(service: AccessibilityService, source: AccessibilityNodeInfo, event: AccessibilityEvent) {
        val target = screenStateReader.describeEditableTarget(service, source)
        recordedActions.add(
            SemanticAction(
                type = ActionType.FILL_TEXT,
                bounds = target.bounds,
                targetText = target.text,
                fillValue = event.text?.joinToString("").orEmpty()
            )
        )
    }

    private fun recordScroll(source: AccessibilityNodeInfo, event: AccessibilityEvent) {
        val bounds = Bounds.fromRect(AccessibilityNodeInfoHelper.getBoundsInScreen(source))
        val direction = when {
            event.scrollDeltaY > 0 -> ScrollDirection.DOWN
            event.scrollDeltaY < 0 -> ScrollDirection.UP
            event.scrollDeltaX > 0 -> ScrollDirection.RIGHT
            else -> ScrollDirection.LEFT
        }
        recordedActions.add(SemanticAction(type = ActionType.SCROLL, bounds = bounds, scrollDirection = direction))
    }
}
