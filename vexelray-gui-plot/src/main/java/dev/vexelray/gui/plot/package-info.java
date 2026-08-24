/**
 * Reliable graphing's substrate: evaluate an expression over a <b>column</b> of x and get back an
 * {@link dev.vexelray.gui.plot.Enclosure} that provably contains every value it takes there.
 *
 * <p>Point sampling cannot be made honest. A curve drawn by evaluating at N points and joining them will draw a
 * confident line straight through an asymptote, and no sample count fixes it: {@code tan(1/x)} has infinitely
 * many poles in any neighbourhood of the origin, so between any two adjacent samples there can be thousands.
 * Evaluating over the column instead — interval arithmetic, the technique behind Tupper's reliable graphing —
 * gives an answer that is allowed to be vague but is never wrong. A pole is found by the arithmetic (a divisor
 * range containing zero), not by a solver, and a renderer built on it can honestly say "there is detail here
 * finer than a pixel" instead of inventing a line.
 *
 * <p>Three types carry the three answers — {@link dev.vexelray.gui.plot.Interval},
 * {@link dev.vexelray.gui.plot.Unbounded}, {@link dev.vexelray.gui.plot.Undefined} — and each carries the
 * operations, so the propagation law is dispatch rather than a switch. {@link dev.vexelray.gui.plot.Expr} is
 * likewise open: twelve nodes are built in, a node encloses itself, and an application adds a thirteenth without
 * touching the module.
 *
 * <p>Above the substrate sit the two units that turn an enclosure into a picture without knowing what a picture
 * is made of. {@link dev.vexelray.gui.plot.Frame} is the rectangle of plot space on show, and owns the two
 * conversions everything above it needs: which column of x a pixel column covers, and where a y falls down the
 * height. {@link dev.vexelray.gui.plot.Span} classifies one column against a frame into the three things a
 * column can be — a clipped stretch of curve, a painted pole, or nothing — and hands it to a renderer through a
 * sink, so the set of operations a renderer implements stays closed while the set of answers stays open.
 * {@link dev.vexelray.gui.plot.Framing} chooses the window, and is deliberately the one unit here that is
 * preference rather than mathematics.
 *
 * <p>The split between evaluation and classification is what makes a viewport cheap to move: an enclosure is in
 * plot space and knows nothing of the frame, so panning and zooming in <b>y</b> is re-classification alone and
 * re-evaluates nothing, while panning in <b>x</b> re-evaluates only the columns that came into view. A consumer
 * that caches enclosures per column of x gets both.
 *
 * <p><b>What is not here yet.</b> Drawing. A renderer needs a node vocabulary, which is a dependency this module
 * does not have and a decision that has not been taken; {@code calculator-vexel-demo} carries one built out of
 * boxes, which is all a column-wise plot ever needs. Adaptive subdivision and affine arithmetic are still ahead
 * too — see {@code docs/reliable-plotting.md}.
 */
package dev.vexelray.gui.plot;
