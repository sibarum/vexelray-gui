package dev.vexelray.gui.scroll;

import dev.vexelray.gui.core.ScrollRange;

/**
 * Where a bar's thumb is, along its track, for a range: pure arithmetic, so what is drawn and what a press is
 * judged against are the same numbers. In the bar's own px, along its axis.
 *
 * <p>The proportions are the ones the renderer drew the old strip with, so a bar looks as it did: the thumb is
 * inset from the track's ends and sides by a fifth of the thickness, as thick as three fifths of it, as long as the
 * visible fraction of the track and never shorter than two thicknesses, so it stays grabbable over long content.
 *
 * @param start  where the thumb begins along the track
 * @param length how long it is; 0 when there is nothing to scroll
 * @param travel how far its start can move: the track's inner length less the thumb
 * @param inset  the gap at either end of the track, and either side of the thumb
 */
record Thumb(float start, float length, float travel, float inset) {

    /** The thumb on a track {@code track} long and {@code thickness} thick, for {@code range}. */
    static Thumb of(ScrollRange range, float track, float thickness) {
        float inset = 0.2f * thickness;
        float inner = Math.max(0f, track - 2f * inset);
        if (!range.scrolls() || range.content() <= 0f) {
            return new Thumb(inset, 0f, 0f, inset);
        }
        float length = Math.min(inner, Math.max(2f * thickness, inner * (range.view() / range.content())));
        float travel = Math.max(0f, inner - length);
        float max = range.max();
        float frac = max > 0f ? Math.max(0f, Math.min(1f, range.offset() / max)) : 0f;
        return new Thumb(inset + frac * travel, length, travel, inset);
    }

    /** Whether {@code at} along the track is on the thumb. */
    boolean holds(float at) {
        return length > 0f && at >= start && at < start + length;
    }

    /** The offset that puts the thumb's start at {@code thumbStart} along the track, for {@code range}. */
    float offsetFor(float thumbStart, ScrollRange range) {
        if (travel <= 0f) {
            return 0f;
        }
        float frac = Math.max(0f, Math.min(1f, (thumbStart - inset) / travel));
        return frac * range.max();
    }
}
