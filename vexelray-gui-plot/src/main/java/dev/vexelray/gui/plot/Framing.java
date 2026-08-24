package dev.vexelray.gui.plot;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the window comes from: {@code expression → frame}. The unit that is <b>preference rather than
 * mathematics</b>, and is isolated for exactly that reason — a different taste in auto-framing must be
 * swappable without evaluation or rendering noticing.
 *
 * <p>So the policy is stated here rather than distributed through a renderer, and it is short enough to argue
 * with:
 *
 * <ol>
 *   <li><b>x is not chosen.</b> {@link #DEFAULT_HALF_WIDTH} either side of the origin, and that is all. Which
 *       part of a curve is the interesting part is not a question arithmetic can answer — {@code sin(1000x)}
 *       and {@code x²} want windows three orders of magnitude apart and neither is more right — so the honest
 *       default is a plain one the user can pan away from, rather than a heuristic that is uncannily good four
 *       times and baffling the fifth.
 *   <li><b>y is measured, robustly.</b> The expression is enclosed over the whole width, and the frame is
 *       fitted to the <em>bulk</em> of what came back: the interquartile range of the sampled endpoints,
 *       reaching out by {@link #SPREAD} IQRs. A parabola keeps its whole range, because a parabola's values are
 *       spread out and its IQR is wide. A curve with a pole is not flattened onto the axis by the one column
 *       that reached a billion, because that column lies far outside an IQR three hundred others agree on.
 *       Unbounded and undefined columns contribute nothing — there is no value there to frame around.
 *   <li><b>Zero is joined when it is nearly in view.</b> A curve running 1 to 10 reads better against an axis
 *       than floating above one, and the gap to the axis is small next to the curve's own height. A curve
 *       running 12 to 14 is the other case: the axis is six heights away, and reaching for it would squash
 *       everything worth seeing into the top eighth of the window. {@link #REACH_TO_ZERO} is where the line
 *       between those falls.
 *   <li><b>The result is padded and snapped</b> to a multiple of {@link #tickStep}, so the frame's edges fall
 *       on labelled gridlines instead of a millimetre past them — and the padding is not allowed to carry the
 *       frame across an axis the curve never crossed.
 * </ol>
 *
 * <p>Nothing here is sound in the sense the enclosure algebra is, and nothing needs to be: a badly framed plot
 * is a plot you pan, while an unsound one is a plot that lies. The soundness lives one layer down and no choice
 * made here can reach it.
 */
public final class Framing {

    /** The default x half-window: a plain, memorable {@code [-10, 10]}. */
    public static final double DEFAULT_HALF_WIDTH = 10;

    /** The default y half-window, used when the expression leaves nothing to measure. */
    public static final double DEFAULT_HALF_HEIGHT = 10;

    /** How many columns the framing pass evaluates: enough to characterise a curve, cheap enough to be instant. */
    private static final int SAMPLES = 240;

    /** How far past the interquartile range the frame reaches, in IQRs. Wide enough to keep a parabola whole. */
    private static final double SPREAD = 2.5;

    /** Zero is brought into frame when it is within this multiple of the fitted height. */
    private static final double REACH_TO_ZERO = 0.35;

    /** Breathing room above and below, as a fraction of the fitted height. */
    private static final double PADDING = 0.08;

    /** Roughly how many labelled gridlines an axis should carry. */
    private static final int TICKS = 8;

    private Framing() {
    }

    /** The automatic frame for {@code expr}: the default x window, and a y window measured across it. */
    public static Frame automatic(Expr expr) {
        return automatic(expr, -DEFAULT_HALF_WIDTH, DEFAULT_HALF_WIDTH);
    }

    /** The automatic frame for {@code expr} over a given x window — the case where the user chose the x. */
    public static Frame automatic(Expr expr, double xLo, double xHi) {
        Frame provisional = new Frame(xLo, xHi, -DEFAULT_HALF_HEIGHT, DEFAULT_HALF_HEIGHT);
        return refit(expr, provisional);
    }

    /**
     * Re-fit the vertical extent of {@code frame} to what {@code expr} does across its <em>current</em> x
     * window — the "fit" command, for once the user has panned somewhere the original framing never saw.
     */
    public static Frame refit(Expr expr, Frame frame) {
        double[] y = fit(sample(expr, frame));
        return frame.withY(y[0], y[1]);
    }

    /**
     * The automatic volume for a surface: the default square floor about the origin, and a height measured
     * across it.
     *
     * @param xName the name of the axis running left to right
     * @param yName the name of the axis running into the picture
     */
    public static Volume automatic(Expr expr, String xName, String yName) {
        return refit(expr, Volume.about(DEFAULT_HALF_WIDTH, DEFAULT_HALF_HEIGHT), xName, yName);
    }

    /**
     * Re-fit the height of {@code volume} to what {@code expr} does across its floor — "fit", for a surface.
     *
     * <p>The policy is not re-argued for the third axis; it is the same preference applied to one more of them.
     * What changes is only the shape of the sample: a grid rather than a row, {@link #SAMPLES} points across it
     * in total so that framing a surface costs about what framing a curve does.
     */
    public static Volume refit(Expr expr, Volume volume, String xName, String yName) {
        double[] z = fit(sample(expr, volume, xName, yName));
        return volume.withZ(z[0], z[1]);
    }

    /**
     * A round step — 1, 2 or 5 times a power of ten — giving roughly {@code target} divisions across
     * {@code span}. The one piece of taste a renderer needs too: gridlines 0.7 apart are not gridlines, they
     * are noise.
     */
    public static double tickStep(double span, int target) {
        if (!(span > 0) || target <= 0) {
            throw new IllegalArgumentException("a tick step needs a positive span and a positive count");
        }
        double rough = span / target;
        double magnitude = Math.pow(10, Math.floor(Math.log10(rough)));
        // To the NEAREST of 1, 2, 5, 10 rather than up to the next one: rounding up always undershoots the
        // requested count, and a span of 20 asked for in eights would come back with four gridlines.
        double normalized = rough / magnitude;
        double snapped = normalized <= 1.5 ? 1 : normalized <= 3 ? 2 : normalized <= 7 ? 5 : 10;
        return snapped * magnitude;
    }

    /**
     * The fitted {@code [lo, hi]} for a set of observed values, by the policy in this class's documentation.
     * Both a curve's y and a surface's z arrive here — the policy is about a spread of numbers and does not
     * know or care which axis they were measured along.
     */
    private static double[] fit(List<Double> seen) {
        if (seen.isEmpty()) {
            // Nothing bounded anywhere: every column is a pole or a gap. There is nothing to measure, and
            // guessing would only be a different way of being arbitrary.
            return new double[]{-DEFAULT_HALF_HEIGHT, DEFAULT_HALF_HEIGHT};
        }
        seen.sort(null);
        double q1 = quantile(seen, 0.25);
        double q3 = quantile(seen, 0.75);
        double iqr = q3 - q1;
        // Reach out from the bulk, but never past what was actually observed: extending to an IQR-derived
        // bound the curve never reaches would frame empty space.
        double lo = Math.max(seen.get(0), q1 - SPREAD * iqr);
        double hi = Math.min(seen.get(seen.size() - 1), q3 + SPREAD * iqr);
        if (!(hi > lo)) {
            // A constant, or a curve flat to within a rounding error. Give it a window to sit in the middle of.
            double centre = (hi + lo) / 2;
            double half = Math.max(1, Math.abs(centre));
            lo = centre - half;
            hi = centre + half;
        }
        double height = hi - lo;
        if (lo > 0 && lo < height * REACH_TO_ZERO) {
            lo = 0;
        }
        if (hi < 0 && -hi < height * REACH_TO_ZERO) {
            hi = 0;
        }
        double pad = (hi - lo) * PADDING;
        double paddedLo = lo - pad;
        double paddedHi = hi + pad;
        // Breathing room may not carry the frame across an axis the curve itself never crosses. A parabola
        // whose floor is zero drawn in a window starting at -20 has been given twenty units of nothing to sit
        // above, and the reader has to work out that the emptiness means nothing rather than something.
        if (seen.get(0) >= 0 && paddedLo < 0) {
            paddedLo = 0;
        }
        if (seen.get(seen.size() - 1) <= 0 && paddedHi > 0) {
            paddedHi = 0;
        }
        return snap(paddedLo, paddedHi);
    }

    /** Every finite endpoint of every bounded column — the sample the fit is computed from. */
    private static List<Double> sample(Expr expr, Frame over) {
        List<Double> seen = new ArrayList<>(2 * SAMPLES);
        Endpoints collector = new Endpoints(seen);
        for (int i = 0; i < SAMPLES; i++) {
            expr.enclose(over.column(i, SAMPLES)).emitTo(collector);
        }
        return seen;
    }

    /**
     * The same measurement over a surface's floor: a square grid of about {@link #SAMPLES} cells, so framing a
     * surface costs what framing a curve does rather than the square of it.
     */
    private static List<Double> sample(Expr expr, Volume over, String xName, String yName) {
        int side = Math.max(2, (int) Math.round(Math.sqrt(SAMPLES)));
        List<Double> seen = new ArrayList<>(2 * side * side);
        Endpoints collector = new Endpoints(seen);
        double xStep = over.xWidth() / side;
        double yStep = over.yDepth() / side;
        for (int ix = 0; ix < side; ix++) {
            Interval xs = new Interval(BigDecimal.valueOf(over.xLo() + ix * xStep),
                                       BigDecimal.valueOf(over.xLo() + (ix + 1) * xStep));
            for (int iy = 0; iy < side; iy++) {
                Interval ys = new Interval(BigDecimal.valueOf(over.yLo() + iy * yStep),
                                           BigDecimal.valueOf(over.yLo() + (iy + 1) * yStep));
                expr.enclose(Cell.of(xName, xs, yName, ys)).emitTo(collector);
            }
        }
        return seen;
    }

    /** Widen {@code [lo, hi]} outward to a whole number of tick steps, so the frame's edges are gridlines. */
    private static double[] snap(double lo, double hi) {
        double step = tickStep(hi - lo, TICKS);
        return new double[]{Math.floor(lo / step) * step, Math.ceil(hi / step) * step};
    }

    /** The {@code p} quantile of a sorted list, by linear interpolation between the two straddling samples. */
    private static double quantile(List<Double> sorted, double p) {
        double position = p * (sorted.size() - 1);
        int below = (int) Math.floor(position);
        int above = Math.min(below + 1, sorted.size() - 1);
        double fraction = position - below;
        return sorted.get(below) * (1 - fraction) + sorted.get(above) * fraction;
    }

    /**
     * The sink the framing pass measures through: a bounded column contributes both endpoints, and the other
     * two answers contribute nothing. Written as a sink for the same reason classification is — the policy has
     * no business switching on the arithmetic's types.
     */
    private record Endpoints(List<Double> into) implements Enclosure.Sink {

        @Override
        public void bounded(BigDecimal lo, BigDecimal hi) {
            add(lo.doubleValue());
            add(hi.doubleValue());
        }

        @Override
        public void unbounded() {
            // A pole has no height to frame around, and letting it contribute would be letting it win.
        }

        @Override
        public void undefined() {
            // Nor has a gap.
        }

        private void add(double v) {
            if (Double.isFinite(v)) {
                into.add(v);
            }
        }
    }
}
