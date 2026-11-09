# Bubble Movability and Visibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix three real, diagnosed bugs in the shared floating-bubble window used by both the Smart Recorder Bubble and the legacy Floating window feature: dragged position doesn't survive a relaunch, the bubble partially hides itself when docked to a screen edge, and it's nearly invisible against light-colored app backgrounds.

**Architecture:** All three fixes land in the existing, shared `CircularMenuWindow`/drag-gesture code and the two plain circle drawables both bubbles already use as their icon background — no new classes, no new files, no behavior added beyond what's already there made to work correctly.

**Tech Stack:** Kotlin/Java, Android `WindowManager`/`SharedPreferences`, Android shape drawables.

**Spec:** `docs/superpowers/specs/2026-11-09-bubble-movability-and-visibility-design.md`

## Global Constraints

- Fix the shared `CircularMenuWindow` class and its shared drawables directly — this intentionally also changes the legacy "Floating window" feature's behavior (`CircularMenu.kt`), per the approved design. Do not fork a separate implementation.
- Keep the existing "snap to the nearest screen edge on drag release" behavior (`BounceDragGesture`'s `setAutoKeepToEdge(true)`) exactly as-is — the spec does not ask for free mid-screen placement, only for the dock to stop hiding part of the circle and for the dragged edge/Y position to survive a relaunch.
- No lock-screen visibility — explicitly out of scope, confirmed with the user.
- No automated test coverage applies to this plan — every change here is either real-device window-manager/`SharedPreferences` behavior or a drawable's visual appearance, with no unit-testable surface. Verification is a clean compile plus the on-device checklist in Task 3.

---

### Task 1: Position persistence and full-circle docking

**Files:**
- Modify: `app/src/main/java/org/autojs/autojs/ui/floating/CircularMenuWindow.java`
- Modify: `app/src/main/java/org/autojs/autojs/ui/agent/AgentBubble.kt`
- Modify: `app/src/main/java/org/autojs/autojs/ui/floating/CircularMenu.kt`

**Interfaces:**
- Consumes: nothing new — all four edits are to existing methods/fields/call sites, verified against the real current source during planning.
- Produces: nothing new — same public API surface (`CircularMenuWindow`'s constructor, `setKeepToSideHiddenWidthRadio`, etc. are all unchanged in signature).

Grounded during planning: `CircularMenuWindow.setInitialState()` currently reads back only `KEY_POSITION_Y`; `close()` already saves both `KEY_POSITION_X` and `KEY_POSITION_Y` correctly (lines 207-212), so the fix is purely on the read side. `DragGesture.keepToEdge()` computes `hiddenWidth = mKeepToSideHiddenWidthRadio * view.getWidth()` and deliberately pushes that many pixels off-screen when docking (`DragGesture.java:122-129`, overridden identically in spirit by `BounceDragGesture.keepToEdge()` at `BounceDragGesture.java:37-46`) — both `AgentBubble.kt:150` and `CircularMenu.kt:107` currently call `setKeepToSideHiddenWidthRadio(0.25f)`, which is the direct cause of the partial-hide. `BounceDragGesture`'s constructor already calls `setAutoKeepToEdge(true)` (`BounceDragGesture.java:23`), so every drag release already snaps to the nearest edge based on which half of the screen the drag ended in (`BounceDragGesture.java:39,41-45`) — restoring the saved X and then letting `keepToSide()` run is safe and idempotent, since a saved position is always already at (or very near) an edge.

- [ ] **Step 1: Restore the saved X position on startup**

In `CircularMenuWindow.java`, replace `setInitialState()`:

```java
    private void setInitialState() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mContext);
        int y = preferences.getInt(KEY_POSITION_Y, ScreenMetrics.getDeviceScreenHeight() / 2);
        mActionViewWindowBridge.updatePosition(mActionViewWindowBridge.getX(), y);
        keepToSide();
    }
```

with:

```java
    private void setInitialState() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mContext);
        int x = preferences.getInt(KEY_POSITION_X, mActionViewWindowBridge.getX());
        int y = preferences.getInt(KEY_POSITION_Y, ScreenMetrics.getDeviceScreenHeight() / 2);
        mActionViewWindowBridge.updatePosition(x, y);
        keepToSide();
    }
```

- [ ] **Step 2: Stop hiding part of the circle when docked**

In `AgentBubble.kt`, change line 150:

```kotlin
        mWindow?.setKeepToSideHiddenWidthRadio(0.25f)
```

to:

```kotlin
        mWindow?.setKeepToSideHiddenWidthRadio(0f)
```

In `CircularMenu.kt`, change line 107 the same way:

```kotlin
        mWindow?.setKeepToSideHiddenWidthRadio(0.25f)
```

to:

```kotlin
        mWindow?.setKeepToSideHiddenWidthRadio(0f)
```

- [ ] **Step 3: Remove the now-inaccurate comment about position persistence being an accepted limitation**

In `AgentBubble.kt`, the `initFloaty()` method currently has this comment immediately above the `CircularMenuWindow(...)` construction (around line 114-118):

```kotlin
        // Known, accepted limitation: CircularMenuWindow persists its position
        // under keys derived from its own class name, so this bubble and the
        // legacy CircularMenu bubble share one saved position and whichever
        // closes last wins. Fixing that would mean changing the shared
        // CircularMenuWindow, which this feature deliberately leaves untouched.
```

Replace it with:

```kotlin
        // CircularMenuWindow persists its position under keys derived from
        // its own class name, so this bubble and the legacy CircularMenu
        // bubble share one saved position and whichever closes last wins -
        // both bubbles are meant to remember "the last place either of them
        // was left," not independent positions.
```

- [ ] **Step 4: Compile to verify**

Run: `./gradlew :app:compileCommonDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/autojs/autojs/ui/floating/CircularMenuWindow.java app/src/main/java/org/autojs/autojs/ui/agent/AgentBubble.kt app/src/main/java/org/autojs/autojs/ui/floating/CircularMenu.kt
git commit -m "fix(bubble): restore the saved X position and stop hiding part of the circle when docked"
```

---

### Task 2: Visible against any background

**Files:**
- Modify: `app/src/main/java/org/autojs/autojs/ui/floating/CircularMenuWindow.java`
- Modify: `app/src/main/res/drawable/circle_white.xml`
- Modify: `app/src/main/res/drawable/circle_red.xml`
- Modify: `common/src/main/res/drawable/circle_white.xml`
- Modify: `common/src/main/res/drawable/circle_red.xml`

**Interfaces:**
- Consumes: nothing new.
- Produces: nothing new — same drawable resource names, same `CircularMenuWindow` field.

Grounded during planning: `CircularMenuWindow.mInactiveAlpha = 0.4F` (line 34) is applied via `initGestures()`'s `mDragGesture.setUnpressedAlpha(mInactiveAlpha)` (line 90) and directly in `onCollapsed`'s `mCircularActionView.setAlpha(mInactiveAlpha)` (line 128) — changing this one field changes the idle-state opacity for both bubbles, with no call-site override anywhere (confirmed: neither `AgentBubble.kt` nor `CircularMenu.kt` calls `setInactiveAlpha`). Separately, `AgentBubble.kt`'s `updateIcon()` (and `CircularMenu.kt`'s equivalent) sets the bubble's background to `R.drawable.circle_white` (idle) or `R.drawable.circle_red` (recording) — both are currently a plain `<shape android:shape="ring" ...><solid .../></shape>` with no stroke, confirmed identical byte-for-byte between the `app` and `common` module copies of each file.

- [ ] **Step 1: Raise the idle-state opacity**

In `CircularMenuWindow.java`, change line 34:

```java
    protected float mInactiveAlpha = 0.4F;
```

to:

```java
    protected float mInactiveAlpha = 0.95F;
```

- [ ] **Step 2: Add a visible outline to the idle (white) circle**

In both `app/src/main/res/drawable/circle_white.xml` and `common/src/main/res/drawable/circle_white.xml`, replace:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
       android:innerRadius="0dp"
       android:shape="ring"
       android:thicknessRatio="2"
       android:useLevel="false">
    <solid android:color="#ffffff"/>
</shape>
```

with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
       android:innerRadius="0dp"
       android:shape="ring"
       android:thicknessRatio="2"
       android:useLevel="false">
    <solid android:color="#ffffff"/>
    <stroke android:width="2dp" android:color="#80000000"/>
</shape>
```

- [ ] **Step 3: Add the same outline to the recording (red) circle**

In both `app/src/main/res/drawable/circle_red.xml` and `common/src/main/res/drawable/circle_red.xml`, replace:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
       android:innerRadius="0dp"
       android:shape="ring"
       android:thicknessRatio="2"
       android:useLevel="false">
    <solid android:color="#ef5350"/>
</shape>
```

with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
       android:innerRadius="0dp"
       android:shape="ring"
       android:thicknessRatio="2"
       android:useLevel="false">
    <solid android:color="#ef5350"/>
    <stroke android:width="2dp" android:color="#80000000"/>
</shape>
```

- [ ] **Step 4: Compile to verify**

Run: `./gradlew :app:compileCommonDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/autojs/autojs/ui/floating/CircularMenuWindow.java app/src/main/res/drawable/circle_white.xml app/src/main/res/drawable/circle_red.xml common/src/main/res/drawable/circle_white.xml common/src/main/res/drawable/circle_red.xml
git commit -m "fix(bubble): raise idle opacity and add an outline so the bubble reads on any background"
```

---

### Task 3: Full build verification and manual on-device check

**Files:** none (verification only).

- [ ] **Step 1: Run the full unit test suite**

Run: `./gradlew :autojs:testDebugUnitTest :app:testCommonDebugUnitTest`
Expected: BUILD SUCCESSFUL — this task introduces no new tests and should not regress any existing one, since nothing here touches test-covered code paths.

- [ ] **Step 2: Assemble the debug APK**

Run: `./gradlew :app:assembleCommonDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual on-device verification**

Install the APK on a connected device and, for **both** the Smart Recorder Bubble and the legacy Floating window bubble (drawer → Record script → each toggle, one at a time):

1. Drag the bubble to several different positions (left edge, right edge, upper area, lower area) and confirm it's a complete, fully-visible circle at every position — never a partial sliver.
2. Fully close and relaunch the app. Confirm the bubble reappears at the same edge/height it was left at, not reset to a default position.
3. Rotate the device. Confirm the bubble re-docks to the nearest edge without being cut off.
4. Open an app with a plain white/light background (e.g. a blank Notes document) and confirm the bubble is clearly visible — both its outline and fill should read clearly, not blend into the background.
5. Open an app with a dark background and confirm the bubble is still clearly visible there too.
6. Tap/drag the bubble (active state) and release (idle state) — confirm the opacity difference between the two states is still perceptible, just no longer anywhere near fully transparent.

If any check fails, return to the relevant task, fix the issue, re-run its build step, and re-check here before considering this plan complete.
