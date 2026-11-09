# Re-enable OCR with Bounds Routing — Design Spec

Status: approved (Sub-project 4 of the voice-agent upgrade program)
Date: 2026-11-09

## Context

OCR-based screen reading was built as part of the original voice agent
(`2026-11-08-live-voice-agent-design.md`) but disabled during that plan's
final review, because it didn't actually work: an OCR-matched target had no
real accessibility `getText()`/`getContentDescription()` for the generated
script's `__findTextNear`/`__findDescNear` selectors to find at execution
time, so every OCR-matched click burned its full 10-second find-timeout and
then failed. Two more issues were found alongside it and parked rather than
fixed: the captured screenshot was never recycled (a leak on every
OCR-triggering turn), and OCR results were unioned into the screen
description unconditionally, with no deduplication against labels the
accessibility tree had already found.

`ScreenStateReader.kt`'s own doc comments (read in full during planning)
already name exactly these three blockers and state what re-enabling
requires - this spec is that work.

Grounded against the real code:

- `ScreenStateReader.ocrFallback()` still exists, unused, with a `@Suppress("unused")`
  annotation and a cached `paddle` field already in place from the earlier
  fix round - re-enabling the call site is a small, contained change, not a
  rebuild.
- `ActionScriptGenerator.clickBoundsStep()` already generates a correct,
  working fixed-coordinate click/long-click from a bare `SemanticAction.bounds`
  - this is the exact mechanism the recorder already uses for a tap with no
    matchable accessibility label. No codegen work is needed; this is a
    routing decision inside the planner.
- `VisibleText(text, matchBy, bounds)` carries no notion of where a label
  came from - `MatchBy` is purely TEXT-vs-DESC (which selector style to
  generate), not accessibility-vs-OCR (which strategy to click with). These
  are genuinely different axes and conflating them would leak a routing
  concern into `MatchBy`'s existing, narrower job.

## Goal

OCR text re-enters the screen description Claude reasons over, so an
icon-only element with real on-screen text but no accessibility label
becomes targetable by voice - this is the gap the original design spec
promised and the final review found unmet. Clicking an OCR-found target
actually works this time, by tapping its exact captured position rather
than re-searching for a label that was never real.

## Decisions

- **`VisibleText` gets a new field marking OCR origin**, not a new `MatchBy`
  value - `MatchBy` stays exactly what it already is (TEXT vs DESC selector
  style), and the new field is a separate, orthogonal routing signal the
  planner uses internally. Claude's tool schema and prompt are unaffected -
  it still just names a target; which codegen path that target takes is
  decided entirely on this app's side.
- **OCR-sourced targets support click and long-click only.** Filling text
  into, or waiting for, something with no real accessibility node doesn't
  have a working mechanism in this codebase (`FILL_TEXT`'s codegen requires
  an `editable(...)` selector; `WAIT`'s requires a pollable selector - bare
  bounds support neither). Asking for either on an OCR-only target is
  treated as a case the planner "couldn't determine a usable action for" -
  which, now that sub-project 3 exists, surfaces as a clarifying question
  rather than a silent failure, instead of inventing a new error path.
- **Fix the two parked bugs while re-enabling, not separately.** Both were
  found and left specifically pending this re-enablement - fixing them now
  is the same unit of work, not scope creep: recycle the captured screenshot
  once OCR processing is done, and skip an OCR hit whose center falls inside
  an already-collected accessibility entry's bounds (cheap, bounds-only
  overlap check - no image/text similarity needed to stop an obviously
  duplicate hit).
- **No cap on total entry count.** The dedup above reduces redundancy but
  doesn't bound how large a dense screen's description can get - this was
  already a pre-existing, accepted, non-blocking concern for the
  accessibility tree alone before OCR was ever involved, and stays out of
  scope here rather than solving a more general problem this sub-project
  wasn't asked to solve.
- **Accepted tradeoff, consistent with existing precedent:** a bounds-based
  OCR click uses the exact pixel position captured when the screen was
  described, not a live re-search - if the screen changes between
  description and execution (the Claude round-trip), the click can land in
  the wrong place. This is the same tradeoff the recorder already accepts
  for its own unlabeled-tap `CLICK_BOUNDS` fallback.

## Components

### `autojs/src/main/java/com/stardust/autojs/core/agent/ClaudeActionPlanner.kt`

- `VisibleText` gains `val isOcrSourced: Boolean = false` (default preserves
  every existing call site and test construction unchanged).
- `actionFromInput`'s click/long-click branches route to `ActionType.CLICK_BOUNDS`
  (bare bounds, no `targetText`/`matchBy`) when the matched entry is
  OCR-sourced, and to the existing `CLICK_TEXT`/`LONG_CLICK` text-selector
  path otherwise - both preserve `isLongClick` correctly either way.
- `actionFromInput`'s fill and wait branches exclude an OCR-sourced match
  (falling through to the existing `NeedsClarification` path the same way a
  completely unmatched target already does), rather than producing an action
  that would always fail to find its selector.

### `autojs/src/main/java/com/stardust/autojs/core/agent/ScreenStateReader.kt`

- `ocrFallback()`'s `@Suppress("unused")` is removed and its results flow
  into `describeScreen()` again, filtered against the accessibility-sourced
  list (skip an OCR hit whose center point falls inside any already-collected
  entry's bounds) before being appended.
- The captured `ImageWrapper` is recycled once OCR processing on it is done,
  success or failure.
- Doc comments on `describeScreen()` and `ocrFallback()` are updated to
  describe the restored, fixed behavior instead of explaining why OCR is
  off.

## Error Handling

No new user-facing error states. An OCR-only target that can't support the
requested action type (fill/wait) reaches the exact same clarifying-question
path sub-project 3 already built for "couldn't determine a usable action" -
no new toast, no new flow.

## Testing

`ClaudeActionPlanner`'s routing logic (OCR-sourced vs accessibility-sourced
click/long-click/fill/wait handling) is pure and unit-testable the same way
the existing action-routing tests already are. `ScreenStateReader`'s OCR
capture, dedup-against-live-bounds, and recycling are Android/OCR-dependent
with no unit-test surface - verified manually on-device, consistent with the
rest of this program.

## Relationship to Other Sub-projects

Extends the existing voice-agent pipeline (`ClaudeActionPlanner`,
`ScreenStateReader`) without touching `AgentBubble`, the bubble's visuals
(sub-project 1), the walkie-talkie trigger (sub-project 2), or the
clarification round-trip mechanics (sub-project 3) - it only adds one more
reason a clarification might be asked for, reusing that mechanism as-is.
Independent of mark-area/find-image (sub-project 5), which is a different
targeting strategy (a user-drawn region matched visually on demand) rather
than this sub-project's always-on passive OCR read of the current screen.
