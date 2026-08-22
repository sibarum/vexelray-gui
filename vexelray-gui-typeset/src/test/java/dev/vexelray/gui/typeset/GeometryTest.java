package dev.vexelray.gui.typeset;

import dev.vexelray.text.AtlasData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The P2 gate (docs/typeset.md §10): the engine, against the <b>real shipped atlas</b> rather than a fixture, so
 * the geometry is measured with the metrics the GUI actually renders from.
 *
 * <p>Most assertions here are <b>relationships</b> — the bar centres on the axis, the numerator clears it by
 * {@code fractionGapAbove}, the surd matches the height it covers — rather than raw coordinates. A relationship
 * survives a profile being retuned, which a golden number does not, and it says what the layout is supposed to
 * mean instead of merely what it currently produces. A few exact goldens are kept as regression tripwires where
 * no relationship captures the intent.
 *
 * <p>{@link #everyConstructStaysInsideItsOwnBox()} is the one that matters most: containment is the single rule
 * the framework enforces on a box, and until this test existed it was enforced by prose alone.
 */
class GeometryTest {

    private static final Profile P = Profile.math();
    private static final double BASE = 16.0;
    /** No face bindings: every key resolves to face 0, which is the documented degradation before P5's math face
     *  and is exactly how the module behaves on today's atlas. */
    private static final AtlasData ATLAS = AtlasData.loadFromResource("/dev/vexelray/text/atlas/primary.json");
    private static final Typeset ENGINE = new Typeset(ATLAS, P, FaceKeys.single());

    // --- containment: the one rule the framework enforces --------------------------------------------------------

    @Test
    void everyConstructStaysInsideItsOwnBox() {
        for (var entry : constructs()) {
            Placed placed = ENGINE.layout(entry.box(), BASE);
            for (Placed.Draw draw : placed.draws()) {
                Bounds b = boundsOf(draw);
                assertTrue(b.left >= -1e-6 && b.right <= placed.width() + 1e-6,
                        () -> entry.name() + ": draw spans x " + b.left + ".." + b.right
                                + " outside a box " + placed.width() + " wide");
                assertTrue(b.top >= -placed.ascent() - 1e-6 && b.bottom <= placed.descent() + 1e-6,
                        () -> entry.name() + ": draw spans y " + b.top + ".." + b.bottom
                                + " outside a box of ascent " + placed.ascent() + " descent " + placed.descent());
            }
        }
    }

    @Test
    void everyConstructReportsANonNegativeBox() {
        for (var entry : constructs()) {
            Placed p = ENGINE.layout(entry.box(), BASE);
            assertTrue(p.width() >= 0, () -> entry.name() + " reported a negative width");
            assertTrue(p.height() > 0, () -> entry.name() + " reported no height at all");
            assertTrue(!p.draws().isEmpty(), () -> entry.name() + " drew nothing");
        }
    }

    // --- the two-pass that growable content needs -----------------------------------------------------------------

    @Test
    void aFractionBarSpansTheWholeFraction() {
        Placed p = ENGINE.layout(Recipes.fraction(P, Recipes.variable("a"), Recipes.number("1234")), BASE);
        Placed.Bar bar = onlyBar(p);

        assertEquals(0.0, bar.x(), 1e-9);
        assertEquals(p.width(), bar.width(), 1e-6,
                "the bar fills the stack's cross extent, which is what Extent.FILL means in a Stack");
    }

    @Test
    void aDelimiterGrowsToTheHeightOfWhatItEncloses() {
        Box tall = Recipes.fraction(P, Recipes.variable("a"), Recipes.variable("b"));
        Placed shortFence = ENGINE.layout(Recipes.fenced(P, "(", ")", Recipes.variable("x")), BASE);
        Placed tallFence = ENGINE.layout(Recipes.fenced(P, "(", ")", tall), BASE);

        assertTrue(tallFence.height() > shortFence.height() * 1.4,
                "a fraction inside parens must make the parens taller — Extent.FILL in a Row is the height");

// The delimiter must COVER the content, not merely match its height. A fraction is not symmetric about
        // the axis, and a delimiter is centred on it, so a paren merely as tall as its content would poke out
        // above and fall short below. Covering is the property worth asserting.
        Placed content = ENGINE.layout(tall, BASE);
        assertTrue(tallFence.ascent() >= content.ascent() - 1e-6, "the fence reaches the top of its content");
        assertTrue(tallFence.descent() >= content.descent() - 1e-6, "and the bottom of it");

        // And it is symmetric about the axis, which is what makes a pair of them look level.
        double axis = P.metrics().axisHeight() * BASE;
        assertEquals(tallFence.ascent() - axis, tallFence.descent() + axis, 1e-6,
                "a grown delimiter straddles the axis evenly");
    }

    @Test
    void aVinculumSpansItsRadicandAndNotTheSurd() {
        Placed p = ENGINE.layout(Recipes.radical(P, Recipes.variable("x"), null), BASE);
        Placed.Bar vinculum = onlyBar(p);

        assertTrue(vinculum.x() > 0, "the vinculum starts after the surd, not at the radical's left edge");
        assertEquals(p.width(), vinculum.x() + vinculum.width(), 1e-6,
                "and runs to the end of the radicand it covers");
    }

    // --- relationships the profile is supposed to produce -----------------------------------------------------------

    @Test
    void aFractionBarSitsOnTheMathAxis() {
        Placed p = ENGINE.layout(Recipes.fraction(P, Recipes.variable("a"), Recipes.variable("b")), BASE);
        Placed.Bar bar = onlyBar(p);
        double centre = bar.y() + bar.height() / 2;

        assertEquals(-P.metrics().axisHeight() * BASE, centre, 0.25,
                "the bar's centre is the axis, which is what a fraction is anchored on");
    }

    @Test
    void aFractionSeparatesItsPartsByTheProfilesGaps() {
        Placed p = ENGINE.layout(Recipes.fraction(P, Recipes.variable("a"), Recipes.variable("b")), BASE);
        Placed.Bar bar = onlyBar(p);
        List<Bounds> glyphs = glyphBounds(p);
        assertEquals(2, glyphs.size());
        Bounds numerator = glyphs.get(0);
        Bounds denominator = glyphs.get(1);

        assertEquals(P.metrics().fractionGapAbove() * BASE, bar.y() - numerator.bottom, 0.5,
                "numerator bottom clears the bar by fractionGapAbove");
        assertEquals(P.metrics().fractionGapBelow() * BASE, denominator.top - (bar.y() + bar.height()), 0.5,
                "and the denominator's top clears it by fractionGapBelow");
    }

    @Test
    void aSuperscriptIsRaisedByTheProfilesShift() {
        Placed p = ENGINE.layout(Recipes.script(P, Recipes.variable("x"), Recipes.number("2"), null), BASE);
        List<Placed.Glyphs> runs = glyphRuns(p);
        assertEquals(2, runs.size());

        assertEquals(0.0, runs.get(0).y(), 1e-9, "the nucleus sits on the baseline");
        assertEquals(-P.metrics().shiftUp() * BASE, runs.get(1).y(), 1e-9,
                "and the satellite's baseline is shiftUp above it");
        assertTrue(runs.get(1).size() < runs.get(0).size(), "drawn smaller, by the profile's script ratio");
    }

    @Test
    void scriptsAndPrescriptsMirrorEachOther() {
        Placed post = ENGINE.layout(Recipes.script(P, Recipes.variable("C"), Recipes.number("14"), null), BASE);
        Placed pre = ENGINE.layout(Recipes.prescript(P, Recipes.variable("C"), Recipes.number("14"), null), BASE);

// Identify the runs by content, not by index: an Attach emits its nucleus first whichever side the
        // satellite is on, so draw order says nothing about horizontal order. That divergence is the design.
        assertTrue(runNamed(pre, "14").x() < runNamed(pre, "C").x(), "a pre-script sits before its nucleus");
        assertTrue(runNamed(post, "14").x() > runNamed(post, "C").x(), "and a post-script after it");
        assertEquals(post.ascent(), pre.ascent(), 1e-9, "the vertical geometry is identical");
    }

    @Test
    void limitsAreCentredOverAndUnderTheirOperator() {
        Box sum = Recipes.underOver(P, Recipes.bigOperator("N"), Recipes.number("9"), Recipes.number("0"));
        Placed p = ENGINE.layout(sum, BASE);
        List<Bounds> glyphs = glyphBounds(p);
        assertEquals(3, glyphs.size());

        double centre = p.width() / 2;
        for (Bounds g : glyphs) {
            assertEquals(centre, (g.left + g.right) / 2, 0.5, "each part is centred on the operator's column");
        }
    }

    @Test
    void rowSpacingComesFromThePairwiseTable() {
        // a b  (two ordinary atoms, gap 0) versus  a + b  where the operator brings BIN spacing on both sides.
        Placed tight = ENGINE.layout(Box.row(Recipes.variable("a"), Recipes.variable("b")), BASE);
        Placed spaced = ENGINE.layout(
                Box.row(Recipes.variable("a"), Recipes.operator("+"), Recipes.variable("b")), BASE);

        double plusWidth = ENGINE.layout(Recipes.operator("+"), BASE).width();
        double expected = 2 * P.spacing().gap(Profile.MathClass.ORD, Profile.MathClass.BIN) * BASE;
        assertEquals(expected, spaced.width() - tight.width() - plusWidth, 1e-6,
                "the extra width is exactly two ORD/BIN gaps from the table");
    }

    @Test
    void aMatrixCentresItsColumnsAndAPiecewiseBlockDoesNot() {
        List<List<Box>> cells = List.of(
                List.of(Recipes.number("1"), Recipes.number("2")),
                List.of(Recipes.number("300"), Recipes.number("4")));

Placed matrix = ENGINE.layout(Recipes.matrix(P, cells, "[", "]"), BASE);
        Placed cases = ENGINE.layout(Recipes.cases(P, cells), BASE);

        // "1" sits above "300" in the same column. Centred, the narrow one is indented; left-aligned, the two
        // share a left edge. Found by content rather than by index, since a matrix also draws its delimiters.
        assertTrue(runNamed(matrix, "1").x() > runNamed(matrix, "300").x() + 0.5,
                "a matrix centres a narrow cell in its column");
        assertEquals(runNamed(cases, "300").x(), runNamed(cases, "1").x(), 1e-6,
                "a piecewise block aligns them to the left");
    }

    // --- the engine's own guarantees ---------------------------------------------------------------------------------

    @Test
    void sizesComeFromTheToneMapNotFromRawRatios() {
        Box deep = Recipes.script(P, Recipes.variable("x"),
                Recipes.script(P, Recipes.variable("y"), Recipes.number("2"), null), null);
        ToneMap tone = ENGINE.toneMapFor(deep, BASE);
        Placed p = ENGINE.layout(deep, BASE);

        for (Placed.Glyphs run : glyphRuns(p)) {
            assertTrue(run.size() >= P.tone().floorPx() - 1e-6,
                    "no run is drawn below the legibility floor: " + run.text() + " at " + run.size() + "px");
        }
        assertEquals(tone.px(1.0), glyphRuns(p).get(0).size(), 1e-9, "the outermost run is the block's root size");
    }

    @Test
    void anApplicationDefinedBoxLaysOutLikeAnyOther() {
        Box notEqual = new dev.vexelray.gui.typeset.app.Superimposed(
                Recipes.relation("="), "/", Profile.FACE_MATH);
        Placed p = ENGINE.layout(Box.row(Recipes.variable("a"), notEqual, Recipes.variable("b")), BASE);

        assertEquals(4, glyphRuns(p).size(), "a, =, the superimposed slash, and b");
        for (Placed.Draw draw : p.draws()) {
            Bounds b = boundsOf(draw);
            assertTrue(b.left >= -1e-6 && b.right <= p.width() + 1e-6, "and it honours containment like the rest");
        }
    }

    @Test
    void aBoxThatLaysItselfOutFailsLoudly() {
        Box recursive = new Box() {
            @Override
            public double size() {
                return 1;
            }

            @Override
            public int spacingClass() {
                return ORDINARY;
            }

            @Override
            public List<Box> children() {
                return List.of();
            }

            @Override
            public Box with(double size, int spacingClass) {
                return this;
            }

            @Override
            public Placed arrange(Arrangement a) {
                return a.lay(this);
            }
        };
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ENGINE.layout(recursive, BASE));
        assertTrue(e.getMessage().contains("laying itself out"), "and says what went wrong");
    }

    @Test
    void theDrawListReadsThroughASinkWithoutAnyoneSwitching() {
        Placed p = ENGINE.layout(Recipes.fraction(P, Recipes.variable("a"), Recipes.variable("b")), BASE);
        List<String> seen = new ArrayList<>();
        p.emitTo(new Placed.Sink() {
            @Override
            public void glyphs(String text, String face, double x, double y, double size, Object sourceRef) {
                seen.add("glyphs:" + text);
            }

            @Override
            public void bar(double x, double y, double width, double height) {
                seen.add("bar");
            }
        });
        // Emission follows the stack.s item order — numerator, bar, denominator — which is draw order, not the
        // reading order children() publishes. The two are allowed to differ and here they do.
        assertEquals(List.of("glyphs:a", "bar", "glyphs:b"), seen);
    }

    // --- exact goldens, as tripwires ----------------------------------------------------------------------------------

    @Test
    void aBareRunMatchesTheAtlasAdvance() {
        Placed p = ENGINE.layout(Recipes.variable("x"), BASE);
        double advance = ENGINE.glyphOf(Profile.FACE_MATH_ITALIC, 'x').advance() * BASE;

        assertEquals(advance, p.width(), 1e-9, "a lone run is exactly its advance — no inset, no padding");
        assertEquals(1, p.draws().size());
    }

    @Test
    void anEmptyRunOccupiesNothing() {
        Placed p = ENGINE.layout(Box.run("", null, Box.ORDINARY), BASE);
        assertEquals(0, p.width(), 1e-9);
        assertTrue(p.draws().isEmpty());
    }

    // --- helpers ---------------------------------------------------------------------------------------------------------

    private record Named(String name, Box box) {
    }

    private static List<Named> constructs() {
        List<List<Box>> cells = List.of(
                List.of(Recipes.variable("a"), Recipes.variable("b")),
                List.of(Recipes.variable("c"), Recipes.variable("d")));
        return List.of(
                new Named("run", Recipes.variable("x")),
                new Named("row", Box.row(Recipes.variable("a"), Recipes.operator("+"), Recipes.number("1"))),
                new Named("fraction", Recipes.fraction(P, Recipes.variable("a"), Recipes.variable("b"))),
                new Named("script", Recipes.script(P, Recipes.variable("x"), Recipes.number("2"),
                        Recipes.variable("i"))),
                new Named("prescript", Recipes.prescript(P, Recipes.variable("C"), Recipes.number("14"),
                        Recipes.number("6"))),
                new Named("underOver", Recipes.underOver(P, Recipes.bigOperator("N"), Recipes.variable("n"),
                        Recipes.number("1"))),
                new Named("radical", Recipes.radical(P, Recipes.variable("x"), Recipes.number("3"))),
                new Named("fenced", Recipes.fenced(P, "(", ")",
                        Recipes.fraction(P, Recipes.variable("a"), Recipes.variable("b")))),
                new Named("matrix", Recipes.matrix(P, cells, "[", "]")),
                new Named("cases", Recipes.casesOf(P, List.of(Recipes.number("0"), Recipes.number("1")))));
    }

    /** A draw's real extent, measured with the same atlas the engine laid it out from. */
    private record Bounds(double left, double right, double top, double bottom) {
    }

    private static Bounds boundsOf(Placed.Draw draw) {
        if (draw instanceof Placed.Bar bar) {
            return new Bounds(bar.x(), bar.x() + bar.width(), bar.y(), bar.y() + bar.height());
        }
        Placed.Glyphs g = (Placed.Glyphs) draw;
        double advance = 0;
        double top = 0;
        double bottom = 0;
        for (int i = 0; i < g.text().length(); ) {
            int cp = g.text().codePointAt(i);
            i += Character.charCount(cp);
            Arrangement.Glyph m = ENGINE.glyphOf(g.face(), cp);
            advance += m.advance();
            top = Math.max(top, m.top());
            bottom = Math.min(bottom, m.bottom());
        }
        return new Bounds(g.x(), g.x() + advance * g.size(),
                g.y() - top * g.size(), g.y() - bottom * g.size());
    }

    /** The one glyph run whose text is {@code text} — draws are found by content, never by index, because draw
     *  order and reading order are allowed to differ. */
    private static Placed.Glyphs runNamed(Placed p, String text) {
        for (Placed.Glyphs g : glyphRuns(p)) {
            if (g.text().equals(text)) {
                return g;
            }
        }
        throw new AssertionError("no run drawing \"" + text + "\"");
    }

    private static List<Placed.Glyphs> glyphRuns(Placed p) {
        List<Placed.Glyphs> out = new ArrayList<>();
        for (Placed.Draw d : p.draws()) {
            if (d instanceof Placed.Glyphs g) {
                out.add(g);
            }
        }
        return out;
    }

    private static List<Bounds> glyphBounds(Placed p) {
        List<Bounds> out = new ArrayList<>();
        for (Placed.Glyphs g : glyphRuns(p)) {
            out.add(boundsOf(g));
        }
        return out;
    }

    private static Placed.Bar onlyBar(Placed p) {
        Placed.Bar found = null;
        for (Placed.Draw d : p.draws()) {
            if (d instanceof Placed.Bar bar) {
                assertTrue(found == null, "expected exactly one bar");
                found = bar;
            }
        }
        assertTrue(found != null, "expected a bar");
        return found;
    }
}
