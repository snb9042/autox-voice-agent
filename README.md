# AutoX.js Voice Agent

A fork of [AutoX.js](https://github.com/kkevsekk1/AutoX) that adds a floating bubble for **recording and replaying automation scripts without writing code**, plus a **voice-controlled AI agent** — speak an instruction, point at something, or drag a box around it, and the agent figures out what to tap, type, wait for, or find on your current screen and does it immediately.

![Bubble menu expanded, showing Record, Stop, Play, Stop-all, and the new Mic button](docs/screenshots/02-bubble-menu-expanded.png)

## What's new in this fork

Everything below is additive on top of stock AutoX.js — nothing about the underlying scripting engine, editor, or existing features changed.

### 🔴 Smart Recorder Bubble
A small floating circle sits on your screen — drag it anywhere, it stays exactly where you leave it (across app relaunches too), never shrinks to a half-hidden sliver when docked at an edge, and reads clearly against any app background, light or dark. Tap it to reveal five actions:

| Icon | Action |
|---|---|
| 🔴 Record | Start recording your taps, long-presses, text entry, and scrolls |
| ⏹ Stop | Stop recording (or stop an active voice session) and automatically clean up the result |
| ▶ Play | Run the last saved script |
| ✖ Stop all | Kill every currently-running script |
| 🎤 Mic | Start a push-to-talk voice instruction (see below) |

Record a sequence of taps in any app, tap Stop, and the bubble shows you exactly what it captured before asking whether to save it as a script. No gaps, no manual cleanup — a tap sequence with pauses in between is automatically trimmed to just the meaningful steps.

### 🎤 Voice Agent
Tap the mic (or hold the volume-up key — see below), speak one instruction ("tap Login", "fill the email field with test@example.com", "scroll down", "wait for the page to load"), and the agent:

1. Reads what's currently on screen — accessibility labels *and* on-screen text an icon alone wouldn't expose
2. Sends your instruction + that screen description to Claude
3. Gets back exactly one action — or one clarifying question if it isn't sure
4. Performs it immediately
5. Tells you what happened (toast — success or a clear reason it couldn't)

If Claude can't tell what you mean, or the action it picked doesn't actually find anything, it asks you one specific follow-up question and listens for your answer before giving up — see [AI clarification](#ai-asks-when-its-not-sure) below.

Keep talking to chain instructions one at a time, then tap Stop to save everything you did as a replayable script — through the exact same save flow the recorder uses.

### 🎙️ Walkie-talkie mode
Hold the volume-up key down instead of tapping the mic. After a brief press-and-hold, it starts listening — keep holding while you speak, even through a pause mid-sentence, and release when you're done. A quick tap of volume-up still does whatever it already did for you (e.g. stop all scripts, if you have that setting on) — holding it is a new, separate gesture, not a replacement.

### 💬 AI asks when it's not sure
If Claude can't determine an action — or the action it picked turns out not to match anything when the script actually runs — instead of silently giving up, it asks you a specific question ("Which button do you mean — the blue 'Submit' or the gray 'Cancel'?") and listens for your spoken answer, same extended listening as walkie-talkie mode. Your answer gets combined with the original instruction and tried once more. If it's still unclear after that, it reports the normal failure — one round of back-and-forth, not an endless interrogation.

### 👆 Tap on mark
Say **"tap on mark"** (or "tap the mark"). The screen tints, tap the exact point you mean, see it confirmed with a red X, and that point gets tapped — no AI guessing involved. For the rare icon with no readable text anywhere, OCR included.

### 🔍 Find image
Say **"find image"**. The screen tints, drag a box around whatever you mean, and the agent remembers what's inside it as a reusable visual template — then finds and taps it right now. Saved into a script, that same step will find and tap the same thing again later even if it has moved, scrolled, or the app was reopened.

## Voice command reference

A quick reference for what to say:

| Say this | What happens |
|---|---|
| "tap X" / "click X" | Tap the thing labeled X |
| "long press X" / "hold X" | Long-press X |
| "type [text] in X" / "fill X with [text]" | Enter text into field X |
| "scroll up/down/left/right" | Scroll the screen |
| "wait for X" | Pause until X appears (no tap) |
| "tap on mark" / "tap the mark" | Tint the screen, tap a point, tap exactly there |
| "find image" | Tint the screen, drag a box, find and tap that visual pattern |

Anything else is sent to Claude as a free-form instruction — the phrases above are recognized instantly and locally, without a network round-trip.

## Screenshots

<table>
<tr>
<td><img src="docs/screenshots/01-home-with-bubble.png" width="260" alt="Home screen with the bubble"/><br/><sub>The bubble floats over any app</sub></td>
<td><img src="docs/screenshots/04-drawer-bubble-toggle.png" width="260" alt="Drawer with the bubble toggle"/><br/><sub>Turn it on/off from the drawer</sub></td>
<td><img src="docs/screenshots/05-settings-ai-agent.png" width="260" alt="Settings screen with the Claude API key field"/><br/><sub>Add your Claude API key once, in Settings</sub></td>
</tr>
</table>

## Getting started

### 1. Build or download the app

```bash
git clone https://github.com/snb9042/autox-voice-agent.git
cd autox-voice-agent
./gradlew :app:assembleCommonDebug
```

The APK lands in `app/build/outputs/apk/common/debug/`. Install the `universal` variant unless you know you want an architecture-specific one.

### 2. Grant the permissions it asks for

The app will prompt for these the first time you need them — just tap through:

- **Accessibility Service** — required for the recorder and the voice agent to see what's on screen and perform taps
- **Display over other apps** — required for the floating bubble and the tap-on-mark/find-image overlays
- **Microphone** — required only for voice input (mic button or volume-up hold), requested the first time you use either

### 3. Turn on the bubble

Open the app → tap the ☰ menu → scroll to **Record script** → turn on **Smart Recorder Bubble**. The bubble appears and stays visible over every app until you turn it off.

### 4. (Voice agent only) Add your Claude API key

Open the app → ☰ menu → **Set up** → scroll to **AI Agent** → **Claude API key** → paste a key from [console.anthropic.com](https://console.anthropic.com/) → OK.

![Toast reminding you to set an API key](docs/screenshots/03-no-api-key-toast.png)

If you tap the mic before adding a key, you'll get this reminder instead of anything happening — your key is never required just to use the recorder/player, or tap-on-mark/find-image once you're already in a voice session.

## How to use it

### Recording a script

1. Tap the bubble → 🔴 Record
2. Go do whatever you want automated — tap buttons, fill in text fields, scroll
3. Tap the bubble → ⏹ Stop
4. Review the step summary, tap **Preview** to see the generated script, then **Save**

The generated script matches by the *text/label* of what you tapped, not fixed coordinates — so it keeps working if the layout shifts slightly. An icon with no readable label falls back to an image match captured at record time.

### Using the voice agent

1. Tap the bubble's 🎤, **or** hold down the volume-up key
2. Speak one instruction, e.g. *"tap the Submit button"*, *"tap on mark"*, or *"find image"*
3. Watch it happen — you'll get a toast telling you what it did, or a clarifying question if it needs more detail
4. Repeat for as many instructions as you like, mixing spoken targets with tap-on-mark/find-image freely
5. Tap ⏹ **Stop** to end the session — you'll get the same save-as-script prompt as recording

**What it can target:** anything with visible text, an accessible label/content-description, or on-screen text readable by OCR — plus, with tap-on-mark and find-image, literally anything you can see at all, regardless of whether it has any text or label.

**Supported instruction types:**
- *Click* / *tap* something by its visible text, label, or OCR-read text
- *Long-press* something
- *Fill* / *type* text into a field
- *Scroll* up/down/left/right
- *Wait for* something to appear (polls for up to 10 seconds, performs no action itself)
- *Tap on mark* — point directly at a fixed spot
- *Find image* — mark a region as a reusable visual target

## Known limitations

This is a from-scratch feature built and reviewed carefully, but it's honest about what's still rough:

- **Tapping Stop in the narrow window right after speaking, before the recognizer has finished processing**, doesn't interrupt that in-flight instruction — it still completes (the action happens on screen) before being discarded from the saved script. In practice this only matters if you tap Stop in the ~1-2 second window right after you finish speaking.
- The Claude API key field doesn't mask the key as you type it (cosmetic; your key is still stored locally, never logged or sent anywhere except directly to Anthropic's API over HTTPS).
- OCR-read text adds to each screen description but isn't deduplicated against anything *visually* similar, only against exact position overlap with an accessibility-found label — on a dense or unusual screen you may occasionally see near-duplicate entries under the hood (this doesn't affect what gets tapped, just internal bookkeeping).

Full technical detail on all of these — and the reasoning behind every other design decision — lives in [`docs/superpowers/specs/`](docs/superpowers/specs/) and [`docs/superpowers/plans/`](docs/superpowers/plans/).

## How it's built

- **Recorder**: an Android Accessibility Service listens for click/long-click/text-change/scroll events, resolves each one to a stable `(text or description, bounds)` target, and appends a `SemanticAction`. On Stop, an optimizer drops dead time between actions and the result renders to a plain JavaScript file using this project's existing `auto.js` scripting API (`click()`, `setText()`, `swipe()`, etc.) — the output is a normal, readable script, not a black box.
- **Voice agent**: one Claude API call per spoken instruction (tool-use, not free-form code generation) maps your instruction + a text description of the current screen (accessibility tree plus OCR) to one of a fixed set of actions, which is rendered through the *same* script generator the recorder uses and executed through the *same* script engine — so every voice-performed action is exactly as inspectable and replayable as a recorded one.
- **Tap on mark / find image**: a full-screen overlay captures your tap or drag directly in true screen coordinates, independent of any AI call — the marked point becomes a fixed-coordinate tap, the marked region becomes an image template matched fresh every time the step runs (so it stays correct even if the target has moved).
- **Callsigns** ("tap on mark", "find image") are recognized locally, before an instruction would otherwise reach Claude — instant and free, not dependent on the AI correctly interpreting a command that was never meant for it.

## Credits & license

Built on [AutoX.js](https://github.com/kkevsekk1/AutoX), itself a continuation of [Auto.js](https://github.com/hyb1996/Auto.js) by [hyb1996](https://github.com/hyb1996). See [`docs/UPSTREAM_README_en.md`](docs/UPSTREAM_README_en.md) for the original project's full README and [`docs/UPSTREAM_README_zh.md`](docs/UPSTREAM_README_zh.md) for the Chinese version.

Licensed under [MPL-2.0](https://www.mozilla.org/MPL/2.0/) (non-commercial) + [GPL-2.0](https://opensource.org/licenses/GPL-2.0), consistent with upstream — see the license files in this repository.
