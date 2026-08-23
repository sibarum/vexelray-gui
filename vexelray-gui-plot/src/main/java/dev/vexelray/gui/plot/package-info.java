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
 * <p><b>What is not here yet.</b> This is the substrate only. Choosing a window (the framing policy), turning a
 * column's enclosure into a classified span, joining runs of spans into clipped polylines, and drawing any of it
 * are all still to come; see {@code docs/reliable-plotting.md} for the design they will land against.
 */
package dev.vexelray.gui.plot;
