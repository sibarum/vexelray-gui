package dev.vexelray.gui.typeset;

import dev.vexelray.gui.typeset.Box.Align;
import dev.vexelray.gui.typeset.Box.Anchor;
import dev.vexelray.gui.typeset.Box.Extent;
import dev.vexelray.gui.typeset.Profile.MathClass;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The built-in compositions: how each notation construct is assembled from the built-in {@link Box} kinds, with
 * its numbers supplied by a {@link Profile}. This file is the answer to "where did the ten-case layout switch go"
 * — the constructs still exist, but as recipes over a small vocabulary rather than as cases in an engine.
 *
 * <p>Nothing here computes geometry. A recipe returns a tree; each box arranges itself and never learns the word
 * "fraction". That separation is what lets a profile change the look of every fraction in an application without
 * an engine edit, and what lets prose constructs reuse the identical machinery.
 *
 * <p>Recipes need no extension point of their own: they are ordinary static functions returning a {@link Box}, so
 * an application adds one by writing one. Nothing here is reachable only from inside the framework.
 *
 * <p><b>Recipes are code, profiles are data.</b> "The numerator sits above the bar, separated by
 * {@code fractionGapAbove}" is an algorithm; the gap is a number. Making recipes themselves data would be a
 * layout DSL authored on speculation — the deliberate later step, and only if a real app needs a construct these
 * cannot compose (docs/typeset.md §7).
 */
public final class Recipes {

    private Recipes() {
    }

    // --- structural compositions -----------------------------------------------------------------------------

    /**
     * A fraction: numerator over denominator with a bar between, the whole centred on the profile's axis.
     *
     * <p>Three primitives and no special case. Note what is <em>absent</em>: the reference implementation had a
     * hand-written rule shrinking a fraction's parts when the fraction was already in script style, because its
     * IR carried a scalar scale and had nowhere else to put the clamp. Here the parts take their authored ratios
     * and the block's tone map handles the range globally, so there is nothing to special-case.
     */
    public static Box fraction(Profile p, Box numerator, Box denominator) {
        Profile.Metrics m = p.metrics();
        List<Box> items = List.of(
                numerator.withSize(p.sizes().numerator()),
                Box.rule(Extent.FILL, m.ruleThickness()),
                denominator.withSize(p.sizes().denominator()));
        List<Double> gaps = List.of(m.fractionGapAbove(), m.fractionGapBelow());
        return Box.stack(items, gaps, Anchor.axis()).withClass(MathClass.INNER);
    }

    /** A base with a superscript and/or subscript to its right. Either satellite may be {@code null}. */
    public static Box script(Profile p, Box base, Box superscript, Box subscript) {
        return attachScaled(p, base, p.sizes().script(),
                Slot.NE, superscript, Slot.SE, subscript);
    }

    /** A base with a pre-superscript and/or pre-subscript to its left — an isotope's mass and atomic numbers.
     *  The right-hand counterpart is {@link #script}, and both are the same primitive. */
    public static Box prescript(Profile p, Box base, Box superscript, Box subscript) {
        return attachScaled(p, base, p.sizes().script(),
                Slot.NW, superscript, Slot.SW, subscript);
    }

    /** A base carrying limits directly above and/or below — a big operator with bounds, a limit with its
     *  approach. Same primitive again; only the slots differ. */
    public static Box underOver(Profile p, Box base, Box over, Box under) {
        return attachScaled(p, base, p.sizes().limit(),
                Slot.N, over, Slot.S, under);
    }

    /**
     * A radicand under a surd, with an optional degree index. The surd stretches to the assembled height and the
     * vinculum spans the radicand, both by {@link Extent#FILL} — the same mechanism a growable delimiter uses.
     * The index rides at {@link Slot#NW} of the surd, which is an {@link Box.Attach} like any other.
     */
    public static Box radical(Profile p, Box radicand, Box index) {
        Profile.Metrics m = p.metrics();
        Box surd = Box.stretch("√", p.defaultFace(), Extent.FILL, m.surdKernBefore(), m.surdKernAfter());
        Box head = index == null
                ? surd
                : Box.attach(surd, Map.of(Slot.NW, index.withSize(p.sizes().index())));
        Box covered = Box.stack(
                List.of(Box.rule(Extent.FILL, m.vinculumThickness()), radicand),
                List.of(m.vinculumGap()),
                Anchor.baselineOf(1));
        return Box.row(head, covered).withClass(MathClass.INNER);
    }

    /** Content wrapped in delimiters that grow to its height. */
    public static Box fenced(Profile p, String open, String close, Box content) {
        double pad = p.metrics().delimiterPad();
        return Box.row(
                delimiter(p, open, 0.0, pad),
                content,
                delimiter(p, close, pad, 0.0)).withClass(MathClass.INNER);
    }

    /** A grid of cells in growable delimiters — a matrix, or a column vector with one column per row. Cells are
     *  centred in their column. */
    public static Box matrix(Profile p, List<List<Box>> rows, String open, String close) {
        return griddedIn(p, rows, Align.CENTER, open, close);
    }

    /**
     * Rows stacked under a single tall brace — a piecewise definition. The <em>same primitive</em> as
     * {@link #matrix}: the only differences are that rows are left-aligned and there is no closing delimiter. In
     * the IR this replaces these were two records and two layout methods.
     */
    public static Box cases(Profile p, List<List<Box>> rows) {
        return griddedIn(p, rows, Align.START, "{", null);
    }

    /** {@link #cases} for the common single-column form, one branch per row. */
    public static Box casesOf(Profile p, List<Box> branches) {
        List<List<Box>> rows = new ArrayList<>(branches.size());
        for (Box branch : branches) {
            rows.add(List.of(branch));
        }
        return cases(p, rows);
    }

    // --- math-profile atoms ----------------------------------------------------------------------------------
    // Leaf helpers for the math profile: they pair a face key with a spacing class, which is exactly the pairing
    // an app would otherwise repeat at every call site. An app on a different profile writes its own.

    /** An italic variable. Where the atlas carries no italic-math face the binding degrades to the primary one
     *  and the letter renders upright — legible, and better than a missing glyph (docs/typeset.md P5). */
    public static Box variable(String text) {
        return Box.run(text, Profile.FACE_MATH_ITALIC, MathClass.ORD);
    }

    /** An upright number. */
    public static Box number(String text) {
        return Box.run(text, Profile.FACE_MATH, MathClass.ORD);
    }

    /** A binary operator: {@code + − ⋅}. */
    public static Box operator(String text) {
        return Box.run(text, Profile.FACE_MATH, MathClass.BIN);
    }

    /** A relation: {@code = ≈ → ≤}. */
    public static Box relation(String text) {
        return Box.run(text, Profile.FACE_MATH, MathClass.REL);
    }

    /** A large operator: {@code ∑ ∏ ∫}, or a named one like {@code lim}. */
    public static Box bigOperator(String text) {
        return Box.run(text, Profile.FACE_MATH, MathClass.OP);
    }

    /** Punctuation — a comma in an argument list. */
    public static Box punctuation(String text) {
        return Box.run(text, Profile.FACE_MATH, MathClass.PUNCT);
    }

    /** An upright function name: {@code sin}, {@code log}, {@code det}. */
    public static Box function(String text) {
        return Box.run(text, Profile.FACE_MATH, MathClass.OP);
    }

    // --- shared ------------------------------------------------------------------------------------------------

    /** One {@link Box.Attach} with up to two satellites, each scaled to {@code satelliteSize}; a {@code null}
     *  satellite is simply absent, which is what makes one primitive serve six construct shapes. */
    private static Box attachScaled(Profile p, Box nucleus, double satelliteSize,
                                    Slot firstSlot, Box first, Slot secondSlot, Box second) {
        Map<Slot, Box> satellites = new EnumMap<>(Slot.class);
        if (first != null) {
            satellites.put(firstSlot, first.withSize(satelliteSize));
        }
        if (second != null) {
            satellites.put(secondSlot, second.withSize(satelliteSize));
        }
        if (satellites.isEmpty()) {
            return nucleus;   // nothing attached: the construct degenerates to its nucleus, not to an empty wrapper
        }
        return Box.attach(nucleus, satellites);
    }

    private static Box delimiter(Profile p, String glyph, double padBefore, double padAfter) {
        return Box.stretch(glyph, p.defaultFace(), Extent.FILL, padBefore, padAfter)
                .withClass(padBefore == 0.0 ? MathClass.OPEN : MathClass.CLOSE);
    }

    /** A grid inside delimiters — shared by {@link #matrix} and {@link #cases}. A {@code null} closing glyph
     *  leaves the right side open, which is the only structural difference a piecewise block needs. */
    private static Box griddedIn(Profile p, List<List<Box>> rows, Align align, String open, String close) {
        Profile.Metrics m = p.metrics();
        Box grid = Box.grid(rows, align, m.gridColGap(), m.gridRowGap(), Anchor.axis());
        List<Box> items = new ArrayList<>(3);
        if (open != null) {
            items.add(delimiter(p, open, 0.0, m.gridPad()));
        }
        items.add(grid);
        if (close != null) {
            items.add(delimiter(p, close, m.gridPad(), 0.0));
        }
        return Box.row(items).withClass(MathClass.INNER);
    }
}
