# vexelray-gui — TODO

Deferred work, each with enough context to pick up cold. Nothing here is a bug in shipped behaviour; it is work
that was identified while doing something else and correctly left alone at the time.

Ordered within each section by "would I regret not doing this", not by size.

---

## 1. Dispatch conversions

**The rule** (see `vexelray-gui-typeset`, `DispatchGuardTest`): a sealed switch is not allowed, and neither is a
default case that throws. Both are the same failure — behaviour living somewhere other than the type it belongs
to. A switch over a type hierarchy is dispatch written by hand; a `default:` that throws says the type permits a
state the code cannot handle, which means the type is wrong.

The rule was adopted after `-typeset` was designed under it. Everything below predates it. The guard's scope list
is `DispatchGuardTest.RULED`, currently `[GUI_TYPESET]`; **adding a module to that list is the definition of done
for converting it.**

### 1.1 `Length` — sealed, switches on itself twice

`vexelray-gui-core/.../layout/Length.java:20` (sealed), `:95` and `:119` (the switches).

Eleven implementations, and `resolve(ctx, basis)` / `scalarPx(ctx, basis)` each switch over all of them. The
behaviour *is* on the type, just written as a switch instead of as methods, so this is the mildest instance —
but the mildness is what makes it worth converting first. Mechanical, low-risk, and it is the smallest possible
demonstration of the rule on existing code.

**Done looks like:** `resolve` is abstract on `Length`; each record implements it; `Em.resolve` is one line. The
`default -> 0f` at `:122` disappears with the switch. No behaviour change, `MinSizeAndZoomTest` and
`FlexLayoutTest` unchanged and green.

### 1.2 `Mutation` — sealed, dispatched from outside

`vexelray-gui-core/.../model/Mutation.java:11` (sealed), `.../model/Reconciler.java:73` (the switch).

The real instance: `Reconciler.apply` switches over every mutation kind, so the model's single writer holds the
knowledge of what each mutation *means*. Adding a mutation means editing two files that must agree.

Careful here — this one is load-bearing. `Reconciler` is the declared single writer of `RetainedNode`
(`ModelWriterGuardTest`), so moving apply-logic onto the mutations moves *write* logic with it, and the
model-writer guard's `STAGES` list would have to admit them. That is a real widening of who may write the model,
and it is exactly the kind of thing that list exists to make deliberate.

**Two candidate shapes**, and the choice matters more than the conversion:
- `Mutation.applyTo(RetainedNode root, ...)` — direct, but widens `STAGES` to every mutation record.
- A **sink**: `Mutation.emitTo(ModelWriter w)` where `ModelWriter` is the closed set of write operations and
  `Reconciler` is its only implementation. `STAGES` stays exactly as it is, because `Reconciler` remains the only
  thing that touches a field. Same inversion as `Placed.Draw`/`Placed.Sink`.

The sink shape looks right and preserves the existing guarantee. Decide before writing.

### 1.3 `Edit` — sealed, dispatched from `Document`

`vexelray-gui-core/.../text/Edit.java:18` (sealed), `.../text/Document.java:83` (the switch).

`Document.apply(Edit)` switches over the edit kinds. Cleaner than 1.2 because `Document` is immutable and `apply`
returns a new one — no write-guard entanglement. Each `Edit` could carry `apply(Document) -> Document`.

Note the constraint in the existing Javadoc: `apply` runs inside a `State` committer, may run more than once on
CAS retry, and must stay pure. Any conversion inherits that, which suits methods on immutable records fine.

**Done looks like:** `Edit.apply(Document)` per record; `Document.apply(Edit)` becomes one delegation.
`TextEditTest` and `SpanTest` unchanged and green.

### 1.4 `GuiApp:591` — a throwing default on our own value

`default -> throw new IllegalArgumentException("unsupported component count " + components)`.

A switch on an int component count with a throwing default. Not a type switch, but the same smell: the value's
domain is small and known, so it should be a type with the mapping on it rather than an int with an exception for
the cases we did not enumerate.

**Not urgent.** It is in vertex-attribute setup, it is our own data so the throw is unreachable in practice, and
that unreachability is precisely the tell.

### 1.5 `InputEvent` — sealed, and the switch is the documented API

`tactroller/tactroller-api/.../InputEvent.java:26` (sealed, 8 records),
`vexelray-gui-core/.../input/InputDispatcher.java:410` (the switch).

Cross-repo, and **we own both** — `sibarum.tactroller` is ours, not third-party. The `sibarum.*` groupId reads
external; it is not.

The type's own Javadoc tells consumers to switch over it ("Switch over the permitted subtypes to handle events",
with an eight-case example). So this is not a slip — the switch is the intended API, which makes the conversion a
real API change to Tactroller rather than an internal tidy.

**Putting the method on the type does not work here.** `InputEvent.KeyPressed` cannot own hit-testing and focus
logic; Tactroller must not depend on the GUI. The sink is the right shape, defined in `tactroller-api`:

```java
public interface InputSink {                       // one method per event kind, all no-op by default
    default void keyPressed(Key key, long t) {}
    default void pointerMoved(int x, int y, int dx, int dy, long t) {}
    default void charTyped(int codepoint, long t) {}
    ...
}
```

with `InputEvent.emitTo(InputSink)` implemented per record, and `InputEvent` unsealed.

**Note the deliberate difference from `Placed.Sink`** (typeset), which has *no* defaults. Same inversion, tuned
oppositely, for a reason worth keeping straight:

| | Defaults | Why |
|---|---|---|
| `InputSink` — a producer-owned event stream | **no-op defaults** | Most consumers legitimately ignore most events. A new event kind must not break them, and a consumer declares its interest by what it overrides. |
| `Placed.Sink` — an alphabet a consumer must render | **no defaults** | A renderer that silently drops a draw kind is a bug. The compiler should catch it. |

Copying the wrong variant is the easy mistake here.

**Scope, verified:** exactly one dispatch site exists — `InputDispatcher.handle`. Nothing in `vexelray`,
`dasum-gui-shi`, or the demo repos switches on `InputEvent`. So the blast radius is one method, plus the
Tactroller API change and its Javadoc.

**Sequencing:** Tactroller must be built and installed to the local `.m2` before vexelray-gui will compile against
the new API. Do it as one change across both repos, not two.

**Risk:** `InputDispatcher` is the largest class in `-core` (977 lines) and carries the most behavioural tests.
The conversion itself is mechanical — each `case` body becomes an override — but `handle` also sequences the
conduit (`current = conduit.next()`) before dispatching, and that must stay exactly where it is: it runs for
*every* edge, including ones that publish nothing. Easy to lose when the switch becomes eight methods.
`KeyClaimTest`, `WheelScrollTest`, `DragCaptureTest`, `KeyboardFocusTest` and `TextInputTest` are the ones that
would notice.

### 1.6 Mechanise the second half of the rule

`DispatchGuardTest` enforces "no sealed type" by reading the `PermittedSubclasses` attribute — exact and cheap.
It does **not** enforce "no throwing default", which needs walking `tableswitch`/`lookupswitch` default targets to
an `athrow` in the bytecode. Real work, not an attribute read.

Hand-reviewed today. Worth mechanising once there is a second module under `RULED`, not before.

---

## 2. Typeset — obligations already decided

Not open questions; decisions taken in P0 that later phases must honour. Listed so they are not rediscovered.

- ~~No switching on `Extent`, `Align`, `Anchor.Kind` or `Slot` at P2.~~ **Done.** Each carries its own function:
  `Align.offset(content, column)`, `Extent.resolve(natural, available)`, `Anchor.Kind.baseline(...)`, and `Slot`
  answers `leading()` / `above()` / `stacked()` / `sideShift(metrics)`. No switch reads any of them.

  A side effect worth knowing: an enum with constant-specific bodies compiles to a *sealed* class permitting its
  anonymous constant subclasses, so `DispatchGuardTest` flagged the cure. `Sealing` now excludes enums, with a
  test saying so on purpose — penalising a constant body would push authors back to the switch it replaced.
- ~~`Layouts` must not survive P2.~~ **Done** — deleted. Each built-in arranges itself inline, which also makes
  the seven look like what an application would write rather than like something with a framework helper.
- ~~Containment must become an assertion at P2.~~ **Done** — `GeometryTest.everyConstructStaysInsideItsOwnBox`
  checks every draw of every construct on both axes, measured with the atlas the engine used.
- **Hysteresis policy for the tone map's slope, before P6.** `s` depends on the block's extremes, so a live edit
  resizes every glyph. Pick quantisation or a threshold; the demo's zoom control will show the jitter otherwise.
- **A one-line `Gui.rootEmPx()` accessor.** Checked 2026-08-22, and the news is good: `Gui.zoom()` and
  `Gui.dpi()` are already `State<Float>`, so the reactive rebuild trigger a typeset block needs already exists —
  subscribe, re-solve, re-project. Only `rootEmPx` is missing, and it is a private `16f` constant
  (`Gui.java:75`). Expose it rather than the whole `LayoutContext`, which is viewport-dependent and would couple a
  widget to a layout type. If the root em ever becomes settable it can become a `State` like the other two.
  **No longer a blocker; P4 needs the one-liner.**
- **Decide whether the engine memoises `lay`.** It does not today. `arrange` is contractually pure, so the option
  stays open, but a box that iterates — a relaxation pass, a shrink-to-fit — will re-lay the same child many
  times and pay for it every time. Worth measuring before P6 rather than assuming either way.
- **The node projection must emit `Length.dp`, not `Length.em`.** `Placed` is in resolved pixels because the tone
  map's floor is physical, and that basis already includes zoom and DPI. Resolving those coordinates as `em`
  would apply both a second time. This is a P4 trap, written down before anyone falls in it.

---

## 3. Deferred capabilities

Wanted eventually, deliberately not now.

- **A diagonal draw.** `Placed.Sink` has `glyphs` and `bar`; `bar` is axis-aligned and a `Node` in the projection
  is too, so a diagonal line cannot be drawn at all. A commutative-diagram arrow is the first real thing an open
  box set will ask for. Widening `Sink` is the decision, and it reaches the node projection (which would have to
  bypass `Node` for `Canvas`) and any remote consumer.
- **Selection.** Insured, not built: every `Box.Run` carries an app-supplied `sourceRef` and reading order is a
  published guarantee. Adding selection later should be purely additive.
- **Wrapping structured text.** A typeset block is a fixed-aspect atom in v1 — right for equations, wrong for
  prose. Wrapping makes measure two-pass.
- **Prose with inline math.** Flat styled prose belongs on `Span`s (one text node); structured content belongs on
  the box IR (one node per run). A paragraph containing one inline fraction is the awkward case neither path
  serves well. Known, unsolved, deliberately postponed.
- **Soft-knee tone curve.** Slope 1 near the root, compressing harder at the extremes — a photographic S-curve.
  Buys a few more levels of nesting before the size ceiling yields. The linear solve ships first.
- **Global constraint solve across the tree.** Tree-local layout is free under the current SPI (multi-pass within
  a box, fixed-point over a subtree — a force-directed graph is one box). A constraint between a node in one box
  and a node in another needs collect-then-solve-then-place: a different engine shape, not an extension.

---

## 4. Engine-side (lands in `vexelray`, not here)

- **A math face in the primary atlas.** Fully specified in `vexelray/docs/math-face.md`: the `<extraFont>` entry,
  the charset to take and in what priority, the atlas budget, and how to verify. It is a quality upgrade on a
  working feature, not a prerequisite — `-typeset` degrades to upright variables where the U+1D400 block is
  absent, and everything else already renders on today's atlas.
