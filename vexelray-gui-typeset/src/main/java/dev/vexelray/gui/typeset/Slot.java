package dev.vexelray.gui.typeset;

/**
 * Where a satellite sits relative to its nucleus in a {@link Box.Attach} — the six named positions that let one
 * box kind cover every attachment in notation and in prose.
 *
 * <p>The corners are <b>geometric</b>, which is why this is a framework enum while a spacing class is a
 * profile-defined index ({@link Profile.Spacing}): there are exactly six places a satellite can go, and no profile
 * will ever want a seventh. What varies between profiles is how far it is shifted, not where it is.
 *
 * <p>The three constructs the previous IR modelled as separate records are all this enum: {@code Script} is
 * {@link #NE} and {@link #SE}, {@code Prescript} is {@link #NW} and {@link #SW}, and {@code UnderOver} is
 * {@link #N} and {@link #S}.
 *
 * <p><b>Each constant carries its own geometry</b> rather than being switched on. Three booleans and one offset
 * are enough for {@link Box.Attach} to place any of them without asking which one it has — an enum switched for
 * behaviour is the same failure as a sealed switch, so the behaviour lives here (see {@code DispatchGuardTest}).
 */
public enum Slot {

    /** Above-right — a superscript, an exponent, a footnote marker. */
    NE(false, true, false),
    /** Below-right — a subscript, an index. */
    SE(false, false, false),
    /** Above-left — a pre-superscript, an isotope's mass number. */
    NW(true, true, false),
    /** Below-left — a pre-subscript, an atomic number. */
    SW(true, false, false),
    /** Directly above, centred — an upper limit on a big operator, a ruby annotation, an over-brace label. */
    N(false, true, true),
    /** Directly below, centred — a lower limit, the {@code x → 0} under a limit. */
    S(false, false, true);

    private final boolean leading;
    private final boolean above;
    private final boolean stacked;

    Slot(boolean leading, boolean above, boolean stacked) {
        this.leading = leading;
        this.above = above;
        this.stacked = stacked;
    }

    /** Whether this satellite sits <em>before</em> the nucleus horizontally (a pre-script). */
    public boolean leading() {
        return leading;
    }

    /** Whether this satellite sits above the nucleus's baseline rather than below it. */
    public boolean above() {
        return above;
    }

    /** Whether this satellite is centred over or under the nucleus ({@link #N}/{@link #S}) rather than set beside
     *  it. A stacked satellite clears the nucleus's own ascent or descent; a side one is placed by a fixed shift. */
    public boolean stacked() {
        return stacked;
    }

    /**
     * The baseline shift for a side-set satellite, in em: negative above the nucleus's baseline, positive below.
     * Meaningless for a {@link #stacked()} slot, which clears the nucleus's extent by
     * {@link Profile.Metrics#limitGap()} instead.
     */
    public double sideShift(Profile.Metrics metrics) {
        return above ? -metrics.shiftUp() : metrics.shiftDown();
    }
}
