# Bubble Movability and Visibility — Design Spec

Status: approved (Sub-project 1 of the voice-agent upgrade program)
Date: 2026-11-09

## Context

This is the first of several planned upgrades to the voice agent built in
`2026-11-04-smart-recorder-bubble-design.md` and
`2026-11-08-live-voice-agent-design.md`. After live device testing, the
bubble itself (not the recorder/voice logic) turned out to have three real
usability problems, diagnosed against the actual running code rather than
guessed:

1. **Dragging doesn't stick.** `CircularMenuWindow.setInitialState()`
   (`app/src/main/java/org/autojs/autojs/ui/floating/CircularMenuWindow.java`)
   reads back only the saved Y position (`KEY_POSITION_Y`) on startup — X is
   never restored, even though `close()` saves both `KEY_POSITION_X` and
   `KEY_POSITION_Y` correctly. Every relaunch, X resets to whatever the
   "keep to side" snap decides.
2. **It shrinks to a barely-visible sliver.** `keepToSide()` calls
   `BounceDragGesture.keepToEdge()`, which docks the bubble against the
   nearest screen edge and hides a fraction of its width
   (`mKeepToSideHiddenWidthRadio`) — inherited, by design, from the generic
   `enhancedfloaty` window library this project vendors.
3. **It's nearly invisible against light-colored apps.** When not actively
   being touched, `CircularMenuWindow` sets the bubble's alpha to
   `mInactiveAlpha = 0.4F`. A 40%-opacity icon with no outline blends into a
   white or light app background.

`CircularMenuWindow` is shared: the original AutoX.js "Floating window"
feature (the toggle above "Smart Recorder Bubble" in the drawer) uses the
exact same class and the exact same `SharedPreferences` position keys. The
user explicitly chose to fix the shared class rather than fork a separate
implementation — both bubbles get the fix, since both have the same bugs
today.

## Goal

The Smart Recorder Bubble (and, as a side effect of sharing code, the
legacy Floating window bubble) should:
- Stay exactly where it's dragged, across app relaunches and orientation
  changes.
- Never shrink to a partially-hidden sliver — always a full, complete
  circle, whether mid-screen or resting against an edge.
- Be clearly visible against any app background, light or dark.

## Decisions

- **Fix the shared `CircularMenuWindow` class directly**, not a forked
  subclass. Both bubble features have identical bugs today; a shared fix is
  less code and a strict improvement for both. (User's explicit choice,
  weighed against the alternative of a separate implementation that would
  leave the legacy Floating window bubble's bugs untouched.)
- **Keep edge-snapping on orientation change, drop the partial-hide.**
  `keepToSide()` still re-docks the bubble to the nearest horizontal edge
  when the device rotates (useful — keeps it out of the way of rotated
  content), but `mKeepToSideHiddenWidthRadio` becomes `0`, so docking never
  hides any part of the circle.
- **Raise inactive alpha and add a visual outline**, rather than only one
  or the other. Alpha alone (even at something like 0.9) still risks
  blending into a near-white background at certain icon colors; an outline
  alone doesn't address the literal 40%-transparency complaint. Both
  together make "always visible" true regardless of what's behind it.
- **No lock-screen visibility.** Explicitly out of scope — confirmed with
  the user. The bubble continues to behave as it does today: visible over
  every unlocked app, gone at the lock screen. Adding lock-screen overlay
  support would need a different permission class and was not requested.

## Components

### `app/src/main/java/org/autojs/autojs/ui/floating/CircularMenuWindow.java`

- `setInitialState()`: read back `KEY_POSITION_X` in addition to the
  existing `KEY_POSITION_Y`, and call
  `mActionViewWindowBridge.updatePosition(x, y)` with both restored values
  before `keepToSide()` runs (not after — the restored position is what
  should be gently edge-snapped, not overridden by it).
- `mKeepToSideHiddenWidthRadio`: changed from whatever `AgentBubble.kt`
  currently sets (if anything — confirmed during planning) to `0f`, so
  `BounceDragGesture.keepToEdge()`'s partial-hide behavior never triggers.
- `mInactiveAlpha`: raised from `0.4F` to a value confirmed during planning
  to read clearly against both light and dark app content in an actual
  on-device check (expected to land around 0.85–0.95 — exact value is an
  implementation/testing detail, not a product decision, since the spec's
  binding requirement is "always visible," not a specific number).

### Bubble icon drawable(s)

Whichever drawable resource renders the bubble's idle (collapsed) circular
icon gets an outline or drop-shadow treatment added, so the shape itself is
legible by its edge even where the fill color and the app behind it are
close in luminance. The exact resource(s) and mechanism (a stroke in the
vector drawable vs. a system `elevation`/shadow) is an implementation
decision made during planning, grounded in whatever the current drawable
actually is.

## Error Handling

None of this introduces new failure modes — it changes read/restore
behavior for a value already being persisted, a numeric threshold, and a
drawable's visual treatment. No new permissions, no new user-facing error
states.

## Testing

Window-manager position persistence, real-device visual contrast, and
drag/orientation-change interaction have no meaningful unit-test surface —
consistent with how the rest of this bubble's behavior has been verified,
this is confirmed manually on-device: drag to several positions, relaunch
the app, rotate the device, and check against both a light-background app
and a dark-background app.

## Relationship to Other Sub-projects

Purely isolated to how the bubble sits on screen. No interaction with the
recorder, the voice agent's planning/execution pipeline, or any of the
other upgrade sub-projects (walkie-talkie mode, AI clarification, OCR
re-enablement, find-image/mark-area, or command callsigns) — those touch
`AgentBubble.kt`'s voice-turn logic and `ClaudeActionPlanner`, not
`CircularMenuWindow`.
