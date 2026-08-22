package dev.vexelray.gui.typeset;

/**
 * The services a {@link Box} gets while it arranges itself: laying children out, reading the profile, and looking
 * up glyph metrics. The engine implements this; a box calls it.
 *
 * <p><b>The box drives.</b> Children are not handed over pre-arranged — they are laid out on demand, as many times
 * as the box likes, at their declared size or at one the box picks. That is what makes measure-then-place work, and
 * what lets a box iterate toward a solution: a relaxation pass over a set of children is an ordinary use of
 * {@link #lay}, not a special case. A force-directed graph is one box whose {@code arrange} runs its own solver
 * and returns a truthful bounding box.
 *
 * <p><b>{@link #lay} is pure.</b> It is a function of {@code (box, size)} and nothing else, so the engine may
 * memoise it and a caller must not depend on how many times it runs or in what order. Iterating is expected;
 * accumulating state across calls is not.
 *
 * <p>The engine bounds recursion depth, so a box that lays itself out fails loudly rather than hanging the GUI
 * thread.
 */
public interface Arrangement {

    /** Lay {@code child} out at its own declared {@link Box#size()}. */
    Placed lay(Box child);

    /** Lay {@code child} out at {@code size} instead of its declared one — for a box making its own sizing
     *  decision. Content sized this way is outside the block's tone map and its legibility is the caller's
     *  responsibility (see {@link Box}). */
    Placed lay(Box child, double size);

    /**
     * The block's solved size transfer applied to an authored ratio — the same function the tone map used for
     * every declared size in this block (docs/typeset.md §4).
     *
     * <p>Offered, never imposed. A box that picks its own sizes can route them through this to stay consistent
     * with the rest of the block, or ignore it entirely.
     */
    double toneMapped(double authoredSize);

    /** The profile in force for this block — spacing table, metrics, size ratios, face keys. */
    Profile profile();

    /** The math axis height at this box's resolved size: where a fraction bar and a growable delimiter centre. */
    double axis();

    /** Metrics for one glyph of one face, for a box placing marks of its own. Never {@code null}: an absent glyph
     *  resolves to the atlas's missing-glyph box, so a box always has real numbers to work with. */
    Glyph glyph(String faceKey, int codepoint);

    /**
     * One glyph's metrics, in em.
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
