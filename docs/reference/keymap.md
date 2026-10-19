# Key bindings — rebindable actions, and the editor that rebinds them

Status: **landed** (`vexelray-gui-keymap`, plus two primitives in `-core`: `Gui.claimAny` and
`Shortcut.text`/`parse`). Opt-in: nothing in the framework creates a keymap, and a title bar shows the key
button only when it is handed one.

## 1. The action mapper

An application declares its keyboard commands **by name**, with the chords they start with, and handles them
by name. Which chord reaches the handler is no longer the code's to fix:

```java
Keymap keys = new Keymap(gui).persist(AppHome.of("editor").settings("keys"));
keys.action("file.save", "Save", Shortcut.of(Key.S, Modifier.CONTROL)).on(this::save);
keys.action("file.save-as", "Save As", Shortcut.of(Key.S, Modifier.CONTROL, Modifier.SHIFT)).on(this::saveAs);
keys.action("view.zen", "Zen mode").on(ui::toggleZen);          // no default: the user may give it one
```

Each line replaces a `gui.shortcut(...)`, and is the same thing on the bus: a `GLOBAL` claim (§3 of
[keyboard-focus-text.md](keyboard-focus-text.md)), so a focused field still outranks it. Rebinding releases one
claim and makes another; the handler never hears about it.

- **The id is the stable part.** It is what a settings file stores: rename the label freely; rename the id
  and the user's choice for it is forgotten. Ids are dotted words, `[A-Za-z0-9._-]+`.
- **Handlers run on a worker**, as every claim's command does. `Action.run()` runs it directly, for the menu
  item or button that does the same thing. `ordered()` runs it on the GUI thread in the order keys arrived instead
  (`claimUi`), for a command that edits what typing edits too: a terminal's paste, which on a worker could land
  among the characters typed around it. Bounded work only, as for any ordered stage.
- **No handler, no claim.** A declared action that nothing implements yet swallows no key.
- **Claimed on a node of the keymap's own**, never on the root. A rebind releases exactly the keymap's claim on
  the old chord; releasing it on the root would take the application's undo chords with it.

### 1.1 Places, and the one invariant

An action applies application-wide unless `within(gui, node, scope)` narrows it to a node at a scope. The claim
is then that node's, ranked exactly as its own claims are.

**Within one place a chord belongs to at most one action.** Across places it may repeat, and the narrower place
wins while it applies — Tab can indent in an editor and move focus everywhere else, both rebindable. The
operations keep the invariant, not the user:

- `bind(action, chords)` **takes** each chord from whichever action in the same place held it, and returns who
  lost one (`Bound.displaced`) so the editor can say what moved.
- Two *defaults* in one place sharing a chord is a mistake in the code, refused when declared.
- A loaded file is anybody's, so the effective chords are derived with the invariant enforced: where a file
  gives one chord to two actions in one place, the one declared first keeps it.

### 1.2 What is stored

Only what the user changed (`Overrides`): an action absent from it is on its defaults; one present with no
chords was deliberately unbound. Storing deltas is what lets a default fixed in a later version reach everyone
who has not said otherwise. Binding an action back to precisely its defaults removes its entry.

The text form is one line per action, chords separated by spaces — a chord's own spelling has none, while
every punctuation mark is a key someone can bind:

```
file.save = Ctrl+K Ctrl+Shift+S
view.zen =
```

Reading is lenient line by line, as `Settings` is about a file. Entries for actions this run never declared are
**kept** and written back, so a profile from a newer build survives a round trip through an older one.

- `text()` / `load(String)` — for an application that keeps profiles of its own.
- `save(Settings)` / `load(Settings)` — one list under the key `keymap`.
- `persist(Settings)` — load now, save on every change. A rebind is rare and a lost key map is a person redoing
  their setup, so it is written as it happens rather than at exit. Use a store of its own (`settings("keys")`)
  if another part of the application holds a `Settings` over the same file: each writes back everything it
  holds.

### 1.3 Windows

Keys are focal — a chord reaches only the focused window's `Gui` — so a command meant from every window is
claimed in every window: `keys.attach(otherGui)`. One keymap, one set of chords.

## 2. A chord's spelling

`Shortcut.text()` writes `Ctrl+Shift+K`, `Alt+PageDown`, `Ctrl+=`, modifiers in the order every desktop writes
them (Ctrl, Alt, Shift, Super). `Shortcut.parse` reads it back — and is the lenient side: any case, spaces
around `+`, the enum's own names (`CONTROL+PAGE_DOWN`) and the usual aliases (`Control`, `Escape`, `Win`,
`Cmd`). **One spelling for the screen and the file**, because a settings file is where a person most often
reads a chord they did not type. A modifier's own key is never a chord's key: pressed alone it is routed
nowhere. Every key under every modifier set round-trips (`ShortcutTextTest`).

### 2.1 Touchpad gestures are chords too

What a claim and an action are made on is a `Trigger`: a `Shortcut`, or a `Gesture`, which is a touchpad
gesture that is a command. A gesture has a number of fingers (two to five) and one motion: `SwipeLeft`,
`SwipeRight`, `SwipeUp`, `SwipeDown`, `Tap` or `DoubleTap`. Its spelling is words run together, the way
`PageDown` is, so it needs no separator: `ThreeFingerSwipeLeft`, `TwoFingerDoubleTap`. `Trigger.parse` reads
either kind, and so a file holds both on one line:

```
view.next = Right PageDown ThreeFingerSwipeLeft
```

Everything above that says chord means either. Gestures are claimed, outranked, taken and stored exactly as
keys are, so an application declares one as a default beside its keys:

```java
keys.action("view.next", "Next picture", Shortcut.of(Key.RIGHT), Gesture.swipe(3, Gesture.Motion.SWIPE_LEFT))
        .on(() -> step(1));
```

**Where they come from.** The dispatcher feeds the window's touchpad contacts (`InputEvent.TouchpadChanged`) to
one tactroller `TouchpadGesture` per window, and `GestureTriggers` decides which of its gestures are commands:

- **A swipe fires while the fingers are still moving**, once they have gone 12 mm along one axis and at least
  twice as far along it as across it. A short flick that lifts sooner is a swipe too (4 mm at 120 mm/s). One per
  gesture, however far it goes, and a pinch is never one.
- **A tap is reported at once**, and a second within 350 ms with the same fingers is also a double tap. The
  single is not held back to wait for a double, so a place that binds both hears both.

**Continuous gestures are not triggers.** A pan or a pinch is direct manipulation, with nothing to rebind. The
dispatcher publishes the same recognizer's output on `InputTopics.GESTURE` for whatever manipulates something
directly, so a widget reading pans and a claim on a swipe agree on what the fingers did.

## 3. Recording: a claim on every chord

`gui.claimAny(node, scope, chord -> ...)` claims *every* chord for a node, and the command hears which one it
got. It is an ordinary claim, which is the whole point: while it applies it outranks the framework's Tab and
the application's Ctrl+S, so a recorder records them rather than performing them. Nothing new in the
dispatcher but the match. A touchpad gesture reaches it too, so the editor records a swipe by watching one
being made on the pad.

**At one scope a named claim outranks the wildcard.** The wildcard is the floor of its scope, never a way to
shadow a key somebody claimed by name. The consequence for a recorder is a design rule: record on a node
that holds no named claims. `KeymapEditor` records on a fresh node rather than the chip, because the chip
claims Enter and Delete by name and could otherwise never record Enter.

## 4. The editor

`KeymapEditor(gui, keymap)` is a node — every action, its chords as chips, a reset per action, a filter, and a
status line. Put it wherever the application keeps preferences.

- **Click a chip, or Enter on it, and press the new chord.** The recorder shows the modifiers as they are held.
  Escape alone cancels; so does focus going anywhere else, because a recorder nobody is looking at must not eat
  the next key.
- **Taking, not refusing.** A chord held in the same place is taken, and the status line names the action it
  came from; the reset beside that action undoes it. A conflict dialog would ask the person to resolve
  something they just resolved by pressing the key.
- **A bare typing key, bound application-wide, is warned about** once, when it is made: it is claimed before
  any field sees it, so it fires while typing.
- Keyboard-complete (Tab walks chips, Enter/Space records, Delete/Backspace removes); with the pointer, `×`
  removes and `+` adds. Nothing appears on hover; the reset is always present and enabled only when there is
  something to reset; the status line's height is reserved.

### 4.1 Behind a title-bar button

```java
titleBar.instruments(List.of(SaveScreenshot.instrument(), KeymapWindow.instrument(app, gui, keys)));
```

`WindowInstrument.keymap(tooltip, open)` is core's — the role `instrument-keymap` and a drawn keyboard mark —
so an agent finds it by one name in every application, as with the screenshot. `KeymapWindow` supplies the
behaviour: the editor in a window of its own (registered as `app.window("keymap")`, so a second click raises
it), wearing the main window's theme and client-drawn chrome. Never in `WindowInstrument.standard()`: an
application without rebindable commands has nothing for it to open.

## 5. Not in this

- **Multi-stroke sequences** (`Ctrl+K Ctrl+S`). A "chord" here is one modifier set and one key, the dispatcher's
  unit. Sequences need a pending-prefix state in the dispatcher and a timeout; the text form would extend to
  them (`Ctrl+K,Ctrl+S` as one word) without breaking files written today.
- **Profiles.** `Overrides` is a value and `load(Overrides)` swaps it, which is what a profile switcher needs;
  the switcher itself is the application's.
