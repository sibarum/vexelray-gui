package dev.vexelray.gui.typeset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A composable box: the unit this module lays out. Seven implementations ship built in (below), but the interface
 * is <b>open</b> — an application defines a new kind of composition by implementing it, with no framework change
 * and no privileged access. A structured-text tree is built from these by the calling application; the framework
 * holds no opinion about what markup produced it (docs/typeset.md §2).
 *
 * <p>Notation constructs are not implementations: a fraction, a radical, a matrix are <em>compositions</em>,
 * assembled by {@link Recipes} from the built-ins and parameterised by a {@link Profile}. That factoring is why
 * {@link Attach} alone subsumes superscript, subscript, pre-scripts and over/under limits — the difference is
 * which {@link Slot} a satellite occupies — and why {@link Grid} covers a matrix and a piecewise block, differing
 * only in {@link Align}.
 *
 * <h2>The contract</h2>
 * A box declares four things the framework reads, and writes one method that does the work.
 *
 * <p><b>The only hard rule is containment: do not draw outside the box you declare.</b> {@link #arrange} returns
 * a {@link Placed} carrying a width, an ascent and a descent, and every draw in it must fit inside them. That is
 * the whole of what a parent needs in order to place a child it knows nothing about, and it is the whole of what
 * the framework enforces. Everything else — how children are sized, where they physically land, whether marks are
 * drawn that correspond to no child at all — is the implementation's business. Physical and logical position are
 * free to diverge: a slash struck through a relation, a combining mark, an arrow derived from two other boxes'
 * positions have no logical child position, and the SPI does not pretend otherwise.
 *
 * <p><b>What that means for the tone map.</b> The block-wide tone map (docs/typeset.md §4) fits <em>declared</em>
 * sizes into a legible range. A box that chooses a size itself has opted out for that content and owns its
 * legibility — a scope boundary, not a loophole. The one thing an unpredictable implementation can do is make the
 * block larger than the declared ratios predict, and the size ceiling is already the constraint that yields.
 *
 * <p><b>Purity.</b> {@link #arrange} must be a pure function of its inputs — no side effects, no reliance on how
 * many times it is called. The engine may memoise it, call it twice in a frame, or call it repeatedly while an
 * enclosing box iterates toward a solution. An implementation that iterates internally (a relaxation pass, a
 * measure-then-place) is expected and supported; one that accumulates state between calls is not.
 *
 * <h2>Two properties every box carries</h2>
 * <b>{@link #size()}</b> is declared <b>relative to its parent</b>. The absolute value carries no meaning; only
 * the ratios do. It lives on every box rather than on {@link Run} alone because a whole superscripted subtree
 * scales as a unit — the ratio has to attach to any node, not just a leaf.
 *
 * <p><b>{@link #spacingClass()}</b> is what a {@link Row} looks up when it spaces this box against its neighbour.
 * Universal for the same reason: a fraction sitting in a row spaces as one atom, so a composite needs a class
 * just as much as a glyph run does. The class vocabulary belongs to the {@link Profile.Spacing profile}, not to
 * this file — these are plain indices and a row only ever evaluates {@code table[i][j]}.
 */
public interface Box {

    /** This box's size <b>relative to its parent</b>; 1.0 is "the same as the parent". Never a pixel value. */
    double size();

    /** This box's index into the profile's spacing table, for adjacency spacing inside a {@link Row}. */
    int spacingClass();

    /**
     * This box's children in <b>reading order</b> — the order a reader encounters them, which is not always the
     * order they are drawn. {@link Attach} yields its pre-scripts before its nucleus and its post-scripts after,
     * because that is how the construct is read.
     *
     * <p>The ordering is a published guarantee, not an implementation detail: it is what a future selection walks,
     * and what makes each {@link Run#sourceRef} recoverable in a sensible sequence (docs/typeset.md §8). A box
     * that draws marks of its own with no logical position simply does not list them here.
     */
    List<Box> children();

    /** This box with a different size and spacing class. Recipes use it to scale a whole subtree, and to declare
     *  that a composite spaces as one atom. */
    Box with(double size, int spacingClass);

    /**
     * Lay this box out and return its geometry and draws. Children are laid out through {@code a} — at their
     * declared size, or at one this box picks — as many times as needed.
     *
     * <p>Must honour containment and purity; see the class documentation.
     */
    Placed arrange(Arrangement a);

    /** The default spacing class — the profile's class 0, which every profile is expected to make its ordinary
     *  atom. Used by the terse factories so a caller only names a class when it differs. */
    int ORDINARY = 0;

    /** This box at a different size, keeping its spacing class. */
    default Box withSize(double size) {
        return with(size, spacingClass());
    }

    /** This box in a different spacing class, keeping its size. */
    default Box withClass(int spacingClass) {
        return with(size(), spacingClass);
    }

    // --- the built-in seven ----------------------------------------------------------------------------------
    // Implemented over exactly this interface, with no framework back door — VocabularyTest asserts it, because
    // "an application can write a primitive" is only true if the built-ins are not secretly special.

    /**
     * Glyphs plus a style. {@code face} is a <em>key</em> the profile resolves, never an atlas index — see
     * {@link FaceKeys} for why. A {@code null} face means the profile's {@link Profile#defaultFace()}.
     *
     * <p>{@code sourceRef} is an opaque token the application attaches and the framework never interprets. It is
     * the whole cost of keeping selection possible later: because the app built this tree, it can stamp each run
     * with a range into its own source, and a future selection returns offsets the app already understands rather
     * than a reconstruction. May be {@code null}.
     */
    record Run(double size, int spacingClass, String text, String face, Object sourceRef) implements Box {

        @Override
        public List<Box> children() {
            return List.of();
        }

        @Override
        public Box with(double size, int spacingClass) {
            return new Run(size, spacingClass, text, face, sourceRef);
        }

        @Override
        public Placed arrange(Arrangement a) {
            return Layouts.run(this, a);
        }
    }

    /** A horizontal sequence. Gaps come <b>entirely</b> from the profile's class-pair spacing table, applied to
     *  each adjacent pair — a row is exactly where adjacency spacing is the right model. A construct needing a
     *  specific kern uses {@link Stretch#padBefore}, not a row gap. */
    record Row(double size, int spacingClass, List<Box> items) implements Box {

        @Override
        public List<Box> children() {
            return items;
        }

        @Override
        public Box with(double size, int spacingClass) {
            return new Row(size, spacingClass, items);
        }

        @Override
        public Placed arrange(Arrangement a) {
            return Layouts.row(this, a);
        }
    }

    /**
     * A vertical sequence with an explicit gap between each adjacent pair, and an {@link Anchor} deciding where
     * the composite's own baseline sits. Gaps are explicit here (unlike {@link Row}) because there is no
     * adjacency table for the vertical axis: the space above a fraction bar and the space below it are different
     * numbers from the profile, not a function of what happens to sit there.
     *
     * @param gaps space before each item after the first, so length is {@code items.size() - 1}; a shorter list
     *             is padded with zeroes
     */
    record Stack(double size, int spacingClass, List<Box> items, List<Double> gaps, Anchor anchor) implements Box {

        @Override
        public List<Box> children() {
            return items;
        }

        @Override
        public Box with(double size, int spacingClass) {
            return new Stack(size, spacingClass, items, gaps, anchor);
        }

        @Override
        public Placed arrange(Arrangement a) {
            return Layouts.stack(this, a);
        }
    }

    /**
     * A nucleus with satellites at named corners. One box kind for every attachment: {@link Slot#NE} and
     * {@link Slot#SE} are a superscript and subscript, {@link Slot#NW} and {@link Slot#SW} are pre-scripts,
     * {@link Slot#N} and {@link Slot#S} are limits above and below. Shifts come from the profile, keyed by slot.
     */
    record Attach(double size, int spacingClass, Box nucleus, Map<Slot, Box> satellites) implements Box {

        /** Pre-scripts, then the nucleus, then limits and post-scripts — reading order, not draw order. */
        @Override
        public List<Box> children() {
            List<Box> out = new ArrayList<>();
            add(out, Slot.NW);
            add(out, Slot.SW);
            out.add(nucleus);
            add(out, Slot.N);
            add(out, Slot.S);
            add(out, Slot.NE);
            add(out, Slot.SE);
            return List.copyOf(out);
        }

        private void add(List<Box> out, Slot slot) {
            Box satellite = satellites.get(slot);
            if (satellite != null) {
                out.add(satellite);
            }
        }

        @Override
        public Box with(double size, int spacingClass) {
            return new Attach(size, spacingClass, nucleus, satellites);
        }

        @Override
        public Placed arrange(Arrangement a) {
            return Layouts.attach(this, a);
        }
    }

    /** A filled bar — a fraction bar, a radical vinculum, an underline, a table border. {@code thickness} is in em
     *  of this box's own resolved size; {@link Extent} decides its length. */
    record Rule(double size, int spacingClass, Extent extent, double thickness) implements Box {

        @Override
        public List<Box> children() {
            return List.of();
        }

        @Override
        public Box with(double size, int spacingClass) {
            return new Rule(size, spacingClass, extent, thickness);
        }

        @Override
        public Placed arrange(Arrangement a) {
            return Layouts.rule(this, a);
        }
    }

    /**
     * A glyph scaled to a target extent — a growable delimiter, a surd, an over-brace. Growth is by scaling the
     * ordinary glyph, not by selecting size variants or assembling pieces, which is why no size-variant ranges are
     * needed in the atlas.
     *
     * <p>{@code padBefore}/{@code padAfter} (em) are the construct's own kerns — the tuck under a surd's hook, the
     * padding just inside a delimiter pair. They live here rather than as row gaps so that {@link Row} keeps a
     * single spacing mechanism.
     */
    record Stretch(double size, int spacingClass, String glyph, String face, Extent extent,
                   double padBefore, double padAfter) implements Box {

        @Override
        public List<Box> children() {
            return List.of();
        }

        @Override
        public Box with(double size, int spacingClass) {
            return new Stretch(size, spacingClass, glyph, face, extent, padBefore, padAfter);
        }

        @Override
        public Placed arrange(Arrangement a) {
            return Layouts.stretch(this, a);
        }
    }

    /**
     * Cells in row-major order, with per-column alignment and explicit gaps. A matrix and a piecewise block are
     * the same box kind: a matrix centres its columns ({@link Align#CENTER}), a piecewise block left-aligns them
     * ({@link Align#START}). In the IR this replaces those were two records with the alignment hardcoded in two
     * layout methods.
     */
    record Grid(double size, int spacingClass, List<List<Box>> rows, Align columnAlign,
                double colGap, double rowGap, Anchor anchor) implements Box {

        @Override
        public List<Box> children() {
            List<Box> out = new ArrayList<>();
            for (List<Box> row : rows) {
                out.addAll(row);
            }
            return List.copyOf(out);
        }

        @Override
        public Box with(double size, int spacingClass) {
            return new Grid(size, spacingClass, rows, columnAlign, colGap, rowGap, anchor);
        }

        @Override
        public Placed arrange(Arrangement a) {
            return Layouts.grid(this, a);
        }
    }

    // --- vocabulary shared by the built-ins --------------------------------------------------------------------

    /** How a {@link Rule} or {@link Stretch} takes its size from context. */
    enum Extent {
        /** The glyph's or content's own natural size. */
        NATURAL,
        /** Fill the enclosing container's cross extent — a bar spanning its stack, a delimiter matching its row's
         *  height. This is what makes a fraction bar and a growable paren fall out of the same two box kinds. */
        FILL
    }

    /** Horizontal alignment of a {@link Grid}'s cells within their column. */
    enum Align {
        START, CENTER, END
    }

    /**
     * Where a composite's own baseline sits, for {@link Stack} and {@link Grid}.
     *
     * @param child the child index for {@link Kind#BASELINE_OF}; {@code -1} for the other kinds
     */
    record Anchor(Kind kind, int child) {

        public enum Kind {
            /** Centre the composite on the profile's math axis — a fraction, a matrix. */
            AXIS,
            /** Centre the composite on its own vertical middle. */
            CENTER,
            /** Adopt the baseline of one child — a radicand under its vinculum, text above an underline. */
            BASELINE_OF
        }

        public static Anchor axis() {
            return new Anchor(Kind.AXIS, -1);
        }

        public static Anchor center() {
            return new Anchor(Kind.CENTER, -1);
        }

        public static Anchor baselineOf(int child) {
            return new Anchor(Kind.BASELINE_OF, child);
        }
    }

    // --- terse factories ---------------------------------------------------------------------------------------
    // Size defaults to 1.0 (same as the parent) and class to ORDINARY; use withSize / withClass to change either
    // on a box already built.

    static Box run(String text, String face, int spacingClass) {
        return new Run(1.0, spacingClass, text, face, null);
    }

    static Box run(String text, String face, int spacingClass, Object sourceRef) {
        return new Run(1.0, spacingClass, text, face, sourceRef);
    }

    static Box row(Box... items) {
        return new Row(1.0, ORDINARY, List.of(items));
    }

    static Box row(List<Box> items) {
        return new Row(1.0, ORDINARY, List.copyOf(items));
    }

    static Box stack(List<Box> items, List<Double> gaps, Anchor anchor) {
        return new Stack(1.0, ORDINARY, List.copyOf(items), List.copyOf(gaps), anchor);
    }

    static Box attach(Box nucleus, Map<Slot, Box> satellites) {
        return new Attach(1.0, ORDINARY, nucleus, Map.copyOf(satellites));
    }

    static Box rule(Extent extent, double thickness) {
        return new Rule(1.0, ORDINARY, extent, thickness);
    }

    static Box stretch(String glyph, String face, Extent extent, double padBefore, double padAfter) {
        return new Stretch(1.0, ORDINARY, glyph, face, extent, padBefore, padAfter);
    }

    static Box grid(List<List<Box>> rows, Align columnAlign, double colGap, double rowGap, Anchor anchor) {
        return new Grid(1.0, ORDINARY, List.copyOf(rows), columnAlign, colGap, rowGap, anchor);
    }

    // --- walking ------------------------------------------------------------------------------------------------

    /** Every leaf of {@code box} in reading order — the sequence a selection would run over. Works for any
     *  implementation, built-in or application-defined, because it only uses {@link #children()}. */
    static List<Box> leaves(Box box) {
        List<Box> out = new ArrayList<>();
        collectLeaves(box, out);
        return List.copyOf(out);
    }

    private static void collectLeaves(Box box, List<Box> out) {
        List<Box> kids = box.children();
        if (kids.isEmpty()) {
            out.add(box);
            return;
        }
        for (Box kid : kids) {
            collectLeaves(kid, out);
        }
    }
}
