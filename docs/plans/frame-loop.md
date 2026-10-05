# Frame loop: draw on change, not on wake

Status: **approved 2026-10-05; steps 1 and 2 done.** Owner's rules first, then what the code did before, then the
design, then the order of work and what was decided.

**Measured after steps 1–2** (calculator, automation driver, probe): 400 pointer moves over empty space presented
5 frames — the caret's blinks — against ~307 before; 200 programmatic resizes presented 434 frames against 872,
still one rebuild per resize. The modal-loop frame sink goes through the same update-then-maybe-draw path, which is
step 2.

## The rules

1. **We choose which OS messages wake the loop.** Not every message is a reason to do work now.
2. **Some events earn as-soon-as-possible handling, but nothing forces more than one redraw between frames.**
3. **Redraw only if something changed, and the change itself is what says so** (a `setDirty`, not a wake on
   activity).
4. **Coalescing is the application's choice.** A configurable message *secretary* per kind of message: deliver
   every one, keep the latest, accumulate, or a custom rule. Easy to set, with sensible defaults.
5. **A frame is not redrawn while the one before it is still being drawn** (already done: one frame in flight,
   vexelray `EngineConfig.MAX_FRAMES_IN_FLIGHT`).

## What happens today

Verified in the code on 2026-10-04; the event-storm survey of the same day measured it.

- **Any OS message ends the park.** `Win32Window.waitEvents` is `MsgWaitForMultipleObjectsEx(QS_ALLINPUT)`.
- **Every pass of the loop draws.** `GuiApp.run` calls `main.frame(...)`, which pumps, then
  `presenter.render(0, this::draw)`; `draw` updates the tree and presents, whether or not anything changed.
  400 scripted pointer moves over a static window gave ~307 full frames in 2 s.
- **The "frame owed" flag exists and nothing asks it.** `Gui.wake(why)` already coalesces wakes to one owed
  frame and `Gui.frameOwed()` reports it — only the automation driver's `settle` reads it.
- **Wakes come from activity, not change.** Every handler wakes on return ("costs one frame per input event
  that finds nothing left to do", `Gui.java` handler executor). `Node.prop` posts every write;
  `Reconciler.setProp` applies it without comparing, so writing the value a prop already has still dirties.
- **What is already bounded:** tactroller samples pointer and wheel once per frame (at most one `PointerMoved`
  and one `Scrolled` per frame); `SetProp`/`SetText` fold per (node, key) in the mutation mailbox; the frame
  ceiling (`maxFrameRateToDisplay`) caps frames at the display rate; the 200 ms idle floor bounds a missed wake.
- **During a drag**, the Win32 modal loop's 8 ms timer pulls a full frame through the frame sink on every tick,
  changed or not.

The waste is not mostly in the messages. It is that **every wake is a full draw and present.**

## The design

### A. Split *update* from *draw*, and draw only on change

Each pass of the loop does two things that are separate today only by name:

- **update** — pump, dispatch input, drain mutations, reconcile, lay out. Cheap, and must run whenever the loop
  wakes: a mutation that arrived without a wake still has to land.
- **draw** — emit the canvas, acquire, record, submit, present. Expensive, and needs a reason.

The loop runs *update* on every wake and *draw* only when one of these is true:

| reason | who says so |
|---|---|
| the tree changed | the reconciler: a mutation that actually changed something (see B) |
| layout moved something | the flex layout, when a computed rect differs from last frame's |
| the window's size or scale changed | the window; `GuiWindow` compares against what it last drew |
| something outside the tree changed | the application, through `gui.requestFrame()` (below) |
| an animation is running | kronometer: its tick writes props, so this is the first row in practice |
| first frame, capture, expose | the loop itself |

`gui.requestFrame()` is the explicit `setDirty` for content the tree cannot see: a viewport whose pixels a GPU
pass rewrote (`SampledColorTarget`), a texture swapped under an unchanged node. Everything that goes through a
`Node` needs nothing from the application.

Consequences:
- A pointer move over a static window costs an update (hit test, hover), and a draw only if hover changed a prop.
- The idle floor stays as a safety net, but it becomes an *update* tick: a missed wake still drains, and draws only
  if what drained changed something.
- The modal-loop timer pulls an update; it draws on the same rules. A move with no resize draws nothing.
- Rule 2 holds by construction: draws happen at most once per pass, and passes are already held to one per
  display refresh by the ceiling.

### B. The change itself marks dirty

- `Reconciler.setProp` / `setText` compare with the current value and **do nothing** when equal — no dirty flag,
  no layout invalidation. (The comparison is on the GUI thread, where the values live; `Node` is a handle used
  from any thread and has nothing to compare against.)
- The reconciler keeps one `changedSinceDraw` bit, set by any mutation that changed something and by layout
  invalidation, cleared when a frame is drawn. That bit is rule 3.
- Handler wakes stay, but now only buy an *update*. Removing them outright is riskier (a handler that posts with
  no wake would stall until the idle floor) and no longer worth anything.

### C. Which OS messages wake the loop

With A in place a wake costs an update, not a frame, so the wake mask matters less than it looks — but rule 1 asks
for control, and the modal loop needs it. Each window classifies what it receives:

| class | examples | effect |
|---|---|---|
| **now** | key, button, char, close, focus, size, DPI, expose | ends the park at once |
| **at frame** | pointer move, wheel | ends the park no sooner than the next frame boundary (last frame start + the display interval) |
| **ignore** | messages the window answers itself (hit-test, set-cursor, the modal timer's own tick) | never ends the park |

Implemented in `Win32Window.waitEvents`: on return, peek the queue; if everything pending is *at frame* and the
boundary is not due, keep waiting for the remainder. The table is a default the application can override per
class. `WM_DPICHANGED` and `WM_DISPLAYCHANGE` become *now* events, replacing the per-frame `GetDpiForWindow`
poll and the once-a-second refresh poll (macOS: `windowDidChangeBackingProperties:`; Wayland: the surface's
preferred scale; X11: XSETTINGS / RandR change notifications).

### D. The secretary

One per `Gui`, standing where input enters the tree today (`InputDispatcher`'s drain), and the only door for
input: the OS path, automation and the harness all come through it, so a test sees what a user's hand produces.

```java
gui.secretary()
   .every(PointerMoved.class)                      // a drawing app: no point dropped
   .latest(WindowResized.class)
   .accumulate(Scrolled.class)
   .filter(KeyPressed.class, k -> !k.repeat());    // or any rule of the app's own
```

Defaults: discrete events (key, button, char, focus, close) are always delivered in order and cannot be set to
drop; a pending continuous event is flushed before a discrete one, so a click lands where the pointer was. Moves
keep the latest position and add up deltas, scrolls accumulate, sizes keep the latest. The discrete queue is
bounded and counts overflow loudly through the probe instead of halting the process.

**`every(PointerMoved)` needs tactroller.** Tactroller adds pointer deltas into a counter and samples once per
frame, so the intermediate points are gone before the GUI sees them. Delivering every one means tactroller keeping
a short history between samples (and the Win32 side reading `GetMouseMovePointsEx` or raw input packets). That
is a tactroller change, and the last step below.

## Order of work

Each step lands, is measured with the probe against today's numbers, and is committed on its own.

1. **B, then A.** Equality in the reconciler, the `changedSinceDraw` bit, the update/draw split in `GuiWindow`,
   the loop consulting it, `gui.requestFrame()`. The largest win and the riskiest; measured against the 400-move
   run (target: frames ≈ moves that changed hover, not ≈ moves).
2. **The modal timer** on the same rules — update every tick, draw on change.
3. **C**: wake classes in `Win32Window`, DPI and display changes as events.
4. **D**: the secretary, with automation and the harness moved onto it and tactroller's unbounded char buffer
   capped.
5. **Tactroller pointer history**, for `every(PointerMoved)`.

Storm troubleshooting on the probe (per-second rollups, per-mailbox names, a storm detector, cause attribution)
is the separate step after this.

## Decided (2026-10-05)

1. **Viewports request their own frames.** A target from `GuiApp.viewport(...)` requests a frame whenever it is
   rendered into, so the common case needs no `requestFrame()` call.
2. **The idle floor stays**, as an update-only tick: a missed wake still drains, and draws only on change.
3. **The framework never drops discrete input** (key, button, char, focus, close). An application's own filter
   may — that is its decision; a drop under load is not.
