package dev.vexelray.gui.typeset;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The P1 gate (docs/typeset.md §10): the tone map's solve, on its own.
 *
 * <p>Pure numbers and no tree — {@link ToneMap#solve} takes a {@link ToneMap.Stats}, so most of this file hands it
 * one directly. That is the point of doing P1 before the engine: the solve can be wrong in ways geometry tests
 * would never reveal, because a golden coordinate is equally golden whether the slope behind it was right or not.
 */
class ToneMapTest {

    private static final Profile P = Profile.math();
    private static final Profile.ToneBounds B = P.tone();
    private static final double BASE = 16.0;
    private static final double EPS = 1e-9;

    /** One level of the math profile's script scale, in log space — the tightest step this profile authors. */
    private static final double SCRIPT_STEP = -Math.log(P.sizes().script());

    // --- degenerate cases, which are where a solver actually breaks -------------------------------------------

    @Test
    void nothingToSolveForIsTheIdentity() {
        ToneMap m = ToneMap.solve(new ToneMap.Stats(0, 0, 0, 0, 0), B, BASE);
        assertEquals(1.0, m.slope(), EPS);
        assertEquals(BASE, m.rootPx(), 1e-6, "an empty block still renders its root at the base size");
    }

    @Test
    void aFlatBlockRendersAtItsBaseSize() {
        // `x + 1`: no nesting, so no range and no steps. Normalizing to fill the window would blow this up to the
        // ceiling for no reason — the map anchors instead.
        Box flat = Box.row(Recipes.variable("x"), Recipes.operator("+"), Recipes.number("1"));
        ToneMap m = solveFor(flat);

        assertEquals(1.0, m.slope(), EPS);
        assertEquals(BASE, m.rootPx(), 1e-6);
        assertFalse(m.compresses());
    }

    @Test
    void aShallowBlockIsNotStretchedToFillTheWindow() {
        // A single 1.05:1 step has a tiny authored range and an enormous fit slope. Without the cap at 1 the map
        // would render it as several stops of contrast the author never asked for.
        ToneMap.Stats tiny = new ToneMap.Stats(Math.log(0.95), 0, Math.log(1.0 / 0.95), Math.log(1.0 / 0.95), 2);
        ToneMap m = ToneMap.solve(tiny, B, BASE);

        assertEquals(1.0, m.slope(), EPS, "slope is capped at 1 without an explicit opt-in to expansion");
    }

    @Test
    void nonPositiveSizesDoNotPoisonTheSolve() {
        Box broken = Box.row(Recipes.variable("a").withSize(0), Recipes.variable("b").withSize(-1));
        ToneMap m = solveFor(broken);

        assertTrue(Double.isFinite(m.slope()), "a bad declared size must not produce a NaN slope");
        assertTrue(Double.isFinite(m.gain()), "nor a NaN gain");
    }

    // --- the hard guarantees ------------------------------------------------------------------------------------

    @Test
    void everyLeafClearsTheFloor() {
        for (int levels = 0; levels <= 12; levels++) {
            Box block = nestedScripts(levels);
            ToneMap m = solveFor(block);
            double smallest = m.px(Math.exp(ToneMap.Stats.of(block, B.ratioFloor()).minLog()));

            int shown = levels;
            assertTrue(smallest >= B.floorPx() - 1e-6,
                    () -> "at " + shown + " levels the smallest glyph came out at " + smallest
                            + "px, below the hard floor of " + B.floorPx());
        }
    }

    @Test
    void theFloorLiftsABlockWhoseBaseIsAlreadyTooSmall() {
        ToneMap m = ToneMap.solve(ToneMap.Stats.flat(), B, 4.0);   // base below the 9px floor
        assertEquals(B.floorPx(), m.rootPx(), 1e-6,
                "the floor is hard even when nothing is nested — a 4px block is not legible either");
    }

    @Test
    void orderingIsAlwaysPreserved() {
        // Compression may squeeze the spread but must never invert or collapse it: a larger authored ratio stays
        // a larger rendered size. This holds for any non-negative slope, and is the property that makes relative
        // size still mean "what is nested in what" after the map.
        ToneMap m = solveFor(nestedScripts(9));
        double previous = 0;
        for (int level = 9; level >= 0; level--) {
            double px = m.px(Math.pow(P.sizes().script(), level));
            assertTrue(px > previous, "level " + level + " must render larger than the one below it");
            previous = px;
        }
    }

    // --- the yield order ------------------------------------------------------------------------------------------

    @Test
    void theCeilingYieldsAndTheContrastFloorDoesNot() {
        Box deep = nestedScripts(12);
        ToneMap.Stats s = ToneMap.Stats.of(deep, B.ratioFloor());
        ToneMap m = ToneMap.solve(s, B, BASE);

        double slopeFloor = Math.log(B.ratioFloor()) / s.minStep();
        assertEquals(slopeFloor, m.slope(), 1e-9,
                "past the crossover the contrast floor pins the slope — it does not give way");

        double largest = m.px(Math.exp(s.maxLog()));
        assertTrue(largest > B.ceilPx(),
                "and the ceiling is what gives instead: " + largest + "px against a " + B.ceilPx() + "px ceiling");
    }

    @Test
    void depthSweepCrossoverMatchesTheHandCalculation() {
        // window / slopeFloor / stepPerLevel, with this profile:
        //   ln(32/9) = 1.26851,  ln(1.2)/|ln 0.7| = 0.51117,  |ln 0.7| = 0.35668   ->   6.96 levels
        double window = Math.log(B.ceilPx() / B.floorPx());
        double slopeFloor = Math.log(B.ratioFloor()) / SCRIPT_STEP;
        double crossover = window / slopeFloor / SCRIPT_STEP;
        assertEquals(6.96, crossover, 0.01, "the arithmetic in docs/typeset.md §4.2");

        assertFalse(yieldsTheCeiling(6), "six levels of nesting still fit");
        assertTrue(yieldsTheCeiling(7), "seven do not");
    }

    /** Whether a block of {@code levels} nested scripts is forced past the size ceiling. */
    private static boolean yieldsTheCeiling(int levels) {
        Box block = nestedScripts(levels);
        ToneMap.Stats s = ToneMap.Stats.of(block, B.ratioFloor());
        return ToneMap.solve(s, B, BASE).px(Math.exp(s.maxLog())) > B.ceilPx() + 1e-6;
    }

    // --- the contrast-floor filter ----------------------------------------------------------------------------------

    @Test
    void aStepTighterThanTheFloorDoesNotPinTheSlope() {
        // A 1.05:1 ratio was never distinguishable, so it must not forbid compressing everything else. Counting it
        // would drive the slope floor above 1 and leave a deep block unable to compress at all because of one
        // near-invisible step.
        Box block = Box.row(
                Recipes.variable("a").withSize(0.95),                     // below ratioFloor: ignored
                nestedScripts(9));                                        // real steps: 0.7 per level

        ToneMap.Stats s = ToneMap.Stats.of(block, B.ratioFloor());
        assertEquals(SCRIPT_STEP, s.minStep(), 1e-9,
                "the tightest *meaningful* step is the script scale, not the 1.05:1 one");
        assertTrue(ToneMap.solve(s, B, BASE).compresses(), "and the block can still compress");
    }

    @Test
    void aProfileWhoseFloorExceedsItsOwnStepsLeavesNothingToProtect() {
        // ratioFloor above every authored ratio: every step is filtered, so the contrast floor stops constraining
        // and the fit alone decides. This is the misconfiguration the shipped profile originally had.
        Profile.ToneBounds tooTight = new Profile.ToneBounds(9.0, 32.0, 3.0, 4.0, false);
        ToneMap.Stats s = ToneMap.Stats.of(nestedScripts(9), tooTight.ratioFloor());

        assertEquals(0.0, s.minStep(), EPS, "every step was tighter than the floor, so none is meaningful");
        ToneMap m = ToneMap.solve(s, tooTight, BASE);
        assertTrue(m.slope() < 0.51, "with nothing to protect, the fit compresses freely");
        assertTrue(m.px(Math.exp(s.maxLog())) <= tooTight.ceilPx() + 1e-6, "and the block fits the ceiling");
    }

    // --- expansion, which is opt-in only --------------------------------------------------------------------------

    @Test
    void expansionIsOffByDefaultAndBoundedWhenOn() {
        ToneMap.Stats shallow = new ToneMap.Stats(Math.log(0.95), 0, Math.log(1 / 0.95), Math.log(1 / 0.95), 2);

        assertEquals(1.0, ToneMap.solve(shallow, B, BASE).slope(), EPS, "off by default");

        Profile.ToneBounds opted = new Profile.ToneBounds(9.0, 32.0, 1.2, 2.0, true);
        ToneMap m = ToneMap.solve(shallow, opted, BASE);
        assertTrue(m.slope() > 1.0, "opted in, a shallow block may be expanded");

        double widest = Math.exp(m.slope() * shallow.maxStep());
        assertTrue(widest <= opted.ratioCeil() + 1e-6,
                "but never past ratioCeil — expansion invents contrast, so it has to be bounded");
    }

    // --- the gather -------------------------------------------------------------------------------------------------

    @Test
    void theGatherAccumulatesPathProductsAndEdgeRatios() {
        // x with a superscript that carries its own: leaves at 1.0, 0.7 and 0.49.
        Box inner = Recipes.script(P, Recipes.variable("a"), Recipes.variable("b"), null);
        Box outer = Recipes.script(P, Recipes.variable("x"), inner, null);
        ToneMap.Stats s = ToneMap.Stats.of(outer, B.ratioFloor());

        assertEquals(3, s.leaves());
        assertEquals(0.0, s.maxLog(), 1e-9, "the outermost nucleus is the largest, at ratio 1.0");
        assertEquals(Math.log(0.49), s.minLog(), 1e-9, "and the innermost is 0.7 squared");
        assertEquals(SCRIPT_STEP, s.minStep(), 1e-9);
        assertEquals(SCRIPT_STEP, s.maxStep(), 1e-9, "every edge here is the same script scale");
        assertEquals(2 * SCRIPT_STEP, s.range(), 1e-9);
    }

    @Test
    void theGatherSeesThroughAnApplicationDefinedBox() {
        // Stats walks children(), so a box kind written outside the framework participates in the tone map with no
        // special handling — the same property VocabularyTest asserts structurally, here in the solver.
        Box custom = new dev.vexelray.gui.typeset.app.Superimposed(
                Recipes.relation("="), "/", Profile.FACE_MATH);
        Box block = Recipes.script(P, custom, Recipes.number("2"), null);

        ToneMap.Stats s = ToneMap.Stats.of(block, B.ratioFloor());
        assertEquals(2, s.leaves(), "the custom box's nucleus is a leaf; its superimposed mark is not a child");
        assertEquals(Math.log(P.sizes().script()), s.minLog(), 1e-9);
    }

    // --- units ------------------------------------------------------------------------------------------------------

    @Test
    void relativeIsPixelsOverTheRootSize() {
        ToneMap m = solveFor(nestedScripts(8));
        for (double ratio : List.of(1.0, 0.7, 0.49, 0.343)) {
            assertEquals(m.px(ratio) / m.rootPx(), m.relative(ratio), 1e-9,
                    "the two unit views must agree exactly — one is a division of the other");
        }
    }

    // --- helpers ------------------------------------------------------------------------------------------------------

    private static ToneMap solveFor(Box block) {
        return ToneMap.solve(ToneMap.Stats.of(block, B.ratioFloor()), B, BASE);
    }

    /** {@code levels} nested superscripts, so the innermost leaf sits at {@code script^levels}. */
    private static Box nestedScripts(int levels) {
        Box box = Recipes.variable("z");
        for (int i = 0; i < levels; i++) {
            box = Recipes.script(P, Recipes.variable("x"), box, null);
        }
        return box;
    }
}
