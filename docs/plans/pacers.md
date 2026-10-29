# Pacers: library code on the render thread, declared and checked

Status: **Proposed 2026-10-29, not started.** The ask first, then what the code does today, then the design, how
each guarantee is held, what it replaces, the questions it leaves open, and the order of work. It answers the second
ruling in Pix's port inventory (`Pix/docs/framework-notes.md`, *vexelray-codec: playback has no message form yet*):
how a video is paced in 0.2. It builds on [tree-thread.md](tree-thread.md) (`NextPresent`), on
[frame-loop.md](frame-loop.md) (draw on change), and on vexelray-framework's `threading.md` (T1.1, T7.1 to T7.3).
It adds no lane, but it names a third exception to T7.1, so that has to be argued there before any code (step 1).

## The ask

The author's, 2026-10-29: a general way to extend what the render thread does each frame, **as safe as it can be
made without costing performance**, of the same family as `RenderTechnique` (an SPI the engine drives, with a
written thread contract) and `Look` (an element's appearance stated as data, naming parts composed ahead of time).

The first ruling came with it: **the codec's views (`ImageView`, `VideoView`, `Playback`) and vex-suite-common's
`suite.view` are widget libraries.** They may use `Seams`, and they say what happened as messages.

## What happens today

- **Every moving picture runs a loop of its own.** `Playback.run` (`vexelray-codec`, `Playback:466`) is a
  `krono.spork` that, until its generation changes, asks the movie which frame is due, hands it to `present`, and
  otherwise sleeps `POLL_MS` and asks again. `ImageView:620` does the same for an animated image, and
  `PictureViewer:1020` for the viewer's inertia. Each is arbitrary code on the timeline, reading state that input,
  the decoder and the frame also touch, which is why `PictureViewer` carries 16 `volatile`s and 24 `synchronized`
  sites.
- **The GPU half is already right.** `StreamTexture` (vexelray-vulkan) is a ring of slots: `take` and the writing of
  a slot's levels on any thread, `submit` and `show` on the thread that submits frames, and a node names
  `live()`, the stream, never a slot. A new video frame changes nothing a tree holds; it is a slot shown and a frame
  asked for (`onShown`).
- **0.2 has taken the loop's door away and given nothing for it.** Gui step 18 removed `KronoGui` from
  applications. A widget's motion is a `Timing` the tree's `Clock` runs, and a view's own is a `Tween`; both carry a
  value from 0 to 1. Neither says *which of these frames is due now*, which is the one question a player asks every
  frame.

The two readings the inventory offered both fall short. Keeping the loop inside a widget library (a) keeps exactly
the unchecked, polling, lock-heavy code 0.2 is removing, merely moved behind `Seams`. Putting each decoded frame on
the bus for a component that hears the frame tick (b) puts a payload on the bus at frame rate, lets a slow logic
tick or the 150 Hz tick budget hold a video back, and costs a hop between the frame's time and the choice made for
it.

## The design

The question a player asks each frame is small, pure and needs an answer at the last moment: *given these frames,
stamped, and the time this frame will appear, which one shows?* Everything around it (decoding, sound, transport
controls, what to report) is either slow, or not per frame, or both. So the design splits the three apart and lets
only the small question onto the render thread, in a form the framework can check.

| Role | Runs on | Owns | Does |
| --- | --- | --- | --- |
| **producer** | its own thread: a decoder (infrastructure), a declared component lane, a job | what it makes | writes stamped frames into a feed; waits when the feed is full |
| **pacer** | the render thread, driven by the host | nothing but plain fields of its own | each frame, picks which written frame shows, and says when it next needs a frame |
| **controller** | a `@View`, or logic through one | the declaration | declares the pace on the node: playing, paused, sought, rate, loop |

### A. The feed: a ring the producer writes and the render thread reads

A **feed** is `StreamTexture` with a stamp on each slot: the presentation time of the frame written into it, in the
producer's timebase, stored with a release write when the slot is handed over. That is the only new concurrency, and
it is the shape T7.3 already allows ("where a foreign thread hands something in"): single producer, single
consumer, fixed slots, no lock in the read.

- **The producer never waits on the frame.** `take` returns null when every slot is shown, landing or written, so a
  producer ahead of the screen stops there. That is the backpressure, and it is why a feed off screen costs a few
  slots of decode and then nothing.
- **Landing is ahead of showing.** The host submits a written slot's copy on the first frame after it is handed
  over, and a pacer may show only a slot that has landed. So the copy of a due frame has finished before the frame
  that shows it records, and picking costs no transfer.
- **Pixels never cross the bus.** A feed is handed to the pacer once, at realise, by the widget library that made
  both.

### B. The pacer: the SPI, after `RenderTechnique`

```java
public interface Pacer<P extends Record> {
    void realize(PacerContext ctx);               // render thread, once: the feed, the timebase, the report cell
    int pick(P pace, Stamps written, long presentNanos);  // render thread, every frame it is on screen
    long nextDue(P pace, Stamps written, long presentNanos); // when it next needs a frame, or NEVER
    void close();                                 // render thread, once, after the device is idle
}
```

`pick` returns the slot to show, or `KEEP`. `pace` is the node's current declaration (C). `written` is a read-only
view of the feed's landed slots and their stamps. `presentNanos` is the time this frame is predicted to appear,
the presenter's own `NextPresent` (tree-thread.md B), so a pacer picks for the moment the picture is seen and not
the moment the frame began.

**The thread contract is `RenderTechnique`'s, word for word where it can be:** called only from the render thread;
`realize`, `pick` and `close` never concurrent and never nested; fields plain, no `volatile`, no lock; a pick can
arrive from inside the platform's modal loop, so it must not wait for anything. And three rules more, because a
pacer runs on every frame of every window it is in:

1. **A pick is pure.** It reads its declaration, the stamps, the present time and the timebase, and its own plain
   fields. The same inputs give the same slot. That makes it testable headlessly against a list of frame times, and
   replayable from the recorded declarations.
2. **A pick allocates nothing, blocks on nothing, logs nothing and publishes nothing.** What it has to say
   (position, ended, frames dropped) it writes into a preallocated **report**: a few `long`s it was given at
   realise. The host publishes the report as a state topic, after present, only when it changed, and at most at a
   rate the pace declares (10 Hz for a seek bar). So `Position` and `Ended` reach the application as messages and
   cost the frame nothing.
3. **A pick is bounded.** The host times it against a budget (B's open question 5). One over budget is a probe
   event; a pacer over budget for a run of frames is frozen on its last slot and reported through `Diagnostics`, so
   a bad pacer shows a stuck picture, loudly, and never a stuck window.

**`nextDue` keeps draw-on-change.** A pacer on screen and playing says when the next stamp falls due; the main
thread parks until the earliest of those or an OS message (frame-loop.md rule 2). A paused video, an image on its
last frame or a feed off screen says `NEVER` and costs no frame at all. This replaces `POLL_MS`.

### C. The pace: a declaration on the node, after `Look`

As a look is an element's appearance stated as data, naming shadelets with their parameters, a **pace** is a
picture's motion stated as data, naming a pacer with its parameters:

```java
Node.image(feed.live()).paced(MediaPacer.PACE.with(new Media(Transport.PLAYING, 1.0, Loop.OFF, seekNanos)))
```

- **A change is a new pace, not new code.** Play, pause, seek and rate are a view declaring again, as it would set a
  label. The declaration travels with the tree's snapshot, immutable, so the render thread reads it with no
  hand-off of its own. A seek is a field of the pace (a generation and a target), not a call.
- **The set of pacers is fixed at build time.** As shadelets are composed into an application's ubershader, the
  pacers an application can name are listed in its generated wiring, each constructed there. Nothing is looked up by
  name at runtime and nothing reflects, so a native image holds exactly the pacers it uses.
- **Pacers are authored as techniques are.** The framework ships the general ones; a library ships its own
  (the codec's `MediaPacer` and `LoopPacer`); an application may write one too, as `FathomTechnique` is written
  outside the engine. Wherever it is written, it is a class implementing the SPI, never a `@View`, a `@Component`
  or a lambda, and it is under D's check.

The framework's own, which cover every site below:

| Pacer | Picks | For |
| --- | --- | --- |
| `LatestPacer` | the newest landed slot | a simulation's field, the About art, a camera: anything that shows its latest |
| `LoopPacer` | by per-frame durations on wall time, looping | an animated image |
| `MediaPacer` (codec) | the newest slot stamped at or before the present time on the timebase; holds or drops late frames as the pace says | video, with sound as the timebase when there is sound |

### D. How each guarantee is held, earliest first

| Guarantee | Held by |
| --- | --- |
| Only declared pacers run | the generated wiring lists them (compile time) |
| A pick is pure, allocates nothing, blocks on nothing | the processor reads every class implementing `Pacer` and refuses `new`, `synchronized`, locks, I/O, `Thread`, logging, bus access and static mutable state in `pick` and `nextDue`, after `StepCheckTest`'s principle: it never refuses safe code, only what is certainly unsafe (`PacerCheckTest`) |
| No application code is handed a frame hook | the SPI is in `spi`, reached from no `Gui` method (`GuiSurfaceTest`), and a `@View` that imports `spi` is refused (owed with `@View`) |
| A slow pacer cannot hold a window | the budget, at runtime, frozen and reported (B, rule 3) |
| The feed is the only shared memory | T7.3's allowlist names it; the architecture test reads the sources |
| Single-thread mode changes nothing | pacers are on the render thread in both modes; one witness run in both, identical reports |

### E. Where it sits in the tree-thread design

Pacers stay on the main thread whether or not the tree moves. The snapshot names `live()`, so a playing video
never makes the tree emit; the main thread picks, shows and presents the last snapshot again. That is the cheapest a
moving picture can be, and it means playback keeps time even while the tree thread is busy laying out. It also
suggests an answer to tree-thread.md's open question 3: a viewport a technique renders into is a feed with a
producer on the render thread, and its "I rendered" is a slot handed over.

## What it replaces

| Today (`KronoGui`) | Site | In 0.2 |
| --- | --- | --- |
| `krono.spork`, a video's frames | `Playback:466`, `VideoView:216` | `MediaPacer` over the movie's feed; `Playback`'s loop and `POLL_MS` are deleted; `Play`, `Pause`, `Seek` are the pace, `Position` and `Ended` its report |
| `krono.spork`, an animated image | `ImageView:620` | `LoopPacer` |
| `krono.spork`, the viewer's inertia | `PictureViewer:1020` | not a picture but a value: a `Tween` with a velocity and a decay (open question 4) |
| `krono.ramp`, fades | `ImageView:463`, `PictureViewer:1281, 1570` | `Timing` / `Tween`, as they stand |
| `krono.after`, settle and idle | `PictureViewer:1033, 1541` | `Timer.after`, as it stands |

The decoder threads, `Sound` and `Track` stay as they are: they are producers, infrastructure behind the feed, and
the inventory already lists them as no offence. What goes is the code that met them in one object.

## Performance

- **Per frame, per pacer on screen:** a search over a handful of stamps, at most one `show`, at most one `submit`.
  No allocation, no bus traffic, no lock. Measured against the budget on every frame, so a regression is a number.
- **Per frame shown, compared with today:** the slot is chosen at the predicted present time rather than whenever
  the timeline thread woke, so frame-to-photon jitter for video should fall, and a paused picture costs nothing
  rather than a poll every `POLL_MS`.
- **What it costs:** a frame's choice is made on the render thread, so a heavy pick is the frame's cost. The budget
  and the check are what keep that to microseconds.

## Open questions

1. **One feed in two windows** (Vexplore's second window, a thumbnail and a full view). A slot shown is the feed's,
   not the window's. Leaning: a feed belongs to one window, and a second view of the same movie is a second feed
   from the same decoder.
2. **The timebase.** Sound's played-sample counter as a read-only, monotonic `Timebase` the pacer is given at
   realise. Who owns its conversion to present time (the audio device's latency) is the codec's to say.
3. **Rate and reverse.** Whether `rate` is a pace field the pacer applies to the timebase, or the producer's, which
   would decode differently for it.
4. **Inertia.** A `Tween` with an initial velocity and a decay (`Timing.Curve.DECAY`), or a value pacer whose report is
   the value. Leaning the first: it is a value a view draws from, and `Tween` already announces one.
5. **The budget.** 50 µs per pick is a guess. Step 0 measures what the codec's pick costs today, inside its loop.
6. **Order among pacers and techniques.** Pacers, then the frame callback, then every technique's `record`, so a
   technique drawing over a video samples the slot just shown. Confirm against `RenderTechnique`'s stated order.

## Order of work

Each step lands on its own and leaves the suite green.

0. **Measure today.** The codec's demo player on a 60 s clip at 24, 30 and 60 fps on a 144 Hz display: the
   histogram of intervals between shown frames, duplicated and dropped frames, and the offset from sound. This is the
   number the design has to beat, and step 5 repeats it.
1. **Argue it in vexelray-framework's `threading.md`.** T7.1 names a third exception, pacing, beside drawing and
   presentation; T7.3 names the feed. Rule the SPI's place in `spi`.
2. **Stamps on `StreamTexture`** (vexelray-vulkan), and the host submitting landed slots one frame ahead. Still
   driven by the codec's loop. Behaviour unchanged.
3. **The SPI, the report and `nextDue`** in vexelray-gui's host: the main loop picks, shows, parks and publishes.
   `LatestPacer` and `LoopPacer`, tested headlessly against a fake feed and a list of present times.
4. **The processor's check** (`PacerCheckTest`) and the generated list of pacers.
5. **The codec on it.** `MediaPacer`; `Playback`'s loop, `POLL_MS` and the locks it needed deleted; transport as a
   pace, position as a report. Measure against step 0.
6. **A third witness, not a player:** the fluid simulator's field through `LatestPacer`, its producer on a declared
   lane, so the SPI is shown to be general rather than shaped round video.

## Decided

- 2026-10-29: the codec's views and vex-suite-common's `suite.view` are widget libraries.
