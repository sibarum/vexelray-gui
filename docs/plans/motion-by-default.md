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
where it got to. `childMotion(false)` and a node not on screen behave as today. **Built.**

### 2.3 Words replaced crossfade

A text write to a node on screen, from words to other words, draws the old words fading out where they were and the
new fading in, over the colour fade's time. Layout takes the new words at once, so the box's size change is the
slide that is already there (it is what made Pix's details look smooth, clipped by their row). An empty string on
either side is an arrival or a departure of the words, and fades the same way. `paintCuts(true)` cuts it, for words
that are data written on every frame (a clock, a meter's readout); a text written on every frame is followed, as a
colour is.

A crossfade replaced by another starts the newest words from nothing over the ones just before. The old words keep
their place in the box. **Built.** The Scheduler's `Motion.Label` and the template's advice to write one go.

### 2.4 An image replaced crossfades

The same for `Node.image`: the new image comes up over the old, and the old goes only in the second half, so two
opaque pictures never let what is behind them show through half-way. The old image stays *named* while it fades
(`GuiApp.names` counts it), so a texture released the moment it was replaced is held by `GuiApp.release` until the
fade has let it go, as the release contract already promised; the reconciler lets go of it once the new one is all
the way up (`settleReplaced`). A feed closed at once is not drawn fading: the new image comes up alone. Words and
images share one channel, `REPLACED`. **Built.**

### 2.5 A clickable node answers the pointer

The framework already infers that a node with a click handler is clickable, for its cursor. It infers the rest: a
clickable node whose background or border is a `Role` is drawn at its pointer state (`Role.at(state)`: lifted
hovered, deeper pressed) unless it hears `NodeEvent.STATE` itself, which says it shades itself. Only the nearest
clickable under the pointer, as for the cursor. Its words are not shaded: the surface lifts, not the text on it. The
dispatcher works it out and Gui writes it as `PropKey.POINTER_STATE`; the reconciler reports the colours it changes,
so it fades as any colour does. A clickable with no role background has nothing to lift, so it is the one case an
application still has to paint. **Built.**

### 2.6 Icons fade, and the title bar is made of nodes

An `IconView` recoloured is a new field in another tint; the tint is its ink, so the reconciler reports it on the
text colour channel and the renderer draws it through the fade. **Built.**

The title bar's plates are box nodes with a role background (rest `NONE`, hovered `RAISED`, pressed `LINE`; close in
`DANGER`), so the framework fades them. Its glyphs stay drawings on the caption grid, which is what keeps a 1 px
stroke on the device's pixels (`CaptionGrid`): an SDF hairline between two pixels is a grey smear. Each is drawn once
per state, in that state's ink over that state's plate, and the state shows one by opacity, which fades (2.1).
Per state rather than recoloured because the restore glyph cuts its front window out in the colour under it.
**Built.** Later: snapping an icon's straight strokes to device pixels in the icon renderer, so the title bar could
use `IconView`s too.

### 2.7 Losing it fails a test

**Built** (`Cuts`, and the template's `ViewTest`). Colours, opacities, words and images given anew and drawn at once, and
nodes appearing whole or gone without leaving; positions are not watched. A value given anew within 40 ms of the last
time is being driven and is followed, the paint motion's own rule. On a freshly generated project its first run found
a node built hidden and shown later appearing whole (fixed in the reconciler: a shown node needs only its parent laid
out), and every view test passes with it; with the framework's motion taken away, ten of fourteen fail. What follows
is the plan as written.

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
