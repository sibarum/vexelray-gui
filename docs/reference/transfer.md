# Transfer — drag and drop, cut and paste

One mechanism, two ways in. A **drag** carries something under the pointer; a **cut** carries the same thing in
hand until a **paste** puts it down. They are not two features that happen to resemble each other — they resolve
against the same targets, produce the same change, and record in the same undo stack. The keyboard route is the
pointer route with the position supplied by the selection instead of by the mouse.

This is the record of the decisions. The classes are in `core.drop`, the widget half is `TreeView` +
`Placement`/`Reorder`/`DropIndicator`, and the motion it depends on is `LayoutMotion` (layout-read-model.md §2.1).
Cards — things that leave their place and travel with the hand, rather than rows an indicator points between —
are `Board`/`Deck`, and §12.

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
from a click. Since a release latches rather than drops (§13), such a twitch now leaves the thing in hand where the
user can see it; the rule still holds for the click that puts it down, which a burst of input can deliver in the
same drain as the pick-up. Without it that would land a real, undoable mutation from a drag that never appeared on
screen for a single frame.

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

Escape while a drag is in flight cancels it and goes no further — and so, once the drag is latched (§13), does a
right click, which is used up by cancelling and opens no menu. Two things about Escape are deliberate.

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

It carries the **payload**, because it is published to the whole window and a reader has to be able to tell whose
drag it is. A ghost that names what is in the hand asks `drag.payload().as(type)` and draws nothing for a drag it
cannot read. Before the payload was published, the gallery's Drag chapter drew its task label over every drag in
the window, cards from the Cards chapter included, naming the last task it had seen. A reader that has to keep its
own copy of "what is being dragged" will show a stale one as soon as a different source starts a drag.

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
| **Auto-scroll near an edge** | **Ruled out (2026-10-11), not deferred** — see §13.2. Every edge auto-scroll is rate control: the pointer's position sets a speed, so the hand steers an integrator through a curve somebody tuned, and however smooth the curve the result is a magic spot that races and a margin that crawls. With the drag latched (§13.1) the hand is free, and the wheel — already position control, already reversible, already learned — scrolls underneath the carried thing. |
| **Scrolling a held drag (`DragRelease.DROP`)** | **Deferred, by ruling (2026-10-11): wanted eventually, not now.** With the button held, the wheel still reaches the scroller under the pointer, but a hand holding a button is a hand less free to wheel, so the convention mode needs a scroll of its own. Whatever it is must clear the bar §13.2 sets: position control, not a speed — the pointer's position decides where the content is, never how fast it moves — and every state it has shown without words (§13.3). |
| **Multi-select payloads** | `Payload` can already carry a list; nothing else is designed for it, and it should be driven by a real requirement rather than guessed at. |
| **Decks other than a column** | A row, a grid and free placement are each a deck that measures its slots differently (§12.4). The flights, the gaps, the lift and the drag do not change. A row is the column with the axes swapped; a grid needs gaps that reflow across a line break, which a height alone cannot express; free placement has no slots at all and is a flight that lands where it was let go. Built when an application needs one. |
| **Cards between a board and something else** | A deck takes only its own board's `itemType`, and the board's cards can be dropped on any target that asks for that type (one that moves it makes the card fade where it was let go). The other direction — a tree row dropped into a deck — needs the deck to build a card for an item it did not hand out, which is a conversion only the application can supply. |

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
| A source hears its drag in the frame it moves | `DragWatchTest.heardOnTheFirstFrame` |
| A drop and a cancel end differently | `DragWatchTest.aDropAndACancelAreDistinguishable` |
| Carrying is grabbing | `DragWatchTest.theHandIsClosedWhileCarrying` |
| Lifting a card moves nothing on screen | `BoardTest.liftingIsInvisible` |
| A carried card catches up, then follows exactly | `BoardTest.theCardFollowsTheHand` |
| A landing never overshoots its slot | `BoardTest.aLandingIsContinuous` |
| A deck answers everywhere, one slot per pixel at most | `BoardTest.resolutionIsTotalAndContinuous` |
| Home is not an edit | `BoardTest.homeIsNotAnEdit` |
| A gap wandered back to reopens rather than doubling | `BoardTest.wanderingReopensTheSameGap` |
| Undo flies the same card back | `BoardTest.aDropLands`, `BoardTest.betweenDecks` |
| Letting go keeps the drag; a click drops it | `DragWatchTest.releasingLatches`, `BoardTest.letGoAndClick` |
| A latched right click cancels and opens no menu | `DragWatchTest.aRightClickCancels` |
| A press is armed until it comes loose or comes up | `DragWatchTest.armedThenLooseOrClick`, `BoardTest.heldThenLoose` |
| A click springs back to exactly where it was | `BoardTest.aClickLiftsNothing` |
| A cancel scrolls the lanes back with the card | `BoardTest.cancellingScrollsBack` |
| A still carry, or a still press, owes no frames | `BoardTest.aStillCarryParks`, `BoardTest.aStillPressParks` |
| The wheel glides, accumulates, lands exactly, and yields | `ScrollGlideTest` |

---

## 12. Cards: carrying the thing itself

A tree reorders rows by drawing a line between two of them: the row stays where it is until the drop, and the
indicator says where it will go. That is right for a tree, whose rows are dense, uniform and mostly read as
text. It is wrong for cards — anything a user thinks of as an object with a place — where the object is expected
to come away in the hand and the others to make room for it. `Board<T>` and `Deck<T>` are that, on the same
`DragSession`, `Drop`, `Reorder` and `Placement` as everything above; nothing in the resolution layer changed.

### 12.1 Nothing jumps, and the reason it holds everywhere

The requirement is the stack's standing rule — no flash, no flicker, no sudden change in anything the eye is
already tracking — and the design is what makes it a property rather than a list of cases somebody remembered. **Every card is in a slot or in flight.** A slot is a
box in a deck whose height can be animated; a flight is the card floating over the window. Every change there is
— a drop, a cancel, undo, redo, a card arriving, one leaving, a shuffle, a keyboard move — is one of four things
done to those: a slot opens, a slot closes, a card flies from where it is drawn to a slot, a card fades.

Two rules then cover every case at once. **A card changes parent only when its two places are drawn identically**:
lifting leaves a gap exactly as tall as the card in exactly its place and floats the card at exactly where it was,
and landing waits until the travel has finished *and* the gap is fully open at the card's own height. And **every
animation starts from where things are drawn**, so one that is interrupted — a pointer that wanders off a slot and
back, an undo pressed while the drop is still settling — turns rather than restarts.

Undo animating is not a feature. A deck is handed lists by the application, and moves the difference into place
whatever caused it: the cards that are already in the list's order stay (the longest such run), everything else
flies from where it is drawn to a gap opened for it. An undone drop is just the old list.

### 12.2 The card the user grabbed, where they grabbed it

The card the hand carries is the application's own node, not a ghost of it: the board builds one per item and
moves that node everywhere it goes, so whatever state the application keeps on it survives the trip. Two things
on it are the board's — its elevation, which is how a card shows it has been picked up, and the spacing around
it, which belongs to the card rather than the deck so that a gap opened for it is exactly its height.

It is held at the point where it was grabbed. The drag is recognised only after the pointer has travelled the
threshold, so at that moment the card is already a few pixels behind; it catches that up over the first moment of
the drag rather than jumping it, then follows exactly. A drag that lagged permanently would be the slippery feel
`Transitions` warns about; a drag that jumped would be the discontinuity this section exists to prevent.

### 12.3 Drawn the frame it moves: `DragWatch`

`Gui.drag()` publishes the drag after layout, so a node written from it lands a frame late — invisible for an
indicator, visible for the thing in the user's hand, and a card's width behind on a fast flick. `gui.watchDrag`
tells a watcher about the drag during input dispatch, ahead of the drain, so what it writes is laid out in the frame
whose pointer position it was computed from.

It also says how the drag **ended**. A published `DragState` goes to `NONE` for a drop and for a cancel alike, and
those are opposite animations: a drop settles the card where it was put, a cancel flies it home. §5 always said
the return was the caller's to animate; until this, the caller could not tell it was owed one. A watch is told
after the drop has moved focus, so the board — whose card flies pointer-transparent, so that the hit at the release
point is the lane beneath it — can put focus on the card that was just put down, which is what §6 asks for.

### 12.4 Where a drop lands

Wherever the carried card is nearest to, measured on the deck **as it would stand with the carried card taken
out**. That is what keeps the answer still while the gap it causes opens: a boundary measured on the deck as drawn
travels with the animation, so a pointer resting near one flips the answer, which opens a different gap, which
moves the boundary back. Measured without the card, the boundary between two slots is fixed — half-way between
where the card would sit in each — and a pixel of movement changes the answer to a neighbour or not at all, which
is §4's continuity requirement.

Nearest *by the card*, not by the pointer: a card is held wherever it was grabbed, and it is the card the user is
aiming. The slot it came from answers `Drop.NONE`, because putting a card back is not an edit — and is drawn as
exactly that, the card's own gap held open, which is also what is drawn over nothing at all. Over a target that
is not this board's, every gap closes, because the card would go there.

This is the one part a different layout replaces. A column sums heights; a row would sum widths; a grid needs
gaps that reflow across a line break. The flights, the gaps, the lift and the drag are the board's and are the
same for all of them.

### 12.5 One outline

A carried card is ringed in the accent, inside its own corners. A focused card already has the focus ring, and
two accent outlines a few pixels apart is one too many — so while a card is up its focus ring is off, and if the
ring was showing when the card was picked up the outline starts at full strength rather than fading in, so the
ring is replaced rather than blinking out. The ring comes back when the card has set down.

---

## 13. Picking up is a gesture

### 13.1 Letting go of the button: the user's choice, keeping by default

A drag is recognised as before — a press that travels and holds. What releasing the button then means is a
preference, `Gui.dragRelease`, with two answers (`DragRelease`):

- **`KEEP`, the default.** Releasing **latches** the drag: the thing stays with the pointer, the button is free, and
  the next left click puts it down where it is aimed. Escape, or a right click, takes it home. `DragSession.latch`
  is the state; the dispatcher's `latchedPointer` is the protocol, and it consumes the click that drops and the
  click that cancels together with their releases, so neither is also a click on whatever was underneath.
- **`DROP`, the convention.** The button is held for the carry and releasing puts the thing down where the pointer
  is — what almost every other program does. Escape still cancels.

Everything else is shared — the threshold, the armed state, the resolution, the history, the unseen-drop rule — so
this is one fact about the ending rather than two drag protocols. Changing it mid-carry does not change a drag
already latched: the user is mid-gesture, and it ends the way it began.

**Why keep by default (ruled 2026-10-11, by the user):** holding a button for the length of a carry is what makes
the far end of a long list a feat of grip, and what turns a slipped finger into a drop in the wrong place. Picking
something up is a gesture; carrying it should not be a posture.

**Why both (ruled 2026-10-11, by the user):** some users will want the familiar one, and should not have to unlearn
twenty years of other software to use this. So it is a setting, and it is the user's: an application wires it to a
preference they can reach. It is one setting for the whole tree, not per widget, because two endings in one
application would be the one inconsistency a user cannot learn around.

### 13.2 The wheel, not the edge

With the hand free, scrolling while carrying is the wheel, which already reached the scroller under the pointer.

**Why (ruled 2026-10-11, by the user):** edge auto-scroll was ruled out above (§10), and the reason is the order of
the relationship, not its tuning. A position-to-speed mapping makes the hand steer an integrator: hold still and
the content keeps moving, and the only control left is how long to wait. The wheel is position control — a notch
is a fixed distance, the next one back undoes it, and time plays no part — and every user already has one.

What the wheel lacked was delivery: a notch moved the content three ems in one frame, the slideshow scrolling the
eye has to re-find its place after. It now glides (`ScrollGlide`, opted into with `Gui.scrollGlide` and turned on
by the framework's shell for every window): a notch moves where the view is heading and the view closes half the
remaining distance per half-life, so notches add up, the speed is the same at any frame rate, and there is no end
to jump at. A glide gives way to anything else that moves the view — the thumb, a reveal, a scroll lock — rather
than pulling against it.

### 13.3 Every state shows, and none of them in words

**Ruled 2026-10-11, by the user:** every state the drag can be in has its own visible sign, acting out what the
state means, and none of it is text. The states and their signs, as built:

| State | Means | Shows as |
|---|---|---|
| Hover over a card | can be picked up | the open hand — the one thing hover may change |
| Pressed, before the threshold | held, not yet loose | the card sinks a rung toward its lane and follows the pointer part of the way, held back (`DragWatch.armed`) |
| The threshold | loose | the card catches up with the hand and rises: the transition point |
| Carried, button down | gripped | top rung of elevation, the accent outline, the closed hand |
| Carried, button up | hanging from the pointer | a rung lower, and the open hand |
| Over a slot that takes it | it goes here | that lane opens a gap with the landing pad |
| Over home, nothing, or a refusal | letting go sends it home | its own gap reopens, with the pad |
| Over a target that is not a lane | it will leave | every gap closes |
| Wheel while carrying | the lane moves under it | the lane glides; the gap follows |
| Dropped | settling | the card flies into its gap and sinks to rest, focused |
| Cancelled | going back | lanes scroll back, the card flies home, the pointer rides on it |

A click that never came loose springs back from its pull and rises from its sink: the press showed, and so does
its ending.

### 13.4 A cancel undoes the gesture, not only the card

Escape puts back everything the carry changed: every lane the wheel moved scrolls back to where it was looking when
the card came loose, the card flies home, and the pointer rides home on it at the point it was gripped — all on the
card's own travel, a step per frame, so the card arrives in a slot that has stopped moving and the pointer ends
where the drag began, over the card, which is focused.

The pointer is moved through `Gui.ridePointer`, which the host backs with `Gui.onPointerWarp` (tactroller's new
`warpPointer`). **The hand wins**: a ride checks each step against where the pointer actually is, and once the user
has moved it themselves, every later step is refused and the card goes home alone. Moving a pointer the user has
started moving would be the worst kind of jump — one that fights them.

`Node.scrollTo` is the instrument the lanes are scrolled back with: immediate, so a lane and the card going home
are written in one batch and land on one frame, on one clock. A second clock is how a card arrives in a slot that is
still moving.
