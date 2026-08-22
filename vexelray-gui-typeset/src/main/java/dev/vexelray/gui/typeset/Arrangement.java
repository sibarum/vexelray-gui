package dev.vexelray.gui.typeset;

/**
 * The services a {@link Box} gets while it arranges itself: laying children out, its own resolved size, the
 * container's cross extent, the profile, and glyph metrics. The engine implements this; a box calls it.
 *
 * <p><b>The box drives.</b> Children are not handed over pre-arranged — they are laid out on demand, as many times
 * as the box likes, at their declared size or at one the box picks. That is what makes measure-then-place work, and
 * what lets a box iterate toward a solution: a relaxation pass over a set of children is an ordinary use of
 * {@link #lay}, not a special case. A force-directed graph is one box whose {@code arrange} runs its own solver
 * and returns a truthful bounding box.
 *
 * <p><b>{@link #lay} is pure.</b> It is a function of {@code (box, size)} and nothing else, so the engine may
 * memoise it and a caller must not depend on how many times it runs or in what order. Iterating is expected;
 * accumulating state across calls is not. (The current engine does not memoise — the contract is what keeps that
 * option open, not a promise that it happens.)
 *
 * <p>The engine bounds recursion depth, so a box that lays itself out fails loudly rather than hanging the GUI
 * thread.
 */
public interface Arrangement {

    /** Lay {@code child} out at its own declared {@link Box#size()}. */
    Placed lay(Box child);

    /**
     * Lay {@code child} out at {@code size} instead of its declared one — still a ratio relative to <em>this</em>
     * box, not a pixel value. For a box making its own sizing decision: content sized this way is outside the
     * block's tone map and its legibility is the caller's responsibility (see {@link Box}).
     */
    Placed lay(Box child, double size);

    /**
     * Lay {@code child} out at its declared size, telling it the cross extent it may grow into — the second half
     * of a container's two-pass. A child that does not grow ignores it, so a container may call this for any
     * child; {@link Box#fillsCrossExtent()} is what lets it skip the ones for which the answer cannot change.
     */
    Placed layFilling(Box child, double crossExtent);

    /**
     * This box's own resolved size in pixels — what every em-valued profile metric and glyph advance multiplies
     * by. The tone map already applied, so this is the real rendered size, not an authored ratio.
     */
    double sizePx();

    /**
     * The container's extent across its main axis, in pixels, or {@code 0} when it is not yet known.
     *
     * <p>This is how a growable box learns what to grow to. A {@link Box.Row} runs its children twice: once with
     * no cross extent, to discover how tall the row is, and once with the height it found. A {@link Box.Stack}
     * does the same with width. A box that answers {@code true} to {@link Box#fillsCrossExtent()} is the only kind
     * that gets laid a second time, so the two-pass costs nothing for the rest of the tree.
     *
     * <p>Cross means perpendicular to the container's flow: <b>height inside a Row, width inside a Stack.</b> That
     * one definition is what makes a fraction bar spanning its stack and a delimiter matching its row's height the
     * same mechanism.
     */
    double crossExtent();

    /**
     * The block's solved size transfer applied to an authored ratio, in pixels (docs/typeset.md §4).
     *
     * <p>Offered, never imposed. A box that picks its own sizes can route them through this to stay consistent
     * with the rest of the block, or ignore it entirely.
     */
    double toneMapped(double authoredSize);

    /** The profile in force for this block — spacing table, metrics, size ratios, face keys. */
    Profile profile();

    /** The math axis height at this box's resolved size, in pixels: where a fraction bar and a growable delimiter
     *  centre. */
    double axis();

    /** Metrics for one glyph of one face, for a box placing marks of its own. Never {@code null}: an absent glyph
     *  resolves to the atlas's missing-glyph box, so a box always has real numbers to work with. */
    Glyph glyph(String faceKey, int codepoint);

    /**
     * One glyph's metrics, in <b>em</b> — multiply by {@link #sizePx()} for pixels.
     *
     * @param advance horizontal advance
     * @param top     highest extent above the baseline, positive
     * @param bottom  lowest extent below the baseline, negative
     */
    record Glyph(double advance, double top, double bottom) {

        public double height() {
            return top - bottom;
        }
    }
}
