# Smart Recorder + Bubble UI — Design Spec

Status: approved (Spec 1 of 2)
Date: 2026-11-04

## Context

AutoX.js already ships most of the low-level plumbing an AI-driven, bubble-controlled
automation feature needs: a floating overlay (`FloatingPermission`, the legacy floating
console), two recording pipelines (`GlobalActionRecorder`/`TouchRecorder` for raw input
events, and `AccessibilityActionRecorder`/`AccessibilityActionConverter` for semantic
accessibility events), screen capture (`ScreenCaptureManager`/`ScreenCapturer`), image
matching (`ImageWrapper`, `images.findImage`), and a vendored OCR module (`paddleocr`).

The existing accessibility-based recorder converts events directly into bounds-based
`click(x1,y1,x2,y2)` / index-based `input(i, "text")` calls (see
`AccessibilityActionConverter`). This is fragile: it hardcodes screen coordinates and
field indices rather than resilient text/image conditions, and it has no concept of
"wait until this appears" — it assumes the UI is already in the exact recorded state.

## Goal

A long-term product goal is a live, voice-controlled agent: the user talks to the app
and it performs actions on the current screen in real time (Spec 2, not covered here).
That live agent needs a reusable **action schema** and an **executor** that can perform
one action against the current screen — not just a script-text emitter. This spec
(Spec 1) builds that reusable foundation first, exposed to the user as a floating
bubble with Record / Stop / Play, before any AI/voice layer is added.

### Out of scope (deferred to Spec 2)

- Natural-language or voice-driven script authoring.
- The live, real-time voice agent loop (speech-to-text, Claude API calls, single-action
  execution against the live screen).

## Decisions

- **Built inside the existing AutoX.js app** as a new, isolated module — not a
  standalone app, and not a modification of the legacy
  `GlobalActionRecorder`/`AccessibilityActionRecorder` code paths. This maximizes reuse
  of already-working permission flows, the script engine, and OCR/image matching,
  while carrying zero regression risk to the existing recorder/console feature.
- **No baked-in timing.** Recorded actions are conditions to satisfy ("click this text
  when it appears"), not delays to wait out. This removes the "dead gaps" problem by
  construction instead of needing to detect and trim gaps after the fact.
- **Bubble UX kept deliberately simple**: Record / Stop / Play only. Optimization runs
  automatically as part of the Stop flow — there is no separate manual "Optimize" step.

## Components

### `autojs` module — new package `com.stardust.autojs.core.agent`

- **`SemanticAction`** — plain data class describing one recorded step:
  - `type`: `CLICK_TEXT` | `CLICK_IMAGE` | `CLICK_BOUNDS` (fallback) | `LONG_CLICK` |
    `FILL_TEXT` | `SCROLL`
  - `targetText: String?` — for `CLICK_TEXT` / text-based `FILL_TEXT` lookup
  - `imageTemplatePath: String?` — path to a small cropped PNG template, for
    `CLICK_IMAGE`
  - `bounds: Rect` — always captured, for every action type. Used both as the
    `CLICK_BOUNDS` fallback value and to disambiguate when a text/image match is not
    unique at playback time (closest-to-original-bounds wins).
  - `fillValue: String?` — the text to enter, for `FILL_TEXT`
  - `scrollDirection: Direction?` — for `SCROLL`, inferred from the accessibility
    event's bounds delta

- **`ScreenStateReader`** — given the accessibility root node (and, lazily, a
  screenshot), resolves:
  - visible text nodes with their bounds (for matching taps to `targetText`)
  - editable fields with bounds/hints (for `FILL_TEXT`)
  - when a tapped node has neither text nor a content description, crops a small
    bitmap template around its bounds from a screenshot for `CLICK_IMAGE` (reusing
    `ImageWrapper`/`images.findImage`; `paddleocr` remains available for any residual
    OCR need but is not the primary path, since accessibility node text covers the
    common case)

- **`SemanticRecorder`** — a new `AccessibilityDelegate`, entirely separate from the
  existing `AccessibilityActionRecorder` (no shared state). Listens for
  `TYPE_VIEW_CLICKED`, `TYPE_VIEW_LONG_CLICKED`, `TYPE_VIEW_TEXT_CHANGED`, and
  `TYPE_VIEW_SCROLLED`, and emits one `SemanticAction` per relevant event via
  `ScreenStateReader`.

- **`ActionOptimizer`** — pure function `List<SemanticAction> -> List<SemanticAction>`.
  Responsibilities:
  1. Collapse repeated `FILL_TEXT` events on the same field (fired on every keystroke
     via `TYPE_VIEW_TEXT_CHANGED`) into a single action with the final value.
  2. Merge consecutive `SCROLL` events in the same direction into one.
  3. Nothing else needs trimming — because no action ever records a `sleep(ms)`, there
     is no idle timestamp data to strip in the first place.

- **`ActionScriptGenerator`** — pure function `List<SemanticAction> -> String`,
  rendering Auto.js source using existing selector APIs:
  - `CLICK_TEXT` → `text("...").waitFor(); text("...").click();`
  - `FILL_TEXT` → `text("...").setText("...")` (or `id(...)` when a stable id is
    available)
  - `CLICK_IMAGE` → a bounded polling loop against `images.findImage()` with the saved
    template path
  - `CLICK_BOUNDS` (fallback) → plain `click(x, y)`, with a generated comment warning
    that this step used fixed coordinates and may be fragile (suggesting re-recording
    with screen-capture permission granted)
  - Image templates are written as small PNG files alongside the generated script in
    the same scripts directory, so a script and its image assets travel together.

### `app` module — new package `org.autojs.autojs.ui.agent`

- The bubble overlay service/UI, wiring the above together. Reuses `FloatingPermission`
  for the overlay permission flow, `ScriptOperations` for saving generated scripts, the
  `codeeditor` module for the read-only script preview, and the existing script
  execution engine to run a script when Play is tapped.

## User flow

1. **Collapsed bubble**: a small draggable circular bubble sits at a screen edge.
2. **Tap to expand**: shows a panel with three controls — **Record**, **Stop**
   (enabled only while recording), **Play** (enabled when a script is available).
3. **Record**: panel collapses back to a bubble with a pulsing recording indicator, so
   it stays out of the way while the user interacts with any other app.
   `SemanticRecorder` starts listening. Tapping the bubble again re-expands the panel
   (recording keeps running) to reach Stop.
4. **Stop**: recording ends; `ActionOptimizer` then `ActionScriptGenerator` run
   automatically. A summary dialog appears (same pattern as the existing
   `showRecordHandleDialog`) showing the step count, a **Preview** (opens the generated
   script read-only in the `codeeditor` module), and **Save as script file** /
   **Discard**.
5. **Play**: opens a picker scoped to scripts created by this feature (defaulting to
   the most recently recorded one) and runs it through the existing script execution
   engine. The bubble shows a stop-execution affordance while a script runs.

## Permissions

- Accessibility Service — already required by AutoX.js; reuses the existing grant
  flow.
- Overlay (`SYSTEM_ALERT_WINDOW`) — via existing `FloatingPermission`.
- Screenshot/MediaProjection (`ScreenCaptureManager`) — requested **lazily**, only the
  first time a recording encounters an unlabeled element that needs an image template.
  Not requested upfront, so the common case (labeled standard UI) stays frictionless.

## Error handling

- **Ambiguous text match** at playback (multiple nodes with the same text): resolved
  by picking the match whose bounds are closest to the originally recorded bounds —
  this is why bounds are always stored even for `CLICK_TEXT`/`CLICK_IMAGE` actions.
- **Target never appears** within the poll timeout: the generated script raises a
  clear runtime error naming the missing step, rather than hanging silently.
- **No text/content-description and no screen-capture permission**: falls back to
  `CLICK_BOUNDS` with a warning comment in the generated script, so recording is never
  blocked, but fragility is visible to the user.

## Testing

- `ActionScriptGenerator`, `ActionOptimizer`, and the event→`SemanticAction` mapping
  logic are plain JVM unit tests under `autojs/src/test` (synthetic `SemanticAction`
  lists in, expected JS string / reduced list out — no Android framework dependency),
  matching this project's existing test conventions (`autojs/src/test/.../
  MultiLinePreprocessorTest.java`, `XmlConverterTest.java`).
- The bubble UI, live accessibility capture, and permission flows are verified manually
  by running the app, consistent with how the rest of the UI in this project is tested
  (no existing instrumented/UI test suite to extend).

## Relationship to Spec 2 (future)

Spec 2 (live voice agent + AI script authoring) will reuse `SemanticAction`,
`ScreenStateReader`, and an executor built from the same primitives to perform one
action against the live screen per spoken instruction (push-to-talk → Android
`SpeechRecognizer` → Claude API with screen-state context → one `SemanticAction` →
executed immediately). It will also reuse `ActionScriptGenerator` to let a live session
be saved as a replayable script afterward. That work is a separate brainstorm/spec,
built after this one lands.
