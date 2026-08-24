# Reliable plotting

`vexelray-gui-plot`. Ported from the Pontif framework's `pontif.algebra` interval evaluator
(`docs/reliable-plotting.md` there), rewritten into this repo's idiom. **Four of the five units are here: the
enclosure algebra, the expression vocabulary that walks onto it, the frame and the classification of a column
against it, and the policy that picks a window. The fifth — drawing — is not, and does not need to be: the
first consumer, `calculator-vexel-demo`, renders spans out of ordinary boxes.**

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
2. **The expression walk** (`Expr`) — nodes onto (1). No plotting dependencies.
3. **Classification** (`Frame`, `Span`) — `(enclosure, frame) → column kind`: a clipped stretch of curve, a
   painted pole, or nothing. A pure function, and the whole of it is clipping.
4. **Framing policy** (`Framing`) — `expression → frame`. Where the window comes from is as much preference as
   mathematics, so it is isolated: swapping the feel of auto-framing must not touch evaluation or rendering.
5. **Renderer** — spans to pixels, knowing nothing of algebra. Lives in the consumer, and the first one is
   `calculator-vexel-demo`. Not here, because a renderer needs a node vocabulary and this module has no
   dependencies at all.

**The diagonal line that was blocking (5) turned out not to exist.** The note this section used to carry said
the renderer was waiting on a node kind that could draw one, `NodeKind` being `{BOX, TEXT}`. But a reliable plot
has no diagonals in it: a column classifies to a *vertical span*, and a vertical span is a box. Point sampling
needs polylines because it joins samples it did not evaluate between; evaluating over the column removes both
the need to join and the thing that was blocked on. `-core` did not have to widen.

**And the cheap-viewport property falls out of unit 3 being separate from unit 1.** An enclosure is in plot
space, so it does not depend on the frame at all: moving in **y**, at any zoom, is re-classification and
re-evaluates nothing, and moving in **x** re-evaluates only the columns that came into view. A consumer that
keeps a map from column-of-x to enclosure can pan and zoom without recomputing what it already knows — which is
what makes the difference between a plot you explore and a plot you wait for.

## Still ahead

- **Adaptive subdivision** (Tupper's recurse-on-"can't tell"): split a fat or tall column into sub-columns to
  sharpen before falling back to a fill. Turns conservative blocks into crisp curves where the detail is
  actually resolvable, and gives partial-domain tightness for free.
- **Affine arithmetic** (de Figueiredo & Stolfi): track the linear correlations interval arithmetic drops, which
  removes most of the false positives. A change of representation *inside* unit 1, behind the same interface —
  which is what the seam is for.
