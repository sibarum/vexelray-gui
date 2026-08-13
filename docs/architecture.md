# vexelray-gui — Architecture

A GPU-accelerated Java GUI framework that runs **on top of VexelRay** and renders **only** through
VexelRay's native 2D API. First-principles redesign; supersedes the earlier draft (which wrongly
re-implemented a renderer VexelRay already ships, and a messaging/input layer two sibling libraries
now provide).

- **Java 25 · Maven · Panama (FFM) for native bindings · Vulkan via VexelRay**
- Builds on three siblings, each a standalone library:
  - `dev.vexelray:*:0.1.0-SNAPSHOT` — the Vulkan engine (Canvas, text, runtime, OS window). Transitively
    SupirVast `dev.supirvast:vastir`.
  - `sibarum.atchung:atchung-core:1.0-SNAPSHOT` — the realtime broadcast/subscribe **message bus** +
    versioned `State`. The connective tissue: every component is a producer/consumer on the bus.
  - `sibarum.tactroller:*:1.0-SNAPSHOT` — cross-platform **keyboard/mouse/pointer/clipboard** input
    middleware (Panama, per-OS). Reaches the GUI as bus traffic via `tactroller-atchung`.

> **Status:** design + early core. Steps 1–2 of §12 are built (window → Canvas → present; retained
> tree + mutations + flex layout). This document is the target and the record of decisions.

---

## 0. The one rule

**vexelray-gui is only a framework. It re-implements nothing that a platform library already ships.**
Three platforms sit under it, and the GUI consumes each rather than re-deriving it:

- **VexelRay** owns all rendering, windowing, and the Vulkan runtime. The GUI never touches Vulkan,
  never authors a shader, never manages a vertex buffer or a swapchain. It describes *what* to draw and
  hands that to VexelRay's `Canvas`; VexelRay decides *how*.
- **Atchung** owns message passing. The GUI does not build its own queue, wake CAS, or event-delivery
  machinery — mutations, input, and application events all ride Atchung topics, pumps, and `State`.
- **Tactroller** owns input acquisition. The GUI does not poll devices or bind `user32`/X11/CoreGraphics
  — it receives input as bus events published by `tactroller-atchung`.

Corollary — **no re-implementation, and no shims bolted onto the engine.** The earlier draft proposed
adding an input event system to VexelRay (`vexelray-os`); that work is now **tactroller's**, delivered
over **atchung**, so it leaves the engine alone. What remains for the engine (§3) is only the two
capabilities that are genuinely *rendering/OS-loop* concerns no sibling covers: an idle-blocking wait
and a canvas clip rectangle.

The GUI's value is entirely *above* the pixel and *above* the wire: **identity, layout, dispatch,
motion.** That is the whole job.

---

## 1. The substrate — what the three siblings provide (ground truth)

Verified against the current libraries.

**VexelRay — rendering, text, runtime, window.**
- *2D drawing (`vexelray-canvas`).* `Canvas(w,h)` immediate-mode: `fillRect`, `fillRoundRect`,
  `fillCircle`, `strokeLine`, `text(...)` (via `TextLayout`), `Color`. Everything batches into one fat
  vertex stream drawn by one uber-shader that branches per primitive (analytic rounded-box SDF for
  shapes, MSDF median + per-vertex `screenPxRange` for glyphs). Submission order = paint order.
  `toVertexArray()` / `vertexCount()` feed a vertex buffer.
- *Text (`vexelray-text`).* `AtlasData` (msdf-atlas-gen JSON, baked by `vexelray-msdf-maven-plugin`),
  `GlyphLayout`, `TextLayout`: line breaking + wrapping, H/V alignment (incl. justify), `measure` →
  bounds, fit queries, anchor placement.
- *Runtime (`vexelray-vulkan`).* `VulkanInstance → Device → Swapchain → RenderPass → GraphicsPipeline →
  WindowedPresenter`. `WindowedPresenter` drives a **per-frame callback** with a **dynamic vertex
  buffer** at one frame in flight — rebuild-and-present each frame is a solved path
  (`DynamicCanvasDemo`). `AtlasTexture`, `OffscreenDraw`, `SampledColorTarget`.
- *Window (`vexelray-os`).* `NativePlatform.current()`, `NativeWindow` (`createWindow`, `pumpEvents`,
  `createVulkanSurface`, `width/height`, resize→swapchain recreate). Its poll-only keyboard input is
  **no longer used by the GUI** — input comes from tactroller (below).

**Atchung — the message bus (`atchung-core`).**
- `Topic<T>` typed channel identity; `Atchung.publish(topic, event)` is non-blocking and fans out.
- Three delivery modes, chosen per subscriber: **inline** (publisher's thread), **async** (an
  `Executor`), and **pumped** — queued into a bounded mailbox, delivered on the thread that calls
  `Pump.drain()`. The pumped path is exactly the render/UI-thread, once-per-frame drain the GUI needs.
- `Backpressure` per pumped mailbox: `DROP_OLDEST` / `DROP_NEWEST` / `COALESCE_LATEST` (keep newest —
  for pointer position / window size) / `BLOCK` (upstream backpressure, off the realtime path).
- `State<T>`: one producer owns a value, consumers read coherent immutable `Versioned<T>` snapshots
  (lock-free reads, CAS commit, bounded history). The "what is true now" shape, complementing the
  "what happened" events.
- Per-topic FIFO from a single publisher; pure in-VM, zero-copy; forward-designed to bridge to a
  transport for cross-process / network without changing this surface.

**Tactroller — input middleware (`tactroller-api` + per-OS backends + `tactroller-atchung`).**
- OS-agnostic `Tactroller`: `snapshot()` → immutable `InputFrame` with held state **and** edges
  (`wasPressed`, motion delta, scroll, modifiers, focus); pointer-lock (`RAW`/`RECENTER`), window
  attach + coordinate spaces (client / framebuffer / HiDPI), focus gating.
- `tactroller-atchung` **bridge**: `TactrollerInputBridge.pump()` once per frame takes a snapshot and
  publishes via `InputPublisher` — discrete edges (key/button/scroll/motion/focus) onto a lossless
  `Topic<InputEvent>` (`"tactroller.input"`), and pointer position onto a coalesced, versioned
  `State<PointerState>`. Render-thread polling; no background daemon.

The split is clean: **VexelRay is pixels, Atchung is messages, Tactroller is input.** The GUI is the
retained model, layout, dispatch, and motion that sit on top.

---

## 2. Requirements

1. One language for layout definition **and** updates. 2. Message passing; app logic off the GUI
thread; GUI owns the main thread. 3. Animations bound to a visibility lifecycle; per-node and
per-group. 4. Flexbox essentials. 5. `em/rem/vw/vh`. 6. Rich renderer effects — *deferred* (engine
seam). 7. Native file dialog. 8. MSDF fonts via VexelRay atlases. 9. Dynamic frames; lower FPS
unfocused. 10. Panama for native bindings. 11. Keyboard, mouse, full shortcuts. 12. Rich-text spans
that auto-adjust to edits. 13. **Seamless integration** — components on other threads, processes, or
machines join by speaking Atchung, never by coupling to the GUI's internals.

Requirements 2 and 11–13 are now largely *satisfied by the substrate*: message passing is Atchung,
input is Tactroller, and network transparency is Atchung's remote-bridge seam. The GUI's remaining job
for these is to **speak the bus well** — publish its mutations and events as topics/state, and consume
input topics — not to build the machinery.

---

## 3. Engine prerequisites (work lands in VexelRay, not here)

The earlier draft listed three engine gaps (E1 input, E2 wait-loop, E3 clip). **E1 is gone** — input is
tactroller-over-atchung, so nothing about input is added to the engine. Two remain, and both are
genuine *rendering / OS-loop* concerns no sibling covers:

| # | Gap | Add to | Shape |
|---|-----|--------|-------|
| E2 | **Idle-blocking loop** — `waitEvents(timeoutMillis)` + `postWake()` | `vexelray-os` | Block at 0% CPU until an OS message or timeout (`MsgWaitForMultipleObjectsEx`); `postWake()` posts a message-only wake. Lets the GUI wake exactly on input, a mutation, or an animation deadline. |
| E3 | **Canvas clip rectangle** | `vexelray-canvas` | A clip-rect stack (`pushClip/popClip`); the current clip is stamped per-vertex into the fat vertex and the fragment zeroes coverage outside it. Stays single-draw — no scissor state. Needed for scroll/overflow. |

`postWake()` is the one OS primitive the threading model needs (§5): a worker publishing a mutation
while the GUI thread sleeps in `waitEvents` must wake it. Until E2 lands, the loop polls every frame
(the wake is a no-op) — the framework runs, just not yet at 0% idle.

> **Decision to confirm:** E2 + E3 land in VexelRay (not as a GUI shim). This is the concrete, and now
> *minimal*, meaning of §0's "no re-implementation." Everything else input/messaging is a sibling's job.

---

## 4. Core model — retained tree, mutated by messages

**A retained node tree with client-stable identity, mutated exclusively through Atchung messages drained
on the GUI thread.** This is the load-bearing decision; it falls directly out of requirements 1 + 2.

**First-principles: why retained + messages (alternatives rejected).**
- *Immediate-mode (Dear ImGui style)* — the app re-emits the whole UI every frame. Rejected: it puts
  app logic **on** the render thread (violates req 2), has no stable identity to hang lifecycle
  animation or FLIP motion on (req 3), and re-evaluates everything every frame (fights req 9).
- *Retained tree mutated directly on a shared, locked model* — rejected: forces locks on the tree and a
  "safe-to-call-off-thread" grey zone (the exact mess this design exists to avoid).
- *Retained tree + message channel* — accepted: construction and updates are the **same** vocabulary
  (req 1: a `Create` at frame 0 is just the first mutation), the channel is the thread boundary
  (req 2), and stable ids give lifecycle + motion something to attach to (req 3).

```
Node (handle)            RetainedNode (model)
 held by app, any thread  GUI thread only
 immutable identity       the live, mutable tree
 long id + publishes      props, children, layout cache, input/anim state
 setters PUBLISH a        mutated only by the reconciler, on drain
 Mutation on the bus
```

Ids are client-assigned (`AtomicLong`), stable for life — editing a node never mints a new identity, so
attached state never orphans. Mutations: `Create · Insert · Remove · SetProp · SetText · Batch` (a
sealed `Mutation` type). `Batch` posts one atomic group no frame boundary can split. Structural ops
never coalesce; per-key `SetProp` coalescing is a later optimization (see §5).

**The mutation channel is an Atchung topic, not a bespoke queue.** `Node` setters publish a `Mutation`
to an internal `Topic<Mutation>`; the GUI thread owns a `Pump` subscribed to it and `drain()`s once per
frame, applying each mutation to the tree through the single-writer `Reconciler`. Losslessness is
required (a dropped tree edit corrupts the model), so the mailbox uses `Backpressure.BLOCK` with a
generous capacity — the exact "backpressure that throttles a hot worker flooding the channel" the prior
design hand-rolled, now provided by the bus. This is the pure realization of §0: the GUI owns the
*model and the reconciler*, Atchung owns the *transport*.

**Reads flow out as events and state, never as tree access.** Widgets and the framework publish
`ValueChanged/Click/Focus/...` onto Atchung topics; long-lived truths (selection, document model,
pointer) are `State<T>`. Workers subscribe; the app holds its own state and the GUI is a projection of
it. Unidirectional, Elm-shaped — and because it is all bus traffic, a subscriber on another thread,
process, or machine is indistinguishable from a local one (req 13).

---

## 5. Threading — GUI thread + workers, unified on Atchung

**The GUI thread owns only drain → reconcile → layout → emit → present. Everything else is a worker.**
The contract is strictly one-way: workers **publish** mutations and **subscribe** to immutable events /
state; **only** the GUI thread mutates the retained tree. Atchung is the sole boundary — there is no
second messaging mechanism.

- **Mutation channel — a pumped Atchung topic.** Drained once per frame on the GUI thread
  (`Pump.drain()`). Per-producer FIFO preserves a worker's program order; `Batch` is one publish, hence
  atomic; `BLOCK` backpressure throttles a runaway producer instead of dropping edits.
- **Input — a pumped Atchung topic + a pointer `State`.** `tactroller-atchung` publishes
  `"tactroller.input"` edges and a `State<PointerState>`; the GUI's frame loop drains the input pump
  (dispatch, §8) before draining mutations, so this frame's input is reflected this frame.
- **Application handlers — async by default.** `gui.on(topic, handler)` registers via
  `subscribeAsync(...)` on the worker executor, so a slow handler can never stall rendering; a
  `gui.onUi(...)` variant uses the GUI-thread pump for the rare handler that must run there.
- **Wake (E2).** When the GUI thread sleeps in `waitEvents`, a worker publishing a mutation — or
  tactroller publishing input — must wake it. This is a single **inline, coalescing** subscriber on the
  relevant topics that calls `window.postWake()`; a burst collapses to one OS wake (the mailbox
  coalesces, or a one-shot `AtomicBoolean` guards the call). This replaces the prior bespoke
  `PENDING_WAKE` CAS with a bus subscription — one mechanism, not two. Until E2 lands it is simply not
  installed and the loop polls.

The GUI holds one `Atchung` bus (its own by default, or one handed in to share with the wider
application). Input publishers, widgets, workers, and — via Atchung's transport bridge — remote peers
all meet on it.

---

## 6. Layout + units

Rows and columns with **border-box** sizing: padding, margin, border and gap, `justify` + `alignItems`,
grow/fill. Not a full flex implementation (no wrap, no shrink-below-basis) but **bulletproof** — every
size is clamped non-negative, every property defaults sensibly, nothing is ever left null or NaN, so a
node can never land at an unexpected position or size. **One `measure(axis)` per node** derives
intrinsic sizes; a second pass places everything.

**Border-box.** A node's rect (`x,y,w,h`) is its border-box: `w`/`h` include border + padding; the
content box children occupy is inset by `border + padding` on every side. Margin is space *outside* the
border-box separating a node from its siblings and its parent's content edge.

**Units — no pixel unit.** Every length is relative, so a UI scales with font size, zoom, DPI and window
size rather than being pinned to device pixels. Resolved only at layout time against a
`LayoutContext{ rootEmPx, zoom, dpi, viewportW/H }`:

```java
sealed interface Length permits Em, Rem, Percent, Vw, Vh, Grow /*flex*/, Auto, Fill { }
```

`em = rem = v·rootEmPx·zoom·dpi` (flat root, no cascade); `vw/vh = v/100·viewport`;
`percent = v/100·basis`, where the basis is the parent content extent along the axis (width/height) or
the node's own border-box width (padding/border/gap/corner). `Auto` sizes to content; `Fill`/`Grow`
share leftover main-axis space (on the cross axis they stretch like `Auto`). Every visual scalar — width,
height, padding, margin, border width, gap, corner radius, **text size** — is a `Length`; there is no
`px`. Layout resolves border/corner/text-size to px onto the node so the renderer needs no units or
context. Layout runs only when structure or a layout-affecting prop changed (`layoutDirty`), never per
animation frame (§7).

---

## 7. Animation — a visual-transform layer, GUI-side

Opt-in per node; nothing fires unless an animation is attached. Animatable properties are a
**post-layout visual transform** — `OPACITY, TRANSLATE_X/Y, SCALE, ROTATION, TINT` — **not** layout
inputs. Animating them never re-runs layout; it only changes how the tree is emitted to the `Canvas`.

**This needs zero engine and zero bus support:** because we draw immediate-mode, the emitter simply
applies the current transform when it makes `Canvas` calls. The visibility FSM
(∅→MOUNTING→VISIBLE↔HIDDEN→UNMOUNTING→DESTROYED) reacts to mutations; `onExit` defers destruction.
`Animated<T>` smooth-interrupt + a next-deadline query drive the wait-loop (§10). Global
`gui.motion(DISABLED)` collapses every timeline to its end-state → loop stays 0 idle frames (also the
reduced-motion path). Built-in defaults ≤100ms; long motion is always app-authored.

Semantic-transaction choreography (FLIP diff on stable ids, hero/`moveTo` ghosts on an overlay layer)
is a **later** high-level layer; not v1.

---

## 8. Input dispatch — framework-owned, over Atchung input events

Consumes tactroller's `"tactroller.input"` topic and `State<PointerState>` from the bus. The framework
does hit-testing (against the laid-out tree, using pointer position from the coalesced `State`),
capture/focus routing, and leaf→root bubbling with consume semantics — no app-owned dispatch chain.
`Shortcut(mods, key)` → a command registry (focus-scoped + global), dispatched before text input claims
the key. Dispatch **re-publishes** high-level results (`ValueChanged/Click/FocusEvent/...`) as Atchung
topics for workers — so raw device input and semantic UI events share one fabric, and either can cross
to another process via the transport bridge.

Coordinate space and focus gating are tactroller's job (`attach`, `setCoordinateSpace(CLIENT)`,
`setFocusGated`); the GUI consumes already-correct client-space coordinates.

---

## 9. Text + RichText

Reuse `vexelray-text` wholesale (atlas, `GlyphLayout`, `TextLayout`, MSDF via `Canvas.text`). Add a
`RichText` model on the node: an immutable rope of runs `{fg, bg/highlight, weight, outline}` with
stable offsets so spans auto-adjust across edits (req 12); each edit publishes a new snapshot atomically
(a natural `State<RichText>`). Weight/outline map to the MSDF edge-shift the engine exposes; a highlight
is a background `fillRoundRect` emitted before the glyphs. Clipboard for text fields is
`tactroller-clipboard` (standalone, no input-subsystem coupling).

---

## 10. The frame loop (VexelRay-native, Atchung-drained)

The GUI owns the main thread and runs VexelRay's dynamic present path — the same one `DynamicCanvasDemo`
uses — with an idle-blocking wait (E2):

```
GUI thread, per dirty/animating frame:
  1. block in waitEvents(timeout = animating ? nextDeadline : ∞)   // E2, 0% CPU idle
  2. window.pumpEvents();  inputBridge.pump()                      // tactroller snapshot -> bus
  3. inputPump.drain()  -> dispatch (§8) -> publish app events
  4. mutationPump.drain()  -> reconciler applies each (single writer)
        structural/layout prop -> layoutDirty;  Remove w/ onExit -> lifecycle;  animatable -> animation
  5. lifecycle.tick(now); animation.tick(now)
  6. if layoutDirty: layout(root, viewport)                        // one measure pass
  7. canvas.begin(); emit(tree, canvas)  // walk, apply transforms(§7) + clip(E3), fill*/text
  8. vertexBuffer.update(canvas.toVertexArray()); presenter.setVertexCount(...)   // native
  9. present                                                        // WindowedPresenter
```

Render-on-demand: with no animations and no input/mutations, the loop blocks in step 1 — zero frames; a
worker's publish or tactroller input wakes it via `postWake()` (§5). **Focus-aware cap** (req 9):
unfocused clamps the wake cadence via the step-1 timeout, no busy loop. Steps 7–9 are pure `Canvas` +
native presenter; the GUI writes no Vulkan.

---

## 11. Modules

```
vexelray-gui           parent (pom), groupId dev.vexelray.gui, Java 25
├─ vexelray-gui-core   the framework core: model (Node/RetainedNode, Mutation, reconciler), the
│                      Atchung-backed mutation channel + event/state publishing, Length + flex layout,
│                      lifecycle FSM + animation transform layer, framework-owned input dispatch,
│                      RichText, and the app loop (GuiApp).
│                      -> vexelray-canvas, -text, -vulkan, -os-api, atchung-core
├─ vexelray-gui-widget widgets built on core: box, text, button, slider, list, scroll, text field, … -> -core
├─ vexelray-gui-nfd    the GUI's one native binding — a Panama nativefiledialog-extended facade;
│                      results delivered back through Atchung.                                        -> -core
└─ vexelray-gui-demo   canonical showcase app; wires tactroller-atchung for real input.
                       -> -widget, -nfd, tactroller-api, tactroller-windows, tactroller-atchung
```

Core packages: `model · layout · anim · input · text · app`. **Dependencies on the siblings:** `-core`
depends on `atchung-core` (the bus is core to the model and threading). Tactroller enters at the
**application edge** — the demo (and any real app) constructs a `Tactroller`, a `TactrollerInputBridge`,
and hands the GUI the same `Atchung` bus, so the framework core stays decoupled from any specific input
source (it only knows the `"tactroller.input"` topic contract). The NFD Panama facade (req 7) is the
GUI's single native binding, invoked on the GUI thread (NFD is modal/main-thread), result published to
the bus.

---

## 12. Build sequence

Engine gaps first (now only two), then the framework bottom-up; the messaging/input substrate already
exists as siblings, so those steps are *integration*, not construction.

1. **E3** Canvas clip-rect (smallest, unblocks scroll/overflow) — in VexelRay.
2. **E2** `waitEvents(timeout)` + `postWake()` idle-blocking — in VexelRay (`vexelray-os`).
3. `GuiApp` + frame loop (§10): a hard-coded tree drawn via Canvas, presenting on demand. **[done]**
4. `Node`/`RetainedNode` + `Mutation` + reconciler + **Atchung-backed mutation channel** (§4). **[done;
   channel now migrating from the bespoke sink to an Atchung `Topic`/`Pump`]**
5. `Length` + flex layout + `measure` (§6); the tree→Canvas emitter (§10 step 7). **[done]**
6. **Input integration:** wire `tactroller-atchung` into the loop (steps 2–3); framework dispatch +
   focus + shortcuts (§8); first interactive widgets (button, slider). **[in progress: pointer input is
   live — tactroller → atchung `"tactroller.input"` → `InputDispatcher`, drained each frame in
   `Gui.frame`. Clicks (hit-test + leaf→root bubbling → `onClick` + a `ClickEvent` topic) and
   hover/pressed `InteractionState` (`onState`, ancestor-or-self coverage) both work; the demo buttons are
   clickable and restyle on hover/press. Pointer **capture + drag** (`onDrag` → `DragEvent` START/MOVE/END,
   tracking off-node until release) drives a first widget, the `Slider` (`vexelray-gui-widget`). Focus
   routing, wheel, keyboard, and shortcuts remain.]**
9. **Overflow + scroll (in progress):** decided — reserve-space scrollbars on both axes (per-axis
   disable), always visible when a container overflows (never hover-triggered; see the pointer-target UX
   rule). Requires engine **E3** (Canvas clip-rect) first, then overflow detection + scroll offset +
   scrollbar widget (drag reuses `onDrag` capture) + wheel dispatch.
7. Application event/state publishing (§4) + `gui.on/onUi` over Atchung (§5); the E2 wake subscriber.
8. Lifecycle FSM + animation transform layer (§7); `RichText` (§9).
9. Scroll/clip views, text field (+ `tactroller-clipboard`); then (later) semantic-transaction
   choreography; file dialog.

---

## 13. Resolved decisions

1. **Rendering (§0):** GUI renders only through VexelRay's `Canvas`; no Vulkan/shader/vertex code here.
2. **Messaging (§0, §4–5):** Atchung is the one message-passing fabric — mutations, input, and app
   events all ride it. The GUI builds **no** bespoke queue, wake CAS, or delivery machinery; the prior
   `MutationSink`/`PENDING_WAKE` is replaced by an Atchung `Topic`/`Pump`.
3. **Input (§0, §3, §8):** input is **tactroller-over-atchung**. The prior "E1 = add input to the
   engine" is dropped; nothing input-related is added to VexelRay. Engine prerequisites shrink to E2
   (idle wait-loop) + E3 (clip-rect).
4. **Retained-tree model (§4):** kept, justified from reqs (immediate-mode and locked-tree rejected).
   Only the *transport* changed (bespoke sink → Atchung).
5. **Threading (§5):** GUI thread drains pumps (mutation + input) per frame; app handlers are
   `subscribeAsync` on a worker executor; the E2 wake is a bus subscription, not a separate primitive.
6. **Integration boundary (§11):** `-core` depends on `atchung-core`; tactroller enters at the app edge
   over a topic contract, keeping the core decoupled from any concrete input source.

Still open: layout-animation path (size/position via `onChange`) first-class vs. transform-layer-only
for v1; group membership static vs. dynamic; choreographer interruption semantics; whether tree
mutations ever share a bus instance with cross-component traffic or always use a private internal topic
(currently: shared bus, private topic name).
