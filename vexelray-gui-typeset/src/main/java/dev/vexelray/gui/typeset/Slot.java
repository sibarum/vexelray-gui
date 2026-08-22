package dev.vexelray.gui.typeset;

/**
 * Where a satellite sits relative to its nucleus in a {@link Box.Attach} — the six named positions that let one
 * primitive cover every attachment in notation and in prose.
 *
 * <p>The corners are <b>geometric</b>, which is why this is a framework enum while a spacing class is a
 * profile-defined index ({@link Profile.Spacing}): there are exactly six places a satellite can go, and no profile
 * will ever want a seventh. What varies between profiles is how far it is shifted, not where it is.
 *
 * <p>The three constructs the previous IR modelled as separate records are all this enum:
 * {@code Script} is {@link #NE} and {@link #SE}, {@code Prescript} is {@link #NW} and {@link #SW}, and
 * {@code UnderOver} is {@link #N} and {@link #S}.
 */
public enum Slot {
    /** Above-right — a superscript, an exponent, a footnote marker. */
    NE,
    /** Below-right — a subscript, an index. */
    SE,
    /** Above-left — a pre-superscript, an isotope's mass number. */
    NW,
    /** Below-left — a pre-subscript, an atomic number. */
    SW,
    /** Directly above, centred — an upper limit on a big operator, a ruby annotation, an over-brace label. */
    N,
    /** Directly below, centred — a lower limit, the {@code x → 0} under a limit. */
    S
}
