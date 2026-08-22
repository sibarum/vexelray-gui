package dev.vexelray.gui.typeset;

/**
 * Everything that varies between one kind of typeset content and another: which faces it draws from, the size
 * ratios its constructs assign, how tightly its atoms space against each other, and the legible range its type is
 * allowed to occupy. A profile is <b>compile-time data</b> — a record, not a config file — because the display
 * parameters for a given kind of content are decided when the app is built, not tuned at runtime.
 *
 * <p><b>Profiles are data over built-in recipes, not recipes themselves.</b> "The numerator sits so its bottom
 * clears the bar by {@code fractionGapAbove}" is an algorithm; {@code fractionGapAbove} is a number. {@link Recipes}
 * owns the algorithms and a profile supplies the numbers. Making recipes themselves data would mean building a
 * layout DSL on speculation — the deliberate later step, taken only if a real app needs a construct the built-ins
 * cannot compose (docs/typeset.md §7).
 *
 * <p>Every dimension here is in <b>em</b> of the box it applies to, except the two unitless ratio bounds and the
 * two pixel bounds in {@link ToneBounds}, which are the only place in the module that names a physical unit — and
 * they name it because legibility is physical.
 */
public record Profile(String defaultFace, Sizes sizes, Metrics metrics, Spacing spacing, ToneBounds tone) {

    /**
     * The relative sizes constructs assign to their parts. These are <b>authored ratios</b>, applied once per
     * nesting level and left to compound: a script inside a script is {@code script²}, and nothing here clamps it.
     * That is deliberate. The classical approach carries a separate "script-script" size precisely to stop runaway
     * shrink, which is a local patch for a global problem; here the tone map handles the whole block's range at
     * once (docs/typeset.md §4), so the authored ratios stay uniform and honest.
     *
     * @param script      a satellite relative to its nucleus — the classic ~0.7
     * @param numerator   a fraction's numerator relative to the fraction
     * @param denominator a fraction's denominator relative to the fraction
     * @param index       a radical's degree index — smaller than a script, and tucked into the surd's arm
     * @param limit       an N/S satellite relative to its nucleus (a big operator's bounds)
     */
    public record Sizes(double script, double numerator, double denominator, double index, double limit) {
    }

    /**
     * The offsets and thicknesses the built-in recipes anchor against. A real OpenType MATH table ships an
     * analogous set; these are the externally-tuned equivalent, kept as data so that choosing a different face
     * means swapping a record rather than editing the engine.
     *
     * @param axisHeight        the axis above the baseline where a fraction bar and a growable delimiter centre
     * @param ruleThickness     a fraction bar
     * @param fractionGapAbove  numerator bottom to bar
     * @param fractionGapBelow  bar to denominator top
     * @param shiftUp           how far an NE/NW satellite's baseline sits above its nucleus's
     * @param shiftDown         how far an SE/SW satellite's baseline sits below its nucleus's
     * @param scriptGapAfter    trailing space after an attached cluster
     * @param limitGap          nucleus to an N or S satellite
     * @param vinculumThickness the bar over a radicand
     * @param vinculumGap       vinculum to the top of the radicand
     * @param surdKernBefore    space before the surd glyph
     * @param surdKernAfter     surd to radicand — the tuck under the hook
     * @param delimiterPad      padding just inside a delimiter pair
     * @param gridColGap        between a grid's columns
     * @param gridRowGap        between a grid's rows
     * @param gridPad           between a grid and its enclosing delimiters
     */
    public record Metrics(double axisHeight, double ruleThickness,
                          double fractionGapAbove, double fractionGapBelow,
                          double shiftUp, double shiftDown, double scriptGapAfter, double limitGap,
                          double vinculumThickness, double vinculumGap,
                          double surdKernBefore, double surdKernAfter, double delimiterPad,
                          double gridColGap, double gridRowGap, double gridPad) {
    }

    /**
     * The inter-atom spacing table: {@code gap(left, right)} in em, indexed by the spacing classes of two adjacent
     * items in a {@link Box.Row}.
     *
     * <p>A <b>pairwise</b> table, not a per-item value. The IR this replaces derived spacing from one run's role,
     * which cannot express that a relation beside an open delimiter spaces differently from a relation beside a
     * number — a distinction every typesetter makes and no single-item model can represent.
     *
     * <p><b>The class vocabulary belongs to the profile, not the framework.</b> Classes are plain indices and the
     * engine only ever evaluates {@code table[i][j]}; {@code names} records what this profile means by each index,
     * for diagnostics. A math profile uses the eight classes below; a prose profile uses whatever it needs. That
     * is what lets one closed primitive set serve both without the framework learning either vocabulary.
     */
    public record Spacing(double[][] table, String[] names) {

        /** Defensive copies — a record holding arrays is otherwise a mutable value pretending to be immutable. */
        public Spacing {
            double[][] copy = new double[table.length][];
            for (int i = 0; i < table.length; i++) {
                copy[i] = table[i].clone();
            }
            table = copy;
            names = names.clone();
        }

        public int classCount() {
            return table.length;
        }

        /** The gap between an item of class {@code left} and one of class {@code right}; 0 for unknown classes. */
        public double gap(int left, int right) {
            if (left < 0 || left >= table.length || right < 0 || right >= table[left].length) {
                return 0.0;
            }
            return table[left][right];
        }

        /** This profile's name for a class index, for diagnostics; {@code "?"} when out of range. */
        public String name(int cls) {
            return cls < 0 || cls >= names.length ? "?" : names[cls];
        }
    }

    /**
     * The bounds the tone map solves against (docs/typeset.md §4). The block's authored size ratios are fitted
     * into {@code [floorPx, ceilPx]} by a single slope and offset in log space.
     *
     * <p>Hardness is <b>pre-declared</b>, because past some nesting depth these cannot all hold at once and the
     * solver must know which one gives: {@code floorPx} is hard (legibility is the point), {@code ratioFloor} is
     * hard (two levels rendering the same size is worse than one being oversized), and {@code ceilPx} yields — a
     * deeply nested expression genuinely is large if every part of it must be readable, and it overflows and
     * scrolls rather than becoming unreadable.
     *
     * @param floorPx        no glyph renders smaller than this — hard
     * @param ceilPx         preferred upper bound — yields first
     * @param ratioFloor     an authored size step must still render at least this ratio apart — hard. Set it
     *                       <b>below</b> the tightest ratio in {@link Sizes}: it protects authored contrast from
     *                       being crushed by compression, and cannot manufacture contrast that was never declared.
     *                       A step already tighter than this is ignored when solving rather than pinning the slope
     * @param ratioCeil      an authored step must not render more than this ratio apart; only binds when
     *                       {@code allowExpansion} is set
     * @param allowExpansion whether the map may render ratios <em>wider</em> than authored. Off by default:
     *                       rendering an authored 1.05:1 as 4:1 invents contrast the author did not ask for, so
     *                       the slope is capped at 1 and the map only ever compresses
     */
    public record ToneBounds(double floorPx, double ceilPx,
                             double ratioFloor, double ratioCeil, boolean allowExpansion) {
    }

    // --- the math profile ------------------------------------------------------------------------------------

    /**
     * The spacing classes the {@link #math()} profile defines. Plain indices, named here so the profile's own
     * vocabulary is legible at the call site — the framework never sees these names.
     */
    public static final class MathClass {

        /** An ordinary atom: a variable, a number. */
        public static final int ORD = 0;
        /** A large operator: ∑, ∏, ∫. */
        public static final int OP = 1;
        /** A binary operator: + − ⋅. */
        public static final int BIN = 2;
        /** A relation: = ≈ → ≤. */
        public static final int REL = 3;
        /** An opening delimiter. */
        public static final int OPEN = 4;
        /** A closing delimiter. */
        public static final int CLOSE = 5;
        /** Punctuation: a comma in an argument list. */
        public static final int PUNCT = 6;
        /** A composite treated as one atom: a fenced group, a fraction. */
        public static final int INNER = 7;

        private MathClass() {
        }
    }

    /** Face key for the upright math face — operators, delimiters, digits. */
    public static final String FACE_MATH = "math";
    /** Face key for the italic math alphabet (U+1D400). Where the atlas lacks it, variables render upright. */
    public static final String FACE_MATH_ITALIC = "mathItalic";

    private static final double THIN = 3.0 / 18.0;
    private static final double MED = 4.0 / 18.0;
    private static final double THICK = 5.0 / 18.0;

    /**
     * The baked math profile. Spacing follows the classical eight-class table (thin 3/18, medium 4/18, thick 5/18
     * em); combinations the table marks invalid are zero here, since the IR permits them structurally and a
     * renderer must do something rather than reject a tree.
     */
    public static Profile math() {
        double[][] table = {
                // right:  ORD    OP     BIN    REL    OPEN   CLOSE  PUNCT  INNER
                /* ORD   */ {0,     THIN,  MED,   THICK, 0,     0,     0,     MED},
                /* OP    */ {THIN,  THIN,  0,     THICK, 0,     0,     0,     MED},
                /* BIN   */ {MED,   MED,   0,     0,     MED,   0,     0,     MED},
                /* REL   */ {THICK, THICK, 0,     0,     THICK, 0,     0,     THICK},
                /* OPEN  */ {0,     0,     0,     0,     0,     0,     0,     0},
                /* CLOSE */ {0,     THIN,  MED,   THICK, 0,     0,     0,     MED},
                /* PUNCT */ {THIN,  THIN,  0,     THIN,  THIN,  THIN,  THIN,  THIN},
                /* INNER */ {MED,   THIN,  MED,   THICK, MED,   0,     THIN,  MED},
        };
        String[] names = {"ord", "op", "bin", "rel", "open", "close", "punct", "inner"};

        return new Profile(
                FACE_MATH,
                new Sizes(0.70, 1.00, 1.00, 0.60, 0.70),
                new Metrics(
                        0.25,   // axisHeight
                        0.04,   // ruleThickness
                        0.10,   // fractionGapAbove
                        0.10,   // fractionGapBelow
                        0.45,   // shiftUp
                        0.20,   // shiftDown
                        0.05,   // scriptGapAfter
                        0.15,   // limitGap
                        0.045,  // vinculumThickness
                        0.06,   // vinculumGap
                        0.05,   // surdKernBefore
                        0.02,   // surdKernAfter
                        0.05,   // delimiterPad
                        0.60,   // gridColGap
                        0.35,   // gridRowGap
                        0.15),  // gridPad
                new Spacing(table, names),
                // ratioFloor must sit BELOW the tightest ratio the profile authors, or it asks to preserve
                // contrast that was never declared. This profile's tightest step is the script scale, 1/0.7 =
                // 1.43:1, so 1.2 leaves real headroom; the 1.5 first written here was larger than the step it was
                // meant to protect, which left the radical's degree index setting the whole block's compression
                // limit. With 1.2 the slope floor is ln(1.2)/|ln 0.7| = 0.511, and a 9–32px window yields the
                // ceiling past about seven levels of nesting (ToneMapTest.depthSweep...).
                new ToneBounds(9.0, 32.0, 1.2, 4.0, false));
    }
}
