# The undo plan

**Status: designed (2026-10-13), nothing built.** Written after the 0.1.0 release, when it became plain that undo
works only where an application asked for it: typing in a `TextField`, drops in a `TreeView` or `Board`, and
pages that wired a `History` by hand (MainFrame's `WorkspacesPage`, Vexplore). Every other control — a toggle, a
slider, a number field, a select, a colour picker, an inspector row — changes the user's data with no way back.
This plan is done when **every stock widget's user edit is undoable in every application with no application
code**, the behaviour below holds for each of them, and the gallery proves it by driving Ctrl+Z after every
interaction through `ottermate`.

The engine is not the gap. `core.edit.History` and `Change` already do the hard part — a change returns its own
reverse, runs coalesce, "saved" is a position — and `Gui.history(...)` already binds the chords. What is missing
is everything between a widget and a history: nothing records by default, nothing takes the user to what is
about to change, and an entry cannot refuse.

---

## 1. What other toolkits do, and why that is not enough

Qt, Swing, WPF and Cocoa undo text and leave the rest to the application; a checkbox there is not undoable unless
the application's model layer records it. The systems where undo works everywhere — Blender, Figma, Core Data —
get there the same way: every control writes through one mutation path and the record is taken on that path. None
of them inverts arbitrary handler code, and neither will this.

The path already exists here. `Toggle` separates `on(v)` (the user, or something acting as the user — fires
`onChange`) from `show(v)` (the model changed underneath — silent). Every value widget has, or gets, that split,
and **the user path is where a default record is taken**.

## 2. The behaviour

One Ctrl+Z, against the entry on top of the history:

1. **Reveal, unless already there.** If the entry's target is not focused, navigate to it — reveal it through
   whatever conceals it, scroll it into view, focus it — and do nothing else. The next Ctrl+Z is the one that
   edits. *Ruled 2026-10-13.* Reason: an undo that changes something the user cannot see looks like an undo that
   did nothing, and the user has no chance to see what is about to be lost.
2. **Skip the reveal when the target already has focus.** Apply in the same press (and scroll it into view if it
   has scrolled away). *Ruled 2026-10-13.* Reason: otherwise type-then-Ctrl+Z costs two presses, which no editor
   does.
3. **Not navigable is a failure.** If navigation cannot reach the target — it has left the tree, or a container
   declined to reveal it (`Navigation`'s `Unreachable`) — the undo fails with an error prompt and nothing
   changes. Off-screen, on another tab or under a collapsed branch is *not* a failure; that is what the reveal is
   for. *Ruled 2026-10-13.* Reason: the line between "the user can be shown this" and "they cannot" is the line
   `Reveal` already draws; a second definition of reachable would drift from it.
4. **Disabled refuses, visibly.** A target that is disabled to user edits is still revealed (scrolled to), but is
   not focused: it plays the rejection cue and the undo fails. Reason: the user should see *which* thing is
   blocking the history, and an edit the user could not make by hand should not be made by Ctrl+Z either. The cue
   is a ramped `Cue`, never a hard flash (no visual discontinuity).
5. **Apply as the user would.** The replay goes down the same path as a user edit: the widget shows the old
   value *and* the application's handler fires with it, so the application's model follows with no code of its
   own. That is what makes the default work in applications that never heard of undo.
6. **A conflict may fail.** Before applying, the entry checks that the target still holds the value this edit
   left behind. If something else has written to it since (a `show` from the model, another window), that is a
   conflict and the undo fails. *May*, not must: the entry's policy decides (§4). Reason: a text diff applied to
   text it was not computed against corrupts the document, and the same check costs a scalar one `equals`.
7. **A failure moves nothing.** The entry stays on top of the undo stack, and the redo stack is untouched — so
   redo still works after a failed undo. The reveal step is never recorded, even where navigation is otherwise
   undoable (§5), because recording it would clear the redo stack.
8. **Redo is symmetrical.** It reveals, refuses and fails for exactly the reasons undo does.

**The failure prompt** is a `Modals` dialog naming what could not be undone and why (gone, refused, conflict).
*Ruled 2026-10-13.* Reason: when the target is gone there is nothing on screen to put a cue on, so the message
has to stand on its own; and `Modals` disables every other window while it is up, so a held Ctrl+Z cannot stack
prompts.

## 3. The contract changes

All additive, so every `Change` written today keeps compiling and behaves as it does now.

- **`Change.target()`** — what the entry is about: a node, or an `Address` for a widget that is rebuilt from its
  model (an `Inspector` row, a list item), whose node id does not survive the rebuild. Default `null`: no target,
  no reveal, apply directly — today's behaviour. *Accepted 2026-10-13:* widgets that rebuild need an address to
  be undoable; a node-targeted entry whose node was rebuilt fails as "gone" by rule 3.
- **`Change.check()`** — asked before `apply`, answering ok, conflict or refused. Default ok.
- **`History.undo()` / `redo()` return an outcome**, not a boolean: applied, revealing, gone, refused, conflict,
  empty. The boolean form stays as a convenience.
- **Core reports, the widget layer presents.** The two-step driver (reveal, check, apply) lives in core beside
  `Gui.history`; it needs `Navigator` to navigate to a node as well as to a landmark. The cue and the prompt live
  in `-widget`, so core hands outcomes to a sink (`Gui.onUndoOutcome`) and the framework installs the
  `Modals`/`Cues` presenter for every window — the same inversion `Reveal` uses, and it keeps core free of
  `-widget`.
- **A `Disabled` property** in core. It does not exist yet ([keyboard-focus-text.md](../reference/keyboard-focus-text.md)
  lists it as outstanding) and rule 4 needs it.

## 4. What an application can override

| Seam | Default | Override |
| --- | --- | --- |
| Whether a widget records | on for value widgets | `.undoable(false)` / `.undoable(true)` |
| Grouping | the change's own `coalesce` (a typing run, a drag) | `history.group(...)` for a command that makes several edits as one |
| Failure policy | **block**: the next Ctrl+Z meets the same failure | skip past it, or drop it |
| Conflict policy | fail | apply anyway |
| Presentation | `Modals` prompt, `Cues` rejection | the outcome sink |

Block is the default because skipping an entry undoes the one below it with the one above it still applied; the
conflict check makes that safe for built-in widgets, but not for an application's own changes.

## 5. Scope

**One history per window**, created by the framework shell, bound to the chords and to `dropHistory`, and
injectable. *Ruled 2026-10-13* (scope per window). Reason: two windows on two documents must not share one
stack.

**Text fields stop keeping a private history**; their typing runs become entries in the window's, ended by a
barrier on blur. That fixes today's bug where typing cannot be undone once focus leaves the field.
`TextField.history()` is public and used (the template's `Buffer`, `WorkspacesPage`), so it keeps returning the
history the field records into.

**Navigation is not recorded by default.** Tab changes, selection, expand/collapse and split positions are not
entries unless a widget opts in (`.undoable(true)`). *Ruled 2026-10-13*, as recommended. Reason: the reveal
step already reaches an edit behind a tab, so recording navigation buys no reachability, and recorded browsing
would bury the user's edits under it.

**A document may keep its own history.** One opt-in, and no other nesting: a subtree can declare a history of its
own, in force while focus is inside it — a document tab in an editor. Its entries never enter the window's.
*Ruled 2026-10-13.* Reason: with one window history, editing document A, switching to B and pressing Ctrl+Z
would navigate back to A and undo there; consistent with §2, but unlike every editor, where each document has
its own past.

## 6. Open

- **Conflict default for scalars.** §4 says fail for everything; a toggle overwritten by a sync could reasonably
  apply anyway. Accepted as recommended, to be re-checked once the gallery exercises it.

## 7. Build order

1. `Change.target/check`, the outcome type, `History` returning it. Unit tests, no GUI.
2. `Disabled` in core.
3. Navigate-to-node in `Navigator`; the two-step driver and the outcome sink in `Gui`.
4. The framework shell: a window history, bound, injectable; the `Modals`/`Cues` presenter installed.
5. `TextField` records into the window history.
6. Value widgets record on the user path: `Toggle`, `Slider`, `NumberField`, `Select`, `Segment`,
   `ColorPicker`, `Inspector`/`Property` rows. `Slider` and `NumberField` gain the `onChange`/`onCommit` split
   `ColorPicker` already has, so a drag is one entry.
7. `Button.perform(Change)` and its menu-item counterpart — the framework cannot invert a `Runnable`, so a
   command that is an edit says so by being a `Change`.
8. The gallery: an `ottermate` script that edits every widget, then walks Ctrl+Z and Ctrl+Y through all of it,
   including a gone target, a disabled one and a conflict. Then MainFrame's `WorkspacesPage` drops its hand
   wiring as the witness.
