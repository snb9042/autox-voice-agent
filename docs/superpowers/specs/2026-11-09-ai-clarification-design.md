# AI Clarification — Design Spec

Status: approved (Sub-project 3 of the voice-agent upgrade program)
Date: 2026-11-09

## Context

This is the third upgrade in the program (after
`2026-11-09-bubble-movability-and-visibility-design.md` and
`2026-11-09-volume-up-walkie-talkie-design.md`). Today, when the voice agent
can't determine an action, or determines one that fails to execute, the
user gets a terse toast ("couldn't work out what to do" / "couldn't find
X") and nothing else happens - the instruction is simply dropped. This adds
a single round of back-and-forth: the AI can ask a clarifying question, the
user answers by voice, and the combined information is given one more
attempt.

Grounded against the real code before designing:

- `ClaudeActionPlanner.plan(instruction, screen, apiKey): SemanticAction?`
  (`autojs/src/main/java/com/stardust/autojs/core/agent/ClaudeActionPlanner.kt`)
  already requires Claude to supply a `reason` string with every response -
  including when `action == "none"` - but today that reason is only used
  internally; the caller never sees it, and a `null` return conflates three
  different situations that currently look identical to `AgentBubble`: (a)
  Claude explicitly said "none" with a real reason, (b) Claude named a
  target that doesn't match anything in `screen.visibleText` (`actionFromInput`'s
  `matched?.let { ... }` silently returns null), and (c) a hard failure -
  non-2xx HTTP, unparseable body, or the request couldn't even be built
  (confirmed: `plan()`'s catch block is deliberately broad for exactly this
  reason, per its own comment). None of these carry the `reason` text out to
  `AgentBubble` today.
- `AgentBubble.runVoiceTurn()` (`app/src/main/java/org/autojs/autojs/ui/agent/AgentBubble.kt`)
  treats a `null` from `plan()` and a `false` from `executeAction()` as two
  separate, both terminal, failure toasts (`text_agent_no_action` and
  `text_agent_target_not_found` respectively) - neither path currently
  offers any way to continue the turn.
- `listenForInstruction(walkieTalkie: Boolean = false)` (added in
  `2026-11-09-volume-up-walkie-talkie.md`) already has everything needed to
  listen with a suppressed-silence, longer window - this sub-project reuses
  that exact mechanism for the clarification answer, rather than building a
  second one.

## Goal

When the AI can't determine an action, or determines one whose target isn't
actually found when the generated script runs, it asks one specific
clarifying question instead of silently failing. The user answers by voice;
that answer is combined with the original instruction and tried exactly
once more. If it's still unresolved after that one retry, the turn fails
with the same kind of toast it already gives today - no infinite loop of
questions.

## Decisions

- **One clarification round, hard cap.** Confirmed with the user. After the
  retry (with the combined instruction), any further `NeedsClarification` or
  execution failure is treated as a normal, terminal failure - the existing
  toasts, not another question. This bounds the feature to a bounded,
  predictable interaction rather than open-ended back-and-forth.
- **Reuse Claude's existing `reason` field rather than adding a new one.**
  The tool's instructions are updated so that specifically when Claude picks
  `action: "none"`, `reason` is phrased as a direct question addressed to
  the user ("Which button do you mean - the blue 'Submit' or the gray
  'Cancel'?") rather than an internal explanation. This is a prompt-text
  change, not a schema change - `reason` was already required on every
  response.
- **`actionFromInput`'s silent-null cases also become clarification
  opportunities**, not just literal `action: "none"`. A named-but-unmatched
  target, a missing scroll direction, or a missing fill value all currently
  collapse into the same silent failure `action: "none"` already does -
  since Claude still supplied a `reason` in all of these cases (it's
  required), they get the same clarification treatment uniformly, rather
  than inventing different handling per malformed-response shape.
- **Execution failure (target not found when the script actually runs) gets
  a templated question, not a Claude-authored one.** Claude was confident
  enough to pick a concrete action; there's no `reason` text for "it didn't
  actually work" since Claude never saw that outcome. The app generates a
  simple, consistent question itself (e.g. "Couldn't find '{target}' - can
  you describe it differently?") rather than making an extra Claude round
  trip just to word a question for a case the app already fully understands.
- **A hard failure (`Failed` - network/parse/request-building errors) is
  NOT a clarification opportunity.** There's no `reason` text to show, and
  asking the user to "clarify" a network error would be nonsensical. This
  case keeps today's exact behavior - the `text_agent_no_action` toast,
  nothing else.
- **The follow-up listening reuses the walkie-talkie's extended window**,
  triggered programmatically (not by a key hold) regardless of whether the
  original instruction came from the mic button or the volume-up hold -
  matching the uniformity decision already made in the walkie-talkie spec.

## Components

### `autojs/src/main/java/com/stardust/autojs/core/agent/ClaudeActionPlanner.kt`

- `plan()`'s return type changes from `SemanticAction?` to a new sealed
  result type (exact name/shape finalized during planning) with three
  cases: a successful action, "needs clarification" carrying the question
  text, and a terminal failure carrying nothing. `parseResponse`/
  `actionFromInput` are restructured to produce this instead of silently
  collapsing every non-action outcome to `null`.
- The tool's `reason` field description (in `buildInputSchema()`) is updated
  to instruct Claude: when action is `none`, phrase `reason` as a specific
  question to ask the user, not an explanation of the failure.
- No change to the HTTP call itself, the request-building shape beyond the
  one instruction-text tweak, or any of the action-producing branches for
  concrete actions.

### `app/src/main/java/org/autojs/autojs/ui/agent/AgentBubble.kt`

- `runVoiceTurn()` branches on the new three-way result instead of a plain
  null check. On "needs clarification" (from either the planner or a
  failed `executeAction()`), and only if this is the first attempt for this
  turn (the one-round cap), it toasts the question and calls
  `listenForInstruction(walkieTalkie = true)` again to capture the answer,
  then re-enters the describe-screen → plan → execute sequence with a
  combined instruction - re-describing the screen fresh, since time has
  passed and it may have changed.
- A second "needs clarification" (whether from the planner again, or
  another execution failure) on the retry attempt is treated exactly like
  today's terminal failure - the existing toasts, no further questions.
- All of the existing mutual-exclusion, epoch-staleness, and
  `addAction`-only-on-confirmed-success guarantees apply identically to the
  retry attempt - it is just another voice turn as far as those mechanisms
  are concerned, not a special case.

## Error Handling

No new user-facing error states beyond the clarifying question itself
(toast-only, consistent with the existing no-TTS posture) and the
already-existing terminal-failure toasts, now reached either immediately
(hard failures) or after exactly one unsuccessful clarification round.

## Testing

`ClaudeActionPlanner`'s new three-way result type and the updated
`actionFromInput`/`parseResponse` branches are pure functions - unit
testable the same way the existing ones already are (construct a fake
Claude response, assert on the result). The clarification round's
re-listening/retry behavior in `AgentBubble` is Android/`SpeechRecognizer`-
dependent with no unit-test surface - verified manually on-device,
consistent with the rest of this program.

## Relationship to Other Sub-projects

Directly extends the walkie-talkie sub-project's `listenForInstruction`
rather than duplicating it - this was the hook that sub-project's spec
explicitly flagged as being built for, not yet used. No interaction with
bubble visuals (sub-project 1). Independent of OCR re-enablement and
find-image/mark-area (sub-projects 4-6) - those extend what can be
*targeted*; this extends what happens when targeting *fails or is unclear*,
regardless of how the target was supposed to be found.
