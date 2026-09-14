# Reliable plotting

`vexelray-gui-plot`. Ported from the Pontif framework's `pontif.algebra` interval evaluator
(`docs/reliable-plotting.md` there), rewritten into this repo's idiom. **Four of the five units are here: the
enclosure algebra, the expression vocabulary that walks onto it, the frame and the classification of a column
against it, and the policy that picks a window. The fifth — drawing — is not, and does not need to be: the
first consumer, `calculator-vexel-demo`, renders spans out of ordinary boxes.**

Two later additions sit alongside them, both pure and both driven by the same consumer: **two variables**
(`Cell`, `Volume`, `Camera`) and **landmarks** (`Expr.derivative`, `Landmark`, `Landmarks`). See
[Two variables](#two-variables-and-what-did-not-have-to-change) and [Landmarks](#landmarks) below.

## The problem point sampling cannot solve

The usual way to plot `y = f(x)` is to evaluate at N points across the window and join them. Two failures, and
the second is fatal:

- **Sparse poles.** `1/x` over `[-10, 10]`: a sample near zero returns a huge finite value that dominates the
  y-range, and the polyline draws a confident line straight through the asymptote. Breaking the line at the pole
  fixes this one.
- **Dense poles.** `tan(1/x)` near zero has **infinitely many** asymptotes in any neighbourhood of the origin.
  Between two adjacent samples there can be thousands. *No finite sample count detects them* — you always alias,
  and adaptive refinement does not converge. Point sampling is not incomplete here; it is blind in principle.

Line-breaking is a real improvement for the first and worthless for the second. The instinct that the honest
answer is to *fill the space with a solid block once there is more than one asymptote per pixel* is correct, and
it is a known algorithm.

## The technique

Jeff Tupper, *"Reliable Two-Dimensional Graphing Methods for Mathematical Formulae with Two Free Variables"*
(SIGGRAPH 2001) — the algorithm behind GrafEq. Instead of evaluating `f` at a *point*, evaluate it over a
*region* — a pixel column's x-interval — using **interval arithmetic**, which returns an **enclosure**: a value
guaranteed to contain everything `f` takes on that column. Every pathology then falls out of the arithmetic
instead of being special-cased:

| pathology | how the enclosure surfaces it | eventually rendered as |
| --- | --- | --- |
| sparse pole (`1/x`) | division by an interval straddling zero → `Unbounded` | a break between the finite stretches |
| dense poles (`tan(1/x)`) | `Unbounded` for the whole neighbourhood | a solid block — the fill, *derived* |
| domain gap (`log` of a negative column) | no real value anywhere → `Undefined` | nothing: a true gap |
| tame stretch | a thin bounded enclosure | a 1px curve |

The pole is found by the **arithmetic**, not by a root-finder. Division by an interval containing zero is
unbounded, and that is the pole, reliably, with nothing solved.

## Soundness is the contract

`Expr.enclose(column)` returns a value that *provably contains* `{ f(x) : x ∈ column }`. It may be conservatively
fat; it can never miss the curve nor place it where it isn't. Inexact endpoints round **outward** — the one place
the module widens deliberately rather than rounding to nearest.

The bias that follows is the one a plotter wants: **no false negatives, at the cost of false positives.** Every
real asymptote is caught, because soundness forces the divisor's enclosure to contain the zero that is really
there. The converse leaks — `x/x` reports a pole it does not have, because interval arithmetic drops the
correlation between the two occurrences of `x` (the *dependency problem*, pinned by
`EnclosureTest.theDependencyProblemWidensRatherThanMisses`). Never draw through a singularity; at worst
over-warn.

## What the port changed

The source was a closed union — twelve `AlgExpr` node types, three enclosure types, and every operation over
them a twelve- or three-case `match`. Under this repo's dispatch rule (`DispatchGuardTest`) that shape is not
available, and the alternative turned out to be better rather than merely compliant:

- **`Enclosure` is an open interface** whose three implementations carry the operations. The propagation law —
  `Undefined` absorbs, then `Unbounded` spills, then the arithmetic runs — is written **once**, as the
  `combine` / `againstBounded` pair, instead of being repeated at the top of every binary operation. Adding a
  fourth kind of answer means writing a type.
- **`Expr` is an open interface** whose nodes enclose themselves. An application adds `Sinh` by writing `Sinh`;
  the evaluator does not exist as a separate thing to edit, because there is no separate evaluator.
- **Two guards were added at the endpoints** the source did not have: an `Interval` with `lo > hi` is rejected at
  construction (an inverted interval encloses nothing, so every claim about it is false), and a transcendental
  whose `double` result is non-finite spills instead of throwing out of `BigDecimal.valueOf`.

The arithmetic itself — the propagation rules, outward rounding, the periodic-extremum test, exact rational
powers — is the same, and the cases it was originally proven with are restated in `EnclosureTest`.

## Module boundaries

The units are separately swappable, and dependencies point one way:

1. **Interval algebra** (`Interval`, `Unbounded`, `Undefined`) — arithmetic and nothing else. Knows nothing of
   expressions or plotting; the single place the three-way propagation lives.
2. **The expression walk** (`Expr`, `Cell`) — nodes onto (1), enclosed over a named region. No plotting
   dependencies. `Expr.derivative` is here too: it produces an `Expr` and belongs with the vocabulary.
3. **Classification** (`Frame`, `Volume`, `Span`) — `(enclosure, extent) → span`: a clipped stretch of curve, a
   painted pole, or nothing. A pure function, and the whole of it is clipping. `Camera` sits beside it — a
   projection is geometry, and this one has no dependencies either.
4. **Framing policy** (`Framing`) — `expression → frame` or `→ volume`. Where the window comes from is as much
   preference as mathematics, so it is isolated: swapping the feel of auto-framing must not touch evaluation or
   rendering.
5. **Feature finding** (`Landmark`, `Landmarks`) — `(expression, window) → the places worth pointing at`. Onto
   (1), (2) and nothing else; it has no idea there is a picture. The one unit here whose bias runs the other
   way: it confirms rather than over-approximates, for the reason given under [Landmarks](#landmarks).
6. **Renderer** — spans to pixels, knowing nothing of algebra. Lives in the consumer, and the first one is
   `calculator-vexel-demo`, which now has two: a curve renderer and a surface renderer. Not here, because a
   renderer needs a node vocabulary and this module has no dependencies at all.

**The diagonal line that was blocking (6) turned out not to exist.** The note this section used to carry said
the renderer was waiting on a node kind that could draw one, `NodeKind` being `{BOX, TEXT}`. But a reliable plot
has no diagonals in it: a column classifies to a *vertical span*, and a vertical span is a box. Point sampling
needs polylines because it joins samples it did not evaluate between; evaluating over the column removes both
the need to join and the thing that was blocked on. `-core` did not have to widen.

**And the cheap-viewport property falls out of unit 3 being separate from unit 1.** An enclosure is in plot
space, so it does not depend on the frame at all: moving in **y**, at any zoom, is re-classification and
re-evaluates nothing, and moving in **x** re-evaluates only the columns that came into view. A consumer that
keeps a map from column-of-x to enclosure can pan and zoom without recomputing what it already knows — which is
what makes the difference between a plot you explore and a plot you wait for.

## Two variables, and what did not have to change

`z = f(x, y)` needed one idea and three small types. The idea: **the region an expression is enclosed over
becomes a value.** `Expr.enclose(Interval)` had `Param` answering with the column *whatever its name was*, and
that clause is exactly what a second variable breaks — the answer now depends on which parameter is asking. So
`Cell` is a binding from a parameter's name to its interval, `enclose(Cell)` is what every node implements, and
`enclose(Interval)` stays as a default that binds every name to the column. One variable is not a special case
of two; it is the degenerate one, and the old call sites mean exactly what they always did.

The arithmetic did not move at all. An enclosure over a cell contains every value the expression takes in that
cell, by the same operations and the same outward rounding; a divisor whose range straddles zero is still a pole,
now a pole somewhere in a patch. **`Span` did not move either** — a cell's range of z clipped to the visible
depth is the same three answers a column's range of y gives, so classification was un-narrowed rather than
generalised (`Span.of(enclosure, lo, hi)`, with the `Frame` form delegating). What is new is only:

- **`Volume`** — `Frame` with a third axis, and `cellAt(ix, iy, u, …)` computed from the indices alone, so cells
  tile the floor without a seam and a cached enclosure stays true about the cell it is looked up for.
- **`Camera`** — an axonometric projection: yaw, pitch, magnification, no perspective. The absence of perspective
  is the design decision. A reliable surface is a field of axis-aligned boxes standing over a grid, and under an
  orthographic projection the order they must be painted in depends **only on the floor** — `depthKey`, a
  function of `(x, y)`. Add perspective and the ordering starts depending on height, for a gain a plot of a
  function does not want: a surface is read by comparing heights, and making the far side smaller than the near
  side makes that comparison a lie.
- **`Framing.automatic(expr, xName, yName)`** — the existing robust IQR policy over a grid instead of a row. The
  policy was not re-argued for a third axis; the fit is about a spread of numbers and does not know which axis
  they were measured along, which is why `fitY` became `fit(List<Double>)` and both callers share it.

The renderer is still the consumer's (`SurfacePlot` in `calculator-vexel-demo`), and it stays honest by making a
**weaker claim than the curve renderer's**: a box in space projects to a hexagon, what gets drawn is that
hexagon's screen bounding rectangle, so the guarantee is per *cell* rather than per pixel — the rectangle drawn
for a cell contains every point of the surface above it. Painting order is presentation, not proof.

## Landmarks

`Landmarks` finds the places on a curve worth pointing at: roots, the y-intercept, local minima and maxima,
inflections and vertical asymptotes. It runs on the **opposite bias** from the enclosure algebra, deliberately:
the arithmetic **proposes** generously and a narrower test **confirms**, and nothing survives unconfirmed. An
enclosure over-approximates because a plotter can live with over-warning and cannot live with drawing through a
singularity; a landmark under-approximates because a marker is a claim that something is *there*, and one that
is not is a lie the reader has no way to check.

Extrema are roots of `f′`, so `Expr` gained a second operation shaped like the first: **a node differentiates
itself**, `derivative(String parameter) → Optional<Expr>`, defaulting to empty. The interface stays open, empty
propagates through any composite, and the consequence is stated rather than hidden — an expression carrying a
node that cannot differentiate itself reports no extrema. All twelve built-in nodes can. The result is
constant-folded on the way out, which is not tidiness: the finder bisects on `f′` some tens of times per
candidate.

Three things are carried over from the Pontif implementation (`pontif.plot.ptf`, 2026-07-22), where all three
arrived as **bug fixes rather than as design**, and they are the difference between a finder that works and one
that looks like it does:

- **reject any candidate whose bracket straddles a pole.** The finite samples either side of an asymptote fake a
  sign change, so a discontinuity looks exactly like a root and a blow-up exactly like a turning point. Without
  this, `1÷(x²−1)` reports four extrema that are not there.
- **confirm a pole by subdivision, not by the raw enclosure.** Over-estimation leaves a band of unbounded
  columns either side of a real pole; only the one that still holds an unbounded sub-column at 16× finer
  resolution is the pole.
- **bisect on that same subdivision test.** Bisecting the raw enclosure converges on the edge of the smear, which
  labelled a pole at exactly 1 as `0.997`.

And two rules bound it, because `tan(1÷x)` has infinitely many of everything near the origin: a **dense** region
(most sub-columns still unbounded under subdivision) gets no markers, since the block fill already says what
there is to say; and a kind with more than `PER_KIND` of them is dropped **entirely** with the fact reported,
because a truncated set of markers is a lie about which ones are there and an absent set with a notice is not.

## Still ahead

- **Adaptive subdivision** (Tupper's recurse-on-"can't tell"): split a fat or tall column into sub-columns to
  sharpen before falling back to a fill. Turns conservative blocks into crisp curves where the detail is
  actually resolvable, and gives partial-domain tightness for free.
- **Affine arithmetic** (de Figueiredo & Stolfi): track the linear correlations interval arithmetic drops, which
  removes most of the false positives. A change of representation *inside* unit 1, behind the same interface —
  which is what the seam is for.
