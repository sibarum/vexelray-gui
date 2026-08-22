# vexelray-gui — Typeset

**`vexelray-gui-typeset`** — a structured-text renderer built on an open set of composable boxes, where type size is
a ratio and the renderer tone-maps the whole block into a legible range.

| | |
|---|---|
| Module | `vexelray-gui-typeset` |
| Depends on | `vexelray-gui-core` (only) |
| Entry | application edge, optional |
| Status | **P0 complete** — the vocabulary and the SPI are settled and proven; P1 next |

---

## 1. What this is

An optional module that renders structured, non-editable rich text — math notation first, but the primitive set
is general. It *complements* the flex layout engine rather than extending it: a typeset block is an **atom** in
flex layout with an intrinsic size, and its interior is its own composition world.

Internally the layout is still flex-shaped — rows, stacks, grow and shrink. What the block adds is
parent-relative sizing and precise attachment: the semantics of `inline-block` and `position: relative`, packaged
for composition rather than for document flow.

## 2. Non-goals

Decisions, not omissions.

- **No parser, no format.** The app builds the IR. The framework holds no opinion about markup and never will —
  that seam is what lets new formats be pioneered without touching the GUI. It also means the app can stamp each
  run with its own source range, so selection later returns offsets the app already understands rather than a
  reconstruction.
- **No editing, no WYSIWYG.** Read-only.
- **Never inside a text field.** A typeset block is a separate component. `TextField` is fast precisely because
  its layout is one-dimensional and deterministic; admitting arbitrary 2D composition into it forfeits that. No
  inline math in prose, no interaction with `Span`.
- **No wrapping in v1.** A block is a fixed-aspect atom — correct for equations, and the deliberate later step
  for prose.
- **No evaluator, no SVG export, no AST bridge.**

---

## 3. The IR: an open set, seven built in

`Box` is an **interface**, not a sealed hierarchy. Seven implementations ship; an application adds an eighth by
implementing it, from its own package, with no framework change and no privileged access.

**The one rule is containment: do not draw outside the box you declare.** `arrange` returns a `Placed` carrying a
width, an ascent and a descent, and every draw must fit inside them. That is the whole of what a parent needs in
order to place a child it knows nothing about, and the whole of what the framework enforces. Everything else —
how children are sized, where they physically land, whether marks are drawn that correspond to no child at all —
belongs to the implementation. **Physical and logical structure are free to diverge:** a slash struck through a
relation, a combining mark, an arrow derived from two other boxes' positions have no logical child position, and
the SPI does not pretend otherwise.

An earlier draft of this section constrained a custom box to *position* pre-arranged children but never to size
them, to keep §4's guarantee airtight. That was the wrong trade — it would have made an overlay, a negation and a
graph primitive all inexpressible, and it was propping up an overclaim (see §4.4).

**What this buys is not "extensibility" but blast radius and uniformity.** A new kind of composition is a change
to *one file*: purely additive, so it cannot break an existing kind, and there is no switch to update, no
registration, no renderer case. And it cannot behave inconsistently with the rest of the application, because it
does not own any of the decisions that would let it — the tone map is one solve for the whole block, spacing is
one table, containment is one rule. A new kind inherits every invariant by construction rather than by its
author remembering them.

That is also why the containment rule is the *enabling* condition rather than a limitation that survived. Open
with decentralized invariants is a plugin free-for-all; open with centralized invariants is the property above.
The second decision is what licenses the first — and it is the precise thing CSS's display modes lack, which is
why margin collapsing works in `block` and not `flex`, and why `align-items` does not mean quite the same thing
twice. Each mode re-derived the invariants instead of inheriting them.

The honest caveat: the property holds only while the invariants really are enforced. Two of the three are —
one tone solve, one spacing table. **Containment is enforced by nothing but this document until P2** (docs/todo.md
§2).

| Built-in | What it is |
|---|---|
| `Run` | Glyphs plus a face key and a source ref |
| `Row` | Horizontal sequence; inter-item gap decided by adjacent spacing classes |
| `Stack` | Vertical sequence with explicit gaps and a named anchor — the baseline of child *n*, the axis, or its own centre |
| `Attach` | A nucleus with satellites at named corners: NE, SE, NW, SW, N, S |
| `Rule` | A filled bar sized from its context |
| `Stretch` | A glyph scaled — later, assembled — to a target extent, with its own kerns |
| `Grid` | Cells with per-column alignment and explicit gaps |

**Two properties are universal, carried by all seven** rather than by `Run` alone:

- `size` — the ratio relative to the parent, because a whole superscripted *subtree* scales as a unit, so the
  ratio has to attach to any node.
- `spacingClass` — the index a `Row` looks up, because a fraction sitting in a row spaces as one atom. A
  composite needs a class just as much as a glyph run does. Recipes set theirs to `INNER`.

Both were `Run`-only in the first sketch, and both had to be lifted for the same reason a custom box gets them
too: a composite is a first-class box, not a second-class container.

Two smaller shapes settled while building it. **Gaps are explicit on `Stack` but table-driven on `Row`**, because
there is no adjacency table for the vertical axis — the space above a fraction bar and the space below it are
different profile numbers, not a function of what happens to sit there. And **construct-specific kerns live on
`Stretch`** (`padBefore` / `padAfter` — the tuck under a surd's hook, the pad inside a delimiter pair) rather than
as row gaps, so `Row` keeps exactly one spacing mechanism.

### 3.1 The SPI

A box declares four things the framework reads, and writes one method that does the work.

```java
public interface Box {
    double size();                              // relative to the parent
    int spacingClass();                         // index into the profile's table
    List<Box> children();                       // reading order — a mark with no logical position is absent
    Box with(double size, int spacingClass);

    Placed arrange(Arrangement a);              // the only method an implementation writes
}
```

`Arrangement` is a layout **service**, not a bag of pre-arranged children — the box drives:

```java
public interface Arrangement {
    Placed lay(Box child);                      // at its declared size
    Placed lay(Box child, double size);         // or at one you pick
    double toneMapped(double authored);         // the block's solved transfer — available, not mandatory
    Profile profile();
    double axis();
    Glyph glyph(String faceKey, int codepoint);
}
```

`lay` being re-callable is what makes measure-then-place work, so shrink-to-fit and optical sizing are
expressible. `toneMapped` is offered rather than imposed: a box choosing its own size can route it through the
same transfer as the rest of the block, or ignore it.

**`lay` and `arrange` are pure** — functions of their inputs, with no side effects and no reliance on call count.
The engine may memoise, call twice in a frame, or call repeatedly while an enclosing box iterates. Iterating
internally is expected and supported; accumulating state between calls is not. The engine bounds recursion depth,
so a box that lays itself out fails loudly rather than hanging the GUI thread.

**Enforcement.** Containment is asserted in tests at P2, where glyph metrics exist. At runtime the block already
clips for scroll, so an overflow is visual overlap — findable, not corrupting. No per-box clip push/pop, which
would be real overhead for a rule that is a bug when violated.

**No sealed switch, and no default case that throws.** Both are the same failure — behaviour living somewhere
other than the type it belongs to. A switch over a type hierarchy is dispatch written by hand; a `default:` that
throws says the type permits a state the code cannot handle, which means the type is wrong. Enforced on this
module by `DispatchGuardTest`. This rule is *why* `Box` is an interface: the sealed switch was not a detail of
the old design, it **was** the design error — it is what made the vocabulary closed.

**Where a consumer must stay closed, invert to a sink.** A renderer, the node projection, and a remote client
holding no atlas all need a closed set of operations they can implement completely. The obvious way to get that is
a sealed `Draw` they switch over — banned, and unnecessary. Instead `Placed.Draw` is **open** and knows how to
express itself as calls on `Placed.Sink`, which is **closed**:

```java
interface Draw { Draw shifted(double dx, double dy); void emitTo(Sink sink); }
interface Sink { void glyphs(String text, String face, double x, double y, double size, Object ref);
                 void bar(double x, double y, double width, double height); }
```

A consumer implements two methods and is done, including for draw kinds written after it. Neither side switches.
Widening `Sink` stays a real, visible decision with consequences in every consumer — which is what it should be.

`Sink` deliberately has **no default methods**: a renderer that silently drops a draw kind is a bug, and the
compiler should catch it. Contrast a producer-owned event stream (`InputSink`, docs/todo.md §1.5), where the same
inversion wants no-op defaults because consumers legitimately ignore most events. Same shape, opposite tuning.


### 3.2 Closure over the reference constructs

The ten-record math IR this replaces collapses as follows. Three of its records are one built-in.

| Construct | Composition |
|---|---|
| Fraction | `Stack(num, Rule, den)` anchored on axis |
| Script | `Attach(base, NE, SE)` |
| Prescript | `Attach(base, NW, SW)` |
| UnderOver | `Attach(base, N, S)` |
| Radical | `Row(Stretch(√), Stack(Rule, radicand))` + index at NW |
| Fenced | `Row(Stretch(open), content, Stretch(close))` |
| Matrix | `Row(Stretch, Grid, Stretch)` |
| Cases | `Row(Stretch({), Grid)` |
| Row · Run | primitives already |

The same seven cover prose structure: a heading is a `Run` with a profile style, a footnote marker is
`Attach(NE)`, underline and strikethrough are `Rule`, a blockquote is `Row(Rule, Stack)`, ruby is `Attach(N)`, a
table is `Grid`. That the two domains land on the same set is the evidence the abstraction is real rather than
convenient.

> **Discipline, relocated.** The earlier version of this rule counted subclasses. That was a proxy; the real
> intent was "no escape hatch that lets you skip the design work", and it is now carried by the *invariants*
> instead — a box must declare a truthful container and must be pure, and those hold for a kind nobody has
> written yet. What remains as judgement: **the built-ins stay at seven** until a real construct forces an
> eighth. Extensibility is for applications; the framework does not sprawl just because it now can.

### 3.3 What this reaches, and what it does not

Because a box drives its own layout and only owes a truthful container, more is expressible than notation:

- **Multi-pass within a box** — measure, then place. `lay` is re-callable.
- **Fixed-point over a subtree** — a force-directed graph is *one box*: its children are the nodes, `arrange`
  runs the relaxation, and it returns a bounding box. Nothing in the SPI forbids it.
- **Not: a global solve across the tree.** A constraint between a node in one box and a node in another needs
  collect-then-solve-then-place, a different engine shape. Everything tree-local is free; anything cross-subtree
  is a rewrite.

This is also the factoring CSS got backwards. It closed the *display mode* into an enum and left the *properties*
open, so every unmet layout need became a new mode with its own vocabulary, mostly non-composable — `block`,
`inline-block`, `flex`, `grid`, `table`, `ruby`, `flow-root` is a changelog of missing extension points. Here flex
would be a box kind, and so would grid, ruby, and the ones nobody standardised. (CSS had one real excuse:
closed algorithms are what let every browser agree pixel-for-pixel. That does not apply to one implementation.)

**Known limit:** `Bar` is an axis-aligned rect, and a `Node` in the projection is an axis-aligned box — so a
diagonal line is not drawable. A commutative-diagram arrow is the first real thing an open set will want and
cannot have without widening the draw alphabet and reaching past the node projection to `Canvas`.

---

## 4. Sizes are ratios; the renderer tone-maps

The same operation as an HDR transfer curve or an audio compressor, applied to type size.

Every node declares its size **relative to its parent**. Absolute values carry no meaning; only ratios do. The
renderer fits the block's authored range into the display's legible range.

### 4.1 The solve

Work in log space. Ratios become differences, and a bound on permissible ratio distortion becomes a bound on the
*slope* of the transfer function.

```
A_i = Σ log r_k        authored log-size of leaf i — path sum, root to leaf
S_i = exp(s·A_i + t)   rendered size — two scalars for the entire block
```

One tree walk gathers four numbers, a closed-form solve gives `s` and `t`, one walk applies them. O(n), no
iteration, deterministic.

```
gathered   minLog, maxLog       extremes of A across the block
           minStep, maxStep     tightest / loosest adjacent authored ratio

bounds     sizeFloor, sizeCeil  legible pixel range
           ratioFloor           contrast corridor

slope      sFit = (sizeCeil − sizeFloor) / (maxLog − minLog)
           sMin = ratioFloor / minStep
           s    = clamp(min(sFit, 1), sMin, 1)

gain       t    = max(baseLog, sizeFloor − s·minLog)
```

**Slope is capped at 1 by default.** Never expand authored contrast — rendering an authored 1.05:1 as 4:1 invents
information the author did not supply. A `ratioCeil` bound exists to make expansion safe for a profile that opts
in; without opt-in it never binds.

**Anchor, do not normalize.** `t` places the root at the block's declared base size and pushes up only as far as
the floor demands. Always stretching to fill the window would blow a flat `x + 1` up to the ceiling and shrink
nothing.

### 4.2 When the constraints conflict

Past some depth, fit and contrast are not simultaneously satisfiable. The yield order is pre-declared, which is
what makes the system total — always feasible, always legible.

| Constraint | Hardness | Rationale |
|---|---|---|
| size floor | **hard** | Legibility. The entire point of the mechanism. |
| contrast floor | **hard** | Two nesting levels rendering at the same size is worse than one being oversized. |
| size ceiling | **yields** | Aesthetic. A deeply nested expression *is* large if every part of it must be readable; it overflows and scrolls. |

**Where it breaks, concretely.** At `9pt–32pt` the display range is 1.83 stops. A script scale of `0.7` costs
0.515 stops per level, and a `1.5:1` contrast floor pins `s ≥ 0.585`. The ceiling therefore yields past
`1.83 / 0.585 / 0.515 ≈ 6` levels of nesting. A fraction whose numerator carries a script that carries its own
script is three to four. Real headroom, and a known limit rather than a surprise.

The soft-knee variant — slope 1 near the root, compressing harder toward the extremes, exactly a photographic
S-curve — is the natural v2 and buys a few more levels. Not first.

### 4.3 Three consequences to accept

- **The map runs at layout time, against resolved pixels.** The floor is physical, so the solve needs
  `rootEmPx · zoom · dpi`. The block re-solves and re-projects when that basis changes — the same rebuild path as
  a content change, not a per-frame cost.
- **Resolve every length after the map, never before.** Gaps declared relative to their context scale with that
  context's post-map size for free. Anything resolved to pixels ahead of the map silently detaches — invisible at
  slope 1.0, wrong the moment compression engages.
- **Scope is the block, never the document.** A global solve would let a heading elsewhere on the page compress
  your equation. Each typeset block is its own scene.

**Stability.** Because `s` depends on the block's extremes, adding a deeply nested subterm resizes every glyph in
the block. Fine for static rendering; visible jitter for anything live. Apply hysteresis to the solve, or
quantize `s` to steps. Cheap now, ugly to retrofit.

### 4.4 What the map actually guarantees

It fits **declared** sizes into the legible range. Not every glyph — declared sizes. A box that chooses a size
itself (§3.1) has opted out for that content and owns its legibility. That is a scope boundary, not a hole, and
stating it accurately is better than constraining the SPI to make a stronger claim true.

The risk this leaves is already absorbed: the one thing an unpredictable box can do is make the block **larger**
than the declared ratios predict — which is exactly the constraint §4.2 already designated as the one that
yields. The soft ceiling was the right call for reasons that turned out to include this one.

---

## 5. What the style context still carries

Because size is handled globally by the map, the context flowing down the tree carries no size at all — the
classical per-style size table is subsumed. What remains is thin and non-dimensional:

- **Slot identity** — which position in which recipe, for spacing and anchoring.
- **Cramped / uncramped** — affects superscript *shift*, not size.
- **Display / inline** — whether limits sit above and below, or to the side.

## 6. Spacing is a pairwise table

Not "space contributed by this run's role", which cannot express that a relation beside an open delimiter differs
from a relation beside a number. A `class × class → gap` table, indexed by adjacency.

**Classes are profile-defined indices, not a framework enum.** The engine only ever evaluates `table[i][j]`. A
math profile uses Ord / Op / Bin / Rel / Open / Close / Punct / Inner; a prose profile uses whatever it needs.
Same engine, different vocabulary — this is what lets one engine serve both vocabularies while letting profiles own their
semantics.

## 7. Profiles

Compile-time records. No config format, no runtime parsing. A profile carries face keys, size-ratio constants,
the spacing table, per-recipe anchoring constants, and the tone-map bounds.

**Faces by key, not index.** `Node.font(int)` indexes a face array, and `AtlasData.face(i)` returns faces over one
shared atlas image. So profiles name faces by *key* and the app supplies the key-to-index binding at
construction. Profiles stay portable across whatever the atlas situation becomes.

**Recipes are built in; profiles are data over them.** "The numerator is anchored so its bottom clears the bar by
`gapNum`" is an algorithm, not a number. Ship built-in recipes and let profiles supply their constants.
Recipes-as-data is a later, deliberate step, taken only if a real app needs a construct the built-ins cannot
compose — building it now would be a layout DSL authored on speculation.

---

## 8. Projection to the node tree

The engine's output is a flat draw list. Each draw becomes one floating node.

```
Glyphs(text, x, y, size, face)
    → Node.text(text).font(idx).textSize(em(size·k))
          .floatAt(em(x·k), em((y − ascender·size)·k)).hitInert(true)

Bar(x, y, w, h)
    → Node.background(ink).size(em(w·k), em(h·k))
          .floatAt(em(x·k), em(y·k)).hitInert(true)
```

The `− ascender·size` term converts baseline-relative y to the node's top-left: a text node draws `VAlign.TOP`
and the engine places the first baseline at `box.y + ascent`. `ascender` is a per-face constant from the same
`AtlasData` the layout already holds. Keep it in one named helper — the moment a second backend exists it must
use the identical conversion.

The container is sized `em(width·k) × em((ascent + descent)·k)`, and that box is exact, because a floating node
takes no space from siblings and adds nothing to parent overflow.

> **Nothing in core changes.** The module builds nodes through the public `Node` API, so it posts mutations like
> any widget and never writes `RetainedNode`. The model-writer guard stays green by construction rather than by
> discipline.

**Selection insurance.** Not built now, but kept possible for one field's cost: every `Run` carries an opaque
`sourceRef` the app supplies, and the layout guarantees leaf emission order matches reading order. Selection then
becomes purely additive later.

---

## 9. Module shape

```
vexelray-gui-typeset        →  vexelray-gui-core   (only)

  Box            the open SPI + the seven built-ins, plus Extent / Align / Anchor   [P0 ✓]
  Slot           the six attach corners                                             [P0 ✓]
  Profile        face keys, ratios, spacing table, recipe constants, tone bounds     [P0 ✓]
  Recipes        built-in compositions + the math profile's atom helpers             [P0 ✓]
  FaceKeys       key → face index, supplied by the app                               [P0 ✓]
  Placed         draw list + metrics; the closed alphabet Glyphs | Bar               [P0 ✓]
  Arrangement    the layout service a box drives                                     [P0 ✓]
  ToneMap        the solve — pure numbers, zero dependencies                         [P1]
  Layouts        where the seven built-ins arrange themselves                        [P2]
  Typeset        the engine: walks the tree, implements Arrangement                  [P2]
  TypesetBlock   the component: Placed → a subtree of floating nodes                 [P4]
```

Depends on `-core` alone — the projection needs `Gui`, `Node`, `Length` and `Color`, and nothing else. It sits
beside `-krono` and `-nfd` as a module the application edge opts into.

`Extent`, `Align` and `Anchor` are nested in `Box` rather than given their own files: they are IR vocabulary, only
meaningful against a primitive, and nesting keeps that visible. `Slot` is separate because it is the more
prominent concept and it is what three former records collapsed into.

---

## 10. Phases

Ordered so the two gates that can invalidate the design come first, and both are cheap.

### P0 — Close the vocabulary ✓

Write the `Box`, `Slot`, `Profile`, `Recipes` and `FaceKeys` shapes, then express every target construct as a
composition.

**Gate:** all ten reference math constructs compose from the seven primitives. If one does not, that is the
finding, and it is resolved before anything else exists.

**Result: passed, no eighth built-in needed.** `VocabularyTest` (16 assertions) holds the line:

- **The framework has no privileged box kinds.** Every member of `Box` is asserted public and the interface
  asserted unsealed — were either false, only a class inside the module could be a box and the extension point
  would be decorative. This replaced counting subclasses, and it is the stronger claim: a count says the
  vocabulary did not grow, this says the SPI is sufficient to write a box kind with.
- **An application-defined box is a first-class citizen.** `Superimposed` lives in a *different package*, uses
  only public API, and does something no built-in can — draws a mark that is not a child, positioned from the
  nucleus's measured box (a negated relation). It composes into a `Row`, a recipe resizes it without knowing what
  it is, it carries a spacing class, and the generic walk sees its nucleus and not its mark.
- All ten constructs build, and every node in every one of them is one of the seven.
- The claimed collapses are asserted, not just described: `script` / `prescript` / `underOver` produce the same
  `Attach` differing only in occupied slots; `matrix` and `cases` produce the same `Grid` differing only in
  `columnAlign`, with identical gaps and anchor; a fraction bar and a radical vinculum are both `Rule` + `FILL`;
  a surd and a growable delimiter are both `Stretch` + `FILL`.
- `authoredRatiosCompoundWithoutClamping` pins the §4 claim concretely: an exponent's exponent comes out at
  `0.49` with nothing in the IR clamping it. That is the special case the previous implementation hand-wrote
  into its fraction layout, now absent by construction.
- Reading order is asserted end to end, including that a pre-script is read before its nucleus — the guarantee
  the future selection depends on.

One thing the recipes settled: an `Attach` with no satellites returns its nucleus rather than an empty wrapper,
so `script(base, null, null)` is `base`. A construct with nothing attached is not a construct.

### P1 — ToneMap, standalone

Pure numbers, zero dependencies, fully testable before a tree exists.

**Gate:** degenerate cases — single run, zero range, identical siblings. `s ≤ 1` holds. Yield order under
infeasibility. The depth sweep matches the hand calculation.

### P2 — The engine

Primitives to `Placed`, against `dev.vexelray.text.AtlasData` — which is already shaped identically to the
reference implementation's metrics API, so this is the least risky phase.

**Gate:** golden geometry over hand-built trees, run against the real shipped atlas rather than a fixture.

### P3 — Recipes and the spacing table

Wire the built-in compositions and the pairwise adjacency table.

**Gate:** assert relationships rather than raw numbers wherever a relationship will do — bar centred on the axis,
numerator bottom clearing the bar by `gapNum`. Keep exact goldens for a few canonical trees as regression
tripwires.

### P4 — Projection and component

The draw list becomes a subtree of floating nodes, rebuilt on content or basis change.

**Gate:** the container box exactly bounds the draw list; node counts are within budget; a headless snapshot
renders.

### P5 — Atlas face

Lands in **vexelray**, not here — one `<extraFont>` alongside the existing mono face in `vexelray-text/pom.xml`,
since faces share a single atlas image. No multi-texture work. Specified in that repo at
`vexelray/docs/math-face.md`, including the charset to take, the budget, and how to verify it.

**Gate:** italic-math renders; and the degradation path is verified — with no math face, probe for U+1D44E and
pass variables through upright, so the module is useful on today's atlas.

### P6 — Demo panel

Hand-built trees, a profile switcher, and a zoom control.

**Gate:** the tone map's response to a changing pixel basis is the thing worth demonstrating — if the zoom
control shows jitter, the hysteresis policy was skipped.

---

**Done in P0:** `DispatchGuardTest` fails the build on any sealed type in `-typeset`, verified against a real
re-seal rather than only a synthesized one. Its scope list is `RULED = [GUI_TYPESET]`; `-core` still has three
sealed types predating the rule — `Length`, `Mutation`, `Edit`, dispatched from `Length` itself, `Reconciler`,
`Document` and `InputDispatcher` — and widening `RULED` to cover them is the conversion.s definition of done.

Every refactor this session surfaced in existing code — `Length`, `Mutation`, `Edit`, and one throwing default in
`GuiApp` — is written up in docs/todo.md §1 with the shape of each conversion. `-core` is deliberately untouched.

The throwing-default half of the rule is not yet mechanised: detecting a `default:` arm that reaches an `athrow`
means walking switch targets in the bytecode, which is real work rather than an attribute read. Reviewed by hand
for now.

**Done in P0:** `-typeset` is in the modules the architecture guard inspects. Both guards now iterate
`Bytecode.INSPECTED` rather than an inline pair, so the next module is one constant away from being covered —
a guard that does not look at a module cannot fail for it.

## 11. Open items

Tracked in docs/todo.md; repeated here only where a phase blocks on them.

- Confirm a widget-facing accessor for the resolved layout context — `rootEmPx`, `zoom`, `dpi` — exists on `Gui`,
  or add one. The tone map needs the pixel basis, and P4 blocks on it. **Unverified.**
- Atlas budget: the primary face is roughly 1300 glyphs in a 2048 atlas at 32px, and the U+1D400 block adds about
  1000 more. Trim to italic and bold-italic; skip fraktur, script and double-struck.
- Choose the hysteresis policy for `s` before P6.
- No reference front-end ships. Test input is hand-built IR trees, which prove the primitives rather than a
  format — and leave the format question entirely open.
