package dev.vexelray.gui.plot;

/**
 * What one pixel column of a plot shows — the answer to {@code (enclosure, frame) → column kind}, and the last
 * type before pixels.
 *
 * <p>There are three of them, and they are the three the enclosure algebra already distinguishes, clipped to a
 * frame:
 * <ul>
 *   <li>{@link Curve} — a bounded stretch of the column, top and bottom given as fractions of the frame's
 *       height. On a tame column this is a hair thick and reads as a line; on a steep one it is tall, and the
 *       curve really is that tall <em>within this column</em>;
 *   <li>{@link #FILL} — the column runs off to ±∞ inside itself. Painted whole, which is the honest answer: the
 *       expression takes arbitrarily large values somewhere in this pixel, and where the visible part of it
 *       goes is finer than the pixel can say. An asymptote comes out as a full-height stripe, and
 *       {@code tan(1/x)} near the origin comes out as a block — <em>derived</em>, not special-cased;
 *   <li>{@link #BLANK} — nothing to draw: no real value on the column at all, or a real one that lies entirely
 *       outside the frame.
 * </ul>
 *
 * <h2>Blank means two different things, deliberately</h2>
 * A domain gap and a curve that has left the top of the window both draw as nothing, so they are one span. The
 * distinction is real but it is not <em>visible</em>, and inventing a way to show it here would put a decision in
 * the classifier that belongs to whoever is drawing (a renderer that wants to mark off-frame excursions has the
 * enclosure and the frame and can ask again).
 *
 * <h2>Why a sink and not a switch</h2>
 * Same reason as {@link Enclosure#emitTo}: a consumer must stay closed, so it gets a closed set of
 * <em>operations</em> — {@link Sink}'s three calls — rather than a closed set of types to switch on. A renderer
 * implements {@code Sink} once and keeps working when a fourth kind of span is written above it.
 */
public interface Span {

    /** Nothing to draw here — an undefined column, or one whose values are all outside the frame. */
    Span BLANK = new Blank();

    /** The column spills to ±∞ somewhere inside itself: paint it whole. */
    Span FILL = new Fill();

    /**
     * Express this span as one call on {@code sink}. The column index rides through rather than being held in
     * the span, because a span is a shape and not a place: the same {@link #FILL} serves every column that needs
     * one, and caching a classified column would otherwise cache its position too.
     */
    void emitTo(int column, Sink sink);

    /**
     * Classify {@code enclosure} against {@code frame}: clip it to the visible height and say what is left.
     *
     * <p>Pure, and cheap — everything expensive happened in {@link Expr#enclose}. That split is what lets a
     * viewport be panned and zoomed in y without re-evaluating anything: the enclosures are in plot space and
     * do not depend on the frame, so a cache of them survives every transform except one that changes which
     * columns of x are being asked about.
     */
    static Span of(Enclosure enclosure, Frame frame) {
        return of(enclosure, frame.yLo(), frame.yHi());
    }

    /**
     * Classify {@code enclosure} against a bare visible extent — the same clipping, without a rectangle around
     * it.
     *
     * <p>This is the form a surface needs. A cell of {@code (x, y)} encloses to a range of {@code z}, and a
     * range of {@code z} clipped to the visible depth is the <em>same three answers</em> a column's range of
     * {@code y} gives: a bounded stretch, a fill, or nothing. So the classification is not generalised for the
     * third dimension, only un-narrowed — {@link Frame} was never doing anything with x here.
     *
     * <p>Pure, and cheap — everything expensive happened in {@link Expr#enclose}. That split is what lets a
     * viewport be moved along the classified axis without re-evaluating anything: the enclosures are in plot
     * space and do not depend on the extent, so a cache of them survives every transform except one that
     * changes which region of the domain is being asked about.
     */
    static Span of(Enclosure enclosure, double visibleLo, double visibleHi) {
        return Classification.of(enclosure, visibleLo, visibleHi);
    }

    /**
     * What a consumer of spans implements — the closed alphabet a plot reduces to. Three calls, and a consumer
     * that writes all three can draw any plot.
     */
    interface Sink {

        /**
         * A bounded stretch of {@code column}, clipped to the frame. {@code top} and {@code bottom} are
         * fractions of the frame's height measured downward from its top edge, so {@code 0 ≤ top ≤ bottom ≤ 1}.
         * They may be equal: a flat curve encloses to a stretch thinner than any pixel, and giving it a minimum
         * visible thickness is the renderer's business, not the classifier's.
         */
        void curve(int column, double top, double bottom);

        /** {@code column} spills to ±∞ somewhere inside it: the whole height, honestly. */
        void fill(int column);

        /** Nothing to draw in {@code column}. */
        void blank(int column);
    }

    /** A bounded, clipped stretch of a column, in fractions of the frame's height measured downward. */
    record Curve(double top, double bottom) implements Span {

        public Curve {
            if (!(top >= 0) || !(bottom <= 1) || top > bottom) {
                throw new IllegalArgumentException("not a clipped span: [" + top + ", " + bottom + "]");
            }
        }

        @Override
        public void emitTo(int column, Sink sink) {
            sink.curve(column, top, bottom);
        }
    }

    /** The whole column, because the arithmetic says the expression leaves every bound inside it. */
    record Fill() implements Span {

        @Override
        public void emitTo(int column, Sink sink) {
            sink.fill(column);
        }
    }

    /** Nothing. */
    record Blank() implements Span {

        @Override
        public void emitTo(int column, Sink sink) {
            sink.blank(column);
        }
    }
}
