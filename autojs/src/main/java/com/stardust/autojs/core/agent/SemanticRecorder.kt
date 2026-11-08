package com.stardust.autojs.core.agent

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.stardust.view.accessibility.AccessibilityDelegate
import com.stardust.view.accessibility.AccessibilityNodeInfoHelper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

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

    /**
     * Bumped on every start/stop. A background screen capture carries the
     * generation it was started under, so a capture that finishes after its
     * recording ended (or after a new one began) can tell it is stale and drop
     * its template instead of writing into the wrong list.
     */
    private val generation = AtomicInteger(0)

    fun start() {
        generation.incrementAndGet()
        recordedActions.clear()
        state = State.RECORDING
    }

    fun stop(): List<SemanticAction> {
        // Order matters. Flipping to IDLE first stops new events from being
        // recorded straight away, but deliberately leaves this recording's
        // generation intact so that captures already queued or running can
        // still swap their results into recordedActions. Bumping the
        // generation here instead - as this used to - is exactly what threw
        // away every in-flight capture, silently leaving those steps as
        // fixed-coordinate CLICK_BOUNDS fallbacks.
        state = State.IDLE
        // Bounded: on timeout we simply proceed, so an unanswered
        // screen-capture permission dialog can't wedge the caller (stop() runs
        // on the UI thread, from the bubble's Stop tap).
        screenStateReader.awaitPendingCaptures(CAPTURE_DRAIN_TIMEOUT_MS)
        // Only now is it safe to invalidate whatever is still outstanding, and
        // only now does the snapshot include the swaps that just landed.
        generation.incrementAndGet()
        return recordedActions.toList()
    }

    override fun onAccessibilityEvent(service: AccessibilityService, event: AccessibilityEvent): Boolean {
        if (state != State.RECORDING) return false
        // The bubble's own Record/Stop/Play controls live in this same app's
        // process, so taps on them surface as ordinary accessibility events
        // just like taps in the app being automated - without this check they
        // get recorded as steps in their own right (an unlabeled icon tap
        // reads as a CLICK_IMAGE, which is also why starting a recording used
        // to trigger a screen-capture permission prompt immediately). Compare
        // by content, not reference: event.packageName's concrete CharSequence
        // type isn't guaranteed to implement value equality.
        if (event.packageName?.toString() == service.packageName) return false
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
        val isLongClick = clickType == ActionType.LONG_CLICK
        if (target.text != null) {
            recordedActions.add(
                SemanticAction(
                    type = clickType,
                    bounds = target.bounds,
                    targetText = target.text,
                    matchBy = target.matchBy,
                    isLongClick = isLongClick
                )
            )
            return
        }
        // No matchable label, so this tap needs an image template - and
        // capturing one must not block the accessibility-event (main) thread.
        // Lock the step's position in the recording right now with a bounds
        // fallback, then upgrade that exact entry in place once the background
        // capture produces a template. Appends from later events therefore stay
        // correctly ordered relative to this one.
        val placeholder = SemanticAction(
            type = ActionType.CLICK_BOUNDS,
            bounds = target.bounds,
            isLongClick = isLongClick
        )
        recordedActions.add(placeholder)
        // Recording only ever happens on the accessibility-event thread and
        // entries are never removed, so the index just appended to is stable.
        val index = recordedActions.size - 1
        val startedGeneration = generation.get()
        screenStateReader.captureTemplateAsync(service, target.bounds) { path ->
            if (path == null) return@captureTemplateAsync
            // Deliberately *not* testing `state != State.RECORDING` here:
            // stop() sets IDLE before draining this executor precisely so
            // in-flight captures can still land, and a state test would mark
            // every one of them stale again. The generation check is
            // sufficient on its own - stop() is the only path to IDLE, and it
            // always bumps the generation once the drain is done.
            val stale = generation.get() != startedGeneration ||
                index >= recordedActions.size ||
                recordedActions[index] !== placeholder
            if (stale) {
                // Nothing will ever reference this template file.
                screenStateReader.deleteTemplate(path)
                return@captureTemplateAsync
            }
            recordedActions[index] = SemanticAction(
                type = ActionType.CLICK_IMAGE,
                bounds = target.bounds,
                imageTemplatePath = path,
                isLongClick = isLongClick
            )
        }
    }

    private fun recordFillText(service: AccessibilityService, source: AccessibilityNodeInfo, event: AccessibilityEvent) {
        val target = screenStateReader.describeEditableTarget(service, source)
        recordedActions.add(
            SemanticAction(
                type = ActionType.FILL_TEXT,
                bounds = target.bounds,
                targetText = target.text,
                fillValue = event.text?.joinToString("").orEmpty(),
                matchBy = target.matchBy
            )
        )
    }

    private fun recordScroll(source: AccessibilityNodeInfo, event: AccessibilityEvent) {
        // AccessibilityEvent.getScrollDeltaX/Y were added in API 28 and this
        // module's minSdk is 21; calling them below P throws NoSuchMethodError
        // straight out of the accessibility service's event dispatch loop.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // Scroll deltas unavailable below API 28; skip recording this
            // scroll rather than guessing its direction.
            return
        }
        val deltaX = event.scrollDeltaX
        val deltaY = event.scrollDeltaY
        val direction = when {
            deltaY > 0 -> ScrollDirection.DOWN
            deltaY < 0 -> ScrollDirection.UP
            deltaX > 0 -> ScrollDirection.RIGHT
            deltaX < 0 -> ScrollDirection.LEFT
            // Both deltas zero: the direction is genuinely indeterminate, so
            // record nothing rather than mislabelling it.
            else -> return
        }
        val bounds = Bounds.fromRect(AccessibilityNodeInfoHelper.getBoundsInScreen(source))
        recordedActions.add(SemanticAction(type = ActionType.SCROLL, bounds = bounds, scrollDirection = direction))
    }

    companion object {
        /**
         * How long stop() waits for already-started captures to finish. A real
         * capture + crop + PNG encode + write is well under a second; three
         * seconds leaves generous headroom for one such job queued behind
         * another while staying clearly inside the ~5s input-dispatch ANR
         * window, since stop() is reached from a user tap on the main thread.
         */
        private const val CAPTURE_DRAIN_TIMEOUT_MS = 3_000L
    }
}
