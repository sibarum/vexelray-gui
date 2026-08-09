# vexelray-gui — Architecture

A GPU-accelerated Java GUI framework that runs **on top of VexelRay** and renders **only** through
VexelRay's native 2D API. First-principles redesign; supersedes the earlier draft (which wrongly
re-implemented a renderer VexelRay already ships).

- **Java 25 · Maven · Panama (FFM) for native bindings · Vulkan via VexelRay**
- Depends on `dev.vexelray:*:0.1.0-SNAPSHOT` (transitively SupirVast `dev.supirvast:vastir`)

> **Status:** design. No framework code yet. This is the target and the record of decisions.

---

## 0. The one rule

**VexelRay is the platform; vexelray-gui is only a framework.** The GUI never touches Vulkan, never
authors a shader, never manages a vertex buffer or a swapchain. It describes *what* to draw and hands
that to VexelRay's `Canvas`; VexelRay decides *how*.

Corollary — **no re-implementation.** If the GUI needs a capability the engine lacks (clipping,
mouse/keyboard events, an idle-blocking loop), that capability is added **in VexelRay** and consumed
here. The GUI owns zero rendering or platform code. Everything below obeys this rule; §3 lists the
few engine gaps it implies.

Why this is the right split: the engine already grew a complete, tested 2D stack (immediate-mode
`Canvas` = batched rounded-box-SDF shapes + MSDF text in one draw, growable vertex buffer, blend,
offscreen/sampled targets; `TextLayout` = wrap/align/measure/fit). Re-deriving any of it in the GUI
would fork the shader, the atlas, and the vertex format for no benefit. The GUI's value is entirely
*above* the pixel: identity, layout, events, motion.

---

## 1. What VexelRay provides today (ground truth)

Verified against the current engine — this is the real substrate, not the stale prior survey.

**2D drawing — `vexelray-canvas`.** `Canvas(w,h)` immediate-mode: `fillRect`, `fillRoundRect`,
`fillCircle`, `strokeLine`, `text(...)` (via `TextLayout`), `Color`. Everything batches into one fat
vertex stream drawn by one uber-shader that branches per primitive (analytic rounded-box SDF for
shapes, MSDF median + per-vertex `screenPxRange` for glyphs). Per-vertex colour + AA range → mixed
sizes/colours in one draw. Submission order = paint order. `toVertexArray()` / `vertexCount()` feed a
vertex buffer.

**Text — `vexelray-text`.** `AtlasData` (msdf-atlas-gen JSON, baked by `vexelray-msdf-maven-plugin`),
`GlyphLayout`, and `TextLayout`: line breaking + wrapping (word/char), H/V alignment (incl. justify),
`measure` → bounds, and fit queries (`fits`, `largestSizeThatFits`, `fitAndPlace`), anchor placement.

**Runtime — `vexelray-vulkan`.** `VulkanInstance → VulkanDevice → VulkanSwapchain → VulkanRenderPass →
GraphicsPipeline → WindowedPresenter`. `GraphicsPipeline.Config` (vertex input, descriptor sets,
blend, push stages). `WindowedPresenter` drives a **per-frame callback** with a **dynamic vertex
buffer** (`VertexBuffer.update`, `setVertexCount`) at one frame in flight — i.e. rebuild-and-present
each frame is a solved path (`DynamicCanvasDemo` proves it). `AtlasTexture`, `OffscreenDraw`,
`SampledColorTarget` (render 2D into a sampled image), and `VulkanRenderPass` final layouts
(present / transfer-src / shader-read-only).

**OS — `vexelray-os`.** `NativePlatform.current()` (ServiceLoader), `NativeWindow`
(`createWindow`, `pumpEvents`, `createVulkanSurface`, `width/height`, resize→swapchain recreate),
`isKeyDown` over a 12-key enum. **Input is keyboard-poll only** — no mouse, wheel, char/IME,
modifiers, focus, or blocking event wait.

---

## 2. Requirements (unchanged goals)

1. One language for layout definition **and** updates. 2. Message passing; app logic off the main
thread; GUI owns the main thread. 3. Animations bound to a visibility lifecycle; per-node and
per-group. 4. Flexbox essentials. 5. `em/rem/vw/vh`. 6. Rich renderer effects — *deferred* (engine
seam). 7. Native file dialog. 8. MSDF fonts via VexelRay atlases. 9. Dynamic frames; lower FPS
unfocused. 10. Panama for native bindings. 11. Keyboard, mouse, full shortcuts. 12. Rich-text spans
that auto-adjust to edits.

---

## 3. Engine prerequisites (work lands in VexelRay, not here)

The GUI cannot ship without these; each is a small, general addition to the engine, useful beyond the
GUI. Tracked as VexelRay tasks and consumed here as native API.

| # | Gap | Add to | Shape |
|---|-----|--------|-------|
| E1 | **Input events** — mouse move/button/wheel, char/IME, modifiers, focus/activate, close, resize | `vexelray-os` | An event-callback path on `NativeWindow` (Win32 `wndProc`), a platform-agnostic `InputEvent` sum type, wider key set. Supersedes poll-only. |
| E2 | **Idle-blocking loop** — `waitEvents(timeoutMillis)` | `vexelray-os` | Block at 0% CPU until an OS message or timeout (`MsgWaitForMultipleObjectsEx`). Lets the GUI wake exactly on input or an animation deadline. |
| E3 | **Canvas clip rectangle** | `vexelray-canvas` | A clip-rect stack (`pushClip/popClip`); the current clip is stamped per-vertex into the fat vertex and the fragment zeroes coverage outside it. Stays single-draw — no scissor state, no multi-draw. Needed for scroll/overflow. |

Nothing else about rendering, windowing, or input is the GUI's job. Effects (shadow/glow) are the
engine's deferred `material-flags` seam (req 6), not ours.

> These are **decisions to confirm**: adding input + wait-loop to `vexelray-os` and clip to
> `vexelray-canvas` is the concrete meaning of "don't re-implement." If instead the GUI should carry
> a thin OS/clip shim, that's a different split — see §12.

---

## 4. Core model — retained tree, mutated by messages

**A retained node tree with client-stable identity, mutated exclusively through an MPSC message queue
drained on the GUI thread.** This is the load-bearing decision; it's kept from the prior design
because it falls directly out of requirements 1 + 2, not out of taste.

**First-principles: why retained + messages (alternatives rejected).**
- *Immediate-mode (Dear ImGui style)* — the app re-emits the whole UI every frame. Rejected: it puts
  app logic **on** the render thread (violates req 2), has no stable identity to hang lifecycle
  animation or FLIP motion on (req 3), and re-evaluates everything every frame (fights req 9's
  idle-when-quiet).
- *Retained tree mutated directly on a shared, locked model* — rejected: forces locks on the tree and
  a "safe-to-call-off-thread" grey zone (the exact mess this design exists to avoid).
- *Retained tree + message queue* — accepted: construction and updates are the **same** vocabulary
  (req 1: a `Create` at frame 0 is just the first mutation), the queue is the thread boundary (req 2),
  and stable ids give lifecycle + motion something to attach to (req 3).

```
Node (handle)            RetainedNode (model)
 held by app, any thread  GUI thread only
 immutable identity       the live, mutable tree
 long id + MutationSink    props, children, layout cache, input/anim state
 setters ENQUEUE          mutated only by the reconciler
```

Ids are client-assigned (`AtomicLong`), stable for life — editing a node never mints a new identity,
so attached state never orphans. Mutations: `Create · Insert · Remove · SetProp · SetText ·
SetRichText · Show · Hide · Batch`. `batch(...)` posts one atomic group no frame boundary can split.
Within a drain, `SetProp(id,key,…)` coalesces last-write-wins; structural ops never coalesce.

**Reads flow out as events, never as tree access.** Workers subscribe to immutable event snapshots
(`gui.on(slider, ValueChanged.class, …)`); the app holds its own state and the GUI is a projection of
it. Unidirectional, Elm-shaped.

---

## 5. Threading — *decision to confirm*

The prior design mandated worker-threaded handlers and an MPSC queue from day one. First-principles,
the **model** (single-writer tree, mutations in, events out) is right, but *forcing* multi-threading
in v1 is optional complexity:

- **Recommended for v1:** build the single-writer + queue seam, but run handlers on the GUI thread by
  default with an explicit `gui.async(...)` opt-out. The queue/event API is identical whether the
  producer is a worker or the GUI thread, so nothing is thrown away — we just don't *require* an
  executor and cross-thread wake CAS to get the first widgets on screen. Threading hardens later
  behind the same API.
- **Alternative:** full worker-thread model immediately (per the old doc).

Either way the contract is one-way: workers enqueue mutations, read events; only the GUI thread
mutates the tree.

---

## 6. Layout + units

Flex essentials: row/column, wrap, grow/**shrink**/**basis**, justify + align, position
relative/absolute. **One `measure(axis, availPx)` per node** (kills the duplicated per-variant
switches of prior toolkits). Units as a sum type resolved only at layout time against a
`LayoutContext{ rootEmPx, zoom, dpi, viewportW/H }`:

```java
sealed interface Length permits Em, Rem, Vw, Vh, Px, Fraction /*grow*/, Auto, Fill { }
```

`em = v·rootEmPx·zoom·dpi`; `rem` = flat root (no cascade); `vw/vh = v/100·viewport`. Layout runs only
when structure or a layout-affecting prop changed (`layoutDirty`), never per animation frame (§7).

---

## 7. Animation — a visual-transform layer, GUI-side

Opt-in per node; nothing fires unless an animation is attached. Animatable properties are a
**post-layout visual transform** — `OPACITY, TRANSLATE_X/Y, SCALE, ROTATION, TINT` — **not** layout
inputs. Animating them never re-runs layout; it only changes how the tree is emitted to the `Canvas`.

**This needs zero engine support:** because we draw immediate-mode, the emitter simply applies the
current transform when it makes `Canvas` calls (offset/scale positions, multiply into `Color.a`). The
visibility FSM (∅→MOUNTING→VISIBLE↔HIDDEN→UNMOUNTING→DESTROYED) reacts to mutations; `onExit` defers
destruction (the node keeps drawing at its last rect, excluded from flex, until its timeline settles).
`Animated<T>` smooth-interrupt + a next-deadline query drive the wait-loop (§10). Global
`gui.motion(DISABLED)` collapses every timeline to its end-state → loop stays 0 idle frames (also the
reduced-motion path). Built-in defaults ≤100ms; long motion is always app-authored.

Semantic-transaction choreography (FLIP diff on stable ids, hero/`moveTo` ghosts on an overlay layer)
is a **later** high-level layer built on the diff + transform + overlay; not v1.

---

## 8. Input dispatch — framework-owned, over engine events

Consumes E1 events. The framework does hit-testing (against the laid-out tree), capture/focus routing,
and leaf→root bubbling with consume semantics — no app-owned dispatch chain. `Shortcut(mods, key)` →
a command registry (focus-scoped + global), dispatched before text input claims the key. Emits
`ValueChanged/Click/FocusEvent/...` onto the event queue for workers.

---

## 9. Text + RichText

Reuse `vexelray-text` wholesale (atlas, `GlyphLayout`, `TextLayout`, MSDF via `Canvas.text`). Add a
`RichText` model on the node: an immutable rope of runs `{fg, bg/highlight, weight, outline}` with
stable offsets so spans auto-adjust across edits (req 12); each edit publishes a new snapshot
atomically. Weight/outline map to the MSDF edge-shift the engine already exposes; a highlight is just
a background `fillRoundRect` emitted in the same `Canvas` before the glyphs.

---

## 10. The frame loop (all VexelRay-native)

The GUI owns the main thread and runs VexelRay's dynamic present path — the same one
`DynamicCanvasDemo` uses — with an idle-blocking wait (E2):

```
GUI thread, per dirty/animating frame:
  1. block in waitEvents(timeout = animating ? nextDeadline : ∞)   // E2, 0% CPU idle
  2. push OS input events (E1) -> dispatch (§8) -> app events
  3. batch = mutationQueue.drainAll(); apply each to the tree (single writer)
        structural/layout prop -> layoutDirty;  Remove w/ onExit -> lifecycle;  animatable -> animation
  4. lifecycle.tick(now); animation.tick(now)
  5. if layoutDirty: layout(root, viewport)                        // one measure pass
  6. canvas.begin(); emit(tree, canvas)  // walk, apply transforms(§7) + clip(E3), fill*/text
  7. vertexBuffer.update(canvas.toVertexArray()); presenter.setVertexCount(...)   // native
  8. present                                                        // WindowedPresenter
```

Render-on-demand: with no animations and no input/mutations, the loop blocks in step 1 — zero frames.
**Focus-aware cap** (req 9): unfocused clamps the wake cadence (e.g. 1/10s) — implemented via the
step-1 timeout, no busy loop. Steps 6–8 are pure `Canvas` + native presenter; the GUI writes no Vulkan.

---

## 11. Modules

Deliberately slim — the engine carries render/OS/text, so the framework is nearly one module:

```
vexelray-gui        the framework: model (Node/Mutation/Event), layout+units, animation+lifecycle,
                    input dispatch, RichText, widgets, the app loop (GuiApp).   -> vexelray-canvas,
                    -text, -vulkan, -os
vexelray-gui-demo   canonical showcase app.                                     -> vexelray-gui
```

Packages inside `vexelray-gui`: `model · layout · anim · input · text · widget · app`. Split into
sub-modules only if one grows a hard dependency boundary. **File dialog (req 7)** is a native/platform
concern → proposed as a VexelRay addition (`vexelray-os` NFD facade) consumed here, consistent with §0;
if you'd rather it live in the GUI, it's a small `vexelray-gui-nfd` module instead (open question).

---

## 12. Build sequence

Engine first (the prerequisites), then the framework bottom-up:

1. **E3** Canvas clip-rect (smallest, unblocks scroll/overflow) — in VexelRay.
2. **E1** input events + wider keys in `vexelray-os` (the biggest engine gap).
3. **E2** `waitEvents(timeout)` idle-blocking — in `vexelray-os`.
4. `GuiApp` + frame loop (§10): a hard-coded tree drawn via Canvas, presenting on demand. First pixels.
5. `Node`/`RetainedNode` + mutation queue + event queue + reconciler (§4).
6. `Length` + flex layout + `measure` (§6); the tree→Canvas emitter (§10 step 6).
7. Input dispatch + focus + shortcuts (§8); first interactive widgets (button, slider).
8. Lifecycle FSM + animation transform layer (§7); `RichText` (§9).
9. Scroll/clip views, text field; then (later) semantic-transaction choreography; file dialog.

---

## 13. Decisions to confirm

1. **Engine prerequisites (§3):** add input events + `waitEvents` to `vexelray-os` and a clip-rect to
   `vexelray-canvas`, rather than any shim in the GUI. (This is the crux of "use the native API.")
2. **Threading (§5):** single-writer + queue seam now, but GUI-thread handlers by default with
   `async` opt-out — vs. full worker-thread model immediately.
3. **Module shape (§11):** one `vexelray-gui` module + demo (file dialog folded into VexelRay-os) —
   vs. splitting core/widgets and/or a `-gui-nfd` module.
4. **Retained-tree + MPSC model (§4):** confirmed kept (justified from reqs), or reconsider.
```
