# Transfer — drag and drop, cut and paste

One mechanism, two ways in. A **drag** carries something under the pointer; a **cut** carries the same thing in
hand until a **paste** puts it down. They are not two features that happen to resemble each other — they resolve
against the same targets, produce the same change, and record in the same undo stack. The keyboard route is the
pointer route with the position supplied by the selection instead of by the mouse.

This is the record of the decisions. The classes are in `core.drop`, the widget half is `TreeView` +
`Placement`/`Reorder`/`DropIndicator`, and the motion it depends on is `LayoutMotion` (layout-read-model.md §2.1).

---

## 1. The invariant

**What the user is shown is what will happen, and nothing happens that the user was not shown.**

Every failure this design exists to prevent is a violation of one half of that:

- an indicator drawn between two rows while the drop inserts under a third
- a cursor promising a copy while the change performs a move
- a region that looks droppable and silently does nothing
- a click too fast to see that lands a real edit
- an edit that lands with no way back

Each is discovered *after* letting go, which is the worst moment to discover anything. The design chases that
one property rather than a feature list, and most of what follows is a consequence of it.

---

## 2. Sources are plural; resolution is singular

A drag can begin in four different places, and only one of them is a press:

| Source | Opens the session | Status |
|---|---|---|
| The pointer, in this window | `DragGesture` recognises press-move-release | **built** |
| The keyboard | a cut or copy puts a `Transfer` in hand | **built** |
| Another window of this process | the session is lifted to `GuiApp` | deferred, §8 |
| Another process (OS file drag) | `IDropTarget` in `vexelray-os` | deferred, §8 |

The last two matter now even though they are not built, because they rule out a design that would otherwise be
natural. An OS file drag **arrives already in flight** — no press, no button, no gesture, and the payload handed
over at `DragEnter`. A session that could only be created by a `DragStarted` would exclude it by construction.
So `DragSession.open` takes a payload and a position, and nothing about how the drag began.

What every source shares is exactly what the session holds: a payload complete up front, a position that changes,
and an ending that is either a drop or a cancel. Thresholds, capture, which button, whether Escape or the OS ends
it — all of that belongs to the source and is not represented in the session at all.

Resolution, by contrast, is one thing. Whatever the source, the question at the far end is identical: *what is
under this point, will it take this, and what would it draw?*

---

## 3. `Drop` is one value, and that is the whole trick

```java
record Drop(DropEffect effect, Rect indicator, Change change)
```

The effect, the picture and the mutation are fields of one record because a drop resolved in three places is a
drop that can lie. A target that wants to move the indicator has to change what the drop does, because there is
only one thing to change. The constructor rejects both ways of building one that cannot be honoured — an
accepting effect with nothing to perform, and a change carried under `NONE`.

Carrying the `Change` rather than re-deriving it at release is the same argument one step on. A *position*
outlives the layout that gave it meaning: one scroll, one expanded folder, and "row 4 of the visible list" is a
different row. The change was built while the resolution was true.

`DropEffect` exists as a value rather than a boolean for two reasons. `IDropTarget::DragOver` demands a `DWORD`
effect back synchronously, so a target that could not answer one would be unable to participate in OS drops at
all. And the user is owed the same answer: a move and a copy leave the model in different states, and which is
about to happen has to be legible before the button comes up.

---

## 4. Resolution is total

A `DropTarget` is asked about **any** point inside its box and must answer for all of them. `Drop.NONE` is a
legitimate answer; the point is that it is an *answer*, chosen, rather than a gap nobody considered.

Two mechanisms make totality survive contact with a real tree:

**Declines bubble.** `DragSession.moveTo` walks the targets under the pointer innermost-first and takes the first
that accepts. A row that refuses a foreign payload therefore does not punch a row-shaped hole in a tree that was
perfectly willing to take it — its container is asked next.

**The row is chosen by which one the pointer has not gone past**, not by which rect contains the point. A
sub-pixel gap between two rows would otherwise be a place where drops silently fail, and that is exactly the
crack a user learns to hunt for. `TreeReorderTest` sweeps every whole pixel from the first row's top to the last
row's bottom and asserts none of them resolves to nothing.

Within a row the partition is: a row that **accepts children** divides into three bands — an outer quarter each
side for *before* and *after*, the middle half for *into* — and one that does not divides in two at its middle.
The test is `Source.acceptsChildren`, not "has children" and not "can expand": an empty folder still takes a
drop, and a row that will never take one gets no "into" band at all, because a band that always refused would be
a third of the row that looks live and is not. Below the last row is the root, which is the only way to drag
something out of a branch.

---

## 5. A drop the user never saw is not a drop

`DragSession.commit` refuses unless the session was rendered at least once.

This is the protection against the gesture that was meant to be a click. A fast press-twitch-release satisfies
any distance threshold — `DragGesture` reports it as a drag, correctly, because distance is what separates a drag
from a click. Without this rule it would land a real, undoable mutation from a drag that never appeared on screen
for a single frame.

Thresholds help and are tuned for it — the drop path uses 6px/90ms where the slider path uses 2px and no delay,
because a tree row is usually clicked and has to survive the hand movement of an ordinary click. But thresholds
are tuning. This is the invariant, and it lives at the commit rather than at the recogniser so that it holds for
every source, including the ones with no thresholds at all.

The counterpart is that **a cancel records nothing**. Not record-then-undo: `History.record` discards the redo
stack, so a cancelled drag would silently destroy work the user could otherwise have redone — a worse outcome
than the drag they just abandoned.

---

## 6. Undo, and which history hears about it

A drop commits through `History.perform`, so it is undone like any other edit. Nothing in `core.drop` knows what
a tree is; the change came from the application.

Two decisions were forced here.

**A completed drop moves focus** to what a click at the drop point would focus. `Gui.history` resolves Ctrl+Z by
focus, and a drag is a pointer gesture with no focus relationship of its own — so dropping a row while a text
field still held focus would send the user's first undo to the field's typing history instead of to the drop they
just made. Focusing the drop point realigns the two, and is what the user expects anyway: the thing just moved is
the thing now being acted on.

**Drops and pastes share one history.** `Gui.dropHistory` is readable for exactly this reason: a paste makes the
same change from the keyboard and must land in the same stack. Two stacks would mean Ctrl+Z undoing whichever
*kind* of transfer the user last did rather than the last thing they did.

**Without a history, a transfer resolves and draws but commits nothing.** Deliberately: a mutation with no way
back, happening silently, is worse than one that does not happen.

---

## 7. Escape belongs to the drag

Escape while a drag is in flight cancels it and goes no further. Two things about that are deliberate.

**`DragGesture` is fed pointer events only.** It cancels on Escape if it is fed keys — and it *observes* the
stream rather than consuming from it, so it would cancel the drag and the same key would go on to dismiss a modal
or close a context menu. One key, two actions. The filter is in `feedDragGesture`, and the test that pins it
failed on its first run for precisely this reason, because the rule had been written in a comment and not in the
code.

**It is not expressed as a claim.** A drag is modal while it lasts — there is no reading of Escape mid-drag that
means anything but "not this one" — and claims resolve a single winner by scope, so a drag would have to outrank
both an open menu's `VISIBLE` claim and a modal's `GLOBAL` one. A scope above `GLOBAL` meaning "except during a
drag" would put a gesture's lifetime into a system that answers *where a key is meaningful*, which is a different
question. So it is handled ahead of claim resolution, in `handleKeyDown`.

---

## 8. Drawing it

The drag reaches a widget as **published state**, not as the live session. `DragSession` is the framework's own
state, mutated on the GUI thread inside the frame; a widget reading it would be reading a value that changes
underneath its own paint, and would have to be on the GUI thread to read it at all. So `DragState` is published
as a coalesced `State` — the pattern layout-read-model.md already names, rather than another per-feature callback.
Latest-wins is right for a drag: a subscriber that missed an intermediate position missed nothing it could still
have drawn.

It is published **after displacement**, so the indicator and the rows it sits between are never a frame out of
step, and **on change only**, so a pointer held still over one seam costs nothing.

`DropIndicator` is told the rectangle and decides only the appearance. Where it goes is not negotiable — it comes
from the same resolution that produced the change. Which *kind* of drop it is comes from the geometry rather than
a flag beside it: a seam between two things is thin, a thing is its whole box. A flag would be a second copy of
the same fact, free to drift from the rectangle it describes.

The default rings a box rather than filling it, because a fill over a row competes with the row's own selected and
hovered states — three fills on one strip and the user has to learn which is which.

**Motion is a separate layer and this depends on it.** "Nothing ever jumps" is not something drag-and-drop can
provide: the model change is instantaneous and correct, and it is the *view* that has to catch up. That is
`LayoutMotion`, and undo gets it for free — a `Change` that puts a row back is a layout change like any other, and
nothing in the motion layer knows which direction the user was going.

---

## 9. Cut, copy and paste are the same thing

`Transfer` is a `Payload` plus a `DropEffect`: a cut is a `MOVE` in hand, a copy is a `COPY` in hand, a paste is a
placement resolved at the selection instead of under the pointer. Saying so in the type is what stops the keyboard
route growing a parallel vocabulary — refusal, the change and the undo record are then the same code.

It is **one per `Gui`**, so two trees in a window can exchange rows and one pane can paste what another cut. It is
deliberately not the OS clipboard: that carries text, and a typed payload has nowhere to ride across a process
boundary. That is a real boundary, not a temporary one, and pretending otherwise would mean a paste that silently
did nothing in the case a user most expects to work.

Unlike the drag it is not a `State`: it changes when a command puts something in it and at no other time, so
there is nothing to observe per frame. A menu greys its Paste entry by reading it when the menu is built, which
is when the answer matters.

---

## 10. What is deferred, and why

| Deferred | Why it is not built, and what it would need |
|---|---|
| **Cross-window, same process** | The capture work already makes this tractable — only the window that saw the press gets the motion and the release, so there is exactly one session and no ambiguity about the source. What it needs is the session lifted from `Gui` to `GuiApp`, because each window has its own `Gui`, bus and dispatcher. The drop window must be resolved by asking each window's `isPointerTarget()` (which the OS answers, so occlusion and z-order come free) rather than by rectangle maths over window bounds. **A cross-window drag must not take the pointer lock** — `ClipCursor`/`SetCursorPos` are process-global, and the lock destroys the absolute position a drop needs. |
| **OS file drop** | Cannot come through Tactroller at all: the source process owns the mouse, so there is no `ButtonPressed` in this process and `DragGesture` is blind to it by construction. `RegisterDragDrop` needs the real HWND on an STA thread with a message pump — Tactroller attaches to an HWND it does not own and pumps a message-only window on its own thread. The real window proc is in `vexelray-os-windows`, alongside the client-decoration work, and that is where it belongs. |
| **Auto-scroll near an edge** | Not an oversight. Scroll-while-dragging works today because the wheel is routed positionally and never consults the drag. An edge-triggered auto-scroll changes layout in response to hovering, which conflicts with the rule that hovering must never move the pointer's target. If it is built it needs its own visual cue and a deliberate decision, not an inherited convention. |
| **Multi-select payloads** | `Payload` can already carry a list; nothing else is designed for it, and it should be driven by a real requirement rather than guessed at. |

The first two share a shape worth stating: **drag sources are plural and platform-shaped; drop resolution is
singular and lives in the GUI.** Adding a source should touch nothing in `DropTarget`, `Drop` or `DragSession`.

---

## 11. Tests that pin the decisions

| Property | Test |
|---|---|
| Declines bubble rather than veto | `DragSessionTest.declinesBubbleRatherThanBlock` |
| Indicator and commit are the same value | `DragSessionTest.theIndicatorIsTheCommittedResolution` |
| An unseen drop does not commit | `DragSessionTest.anUnseenDropIsNotADrop`, `TreeReorderTest.aFlickDoesNotReorganise` |
| A cancel keeps the redo stack | `DragSessionTest.cancellingRecordsNothingAndKeepsRedo` |
| Escape is not also a claim | `DropGestureTest.escapeDuringADragIsNotAlsoAClaim` |
| Focus follows the drop | `DropGestureTest.dropMovesFocus` |
| No dead pixels over the rows | `TreeReorderTest.thereAreNoDeadPixels` |
| Displacement never accumulates | `DisplacementTest.displacingTwiceWithoutARelayoutIsTheSameAsDisplacingOnce` |
| An interrupted transition does not snap | `TransitionsTest.interruptionIsContinuous` |
