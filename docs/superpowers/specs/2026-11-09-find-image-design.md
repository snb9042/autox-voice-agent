# "Find Image" — Design Spec

Status: approved (Sub-project 6 of the voice-agent upgrade program)
Date: 2026-11-09

## Context

This is the sixth and final planned upgrade in the program. "Tap on mark"
(`2026-11-09-tap-on-mark-design.md`) covers pointing at a one-off fixed
spot; this covers the other half of the original ask - a *reusable, visual*
target: draw a box around something, and the agent can find and tap that
same visual pattern again, even if it has moved (scrolled, resized,
re-opened).

Grounded against the real code before designing:

- `ActionType.CLICK_IMAGE` and its codegen (`ActionScriptGenerator.clickImageStep`,
  read in full during planning) already exist from the original recorder
  work, and are already fully adaptive in exactly the sense this feature
  needs: the generated script calls `images.captureScreen()` +
  `images.findImage(...)` fresh every time it runs, polling up to
  `FIND_TIMEOUT_MS` - it does **not** use `SemanticAction.bounds` at all,
  only `imageTemplatePath`. The original capture position is irrelevant to
  where the step actually taps at execution time. No codegen work is needed
  for this sub-project - it is pure UI work plus reusing existing capture
  infrastructure.
- `ActionScriptGenerator.generate()` already inserts the
  `images.requestScreenCapture()` guard automatically whenever any action in
  the list is `CLICK_IMAGE` - confirmed, no new wiring needed there either.
- `ScreenStateReader.captureTemplateAsync(service, bounds, onCaptured)`
  (confirmed signature from this session's earlier grounding) already does
  exactly what this feature needs for turning a marked region into a
  template file: requests screen-capture permission if needed, captures,
  crops to `bounds`, saves a PNG, and invokes `onCaptured(path)` (or `null`
  on failure) on its own background thread. This is the exact mechanism the
  recorder already uses for an unlabeled tap's `CLICK_IMAGE` fallback -
  reused here unchanged, not rebuilt.
- `ScreenStateReader.deleteTemplate(path)` already exists for cleaning up an
  orphaned template file - used here the same way.
- `TapOnMarkFloatyWindow` (just built) is the direct structural precedent:
  same `FullScreenFloatyWindow` base, same local-callsign-recognition entry
  point in `AgentBubble.listenForInstruction`'s `onTranscribed`, same
  raw-vs-local touch-coordinate handling, same back-to-cancel pattern. This
  sub-project's overlay differs only in capturing a dragged rectangle
  instead of a single tap.

## Goal

Say "find image", drag a rectangle around the thing you mean on the tinted
screen, and the agent captures that region as a reusable template, then
finds and taps it right now - and, since this becomes an ordinary action in
the voice session, finds and taps it again correctly whenever the saved
script is replayed later, even if it has moved.

## Decisions

- **Recognized locally, exactly like "tap on mark"** - a fixed phrase match
  ("find image") checked in the same place before an instruction would
  otherwise reach Claude. No AI involvement in capturing or matching the
  image at all.
- **Drag-to-draw, not tap-then-resize.** One continuous touch gesture: press,
  drag (the box grows live so you can see exactly what you're selecting),
  release. No separate confirm step, consistent with "tap on mark"'s choice
  to keep the single gesture final.
- **A too-small drag (effectively a tap, not a real rectangle) is expanded
  to a small default box** rather than silently failing - the same spirit as
  "tap on mark" never leaving the user with an unexplained no-op.
- **Reuses `CLICK_IMAGE` and existing capture machinery entirely** - no new
  image-matching, no new template storage mechanism, no new permission
  handling. The only new code is the drag-rectangle overlay itself and the
  glue connecting its output to the existing pipeline.
- **A failed capture or a failed immediate click deletes the template file**
  rather than leaving an orphaned PNG nobody will ever reference - using the
  existing `deleteTemplate`.
- **Stop (and closing the bubble) dismiss an open find-image overlay**,
  identical to "tap on mark".

## Components

### `app/src/main/java/org/autojs/autojs/ui/agent/FindImageFloatyWindow.kt` (new)

- Extends `FullScreenFloatyWindow`, structurally parallel to
  `TapOnMarkFloatyWindow`. Its view tracks `ACTION_DOWN`/`ACTION_MOVE`/
  `ACTION_UP` to draw a live-updating rectangle outline over the tint, using
  local coordinates for drawing and raw (true screen) coordinates - captured
  separately at both the down and up points - for the final region passed to
  the callback. `KEYCODE_BACK` cancels with no callback, identical to
  "tap on mark".

### `app/src/main/java/org/autojs/autojs/ui/agent/AgentBubble.kt`

- `listenForInstruction`'s `onTranscribed` default also checks
  `FindImageFloatyWindow.matchesCallsign` alongside the existing
  "tap on mark" check, before falling through to `runVoiceTurn`.
- On a marked region: calls `ScreenStateReader.captureTemplateAsync`, and on
  a successful capture, constructs `SemanticAction(ActionType.CLICK_IMAGE,
  bounds, imageTemplatePath = path)` and dispatches it through
  `voiceTurnExecutor` to `executeAction`, with the same session-epoch
  staleness guard every other turn already uses. A failed capture, or a
  failed `executeAction`, deletes the template file via `deleteTemplate` and
  reports the same kind of failure toast every other failed action already
  uses - neither is added to the session.
- A reference to the currently-open find-image overlay (if any) is tracked
  so `stopActive()`/`close()` can dismiss it, the same way the tap-on-mark
  overlay already is.

## Error Handling

Screen-capture permission denial surfaces through `captureTemplateAsync`
returning `null` (already how the recorder handles this case) - reported
with the same generic failure toast, no new error states.

## Testing

The overlay itself (real touch events, real window manager, real capture)
has no unit-test surface - verified manually on-device. The phrase-matching
function is pure and trivially unit-testable, the same way
`TapOnMarkFloatyWindow.matchesCallsign` already is.

## Relationship to Other Sub-projects

Completes the pair started by "tap on mark" (sub-project 5) - both are
manual-targeting overrides for when voice description, and even OCR
(sub-project 4), can't identify something. Reuses `CLICK_IMAGE` (part of the
original recorder work, untouched by this program until now) and the
existing template-capture infrastructure unchanged. This is the last planned
sub-project in the program.
