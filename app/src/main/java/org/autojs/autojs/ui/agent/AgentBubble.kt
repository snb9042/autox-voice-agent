package org.autojs.autojs.ui.agent

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import com.afollestad.materialdialogs.DialogAction
import com.afollestad.materialdialogs.MaterialDialog
import com.makeramen.roundedimageview.RoundedImageView
import com.stardust.app.DialogUtils
import com.stardust.autojs.core.agent.ActionOptimizer
import com.stardust.autojs.core.agent.ActionScriptGenerator
import com.stardust.autojs.core.agent.ActionType
import com.stardust.autojs.core.agent.ClaudeActionPlanner
import com.stardust.autojs.core.agent.PlanResult
import com.stardust.autojs.core.agent.ScreenStateReader
import com.stardust.autojs.core.agent.SemanticAction
import com.stardust.autojs.core.agent.SemanticRecorder
import com.stardust.autojs.core.agent.VoiceAgentSession
import com.stardust.autojs.core.permission.Permissions
import com.stardust.autojs.execution.ExecutionConfig
import com.stardust.autojs.execution.ScriptExecution
import com.stardust.autojs.execution.ScriptExecutionListener
import com.stardust.autojs.script.StringScriptSource
import com.stardust.autojs.util.FloatingPermission
import com.stardust.enhancedfloaty.FloatyService
import com.stardust.view.accessibility.AccessibilityService
import org.autojs.autojs.Pref
import org.autojs.autojs.autojs.AutoJs
import org.autojs.autojs.autojs.key.GlobalKeyObserver
import org.autojs.autojs.model.explorer.ExplorerDirPage
import org.autojs.autojs.model.explorer.Explorers
import org.autojs.autojs.model.script.Scripts
import org.autojs.autojs.theme.dialog.ThemeColorMaterialDialogBuilder
import org.autojs.autojs.tool.AccessibilityServiceTool
import org.autojs.autojs.ui.common.ScriptOperations
import org.autojs.autojs.ui.explorer.ExplorerViewKt
import org.autojs.autojs.ui.floating.CircularActionMenu
import org.autojs.autojs.ui.floating.CircularMenuFloaty
import org.autojs.autojs.ui.floating.CircularMenuWindow
import org.autojs.autoxjs.R
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AgentBubble(context: Context) {

    private val mContext: Context = ContextThemeWrapper(context, R.style.AppTheme)
    private var mWindow: CircularMenuWindow? = null
    private var mIcon: RoundedImageView? = null
    private val mRecorder: SemanticRecorder = AutoJs.getInstance().semanticRecorder
    private val mVoiceSession = VoiceAgentSession()
    private val mPlanner = ClaudeActionPlanner()

    /**
     * Identifies which voice session is "current", separately from
     * [VoiceAgentSession.state]. [VoiceAgentSession.stop] snapshots and
     * returns its actions but does not clear its internal list - only
     * [VoiceAgentSession.start] does - and a turn's describe+plan+execute
     * work can still be in flight on [voiceTurnExecutor] after the user taps
     * Stop (or taps Talk again, which restarts the session for a new one).
     * Without this, a stray turn resolving after its session has already
     * been stopped/restarted would call [VoiceAgentSession.addAction] on
     * whichever session is live *now*, silently folding an action from a
     * finished or unrelated session into one the user never spoke it into.
     * Bumped every time the voice session's identity changes (start or
     * stop); a turn captures the value before being dispatched and checks it
     * again once it resolves, dropping its result if the value moved on.
     */
    @Volatile
    private var mVoiceSessionEpoch = 0

    /**
     * The script a voice turn is currently blocked on in [executeAction], or
     * null when no turn is executing. The epoch above only invalidates
     * *bookkeeping*; this handle is what makes Stop (and closing the bubble)
     * able to kill the script that is physically driving the screen right
     * now, rather than letting its click/scroll/fill land anyway and merely
     * discarding the result afterwards.
     */
    @Volatile
    private var mInFlightExecution: ScriptExecution? = null
    // Paddle's underlying Predictor does real native model-load work the
    // first time it's used (see ScreenStateReader's own doc comment on its
    // cached instance), so this reader is held for the bubble's lifetime
    // rather than constructed fresh per voice turn.
    private val mScreenStateReader = ScreenStateReader()
    private var mSpeechRecognizer: SpeechRecognizer? = null
    private val mWalkieTalkieHandler = Handler(Looper.getMainLooper())
    private val mWalkieTalkieSafetyStopRunnable = Runnable { mSpeechRecognizer?.stopListening() }
    private val mVolumeUpHoldListener = object : GlobalKeyObserver.OnVolumeUpHoldListener {
        override fun onVolumeUpHoldStart() = this@AgentBubble.onVolumeUpHoldStart()
        override fun onVolumeUpHoldEnd() = this@AgentBubble.onVolumeUpHoldEnd()
    }

    /**
     * One voice turn (screen description + the Claude call + running the
     * resulting script) is network- and OCR-bound and must never run on the
     * main thread - the same reasoning that already keeps template capture
     * off it in ScreenStateReader. Single-threaded so overlapping turns (a
     * second mic tap before the first finishes) queue rather than race.
     */
    private val voiceTurnExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "agent-voice-turn").apply { isDaemon = true }
    }

    init {
        initFloaty()
    }

    private fun initFloaty() {
        // CircularMenuWindow persists its position under keys derived from
        // its own class name, so this bubble and the legacy CircularMenu
        // bubble share one saved position and whichever closes last wins -
        // both bubbles are meant to remember "the last place either of them
        // was left," not independent positions.
        mWindow = CircularMenuWindow(
            mContext,
            object : CircularMenuFloaty {
                override fun inflateActionView(service: FloatyService, window: CircularMenuWindow): View {
                    val view = View.inflate(service, R.layout.agent_bubble_action_view, null)
                    mIcon = view.findViewById(R.id.agent_icon)
                    // The recorder is a process-wide singleton, so reflect its
                    // real state instead of assuming idle.
                    updateIcon()
                    return view
                }

                override fun inflateMenuItems(service: FloatyService, window: CircularMenuWindow): CircularActionMenu {
                    val menu = View.inflate(
                        ContextThemeWrapper(service, R.style.AppTheme),
                        R.layout.agent_bubble_action_menu,
                        null
                    ) as CircularActionMenu
                    menu.findViewById<View>(R.id.agent_record)?.setOnClickListener { startRecording() }
                    menu.findViewById<View>(R.id.agent_stop)?.setOnClickListener { stopActive() }
                    menu.findViewById<View>(R.id.agent_play)?.setOnClickListener { pickAndPlay() }
                    menu.findViewById<View>(R.id.agent_stop_all_scripts)
                        ?.setOnClickListener { stopAllScripts() }
                    menu.findViewById<View>(R.id.agent_talk)?.setOnClickListener { startVoiceTurn() }
                    return menu
                }
            }
        )
        // CircularMenuWindow.setListeners() already installs the
        // expand/collapse toggle on the action view, so there is nothing to
        // override here.
        mWindow?.setKeepToSideHiddenWidthRadio(0f)
        FloatyService.addWindow(mWindow)
        GlobalKeyObserver.init()
        GlobalKeyObserver.getSingleton().addVolumeUpHoldListener(mVolumeUpHoldListener)
    }

    private fun updateIcon() {
        mIcon?.setBackgroundResource(
            if (mRecorder.state == SemanticRecorder.State.RECORDING) R.drawable.circle_red
            else R.drawable.circle_white
        )
    }

    private fun startRecording() {
        mWindow?.collapse()
        // Starting again would clear the in-progress recording, silently
        // discarding everything captured so far. A voice turn and a
        // recording must not run concurrently either - they'd both be
        // writing into the picture of "what happened this session".
        if (mRecorder.state == SemanticRecorder.State.RECORDING) return
        if (mVoiceSession.state == VoiceAgentSession.State.ACTIVE) return
        if (!AccessibilityServiceTool.isAccessibilityServiceEnabled(mContext)) {
            AccessibilityServiceTool.goToAccessibilitySetting()
            return
        }
        mRecorder.start()
        updateIcon()
    }

    private fun stopActive() {
        mWindow?.collapse()
        when {
            mRecorder.state == SemanticRecorder.State.RECORDING -> {
                val actions = mRecorder.stop()
                updateIcon()
                finishSession(actions)
            }
            mVoiceSession.state == VoiceAgentSession.State.ACTIVE -> {
                // This session's identity ends here - any turn still in
                // flight for it must not land its result later. See
                // mVoiceSessionEpoch's doc comment.
                mVoiceSessionEpoch++
                // The epoch alone would let a turn already blocked inside
                // executeAction() run its script to completion and only then
                // have its result dropped - so the tap/scroll/fill would
                // still physically happen after Stop. Kill the script too.
                forceStop(mInFlightExecution)
                finishSession(mVoiceSession.stop(), optimize = false)
            }
            // Nothing active - don't optimize, generate, or show a "0
            // step(s) recorded" dialog for an accidental Stop tap.
        }
    }

    /**
     * Converges the Record and voice paths on the same Preview/Save/Discard
     * flow.
     *
     * [optimize] must be false for a voice session. ActionOptimizer merges
     * consecutive scrolls, which is correct for Record - one physical fling
     * emits many scroll accessibility events - but wrong here: each voice
     * SCROLL is one separate spoken instruction that already executed once on
     * screen and was appended to the session only after a confirmed success,
     * so three spoken "scroll down"s must save as three steps, not one.
     */
    private fun finishSession(actions: List<SemanticAction>, optimize: Boolean = true) {
        val kept = if (optimize) ActionOptimizer.optimize(actions) else actions
        // Templates belonging to steps the optimizer dropped can never be
        // referenced again. When nothing was optimized away, `kept` is
        // `actions` itself and this drop-list is empty for free.
        discardTemplates(actions.filterNot { kept.contains(it) })
        showSummaryDialog(kept)
    }

    private fun stopAllScripts() {
        mWindow?.collapse()
        AutoJs.getInstance().scriptEngineService.stopAllAndToast()
    }

    private fun startVoiceTurn() {
        mWindow?.collapse()
        // A recording and a voice turn must not run concurrently (see
        // startRecording's guard). A second mic tap while one recognizer is
        // already listening would leave two live recognizers competing for
        // the mic, so block on that too - a second tap while a turn is only
        // *thinking* (mSpeechRecognizer already null, back on the executor)
        // is fine and queues behind it.
        if (mRecorder.state == SemanticRecorder.State.RECORDING) return
        if (mSpeechRecognizer != null) return
        if (!AccessibilityServiceTool.isAccessibilityServiceEnabled(mContext)) {
            AccessibilityServiceTool.goToAccessibilitySetting()
            return
        }
        if (Pref.getClaudeApiKey().isBlank()) {
            AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_api_key))
            return
        }
        val missing = Permissions.getPermissionsNeedToRequest(mContext, arrayOf("RECORD_AUDIO"))
        if (missing.isNotEmpty()) {
            Permissions.requestPermissions(mContext, missing)
            return
        }
        if (mVoiceSession.state != VoiceAgentSession.State.ACTIVE) {
            // A new session identity starts here too - see
            // mVoiceSessionEpoch's doc comment.
            mVoiceSessionEpoch++
            mVoiceSession.start()
        }
        listenForInstruction()
    }

    /**
     * Must be called on the main thread - SpeechRecognizer requires it.
     *
     * [walkieTalkie] suppresses Android's own silence-based auto-stop (a
     * mid-sentence pause must not end a held-key instruction early) and
     * schedules [WALKIE_TALKIE_MAX_HOLD_MILLIS] as a hard safety cap in case
     * the key-release event is ever missed - [onVolumeUpHoldEnd] is the
     * normal way listening ends for this path, by calling stopListening()
     * directly, which this safety timer also does if it fires first.
     */
    private fun listenForInstruction(walkieTalkie: Boolean = false, onTranscribed: (String) -> Unit = { runVoiceTurn(it) }) {
        AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_listening))
        val recognizer = SpeechRecognizer.createSpeechRecognizer(mContext).also { mSpeechRecognizer = it }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                mWalkieTalkieHandler.removeCallbacks(mWalkieTalkieSafetyStopRunnable)
                recognizer.destroy()
                mSpeechRecognizer = null
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) {
                    AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_speech))
                } else {
                    onTranscribed(text)
                }
            }

            override fun onError(error: Int) {
                mWalkieTalkieHandler.removeCallbacks(mWalkieTalkieSafetyStopRunnable)
                recognizer.destroy()
                mSpeechRecognizer = null
                AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_speech))
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            if (walkieTalkie) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, WALKIE_TALKIE_MAX_HOLD_MILLIS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, WALKIE_TALKIE_MAX_HOLD_MILLIS)
            }
        }
        recognizer.startListening(intent)
        if (walkieTalkie) {
            mWalkieTalkieHandler.postDelayed(mWalkieTalkieSafetyStopRunnable, WALKIE_TALKIE_MAX_HOLD_MILLIS)
        }
    }

    /** Invoked on the main thread by [GlobalKeyObserver] once a volume-up hold is confirmed. */
    private fun onVolumeUpHoldStart() {
        mWindow?.collapse()
        // Identical guards to startVoiceTurn() - a held volume-up key begins
        // the exact same kind of turn the mic button does, not a different one.
        if (mRecorder.state == SemanticRecorder.State.RECORDING) return
        if (mSpeechRecognizer != null) return
        if (!AccessibilityServiceTool.isAccessibilityServiceEnabled(mContext)) {
            AccessibilityServiceTool.goToAccessibilitySetting()
            return
        }
        if (Pref.getClaudeApiKey().isBlank()) {
            AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_api_key))
            return
        }
        val missing = Permissions.getPermissionsNeedToRequest(mContext, arrayOf("RECORD_AUDIO"))
        if (missing.isNotEmpty()) {
            Permissions.requestPermissions(mContext, missing)
            return
        }
        if (mVoiceSession.state != VoiceAgentSession.State.ACTIVE) {
            mVoiceSessionEpoch++
            mVoiceSession.start()
        }
        listenForInstruction(walkieTalkie = true)
    }

    /** Invoked on the main thread by [GlobalKeyObserver] when a confirmed hold's key is released. */
    private fun onVolumeUpHoldEnd() {
        mWalkieTalkieHandler.removeCallbacks(mWalkieTalkieSafetyStopRunnable)
        mSpeechRecognizer?.stopListening()
    }

    /** Runs the describe-plan-execute sequence on [voiceTurnExecutor]. */
    /**
     * [isRetry] caps clarification at exactly one round: a true turn is
     * itself the retry after the user answered a clarifying question, so a
     * further NeedsClarification (from the planner, or a second execution
     * failure) is reported as today's terminal failure instead of asking
     * again - see [askForClarification].
     */
    private fun runVoiceTurn(instruction: String, isRetry: Boolean = false) {
        AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_thinking))
        // Captured on the main thread, before this turn is handed off - see
        // mVoiceSessionEpoch's doc comment. Stop (or a fresh Talk tap) can
        // end/replace this session while this turn is still describing,
        // planning, or executing on the background thread below.
        val sessionEpoch = mVoiceSessionEpoch
        voiceTurnExecutor.execute {
            // Nothing below may be allowed to throw out of this lambda. App
            // installs a global CrashHandler that catches uncaught
            // background-thread exceptions and uploads them to a remote crash
            // service with no toast or dialog, so an unguarded failure here
            // would make the turn vanish behind a "Thinking..." toast that
            // never resolves - and could carry the API key off-device inside
            // a stack trace. The most reachable trigger is OkHttp's
            // addHeader(), which throws IllegalArgumentException on a key with
            // a stray newline or any non-printable-ASCII character.
            try {
                // This turn may have been queued behind a slower one while the
                // user tapped Stop. Bail before doing any describe / plan /
                // execute work at all - see mVoiceSessionEpoch's doc comment.
                if (mVoiceSessionEpoch != sessionEpoch) return@execute
                val service = AccessibilityService.instance
                if (service == null) {
                    AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_action))
                    return@execute
                }
                val screen = mScreenStateReader.describeScreen(service)
                when (val result = mPlanner.plan(instruction, screen, Pref.getClaudeApiKey())) {
                    is PlanResult.Failed -> {
                        AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_action))
                    }
                    is PlanResult.NeedsClarification -> {
                        if (isRetry) {
                            AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_action))
                        } else {
                            askForClarification(instruction, result.question, sessionEpoch)
                        }
                    }
                    is PlanResult.Action -> {
                        // Describing the screen and the Claude round trip both
                        // take real time. Re-check before physically touching
                        // the screen, so a Stop during that window doesn't
                        // still perform an action.
                        if (mVoiceSessionEpoch != sessionEpoch) return@execute
                        val succeeded = executeAction(result.action)
                        if (mVoiceSessionEpoch != sessionEpoch) {
                            // The session this turn belongs to was stopped, or a
                            // new one started, while this turn was still in
                            // flight. Its result is stale and must not be folded
                            // into whatever session is live now - drop it
                            // silently rather than calling addAction() or
                            // reporting/asking about it.
                            return@execute
                        }
                        if (succeeded) {
                            mVoiceSession.addAction(result.action)
                            AutoJs.getInstance().uiHandler.toast(
                                mContext.getString(R.string.text_agent_action_performed, instruction)
                            )
                        } else if (isRetry) {
                            val label = result.action.targetText ?: instruction
                            AutoJs.getInstance().uiHandler.toast(
                                mContext.getString(R.string.text_agent_target_not_found, label)
                            )
                        } else {
                            val label = result.action.targetText ?: instruction
                            askForClarification(
                                instruction,
                                mContext.getString(R.string.text_agent_clarify_target_not_found, label),
                                sessionEpoch
                            )
                        }
                    }
                }
            } catch (e: InterruptedException) {
                // close() interrupted this turn via shutdownNow(). The bubble
                // is going away, so there is no longer anyone to report to -
                // toasting here would surface a failure after the UI is gone.
            } catch (e: Exception) {
                AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_action))
            }
        }
    }

    /**
     * Shows [question], then listens for one spoken answer and retries the
     * turn with it folded into the original instruction - at most once, see
     * [runVoiceTurn]'s [isRetry] parameter. Called from the background
     * [voiceTurnExecutor] thread; hops to the main thread itself since
     * [listenForInstruction] requires it.
     */
    private fun askForClarification(originalInstruction: String, question: String, sessionEpoch: Int) {
        AutoJs.getInstance().uiHandler.toast(question)
        mWalkieTalkieHandler.post {
            if (mVoiceSessionEpoch != sessionEpoch) return@post
            if (mSpeechRecognizer != null) return@post
            listenForInstruction(walkieTalkie = true) { answer ->
                runVoiceTurn("$originalInstruction ($answer)", isRetry = true)
            }
        }
    }

    /**
     * Runs [action] as a one-step script and blocks (on the calling background
     * thread) for the result.
     *
     * The returned [ScriptExecution] is published to [mInFlightExecution] for
     * the duration of the call, and force-stopped here if the script blows
     * past its own timeout: discarding the handle would leave a runaway script
     * with no way to be killed, still touching the screen indefinitely.
     */
    private fun executeAction(action: SemanticAction): Boolean {
        val script = ActionScriptGenerator.generate(listOf(action))
        val source = StringScriptSource("voice-turn", script)
        val latch = CountDownLatch(1)
        var succeeded = false
        val listener = object : ScriptExecutionListener {
            override fun onStart(execution: ScriptExecution) {}
            override fun onSuccess(execution: ScriptExecution, result: Any?) {
                succeeded = true
                latch.countDown()
            }

            override fun onException(execution: ScriptExecution, e: Throwable) {
                succeeded = false
                latch.countDown()
            }
        }
        val execution = AutoJs.getInstance().scriptEngineService.execute(
            source, listener, ExecutionConfig(workingDirectory = Pref.getScriptDirPath())
        )
        mInFlightExecution = execution
        try {
            if (!latch.await(VOICE_TURN_EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                // The script outlived even its own internal find timeout.
                // Nothing else will ever stop it, so stop it here rather than
                // returning and leaving it running on the device.
                forceStop(execution)
            }
        } finally {
            mInFlightExecution = null
        }
        return succeeded
    }

    /**
     * Best-effort force-stop of a running voice-turn script.
     *
     * [ScriptExecution] itself exposes no stop/cancel; forcing the engine to
     * stop is the documented way to stop a script from another thread (see
     * ScriptEngine's own KDoc), and is what the rest of this app already does.
     * `engine` is null until the script thread has created one, so a turn
     * stopped in its first instants simply has nothing to stop.
     */
    private fun forceStop(execution: ScriptExecution?) {
        try {
            execution?.engine?.forceStop()
        } catch (e: Exception) {
            // The script may have finished and destroyed its engine between
            // the read above and this call; nothing left to stop either way.
        }
    }

    private fun showSummaryDialog(actions: List<SemanticAction>) {
        DialogUtils.showDialog(
            ThemeColorMaterialDialogBuilder(mContext)
                .title(R.string.text_recorded)
                .content(mContext.getString(R.string.text_agent_recorded_steps, actions.size))
                .items(
                    mContext.getString(R.string.text_preview),
                    mContext.getString(R.string.text_new_file)
                )
                .itemsCallback { _, _, position, _ ->
                    when (position) {
                        0 -> showPreviewDialog(ActionScriptGenerator.generate(actions)) {
                            showSummaryDialog(actions)
                        }
                        1 -> promptSaveScript(actions)
                    }
                }
                .negativeText(R.string.text_cancel)
                .onNegative { _, _ -> discardTemplates(actions) }
                .cancelListener { discardTemplates(actions) }
                .canceledOnTouchOutside(false)
                .build()
        )
    }

    private fun showPreviewDialog(script: String, onDismiss: () -> Unit) {
        val textView = TextView(mContext).apply {
            text = script
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(24, 24, 24, 24)
        }
        val scrollView = ScrollView(mContext).apply { addView(textView) }
        DialogUtils.showDialog(
            ThemeColorMaterialDialogBuilder(mContext)
                .title(R.string.text_preview)
                .customView(scrollView, false)
                .positiveText(R.string.ok)
                .dismissListener { onDismiss() }
                .build()
        )
    }

    /**
     * Asks for a script name, then writes the script - and the image templates
     * it references - into the scripts directory. Driving the name prompt here
     * (rather than through ScriptOperations.newScriptFileForScript) is what
     * makes it possible to tell "saved" from "discarded" and clean up the
     * staged templates in the latter case.
     */
    private fun promptSaveScript(actions: List<SemanticAction>) {
        var saved = false
        var saveAttemptFailed = false
        val dialog = ThemeColorMaterialDialogBuilder(mContext)
            .title(R.string.text_name)
            .inputType(InputType.TYPE_CLASS_TEXT)
            // Without this the input callback fires only once, so the
            // collision check below would never see what the user typed.
            .alwaysCallInputCallback()
            .input(mContext.getString(R.string.text_please_input_name), "", false) { dialog, input ->
                validateScriptName(dialog, input)
            }
            .onPositive { dialog, _ ->
                val fileName = scriptFileNameFor(dialog.inputEditText?.text?.toString().orEmpty())
                when {
                    // Should be unreachable - the positive button is disabled
                    // for a blank name - but a blank submit must not be
                    // mistaken for a cancel and take the recording with it.
                    fileName == null -> saveAttemptFailed = true
                    saveScript(fileName, actions) -> {
                        saved = true
                        // The staged originals have been copied next to the
                        // script; their only reference now is the relocated one.
                        discardTemplates(actions)
                    }
                    // The write didn't happen. Keep the staged templates so the
                    // recording stays recoverable rather than destroying it on
                    // a failed save.
                    else -> saveAttemptFailed = true
                }
            }
            .negativeText(R.string.text_cancel)
            .dismissListener {
                // Declining to save means the staged templates are garbage.
                // A save that was attempted and failed is *not* a decline, so
                // it must not take the recording down with it.
                if (!saved && !saveAttemptFailed) discardTemplates(actions)
            }
            .build()
        // Run the validator once up front so the button starts out disabled for
        // the empty prefill, rather than relying on the dialog library to do it.
        validateScriptName(dialog, dialog.inputEditText?.text ?: "")
        DialogUtils.showDialog(dialog)
    }

    /**
     * Keeps the positive button (and so submitting at all) disabled unless the
     * typed name is non-blank and doesn't collide with an existing file -
     * mirroring ScriptOperations.validateInput, whose protection was lost when
     * this feature took over the name prompt.
     */
    private fun validateScriptName(dialog: MaterialDialog, input: CharSequence) {
        val editText = dialog.inputEditText ?: return
        val positiveButton = dialog.getActionButton(DialogAction.POSITIVE)
        val fileName = scriptFileNameFor(input.toString())
        if (fileName == null) {
            // Blank: just don't let it through, no error text needed.
            positiveButton?.isEnabled = false
            return
        }
        if (File(Pref.getScriptDirPath(), fileName).exists()) {
            editText.error = mContext.getString(R.string.text_file_exists)
            positiveButton?.isEnabled = false
        } else {
            editText.error = null
            positiveButton?.isEnabled = true
        }
    }

    /**
     * The script file name a raw input would be saved under, or null if the
     * input is blank. Shared by the input validator and the save itself so the
     * name that gets checked is always the name that gets written.
     *
     * Path separators would move the script out of the directory its templates
     * were copied into, breaking the relative template references.
     */
    private fun scriptFileNameFor(input: String): String? {
        val name = input.trim().replace(PATH_SEPARATORS, "_")
        if (name.isEmpty()) return null
        return if (name.endsWith(".js", ignoreCase = true)) name else "$name.js"
    }

    /** Returns true only if the script file was actually written to disk. */
    private fun saveScript(fileName: String, actions: List<SemanticAction>): Boolean {
        // ScriptOperations(context, null) targets Pref.getScriptDirPath(), so
        // that's the directory the script - and therefore its templates - lands
        // in. Templates are copied next to the script and referenced by bare
        // filename, which Auto.js resolves against the running script's own
        // directory; an absolute filesDir path would break on export/share and
        // on clearing app data.
        val scriptDir = File(Pref.getScriptDirPath())
        val target = File(scriptDir, fileName)
        // The dialog already refuses colliding names, but the file could have
        // appeared since the last keystroke - and createScriptFile would then
        // write nothing while leaving the pre-existing file in place, which the
        // post-write existence check below cannot tell from a success.
        if (target.exists()) return false
        val relocated = actions.map { action ->
            val staged = action.imageTemplatePath
            if (action.type != ActionType.CLICK_IMAGE || staged.isNullOrEmpty()) {
                action
            } else {
                val templateFileName = copyTemplateInto(scriptDir, staged)
                if (templateFileName != null) {
                    action.copy(imageTemplatePath = templateFileName)
                } else {
                    // Template lost; degrade to the fixed-coordinate fallback
                    // rather than emitting a step that can never load.
                    action.copy(type = ActionType.CLICK_BOUNDS, imageTemplatePath = null)
                }
            }
        }
        val script = ActionScriptGenerator.generate(relocated)
        // createScriptFile is void and only toasts on failure, so the only way
        // to know whether the write actually happened is to look.
        ScriptOperations(mContext, null).createScriptFile(target.path, script, false)
        return target.isFile
    }

    private fun copyTemplateInto(dir: File, stagedPath: String): String? = try {
        val source = File(stagedPath)
        if (!source.isFile) {
            null
        } else {
            dir.mkdirs()
            val target = File(dir, source.name)
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            target.name
        }
    } catch (e: Exception) {
        null
    }

    /** Deletes the staging-directory template files referenced by [actions]. */
    private fun discardTemplates(actions: List<SemanticAction>) {
        actions.asSequence()
            .filter { it.type == ActionType.CLICK_IMAGE }
            .mapNotNull { it.imageTemplatePath }
            .map { File(it) }
            // Only ever delete out of the recorder's own staging directory.
            .filter { it.parentFile?.name == TEMPLATE_STAGING_DIR_NAME }
            .forEach {
                try {
                    it.delete()
                } catch (e: Exception) {
                    // Best effort only.
                }
            }
    }

    private fun pickAndPlay() {
        mWindow?.collapse()
        val explorerView = ExplorerViewKt(mContext)
        explorerView.setExplorer(Explorers.workspace(), ExplorerDirPage.createRoot(Pref.getScriptDirPath()))
        explorerView.setDirectorySpanSize(2)
        val dialog = ThemeColorMaterialDialogBuilder(mContext)
            .title(R.string.text_run_script)
            .customView(explorerView, false)
            .positiveText(R.string.cancel)
            .build()
        explorerView.setOnItemOperatedListener { dialog.dismiss() }
        explorerView.setOnItemClickListener { _, item ->
            item?.let { Scripts.run(it.toScriptFile()) }
            dialog.dismiss()
        }
        DialogUtils.showDialog(dialog)
    }

    fun close() {
        mSpeechRecognizer?.destroy()
        mSpeechRecognizer = null
        mWalkieTalkieHandler.removeCallbacks(mWalkieTalkieSafetyStopRunnable)
        GlobalKeyObserver.getSingleton().removeVolumeUpHoldListener(mVolumeUpHoldListener)
        // A turn still in flight when the bubble closes has no bubble left to
        // report into: flag it stale (see mVoiceSessionEpoch), kill the script
        // it may have driving the screen, and interrupt the executor rather
        // than letting shutdown() drain its queue to completion.
        mVoiceSessionEpoch++
        forceStop(mInFlightExecution)
        voiceTurnExecutor.shutdownNow()
        // The recorder is a process-wide singleton: hiding the bubble mid
        // recording would otherwise leave it recording forever into a list
        // nobody drains.
        if (mRecorder.state == SemanticRecorder.State.RECORDING) {
            // Nothing useful can be done with a recording abandoned by hiding
            // the bubble, but its staged templates still need cleaning up.
            discardTemplates(mRecorder.stop())
        }
        try {
            mWindow?.close()
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
        }
    }

    companion object {
        private const val TEMPLATE_STAGING_DIR_NAME = "agent_templates"
        private val PATH_SEPARATORS = Regex("[/\\\\]")

        /**
         * How long one voice turn's script is given to finish. The script's
         * own internal polling already times out after 10s (FIND_TIMEOUT_MS
         * in ActionScriptGenerator); this leaves headroom for script startup.
         */
        private const val VOICE_TURN_EXECUTION_TIMEOUT_MS = 15_000L

        /**
         * The walkie-talkie's hard safety cap on how long it keeps listening
         * after a confirmed hold, independent of whether the key-release event
         * is ever detected. Also used as the suppressed silence-detection
         * window passed to RecognizerIntent, so a mid-sentence pause doesn't
         * trigger Android's own default silence-based stop before this does.
         */
        private const val WALKIE_TALKIE_MAX_HOLD_MILLIS = 30_000L

        private var instance: AgentBubble? = null

        fun show(context: Context) {
            if (instance != null) return
            val appContext = context.applicationContext
            appContext.startService(Intent(appContext, FloatyService::class.java))
            // Safety net for callers that don't check the overlay permission
            // themselves, matching FloatyWindowManger.addWindow().
            if (!FloatingPermission.ensurePermissionGranted(appContext)) return
            instance = AgentBubble(appContext)
        }

        fun hide() {
            instance?.close()
            instance = null
        }

        fun isShowing(): Boolean = instance != null
    }
}
