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
tactroller-over-atchung, so nothing about input is added to the engine. Three remain: E2 and E3 as before, plus
**E4**, added after finding that `NativeWindow` cannot express the difference between a window's point extent and
its pixel extent at all. All three are genuine *rendering / OS-loop* concerns no sibling covers:

| # | Gap | Add to | Shape |
|---|-----|--------|-------|
| E2 | **Idle-blocking loop** — `waitEvents(timeoutMillis)` + `postWake()` | `vexelray-os` | Block at 0% CPU until an OS message or timeout (`MsgWaitForMultipleObjectsEx`); `postWake()` posts a message-only wake. Lets the GUI wake exactly on input, a mutation, or an animation deadline. |
| E3 | **Canvas clip rectangle** | `vexelray-canvas` | A clip-rect stack (`pushClip/popClip`); the current clip is stamped per-vertex into the fat vertex and the fragment zeroes coverage outside it. Stays single-draw — no scissor state. Needed for scroll/overflow. |
| E5 | **Cursor shapes** | `vexelray-os` | `NativeWindow.Cursor` offers only `ARROW` and `TEXT`. The pointer rule (§8.3) also asks for a hand over anything clickable and open/closed hands over anything grabbable; those fall back to the arrow until the enum and its OS handles exist. Cosmetic, unlike E2-E4, and the rule that decides them is framework-side and already tested. |
| E4 | **Framebuffer extent + content scale** | `vexelray-os` | `framebufferWidth()/Height()` (pixels) alongside `width()/height()` (points), and `contentScale()`. Both must track live monitor changes — dragging a window between a dense and a conventional display changes the factor while running. |

**E4 is the one that breaks a framework rather than merely limiting it.** `NativeWindow` currently exposes a
single `width()`/`height()` and no scale at all, and `GuiApp` feeds that one number to three consumers that do
not all want the same space: the Vulkan swapchain extent (pixels, non-negotiable — surface capabilities are in
pixels), the `Canvas` (must match the framebuffer), and the layout viewport (what `vw`/`vh` resolve against, and
the space input is hit-tested in). On a conventional display points and pixels are the same number and all three
agree by coincidence. On a dense one they differ by the scale factor and at least one of the three is wrong,
*whichever* space `width()` returns — so this is not a question about the implementation, it is a gap in the API's
expressiveness.

Two consequences worth stating, because both are non-obvious:

- **DPI awareness is declared by packaging, not by code.** `NSHighResolutionCapable` in a macOS bundle's
  Info.plist; the manifest or `SetProcessDpiAwarenessContext` on Windows. A JVM launch inherits the JVM's
  declaration and a native-image binary has its own, or none — so identical source behaves differently purely by
  how it was built. That makes it a native-image concern (proof-plan §4) as much as an engine one.
- **Density is testable without a dense display.** The confusion is arithmetic, not hardware: laying out at
  `dpi = 2` and delivering input in point space reproduces it exactly, on any machine (`DpiTest`). There is no
  reason to discover this during a port.

**Until E4 lands, density cannot be switched on at all** — this was tried at the application edge and had to be
backed out, which is worth recording because each half of it fails in a differently misleading way:

- The engine's window and `Canvas` are in **logical** coordinates, so the OS is already scaling their output on a
  scaled display. Feeding `contentScale()` into `Gui.dpi` therefore scales the content a *second* time — 1.56× on
  a 125% display — which reads as "everything is too big" rather than as a units bug.
- `CoordinateSpace.FRAMEBUFFER` is `CLIENT × contentScale`, so against a logical canvas it puts every press
  down and to the right of the cursor by the scale factor. `CLIENT` is correct while the canvas is logical.
- `contentScale()` is a property of the window's monitor, so it reads 1.0 before `attach`. A window therefore
  cannot be created at the pixel size its own scale calls for — and sizing it from that 1.0 leaves density pinned
  at 1.0, which *looks entirely correct* while silently disabling the thing it was meant to enable.

All three flip together the moment the process is DPI-aware and the canvas is in pixels; none of them can flip
before that. Note that DPI awareness is declared by **packaging** — a manifest or `SetProcessDpiAwarenessContext`
on Windows, `NSHighResolutionCapable` on macOS — and the engine owns window creation, so this is not something an
application can opt into from its own code.

The framework half is done and tested regardless (`Gui.dpi`, `Length.dp`, `DpiTest`): the arithmetic is exercised
headlessly at density 2, so E4 is a wiring task when it lands, not a design one.

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

**Computed geometry flows out the same way — the layout read-model.** A worker cannot read its node's
laid-out rect, scroll or caret geometry off the write-only handle, and the answer is *not* a per-feature
callback: core publishes an immutable, versioned `LayoutSnapshot` of the whole tree as a coalesced
`State`, and `Node.layout()` reads its entry lock-free. Pure data, no captured measurer — so a widget, a
test, a devtools overlay, or a remote client with no glyph atlas all answer the same questions the same
way. **docs/layout-read-model.md is the full treatment**; the load-bearing parts to know here:

- **Three tiers, one writer each** (§2.1 there): *authored props* written by the command phase,
  *derived geometry* written by the compute phase, and the *read-model* which `publishLayout` only
  copies. Compute in layout, copy in publish, read everywhere else — renderers and widgets never write.
- Anything that is a pure function of (authored props + viewport + measurer) is derived geometry, so
  "where does this computed value go?" has a mechanical answer rather than a judgement call.
- The rule is machine-checked: `vexelray-gui-architecture` fails the build if any class outside the
  declared stages writes a `RetainedNode` field (§11).

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
node can never land at an unexpected position or size. `measure(axis)` derives intrinsic sizes; a second
pass places everything.

**Text is the exception to "one measure pass", and deliberately so.** A wrapped node's height *is* its
wrapped line count times the line height, so text height is **height-for-width**, not an intrinsic —
`measure(VERTICAL)` alone can only ever answer "one line". Two consequences:

- The axes resolve in opposite orders. In a **column** a child's width is settled by the container before
  its height matters, so `place` resolves the cross axis first and feeds each width into the height
  measure; in a **row** the dependency runs the other way and the existing cross pass already has it.
- Reserving a scrollbar narrows the box, and narrower wrapped text is *taller*, so a scroll container
  re-measures its content at the reserved width. That cannot oscillate: narrowing never shortens wrapped
  content, so a box that overflowed at the full width still overflows at the reserved one.

Line breaking enters through exactly one seam, `TextMeasurer.lineSpans`, and is computed **once** per
changed frame onto `RetainedNode.lineSpans` — the layout sizes from it, the compute phase bakes caret
geometry from it, and the renderer draws from that. Two stages independently re-breaking the same text is
how the box stops matching the lines drawn in it.

Layout runs only when structure or a layout-affecting prop changed (`layoutDirty`). Derived geometry has
its own, weaker flag: `geometryDirty` re-runs the compute phase and republishes without a relayout, which
is what a caret move needs — it moves the view without reflowing anything.

**Border-box.** A node's rect (`x,y,w,h`) is its border-box: `w`/`h` include border + padding; the
content box children occupy is inset by `border + padding` on every side. Margin is space *outside* the
border-box separating a node from its siblings and its parent's content edge.

**Units — no device-pixel unit.** Every length is relative, so a UI scales with font size, zoom, DPI and
window size rather than being pinned to the device grid. Resolved only at layout time against a
`LayoutContext{ rootEmPx, zoom, dpi, viewportW/H }`:

```java
sealed interface Length permits Em, Rem, Dp, Percent, Vw, Vh, Grow /*flex*/, Auto, Fill { }
```

`em = rem = v·rootEmPx·zoom·dpi` (flat root, no cascade); **`dp = v·dpi`**; `vw/vh = v/100·viewport`;
`percent = v/100·basis`, where the basis is the parent content extent along the axis (width/height) or
the node's own border-box width (padding/border/gap/corner). `Auto` sizes to content; `Fill`/`Grow`
share leftover main-axis space (on the cross axis they stretch like `Auto`). Every visual scalar — width,
height, padding, margin, border width, gap, corner radius, **text size** — is a `Length`. Layout resolves
border/corner/text-size/text-insets to px onto the node so the renderer needs no units or context.
Neither layout nor the compute phase runs per animation frame (§7).

**Why `dp` is not the pixel unit returning.** The original rule was doing two jobs at once, and they are
separable:

1. *Never pin to the device grid* — density must be honoured, or the UI breaks on a dense display.
2. *Scale with the user's zoom* — a good default, not a law.

`em`/`rem` satisfy both. `dp` keeps (1), which is the one whose violation is fatal, and opts out of (2).
The same split as Android's `dp` vs `sp`. It exists because **zoom is a request to make content legible,
and tripling the frame around the content to match means seeing less of what you zoomed in to read.**

The boundary, which is where this will go wrong if it goes wrong: `dp` is for chrome that is not
proportional to text — panel padding, gaps, margins, separators, hairlines. Anything that *sizes or
contains glyphs* stays in `em`, including the text insets in `TextMetrics`; make those `dp` and at 3× you
get triple-height text inside an unchanged inset, all but touching its border. A row whose height must fit
a label is likewise `em`, not `dp`, even though a toolbar feels like chrome.

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
capture/focus routing, and leaf→root bubbling for pointer events — no app-owned dispatch chain. Dispatch
**re-publishes** high-level results (`ValueChanged/Click/FocusEvent/...`) as Atchung topics for workers —
so raw device input and semantic UI events share one fabric, and either can cross to another process via
the transport bridge.

**Visibility is a prop, not a structural edit.** `Node.visible(false)` takes a node and its subtree out of layout,
drawing, hit-testing and the compute phase while leaving everything attached to it alone. The distinction from
removal is not cosmetic: registrations are keyed by node id and released when a node leaves the tree (§13), so a
page rebuilt by remove/insert comes back drawn but inert — a text field on it would no longer take a keystroke.
Anything showing one of several children at a time therefore wants this rather than structural churn, and `Tabs`
is built on exactly that.

Two consequences that are easy to miss, both found by getting them wrong first: a hidden node must be skipped by
the *compute phase* as well, because a text node draws from its baked `TextMetrics` rather than from its rect, so
metrics baked against a zeroed rect will happily draw the hidden text at the window origin. And its rect is zeroed
rather than left stale, so nothing downstream reads last frame geometry for something no longer shown.

**The cursor advertises what the pointer can do, and it is inferred rather than declared.** A node takes the
hand because it has a click handler — the same ancestor-or-self walk clicks bubble by, so a button label is part
of its button; a text node takes the I-beam because it is editable; the framework knows where its own scrollbars
are. The affordance therefore falls out of registering the behaviour, and no widget can forget to describe itself.
Precedence runs most-specific-first: a grab in progress, then something grabbable, then something clickable, then
text — so a control inside an editable field reads as a control, and a scrollbar over text reads as a scrollbar.

Declaration (`gui.cursor(node, GRAB)`) exists only for what cannot be inferred: a slider and a text field both use
the drag seam, so a drag handler alone is not an affordance and only the slider can say it is grabbable. That
asymmetry is the whole reason the seam exists, and it is pinned down by a test rather than left to convention.

**Keys: core eats nothing; preemption is declared in advance.** There is no `preventDefault` here, and
there cannot be — cancelling requires a handler to answer *synchronously* that it consumed the event, and
handlers run on worker threads through the bus, so by the time one could answer the frame is over. Every
framework offering bubbling and cancellation is quietly paying for it with an isolated GUI thread. What
replaces it is a **claim**: a node declares up front that it takes a chord, at a scope.

```java
enum ClaimScope { FOCUSED, VISIBLE, GLOBAL }   // most specific wins
gui.claim(node, Shortcut.of(Key.TAB), ClaimScope.FOCUSED, editor::indent);
```

When a claim applies, its command runs and the key reaches nothing else. The dispatcher resolves claims
on the GUI thread from state it can read immediately, while the command itself runs wherever it likes.
Two consequences worth knowing:

- **Framework defaults are ordinary claims, not interception.** Tab traversal is a `GLOBAL` claim, so a
  focused multiline editor outranks it by claiming Tab at `FOCUSED` scope — no property, no special case,
  and core needs no knowledge of what a text field is. `Gui.shortcut(...)` is likewise just a `GLOBAL`
  claim owned by no node.
- **Unclaimed keys go to the focused node**, and only then. Auto-repeat is armed on that path alone, so a
  held Tab inserts one soft tab rather than a stream.

**Everything is observable, whatever the routing decided.** The raw stream was never consumed —
`"tactroller.input"` is a plain topic and the dispatcher is one subscriber among any number. What the
dispatcher adds is the *resolved* view: `gui.keyRoutes()` reports every key press, who held focus, and
whether a claim preempted delivery. Strictly observational — the moment observing could veto, it would be
`preventDefault` again with the synchronous assumption in tow. Authority lives in claims; the channel only
reports.

Coordinate space and focus gating are tactroller's job (`attach`, `setCoordinateSpace(CLIENT)`,
`setFocusGated`); the GUI consumes already-correct client-space coordinates.

---

## 9. Text + RichText

Reuse `vexelray-text` wholesale (atlas, `GlyphLayout`, `TextLayout`, MSDF via `Canvas.text`). Weight and
outline map to the MSDF edge-shift the engine exposes; a highlight is a background `fillRoundRect`
emitted before the glyphs. Clipboard for text fields is `tactroller-clipboard` (standalone, no
input-subsystem coupling).

**What shipped instead of the planned rope.** Spans are a plain `List<Span>` of `[start, end)` ranges
carrying `{fg, bg, underline}`, and edits produce a `TextEdit(at, removed, inserted)` diff. That one diff
does double duty exactly as intended (req 12): undo/redo replays it, and every span remaps its offsets
through it, so formatting stays attached to its text across edits. A rope/piece-table is a later
optimization for large documents, not a prerequisite — the diff was the load-bearing idea, not the
storage.

**Where text geometry lives.** Everything positional — visual line breaks, per-boundary caret x, line
tops, the alignment indent, the hard-line number of each row — is baked into `TextMetrics` by the compute
phase and published in the read-model (§4). The renderer measures **nothing**: it draws every glyph run,
selection rect, underline, caret and line number from those baked coordinates. That is what makes the
published geometry trustworthy — the only way to guarantee the read-model describes what is drawn is for
the drawing to come from the read-model. Point↔offset, vertical navigation and visual Home/End are then
pure lookups a widget (or a remote client with no atlas) performs itself.

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
        structural/layout prop -> layoutDirty;  caret -> geometryDirty;
        Remove w/ onExit -> lifecycle;  animatable -> animation
  5. lifecycle.tick(now); animation.tick(now)
  6. if layoutDirty: layout(root, viewport)                        // boxes, viewports, overflow
  6b. if layoutRan || geometryDirty:                               // the compute phase (§4)
        resolveGeometry(root, measurer)   // caret-follow scroll, text metrics, gutter -> onto the tree
        publishLayout(root)               // pure copy -> LayoutSnapshot on the bus
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
├─ vexelray-gui-demo   canonical showcase app; wires tactroller-atchung for real input.
│                      -> -widget, -nfd, tactroller-api, tactroller-windows, tactroller-atchung
└─ vexelray-gui-architecture   test-only: the separation-of-concerns guard. No main sources, test-scope
                       deps only, so nothing here reaches a runtime or a native image. Reads the compiled
                       classes of -core and -widget (bytecode, not source — an alias or a wildcard import
                       defeats a source scan). Fails the build if a GUI class references the wire stack,
                       or if anything outside the declared stages writes a RetainedNode field.
                       -> -core, -widget (test), asm (test)
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

Engine gaps first (three: E2, E3, E4), then the framework bottom-up; the messaging/input substrate already
exists as siblings, so those steps are *integration*, not construction.

1. **E3** Canvas clip-rect (smallest, unblocks scroll/overflow) — in VexelRay.
2. **E2** `waitEvents(timeout)` + `postWake()` idle-blocking — in VexelRay (`vexelray-os`).
3. `GuiApp` + frame loop (§10): a hard-coded tree drawn via Canvas, presenting on demand. **[done]**
4. `Node`/`RetainedNode` + `Mutation` + reconciler + **Atchung-backed mutation channel** (§4). **[done;
   channel now migrating from the bespoke sink to an Atchung `Topic`/`Pump`]**
5. `Length` + flex layout + `measure` (§6); the tree→Canvas emitter (§10 step 7). **[done]**
6. **Input integration:** wire `tactroller-atchung` into the loop (steps 2–3); framework dispatch +
   focus + keys (§8); first interactive widgets. **[done: clicks, hover/pressed `InteractionState`,
   pointer capture + drag (`Slider`), wheel + scrollbar-thumb drag, focus + Tab traversal, keys and
   `CharTyped`. Shortcuts became claims (§8, resolved decision 9).]**
7. **Overflow + scroll.** Reserve-space scrollbars on both axes (per-axis disable via `Node.scroll(x,y)`),
   visible only on overflow, never hover-triggered (pointer-target UX rule). **[done, containers and text
   nodes alike: text leaves report their own content extent, so a multiline editor takes the wheel and the
   thumb like any other scroller. A wrapped node never scrolls horizontally — nothing lies to the right of
   a wrapped line — and a single-line input masks at its edge instead of growing a bar.]**
8. **The layout read-model (§4)** and everything built on it: boxes → text metrics → click-to-caret → the
   compute phase → multiline, wrap and vertical navigation → line numbers → the unified label draw path.
   **[done — see docs/layout-read-model.md, which is closed.]**
9. Application event/state publishing (§4) + `gui.on/onUi` over Atchung (§5); the E2 wake subscriber.
10. Lifecycle FSM + animation transform layer (§7).
11. Overlay/popup layer (tooltips, menus, dialogs); then (later) semantic-transaction choreography; file
    dialog. Proving the architecture end-to-end — transports, bridge, a headless remote GUI — is its own
    plan in docs/architecture-proof-plan.md (M0 landed).

---

## 13. Resolved decisions

1. **Rendering (§0):** GUI renders only through VexelRay's `Canvas`; no Vulkan/shader/vertex code here.
2. **Messaging (§0, §4–5):** Atchung is the one message-passing fabric — mutations, input, and app
   events all ride it. The GUI builds **no** bespoke queue, wake CAS, or delivery machinery; the prior
   `MutationSink`/`PENDING_WAKE` is replaced by an Atchung `Topic`/`Pump`.
3. **Input (§0, §3, §8):** input is **tactroller-over-atchung**. The prior "E1 = add input to the
   engine" is dropped; nothing input-related is added to VexelRay. Engine prerequisites are E2 (idle wait-loop),
   E3 (clip-rect) and E4 (framebuffer extent + content scale) — E4 added later, once it was clear the window API
   cannot distinguish points from pixels and so cannot be made correct on a dense display from this side.
4. **Retained-tree model (§4):** kept, justified from reqs (immediate-mode and locked-tree rejected).
   Only the *transport* changed (bespoke sink → Atchung).
5. **Threading (§5):** GUI thread drains pumps (mutation + input) per frame; app handlers are
   `subscribeAsync` on a worker executor; the E2 wake is a bus subscription, not a separate primitive.
6. **Integration boundary (§11):** `-core` depends on `atchung-core`; tactroller enters at the app edge
   over a topic contract, keeping the core decoupled from any concrete input source.
7. **Computed state is a read-model, never a callback (§4):** the single-writer rule was always about
   *unordered writes*, not about reads. Geometry a worker needs flows back as a published snapshot; the
   ad-hoc per-feature seams it replaced (`onCaretHit`/`onCaretDrag`) are deleted, and adding a new one is
   the wrong answer by default. See docs/layout-read-model.md.
8. **Derived geometry has one owner (§4, §6):** the compute phase computes, publish copies, renderers and
   widgets read. Enforced by `vexelray-gui-architecture`, after a live instance of the opposite —
   caret-follow scroll living in `TreeRenderer` meant a field behaved one way on screen and another way
   headless, silently, for as long as nobody looked.
9. **Input preemption is declared, not cancelled (§8):** claims with a scope, resolved on the GUI thread,
   because `preventDefault` needs a synchronous handler answer that an async bus cannot give. Framework
   defaults (Tab traversal, global shortcuts) are themselves claims, so any focused element can outrank
   them. Observation (`gui.keyRoutes()`) is separate and can never veto.
10. **Ordered state transitions run on the drain; notifications stay async (§5, §8).** You cannot have
    concurrent unordered handlers and an order-dependent transition without a serialization point, and the
    only three places to put one are the delivery, a reorder buffer at the consumer, or the transition
    itself. The first two are the same queue — and building it inside the dispatcher would re-implement the
    bus's own delivery machinery (§0) while spending exactly the asynchrony §5 exists to provide. So the
    transition moves to the stage that is *already* a total order. `onCharUi`/`onKeyUi`/`onDragUi`/`claimUi`
    are that seam; a stage must be bounded model work, never application logic. Everything without an
    ordering requirement — `onChange`, app handlers, anything slow — stays on the worker executor, where it
    still cannot stall rendering. Landed after a text field applied a 64-character burst scrambled: each
    edit was individually coherent, so the result read as a dropped keystroke rather than a race
    (`HandlerOrderingTest`, the module's only test that runs handlers on a real pool).
11. **Widget state is a `State<T>`, not a private buffer behind a lock (§4).** A widget holding its own
    authoritative model is the "retained tree on a shared, locked model" alternative §4 rejected,
    reintroduced one layer up. `TextField` now owns a `State<Document>` changed by committing a *relative*
    `Edit` the reducer resolves against the current value: readers on any thread get a coherent versioned
    snapshot lock-free, and a concurrent programmatic change costs a CAS retry instead of a lost edit.
    Absolute mutations are what made this invisible — recomputing the whole string from a stale read yields
    a coherent document with a keystroke missing, and nothing reports it.
12. **Order is `(conduit, sequence)`; time is evidence; conflict criteria are undefined.** A conduit is a
    single-threaded ordering domain, so its counter is a plain increment — free, uncontended, and exact,
    with none of the uncertainty interval that comparing clocks carries. The counter nobody can afford is a
    *global* one. Across conduits the sequences are deliberately incomparable and timestamps are the only
    evidence, bounded by sync error — which is why they cannot adjudicate events packed tighter than that
    bound, and why ordering is never left to them. Resolving a genuine cross-conduit conflict has no general
    answer at all: a winner is picked by criteria the domain chooses (a deterministic hash, an arbiter), the
    only universal requirement being that every peer applies the same criteria. Left undefined, because the
    topology is single-writer — one host owns the document, the bridge does not relay — so no conflict can
    arise. **The trigger to revisit is a second writer, not a date.**
13. **`dp` — density-independent chrome (§6).** A length that honours DPI and ignores zoom, added after
    observing that scaling the frame with the content is self-defeating: at 3× the demo's chrome consumed the
    window and starved the content region it surrounds. Justified as *splitting* §6's original rule rather than
    breaching it — never-pin-to-device-pixels is kept, scale-with-zoom is opted out of. Restricted by convention
    to chrome that is not proportional to text; `TextMetrics`' insets stay `em` precisely because they are.
    Verified with the 2×2 that is the unit's entire contract: density moves `dp`, zoom does not, `em` answers
    both. **Deliberately not machine-checked.** The obvious guard — "`dp` may not appear in `gui-core`" — is
    wrong: it bans the legitimate uses along with the bad one. Scrollbar thickness lives in `FlexLayout` and is
    chrome, so it is a reasonable `dp` candidate; `TextMetrics`' insets live one package over and must stay `em`.
    The distinction is *is this proportional to text*, which no package boundary tracks, so it stays a documented
    rule rather than a guard that would be wrong in both directions.
14. **The stack updates in unison, so wire-format skew is not a case.** Every peer is one build, deployed
    atomically; there is no rolling window, so DTOs need no independent versioning or optional-field
    negotiation. This holds only while nothing in a wire format outlives the build — the moment a snapshot
    or session is persisted and read back after an upgrade, the other peer is last month's build and the
    assumption acquires an exception. **The trigger is the first `writeTo(file)`, not a peer running old
    code.**

Still open: layout-animation path (size/position via `onChange`) first-class vs. transform-layer-only
for v1; group membership static vs. dynamic; choreographer interruption semantics; whether tree
mutations ever share a bus instance with cross-component traffic or always use a private internal topic
(currently: shared bus, private topic name).

**Open, and needs a sibling change — the input topic mixes two loss classes.** `"tactroller.input"`
carries pointer motion (coalesces harmlessly) alongside key, char and button edges (must not be dropped),
under one `DROP_OLDEST` mailbox. Under a stall that sheds the *oldest*, which is the keystrokes, while the
newest motion survives — backwards for half the traffic. Nothing in the GUI can fix it well: `BLOCK`
deadlocks, because the bridge publishes from the frame loop and the pump drains on that same thread, so a
full mailbox has the GUI thread waiting on itself; `COALESCE_LATEST` discards keys outright; and splitting
into two class-filtered mailboxes protects the keys but reorders them against motion, which breaks drag.
Atchung also exposes no dropped count, so a shed edge is undetectable from this side. The fix is the one
§5 already describes — pointer *position* on the coalesced `State<PointerState>`, leaving this channel
carrying only what must not be dropped — and it belongs in `tactroller-atchung`.
