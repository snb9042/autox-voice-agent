package com.stardust.autojs.core.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import com.stardust.autojs.core.image.ImageWrapper
import com.stardust.autojs.core.image.capture.ScreenCaptureManager
import com.stardust.autojs.runtime.api.Paddle
import com.stardust.view.accessibility.AccessibilityNodeInfoHelper
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ScreenStateReader {

    data class TargetDescription(
        val bounds: Bounds,
        val text: String?,
        val matchBy: MatchBy?,
        val imageTemplatePath: String? = null
    )

    private val screenCaptureManager = ScreenCaptureManager()
    private var templateDir: File? = null

    /**
     * Shared OCR instance for [ocrFallback], which is currently disabled - so
     * nothing reaches this at the moment. `Paddle`'s underlying `Predictor`
     * does its real native model-load work on first *use*, not on
     * construction, so holding an unused instance costs nothing; it is cached
     * rather than constructed per call so that re-enabling OCR doesn't pay a
     * full model load on every voice turn instead of just an inference cost.
     */
    private val paddle = Paddle()

    /**
     * Single background thread for all screen-capture work. Everything here is
     * called from the accessibility-event (main) thread, and capturing /
     * cropping / saving a bitmap - let alone awaiting the screen-capture
     * permission dialog - must never run there. One thread (rather than a pool
     * or an unscoped coroutine launch) keeps captures serialized in recording
     * order and keeps the permission request from being fired several times
     * concurrently.
     */
    private val captureExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "agent-screen-capture").apply { isDaemon = true }
    }

    fun describeClickTarget(service: AccessibilityService, node: AccessibilityNodeInfo): TargetDescription {
        val bounds = Bounds.fromRect(AccessibilityNodeInfoHelper.getBoundsInScreen(node))
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
            return TargetDescription(bounds, it, MatchBy.TEXT)
        }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
            return TargetDescription(bounds, it, MatchBy.DESC)
        }
        // No matchable label. The caller is expected to fall back to an image
        // template (see captureTemplateAsync) or to raw bounds.
        return TargetDescription(bounds, null, null)
    }

    fun describeEditableTarget(service: AccessibilityService, node: AccessibilityNodeInfo): TargetDescription {
        val bounds = Bounds.fromRect(AccessibilityNodeInfoHelper.getBoundsInScreen(node))
        // Deliberately *not* using hintText: there is no Auto.js selector that
        // can match on a hint (UiGlobalSelector has desc(...) but no hint(...)),
        // so a hint-sourced label would generate a step that can never find its
        // target. Without a content description we return a null label and let
        // the caller fall back to bounds matching.
        val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        return TargetDescription(bounds, desc, desc?.let { MatchBy.DESC })
    }

    /**
     * Captures, crops and saves a template for [bounds] on the capture
     * executor, then invokes [onCaptured] on that same background thread with
     * the staged file's absolute path, or null if the capture was not possible
     * (no permission yet, capture failed, ...). Never blocks the caller.
     */
    fun captureTemplateAsync(
        service: AccessibilityService,
        bounds: Bounds,
        onCaptured: (String?) -> Unit
    ) {
        captureExecutor.execute {
            val path = try {
                captureTemplate(service, bounds)
            } catch (e: Exception) {
                null
            }
            try {
                onCaptured(path)
            } catch (e: Exception) {
                // A failing consumer must not kill the capture thread.
            }
        }
    }

    /**
     * Blocks the caller until every capture job that was already queued or
     * running when this was called has finished - including the [onCaptured]
     * consumer it runs on this same thread - or until [timeoutMs] elapses,
     * whichever comes first. Never blocks indefinitely.
     *
     * This works because [captureExecutor] is single-threaded and runs tasks
     * strictly in submission order: a marker task submitted now cannot start
     * until all previously submitted work has completed. Returns true if the
     * queue actually drained, false if it timed out or the wait failed.
     */
    fun awaitPendingCaptures(timeoutMs: Long): Boolean {
        return try {
            captureExecutor.submit(Runnable { }).get(timeoutMs, TimeUnit.MILLISECONDS)
            true
        } catch (e: TimeoutException) {
            // A capture is still stuck - most likely on an unanswered
            // screen-capture permission dialog. Give up waiting rather than
            // leaving the caller (and, for stop(), the UI thread) hanging.
            false
        } catch (e: Exception) {
            // Interrupted, rejected, or the marker task itself failed; nothing
            // useful to do but stop waiting.
            false
        }
    }

    /** Deletes a staged template file. Safe to call with a null/unknown path. */
    fun deleteTemplate(path: String?) {
        if (path.isNullOrEmpty()) return
        try {
            File(path).delete()
        } catch (e: Exception) {
            // Best effort only.
        }
    }

    /** Runs on [captureExecutor] only - it blocks. */
    private fun captureTemplate(service: AccessibilityService, bounds: Bounds): String? {
        if (screenCaptureManager.screenCapture?.available != true) {
            // Requesting screen-capture permission awaits an activity-result
            // callback delivered on the main looper. We're on the capture
            // thread (never the accessibility-event/main thread), so blocking
            // here is safe. A request the user leaves unanswered holds up later
            // captures on this single thread, which is what we want: those taps
            // simply stay on their CLICK_BOUNDS fallback instead of stacking up
            // permission prompts.
            try {
                runBlocking {
                    screenCaptureManager.requestScreenCapture(
                        service.applicationContext,
                        service.resources.configuration.orientation
                    )
                }
            } catch (e: Exception) {
                // Permission denied or request failed; the next qualifying tap
                // will simply retry the request.
                return null
            }
            if (screenCaptureManager.screenCapture?.available != true) return null
        }
        return try {
            runBlocking {
                val screen = screenCaptureManager.screenCapture?.captureImageWrapper() ?: return@runBlocking null
                val cropped = cropToBounds(screen, bounds) ?: return@runBlocking null
                val file = File(templateDirFor(service), "${UUID.randomUUID()}.png")
                ImageWrapper.ofBitmap(cropped).saveTo(file.absolutePath)
                file.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun cropToBounds(image: ImageWrapper, bounds: Bounds): Bitmap? {
        val bitmap = image.bitmap
        val left = bounds.left.coerceIn(0, bitmap.width - 1)
        val top = bounds.top.coerceIn(0, bitmap.height - 1)
        val width = (bounds.right - bounds.left).coerceIn(1, bitmap.width - left)
        val height = (bounds.bottom - bounds.top).coerceIn(1, bitmap.height - top)
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    private fun templateDirFor(service: AccessibilityService): File {
        return templateDir ?: File(service.applicationContext.filesDir, "agent_templates").also {
            it.mkdirs()
            templateDir = it
        }
    }

    /**
     * Everything visible right now, for the voice agent to reason over: every
     * accessibility node's text or content-description, with its bounds.
     *
     * The OCR pass ([ocrFallback]) is deliberately **disabled** in this
     * version, for three reasons:
     *
     *  1. It didn't work. OCR-sourced entries were tagged [MatchBy.TEXT], but
     *     an OCR-only target has no accessibility `getText()` /
     *     `getContentDescription()` for the generated script's
     *     `__findTextNear` / `__findDescNear` selectors to match against at
     *     execution time - so an OCR-matched action always burned its full
     *     10s find timeout and then failed, defeating the point of the
     *     fallback. Making these targets actionable needs CLICK_BOUNDS
     *     routing for label-less targets, which does not exist yet;
     *     **re-enabling OCR requires building that routing first.**
     *  2. Each call captured a full-screen bitmap (and its native Mat) that
     *     was never recycled, leaking memory on every OCR-triggering turn.
     *  3. It appended an unbounded number of extra entries to the prompt.
     *
     * The accepted consequence is that targets which are readable on screen
     * but carry no accessibility label are not voice-addressable in this
     * version.
     *
     * Still call off the main thread: the accessibility walk is not work for
     * the accessibility-event thread, the same reason template capture
     * already runs on [captureExecutor].
     */
    fun describeScreen(service: AccessibilityService): ScreenDescription {
        val root = service.rootInActiveWindow
            ?: return ScreenDescription(Bounds(0, 0, 0, 0), emptyList())
        val screenBounds = Bounds.fromRect(AccessibilityNodeInfoHelper.getBoundsInScreen(root))
        val visible = mutableListOf<VisibleText>()
        collectVisibleText(root, visible)
        root.recycle()
        return ScreenDescription(screenBounds, visible)
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo, out: MutableList<VisibleText>) {
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            val bounds = Bounds.fromRect(AccessibilityNodeInfoHelper.getBoundsInScreen(node))
            when {
                text != null -> out.add(VisibleText(text, MatchBy.TEXT, bounds))
                desc != null -> out.add(VisibleText(desc, MatchBy.DESC, bounds))
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectVisibleText(child, out)
            child.recycle()
        }
    }

    /**
     * **Currently unused.** [describeScreen] no longer calls this; see its doc
     * comment for why OCR is disabled and what has to be built before it can
     * be switched back on. Kept, rather than deleted, so re-enabling it is a
     * one-line change once CLICK_BOUNDS routing exists for label-less targets.
     *
     * When it was live it returned whole-screen OCR text unioned into
     * [describeScreen]'s accessibility-sourced list - not, despite an earlier
     * version of this comment, a gated fallback scoped to unlabeled widgets:
     * it was unconditional and undeduplicated, so an already-labeled widget's
     * text could appear twice. It never requests screen-capture permission -
     * that belongs to an explicit user action (recording an unlabeled tap),
     * not a side effect of speaking an instruction - and returns nothing if
     * permission isn't already available. Note it also does not recycle the
     * bitmap it captures (finding 4b); that must be fixed before re-enabling.
     */
    @Suppress("unused")
    private fun ocrFallback(): List<VisibleText> {
        val capturer = screenCaptureManager.screenCapture?.takeIf { it.available } ?: return emptyList()
        return try {
            runBlocking {
                val screen = capturer.captureImageWrapper() ?: return@runBlocking emptyList()
                paddle.ocr(screen).map {
                    VisibleText(it.text, MatchBy.TEXT, Bounds.fromRect(it.bounds))
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
