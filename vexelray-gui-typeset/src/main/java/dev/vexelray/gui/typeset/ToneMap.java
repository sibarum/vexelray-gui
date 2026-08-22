package dev.vexelray.gui.typeset;

/**
 * Fits a block's authored size ratios into the range a reader can actually see — the same operation as an HDR
 * transfer curve or an audio compressor, applied to type size (docs/typeset.md §4).
 *
 * <p>Sizes in this module are declared <b>relative to the parent</b> and carry no absolute meaning. A leaf's
 * authored size is therefore the product of every ratio on the path down to it, and a block's authored
 * <em>range</em> is the spread of those products. That range is whatever the content needs; the legible range is
 * whatever the display allows. This maps one onto the other.
 *
 * <h2>Two scalars</h2>
 * In log space a ratio becomes a difference, so "an authored step must not be crushed below 1.2:1" becomes a bound
 * on a <b>slope</b>, and the whole transfer is a line:
 *
 * <pre>
 *   px(r) = exp(slope · ln r + gain)
 * </pre>
 *
 * One walk gathers four numbers ({@link Stats}), a closed-form solve gives {@code slope} and {@code gain}, one walk
 * applies them. O(n), no iteration, deterministic — and {@link #solve} needs no tree at all, which is why it is
 * testable before a layout engine exists.
 *
 * <h2>What it guarantees, and what it does not</h2>
 * It fits <b>declared</b> sizes. A {@link Box} that chooses a size itself has opted out for that content and owns
 * its legibility; the block may then come out larger than the declared ratios predict, which is exactly the
 * outcome the soft ceiling already absorbs.
 *
 * <h2>Units</h2>
 * {@link #px} returns <b>pixels</b>, because a legibility floor is physical and nothing else in this module is.
 * {@link #relative} returns the same thing as a multiple of {@link #rootPx()}, which is what a layout working in
 * em of the block root wants. Which of the two the engine lays out in is P2's decision; both are exact and one is
 * a division of the other.
 *
 * @param slope compression applied to authored log-ratios; {@code 1.0} reproduces them exactly, less than 1
 *              compresses, and more than 1 only ever appears when a profile opts into expansion
 * @param gain  log-pixels added after scaling; anchors the authored ratio {@code 1.0}
 */
public record ToneMap(double slope, double gain) {

    /** The identity map at {@code basePx}: authored ratios reproduced exactly. */
    public static ToneMap identity(double basePx) {
        return new ToneMap(1.0, Math.log(Math.max(1e-6, basePx)));
    }

    /**
     * Solve for the transfer that fits {@code stats} into {@code bounds}, anchored so the authored ratio 1.0 sits
     * at {@code basePx} unless the floor forces it higher.
     *
     * <p>The three constraints cannot always hold at once, so the yield order is pre-declared (docs/typeset.md
     * §4.2): the <b>size floor is hard</b> (legibility is the whole point), the <b>contrast floor is hard</b> (two
     * levels rendering the same size is worse than one being oversized), and the <b>size ceiling yields</b> — a
     * deeply nested block genuinely is large if every part of it must be readable, and it overflows and scrolls.
     * That ordering makes the solve total: it always returns, and what it returns is always legible.
     */
    public static ToneMap solve(Stats stats, Profile.ToneBounds bounds, double basePx) {
        double baseLog = Math.log(Math.max(1e-6, basePx));
        if (stats.isEmpty()) {
            return new ToneMap(1.0, baseLog);
        }

        double floorLog = Math.log(Math.max(1e-6, bounds.floorPx()));
        double ceilLog = Math.log(Math.max(1e-6, bounds.ceilPx()));

        // The widest slope allowed. Without expansion that is 1: rendering an authored 1.05:1 as 4:1 would invent
        // contrast the author never asked for. With expansion opted in, ratioCeil is what keeps it safe.
        double upper = 1.0;
        if (bounds.allowExpansion() && stats.maxStep() > 0) {
            upper = Math.max(1.0, Math.log(Math.max(1.0, bounds.ratioCeil())) / stats.maxStep());
        } else if (bounds.allowExpansion()) {
            upper = Double.POSITIVE_INFINITY;
        }

        // The narrowest slope allowed: compressing past this crushes the tightest authored step below the contrast
        // floor. Steps already tighter than the floor were filtered out during the gather — see Stats.of.
        double lower = stats.minStep() > 0
                ? Math.min(upper, Math.log(Math.max(1.0, bounds.ratioFloor())) / stats.minStep())
                : 0.0;

        // The slope that exactly fits the authored range into the legible window. Infinite when there is no range
        // to fit (a flat block) or no window to fit it into (a misconfigured profile) — in both cases the fit
        // simply does not constrain, and the cap at `upper` decides.
        double window = ceilLog - floorLog;
        double range = stats.range();
        double fit = (range > 0 && window > 0) ? window / range : Double.POSITIVE_INFINITY;

        double slope = clamp(Math.min(fit, upper), lower, upper);

        // Anchor on the base size, then lift only as far as the hard floor demands. Normalizing to fill the window
        // instead would blow a flat `x + 1` up to the ceiling for no reason.
        double gain = Math.max(baseLog, floorLog - slope * stats.minLog());
        return new ToneMap(slope, gain);
    }

    /** The rendered size in pixels for an authored ratio (the product of {@code size()} down to a node). */
    public double px(double authoredRatio) {
        return Math.exp(slope * Math.log(Math.max(1e-9, authoredRatio)) + gain);
    }

    /** The rendered size for the authored ratio {@code 1.0} — the block's root size in pixels. */
    public double rootPx() {
        return Math.exp(gain);
    }

    /** {@link #px} as a multiple of {@link #rootPx()}, for a layout working in em of the block root. */
    public double relative(double authoredRatio) {
        return Math.pow(Math.max(1e-9, authoredRatio), slope);
    }

    /** Whether this map compresses at all. A block that fits needs no compression and says so. */
    public boolean compresses() {
        return slope < 1.0 - 1e-9;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /**
     * The four numbers a solve needs, gathered in one walk.
     *
     * <p>{@code minLog}/{@code maxLog} bound the authored range; {@code minStep}/{@code maxStep} are the tightest
     * and loosest single parent-to-child ratio in the tree, in log space, which is what the contrast bounds apply
     * to.
     *
     * @param minLog  smallest authored log-size across the block's leaves
     * @param maxLog  largest authored log-size across the block's leaves
     * @param minStep tightest meaningful parent-to-child log-ratio, or 0 if there are none
     * @param maxStep loosest parent-to-child log-ratio, or 0 if there are none
     * @param leaves  how many leaves contributed; 0 means nothing to solve for
     */
    public record Stats(double minLog, double maxLog, double minStep, double maxStep, int leaves) {

        /** Nothing to fit — an empty tree, or one with no leaves. */
        public boolean isEmpty() {
            return leaves == 0;
        }

        /** The authored dynamic range in log space; 0 for a block whose leaves are all one size. */
        public double range() {
            return Math.max(0, maxLog - minLog);
        }

        /**
         * Walk {@code root}, accumulating the path product down to each leaf and the ratio across each edge.
         *
         * <p><b>Steps tighter than {@code ratioFloor} are ignored.</b> The contrast floor exists to stop
         * compression crushing authored contrast — not to manufacture contrast the author never declared. A step
         * that was already below the floor was never distinguishable, so letting it constrain the slope would mean
         * one nearly-unit ratio anywhere in a block forbidding compression everywhere, which is both surprising
         * and useless. Filtering it costs nothing: it stays proportionally where the author put it.
         *
         * <p>A non-positive declared size is treated as a very small positive one rather than producing a NaN that
         * would silently poison the solve.
         */
        public static Stats of(Box root, double ratioFloor) {
            Accumulator acc = new Accumulator(ratioFloor > 1.0 ? Math.log(ratioFloor) : 0.0);
            acc.visit(root, 0.0);
            return new Stats(
                    acc.leaves == 0 ? 0 : acc.minLog,
                    acc.leaves == 0 ? 0 : acc.maxLog,
                    acc.minStep == Double.MAX_VALUE ? 0 : acc.minStep,
                    acc.maxStep,
                    acc.leaves);
        }

        /** A one-node block: no range, no steps. */
        public static Stats flat() {
            return new Stats(0, 0, 0, 0, 1);
        }

        private static final class Accumulator {

            private final double ignoreStepsBelow;
            private double minLog = Double.MAX_VALUE;
            private double maxLog = -Double.MAX_VALUE;
            private double minStep = Double.MAX_VALUE;
            private double maxStep;
            private int leaves;

            Accumulator(double ignoreStepsBelow) {
                this.ignoreStepsBelow = ignoreStepsBelow;
            }

            void visit(Box box, double inheritedLog) {
                double here = inheritedLog + Math.log(Math.max(1e-9, box.size()));
                java.util.List<Box> kids = box.children();
                if (kids.isEmpty()) {
                    leaves++;
                    minLog = Math.min(minLog, here);
                    maxLog = Math.max(maxLog, here);
                    return;
                }
                for (Box kid : kids) {
                    double step = Math.abs(Math.log(Math.max(1e-9, kid.size())));
                    if (step >= ignoreStepsBelow && step > 0) {
                        minStep = Math.min(minStep, step);
                        maxStep = Math.max(maxStep, step);
                    }
                    visit(kid, here);
                }
            }
        }
    }
}
