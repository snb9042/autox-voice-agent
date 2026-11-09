# Volume-Up Walkie-Talkie Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Holding the volume-up key starts a voice instruction (listening continues for as long as it's held, not cut short by a pause); releasing it ends the instruction and sends it through the voice agent's existing pipeline. A quick tap keeps doing exactly what it does today.

**Architecture:** `GlobalKeyObserver` gains hold-vs-tap detection (a 400ms confirm timer) and a new listener interface; `AgentBubble` registers for it and reuses its existing `listenForInstruction()`/`runVoiceTurn()` pipeline, parameterized to suppress Android's silence-based auto-stop and add a 30-second safety cap.

**Tech Stack:** Java/Kotlin, Android `KeyEvent`/`SpeechRecognizer`/`RecognizerIntent`/`Handler`.

**Spec:** `docs/superpowers/specs/2026-11-09-volume-up-walkie-talkie-design.md`

## Global Constraints

- Do not change the existing tap behavior for volume-up (`stopAllAndToast()` when `Pref.isRunningVolumeControlEnabled()` is true) or for volume-down (`OnVolumeDownListener`/recording control) in any way.
- `GlobalKeyObserver` has a pre-existing bug, confirmed during planning: in `onKeyEvent`'s `KEYCODE_VOLUME_DOWN` branch, the accessibility-path dedup flag it sets is `mVolumeUpFromAccessibility` (should almost certainly be `mVolumeDownFromAccessibility`) — meaning `mVolumeDownFromAccessibility` is checked in `onKeyUp` but never actually set `true` anywhere, so the shell/accessibility dedup for volume-*down* releases is broken today. **Do not fix this as part of this plan** — it's a pre-existing, unrelated bug (volume-down, not volume-up) and fixing it is out of scope; do not copy this mismatched-variable pattern into the new volume-up-down-edge code either.
- Both new listener callbacks (`onVolumeUpHoldStart`/`onVolumeUpHoldEnd`) must always be invoked on the main thread — confirmed during planning that `AccessibilityService.onKeyEvent` dispatches via a background `eventExecutor`, and the shell input path's `ShellKeyObserver.onInputEvent` also runs off-main, while `EventDispatcher.dispatchEvent` (confirmed by reading its source) is a plain synchronous iteration with no thread hop of its own. `SpeechRecognizer` requires the main thread, so `GlobalKeyObserver` itself must be the one guaranteeing this via its `Handler(Looper.getMainLooper())`, not `AgentBubble`.

---

### Task 1: Hold-vs-tap detection in `GlobalKeyObserver`

**Files:**
- Modify: `app/src/main/java/org/autojs/autojs/autojs/key/GlobalKeyObserver.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `public interface OnVolumeUpHoldListener { void onVolumeUpHoldStart(); void onVolumeUpHoldEnd(); }`, `public void addVolumeUpHoldListener(OnVolumeUpHoldListener listener)`, `public boolean removeVolumeUpHoldListener(OnVolumeUpHoldListener listener)` — Task 2 registers/unregisters with these and implements both callback methods, guaranteed to be invoked on the main thread.

This task has no Android-framework dependency beyond `Handler`/`Looper`/`KeyEvent`, all already imported transitively or straightforward to add, and no isolated unit test (hardware-key timing has no test surface) — verified by compile plus Task 3's on-device check.

- [ ] **Step 1: Add the new imports, fields, and interface**

At the top of `GlobalKeyObserver.java`, add to the existing imports:

```java
import android.os.Handler;
import android.os.Looper;
```

Add the new public interface, directly below the existing `OnVolumeDownListener` interface (around line 24):

```java
    public interface OnVolumeUpHoldListener {
        void onVolumeUpHoldStart();
        void onVolumeUpHoldEnd();
    }
```

Add new fields, directly below the existing `mVolumeUpFromShell, mVolumeUpFromAccessibility;` field (around line 32):

```java
    // Tracks the DOWN edge of volume-up specifically, separately from the
    // existing mVolumeUpFromShell/mVolumeUpFromAccessibility pair above
    // (which dedup the UP edge) - deliberately not reusing or mirroring the
    // KEYCODE_VOLUME_DOWN branch's existing mVolumeUpFromAccessibility
    // mismatch below, which is a pre-existing bug left untouched here.
    private boolean mVolumeUpDownFromShell, mVolumeUpDownFromAccessibility;
    private boolean mVolumeUpHoldConfirmed;
    private static final long VOLUME_UP_HOLD_THRESHOLD_MILLIS = 400;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mVolumeUpHoldConfirmRunnable = this::onVolumeUpHoldConfirmed;
    private final EventDispatcher<OnVolumeUpHoldListener> mVolumeUpHoldEventDispatcher = new EventDispatcher<>();
    private static final EventDispatcher.Event<OnVolumeUpHoldListener> VOLUME_UP_HOLD_START_EVENT = OnVolumeUpHoldListener::onVolumeUpHoldStart;
    private static final EventDispatcher.Event<OnVolumeUpHoldListener> VOLUME_UP_HOLD_END_EVENT = OnVolumeUpHoldListener::onVolumeUpHoldEnd;
```

- [ ] **Step 2: Add the listener registration methods**

Directly below the existing `removeVolumeDownListener` method (around line 71):

```java
    public void addVolumeUpHoldListener(OnVolumeUpHoldListener listener) {
        mVolumeUpHoldEventDispatcher.addListener(listener);
    }

    public boolean removeVolumeUpHoldListener(OnVolumeUpHoldListener listener) {
        return mVolumeUpHoldEventDispatcher.removeListener(listener);
    }
```

- [ ] **Step 3: Add the down-edge handling and hold-confirm callback**

Directly below the existing `onVolumeDown()` method (around line 63):

```java
    private void onVolumeUpDown() {
        mVolumeUpHoldConfirmed = false;
        mHandler.removeCallbacks(mVolumeUpHoldConfirmRunnable);
        mHandler.postDelayed(mVolumeUpHoldConfirmRunnable, VOLUME_UP_HOLD_THRESHOLD_MILLIS);
    }

    private void onVolumeUpHoldConfirmed() {
        mVolumeUpHoldConfirmed = true;
        mVolumeUpHoldEventDispatcher.dispatchEvent(VOLUME_UP_HOLD_START_EVENT);
    }
```

- [ ] **Step 4: Branch the existing `onVolumeUp()` release handler on whether a hold was confirmed**

Replace the existing `onVolumeUp()` method:

```java
    public void onVolumeUp() {
        Log.d(LOG_TAG, "onVolumeUp at " + System.currentTimeMillis());
        if (Pref.isRunningVolumeControlEnabled()) {
            AutoJs.getInstance().getScriptEngineService().stopAllAndToast();
        }
    }
```

with:

```java
    public void onVolumeUp() {
        Log.d(LOG_TAG, "onVolumeUp at " + System.currentTimeMillis());
        mHandler.removeCallbacks(mVolumeUpHoldConfirmRunnable);
        if (mVolumeUpHoldConfirmed) {
            mVolumeUpHoldConfirmed = false;
            // Route through the main-thread handler even though this method
            // itself may already be running off-main (see this plan's Global
            // Constraints) - dispatchEvent is a plain synchronous loop with no
            // thread hop of its own, and the listener's SpeechRecognizer calls
            // require the main thread.
            mHandler.post(() -> mVolumeUpHoldEventDispatcher.dispatchEvent(VOLUME_UP_HOLD_END_EVENT));
            return;
        }
        if (Pref.isRunningVolumeControlEnabled()) {
            AutoJs.getInstance().getScriptEngineService().stopAllAndToast();
        }
    }
```

- [ ] **Step 5: Wire the down-edge into both existing key-event paths**

Replace the existing `onKeyEvent` method:

```java
    @Override
    public void onKeyEvent(int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_UP)
            return;
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (mVolumeDownFromShell) {
                mVolumeDownFromShell = false;
                return;
            }
            mVolumeUpFromAccessibility = true;
            onVolumeDown();
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (mVolumeUpFromShell) {
                mVolumeUpFromShell = false;
                return;
            }
            mVolumeUpFromAccessibility = true;
            onVolumeUp();
        }
    }
```

with:

```java
    @Override
    public void onKeyEvent(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (mVolumeUpDownFromShell) {
                mVolumeUpDownFromShell = false;
                return;
            }
            mVolumeUpDownFromAccessibility = true;
            onVolumeUpDown();
            return;
        }
        if (event.getAction() != KeyEvent.ACTION_UP)
            return;
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (mVolumeDownFromShell) {
                mVolumeDownFromShell = false;
                return;
            }
            mVolumeUpFromAccessibility = true;
            onVolumeDown();
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (mVolumeUpFromShell) {
                mVolumeUpFromShell = false;
                return;
            }
            mVolumeUpFromAccessibility = true;
            onVolumeUp();
        }
    }
```

(Note: the `mVolumeUpFromAccessibility = true;` line inside the `KEYCODE_VOLUME_DOWN` branch is the pre-existing bug called out in Global Constraints - left exactly as-is, not part of this change.)

Replace the existing `onKeyDown` method:

```java
    @Override
    public void onKeyDown(String keyName) {

    }
```

with:

```java
    @Override
    public void onKeyDown(String keyName) {
        if ("KEY_VOLUMEUP".equals(keyName)) {
            if (mVolumeUpDownFromAccessibility) {
                mVolumeUpDownFromAccessibility = false;
                return;
            }
            mVolumeUpDownFromShell = true;
            onVolumeUpDown();
        }
    }
```

- [ ] **Step 6: Compile to verify**

Run: `./gradlew :app:compileCommonDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/autojs/autojs/autojs/key/GlobalKeyObserver.java
git commit -m "feat(key): add volume-up hold detection alongside the existing tap behavior"
```

---

### Task 2: Wire the walkie-talkie into `AgentBubble`

**Files:**
- Modify: `app/src/main/java/org/autojs/autojs/ui/agent/AgentBubble.kt`

**Interfaces:**
- Consumes: `GlobalKeyObserver.init()`, `GlobalKeyObserver.getSingleton().addVolumeUpHoldListener(...)`/`removeVolumeUpHoldListener(...)`, `GlobalKeyObserver.OnVolumeUpHoldListener` (Task 1) - all confirmed to invoke on the main thread.
- Produces: no new public API - purely new private behavior on the existing `AgentBubble` class.

No isolated unit test (real `SpeechRecognizer`/hardware-key behavior) - verified by compile plus Task 3's on-device check.

- [ ] **Step 1: Add the new import**

Add to `AgentBubble.kt`'s existing imports:

```kotlin
import android.os.Handler
import android.os.Looper
import org.autojs.autojs.autojs.key.GlobalKeyObserver
```

- [ ] **Step 2: Add the new fields**

Add alongside the existing `mSpeechRecognizer` field (around line 96):

```kotlin
    private var mSpeechRecognizer: SpeechRecognizer? = null
    private val mWalkieTalkieHandler = Handler(Looper.getMainLooper())
    private val mWalkieTalkieSafetyStopRunnable = Runnable { mSpeechRecognizer?.stopListening() }
    private val mVolumeUpHoldListener = object : GlobalKeyObserver.OnVolumeUpHoldListener {
        override fun onVolumeUpHoldStart() = this@AgentBubble.onVolumeUpHoldStart()
        override fun onVolumeUpHoldEnd() = this@AgentBubble.onVolumeUpHoldEnd()
    }
```

Add a new constant in the existing `companion object` block, alongside `VOICE_TURN_EXECUTION_TIMEOUT_MS`:

```kotlin
        /**
         * The walkie-talkie's hard safety cap on how long it keeps listening
         * after a confirmed hold, independent of whether the key-release event
         * is ever detected. Also used as the suppressed silence-detection
         * window passed to RecognizerIntent, so a mid-sentence pause doesn't
         * trigger Android's own default silence-based stop before this does.
         */
        private const val WALKIE_TALKIE_MAX_HOLD_MILLIS = 30_000L
```

- [ ] **Step 3: Register the listener during setup**

In `initFloaty()`, add after the existing `FloatyService.addWindow(mWindow)` line (around line 151):

```kotlin
        FloatyService.addWindow(mWindow)
        GlobalKeyObserver.init()
        GlobalKeyObserver.getSingleton().addVolumeUpHoldListener(mVolumeUpHoldListener)
```

- [ ] **Step 4: Parameterize `listenForInstruction` for the walkie-talkie's extended listening window**

Replace the existing `listenForInstruction()` method:

```kotlin
    /** Must be called on the main thread - SpeechRecognizer requires it. */
    private fun listenForInstruction() {
        AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_listening))
        val recognizer = SpeechRecognizer.createSpeechRecognizer(mContext).also { mSpeechRecognizer = it }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                recognizer.destroy()
                mSpeechRecognizer = null
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) {
                    AutoJs.getInstance().uiHandler.toast(mContext.getString(R.string.text_agent_no_speech))
                } else {
                    runVoiceTurn(text)
                }
            }

            override fun onError(error: Int) {
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
        }
        recognizer.startListening(intent)
    }
```

with:

```kotlin
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
    private fun listenForInstruction(walkieTalkie: Boolean = false) {
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
                    runVoiceTurn(text)
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
```

- [ ] **Step 5: Add the hold-start and hold-end handlers**

Add directly after `listenForInstruction`:

```kotlin
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
```

- [ ] **Step 6: Deregister the listener on close**

In `close()`, add after the existing `mSpeechRecognizer = null` line (around line 646):

```kotlin
    fun close() {
        mSpeechRecognizer?.destroy()
        mSpeechRecognizer = null
        mWalkieTalkieHandler.removeCallbacks(mWalkieTalkieSafetyStopRunnable)
        GlobalKeyObserver.getSingleton().removeVolumeUpHoldListener(mVolumeUpHoldListener)
```

(The rest of `close()` is unchanged.)

- [ ] **Step 7: Compile to verify**

Run: `./gradlew :app:compileCommonDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/org/autojs/autojs/ui/agent/AgentBubble.kt
git commit -m "feat(agent): add volume-up walkie-talkie mode alongside the mic button"
```

---

### Task 3: Full build verification and manual on-device check

**Files:** none (verification only).

- [ ] **Step 1: Run the full unit test suite**

Run: `./gradlew :autojs:testDebugUnitTest :app:testCommonDebugUnitTest`
Expected: BUILD SUCCESSFUL - this plan introduces no new tests and should not regress any existing one.

- [ ] **Step 2: Assemble the debug APK**

Run: `./gradlew :app:assembleCommonDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual on-device verification**

With the Smart Recorder Bubble shown and a Claude API key configured:

1. Quickly press and release volume-up (faster than ~400ms). Confirm nothing voice-related happens - if "Volume up key stops all scripts" is enabled, confirm that still fires exactly as before; if it's disabled, confirm nothing happens at all.
2. Press and hold volume-up for over a second, then speak a short instruction (e.g. "scroll down"), then release. Confirm a "Listening..." toast appears, the instruction is captured even across a brief pause mid-sentence, and releasing ends listening and performs the action - same as the mic button's result, just triggered differently.
3. While holding volume-up and speaking, pause for 3-4 seconds mid-sentence (longer than the mic button's own silence cutoff) before continuing to speak. Confirm it does NOT get cut off early - verifies the suppressed-silence-detection is actually taking effect, not just a cosmetic config no-op.
4. Hold volume-up, say nothing, and simply wait without releasing. Confirm listening automatically stops within ~30 seconds even without a release (safety cap).
5. Start a recording (bubble Record), then try holding volume-up. Confirm nothing happens (recording takes priority, matching the mic button's own existing mutual-exclusion).
6. Tap the mic button to start a turn, then (before it resolves) try holding volume-up. Confirm nothing happens (an active recognizer blocks a second one, matching the mic button's own existing guard against itself).

If any check fails, return to the relevant task, fix the issue, re-run its build step, and re-check here before considering this plan complete.
