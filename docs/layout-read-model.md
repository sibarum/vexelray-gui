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

---

## 10. Migration

1. **Boxes.** Publish `rect`/`content`/`scroll`/`overflow`/`textSizePx` as `State<LayoutSnapshot>` + `Node.layout()`.
   Unblocks position/size for all widgets immediately. *(this step first)*
2. **Text metrics.** Add `NodeLayout.text()` (line spans + advances as data), computed at publish via the measurer.
3. **Refactor click** onto `node.layout().text().offsetAt(...)`; delete `onCaretHit`/`onCaretDrag`.
4. **Multiline + wrap + Up/Down** as pure widget code on the read-model — the original goal, with no ad-hoc seams.
