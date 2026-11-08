# Live Voice Agent — Design Spec

Status: approved (Spec 2 of 2)
Date: 2026-11-08

## Context

Spec 1 (`2026-11-04-smart-recorder-bubble-design.md`) built a floating bubble with
Record/Stop/Play/Stop-all-scripts, a resilient action model (`SemanticAction`), an
optimizer, and a script generator — all implemented, reviewed, and verified on-device.
Spec 1's own design doc flagged a long-term goal: a live, voice-controlled agent built
on top of that foundation. This spec covers that agent.

## Goal

Add a fifth control to the existing bubble — push-to-talk — so the user can speak a
single instruction ("click Login", "fill the email field with test@example.com",
"wait for the page to load", "scroll down"), have it reasoned into one concrete action
by Claude, and have that action performed immediately on the current screen. A running
voice session's performed actions accumulate the same way a recording's do, and can be
saved as a replayable script through the exact same Preview/Save/Discard flow Spec 1
already has.

## Decisions

- **Reuses Spec 1's pipeline per turn**, rather than a parallel direct-execution path
  or free-form LLM-generated code. Each turn builds a `SemanticAction`, renders it
  through the existing `ActionScriptGenerator`, and executes the resulting one-action
  script through the existing engine — reusing all of Spec 1's already-tested
  text/description matching, polling, and timeout logic instead of re-implementing it.
- **Text/description-only targeting.** The live agent has no vision input, so it can
  only target things Claude can read as text: accessibility-exposed text/content
  descriptions, plus OCR'd on-screen text (reusing the vendored `paddleocr` module) for
  labels with no accessible text. It does **not** do image-template matching the way
  recording does — there is no physical tap to crop a template from. An icon with no
  text and no OCR-readable label cannot be targeted by voice.
- **Explicit `wait` action.** Alongside click/fill/scroll, the action vocabulary
  includes `wait_for_text`/`wait_for_desc`: the agent polls the screen until the
  described text/description appears, then stops — no click. This lets "wait for the
  page to load" be its own spoken instruction, separate from "click Submit".
- **Push-to-talk, single action per turn, stay-in-the-loop.** Already decided before
  Spec 1 began; reaffirmed here. No always-on listening, no multi-step autonomous
  chains.
- **5th item on the existing bubble menu** (mic icon), not a second bubble.
- **No synthesized speech output in v1.** Feedback is visual/toast only ("Tapped
  'Login'" / "Couldn't find 'Login'").

## Components

### `autojs` module — extends `com.stardust.autojs.core.agent`

- **`ScreenStateReader.describeScreen()`** (new method on the existing class): walks
  the current accessibility tree collecting visible text/content-description nodes
  with bounds, plus an OCR pass over a screenshot for text with no accessible label.
  Returns one flat list of `(text, bounds)` entries — the same shape of information
  Claude needs to reason about what's clickable, regardless of whether a label came
  from the accessibility tree or OCR.
- **`ClaudeActionPlanner`** (new): wraps one Claude API call. Takes the screen
  description + the transcribed instruction, calls Claude using a tool-use schema
  shaped like `SemanticAction` (`click_text`, `click_desc`, `fill_text`, `fill_desc`,
  `long_click_text`, `long_click_desc`, `scroll`, `wait_for_text`, `wait_for_desc`),
  and returns one structured action (or a "couldn't determine an action" result).
- **`VoiceAgentSession`** (new): holds the running session's accumulated
  `SemanticAction` list (mirroring `SemanticRecorder`'s role for a live session) and
  exposes `addAction()`/`actions()`/`clear()`. Does not itself talk to Claude or
  the OS — purely the session's action-list bookkeeping, so it's trivially unit
  testable.

### `app` module — extends `org.autojs.autojs.ui.agent`

- **`AgentBubble`**: gains a 5th menu item (mic), wired to:
  1. Start `SpeechRecognizer` (push-to-talk: begin on tap, finalize on the
     recognizer's own end-of-speech or a second tap).
  2. On transcription: call `ScreenStateReader.describeScreen()` +
     `ClaudeActionPlanner` → one action → `SemanticAction` → `ActionScriptGenerator`
     → execute via the existing script engine.
  3. Append the performed action to the bubble's `VoiceAgentSession` (created lazily
     on first mic tap, cleared when the bubble's existing Stop flow runs).
  4. Report success/failure via toast.
  - Mic states: idle → listening (visual cue while capturing) → thinking (brief
    spinner during the Claude call + execution) → idle.
  - Tapping Stop (the existing control) ends the voice session the same way it ends a
    recording: runs `ActionOptimizer` + `ActionScriptGenerator` over the accumulated
    list and shows the existing Preview/Save/Discard summary dialog, unmodified.
  - If both a recording and a voice session could theoretically be "active," Record
    and the mic are mutually exclusive: starting one while the other is active is
    blocked (mirroring the double-start guard Spec 1 already has for Record), so the
    accumulated action list only ever reflects one mode at a time per session.
- **AI Agent Settings** (new drawer entry + settings screen): single API key input
  field, saved via this project's existing `Pref`-style local-storage pattern. If the
  mic is tapped with no key configured, a dialog prompts for it once, linking to this
  same screen.

## Permissions

No new OS-level permissions beyond what Spec 1 already requires (Accessibility
Service, overlay) plus Android's standard `RECORD_AUDIO` permission for
`SpeechRecognizer`, requested the same way any other runtime permission is in this
project — on first mic use, not upfront.

## Error handling

- **No API key configured:** prompt once, linking to AI Agent Settings; the turn does
  not proceed until a key is saved.
- **STT fails to transcribe:** toast "didn't catch that," no turn attempted.
- **Claude returns "couldn't determine an action":** toast surfacing that, no action
  executed, nothing added to the session.
- **Action's target isn't found when it actually runs** (the same timeout-then-error
  the generated script already raises): the turn reports "couldn't find X" via toast;
  the action is **not** added to the session list, so a failed turn never pollutes a
  saved script the way the self-click bug in Spec 1 did — this is the direct fix for
  that class of problem, enforced by only appending on confirmed success.
- **Record and mic both guarded against concurrent use** (see above).

## Testing

- `VoiceAgentSession`'s list bookkeeping and `ClaudeActionPlanner`'s
  request/response-to-`SemanticAction` mapping are plain Kotlin, unit-testable the same
  way Spec 1's `ActionOptimizer`/`ActionScriptGenerator` are (construct a fake Claude
  response, assert the resulting `SemanticAction`).
- `ScreenStateReader.describeScreen()`, `SpeechRecognizer` integration, the live Claude
  network call, and on-device execution are Android/network-dependent and verified
  manually — same posture as Spec 1's Android-dependent pieces.

## Relationship to Spec 1

Spec 2 adds no new recording concept and modifies no part of Spec 1's recording path
(`SemanticRecorder`, the self-package filter, the save/relocate flow) — it is purely
additive: a new way to populate the same `SemanticAction` list that Record already
populates, converging on the same Stop → optimize → Preview/Save/Discard flow.
