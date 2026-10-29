# The frame contract: one way for code to run on the GUI thread

Status: **Proposed 2026-10-29, not started.** Supersedes *pacers.md* (committed as `06f27c2` the same day), which
proposed a third exception to T7.1 for video; the author pointed out that pacing is drawing, and that drawing,
presentation and pacing should be one API. The ask first, then what the code does today, then the design, how each
guarantee is held, what moves onto it, the questions it leaves open, and the order of work. It answers the second
ruling in Pix's port inventory (`Pix/docs/framework-notes.md`, *vexelray-codec: playback has no message form yet*):
how a video is paced in 0.2. It builds on [tree-thread.md](tree-thread.md), [frame-loop.md](frame-loop.md) and
vexelray-framework's `threading.md` (T7.1 to T7.4, and the declared-step exception under T7.2).

## The ask

The author's, 2026-10-29: a general way to extend what the render thread does each frame, **as safe as it can be
made without costing performance**, of the same family as `RenderTechnique` (an SPI the engine drives, under a
written thread contract) and `Look` (an appearance stated as data, naming parts composed ahead of time). And then:
**drawing, presentation and video are one thing, and follow one API.**

Ruled with it: **the codec's views (`ImageView`, `VideoView`, `Playback`) and vex-suite-common's `suite.view` are
widget libraries.**

## What happens today

- **T7.1 named two exceptions** to "no application code on the main thread": drawing and presentation (T7.4).
  (Restated 2026-10-29 as one, T7.6, by this plan's step 1.)
- **Presentation is "told".** A `@View`'s `@Subscribe` methods are drained in the widget tick; it hears a message
  and writes its widgets and nodes.
- **Drawing is "told" too.** A node holds a `Picture` (`Node.picture`), a value a view builds with `Sketch` when it
  hears that something changed; `TreeRenderer.emit` turns it into canvas vertices. No application code runs at emit.
- **Code that is "asked" at draw time exists, but only inside the framework.** `PaintMotion` is told a colour
  changed (`repainted`) and asked what to draw now (`drawn`, called by the renderer), and says whether it is still
  `moving`. `LayoutMotion` is the same for position. The `Clock` behind `Timing` and `Tween` runs a function per
  step. This is the shape every moving thing needs, and no library or application can reach it.
- **So a moving picture has nowhere to go.** The codec's players are `krono.spork` loops (`Playback:466`,
  `VideoView:216`, `ImageView:620`) that poll every `POLL_MS` and meet input and the decoder in one locked object;
  gui step 18 took `KronoGui` away and gave nothing for *which of these frames is due now*. `Tween` covers a value
  from 0 to 1, at the cost of a bus message per step.
- **The GPU half is already right.** `StreamTexture` is a ring of slots written on any thread, submitted and shown
  on the frame's thread, and a node names `live()`, never a slot.

## The design

### A. One part shape, two halves

Everything that runs on the GUI thread is a **part**: a `@View`, a widget, or the framework's own motion. A part
has one immutable state and two declared halves, and nothing else of it runs on the GUI thread:

| Half | When it runs | Signature | Does | Today's name for it |
| --- | --- | --- | --- | --- |
| **told** | a message arrives, in the widget tick | a declared step, `(state, message, out) → state` | changes the part's state; builds what is costly (a `Picture`, a node, a feed request); sends | presentation (T7.4), drawing (T7.1) |
| **asked** | at emit, sampled for the frame's present time | a declared function, `(state, frame, drawn) → next due` | chooses and parameterises what its nodes show *now*: which picture, which slot, an offset, an alpha, a colour | `PaintMotion.drawn`, `LayoutMotion`, the `Clock`, `krono.spork` |

**Told builds, asked chooses.** That one line is the whole division of labour, and it is what makes the asked half
cheap enough to run every frame: anything that allocates (a picture, a node, a string) is built when something
changed, and the asked half only picks among what the told half built and sets numbers on it. A still part has no
asked half at all and costs what it costs today.

A declared step is already ruled safe under T7.2 (*a declared step sent to the state that runs it is not a
callback*), with its four rules and atchung's `Ledger`. The asked half is the same kind of thing: a static function
the part declares on its own state, capturing nothing, checked at compile time. So **T7.1 is restated with one
exception, not three: the GUI thread runs a part's declared steps and its declared draw, and nothing else.**

### B. The asked half

```java
@Drawn
static long draw(Player.State s, Frame frame, Drawn out) {
    int slot = s.feed().dueAt(frame.presentNanos(), s.timebase());
    out.slot(s.screen(), slot);
    return s.playing() ? s.feed().nextStampAfter(slot) : Frame.NEVER;
}
```

- **`Frame`** carries the present time, the presenter's predicted `NextPresent` (tree-thread.md B), not the moment
  emit began; the window's scale; and the reduced-motion flag. A part picks for the moment its picture is seen.
- **`Drawn`** is a sink the framework preallocates per part: it addresses the part's own nodes, by the handles the
  told half made, and sets what can change per frame (picture choice, stream slot, offset, scale, alpha, paint).
  It cannot add, remove or restructure nodes: that is the told half's, in the tick, where layout follows.
- **The return is when the part next needs asking**, or `Frame.NEVER`. It replaces `PaintMotion.moving()`, `Tween`'s
  per-step messages and the codec's `POLL_MS`. The main thread parks until the earliest due time of any part on
  screen or an OS message, so draw-on-change (frame-loop.md rule 2) holds: a paused video, a settled fade or a
  hidden part costs no frame.
- **It may not send.** What it would report (a video's position, that it ended) the told half derives instead: the
  framework publishes a part's due-time crossings as a state topic it can hear (C), at a rate the part declares.

### C. Time as a told input

A part that must *act* on time passing (a seek bar's position, a video that ended and should say so, a fade that
should then remove its node) does that in its told half, from a message: the framework's `Timer` (built,
`TimerTest`), or the part's own **clock topic**, which the framework publishes for a part with an asked half at the
rate it declares (10 Hz for a seek bar), carrying the present time it last drew for. So reporting is a message like
any other, sent from a step, and the asked half stays pure.

### D. The feed: how a producer hands frames in

The one new piece of shared memory, and the only thing left of *pacers.md* in its own form. A **feed** is
`StreamTexture` with a presentation stamp on each slot, stored with a release write when the producer hands the slot
over: single producer, single consumer, fixed slots, no lock in the read. It is the kind of seam T7.3 already allows
("where a foreign thread hands something in").

- **A producer** is anything off the GUI thread: the codec's decoder threads (infrastructure), a component on a
  declared lane (the fluid simulator), a job. It never waits on the frame; `take` returns null when the ring is full,
  which is the backpressure.
- **Landing is ahead of showing.** The host submits a handed-over slot's copy on the next frame; an asked half may
  choose only a landed slot, so choosing costs no transfer.
- **Pixels never cross the bus.** The told half asks for a feed (a request to the host, like any GPU resource in
  tree-thread.md D) and keeps its handle in its state.

### E. Where it runs

At emit, where `PaintMotion.drawn` already runs: on the main thread now, and on the tree thread if tree-thread.md
goes ahead, which already samples the present time for its emit. One consequence to measure: a playing video then
re-emits the canvas once per video frame. If that costs, the renderer can resolve a slot choice at record instead of
at emit, as `StreamTexture.live()` already does for the descriptor. That is an implementation detail behind the same
contract, decided by step 0's numbers, not a second API.

## How each guarantee is held, earliest first

| Guarantee | Held by |
| --- | --- |
| Only declared halves run on the GUI thread | the processor: a part's told steps and `@Drawn` function are found from declarations, and the generated wiring calls nothing else (compile time) |
| A told step is a step | the existing step check (`StepCheckTest`) |
| An asked half is pure and cheap | the same check, plus: static, reads only its parameters, and no `new`, no array or string building, no locks, I/O, logging, `Thread` or bus (`DrawnCheckTest`). After the step check's principle: it never refuses safe code, only what is certainly unsafe |
| No application code holds a frame hook | the SPI's host side is in `spi`, reached from no `Gui` method (`GuiSurfaceTest`); a `@View` importing `spi` is refused (owed with `@View`) |
| A slow draw cannot hold a window | the framework times every asked half against a budget; over it for a run of frames, the part is frozen on what it last drew and reported through `Diagnostics`. A stuck picture, loudly, never a stuck window |
| The feed is the only shared memory | T7.3's allowlist names it; the architecture test reads the sources |
| Single-thread mode changes nothing | the halves run in the same places in both modes; one witness run in both, identical told logs |

The same checks now cover drawing and presentation, which today are held by convention and the T7.2 walk alone.

## What moves onto it

| Today | In 0.2 |
| --- | --- |
| a `@View`'s `@Subscribe` methods | its told half, unchanged in form; they become checked steps when phase 4's `@View` checks land |
| a view writing a `Picture` on a message | told, unchanged |
| `PaintMotion`, `LayoutMotion` | framework parts with an asked half: `repainted` and `moved` are told, `drawn` and the displacement are asked, `moving()` is the due time. Their behaviour does not change; they become the first users of the contract, which proves it carries real motion |
| `Tween`, `Timing` run by the `Clock` | `Timing` stays the data a widget is given; a view that draws its own motion holds a target and a start time in its state and is asked, rather than hearing a bus message per step. `Tween.VALUE` stays for a view that wants the value as a message |
| `krono.spork`, a video (`Playback:466`, `VideoView:216`) | a codec widget whose state holds the feed, the transport (playing, rate, loop, sought to) and the timebase; asked picks the due slot; `Play`, `Pause`, `Seek` are told; `Position` and `Ended` come from its clock topic. `Playback`'s loop, `POLL_MS` and its locks are deleted |
| `krono.spork`, an animated image (`ImageView:620`) | the same, with per-frame durations on wall time |
| `krono.spork`, the viewer's inertia (`PictureViewer:1020`) | an asked half: a velocity and a decay in state, the offset computed for the present time |
| `krono.ramp`, fades (`ImageView:463`, `PictureViewer:1281, 1570`) | `Timing` on a widget, or an asked alpha |
| `krono.after`, settle and idle (`PictureViewer:1033, 1541`) | `Timer.after`, told |

The decoder threads, `Sound` and `Track` stay: they are producers behind the feed, and the inventory already lists
them as no offence.

## Performance

- **A still window costs nothing new.** No asked halves due, no frame.
- **Per frame, per part asked:** a static call with no allocation, timed. For a video, a search over a handful of
  stamps and at most one slot shown.
- **Per moving thing, against today:** one bus message per animation step (`Tween`) or a timeline loop polling every
  `POLL_MS` (`krono.spork`) becomes one call at emit, sampled for the present time rather than whenever a thread
  woke. Frame-to-photon jitter for video should fall; step 0 says by how much.
- **What it costs:** asked halves run in the frame, so a heavy one is the frame's cost. The check and the budget keep
  that to microseconds, and say so when it is not.

## Open questions

1. **Several windows on one feed.** A slot shown is the feed's. Leaning: a feed belongs to one window, and a second
   view of the same movie is a second feed from the same decoder.
2. **The timebase.** Sound's played-sample counter as a read-only monotonic value the asked half reads; who corrects
   it for the audio device's latency is the codec's to say.
3. **What `Drawn` may set.** Picture choice, slot, offset, scale, alpha and paint cover every site above. Anything
   more (a `Picture` parameter, a shadelet's shared value) is added when a witness needs it, not ahead.
4. **The budget.** 50 µs per asked half is a guess; step 0 measures the codec's pick inside its loop today.
5. **Order within emit.** Asked halves before `PaintMotion`'s own, or the framework's motion parts are just parts in
   tree order. Leaning tree order, so there is no special case.
6. **Re-emit per video frame** (E), decided by step 0.

## Order of work

Each step lands on its own and leaves the suite green.

0. **Measure today.** The codec's demo player on a 60 s clip at 24, 30 and 60 fps on a 144 Hz display: intervals
   between shown frames, duplicated and dropped frames, the offset from sound, and the cost of an emit. The number to
   beat, repeated at step 5.
1. ~~**Argue it in vexelray-framework's `threading.md`.**~~ **Done 2026-10-29:** T7.1 restated with one exception;
   T7.6 states the told and asked halves, with T7.4 and the drawing exception folded into it; T7.3 names the feed;
   the reasons are in vexelray-framework's `architecture.md`, *the message model*.
2. **The contract in vexelray-gui's host, with the framework as its first user.** `Frame`, `Drawn`, due times and
   parking; `PaintMotion` and `LayoutMotion` moved onto it with no change in behaviour, held by their existing tests.
3. **The processor's check** (`DrawnCheckTest`) and `@Drawn` on a part, in vexelray-framework.
4. **Stamps on `StreamTexture`** (vexelray-vulkan), feeds requested by message, landing a frame ahead.
5. **The codec on it.** The video and animated-image widgets as parts; `Playback`'s loop deleted. Measure against
   step 0.
6. **A third witness, not a player:** the fluid simulator's field through a feed, its producer on a declared lane,
   so the contract is shown to be general rather than shaped round video.

## Decided

- 2026-10-29: the codec's views and vex-suite-common's `suite.view` are widget libraries.
- 2026-10-29: drawing, presentation and pacing are one contract, not three exceptions (this document).
