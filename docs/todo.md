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
- ~~A style context must exist by P3 to carry cramped and display style.~~ **Done, by finding there is no
  context.** Display/inline is a choice of recipe (`Recipes.limits`); cramped is `Arrangement.headroom()` /
  `footroom()`, derived from the gaps a container already reserves. docs/typeset.md §5 is rewritten around it.
- **A nested attach can exceed its allowance by up to twice the reserved gap.** Known, bounded, and left alone.
  `Attach` clamps a side satellite against its *core's measured* ascent, and passes its room down to the nucleus
  — so in `a / b²ᶜ`, where the denominator's nucleus is itself an attach, the inner script may consume the room
  and the outer clamp then allows the same room again above the result. Satellites do not have this problem: the
  clamp reads their measured ascent, so anything they raised internally is already counted. An exact fix needs
  the nucleus's *unclamped* ascent as well as its actual one — two lays, or a second field on `Placed` — and
  neither is worth it for the growth of one gap in a construct this rare. Revisit only if a real document shows it.
- ~~**Hysteresis policy for the tone map's slope, before P6.**~~ **Closed — the policy is that there is none,
  because the solve is already stable.** Four properties, each a test in `ToneMapTest`, written up in
  docs/typeset.md §4.3.1: the slope does not depend on the pixel basis at all (so zoom cannot make it shimmer,
  and P6's gate was watching an axis where jitter is not expressible); it is already a five-valued staircase over
  thirteen depths, so there is nothing left to quantise; while it is compressing the block sits exactly at the
  ceiling, so added nesting redistributes the interior and does not move the block; and it is Lipschitz in the
  block's range with the legible window as the bound, which covers content the shipped profile does not author.

  A 1/8 slope grid was built and measured before the staircase was noticed. It moved the ceiling crossover from
  seven levels to four and bought nothing, and it is not in the tree. What is left is a real visible change when
  a whole nesting level is added — the compressor responding to its input, which no policy should suppress and
  no threshold could have caught anyway. Hysteresis would also have been *state*, so the same document would
  render two ways depending on how it was reached: a headless snapshot, a remote client and any cold re-render
  would all disagree. `solve` stays a pure function of `(Stats, bounds, basePx)`.

  Also wrong in the old note: "ugly to retrofit". The quantising version was one line in `solve` and one field on
  `ToneBounds` — which is a small argument for having looked at the numbers five phases earlier.
- ~~**A one-line `Gui.rootEmPx()` accessor.**~~ **Done in P4**, exactly as scoped: a plain `float` accessor, not
  the whole `LayoutContext` (viewport-dependent, would couple a widget to a layout type) and not a `State`,
  because nothing can change it. `TypesetBlock` subscribes to `zoom()` and `dpi()` for the rebuild trigger and
  reads this for the basis.
- **Decide whether the engine memoises `lay`.** It does not today. `arrange` is contractually pure, so the option
  stays open, but a box that iterates — a relaxation pass, a shrink-to-fit — will re-lay the same child many
  times and pay for it every time. Worth measuring before P6 rather than assuming either way.
- ~~**The node projection must emit `Length.dp`, not `Length.em`.**~~ **Done in P4** — and the conclusion was
  right while the premise was not, which is worth keeping. The note said the solved basis "already includes zoom
  and DPI". It includes zoom and must *not* include DPI: `dp` resolves as `v · dpi`, so a basis carrying density
  would apply it twice, and a legibility floor expressed in device pixels shrinks physically on exactly the
  displays where legibility is at stake. The block solves at `rootEmPx · zoom` and lets `dp` supply the one
  remaining factor. `ProjectionTest.densityIsAPureScaleAndZoomIsNot` pins both halves.
- **The projection rebuilds every node on each basis change — measure it before P6.** This is what replaced
  hysteresis as the live-behaviour concern, and unlike hysteresis it is real: `TypesetBlock.rebuild` removes and
  re-creates the whole subtree on every zoom or DPI commit, so a zoom drag is a remove-plus-create per glyph per
  commit. The tone map's slope cannot change with the basis (§4.3.1), so the draw list keeps its exact shape and
  only its numbers move — which means node reuse needs no diffing: same content plus same draw count implies the
  nodes are positionally interchangeable, and the rebuild becomes a prop update. Cheap to do, but measure first;
  the whole-subtree rebuild was a deliberate P4 choice and a draw list is small.

---

## 3. Deferred capabilities

Wanted eventually, deliberately not now.

- **A diagonal draw.** `Placed.Sink` has `glyphs` and `bar`; `bar` is axis-aligned and a `Node` in the projection
  is too, so a diagonal line cannot be drawn at all. A commutative-diagram arrow is the first real thing an open
  box set will ask for. Widening `Sink` is the decision, and it reaches the node projection (which would have to
  bypass `Node` for `Canvas`) and any remote consumer.

  **The target now exists.** `vexelray-gui-draw` is a `Picture` of marks with a `line` at any angle, drawn on a
  node as one prop and exportable as SVG — which is exactly the "bypass `Node` for `Canvas`" the note was
  describing, already built and already clipped to its box (docs/drawing.md). So what is left is the narrow
  decision this note always was: widen `Placed.Sink` by one operation, and project a typeset block onto a
  `Picture` rather than onto floating nodes. The two sinks are close enough — `glyphs` and a box fill — that it is
  a projection rather than a redesign, and the remote consumer's half of the question is unchanged.
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
- **A context menu the keyboard can reach.** Menus are pointer-only: a right click opens one, and the
  pointer chooses from it. Two halves are missing, and they are separable. *Opening* one from the
  keyboard (Shift+F10, the Menu key) needs a position, and the honest one is the focused node's box
  from the layout read-model rather than the last pointer position — so it belongs next to
  `Gui.onContextMenu` as a "open the menu for the focused node" dispatch path, not in the widget.
  *Walking* one (Up/Down/Home/End, Enter to choose, Escape already works) belongs in `ContextMenu`,
  which would claim those chords at `ClaimScope.VISIBLE` exactly as it claims Escape, and needs a
  highlighted-row notion that hover and the keyboard share. Neither is hard; both were out of scope
  the day the menu became declarative. Sequenced in §4.3.
- **Checkable and nested items.** `MenuItem` is a label, an action and an enabled flag. A tick ("Word
  wrap ✓"), an accelerator hint ("Copy   Ctrl+C") and a submenu are the three things a real menu adds
  next. The first two are fields on the record and rows in the presenter. A submenu is not: it is a
  second panel, a hover-open delay, and a hit region that spans both — the overlay primitive handles
  the drawing, but nothing in `MenuSink` can express "these items, under that one" yet, and the
  temptation to model it as a `MenuItem` with children is exactly how a menu stops being a list.
  Sequenced in §4.3.
- **A theme swapped at runtime.** `Gui.theme(Theme)` is read when a widget writes a prop, so a swap after the tree
  is built reaches the renderer's chrome (scrollbars, gutter, selection, shadow) and everything that restyles on
  interaction, but not the props already written — a live dark/light toggle would leave stale colours behind. The
  cheap fix is a restyle notification (a per-node observer shaped like `onState`, released with the node); the
  right one is for a node to *hold* the `Role` and for `TreeRenderer` to resolve it per frame, exactly as `Length`
  resolves against zoom. `Role` is already the declaration and `Color` only the result, so the second is additive:
  `PropKey.BACKGROUND` would carry a `Role` (a literal colour being the constant function), the renderer would ask
  the theme, and re-theming would cost one repaint and zero prop writes. What it needs is invalidation on theme
  change plus a decision about where interaction state is applied, since the renderer does not know it.
- ~~**Interaction depth from the theme.**~~ **Done** — see §4.1, which is where the shape was decided. The
  signature is not the `Theme.elevation(base, state)` this note guessed: a base *length* cannot be stepped without
  arithmetic on `Length`, and adding a scale operator would have meant a third switch over the sealed type §1.1
  exists to remove. Depth is a ladder of rungs instead, so the response is a move rather than a sum.

---

## 4. Widget vocabulary — the components still missing

`-widget` holds **interaction protocols**, not painted controls: `Tabs`, `TreeView`, `TextField`, `ContextMenu`,
`Modal`, `Reorder`, `Popout`, `ListView`, `Table`, `Rail`, `Inspector`, `ColorPicker`, `Select`. A button, a card and a
heading are application code — `Ui` in the demo is the honest demonstration of that, and the reason it works is
that a `Role` already knows its own hover and pressed shades, so nobody there writes a colour down. Promoting
those would make the framework opinionated about appearance and buy no invariant. So a component earns a place
here only when it carries an invariant application code cannot be trusted to re-derive: a selection anchor, a
commit-or-revert, a virtualised row window, a claim on a chord.

**A toggle was on that list and is not any more**, and the correction is worth keeping because it was not a
change of mind about appearance. `Property.flag` needs a switch: a declared property builds its own editor
(§4.4), so every built-in row's control has to exist in the module that declares the row, or the vocabulary the
inspector ships with is not closed. Building one then turned up the invariant that was hiding — a knob placed by
a hand-computed inset is correct at exactly one rem size, and placing it by two grow-weighted spacers in the
ratio `on : (1-on)` is correct at all of them. That is the same trick `Slider` uses, and "a switch is a slider
that holds two values" is worth being literally true rather than nearly true. `Toggle` is therefore here;
`Ui`'s painted one in the demo is not obsolete, it is still the demonstration that a control *can* be
application code.

Two standing constraints shape almost every entry below. **Nothing appears, moves or expands on hover** — a
popup opens on click, and space a control might need is reserved before it needs it. And **every device event
arrives through Tactroller on the bus**; a component that wants a key reads a claim, it does not read a device.

**Sequenced.** §4.1 and §4.2 are done, and so are the entries struck through in §4.3–§4.5. What is left is no
longer a queue. `Select` (§4.3) was the first of the two things named by more than one remaining entry and is
now done, so §4.8's date picker has the half it was waiting on. The other is the **resize `CursorShape`** §4.2
defers, which `Table`'s grip has already settled for `GRAB` and `SplitPane` (§4.5) will want the same one — they
are one native binding, done together. §4.7 is scheduled against the C1 proof in
`architecture-proof-plan.md` rather than against this list, and §4.9 is a gap in what the built widgets
*publish* rather than a widget to build.

### 4.1 ~~Prerequisite: `Theme.elevation(base, state)`~~ — **Done**, as a ladder

`Relief` is the depth half of what `Shading` is for colour, and `Theme.elevation(rung)` /
`elevation(rung, state)` are how a widget asks for it. The seven hand-picked depths and the three copies of the
`switch (state)` are gone; `ReliefGuardTest` is what stops an eighth.

**The signature changed, and the reason is worth keeping.** `elevation(base, state)` would have to scale a
`Length` — which needs an operator on `Length`, which is a third switch over the sealed type §1.1 exists to
delete. So depth is a **ladder of rungs**, exactly as a `Palette` is a ladder of surfaces: hover is one rung up
and press one rung down *whatever rung the control rests on*, the ladder is geometric (base × ratio per rung), and
nothing adds or multiplies a `Length` anywhere. The quantisation is the ladder. A control names `Relief.CONTROL`,
not a shadow size, and a flatter look is a different `Relief` on the theme rather than an edit per widget.

Rungs: `FLUSH`, `CONTROL` (button, toggle, slider knob, selected tab), `RAISED` (a strip over a document, a
popover), `FLOATING` (menu, tooltip, drag ghost), `OVERLAY` (card, dialog). Stepping clamps at both ends, so a
widget cannot walk off the ladder, and the step table has `Shading`'s property: a state it says nothing about does
not move, so a new `InteractionState` needs no case added.

Existing depths were remapped onto the nearest rung, which moves a few of them by a fraction of a rem — the point
of doing this first was that fifteen components should not each remember their own number.

### 4.2 ~~Selection, and the things that need it~~ — **Done**

- ~~**`SelectionModel`.**~~ **Done.** The invariant was stated correctly — *anchor + extent + a set*, with
  Shift-click, Ctrl-click, Shift+Arrow and rubber-band as the same three operations (`at`, `toggle`, `extendTo`)
  over it. Two things about the note were wrong, and writing it is what found them:

  **Not over `nav.Address`.** An address is a window plus a landmark, so keying a selection on one would require
  every row to be a landmark — impossible for the virtualised list this exists to serve, and wrong for `TreeView`,
  which selects items. What selection actually needs of its domain is equality *and an order to range along*, and
  those are separable: `SelectionModel<T>` holds items and takes an `Order<T>` **per call** rather than holding
  one. An `Address` is then just a `T` some consumer might use.

  **Identity, not index.** A stored index silently comes to mean a different row after an expand, a sort, an
  insert or a filter — the same four events a selection has to survive. Asking for the order only while a range is
  being computed is what makes "the selection survives the rows moving underneath it" a test rather than a hope,
  and it is also what lets a virtualised list select an item whose row does not exist yet.

  `Mode` is a capability pair (`allowsMultiple`, `allowsRange`), not a case: an operation a mode does not permit
  degrades to the nearest one it does, in one place, so a single-select list cannot be talked into holding two by
  a widget that forgot to check.

  **`TreeView` has not adopted it yet.** It is single-select with its own row-keyed state, so it would gain
  sharing rather than behaviour, and it carries the most behavioural tests in `-widget`. Convert it once
  `ListView` has proved the model against a second consumer — that is the point at which the two would otherwise
  start to disagree.
- ~~**`ListView`.**~~ **Done.** Virtualised rows over an item list and a row builder, holding viewport-many nodes
  at a hundred thousand items. Three children — a top spacer, the window, a bottom spacer — so the scroller's
  content is the whole list's height from the first frame and the scrollbar is honest before anything is built.
  The window is recomputed from `Gui.layout()`, which already publishes the node's own `scrollY` and `viewH` per
  frame, so virtualisation needed no frame callback of its own. Rows that stay in the window are kept, not
  rebuilt: a scroll of one row builds one row, pinned by `scrollingByOneRowKeepsTheRowsThatStayed` asserting
  node identity.

  **Row height is uniform and stated in rem**, for the reason `Relief` is a ladder: the spacers need *n* times a
  row, and nothing here multiplies a `Length`. Variable heights need a prefix-sum index and are not v1.

  **The `Reveal` half of the note was wrong, and the invariant behind it was right.** `Reveal`'s own scope
  excludes this case in as many words — "a container whose hidden children have no nodes at all" declares
  nothing — so registering a `Reveal` on the list would never be consulted: `Gui.navigate` cannot resolve a
  landmark that does not exist to begin with. What the invariant actually requires is that *the list* can address
  an item with no row, which is `ListView.reveal(item)`: realize the window around the index first (geometrically
  consistent at any scroll offset, because the spacers put a row where its index says), then let the row scroll
  itself in. Everything else that addresses a row — Home/End, Shift+Arrow past the fold — goes through it.

  **It needed one core change**, and the note for it was already written in `ClickEvent`'s javadoc: modifiers are
  part of a click, but `Gui.onClick` handed the handler a bare `Runnable`, so Ctrl-click and Shift-click were
  undeliverable. There is now an overload taking `Consumer<ClickEvent>`, exactly as `onContextClick` already did.
  The alternative — reading `gui.modifiers()` inside a pointer handler — is a second channel alongside the event,
  and is already wrong if the key came up while the handler was queued.
- ~~**`Table`.**~~ **Done.** Sticky header, column resize by drag, sort by column, rows selected through
  `SelectionModel`, body virtualised by `ListView`. The note was right that the hard part is the width
  negotiation, and right about where it lives — with one correction and one consequence.

  **It does not reach `FlexLayout` after all.** Two cells of a column are in different rows, so making them agree
  is a constraint between siblings' *children* — precisely the cross-tree solve §3 defers, and extending flex to
  express it would be that engine change. It does not need one: the widget does the collect-solve-place that §3
  names, from the layout read-model, once per published layout. What it must not do is write unconditionally —
  a width write invalidates the layout that produced it, so an unchanged solve writes nothing, and there is a
  test that the widths are a fixed point rather than a drift.

  **The negotiation needed no new type.** A column's width *is* a `Length`, read through the published contract
  rather than by case: `resolve` answers negative for the keywords, `growFactor` separates fill from auto. So
  fixed is `rem(8)`, a share is `grow(2)`, and to-content is `AUTO` — the vocabulary flex already has, which is
  why there is no `ColumnWidth`. The minimum is a floor applied *after* the share, never a basis added before it:
  as a basis it makes every declared ratio wrong by the same constant, which is what the first version did.

  **`AUTO` had to be decided, not implemented.** A virtualised body has no nodes for the rows off screen and a
  widget cannot measure text that is not in the tree, so "as wide as its content" can only be measured from the
  content that exists. Following the window would resize columns *while the user scrolls* — the standing rule's
  exact prohibition. So it is measured once, from the header and the rows realized at the time, and latched until
  the items change or the user drags. A wider row later is clipped, as it would be in a fixed column.

  **The header is sticky by not being in the scroller** — a sibling above the body, so there is nothing to pin
  and nothing to keep in sync. The same fact is why the body does not scroll horizontally in v1: the columns are
  solved to the width there is.

  Two things worth keeping. A resize grip belongs *outside* the header cell's padding, or the user has to aim
  8px off the divider they can see. And `alignItems(CENTER)` on a header row gives an empty grip or indicator
  slot zero height — nothing to press and nothing to draw in, while reading as perfectly correct code; cells
  stretch and the centring happens one level further in.

  **Not yet: `CursorShape` has no resize shape.** The grip takes `GRAB`, which fits its own documentation
  ("something that can be grabbed and dragged"). A proper double-arrow is a native binding rather than a widget
  change, and `SplitPane` (§4.5) will want the same one — do them together.

### 4.3 Choosers

- ~~**`Segment<T>`.**~~ **Done**, and it was not on this list — it arrived because `Property.choice` needed a
  control for "one of a handful", and a `Select` that hides the alternatives is the wrong shape for three words.
  The entry it should have had: a menu costs a click and a guess to find out what the options *are*, and three
  switches say the wrong thing in the other direction, since nothing in their shape forbids turning two on, so
  the exclusivity lives in a handler and the user meets it by being corrected. A segment says *one of these*
  before it is touched. Options are `(label, value)` compared by `equals`, so an enum constant, a record or a
  string all work and none has to become a string first — and nothing is keyed by an index that silently means a
  different option after an insert.
- ~~**`ColorPicker` / `ColorHistory`.**~~ **Done.** §4.8 said a colour picker was `Select` plus a bespoke panel
  and added no invariant of its own. Both halves were wrong, and building it is what showed why:

  **It never needed `Select`.** The panel is an ordinary node; where it appears — inline, in a `Popout`, floated
  with `Node.floatAt` — is the application's decision. A widget that drew a picker *and* decided it was a
  drop-down would be two things.

  **It holds `Hsv`, not `Color`, and that is the invariant.** Grey has no hue and black has neither hue nor
  saturation, so a picker that stored the resulting colour and re-derived its coordinates loses the user's place
  every time they drag into a corner. That is the bug every hand-rolled picker has, it is invisible until
  somebody drags to an edge, and it is exactly what "an invariant application code cannot be trusted to
  re-derive" was written for.

  **Two more fell out.** `onChange` (a drag moving — the live preview) and `onCommit` (a choice *made* — the undo
  entry) are different events, and collapsing them makes either every pixel of a drag an undo step or the picker
  impossible to watch; `color(...)` fires neither, because nobody chose anything. And a recents strip is only
  useful because it is the *same* strip everywhere, so `ColorHistory` is handed to the pickers rather than owned
  by one: a picker per property with a history each gives the user as many separate pasts as the panel has rows.

  A date picker stays on §4.8 untouched by this — nothing about a calendar has a coordinate a value can fail to
  recover.

- ~~**`Select`.**~~ **Done**, and the sketch was right about all three parts: the overlay primitive, a list, and
  a claim on Up/Down/Enter/Escape at `ClaimScope.VISIBLE`, taken on opening and given back on closing because a
  hidden node keeps its claims. The no-hover rule was the specification and remains so — the popup opens on a
  click or a key, and the chevron slot is reserved whether or not there is a value.

  **The list is a `ListView`, and that is the whole implementation.** Rows are not built here. Composing brings
  virtualisation (ten thousand options cost a popup's worth of nodes), `SelectionModel`, and `reveal` — which is
  what makes opening scroll to the current value rather than to the top. A drop-down that grew its own rows
  would be a second, worse list whose Shift+Down drifted from every other one.

  **Multi-select turned out to be the mode, not a second entry on this list**, which is why there is no
  `MultiSelect` above. `Mode` already makes "several" a capability, so the widget asks the model what it permits
  and everything falls out of that: choosing shuts a single-choice popup and leaves a set-building one up, and
  the strip reads out one label or several.

  **What it cost was a fourth operation on `SelectionModel`, and finding it is the reason this entry is worth
  keeping.** The three operations all answer *what is chosen*. A multi-select keyboard needs to answer *where am
  I* first: with only `at`, `toggle` and `extendTo`, the sole way to reach the fourth option is to select it, and
  reaching it is exactly what must not select it. So `lead(T)` moves the cursor and leaves the selection alone —
  and, being one more operation over the same invariant, it degrades like the rest, to `at` where only one item
  may be held. That degradation is what lets `Select` drive both modes with one call and no branch. Two smaller
  things came with it: `onLeadChange`, because a cursor that moves without changing the selection announces
  nothing on `onChange` and a row that drew it would draw it in the wrong place; and `ListView.clickToggles`,
  the difference between an explorer pane (a plain click replaces) and a tick list (a plain click flips) —
  unconditional in the popup, since `toggle` degrades to `at` too.

  **Not done: it has no demo chapter**, which is the same gap `Segment` has. Neither is wrong, but the showcase
  is where a control's look gets argued with, and two choosers are now unargued.
- **`MenuBar`, submenus, checkable items.** §3 already names all three halves and they stay accurate. `MenuItem`
  wants a tick and an accelerator hint (fields on the record, rows in the presenter); a submenu needs `MenuSink`
  to be able to say "these items, under that one" without a `MenuItem` growing children, which is how a menu
  stops being a list. Opening a menu from the keyboard belongs next to `Gui.onContextMenu`, positioned from the
  focused node's box in the layout read-model — not from the last pointer position, and not in the widget.
- **`CommandPalette`.** `FindBar` is the precedent: a transient, claim-scoped strip over a filtered result list.
  Small once `ListView` exists, and it is how every action becomes keyboard-reachable without waiting on the menu
  work above.

### 4.4 Commit protocol

- **`Field<T>`.** Parse, validate, clamp, and commit-or-revert: Enter commits, Escape restores, focus loss
  commits, an invalid value never reaches the application. `TextField` owns text; nothing owns "this text means a
  number between 0 and 1". `NumberField`, and a `Slider` with a typed entry beside it, are both instances of it
  rather than separate widgets.

  ~~**`NumberField`.**~~ **Done** — as the concrete case, not yet as `Field<T>`. The generic type is still worth
  extracting and this is the evidence for what its contract has to be, because writing it turned up the rule the
  note above states too weakly. It is not that an invalid value never reaches the application; it is that **what
  is on screen and what has been agreed are two different things, and validating per keystroke conflates them**.
  Half of `-1.5` is `-1.`, and `-` alone is half of every negative number there is: a field that parsed each
  keystroke would refuse both and make those numbers unreachable *by typing them*. So the text is left alone
  while it is being edited and read only on commit, and `value()` answers the last agreed number until then. The
  same fact settles the failure case — an unparseable commit puts the last agreed number back rather than
  clearing, so the field always shows a number and the user always sees which one they are now holding.

  Stepping is `BigDecimal`, because `0.1` ten times is not `1.0` and a field stepped up and down ten times should
  read what it read before. The value handed out is a `double` because that is what the callers plot with; the
  arithmetic getting there is not obliged to lose the same digits. `Field<T>` will need that distinction — the
  agreed value's type and the arithmetic's type are not the same question.
- **`RadioGroup`, and a tri-state `Check`.** Trivial to draw, which is why they look like application code and
  are not: exclusivity across siblings, arrow keys traversing *within* the group while Tab leaves it entirely,
  and indeterminate resolving to checked — never to unchecked — on click.

### 4.5 Space and structure

- ~~**`Rail`.**~~ **Done**, and the reason it is not `Tabs` with an orientation flag is a state, not a look. A
  tab bar answers *which of these documents am I looking at*, so one is always selected and the bar is part of
  the content beneath it. A rail answers *which tool am I holding*, so **none** is a legitimate answer: clicking
  the selected icon puts the panel away and leaves the rail. That is the whole reason a rail is worth having —
  the work is the canvas and the panel is on loan from it — and it is a state the tab bar does not have and
  should not grow.

  Pages are builders, built the first time they are shown and kept thereafter — the same choice `Tabs` makes for
  pages and `Popout` for its two hosts, for the same reason: registrations are keyed by node id inside the tree
  that minted them and released when a node leaves, so a rebuilt page comes back drawing correctly and unable to
  take a keystroke. Building lazily is the affordable half — six panels cost one panel's nodes until the others
  are asked for. The icons keep their places whether the panel is open or shut and the panel takes its space
  from the content, so the icon under the pointer when the panel opens is the icon under it afterwards.
- ~~**`Inspector` / `Property`.**~~ **Done**, and it is the entry that most needed the "carries an invariant"
  test applied honestly, because a settings panel looks like the most application-shaped thing there is. What
  makes it not: a panel hand-built out of rows *is* the schema, so what is settable can only be read off the
  layout code, and a second view of the same model — a search, a preset dump, a transcript of what the user
  changed — has nothing to read. Declaring the properties puts the schema where a program can look at it and
  makes the layout a consequence.

  **There is no `Kind` enum and no switch**, which is the whole of what keeps the editor vocabulary open: a
  `Property` builds its own editor and the inspector places the node it gets back, so a sixth kind is an
  application class handed over rather than a change to the widget that displays it. Same shape as
  `dev.vexelray.gui.plot.Expr`, same reason, and it is also what §1's dispatch rule requires. It costs
  something and the cost is right: an inspector *cannot* special-case a kind, and a row that needs to look
  different is a different `Property`.

  Two decisions worth keeping. The **value column belongs to the inspector**, not to the row — reserved for
  every row and written through a `Readout`, because a column that appeared only where there was something to
  put in it would move the controls beside it as values came and went, which is the standing no-movement rule.
  And factories take a **supplier and a consumer**, not an initial value and a callback: the model is the truth
  and a property is a view onto it, so `refresh()` re-reads after a preset, a keystroke or a fit changed
  something elsewhere. Sections are declared, never sorted — the order on screen is the order in the source, and
  no comparator quietly decides that `Look` comes before `Sampling`.

- **`SplitPane`.** A draggable divider with a per-pane minimum and a collapse threshold, persisted through
  `WindowMemory` beside the window bounds that already live there. `Popout` covers the docking half; the divider
  does not exist.
- **`Toolbar` with overflow.** A real component precisely because of the no-movement rule: measure what fits,
  move the remainder into a chevron menu, and keep the chevron's slot reserved so the bar never reflows as its
  contents change.
- **`Disclosure`.** One animated height over `LayoutMotion`, with the invariant that a collapsed section's
  content is not merely clipped but out of the focus order.

### 4.6 Status

- **`Progress`,** determinate and indeterminate. Deliberately distinct from `Cue`, which is one-shot by design; a
  long operation has a value that changes and usually a cancel beside it.
- **`Toasts`.** A non-modal transient stack — the same ordering problem `ModalQueue` already solves, with a
  timeout and no scrim.
- **`StatusBar` and `Breadcrumb`.** Both are projections of state that already exists (`SemanticSnapshot`,
  `nav.Address`) rather than new state, which is the reason to build them: they are a cheap check that the read
  model is sufficient.

### 4.7 `Scrollbar` as a widget

Scrollbars are renderer chrome today. That is the same fact as `CaretScrollTest` proving behaviour lives in the
renderer, and it is why **C1 is not yet provable** (`architecture-proof-plan.md` §1). Promoting the scrollbar is
architecture work with a component-shaped output, so it is scheduled there rather than here.

### 4.8 Not on this list

Buttons, cards, headings, labels, paragraphs — application code, see above.

~~Toggles.~~ ~~Date and colour pickers likewise: each is `Select` plus a bespoke panel once `Select` exists, and
neither adds an invariant of its own.~~ **Two of those three were wrong** — see §4.3 for the colour picker and
the §4 preamble for the toggle. The prediction failed the same way both times: it was made from what the control
*looks like* rather than from what its state has to be, and the invariant only showed up once something was
built. Worth remembering the next time this section is used to decline something.

A **date picker** stays here, and now on a narrower claim than the one it was grouped under: a calendar is a
grid of buttons over a value that survives every round trip through what is displayed, so there is no coordinate
to lose and nothing an application would re-derive wrongly. If that turns out to be false, it will be false for
a reason, and the reason goes here.

### 4.9 Semantic roles the widgets do not declare

~~`ListView` and `Table` publish no `role` at all~~ — **the naming half is done.** `ListView` declares
`list` / `listitem`, `Table` declares `table`, `row`, `columnheader`, `columngrip`, and gives its body the words
`rowgroup` / `row` through `ListView.roles(container, item)`; the row role goes on what the row builder built,
because a name is derived from the text below a node and the wrapper `ListView` puts around it is one level too
far up. Found by driving a real table: without a role, the only handle an agent had on a hundred-thousand-row
table was the header's derived name, and a stale ref for a scrolled-past row clicked that header instead.

The objection recorded here — that a bare `list` role would publish a number that is quietly the window's — did
not survive contact: no role publishes a count, and a reader counting `row` nodes gets the window with or
without one. Declaring nothing did not withhold the count, it withheld the structure.

**The hard half stands.** A virtualised body has no node for a row that is off screen, so the honest description
of a hundred-thousand-row list is not the thirty rows that happen to exist — it wants a way to say a count and a
realized range, which no role can carry today. A reader currently sees the window and no statement about the
rest.

Second, smaller: the demo's `Ui` declares `toggle` for a painted text node while `Toggle` declares `switch`, so
one word covers two different things in a reader's view of the same tree. One of them should move.

Both are `semantic-read-model.md` §5's table, which is where the current state is written down.

---

## 5. Engine-side (lands in `vexelray`, not here)

- **A math face in the primary atlas.** Fully specified in `vexelray/docs/math-face.md`: the `<extraFont>` entry,
  the charset to take and in what priority, the atlas budget, and how to verify. It is a quality upgrade on a
  working feature, not a prerequisite — `-typeset` degrades to upright variables where the U+1D400 block is
  absent, and everything else already renders on today's atlas.
