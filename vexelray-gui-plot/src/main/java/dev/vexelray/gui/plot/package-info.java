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
 * touching the module. A node also <b>differentiates itself</b>, which is the same shape again and is what
 * {@link dev.vexelray.gui.plot.Landmarks} stands on.
 *
 * <p><b>The region is a value.</b> {@link dev.vexelray.gui.plot.Cell} binds each parameter to the interval it
 * ranges over, so the substrate is not restricted to one variable: a curve is enclosed over a column of x, a
 * surface over a cell of {@code (x, y)}, and {@code enclose(Interval)} is the convenience that binds every name
 * to the same column. One variable is the degenerate case of two, rather than a separate evaluator to be kept in
 * agreement with the other.
 *
 * <p>Above the substrate sit the units that turn an enclosure into a picture without knowing what a picture is
 * made of. {@link dev.vexelray.gui.plot.Frame} is the rectangle of plot space on show and
 * {@link dev.vexelray.gui.plot.Volume} the box; both own the conversions everything above them needs and nothing
 * below them should have. {@link dev.vexelray.gui.plot.Span} classifies one region against a visible extent into
 * the three things it can be — a clipped stretch, a painted pole, or nothing — and hands it to a renderer through
 * a sink, so the set of operations a renderer implements stays closed while the set of answers stays open. It is
 * the <em>same</em> three answers whether the extent is a frame's y or a volume's z.
 * {@link dev.vexelray.gui.plot.Framing} chooses the window, and is deliberately the one unit here that is
 * preference rather than mathematics; {@link dev.vexelray.gui.plot.Camera} is where a surface is looked at from,
 * and has no perspective on purpose — see its own note.
 *
 * <p>The split between evaluation and classification is what makes a viewport cheap to move: an enclosure is in
 * plot space and knows nothing of the frame, so panning and zooming in <b>y</b> is re-classification alone and
 * re-evaluates nothing, while panning in <b>x</b> re-evaluates only the columns that came into view. A consumer
 * that caches enclosures per column of x gets both — and a surface gets the stronger version of the same thing,
 * since an enclosure knows nothing of the camera either and turning the picture evaluates nothing at all.
 *
 * <p>{@link dev.vexelray.gui.plot.Landmarks} is the one unit here whose bias runs the other way. Everything else
 * over-approximates, because a plot that over-warns is conservative; a landmark is a claim that something is
 * <em>there</em>, so the arithmetic proposes and a narrower test disposes, and nothing survives unconfirmed.
 *
 * <p><b>What is not here yet.</b> Drawing. A renderer needs a node vocabulary, which is a dependency this module
 * does not have and a decision that has not been taken; {@code calculator-vexel-demo} carries two built out of
 * boxes, which is all a column-wise plot or a cell-wise surface ever needs. Adaptive subdivision and affine
 * arithmetic are still ahead too — see {@code docs/reliable-plotting.md}.
 */
package dev.vexelray.gui.plot;
