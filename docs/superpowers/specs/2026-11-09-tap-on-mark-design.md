# "Tap on Mark" — Design Spec

Status: approved (Sub-project 5 of the voice-agent upgrade program)
Date: 2026-11-09

## Context

This is the fifth upgrade in the program. Every voice action so far requires
Claude to identify a target from a text description of the screen. This adds
a manual override: say "tap on mark" (or "tap the mark"), the screen tints,
tap the exact point you mean, see an X confirm it, and that point gets
tapped - no AI involved in picking the target at all. This is for the case
voice description can't handle: an icon with no readable text and nothing
OCR can read either.

Grounded against the real code before designing:

- `FullScreenFloatyWindow` (`app/src/main/java/org/autojs/autojs/ui/floating/FullScreenFloatyWindow.java`)
  already exists - a `FloatyWindow` subclass whose `onCreateWindowLayoutParams()`
  returns a `MATCH_PARENT`/`MATCH_PARENT`, translucent, `FLAG_LAYOUT_NO_LIMITS`
  window. It's already used by this project's layout-bounds-inspector feature
  (`LayoutBoundsFloatyWindow`) for exactly the same shape of thing: a
  full-screen overlay that draws over the live screen and reacts to touches.
  This sub-project's overlay is a new subclass of the same base, not new
  window-management infrastructure.
- `LayoutBoundsView` (`app/src/main/java/org/autojs/autojs/ui/floating/layoutinspector/LayoutBoundsView.java`)
  is the precedent for this sub-project's own view: a plain `View` subclass
  with a `Paint` and a `MotionEvent`-driven `onDraw()`.
- `LayoutBoundsFloatyWindow`'s `onCreateView` overrides `dispatchKeyEvent` to
  treat `KEYCODE_BACK` as "close this overlay" - the same pattern this
  sub-project's overlay uses as its cancel affordance.
- `ActionScriptGenerator.clickBoundsStep` and `ActionType.CLICK_BOUNDS`
  already exist and are already reused by sub-project 4 (OCR routing) for
  exactly this shape of action - a bare-bounds, no-selector tap. This
  sub-project reuses that same mechanism again, a third time.
- `AgentBubble.listenForInstruction`'s `onTranscribed` callback (added in the
  walkie-talkie and clarification sub-projects) is the one place every
  transcribed instruction already passes through, regardless of whether it
  came from the mic button or the volume-up hold - the natural place to
  recognize this callsign before it would otherwise be sent to Claude.

## Goal

Say "tap on mark" (or "tap the mark"), tap a point on the tinted screen, see
it confirmed with an X, and that exact point gets tapped - added to the
current voice session as a normal successful action, just like any
AI-targeted one.

## Decisions

- **Recognized locally, not sent to Claude.** No screen description, no API
  call, no `reason`/clarification machinery - this is a fixed phrase match
  on the transcribed text, checked before `listenForInstruction`'s default
  `onTranscribed` would otherwise call `runVoiceTurn`. Faster, free, and not
  dependent on Claude correctly interpreting an instruction that was never
  meant for it in the first place.
- **Single tap, not drag-to-select-then-confirm.** The first tap on the
  tinted overlay is final - it's marked, shown, and acted on. No second
  "confirm" step. The back button is the cancel affordance if the overlay
  was opened by mistake or the user changes their mind before tapping.
- **Reuses `CLICK_BOUNDS` codegen entirely** - the marked point becomes
  `SemanticAction(ActionType.CLICK_BOUNDS, bounds)` (a small box centered on
  the tap point) and runs through the exact same `executeAction` → toast →
  `VoiceAgentSession.addAction` pipeline every other voice action already
  uses. No new execution, session, or script-generation code.
- **Stop (and closing the bubble) dismiss an open mark-overlay.** Consistent
  with every other in-flight voice interaction this program has built -
  Stop always means stop, including a mark-overlay that's still waiting for
  a tap.
- **No long-press-to-mark variant.** Only what was asked for - a tap.

## Components

### `app/src/main/java/org/autojs/autojs/ui/agent/TapOnMarkFloatyWindow.kt` (new)

- Extends `FullScreenFloatyWindow`. Its view: a tinted (semi-transparent
  dark) full-screen background; on the first `ACTION_UP` touch, draws an X
  centered at that point, then - after a short, fixed delay so the X is
  actually seen - invokes a callback with the tapped point's bounds and
  closes itself. `KEYCODE_BACK` closes the window immediately with no
  callback invocation (cancel).

### `app/src/main/java/org/autojs/autojs/ui/agent/AgentBubble.kt`

- `listenForInstruction`'s default `onTranscribed` recognizes "tap on mark"/
  "tap the mark" (case-insensitive, trimmed) before falling through to
  `runVoiceTurn` - matched instructions open the new overlay instead.
  Mutual-exclusion and accessibility-service guards mirror `startVoiceTurn`'s
  existing ones; no API-key or `RECORD_AUDIO` check is needed here (those
  already passed by the time a transcribed instruction exists at all).
- The overlay's callback constructs the `CLICK_BOUNDS` action and dispatches
  it through `voiceTurnExecutor` (executing a script blocks its caller, so
  this must not run on the main thread, same as every other action), with
  the same session-epoch staleness guard every other turn already uses.
- A reference to the currently-open mark-overlay (if any) is tracked so
  `stopActive()` can dismiss it, the same way it already force-stops an
  in-flight script execution.

## Error Handling

If the marked point's `CLICK_BOUNDS` execution fails (rare - a bounds-based
click only fails if the system drops the synthesized gesture), it reports
through the exact same failure toast every other failed action already uses.
No new error states.

## Testing

The overlay itself (real touch events, real window manager, real drawing)
has no unit-test surface - verified manually on-device. The phrase-matching
function is pure and trivially unit-testable.

## Relationship to Other Sub-projects

Reuses `CLICK_BOUNDS` (already exercised by sub-project 4's OCR routing) and
the existing execute/session/epoch machinery unchanged. Independent of the
bubble's visuals (sub-project 1), the walkie-talkie trigger (sub-project 2,
though its transcribed text flows through the same recognition point this
sub-project hooks into), the clarification loop (sub-project 3, which this
sub-project deliberately bypasses), and OCR (sub-project 4). The next
sub-project ("find image") is a related but distinct targeting strategy - a
reusable, adaptive visual match rather than this sub-project's one-off fixed
point.
