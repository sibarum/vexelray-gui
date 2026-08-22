package dev.vexelray.gui.typeset;

import dev.vexelray.gui.typeset.Profile.MathClass;
import dev.vexelray.gui.typeset.app.Superimposed;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The P0 gate (docs/typeset.md §10): <b>every target construct composes from the built-in box kinds</b>, and the
 * framework has <b>no privileged box kinds</b> — an application can write one over the same public SPI.
 *
 * <p>This is the test that can invalidate the design, which is why it comes before any engine exists. The second
 * half is the stronger claim of the two, and it is the one that replaced counting subclasses: a count only says
 * the vocabulary did not grow, whereas {@link #anApplicationDefinedBoxIsAFirstClassCitizen()} says the SPI is
 * genuinely sufficient to write a box kind with. If a built-in needs something an application cannot reach, that
 * is a finding about the SPI, and it surfaces here rather than the first time an app tries.
 */
class VocabularyTest {

    private static final Profile P = Profile.math();

    /** The kinds that ship built in. The set is open — this is what the framework provides, not a limit. */
    private static final Set<String> BUILT_INS =
            Set.of("Run", "Row", "Stack", "Attach", "Rule", "Stretch", "Grid");

    @Test
    void theFrameworkHasNoPrivilegedBoxKinds() {
        // Every method the engine may call on a box is public API. Were any of them package-private, only a class
        // inside dev.vexelray.gui.typeset could be a box, and the extension point would be decorative.
        for (Method m : Box.class.getDeclaredMethods()) {
            if (m.isSynthetic() || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            assertTrue(Modifier.isPublic(m.getModifiers()),
                    () -> "Box." + m.getName() + " must be public: an application implements this interface from "
                            + "its own package, so a non-public member would make the built-ins privileged.");
        }
        assertFalse(Box.class.isSealed(),
                "Box is deliberately open — a sealed interface would close the vocabulary to this file "
                        + "(docs/typeset.md §3).");
    }

    @Test
    void anApplicationDefinedBoxIsAFirstClassCitizen() {
        // Superimposed lives in dev.vexelray.gui.typeset.app — a different package, using only public API. It
        // also does something no built-in can: it draws a mark that is not a child, at a position derived from
        // the nucleus's measured box.
        Box notEqual = new Superimposed(Recipes.relation("="), "/", Profile.FACE_MATH);

        // It composes into a built-in the same as anything else...
        Box expression = Box.row(Recipes.variable("a"), notEqual, Recipes.variable("b"));
        assertEquals(3, expression.children().size());

        // ...a recipe resizes it without knowing what it is...
        Box scripted = Recipes.script(P, Recipes.variable("x"), notEqual, null);
        Box satellite = ((Box.Attach) scripted).satellites().get(Slot.NE);
        assertInstanceOf(Superimposed.class, satellite, "a recipe must not need to know the kind it is scaling");
        assertEquals(P.sizes().script(), satellite.size());

        // ...it carries a spacing class like any other box...
        assertEquals(MathClass.REL, notEqual.spacingClass());

        // ...and the generic walk reaches through it, seeing the nucleus and not the superimposed mark, which has
        // no logical position and must never appear in a selection.
        assertEquals(List.of("a", "=", "b"), texts(Box.leaves(expression)));
    }

    // --- the ten reference constructs -------------------------------------------------------------------------

    @Test
    void everyReferenceConstructComposes() {
        // Run and Row are primitives already; the other eight are recipes over the seven.
        assertInstanceOf(Box.Run.class, Recipes.variable("x"), "Run");
        assertInstanceOf(Box.Row.class, Box.row(Recipes.variable("a"), Recipes.operator("+"),
                Recipes.variable("b")), "Row");

        assertInstanceOf(Box.Stack.class, fraction(), "Fraction → Stack(num, Rule, den)");
        assertInstanceOf(Box.Attach.class, superscript(), "Script → Attach(NE, SE)");
        assertInstanceOf(Box.Attach.class, prescript(), "Prescript → Attach(NW, SW)");
        assertInstanceOf(Box.Attach.class, underOver(), "UnderOver → Attach(N, S)");
        assertInstanceOf(Box.Row.class, radical(), "Radical → Row(Stretch, Stack(Rule, radicand))");
        assertInstanceOf(Box.Row.class, fenced(), "Fenced → Row(Stretch, content, Stretch)");
        assertInstanceOf(Box.Row.class, matrix(), "Matrix → Row(Stretch, Grid, Stretch)");
        assertInstanceOf(Box.Row.class, cases(), "Cases → Row(Stretch, Grid)");
    }

    @Test
    void everyNodeInEveryConstructIsOneOfTheSeven() {
        for (Box construct : allConstructs()) {
            for (Box node : flatten(construct)) {
                assertTrue(BUILT_INS.contains(node.getClass().getSimpleName()),
                        () -> "unexpected node type " + node.getClass().getSimpleName());
            }
        }
    }

    // --- the collapses the design claims ----------------------------------------------------------------------

    @Test
    void attachSubsumesScriptPrescriptAndLimits() {
        Box.Attach post = (Box.Attach) superscript();
        Box.Attach pre = (Box.Attach) prescript();
        Box.Attach limits = (Box.Attach) underOver();

        // One primitive, three constructs: only the occupied slots differ.
        assertEquals(Set.of(Slot.NE, Slot.SE), post.satellites().keySet());
        assertEquals(Set.of(Slot.NW, Slot.SW), pre.satellites().keySet());
        assertEquals(Set.of(Slot.N, Slot.S), limits.satellites().keySet());
    }

    @Test
    void gridSubsumesMatrixAndCases() {
        Box.Grid asMatrix = firstGrid(matrix());
        Box.Grid asCases = firstGrid(cases());

        assertEquals(Box.Align.CENTER, asMatrix.columnAlign(), "a matrix centres its columns");
        assertEquals(Box.Align.START, asCases.columnAlign(), "a piecewise block left-aligns its rows");

        // Everything else about them is identical — the alignment is the whole difference, where the IR this
        // replaces needed two records and two hardcoded layout methods.
        assertEquals(asMatrix.colGap(), asCases.colGap());
        assertEquals(asMatrix.rowGap(), asCases.rowGap());
        assertEquals(asMatrix.anchor(), asCases.anchor());
    }

    @Test
    void aRadicalGrowsByTheSameMechanismAsADelimiter() {
        // Both the surd and a fence are Stretch/FILL: growth is one primitive, not two special cases.
        List<Box.Stretch> inRadical = stretches(radical());
        List<Box.Stretch> inFence = stretches(fenced());

        assertFalse(inRadical.isEmpty());
        assertFalse(inFence.isEmpty());
        for (Box.Stretch s : inRadical) {
            assertEquals(Box.Extent.FILL, s.extent());
        }
        for (Box.Stretch s : inFence) {
            assertEquals(Box.Extent.FILL, s.extent());
        }
    }

    @Test
    void aFractionBarAndARadicalVinculumAreTheSamePrimitive() {
        List<Box.Rule> bar = rules(fraction());
        List<Box.Rule> vinculum = rules(radical());

        assertEquals(1, bar.size());
        assertEquals(1, vinculum.size());
        assertEquals(Box.Extent.FILL, bar.get(0).extent());
        assertEquals(Box.Extent.FILL, vinculum.get(0).extent());
    }

    // --- sizes are ratios --------------------------------------------------------------------------------------

    @Test
    void authoredRatiosCompoundWithoutClamping() {
        // An exponent carrying its own exponent. The authored ratio is applied once per level and left to
        // compound: 0.7 then 0.7 again. Nothing here clamps it, because clamping is the block-wide tone map's
        // job (docs/typeset.md §4) — this is precisely the special case the previous implementation hand-wrote
        // into its fraction layout for want of anywhere better to put it.
        Box inner = Recipes.script(P, Recipes.variable("a"), Recipes.variable("b"), null);
        Box outer = Recipes.script(P, Recipes.variable("x"), inner, null);

        List<Double> paths = leafPathSizes(outer);
        assertEquals(List.of(1.0, 0.7, 0.49), round(paths),
                "authored size ratios compound per nesting level and are not clamped in the IR");
    }

    @Test
    void sizeScalesAWholeSubtreeNotJustALeaf() {
        Box subtree = Box.row(Recipes.variable("a"), Recipes.operator("+"), Recipes.variable("b"));
        Box scaled = subtree.withSize(0.5);

        assertEquals(0.5, scaled.size(), "the ratio attaches to the composite");
        for (Box leaf : Box.leaves(scaled)) {
            assertEquals(1.0, leaf.size(), "and the leaves are untouched — they are still 1.0 of their parent");
        }
    }

    @Test
    void anAttachWithNoSatellitesDegeneratesToItsNucleus() {
        Box base = Recipes.variable("x");
        assertSame(base, Recipes.script(P, base, null, null),
                "a construct with nothing attached is its nucleus, not an empty wrapper around it");
    }

    // --- spacing is pairwise -----------------------------------------------------------------------------------

    @Test
    void spacingDependsOnBothNeighboursNotOne() {
        Profile.Spacing s = P.spacing();

        // Same left class, different right class, different gap: a per-item model cannot express this.
        assertNotEquals(s.gap(MathClass.ORD, MathClass.ORD), s.gap(MathClass.ORD, MathClass.OP),
                "an ordinary atom spaces differently before an operator than before another ordinary atom");

        // And the table is directional.
        assertNotEquals(s.gap(MathClass.ORD, MathClass.PUNCT), s.gap(MathClass.PUNCT, MathClass.ORD),
                "a comma takes space after it, not before it");
    }

    @Test
    void compositesCarryASpacingClassSoTheySpaceAsOneAtom() {
        for (Box construct : List.of(fraction(), radical(), fenced(), matrix(), cases())) {
            assertEquals(MathClass.INNER, construct.spacingClass(),
                    () -> construct.getClass().getSimpleName() + " should space as a single inner atom");
        }
    }

    @Test
    void spacingClassesAreProfileDefinedIndices() {
        Profile.Spacing s = P.spacing();
        assertEquals(8, s.classCount(), "the math profile defines eight classes");
        assertEquals("inner", s.name(MathClass.INNER));
        assertEquals(0.0, s.gap(MathClass.INNER, 99), "an out-of-range class is inert, not an exception");
    }

    // --- reading order, the selection insurance -----------------------------------------------------------------

    @Test
    void leavesComeOutInReadingOrderWithTheirSourceRefs() {
        Box expression = Box.row(
                Box.run("x", Profile.FACE_MATH_ITALIC, MathClass.ORD, "src:0"),
                Box.run("+", Profile.FACE_MATH, MathClass.BIN, "src:1"),
                Recipes.script(P, Box.run("y", Profile.FACE_MATH_ITALIC, MathClass.ORD, "src:2"),
                        Box.run("2", Profile.FACE_MATH, MathClass.ORD, "src:3"), null));

        List<Object> refs = new ArrayList<>();
        for (Box leaf : Box.leaves(expression)) {
            refs.add(((Box.Run) leaf).sourceRef());
        }
        assertEquals(List.of("src:0", "src:1", "src:2", "src:3"), refs);
    }

    @Test
    void prescriptsAreReadBeforeTheirNucleus() {
        Box isotope = Recipes.prescript(P,
                Box.run("C", Profile.FACE_MATH, MathClass.ORD, "element"),
                Box.run("14", Profile.FACE_MATH, MathClass.ORD, "mass"),
                Box.run("6", Profile.FACE_MATH, MathClass.ORD, "atomic"));

        List<Object> refs = new ArrayList<>();
        for (Box leaf : Box.leaves(isotope)) {
            refs.add(((Box.Run) leaf).sourceRef());
        }
        assertEquals(List.of("mass", "atomic", "element"), refs,
                "reading order is a published guarantee, and a pre-script is read before what it qualifies");
    }

    // --- the constructs under test -------------------------------------------------------------------------------

    private static Box fraction() {
        return Recipes.fraction(P, Recipes.variable("a"), Recipes.variable("b"));
    }

    private static Box superscript() {
        return Recipes.script(P, Recipes.variable("x"), Recipes.number("2"), Recipes.variable("i"));
    }

    private static Box prescript() {
        return Recipes.prescript(P, Recipes.variable("C"), Recipes.number("14"), Recipes.number("6"));
    }

    private static Box underOver() {
        return Recipes.underOver(P, Recipes.bigOperator("∑"), Recipes.variable("n"),
                Box.row(Recipes.variable("k"), Recipes.relation("="), Recipes.number("1")));
    }

    private static Box radical() {
        return Recipes.radical(P, Box.row(Recipes.variable("x"), Recipes.operator("+"), Recipes.number("1")),
                Recipes.number("3"));
    }

    private static Box fenced() {
        return Recipes.fenced(P, "(", ")", Box.row(Recipes.variable("a"), Recipes.operator("+"),
                Recipes.variable("b")));
    }

    private static Box matrix() {
        return Recipes.matrix(P,
                List.of(List.of(Recipes.variable("a"), Recipes.variable("b")),
                        List.of(Recipes.variable("c"), Recipes.variable("d"))),
                "[", "]");
    }

    private static Box cases() {
        return Recipes.casesOf(P, List.of(Recipes.number("0"), Recipes.number("1")));
    }

    private static List<Box> allConstructs() {
        return List.of(fraction(), superscript(), prescript(), underOver(), radical(), fenced(), matrix(),
                cases());
    }

    // --- walking helpers -----------------------------------------------------------------------------------------

    /** The text of each leaf, for asserting reading order. */
    private static List<String> texts(List<Box> leaves) {
        List<String> out = new ArrayList<>(leaves.size());
        for (Box leaf : leaves) {
            out.add(leaf instanceof Box.Run r ? r.text() : leaf.getClass().getSimpleName());
        }
        return out;
    }

    private static List<Box> flatten(Box root) {
        List<Box> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Box box, List<Box> out) {
        out.add(box);
        for (Box kid : box.children()) {
            collect(kid, out);
        }
        // An Attach's nucleus and satellites are all reachable through children(); a Stretch/Rule/Run has none.
    }

    private static List<Box.Stretch> stretches(Box root) {
        List<Box.Stretch> out = new ArrayList<>();
        for (Box b : flatten(root)) {
            if (b instanceof Box.Stretch s) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<Box.Rule> rules(Box root) {
        List<Box.Rule> out = new ArrayList<>();
        for (Box b : flatten(root)) {
            if (b instanceof Box.Rule r) {
                out.add(r);
            }
        }
        return out;
    }

    private static Box.Grid firstGrid(Box root) {
        for (Box b : flatten(root)) {
            if (b instanceof Box.Grid g) {
                return g;
            }
        }
        throw new AssertionError("no Grid in " + root);
    }

    /** The product of {@code size} down the path to each leaf, in reading order — a preview of the authored log
     *  sizes the P1 tone map solves over. */
    private static List<Double> leafPathSizes(Box root) {
        List<Double> out = new ArrayList<>();
        accumulate(root, 1.0, out);
        return out;
    }

    private static void accumulate(Box box, double inherited, List<Double> out) {
        double here = inherited * box.size();
        List<Box> kids = box.children();
        if (kids.isEmpty()) {
            out.add(here);
            return;
        }
        for (Box kid : kids) {
            accumulate(kid, here, out);
        }
    }

    private static List<Double> round(List<Double> values) {
        List<Double> out = new ArrayList<>(values.size());
        for (double v : values) {
            out.add(Math.round(v * 1000.0) / 1000.0);
        }
        return out;
    }
}
