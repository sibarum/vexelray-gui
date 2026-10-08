package dev.vexelray.gui.widget;

import java.util.Arrays;

/**
 * The places a {@link Slider} may come to rest, as fractions of its travel: ascending, in {@code [0, 1]}.
 *
 * <h2>In fraction space, so the scale is not the lattice's business</h2>
 *
 * A number stepped by {@code 0.25} from 0 to 1, a speed stepped by octaves from an eighth to eight, and four named
 * sizes are all a handful of evenly spaced fractions once the caller has mapped its value onto the track. The
 * mapping — linear, logarithmic, whatever — stays with the caller, who already has it; the lattice only says
 * where along the track a value may sit. So there is one type for all of them and no scale enum.
 *
 * <h2>No points is a lattice too</h2>
 *
 * {@link #none()} has nothing to rest on, so it rests anywhere: {@link #nearest} is the identity, nothing is
 * drawn, and nothing pulls. That is the continuous slider, reached by the same code with an empty set rather
 * than by a mode — which is also why a very fine lattice degrades into it smoothly instead of switching.
 */
public interface Lattice {

    /** How many points. Zero means the slider is continuous. */
    int size();

    /** The {@code i}th point, ascending, in {@code [0, 1]}. */
    float point(int i);

    /** Index of the last point at or before {@code f}, or -1 if every point is after it. */
    default int below(float f) {
        int lo = 0;
        int hi = size() - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (point(mid) <= f) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /** The point closest to {@code f}; {@code f} itself if there are none. */
    default float nearest(float f) {
        int n = size();
        if (n == 0) {
            return f;
        }
        int i = below(f);
        if (i < 0) {
            return point(0);
        }
        if (i == n - 1) {
            return point(i);
        }
        float a = point(i);
        float b = point(i + 1);
        return f - a <= b - f ? a : b;
    }

    /** Nothing to rest on: the continuous slider. */
    static Lattice none() {
        return Even.NONE;
    }

    /**
     * Points every {@code spacing} from 0, and the far end — which is a point even where {@code spacing} does not
     * divide the travel, because a slider whose last value cannot be reached is one whose range is a lie. A
     * spacing of zero or less is {@link #none()}.
     */
    static Lattice every(double spacing) {
        if (!(spacing > 0)) {
            return Even.NONE;
        }
        // Strictly-before-the-end points, then the end: a near miss from floating point is the end, not a
        // separate point a hair before it.
        int inside = (int) Math.ceil(1.0 / spacing - 1e-6);
        return new Even(spacing, Math.max(1, inside) + 1);
    }

    /** {@code intervals} equal steps across the travel: {@code intervals + 1} points, both ends among them. */
    static Lattice intervals(int intervals) {
        return intervals <= 0 ? Even.NONE : every(1.0 / intervals);
    }

    /** These points, in any order; each clamped into {@code [0, 1]}, duplicates kept once. */
    static Lattice of(double... points) {
        double[] sorted = Arrays.stream(points).map(p -> Math.clamp(p, 0, 1)).sorted().distinct().toArray();
        float[] fs = new float[sorted.length];
        for (int i = 0; i < fs.length; i++) {
            fs[i] = (float) sorted[i];
        }
        return new Listed(fs);
    }

    /** Evenly spaced from 0, with the far end appended. */
    record Even(double spacing, int size) implements Lattice {

        static final Even NONE = new Even(0, 0);

        @Override
        public float point(int i) {
            return i >= size - 1 ? 1f : (float) (i * spacing);
        }

        @Override
        public int below(float f) {
            if (size == 0 || f < 0f) {
                return -1;
            }
            // Arithmetic rather than a search, so a lattice of ten thousand points costs what four do.
            int i = Math.min(size - 1, (int) Math.floor(f / spacing + 1e-6));
            return f >= 1f ? size - 1 : (point(i) <= f ? i : i - 1);
        }
    }

    /** An explicit set of points. */
    record Listed(float[] points) implements Lattice {

        @Override
        public int size() {
            return points.length;
        }

        @Override
        public float point(int i) {
            return points[i];
        }
    }
}
