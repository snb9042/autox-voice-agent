# Volume-Up Walkie-Talkie Mode — Design Spec

Status: approved (Sub-project 2 of the voice-agent upgrade program)
Date: 2026-11-09

## Context

This is the second upgrade in the program started by
`2026-11-09-bubble-movability-and-visibility-design.md`. The voice agent
(`2026-11-08-live-voice-agent-design.md`) currently has one way to start a
spoken instruction: tap the bubble's mic icon, speak, and Android's own
silence detection ends the recording after a couple of seconds of quiet.
This adds a second, hold-to-talk trigger on the hardware volume-up key,
alongside (not replacing) the mic button, with a much longer listening
window driven by how long the key is held rather than by silence
detection.

Grounded against the real code before designing:

- `GlobalKeyObserver` (`app/src/main/java/org/autojs/autojs/autojs/key/GlobalKeyObserver.java`)
  already owns all global volume-key handling. It listens on two parallel
  paths - an `AccessibilityService`-delivered `onKeyEvent(keyCode, KeyEvent)`
  and a root-shell `ShellKeyObserver`-delivered `onKeyUp(String keyName)` -
  and de-duplicates between them with `mVolumeUpFromShell`/
  `mVolumeUpFromAccessibility` flags, since the same physical key press can
  otherwise be reported by both paths.
- It only reacts to a key being **released** (`KeyEvent.ACTION_UP`, or the
  shell path's `onKeyUp`) - there is no existing press-and-hold detection
  anywhere in this class. `onKeyDown(String keyName)` is already declared
  (the `ShellKeyObserver.KeyListener` interface requires it) but is an empty
  no-op today, and `onKeyEvent` returns immediately for anything that isn't
  `ACTION_UP`.
- Volume-up **already has a real, existing, user-facing function**:
  `onVolumeUp()` calls `AutoJs.getInstance().getScriptEngineService().stopAllAndToast()`
  when `Pref.isRunningVolumeControlEnabled()` is true - this is the drawer's
  "Volume up key stops all scripts" toggle. Volume-down has a parallel
  existing function (start/stop recording, via a separate toggle and a
  listener interface, `OnVolumeDownListener`, that `GlobalKeyObserver`
  already exposes via `addVolumeDownListener`/`removeVolumeDownListener`).
  Neither existing feature may be broken or silently overridden by this one.
- `GlobalKeyObserver.init()` (which creates the lazy singleton and wires
  both input paths) is currently only called from two places - `App.kt`'s
  startup, gated behind `Pref.isRunningVolumeControlEnabled()`, and
  `Pref.java`'s preference-change listener, which re-inits if the user
  toggles on *either* of the two existing volume-control settings at
  runtime. If neither existing setting is enabled, `GlobalKeyObserver` is
  never initialized and no volume key presses reach the app at all - this
  feature must ensure it gets initialized regardless of those two settings'
  state, since the user should not have to enable an unrelated feature to
  get the walkie-talkie working.

## Goal

Holding the volume-up key down starts listening; speaking continues to be
captured for as long as the key is held (not cut short by a brief pause,
unlike the mic button); releasing the key ends the instruction and sends it
through the exact same describe-screen → plan → execute pipeline the mic
button already uses. A quick tap (press and release faster than the hold
threshold) continues to do exactly what it does today - nothing new is
triggered, and the existing "stop all scripts" behavior (when that setting
is enabled) is completely unaffected.

## Decisions

- **Hold vs. tap, not a mode switch.** No new setting to turn walkie-talkie
  "on" - holding volume-up always does this, tapping it always does what it
  already does. This was the user's explicit choice over gating it behind
  disabling the existing "stop all scripts" setting.
- **400ms hold threshold**, confirmed by a 400ms-delayed check rather than
  measuring duration after the fact. On key-down, a timer is scheduled for
  400ms later; if the key is released before that timer fires, nothing new
  happens (it was a tap - cancel the timer, let the existing tap behavior
  proceed unchanged). If the timer fires while the key is still down, *that
  is the moment* listening starts - never retroactively, so there's no need
  to cancel or discard a speech recognizer that already started capturing
  audio. The practical effect matches how a real walkie-talkie feels: press,
  a brief beat, then speak.
- **Release-driven stop, not silence-driven, with a 30-second hard cap.**
  Android's built-in "stop after you go quiet" behavior is suppressed for
  this path (via `RecognizerIntent`'s silence-length extras) so a mid-sentence
  pause doesn't cut the instruction short. Listening ends when the key is
  released (`recognizer.stopListening()`), or at a 30-second safety cap if
  release is somehow never detected - the user's chosen listening-window
  length.
- **A later sub-project extends this, not this one.** The user wants an
  additional ~30-second window when the AI needs to ask a clarifying
  question - that depends on the clarification feature
  (`2026-11-09-*-ai-clarification-*`, not yet built) existing first, and
  should apply uniformly to both the mic button and the walkie-talkie once
  built, not be hard-coded into this sub-project. Out of scope here.
- **`GlobalKeyObserver` gets initialized independent of the two existing
  volume-control settings.** `AgentBubble`'s own setup calls
  `GlobalKeyObserver.init()` unconditionally (idempotent - the class is
  already a lazily-created singleton, so an extra call site is harmless)
  rather than this feature depending on an unrelated toggle being on.

## Components

### `app/src/main/java/org/autojs/autojs/autojs/key/GlobalKeyObserver.java`

- New hold-tracking: both `onKeyEvent` (extended to also observe
  `ACTION_DOWN`, which it currently ignores entirely) and `onKeyDown(String
  keyName)` (currently a no-op) record a volume-up down timestamp, with the
  same from-shell/from-accessibility de-duplication pattern already used for
  the up-events, so a single physical press-and-hold is only tracked once
  regardless of which path reports it first.
- A new listener interface (`OnVolumeUpHoldListener`, mirroring the existing
  `OnVolumeDownListener` shape) with two callbacks - one fired when the
  400ms hold is confirmed (still held at the timer), one fired on release
  *only if* the hold was confirmed (a plain tap fires neither). `AgentBubble`
  registers for these the same way it would for `OnVolumeDownListener`.
- The existing `onVolumeUp()` (tap → stop-all-scripts-if-enabled) is
  unchanged in behavior and continues to fire exactly when it does today -
  on a release that *didn't* cross the hold threshold.

### `app/src/main/java/org/autojs/autojs/ui/agent/AgentBubble.kt`

- Calls `GlobalKeyObserver.init()` during its own setup and registers an
  `OnVolumeUpHoldListener`.
- On hold-confirmed: the same mutual-exclusion and permission/API-key checks
  `startVoiceTurn()` already runs (recording active? voice session already
  active? accessibility service enabled? API key set? `RECORD_AUDIO`
  granted?) apply identically here - a held volume-up key is just a second
  way to begin the exact same kind of turn, not a different kind of turn.
  Starts a `SpeechRecognizer` configured with suppressed silence-detection
  (long `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`/
  `EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS`) and a
  30-second safety-cap timer.
- On release-after-hold: calls `recognizer.stopListening()`, which lets the
  recognizer finish processing whatever was captured and deliver it through
  its normal `onResults` callback - from there, the exact same
  `runVoiceTurn(instruction)` pipeline the mic button already uses takes
  over unchanged.

## Permissions

No new permissions - `RECORD_AUDIO` is already requested on first mic use
and applies equally here (the walkie-talkie path reuses the exact same
permission check `startVoiceTurn()` already performs).

## Error Handling

All of the mic button's existing error paths apply identically (no API key
→ toast and do nothing; recording active → do nothing; accessibility
service not enabled → prompt for it). One new case: if the 400ms hold is
confirmed but the recognizer fails to start (e.g. another app is using the
microphone), the failure toast is the same one the mic button already shows
on recognition error - no new user-facing error states invented for this
path.

## Testing

Hardware-key hold/release timing, dual-path de-duplication, and real
`SpeechRecognizer` behavior have no meaningful unit-test surface - verified
manually on-device, consistent with how the rest of the bubble and voice
agent's Android-dependent behavior has been checked throughout this
program.

## Relationship to Other Sub-projects

Purely additive to `AgentBubble`'s existing voice-turn pipeline - the
describe-screen → plan → execute → accumulate-into-session logic from
`2026-11-08-live-voice-agent-design.md` is reused completely unchanged, only
how a turn is *triggered* and *when it ends* differs. Does not touch
`CircularMenuWindow`/bubble visuals (sub-project 1). Explicitly sets up the
hook the next sub-project (AI clarification) will extend to give a longer
follow-up listening window - but does not build that extension itself.
