# The Layout Read-Model — computed GUI state, published on the bus

Status: **design → in progress**. This is the framework's standing pattern for the question "how does a
widget (or a test, a devtools overlay, a remote client) learn a node's computed layout — its position, size,
scroll, and text metrics — that only core knows after it lays the tree out?" It generalizes the pattern already
used for the window size (`Gui.viewport()` is a computed value published as a coalesced bus `State`).

Whenever a future scenario needs computed state to flow *from* core *to* a widget/observer, it uses this
pattern. Do not add ad-hoc per-feature callbacks for it.

---

## 1. The problem it solves

Worker threads build and mutate the retained tree through **write-only** `Node` handles: every setter posts a
`Mutation` onto the bus, drained on the GUI thread and applied to the single-writer `RetainedNode` model
(architecture.md §3–5). That handle deliberately exposes **no reads** of computed layout — so a widget on a
worker thread cannot see its own laid-out `x/y/w/h`, scroll offset, or (for text) caret geometry.

That gap forced ad-hoc seams: click-to-caret had to be computed inside the `InputDispatcher` (which *does* have
the tree + the measurer) and pushed to the widget as a bare offset; arrow-key caret motion would have needed a
second such seam; every future geometry-aware widget (scrollbars, resizers, popovers, drag handles, virtualized
lists) would need yet another. The friction is real and it multiplies.

The write-only rule was never "widgets can't read." It was **"widgets can't do *unordered* writes"** — the
single-writer guarantee that keeps the model from corrupting. Reads of computed geometry are an orthogonal
concern and can be served without touching that guarantee.

---

## 2. The pattern: Retained CQRS on the Atchung bus

Two flows, one fabric, separated by direction:

- **Command flow (writes) — exists, unchanged.** `Node` setter → `Mutation` on the bus → GUI thread applies to
  `RetainedNode` (single writer, lossless, ordered).
- **Read-model flow (computed) — new.** After reconcile + layout each frame, core publishes an **immutable,
  versioned `LayoutSnapshot` of the whole tree** onto the bus as a coalesced `State<LayoutSnapshot>` — the same
  mechanism as `viewport()`. Latest-wins, versioned, observable, lock-free.

```
 worker →  Node.set(...)  ─mutation▶  bus  ─drain▶  RetainedNode (single writer)  ─layout▶  geometry
                                                                                                │
 worker ◀── Node.layout() ◀─snapshot─  bus  ◀─commit─  LayoutSnapshot  ◀──────────────publish──┘
```

This is command/query separation: commands go up, a computed read-model comes down, both as bus traffic.

### 2.1 Three tiers, one writer each

Command/query separation answers *who may write the model*. It does not by itself say *who **computes** derived
values* — and that gap is where every ad-hoc seam has appeared: caret-follow scroll wrote from the renderer, and
the first draft of §11.3 merely moved that write into publish. Naming the missing tier closes the gap:

| Tier | Contents | Written by | Phase |
|---|---|---|---|
| **Authored props** | text, caret, selection, editable, **scroll intent** | worker → `Mutation` → `pump.drain()` | command |
| **Derived geometry** | `rect`, `content`, `contentW/H`, `overflow`, **effective scroll**, **text metrics**, gutter width | the layout pass | compute |
| **Read-model** | `LayoutSnapshot` | `publishLayout` — a pure copy | publish |

**Compute in the layout phase, copy in the publish phase, read everywhere else.** Renderers and widgets never
write; `publishLayout` never computes.

This adds no new concept. `FlexLayout.layout()` already writes `x/y/w/h`, `viewX/viewY`, `contentW/H` and
`overflow` into `RetainedNode` — on the GUI thread, in the write phase, and nobody calls that a violation.
"Derived geometry" is simply that category, named. Anything that is a pure function of (authored props +
viewport + measurer) belongs to it, so "where does this new computed value go?" has a mechanical answer instead
of a judgement call.

### 2.2 Scroll is a staged pipeline value, not a second model

Scroll looks like it has two owners — the user (wheel, scrollbar drag) and the caret (follow-to-keep-in-view) —
which is what made it feel homeless. It doesn't. Both writers run **on the GUI thread, in a fixed phase order**,
and the model already works this way:

```
 dispatch ─proposes─▶ scrollX ─constrains─▶ scrollX ─copies─▶ snapshot ─reads─▶ renderer
 (InputDispatcher)              (layout/compute)              (publish)
```

`InputDispatcher` writes the offset on a wheel or scrollbar drag; `FlexLayout` then clamps it to the content and
applies scroll-lock. One field, written by successive stages, each narrowing the last — no concurrency, no
ambiguity. Caret-follow is simply another constraint belonging to the compute stage, and the renderer's only
error was acting as a stage at all.

So there is **no intent/effective split and no feedback `Mutation`**: an earlier draft of this section proposed
both, before it was clear that `FlexLayout` already clamps in place. A second field and a convergence argument
would buy nothing over the pipeline the code already has. The rule is just: *scroll is written by the dispatch
and compute stages, in that order, and by nobody else.*

### 2.3 `layoutDirty` splits in two

Scroll, caret and selection changes must re-run compute + publish **without** re-running flex; text, size and
structure changes must re-run both. The reconciler therefore carries two flags: `geometryDirty` (recompute
derived geometry, republish) and `layoutDirty` (relayout, which implies `geometryDirty`). This makes "static
frames allocate nothing" finer-grained rather than coarser — a caret move republishes geometry without a
relayout, and a genuinely static frame still does neither.

---

## 3. What a widget sees

```java
NodeLayout L = node.layout();    // synchronous, lock-free: this node's entry in the latest snapshot
L.present();                     // false until the node has been laid out at least once
L.rect();                        // x,y,w,h in root space
L.content();                     // the inner viewport (border/padding/scrollbar inset)
L.scroll(); L.overflow(); L.contentSize();
```

`node.layout()` reads a `volatile` reference to an immutable snapshot — **no lock, no poll into the live model**.
It is **one frame stale**, which is exactly the latency the input dispatcher already embraces on purpose
(hit-testing uses the previous frame's rects; "invisible in practice", InputDispatcher). Widgets may also
`gui.bus().subscribe(gui.layout(), ...)` to react to layout changes instead of reading on demand.

**Discipline:** `Node` stays write-only *for mutations*. `layout()` is a read of a *published snapshot*, never a
live reach into `RetainedNode`. The single-writer model is untouched.

---

## 4. Text is not a special case — it is a richer `NodeLayout`

For text nodes the snapshot entry additionally carries **text metrics as data** and answers caret queries as
**pure functions of that data**:

```java
TextMetrics T = node.layout().text();     // present only for text nodes
T.offsetAt(localX, localY);               // point → caret offset   (what click needs)
T.caretPoint(offset);                     // offset → (x,y)         (drawing / desired-column)
T.offsetAbove(offset, desiredX);          // vertical navigation
T.offsetBelow(offset, desiredX);
```

Crucially, the metrics (visual line spans, line boxes, and the per-line advance data needed to map x ↔ offset)
are **computed once at publish time on the core side**, using the application-supplied measurer/atlas, and
shipped in the snapshot as plain data. The query methods above are pure lookups over that data. Therefore:

- The widget does its **own** caret math and owns its **own** sticky desired-column — but never sees the atlas
  or the measurer.
- The same code path works for a **remote** consumer that has no atlas at all (see §6).

For very large documents, metrics are computed for the visible range only; the data model already supports a
partial snapshot.

---

## 5. What this deletes

- The click-to-caret seam (`onCaretHit` / `onCaretDrag` / the dispatcher's `placeCaret`) — gone. The dispatcher
  returns to delivering **raw** events (pointer down/move/up, keys, chars) to the hit/focused node; the widget
  maps points itself via `node.layout().text().offsetAt(...)`.
- The arrow-key offset seam — never built. Up/Down/Home/End become ordinary widget code over the text metrics.

One pattern replaces both ad-hoc workarounds, and it is the pattern for every future geometry-aware widget.

---

## 6. Transport extensibility (a first-class requirement)

Because both flows are bus traffic and Atchung makes a subscriber on another thread, process, or machine
indistinguishable from a local one (architecture.md §5), the read-model must be **transport-agnostic**:

- `LayoutSnapshot` / `NodeLayout` / `TextMetrics` are **pure immutable data** — no references to `RetainedNode`,
  no captured measurer, no behavior that needs the atlas at read time. They serialize over any codec (in-process
  hand-off, shared memory, JSON/CBOR/protobuf over a socket).
- All measurement happens **once, at publish, on the sender (core) side**; results travel as data. A receiver —
  local widget *or* remote thin client — does pure lookups.
- The `version` field gives transports a natural basis for **delta encoding** and for detecting dropped/coalesced
  frames later.

With commands already bus-native and the read-model now bus-native and serializable, a **remote GUI** (thin client
renders snapshots, sends input as bus events) is a transport away — no change to widget or core code.

---

## 7. Versioning & consistency

Snapshots carry a monotonically increasing frame `version`. Mutations are ordered at write time; a widget can
tell whether its edit is reflected yet (the snapshot whose `version` corresponds to the frame that applied it),
and in the rare case it matters, wait for it rather than guessing. Coalescing is safe: only the latest snapshot
matters for reads, so dropping intermediate ones (a slow/remote consumer) never corrupts state.

---

## 8. Concrete shape

```
record Rect(float x, float y, float w, float h)
record NodeLayout(boolean present, Rect rect, Rect content, float scrollX, float scrollY,
                  float contentW, float contentH, boolean overflowX, boolean overflowY,
                  float textSizePx, TextMetrics text /* nullable */)
record LayoutSnapshot(long version, Map<Long,NodeLayout> nodes) { NodeLayout node(long id); }

Gui.layout()   -> State<LayoutSnapshot>   // the bus read-model (mirrors viewport())
Node.layout()  -> NodeLayout              // convenience: latest snapshot .node(this.id)
```

Core publishes right after `FlexLayout.layout(...)` in `Gui.frame`, **only when layout actually ran** (dirty or
viewport change), so the coalesced `State` commits on change and static frames produce no garbage. Text metrics
share what `TreeRenderer` already computes for drawing.

---

## 9. Costs & rules

- **Allocation:** one immutable snapshot per *changed* frame; reuse unchanged node entries (structural sharing)
  if profiling demands it.
- **Text metrics:** lazy / visible-range for large documents.
- **Staleness is a contract, not a bug:** `layout()` is explicitly last-frame; the `version` lets a widget detect
  it.
- **Never** leak a live `RetainedNode` or a measurer into the snapshot — that breaks both the single-writer model
  and transport.
- **Never** compute in `publishLayout`, and never write the model from a renderer or a widget (§2.1). If a value
  needs computing it belongs to the compute phase; arithmetic appearing in publish is arithmetic in the wrong
  phase. This is the rule that would have caught the caret-follow scroll seam before it was written twice.

---

## 10. Migration

1. **Boxes.** Publish `rect`/`content`/`scroll`/`overflow`/`textSizePx` as `State<LayoutSnapshot>` + `Node.layout()`.
   Unblocks position/size for all widgets immediately. *(this step first)*
2. **Text metrics.** Add `NodeLayout.text()` (line spans + advances as data), computed at publish via the measurer.
3. **Refactor click** onto `node.layout().text().offsetAt(...)`; delete `onCaretHit`/`onCaretDrag`.
4. **Multiline + wrap + Up/Down** as pure widget code on the read-model — the original goal, *landed*, with no
   ad-hoc seams: the only thing added to core was `TextMeasurer.lineSpans`, and `TextField` does its own caret
   arithmetic over `node.layout().text()` without ever seeing a measurer. Preceded by **4·0, the compute phase**
   (§2.1–2.3): `Gui.resolveGeometry` owns all derived geometry, `publishLayout` is a pure copy,
   `TreeRenderer.updateHScroll` is gone, and `geometryDirty` lets a caret move republish without a reflow.
   See §11 for the build spec and what remains (4b line numbers, the label draw path).

---

## 11. Step 4 build spec — multiline + word wrap + line numbers

Status: **4a landed** (steps 1–3, **4·0** and **4a**: read-model boxes, text metrics, click via `onDrag`, the
compute phase, and multiline + word wrap + vertical navigation). **4b (line numbers) remains**, plus the label
draw path in §11.4. This is
the execution plan so a fresh session needs no re-derivation. Build order is **4·0 (the compute phase — §2.1–2.3:
`resolveGeometry`, scroll intent/effective split, `geometryDirty`) → 4a (multiline + wrap) → 4b (line numbers)**.
4·0 came first because it was separately provable: `CaretScrollTest` was red before it and green after, with no
multiline code involved. Font selection stays parked behind the multi-atlas engine work
([[project-font-atlas-registry]] / keyboard-focus-text.md §5).

### 11.1 Model (core)
- New props (all layout-affecting): `MULTILINE`, `WORD_WRAP`. (`LINE_NUMBERS` in 4b.) Add `Node` setters +
  `RetainedNode` accessors, mirroring the existing text props.
- Single-line stays the default; a `TextField` opts into multiline.

### 11.2 The one new seam: line breaking as a measurer query
`TreeRenderer` and the read-model must agree on line breaks, so both go through the measurer (which already owns
the atlas). Add to `TextMeasurer`:
```java
default java.util.List<dev.vexelray.text.TextLayout.LineSpan> lineSpans(String text, float wrapWidth, float px) {
    return List.of(new TextLayout.LineSpan(0, text == null ? 0 : text.length(), true)); // one line by default
}
```
- `GuiApp` implements it via `textLayout.breakLineSpans(text, px, wrapWidth, WrapMode.WORD_CHAR)` (built in the
  `393b276` commit). `wrapWidth <= 0` ⇒ no wrap (split on `\n` only).
- `HeadlessGui` stub implements a monospace version (split on `\n`, wrap by `floor(width / CELL)`), so multiline
  is testable headless.

### 11.3 Multi-line `TextMetrics` in the compute phase (core) — the crux
Derived geometry gets its own pass: `resolveGeometry(root, tm)`, run immediately after `FlexLayout.layout(...)`
in `Gui.frame`, writing effective scroll and `n.textMetrics` onto the retained tree (§2.1). `collectLayout` then
becomes a pure copy — it reads `n.textMetrics` instead of calling `Gui.textMetrics(n, tm)`.

Within that pass, rewrite the metrics builder (currently single-line) to emit one `VisualLine` per `lineSpans`
entry. Sequence per text node:
1. `pad = min(TextMetrics.PAD_X, w*0.25)`; `gutter = 0` (4a) or line-number width (4b); `contentLeft = x + pad +
   gutter`; `viewW = w - 2*pad - gutter`; `viewH = h - 2*pad_v` (top-aligned for multiline).
2. `wrapWidth = wordWrap ? viewW : 0`; `lines = tm.lineSpans(text, wrapWidth, px)`; `adv = tm.caretAdvances(text, px)`
   (whole-string cumulative); `lineH = tm.intrinsic(n, VERTICAL, px)`.
3. Find the caret's visual line (`last line with start ≤ caret`) and its x (`contentLeft + adv[caret] -
   adv[line.start]`).
4. **Constrain scroll** (§2.2) — narrow whatever the dispatch stage left on the node, then clamp:
   - vertical (multiline): keep caret line in `[0, viewH)` → clamps so `caretLineIndex*lineH` is visible.
   - horizontal: only when **not** wrapping → keep caret x in `[0, viewW)`; when wrapping, `scrollX = 0`.
   - Once multiline makes a text node genuinely wheel-scrollable, follow must run **on caret change only**
     (`geometryDirty`, §2.3), or the view snaps back to the caret the instant the user wheels away from it,
     which no editor does. Clamping runs always. This is deferred to 4a rather than built in 4·0: a single-line
     field has no other scroll source, so unconditional follow and follow-on-caret-change are observationally
     identical today, and the refinement would ship untestable.
5. Bake absolute geometry: for line *i*, `top = contentTop + i*lineH - scrollY`; `xs[j] = contentLeft +
   (adv[start+j] - adv[start]) - scrollX`. Single-line keeps its centered `top` (unchanged behavior).
6. Emit `TextMetrics(lines)` onto `RetainedNode.textMetrics` (new transient field); publish then *copies* it into
   the snapshot (§2.1). The multi-line query methods (`offsetAt`, `offsetAbove/Below`, `lineStart/End`) already
   exist on `TextMetrics`.

### 11.4 Renderer unification (core) — one source of truth
**Done for editable fields** (`TreeRenderer.drawField`), which now measure nothing: every glyph run, selection
rect, underline and the caret are placed from `n.textMetrics`, so the renderer cannot disagree with the widget
about what sits under the pointer, and multi-line needs no separate drawing path at all.
- Each `VisualLine`'s runs draw at their baked `caretX`, `WrapMode.NONE`, `HAlign.LEFT`/`VAlign.TOP`, in a box
  exactly `line.height()` tall — the canvas does no alignment or wrapping of its own.
- Spans / selection / caret use the line `xs` (already absolute), clipped per line by `fillLineRange`.

**Not done — labels.** A non-editable text node still draws through `canvas.text` with its own `hAlign`/`vAlign`
and `WrapMode.WORD_CHAR`. Its metrics *are* published (and now carry real wrapped line spans), but they assume a
left/top origin, so a centred label's published `xs` do not describe where it is drawn. Unifying it needs per-line
alignment offsets baked into `TextMetrics` — worth doing, and a prerequisite for M4 reconstructing a label
remotely, but out of scope for 4a because it changes how every existing label renders and wants a visual check.
- ~~**Delete `updateHScroll` from the renderer**~~ — **done in 4·0.** Scroll is resolved in the compute phase
  (`Gui.resolveTextGeometry`), so the renderer is a pure consumer and no longer mutates model state.

  This was not a tidiness change. `TreeRenderer.emit` is called only from `GuiApp`, so caret-follow scroll had
  existed *only when a Vulkan renderer was attached* — a headless host or a remote client ran a field that never
  scrolled. `CaretScrollTest` pinned two consequences, both red before the move and green after:
  - a field with 20 chars in an 80px box reported its caret at x=210, outside its own box;
  - clicking at x=40 in that field returned offset **3** where the pointer was over offset **17** — the published
    `xs` were baked with scroll S₀ while the renderer drew at S₁, and with layout clean it never republished.

  The second was a live click-to-caret bug in any horizontally scrolled field, not just a headless artifact.
  Both are the C1 claim of `architecture-proof-plan.md` ("widget code unchanged across hosts") made concrete, so
  they must stay green through M4.
- Keep the content-box `pushClip`; add the vertical clip for multiline.

### 11.5 Widget (TextField)
- `multiline(boolean)`: when true, `Enter` inserts `\n` (via `applyEdit`) instead of `onSubmit`.
- Vertical nav via the read-model + a **widget-owned sticky desired-column**:
  - On Up/Down: `m = node.layout().text()`; `caret = m.offsetAbove/Below(caret, desiredX)`; set `desiredX` from
    `m.caretX(caret)` only on horizontal moves (typing, Left/Right, click), not on Up/Down, so it stays sticky.
  - Home/End become **visual**-line start/end via `m.lineStart/lineEnd(caret)` (falls back to string ends when
    metrics absent).
  - PageUp/PageDown: move by `floor(viewH / lineH)` lines (viewH from `node.layout().content()`).
- Click/drag already works unchanged (2D `offsetAt` is multi-line-ready).
- Selection rendering across lines is handled by 11.4.

### 11.6 Line numbers (4b)
- `LINE_NUMBERS` prop. Gutter width = `digits(hardLineCount+1) * digitWidth + padding`, `digitWidth =
  caretAdvances("0", px)[1]`. `hardLineCount` counts `\n` (numbers are per hard line, not per wrapped line).
- Gutter shifts `contentLeft` (11.3 step 1) and narrows `wrapWidth`. `TreeRenderer` draws right-aligned numbers
  in the gutter; wrapped continuation lines get no number.

### 11.7 Tests (headless harness — deterministic)
- **4·0 (landed, green):** `CaretScrollTest` — the caret stays inside an overflowing field; a click in a scrolled
  field hits the character under the pointer; a caret move republishes geometry with no reflow. Plus
  `LayoutReadModelTest.resizingACleanTreeRepublishesTheSnapshot` / `staticFramePublishesNothing`, which pin the
  two edges of the `geometryDirty` condition — a resize has no dirty mutation to ride on, and a static frame must
  still publish nothing.
- **4a:** wheel-scrolling away from the caret is *not* undone on the next frame, but *is* on the next caret move
  (the follow-on-caret-change rule, 11.3 step 4) — writable once multiline gives a text node a second scroll
  source.
- **4a (landed, green):** `MultilineTest` — 10 cases covering Enter (inserts in multiline, still submits in
  single-line), Up/Down between lines, the sticky desired column across a short line *and* its reset on a
  horizontal move, wrap producing >1 `VisualLine` with offset↔point round-trips across the boundary, visual
  Home/End under wrap, click landing on the pointed-at line, selection spanning lines, and vertical
  caret-follow scroll in a document taller than its field.
- **Note for future multiline tests:** a newline cannot be *typed*. `'\n'` is a control character, so it rides
  the key channel and `TextField.onCodePoint` filters it out of `CharTyped` — build multi-line text with
  `Enter`, as a real keyboard does (`MultilineTest.typeLines`).
- **Still to do:** a `--capture` visual check of a multiline field (+ gutter in 4b). 4a's renderer change is the
  one part no headless test covers: the editable path now positions each line itself rather than letting the
  canvas centre it, so single-line fields should look identical only if `intrinsic(VERTICAL)` matches the block
  height the canvas would have used.

### 11.8 Open decisions (resolve in implementation)
- **Tab in multiline:** soft tabs (N spaces) first per keyboard-focus-text.md §4.2; focus-traversal vs. insert is
  the modifier question there.
- **Auto-grow vs. fixed height + scroll:** spec assumes app-set height + vertical scroll (simplest, matches
  read-model). Auto-grow (intrinsic height = lineCount·lineH) is a later option and reintroduces the
  intrinsic-wrap-height coupling — defer.
- **Large documents:** `caretAdvances`/`lineSpans` over the whole string is O(n) per changed frame; fine for
  fields/small editors. Visible-range windowing is a later optimization (note in §9 already).
