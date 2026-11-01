# Motion and pointer feel by default

**Status: accepted 2026-11-01, being built.** Any application generated from the template should answer the pointer
and change gradually with no code of its own, and that should be hard to lose. Two prereleases in, it is still easy
to lose. This plan says why, and what changes so that it is not.

Context: Pix's UI pass (Pix `docs/framework-notes.md` FN-16 to FN-18, and its retrospective's *A UI pass*), and
vexelray-framework's `docs/developers/motion.md`, whose *Traps* table is the inventory this plan works through.

---

## 1. Why it keeps getting lost

The framework's defaults are good where they reach. `WindowMotion` fades every colour written to a node, slides
every node that layout moved, and fades a node up as it is added and down before it is removed. An application
gets all of that by writing nothing.

Everything they do not reach, an application has to time itself, and that code is where motion is lost:

| Change | Today | What an application writes instead |
| --- | --- | --- |
| An opacity the application sets | one frame | Pix's `Shade` |
| `visible(false)` / `visible(true)` | one frame ("appears where it is") | nothing; Pix's details pop |
| Words replaced | one frame | the Scheduler's `Motion.Label`: dip, swap, dip back |
| An image replaced | one frame | Pix's `Toss.crossfade` |
| Hover and press on a box with a click handler | the pointer cursor is inferred; the shade is not | `NodeEvent.STATE` heard and `Role.at(state)` written, per node |
| A picture's colours (the title bar's buttons) | one frame | nothing (Pix FN-17) |
| `scrollIntoView`, a scrollbar arriving | one frame | "do not reveal by scrolling" |
| A stage resized: layout and an asked half a frame apart | one frame wrong | nothing (Pix FN-18) |

Each "writes instead" row is a clock of the application's own, different in every application, and it is the code a
later reader (a person or an agent) finds hard to follow and takes out, because nothing fails when it goes. Each
"nothing" row is a jump the application lives with.

So the rule this plan follows: **an application states what it wants shown, and the framework times the way there.**
Not one more instrument for applications to learn; fewer.

## 2. What changes

Each step lands with its tests, in the order below, and each removes rows from motion.md's *Traps*.

### 2.1 An opacity set by the application fades

`Node.opacity(v)` is reported to the paint motion as a fourth channel, `Channel.OPACITY`, and drawn through
`PaintMotion.drawn` as the other three are. `PaintMotion` speaks colours, so an opacity is reported as white at that
alpha: `Fades` then blends it linearly (Oklab between one colour and itself is that colour), its "transparent has no
hue" rule is already right for it, and its check for a value written on every frame lets a hand-driven ramp through
untouched. `paintCuts(true)` cuts it as it cuts the colours.

What does not change: an opacity never decided what is hit (a node faded by hand says `hitInert`, or is removed),
and an asked half's `Drawn.opacity` is its own, set for the frame, not faded. **Built.**

### 2.2 `visible()` fades

Hiding a node that is on screen is offered to the exit motion, as `remove()` is: it keeps its place, stops taking
the pointer at once, fades out, and then is hidden for real (where `remove()` would detach it), so the rest close
up. Showing one is an arrival, offered to the enter motion. A node shown again before it had gone comes back up from
where it got to. `childMotion(false)` and a node not on screen behave as today.

### 2.3 Words replaced crossfade

A text write to a node on screen, from words to other words, draws the old words fading out where they were and the
new fading in, over the colour fade's time. Layout takes the new words at once, so the box's size change is the
slide that is already there (it is what made Pix's details look smooth, clipped by their row). An empty string on
either side is an arrival or a departure of the words, and fades the same way. `paintCuts(true)` cuts it, for words
that are data written on every frame (a clock, a meter's readout); a text written on every frame is followed, as a
colour is.

The Scheduler's `Motion.Label` and the template's advice to write one go.

### 2.4 An image replaced crossfades

The same for `Node.image`: the old image fades out in the box as the new one fades in. **The open question** is the
image's lifetime. `Node.image` says an image must stay valid until a frame that no longer names it has been
presented; a crossfade names the old one for 120 ms more. So the framework holds the outgoing image for the fade
and says when it has let go, and an image the framework cannot hold (a handle released at once) is cut, as today.
Decided when this step is built; until then, images cut.

### 2.5 A clickable node answers the pointer

The framework already infers that a node with a click handler is clickable, for its cursor. It infers the rest: a
clickable node whose background, border or text colour is a `Role` is drawn at its pointer state
(`Role.at(state)`: lifted hovered, deeper pressed) unless it hears `NodeEvent.STATE` itself, which says it shades
itself. The press is drawn the frame the button goes down. Colours change through `Fades`, so hover comes up
gradually and press shows at once. `Role.NONE` lifts to a faint surface, so a ghost control is not dead under the
pointer.

### 2.6 Icons fade, and the title bar is made of nodes

An `IconView`'s tint is a colour channel and fades. The title bar's plates become box nodes with a background, and
its glyphs `IconView`s, so they fade like everything else. `Picture` is for drawings (plots, diagrams, diagonals),
not for controls.

The title bar drew its own glyphs to land every stroke on the device's pixel grid (`CaptionGrid`): an SDF hairline
between two pixels is a grey smear. That is an icon's concern, not a drawing's, so the icon renderer takes it on
(snapping a monochrome icon's straight strokes to the window's device pixels) before the title bar moves to icons.

### 2.7 Losing it fails a test

The headless harness gets a **cut detector**: between two frames it compares what each node was drawn with (colour
channels, opacity, position, text, image) and records any that changed by more than a step without a motion source
carrying it, unless the node said it cuts (`paintCuts`, `childMotion(false)`) or a resize or zoom moved it. The
template's generated project gets a test that builds its window headless, walks its slices (open, change, hover,
press) and asserts no unexplained cut, with a failure that says what to do instead. The template's CLAUDE.md gets a
hard rule: motion and pointer feedback are not removed or bypassed; if they are in the way, that is a framework
note.

### 2.8 Then

FN-18 (a stage resized drawn a frame out of step), a glided `scrollIntoView`, and a scrollbar that fades in. Each
has its own note and is taken up after the above.

## 3. What an application keeps

Two opt-outs, each stating a fact rather than a preference: `paintCuts(true)` (this node's look is data) and
`childMotion(false)` (this container moves its own children). Reduced motion is the user's and still lands every
one of these at its end.

## 4. Proof

Pix is the first application on it: its `Shade`, `Toss.crossfade` and hand-wired hover go, and its UI pass's
behaviour stays. motion.md's *Traps* table should end with only what an application can still get wrong by its own
design (rebuilding nodes it should keep).
