package dev.vexelray.gui.plot;

/**
 * A place on a curve worth pointing at: what it is, and where.
 *
 * <p>A landmark is a <b>confirmed</b> claim, which is the opposite bias from the one an {@link Enclosure} runs
 * on and is the right one for this job. An enclosure over-approximates because a plotter can live with
 * over-warning and cannot live with drawing through a singularity; a landmark under-approximates because a
 * marker on a picture is a statement that something is <em>there</em>, and one that is not is a lie the reader
 * has no way to check. So {@link Landmarks} finds candidates generously and keeps only what it can prove.
 *
 * @param kind what was found
 * @param x    where, refined well below the resolution of any pixel that will show it
 * @param y    the height there, or {@link Double#NaN} for a {@link Kind#POLE} — ask {@link Kind#hasHeight}
 *             rather than testing the number, because the kind is what decides it
 */
public record Landmark(Kind kind, double x, double y) {

    public Landmark {
        if (kind == null) {
            throw new IllegalArgumentException("a landmark is a kind of thing");
        }
        if (!Double.isFinite(x)) {
            throw new IllegalArgumentException("a landmark is somewhere: x was " + x);
        }
        if (kind.hasHeight() && !Double.isFinite(y)) {
            throw new IllegalArgumentException(kind + " has a height, and it was " + y);
        }
    }

    /** A vertical asymptote at {@code x}. The one kind with no height: that is what makes it one. */
    public static Landmark pole(double x) {
        return new Landmark(Kind.POLE, x, Double.NaN);
    }

    /** What a landmark is. */
    public enum Kind {

        /** A crossing of the x-axis. */
        ROOT("root"),

        /** The crossing of the y-axis — where the curve is when the variable is nothing. */
        Y_INTERCEPT("y-intercept"),

        /** A local minimum: {@code f′} passing from negative to positive. */
        MINIMUM("local minimum"),

        /** A local maximum: {@code f′} passing from positive to negative. */
        MAXIMUM("local maximum"),

        /** A change of curvature: {@code f″} through zero, where the bend turns the other way. */
        INFLECTION("inflection"),

        /** A vertical asymptote — a single, resolvable pole, proven by the arithmetic and pinned by bisection. */
        POLE("vertical asymptote");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        /** What to call this in a sentence a reader sees. */
        public String label() {
            return label;
        }

        /** Whether a landmark of this kind sits at a finite height. Everything but a pole does. */
        public boolean hasHeight() {
            return this != POLE;
        }
    }
}
