/**
 * Structured, non-editable rich text: an open set of composable boxes ({@link dev.vexelray.gui.typeset.Box}, seven
 * built in), notation constructs assembled from them by {@link dev.vexelray.gui.typeset.Recipes}, and per-content
 * display parameters supplied as compile-time data by a {@link dev.vexelray.gui.typeset.Profile}.
 *
 * <p>An application defines a new kind of composition by implementing {@code Box} — a force-directed graph, an
 * overlay whose mark corresponds to no child, a table with spanning cells. <b>The one rule is containment: do not
 * draw outside the box you declare.</b> Everything else, including whether physical position follows logical
 * structure at all, is the implementation's business.
 *
 * <p>The module holds <b>no opinion about markup</b>. The application parses whatever format it likes and builds
 * the IR; keeping the parser out is what lets new formats be pioneered without touching the framework, and it is
 * also what makes selection recoverable later — the app can stamp each run with a reference into its own source
 * ({@link dev.vexelray.gui.typeset.Box.Run#sourceRef()}), so an eventual selection returns offsets the app
 * already understands.
 *
 * <p>A typeset block is an <b>atom</b> in flex layout, never content inside a text field. That boundary is what
 * keeps {@code TextField} fast: its layout stays one-dimensional and deterministic, and arbitrary
 * two-dimensional composition never enters it.
 *
 * <p>See {@code docs/typeset.md} for the design, including the log-space tone map that fits a block's authored
 * size ratios into a legible pixel range.
 */
package dev.vexelray.gui.typeset;
