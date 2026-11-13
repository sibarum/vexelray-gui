# The device feed: pictures made on the GPU, shown without a copy

Status: **Proposed 2026-11-12.** Nothing built. It takes up [frame-contract.md](frame-contract.md)'s step 6 (the fluid
simulator through a feed) and widens it: no 0.2 application draws anything it made on the GPU yet, and two are waiting
on it. The ask first, then what the code does today, the design, how each guarantee is held, what moves onto it, the
questions it leaves open, and the order of work.

## The ask

The author's, 2026-11-12: the simulations (`vexelray-sim-physics`) are ported to 0.2 next, and Rasterware's video
needs the same thing they do. Both make a picture's material off the GUI thread, on the GPU or bound for it, and want
it shown without a round trip through the heap.

- **The simulations.** A step is computed on the window's own device, kept by a GPU copy into one of three slots, and
  drawn by the application's ray march from the slot it is in. Nothing is read back. Under 0.2 the code that does it
  does not compile: the drawing is a `@MainThread` part on `@BeforeFrame` holding the `GuiApp`, and the physics lane
  shares mutable state with it (`ShownRing`, the `Dilated` clock, `PhysicsNews`).
- **Video.** FFmpeg decodes on the CPU into RGBA8, the player writes it into a `Feed`'s heap slot, and `DeviceImages`
  copies the slot through staging on the GUI thread when it is shown: about 250 MB a second at 1080p and 30 fps
  (vexelray-codec's README), on the thread that must not be held.

## What happens today

- **The feed is device-free** (frame-contract.md step 4, decided in the building). Its slots are heap memory, the
  producer writes them on any thread, and each device that draws one uploads the slot it shows. That was right for
  its first users: a still is uploaded once, a GIF is small, a producer may start before any window, and a headless
  capture draws a feed as a window does. It is the copy step 4 named as the cost, and video pays it every frame.
- **A viewport exists, and an application cannot reach it.** `GuiApp.viewport(w, h)` makes a `SampledColorTarget` on
  the application's device, `Node.image(target)` shows it, and `renderInto` draws into it. All three are the main
  thread's, and T7.1 and T2.2 keep application code off it and away from the `GuiApp`. So 0.2 has no way for an
  application to put anything it renders in front of the user. vexelray-framework's TODO has the same gap from the other
  side (*one `Viewport3D` component*, with "decide which repo owns the contract" as its open question).
- **The engine's extension point is the technique** (vexelray's `techniques.md`): `realize`, then `record` every
  frame into a pass already begun, then `close`, all on one thread, making nothing in `record`. `SampledTechniqueHost`
  runs a list of them into a `SampledColorTarget` someone else made. A technique cannot yet declare the buffers it
  binds (`SdfRaymarchTechnique` builds its pipeline with no descriptor layouts), which is why the simulations' views and
  `vexelray-designer`'s `Viewport` drop below the seam and call `renderInto` themselves.
- **The simulations already have a feed of their own, in the wrong place.** `ShownRing` (vexelray-sim-core) is a ring
  of three slots with a front, a ready and a free; `KeptSlots` copies a step into a slot on the GPU and signals a
  timeline value; the view waits for that value inside the GPU, which the step reached before it was published, so the
  frame never waits on the physics. It is a feed with device slots and a timeline for a stamp, held together by a lock
  and a rule that `renderInto` returns only once its draw has finished. That rule is a main-thread fence wait per frame.

## The design

### A. A feed whose slots are on the device

**`Feed`'s contract, kept whole, with the slots on the window's device instead of in the heap.** One producer, one
consumer, fixed slots, `take` answering `-1` when none is free, `handOver(slot, stamp)`, the producer's time and
`due(frame)`, `stale`, `close` from any thread. An asked half chooses with `Drawn.show` as it does now and cannot tell
the two apart. The device-free feed stays for everything it is right for: stills, pictures decoded on the CPU anyway,
and anything made before there is a device.

What changes is what a slot is and how it lands:

- **A slot is device memory**: an image (RGBA8, or a picture's planes, B) or a buffer (a simulation's step, C). Made
  with the feed, on the device, all slots alike, and freed with it.
- **The producer writes it on the GPU, on a queue of its own.** Video records a copy from its own staging into the
  slot's planes; a simulation records the copy `KeptSlots` makes now. Either way the GUI thread records nothing for it.
- **A hand-over carries a timeline value** as well as the stamp: `handOver(slot, stamp, timeline, value)`, the value the
  producer's submission signals when the slot's writes are done. The producer hands over at submission, not at
  completion, so it never waits on its own GPU work.
- **Landed means the value is reached.** The renderer reads the timeline's counter, which does not wait, and an asked
  half may choose only a landed slot; `due` answers among those. A slot that is not landed is a frame not shown yet,
  exactly as a frame the producer is late with is now. The draw also waits for the value inside the GPU, which then
  costs nothing and makes the producer's writes visible to it.
- **A slot comes free when the frame that last sampled it has finished on the GPU**, known from the frame's own fence
  as `GuiApp`'s retiring already knows it, not when a call returns. That is what lets the draw stop waiting (E).

**The device is the application's one device** ("one GPU bring-up serves every window"). A feed is made on it and
drawn by every window, and a headless capture draws on the same one. Several devices are an open question, not a
case to design for.

### B. Pictures: planes, converted where they are sampled

For video the slot is the frame as the decoder has it: **Y and CbCr planes**, at 4:2:0 a half of RGBA8's bytes, and
converted to RGB by the canvas when it samples the slot. The feed carries what the conversion needs (matrix, range,
bit depth), fixed for the feed's life, so the canvas shader's look up of it is per feed and not per frame. A node shows
it with `Node.image(feed)`, as now; no application code draws it.

This needs **ffmpeg-wrapper to hand out planes**, which it does not: every frame leaves it as straight RGBA8, already
converted ("the caller never learns a codec"). A planes output beside the RGBA one keeps that promise for everyone who
wants RGBA and is the codec's own README's suggestion. Decoding on the GPU and handing its image over without a copy is
the step after (open question 5): the feed's shape does not change for it, only who writes the slot.

### C. Rendered pictures: a technique, run by the host, reading a slot

For a simulation the slot is not a picture but what a picture is made from, and the application's technique makes it.
**A technique view** is a node whose pixels a technique records:

- **The host owns the target and its resize.** It makes the `SampledColorTarget` at the node's laid-out size, on the
  device, makes a new one when the box changes by more than a threshold (the designer's 1.35x), and retires the old one
  after the frames that sample it. The node is a `Node.image` of the host's target, so it rounds, clips and fades like
  any other.
- **The technique is declared, not called.** An application or a library names a technique for the node; the host
  `realize`s it against the target, calls `record` once per frame the node is drawn, and `close`s it. Its thread
  contract is the engine's (`techniques.md` §5): one thread, makes nothing in `record`, never blocks. Where the frame
  contract's asked half chooses, the technique draws.
- **What it reads is declared.** The technique names its bindings, and the feed is one: the host binds the slot the
  view's asked half chose. This is the seam growth vexelray-framework's TODO asked for, a technique declaring the
  buffers it binds, and it belongs in vexelray's `RenderTechnique` (open question 1).
- **Per-frame numbers come from the asked half.** The camera, the blend between two steps, a squash: numbers the
  view's `@Draw` sets through `Drawn` for the technique, which reads them in `record`. The technique holds no clock and
  hears no message. The simulations' blend needs the slot due and the one before it, so `Drawn` can name two slots of
  a feed and a weight (open question 4).

### D. Who may write: a lent queue

A producer on the device needs a queue on it, and 0.2 gives application code no `GuiApp` to take one from. **The
framework lends one as a declared service**: a part asks for a `DeviceQueue` (compute, or transfer) in its constructor,
the wiring hands it one when the device exists, and takes it back at shutdown, before the device goes. It is what
vexelray-sim-core's `AppCompute.lend(GuiApp)` does by hand today, made a declaration. A part that takes one is built
after the device, so a recipe that holds one is in `WINDOW`'s phase or later, as anything on the device is.

The queue carries the device, so the producer makes its feed on it; a `Feed` on a device is still a handle and crosses
in a payload as a feed does now (`Shareable.FEED`).

The simulations' physics becomes a **`@Source`** that takes a `DeviceQueue`: a run per `Start`, stepping for as long as
it runs, keeping steps into its feed and sending its readings through declared senders; a newer `Start` stops it and
begins again, which is the demos' rule already ("only the newest start is worth making"). Video's producer is the
codec's `Player` thread, infrastructure, which takes the queue from the view that made it.

### E. Where it runs, and what nothing waits for

- **Producers**: their own threads, their own queues. They wait for nothing on the GUI side: a full ring is `-1`.
- **The GUI thread** reads timeline counters, runs asked halves, and records techniques into the frame's command
  buffer, before the canvas pass that samples their targets, as `GroupPasses` records SDG groups now. **It waits for no
  fence.** Today's `renderInto` submits and waits per call; a technique view does not use it.
- **The GPU** waits, inside the frame's submission, for the timeline values of the slots it reads: already reached,
  so free.

## How each guarantee is held

| Guarantee | Held by |
| --- | --- |
| An asked half chooses only a landed slot | `due` and `newest` answer among landed slots; a device feed test with a timeline held back |
| A slot is never written while a frame may read it | it comes free on the reading frame's fence; a test that holds a frame in flight and asks `take` |
| No application code runs on the GUI thread, a technique's `record` aside | the processor: a technique is declared and never called; `record` is the engine's contract, held by the technique's own tests (open question 2, ruled) |
| The GUI thread waits for no fence | the host records into the frame; an architecture test that the technique host calls no `renderInto` and no fence wait |
| The device feed is the only new shared memory | T7.3's allowlist names it beside the feed |
| A lent queue does not outlive the device | the wiring takes it back in the shutdown order before the device is closed; a test of the order |

## What moves onto it

| Today | Then |
| --- | --- |
| `ShownRing` and `KeptSlots` (vexelray-sim-core) | a device feed of buffers; `KeptSlots`' copy is the producer's write. `ShownRing` is deleted |
| `AppCompute.lend(GuiApp)` | a `DeviceQueue` asked for in a constructor |
| the simulations' `Session` (`@MainThread`, `@BeforeFrame`) | a `@View` whose `@Draw` chooses the step and sets the blend and camera; the panel's diffing becomes messages from the controls |
| `RigidView`, `FluidView3` (call `renderInto`) | techniques, declaring the slot they bind |
| the simulations' physics lane | a `@Source` with a `DeviceQueue` |
| the codec's `Player` writing RGBA8 into heap slots | writing planes into device slots, on its own transfer queue |
| `DeviceImages` uploading a playing feed's slot on the GUI thread | nothing: the slot is already there |
| `vexelray-designer`'s `Viewport` | a technique view, when the designer moves to 0.2 |

## Open questions

1. ~~**Where a technique's bindings are declared.**~~ **Ruled 2026-11-13: `RenderTechnique` grows**, so a technique
   says what it binds, and the four built-in techniques declare nothing. Over a host-side wrapper that binds and then
   calls `record`, which would have kept the SPI and put the knowledge of what a technique reads outside it. It
   changes the SPI every technique implements, so the work is before the freeze (vexelray-framework's TODO, *one
   `Viewport3D` component*, moves to Blocks v1 by its own rule).
2. ~~**Is a technique checked?**~~ **Ruled 2026-11-13: a technique is library code**, as a widget's asked half is. It
   is the engine's contract, not the frame contract's, and is held to `techniques.md` by its own tests, an
   application's own technique included. The processor checks only that a technique is declared and never called
   directly; it does not read `record`'s body, which is Vulkan calls through Panama the step check could not read
   usefully.
3. **Queue family ownership.** A slot written on a compute or transfer queue and read on the graphics queue needs an
   ownership transfer (a release on the producer's side and an acquire on the frame's) or concurrent sharing. Which
   one `KeptSlots` relies on now, and which costs less, is to be measured, not assumed.
4. **Two slots and a weight.** The simulations draw between the newest step and the one before. Either `Drawn` names
   two slots of a feed, or the technique is given the due slot and finds the previous one. The ring's free rule then
   keeps two shown slots, so a blending feed needs four.
5. **Decoding on the GPU.** Vulkan Video, or FFmpeg's hardware decoders handing over an image that is then copied on
   the device. The feed's shape is the same; who writes the slot and on which queue is not. After B, measured against it.
6. **Several devices.** Not designed for. A feed is on the application's one device; a second device would need the
   feed to say which, and a copy between them.
7. **A device feed's mips.** Step 0 found the producer's mip chain, built on the CPU, is three quarters of a video
   frame's cost. Planes (B) carry no mips; a picture drawn well below its size needs them. Either the GPU makes them
   after the hand-over (a blit chain on the producer's queue, before the timeline value), or the feed says it has none
   and the canvas samples it as it is. Decided with B, by step 0's numbers repeated.
8. **The budget that freezes a slow asked half** (frame-contract.md) now has a second customer: a slow technique holds
   the frame the same way, and wants the same freeze and report.

## Order of work

Each step lands on its own and leaves the suites green.

0. ~~**Measure today.**~~ **Done 2026-11-13**, below. Video: the codec's video-player demo at 1080p and 2160p, 30 and
   60 fps, with the GUI thread's time per upload and the process's CPU; its meter is frame-contract step 5's
   instrument. The simulations: their 0.1 numbers in vexelray-sim-rigid's `physics-timing.md` (frame time at the
   display's rate, `a step every N ms`, world speed), taken again so the port has a before.

   *How:* the laptop (RTX 5070 Ti Laptop, Core Ultra 7 255HX, one 1920 × 1200 display at 144 Hz), each application a
   background run driven by ottermate, with a JFR recording (`settings=profile`). A window of 20 s between two
   `mark`s, after warm-up; the probe's rows in it give the frames (`frame`, `frame.present` and the spans of a drawn
   frame), JFR's samples in it give the threads and `jdk.CPULoad`. Other sessions were building on the machine at the
   same time (machine load 6 to 42%), so a CPU figure is good to a few percent. The clips are synthetic, `testsrc2`
   encoded by SVT-AV1 (preset 10, 9 at 2160p; CRF 35; its default GOP of about 5.3 s), 40 s, no sound; the demo opens
   every clip at a side of 1920 (`Player.SIDE`), so a 2160p clip is decoded at 2160p and scaled down, and what is
   uploaded is 1920 × 1080 whatever the clip.

   | Clip | Shown | Process CPU | GUI thread, a drawn frame (median / p95) | Upload, GUI thread | Decoder thread |
   | --- | --- | --- | --- | --- | --- |
   | 1080p30 | 30.0 fps | 1.27 cores | 2.35 / 4.60 ms, 46 drawn a second | 1.3 ms a frame, 30% of its samples | about 9.6 ms a frame |
   | 2160p30 | 30.0 fps | 1.66 cores | 2.23 / 4.23 ms, 47 drawn a second | 1.1 ms a frame, 25% | about 9.7 ms a frame |
   | 1080p60 | **0.0 fps** | 1.39 cores | (nothing to draw) | none | decoding, never handing over |
   | 2160p60 | **0.0 fps** | 2.18 cores | (nothing to draw) | none | decoding, never handing over |

   What it says:
   - **The upload is what the plan said it is**: `StreamTexture.Slot.write` under `DeviceImages.fed`, on the GUI
     thread, about 1.2 ms for each 1080p frame with its mips, a quarter to a third of that thread's work while a video
     plays. B and A remove all of it.
   - **The bigger cost is the producer's, and it is mips.** Three quarters of the decoder thread's samples are
     `MipChain.half`, building each frame's mip chain on the CPU; FFmpeg's decode and conversion are about an eighth.
     About 10 ms a 1080p frame, so a 60 fps clip at this size has 6.7 ms left in each frame's 16.7 for everything else.
     B's planes would not take mips with them: a video drawn near its own size needs none, or the GPU can make them.
     The device feed has to say which (open question 7).
   - **60 fps at 1080p does not play.** `shown 0.0 fps` while the clock runs, at both sizes: the player hands over
     its first frame and none after. Read from the code, not proven: the decoder thread falls behind, and once the
     wanted frame is more than `Footage.RUN_SECONDS` (2 s) ahead it is sought; `Video.seek` is exact, so it decodes
     from the keyframe, up to 5.3 s of frames here, while the wanted frame moves on again, and it never lands. Step 5's
     60 fps clips were 960 × 540 and played. A codec bug in its own right (a seek that cannot catch up should give up
     on exactness), and a cost the device feed would hide but not fix.

   | Demo, on 0.1 | Scenario | Physics | Frames presented (gap p50 / p99 / max) | GUI thread, a frame (median) |
   | --- | --- | --- | --- | --- |
   | Rigid | 1000 spheres, 10 × 1 a step | step 7.47 ms (GPU 3.91), picture 3.84; world at 100%, a step every 17.4 ms | 57.1 a second (17.40 / 21.24 / 23.58 ms) | 5.76 ms, of which the draw (`before frame`, `renderInto` and its fence) 4.21 |
   | Fluid | 3D dam break, 40³, 41,600 particles | 58 steps a world step, 14.29 ms; world at 100%, a step every 17.3 ms | 57.1 a second (17.50 / 21.12 / 22.19 ms) | 5.54 ms: draw 1.47, the panel's layout 2.57 |

   What it says:
   - **The frame follows the steps, not the display.** Both demos present one frame per step, 57 a second, where
     `physics-timing.md` recorded 140 to 144 for the rigid demo in stage 4 and 5. The blend between steps (by
     `Dilated.phase`) is computed every frame and never seen between two steps. Not yet known whether that is the loop
     drawing only on change (frame-loop.md) since then, or a background run's cloaked window; it is noted in
     vexelray-sim-rigid's TODO. The port's E keeps the frame off the step either way, and is measured against this.
   - **The draw holds the GUI thread for its fence**: 4.2 ms of the rigid demo's 5.8 ms frame is `renderInto`, most of
     it waiting. E is the change that removes it.
1. **Argue it in vexelray-framework's `threading.md`.** T7.3 names the device feed; T7.6 says where a technique's
   `record` stands (open question 2); the lent queue as a declared service, and its place in the shutdown order.
2. **The device feed in vexelray-gui**, slots as buffers and RGBA8 images first: landed by timeline, freed by the
   frame's fence, chosen by the asked half as now. Its tests run on the GPU, after `FeedTest`'s pattern.
3. **Video on it.** Planes from ffmpeg-wrapper, converted by the canvas (B); the `Player` writing on a transfer queue;
   `DeviceImages` no longer uploading a playing feed. Rasterware and the codec's demo are the witnesses, measured
   against step 0.
4. **Technique views.** The host in vexelray-gui (C, E), bindings in vexelray's `RenderTechnique` (open question 1),
   the declaration and `DeviceQueue` in vexelray-framework (D). This closes vexelray-framework's `Viewport3D` item.
5. **The rigid demo on it**, its physics a `@Source`, its view a technique; then the fluid's. They port to 0.2 here,
   and vexelray-sim-core loses `ShownRing`, `KeptSlots` and `AppCompute`. Measured against step 0: the frame stays at
   the display's rate whatever the physics costs, as it does now.
6. **Then the coupled demo** (vexelray-sim-physics), which is both simulations into one technique view: the first
   application with two producers on one device.

## Decided

- 2026-11-12, by the author: the simulations' 0.2 port and Rasterware's video wait on the same thing, and it is built
  once for both (this document).
- 2026-11-13, by the author: `RenderTechnique` grows to declare its bindings (open question 1), and a technique is
  library code the processor does not read (open question 2).
- *Proposed, not ruled:* video first (step 3 before step 5), as the simpler producer and the one already on 0.2.
