# Tree thread: the tree leaves the main thread

Status: **Proposed 2026-10-28, not started.** Owner's rules first, then what the code does today, then the design,
the questions it leaves open, and the order of work. It builds on [frame-loop.md](frame-loop.md) (A and B done) and
on the 0.2 message model (vexelray-framework's `ROADMAP.md`, and vexelray-framework's `threading.md`). It adds
a lane, so T1.1 has to be argued in vexelray-framework's `architecture.md` before any code (step 1).

## Why

Latency, and where it comes from. Today one thread pumps input, dispatches it, lays out, emits and draws, in that
order, once per pass. A response that needs logic is computed while the frame that took the input is being drawn,
so it can only appear in the frame after. Where the draw is cheap that is one refresh: the click path measured in
`architecture.md` is a median 6.5 ms at 144 Hz. Where the draw is what is slow (the case `vexelray-mini` exists for,
15 frames a second, about 67 ms a frame), an input that arrives mid-draw waits for that draw to finish before it is
even dispatched, and its response waits for one more.

With the tree on a thread of its own, input is dispatched and laid out *while* the main thread draws, and the next
frame the main thread draws already holds the response. **What that saves is up to one frame, and only the share of
a frame that is drawing.** That share is not measured on any machine yet; step 0 measures it, and the plan stops
there if it is small.

## The rules

The author's, 2026-10-28:

1. **Rendering is decoupled from input and logic.** It runs on its own clock and frame rate, and need not hold a
   fixed one.
2. **The thread that draws keeps a frame on idle and draws on demand.** Retained when nothing changed, immediate when
   something did, within the ceiling.
3. **Messages that mutate the GUI are applied in batches**, once per graphics frame, a whole logic tick at a time.
4. **Logic drains on arrival**, no faster than about 150 Hz, and immediately when below that rate. A message storm
   past a reasonable depth ends the program loudly.

And two standing constraints from this repo's `CLAUDE.md`, which decide which way the split goes:

- **Vulkan, the window and present stay on the main thread.** So the main thread is the one that draws, and the tree
  is what moves.
- **All input arrives through tactroller, on the bus.** So input is already a message, and the thread that dispatches
  it does not have to own the window.

## What happens today

- **One loop, on the main thread** (`GuiApp.run`). Each pass pumps the window, runs the frame (drain mutations,
  reconcile, lay out, which gives a `RetainedNode` root), emits the tree into a `Canvas` (`TreeRenderer.emit`), then
  uploads, records, submits and presents. The ceiling holds the gap from frame start to frame start, and
  `waitEvents` parks the loop between passes.
- **Update and draw are already separate by need**, if not by thread: frame-loop.md A runs the update on every wake
  and the draw only when `changedSinceDraw` says something changed.
- **The canvas is already a value.** `Canvas` is a CPU-side `float[]` of `CanvasVertex` data, one buffer and one draw
  call for the whole overlay, rebuilt each frame and handed to the binding layer to upload. It is the natural thing to
  hand from one thread to another.
- **Input** is read once per frame at `FrameStage.INPUT` (`TactrollerInputBridge`'s snapshot), and a real OS message
  ends the park.
- **During a drag**, the Win32 modal loop owns the main thread and pulls frames through the frame sink on an 8 ms
  timer.

## The design

Three threads, each owning its own state, talking only by messages and immutable values:

| Thread | Owns | Does |
| --- | --- | --- |
| **main** | the window, Vulkan, the swapchain, pacing | pumps OS messages (tactroller publishes them), uploads and draws the latest snapshot, presents, keeps a frame on idle |
| **tree** (`vexel-tree`) | widget state, the retained tree, layout | dispatches input from the bus, runs the widget tick, applies logic's writes, reconciles, lays out, emits a snapshot |
| **logic** (`vexel-logic`) | components | ticks on arrival, rate-capped (the companion section at the end) |

In single-thread mode all three run on the main thread, in that order (E).

### A. The snapshot is the canvas

The tree thread emits; the main thread draws what was emitted. A **snapshot** is:

- the canvas's vertex data and count;
- the GPU resources it refers to, by handle (images, viewport targets);
- the size and scale it was laid out for;
- the present time it was sampled for (C).

It is immutable from the moment it is handed over. Three canvases rotate between the threads, so neither waits on the
other and nothing is allocated once warm.

**The hand-off is a state topic, one key per window: the last snapshot wins.** That makes frame-loop.md's rule 2
(*nothing forces more than one redraw between frames*) hold by construction: however many snapshots the tree emits
while a frame is being drawn, the main thread draws only the latest.

**A custom widget's `draw` runs during emit, so on the tree thread**, where the widget's state lives. threading.md
T7.6's asked half (T7.1's drawing exception until 2026-10-29) becomes "drawing runs on the tree thread", which is a simpler statement than the one it
replaces: the widget reads only state its own thread owns.

### B. The main thread keeps and draws

It wakes for three reasons: an OS message (pump it; tactroller publishes it), a new snapshot, or an expose. It
draws only when a new snapshot exists, or presents the last one again when the window needs its pixels back (expose,
occlusion, a swapchain recreated). An expose no longer involves the tree at all.

It keeps everything that is about the display: the ceiling, one frame in flight (frame-loop.md rule 5), FIFO, the
display's refresh. **It publishes when it next expects to present** (`NextPresent(window, nanos)`, a state topic),
because only it knows.

**The modal loop's timer** only pumps and presents the latest snapshot. The tree goes on updating on its own thread
while the modal loop holds the main one, which is what the frame sink was standing in for.

### C. The tree thread updates and emits

Its loop: drain input from the window's input topic (frame-loop.md D's secretary stands here), dispatch, run the
widget tick, apply logic's writes a whole tick at a time (`Tick.stageShare`, already built), reconcile, lay out, and
emit if `changedSinceDraw`.

**It emits at most one snapshot per `NextPresent`**, and samples kronometer for the time that frame will appear. An
emit nobody will draw is wasted work, and an animation sampled for the wrong moment is a visible stutter: the 29 ms
stall at the start of each animation recorded in `architecture.md` was partly a ramp posted after its frame's CLOCK
stage had passed. Pacing animation from the presenter's own prediction is meant to make that class of bug
impossible.

Input does not wait for `NextPresent`: a click is dispatched the moment it is drained, so its widget events, and the
logic tick they start, run while the main thread is still drawing.

### D. GPU resources

Everything Vulkan stays on the main thread. The tree thread asks for what it needs by message (upload this image,
make this viewport target) and refers to it by handle. Font atlases are all loaded at startup
([font-families.md](font-families.md)), so text needs nothing at runtime. What a snapshot does when it refers to a
handle not yet uploaded is an open question (2).

### E. Single-thread mode

The same three loops run on the main thread, one after another: pump, logic tick, tree update and emit, draw. Run
that way, a response that needs logic appears in the frame that took the input, because the logic runs before the
layout. A slow tick or a slow layout delays the frame, which is the trade single-thread mode makes anyway.

## Open questions

1. **Live resize.** The new size arrives on the main thread; the layout for it comes from the tree thread. Until it
   does, the main thread has a snapshot for the old size: present it padded, present it stretched, or wait a bounded
   time for the new one. Decide on purpose; left to chance it is resize jitter.
2. **A snapshot that refers to a resource not yet on the GPU.** Draw without it, or hold the snapshot until the
   upload lands.
3. **Viewports.** A target from `GuiApp.viewport(...)` is rendered into by an application's technique, which is Vulkan
   work. Whose thread runs it, and how its "I rendered" (frame-loop.md decided 1) reaches the tree.
4. **Automation and capture.** `settle`, `shot` and capture read the tree and the frames. Which thread answers, and how
   a capture names the tree version it wants drawn.
5. **Several windows.** One tree thread for every window, or one per window. Leaning one: it is one lane, and T4.6
   already keeps windows' topics apart.
6. **The OS cursor.** Hover is decided on the tree thread; the cursor is set on the main thread. One more message, and
   a frame of lag on a cursor change unless it is sent at dispatch rather than with the snapshot.

## Order of work

Each step lands on its own, is measured with the probe, and leaves the suite green.

0. **Measure.** Input-to-photon (`PacingMeasurementTest`) on the development machine and on `vexelray-mini`, and each
   frame split into update, emit and draw. If drawing is a small share of a slow frame, stop: the split would not pay.
1. **Argue the lane** in vexelray-framework's `architecture.md`, and rule T1.1's list to include it, with the
   numbers from step 0.
2. **The snapshot seam, still on one thread.** Emit into rotating canvases and draw only from the handed-over one,
   all on the main thread. Behaviour unchanged. This proves the boundary: nothing in the draw reads the tree.
3. **The tree thread**, for one window: update and emit move, `NextPresent` paces emits, the modal loop presents only.
4. **GPU resources by message** (D, and open questions 2 and 3).
5. **Resize, several windows, automation** (open questions 1, 4, 5, 6).
6. **Measure again**, in both modes, against step 0.

## The logic side, for the whole picture

The tick scheduler is vexelray-framework's (ROADMAP phase 4), not this repo's. It is recorded here because rule 4 is
half of the latency answer, and the two were proposed together:

- **A tick runs when a message arrives**, not at frame start, so input dispatched on the tree thread is answered
  within a millisecond or two rather than at the next frame. The ROADMAP's "usually once per frame" becomes this.
- **The rate cap is a budget, not a minimum gap.** An average of about 150 Hz with a short burst allowed (a token
  bucket), so an isolated input always ticks at once and only a sustained flood is held back. A strict 6.7 ms gap
  would delay a click that arrived just after a timer's tick.
- **No tick deadline when logic has its own thread.** Its only job was to keep a slow tick from costing a frame, and
  with the tree and the draw elsewhere it cannot. Every tick runs until nothing is queued; the hop limit stays, as the
  loop detector. Single-thread mode keeps the deadline.
- **The storm cap counts after folding**, so a thousand pointer moves are one message, and is distinct from the hop
  limit: the hop limit catches a loop, the cap catches a backlog. It is fork 1's per-tick budget.
- **Replay records tick boundaries.** Ticks that start on arrival end in places timing decides, and state folds per
  tick, so ROADMAP *Done means* 5 holds given the recorded ticks.

What this does not do: the cap limits how often logic runs, not how long one tick takes. A 200 ms tick still holds
every response for 200 ms. What it protects is the window, which goes on drawing.

## Decided

Nothing yet beyond the rules above.
