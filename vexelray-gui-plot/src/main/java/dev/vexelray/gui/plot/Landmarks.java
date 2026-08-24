package dev.vexelray.gui.plot;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Where the interesting places on a curve are: roots, intercepts, extrema, inflections and vertical asymptotes,
 * over one x window.
 *
 * <h2>The arithmetic proposes; something else disposes</h2>
 * Every landmark here is found twice. A cheap scan over the window <b>proposes</b> a candidate — a sign change,
 * a column the arithmetic could not bound — and a second, narrower test <b>confirms</b> it before it is
 * reported. The two tests are deliberately not the same test:
 *
 * <ul>
 *   <li>a <b>root</b> is proposed by a sign change between adjacent samples and confirmed by bisection, which is
 *       the intermediate value theorem applied to a bracket that has been checked for continuity;
 *   <li>an <b>extremum</b> is a root of {@code f′}, found by the same routine, and which of the two it is comes
 *       from the direction {@code f′} crossed in — there is no second-derivative test and no ambiguity;
 *   <li>an <b>inflection</b> is a root of {@code f″}, dropped where it lands on an extremum or a pole;
 *   <li>a <b>pole</b> is proposed by an unbounded column and confirmed by <b>subdivision</b>: the column must
 *       still hold an unbounded sub-column at sixteen times the resolution. This is what separates a real
 *       asymptote from the over-estimation smear either side of one, and it matters twice — as the test, and as
 *       the thing the bisection converges on, since bisecting the raw enclosure would converge on the edge of
 *       the smear rather than on the pole.
 * </ul>
 *
 * <p>Every candidate whose bracket <b>straddles a pole</b> is thrown away, whatever kind it is. The finite
 * samples either side of an asymptote fake a sign change, so a discontinuity looks exactly like a root and a
 * blow-up looks exactly like a turning point; without this rejection {@code 1÷(x²−1)} reports four extrema that
 * are not there. (Both this and the subdivision probe are carried over from the Pontif implementation, where
 * both arrived as bug fixes rather than as design.)
 *
 * <h2>Sanity is a requirement</h2>
 * {@code tan(1÷x)} has infinitely many roots, extrema and asymptotes in any neighbourhood of the origin, so a
 * finder with no ceiling is a finder that will one day try to draw ten thousand markers. Two rules keep it
 * bounded, and both are about honesty rather than performance:
 *
 * <ul>
 *   <li>a <b>dense</b> region — a column where most sub-columns stay unbounded under subdivision — gets no
 *       markers at all. The painted block already says "there is detail here finer than a pixel", and stacking
 *       asymptote markers on top of it says the same thing worse;
 *   <li>a kind that overflows {@link #PER_KIND} is dropped <b>entirely</b>, and named in
 *       {@link Survey#suppressed} so a caller can say so. A truncated set of markers is a lie about which ones
 *       are there; an absent set with a notice is not.
 * </ul>
 *
 * <h2>Two landmarks may share a place</h2>
 * {@code x²} has a root and a minimum at the origin; {@code x³ − 3x} has a root and an inflection there. Both
 * facts are reported, because both are true and neither implies the other, and collapsing them here would be
 * this class deciding which one a reader wanted. A view that draws one dot per position picks between them; a
 * view that names what is under the pointer can name both. The single exception is the y-intercept, which is
 * dropped wherever anything else already stands — that the curve crosses the y-axis is true of every curve on
 * the page, and it is the least of whatever else is being said about that spot.
 *
 * <h2>What is not looked for</h2>
 * Horizontal asymptotes, holes, and the difference between a pole and a removable discontinuity. The last is not
 * an oversight but the dependency problem: {@code x÷x} encloses to unbounded at the origin because interval
 * arithmetic cannot see that the two occurrences of {@code x} move together, and no amount of scanning
 * distinguishes that from a real pole. It would take affine arithmetic, one layer down.
 */
public final class Landmarks {

    /** How many of any one kind may be reported before the whole kind is suppressed instead. */
    public static final int PER_KIND = 24;

    /** Probe columns across the window. Finer than a plot's own columns, so features close together resolve. */
    private static final int PROBES = 512;

    /** Sub-columns a candidate pole column is split into. Tupper's recurse-on-"can't tell", one level deep. */
    private static final int SUBDIVISIONS = 16;

    /** More than this many unbounded sub-columns and the region is dense: unresolvable, and left to the fill. */
    private static final int DENSE = SUBDIVISIONS / 2;

    /** Halvings a confirmed candidate is pinned by. Enough to put an integer root on the integer. */
    private static final int HALVINGS = 60;

    private Landmarks() {
    }

    /** The landmarks of {@code expr} across {@code [xLo, xHi]}, with nothing said about what was suppressed. */
    public static List<Landmark> find(Expr expr, String parameter, double xLo, double xHi) {
        return survey(expr, parameter, xLo, xHi).found();
    }

    /**
     * The landmarks of {@code expr} across {@code [xLo, xHi]}, and which kinds were dropped for being too many
     * to draw.
     *
     * <p>Pure and self-contained: it evaluates what it needs and caches nothing, because it is called when a
     * window moves and the window it was last called for is gone.
     */
    public static Survey survey(Expr expr, String parameter, double xLo, double xHi) {
        if (expr == null || parameter == null) {
            throw new IllegalArgumentException("a curve and the name of its variable");
        }
        if (!(xHi > xLo) || !Double.isFinite(xLo) || !Double.isFinite(xHi)) {
            throw new IllegalArgumentException("not an x window: [" + xLo + ", " + xHi + "]");
        }
        Scan scan = new Scan(expr, xLo, (xHi - xLo) / PROBES);
        List<Landmark> found = new ArrayList<>();
        Set<Landmark.Kind> suppressed = EnumSet.noneOf(Landmark.Kind.class);

        offer(found, suppressed, Landmark.Kind.POLE, poles(scan));
        offer(found, suppressed, Landmark.Kind.ROOT, roots(scan));

        // The derivatives, if this expression has any. A node that cannot differentiate itself takes the whole
        // expression's extrema with it -- stated on Expr.derivative, and silently correct here: an absent
        // derivative simply contributes nothing, rather than falling back to a second algorithm that would have
        // to be kept in agreement with this one.
        Optional<Expr> slope = expr.derivative(parameter);
        if (slope.isPresent()) {
            Scan over = new Scan(slope.get(), xLo, scan.step);
            List<Landmark> turns = turningPoints(over, scan);
            offer(found, suppressed, Landmark.Kind.MINIMUM, only(turns, Landmark.Kind.MINIMUM));
            offer(found, suppressed, Landmark.Kind.MAXIMUM, only(turns, Landmark.Kind.MAXIMUM));
            slope.get().derivative(parameter).ifPresent(bend -> {
                List<Landmark> bends = inflections(new Scan(bend, xLo, scan.step), scan, turns);
                offer(found, suppressed, Landmark.Kind.INFLECTION, bends);
            });
        }
        // Last, so that it yields to everything: a curve that has a root, a floor or an asymptote where it meets
        // the y-axis is better described by that than by the crossing, which is true of every curve on the page.
        intercept(scan, xLo, xHi, found);
        found.sort((a, b) -> Double.compare(a.x(), b.x()));
        return new Survey(List.copyOf(found), Set.copyOf(suppressed));
    }

    /** What a survey found, and what it decided not to show. */
    public record Survey(List<Landmark> found, Set<Landmark.Kind> suppressed) {

        /** A sentence naming the suppressed kinds, or empty when nothing was. For a status line. */
        public String notice() {
            if (suppressed.isEmpty()) {
                return "";
            }
            List<String> names = suppressed.stream().map(k -> k.label() + "s").sorted().toList();
            return "too many " + String.join(", ", names) + " to mark";
        }
    }

    // --- the kinds --------------------------------------------------------------------------------------

    /**
     * The vertical asymptotes. A column that encloses ±∞ is only a candidate: the over-estimation either side
     * of a real pole leaves a band of unbounded columns, and only the one that still holds an unbounded
     * sub-column under subdivision is the pole. A dense column is skipped entirely.
     */
    private static List<Landmark> poles(Scan f) {
        List<Landmark> out = new ArrayList<>();
        for (int i = 0; i < PROBES; i++) {
            if (!f.columnUnbounded(i)) {
                continue;
            }
            double a = f.at(i);
            double b = f.at(i + 1);
            int held = f.polesWithin(a, b);
            if (held < 1 || held > DENSE) {
                continue;                              // a smear column, or unresolvable detail
            }
            out.add(Landmark.pole(bisect(a, b, (left, mid) -> f.polesWithin(left, mid) >= 1)));
        }
        return dedup(out, f.step);
    }

    /** The roots of the scanned expression: where the curve itself crosses the x-axis. */
    private static List<Landmark> roots(Scan f) {
        List<Landmark> out = new ArrayList<>();
        for (Bracket bracket : brackets(f)) {
            double x = refine(f, bracket);
            // A confirmed crossing sits at zero by construction, but the height is read back rather than
            // assumed: a bracket that bisected into a gap has no value there, and a landmark with no value is
            // not a root of anything.
            Double y = value(f.expr, x);
            if (y == null) {
                continue;
            }
            out.add(new Landmark(Landmark.Kind.ROOT, x, y));
        }
        return dedup(out, f.step);
    }

    /** The extrema: roots of {@code f′}, named by which way {@code f′} crossed. */
    private static List<Landmark> turningPoints(Scan slope, Scan f) {
        List<Landmark> out = new ArrayList<>();
        for (Bracket bracket : brackets(slope)) {
            // The bracket is over f'; the continuity that matters is f's, since a blow-up in the curve fakes a
            // turning point in its slope. Checking both is checking the same discontinuity twice.
            if (f.straddlesPole(slope.at(bracket.index()), slope.at(bracket.index() + 1))) {
                continue;
            }
            double x = refine(slope, bracket);
            Double y = value(f.expr, x);
            if (y == null || !Double.isFinite(y)) {
                continue;
            }
            out.add(new Landmark(bracket.rising() ? Landmark.Kind.MINIMUM : Landmark.Kind.MAXIMUM, x, y));
        }
        return dedup(out, slope.step);
    }

    /** The inflections: roots of {@code f″} that are not already a turning point. */
    private static List<Landmark> inflections(Scan bend, Scan f, List<Landmark> turns) {
        List<Landmark> out = new ArrayList<>();
        for (Bracket bracket : brackets(bend)) {
            if (f.straddlesPole(bend.at(bracket.index()), bend.at(bracket.index() + 1))) {
                continue;
            }
            double x = refine(bend, bracket);
            Double y = value(f.expr, x);
            if (y == null || !Double.isFinite(y)) {
                continue;
            }
            // A stationary inflection -- x³ at the origin -- is already reported as neither a minimum nor a
            // maximum, so there is nothing to collide with; a turning point that happens to sit on a zero of
            // f″ is reported as the turning point, which is the more useful of the two things it is.
            if (near(turns, x, bend.step)) {
                continue;
            }
            out.add(new Landmark(Landmark.Kind.INFLECTION, x, y));
        }
        return dedup(out, bend.step);
    }

    /** The y-intercept, unless a root has already claimed the origin — a root at zero is the better statement. */
    private static void intercept(Scan f, double xLo, double xHi, List<Landmark> found) {
        if (xLo > 0 || xHi < 0) {
            return;
        }
        Double y = value(f.expr, 0);
        if (y == null || !Double.isFinite(y)) {
            return;
        }
        if (near(found, 0, f.step)) {
            return;
        }
        found.add(new Landmark(Landmark.Kind.Y_INTERCEPT, 0, y));
    }

    // --- proposing, and confirming ----------------------------------------------------------------------

    /**
     * Every bracket in the scan where the value changes sign between adjacent samples, both of them real and
     * the span between them free of poles.
     *
     * <p>The crossing test is biased so a root landing exactly on a sample is caught <b>once</b>: a sample of
     * zero closes the bracket that reached it and does not open the next one.
     */
    private static List<Bracket> brackets(Scan f) {
        List<Bracket> out = new ArrayList<>();
        for (int i = 0; i < PROBES; i++) {
            Double before = f.sample(i);
            Double after = f.sample(i + 1);
            if (before == null || after == null) {
                continue;                                            // a pole or a gap carries no sign
            }
            boolean crossing = before < 0 ? after >= 0 : before > 0 && after <= 0;
            if (!crossing) {
                continue;
            }
            if (f.straddlesPole(f.at(i), f.at(i + 1))) {
                continue;                                            // a discontinuity, not a crossing
            }
            out.add(new Bracket(i, before < 0));
        }
        return out;
    }

    /** Bisect a confirmed sign-change bracket down to where the sign changes. */
    private static double refine(Scan f, Bracket bracket) {
        double a = f.at(bracket.index());
        double b = f.at(bracket.index() + 1);
        boolean negativeAtA = bracket.rising();
        return bisect(a, b, (left, mid) -> {
            Double v = f.valueAt(mid);
            if (v == null) {
                return false;                        // unreadable: narrow from the other side instead
            }
            return negativeAtA ? v >= 0 : v <= 0;    // the sign has already turned, so the crossing is behind
        });
    }

    /**
     * Halve {@code [a, b]} while {@code inLower} says the answer is in the lower half. The one numerical
     * routine here, shared by every kind — a root, a turning point and a pole are the same search over
     * different predicates, and writing it once is what keeps them from disagreeing about precision.
     */
    private static double bisect(double a, double b, InLowerHalf inLower) {
        double lo = a;
        double hi = b;
        for (int i = 0; i < HALVINGS; i++) {
            double mid = (lo + hi) / 2;
            if (mid <= lo || mid >= hi) {
                break;                               // adjacent doubles: there is nothing finer to say
            }
            if (inLower.test(lo, mid)) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return (lo + hi) / 2;
    }

    // --- housekeeping -----------------------------------------------------------------------------------

    /** Add {@code candidates} if there are few enough of them to draw, and otherwise record the refusal. */
    private static void offer(List<Landmark> into, Set<Landmark.Kind> suppressed,
                              Landmark.Kind kind, List<Landmark> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        if (candidates.size() > PER_KIND) {
            suppressed.add(kind);
            return;
        }
        into.addAll(candidates);
    }

    private static List<Landmark> only(List<Landmark> all, Landmark.Kind kind) {
        return all.stream().filter(l -> l.kind() == kind).toList();
    }

    /** Collapse landmarks of the same kind that landed within half a probe of each other. */
    private static List<Landmark> dedup(List<Landmark> found, double step) {
        List<Landmark> out = new ArrayList<>(found.size());
        for (Landmark candidate : found) {
            boolean duplicate = out.stream().anyMatch(kept -> kept.kind() == candidate.kind()
                    && Math.abs(kept.x() - candidate.x()) < step / 2);
            if (!duplicate) {
                out.add(candidate);
            }
        }
        return out;
    }

    private static boolean near(List<Landmark> found, double x, double step) {
        return found.stream().anyMatch(l -> Math.abs(l.x() - x) < step / 2);
    }

    /** The value of an expression at a point, or null where there is no finite one. */
    private static Double value(Expr expr, double x) {
        Reading read = new Reading();
        expr.enclose(Interval.at(x)).emitTo(read);
        return read.middle;
    }

    /** One scanned expression: its probe grid, its samples, and its columns, each computed once. */
    private static final class Scan {

        private final Expr expr;
        private final double xLo;
        private final double step;
        private final Double[] samples = new Double[PROBES + 1];
        private final boolean[] sampled = new boolean[PROBES + 1];
        private final Boolean[] columns = new Boolean[PROBES];

        Scan(Expr expr, double xLo, double step) {
            this.expr = expr;
            this.xLo = xLo;
            this.step = step;
        }

        double at(int index) {
            return xLo + index * step;
        }

        /** The value at probe {@code index}, memoised — the scans ask for the same points repeatedly. */
        Double sample(int index) {
            if (!sampled[index]) {
                samples[index] = value(expr, at(index));
                sampled[index] = true;
            }
            return samples[index];
        }

        /** The value at an arbitrary x, off the probe grid — what the bisection asks for. Not worth memoising. */
        Double valueAt(double x) {
            return value(expr, x);
        }

        /** Whether probe column {@code index} encloses ±∞ — the candidate test, memoised for the same reason. */
        boolean columnUnbounded(int index) {
            if (columns[index] == null) {
                columns[index] = unbounded(expr, at(index), at(index + 1));
            }
            return columns[index];
        }

        /** Whether {@code [a, b]} contains a discontinuity this expression blows up at. */
        boolean straddlesPole(double a, double b) {
            return unbounded(expr, a, b);
        }

        /** How many of {@link #SUBDIVISIONS} sub-columns of {@code [a, b]} are still unbounded. */
        int polesWithin(double a, double b) {
            double width = (b - a) / SUBDIVISIONS;
            int count = 0;
            for (int i = 0; i < SUBDIVISIONS; i++) {
                if (unbounded(expr, a + i * width, a + (i + 1) * width)) {
                    count++;
                }
            }
            return count;
        }
    }

    private static boolean unbounded(Expr expr, double a, double b) {
        Reading read = new Reading();
        expr.enclose(Interval.of(Math.min(a, b), Math.max(a, b))).emitTo(read);
        return read.unbounded;
    }

    /**
     * Whether the answer lies in {@code [lo, mid]} — the predicate {@link #bisect} narrows on. It is handed the
     * live left edge and not only the midpoint because a pole is proven over an <em>interval</em> rather than
     * at a point: a subdivision probe run over the original column would stop shrinking after one halving.
     */
    @FunctionalInterface
    private interface InLowerHalf {

        boolean test(double lo, double mid);
    }

    /** A candidate: which probe column the sign changed in, and whether it changed upward. */
    private record Bracket(int index, boolean rising) {
    }

    /**
     * Reads an enclosure without asking its type — the same inversion classification uses, for the same reason.
     * A bounded answer leaves its midpoint; an unbounded one says so; a gap leaves neither.
     */
    private static final class Reading implements Enclosure.Sink {

        private Double middle;
        private boolean unbounded;

        @Override
        public void bounded(java.math.BigDecimal lo, java.math.BigDecimal hi) {
            double m = lo.add(hi).doubleValue() / 2;
            middle = Double.isFinite(m) ? m : null;
        }

        @Override
        public void unbounded() {
            unbounded = true;
        }

        @Override
        public void undefined() {
            // no value and no blow-up: nothing to say, and null is the saying of it
        }
    }
}
