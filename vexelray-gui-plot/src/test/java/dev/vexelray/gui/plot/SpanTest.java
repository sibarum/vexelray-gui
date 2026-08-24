package dev.vexelray.gui.plot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Classification: {@code (enclosure, frame) → span}. The three answers of the arithmetic, clipped to a window.
 *
 * <p>Everything here is checked through the {@link Span.Sink} a renderer would implement, rather than by
 * unwrapping the span with {@code instanceof} — partly because that is the seam that has to work, and partly
 * because a test that switches on the types would be quietly asserting that consumers may.
 */
class SpanTest {

    private static final Frame FRAME = new Frame(-1, 1, -10, 10);

    @Test
    void aBoundedColumnInsideTheFrameBecomesAClippedStretch() {
        // [0, 10] is the top half of a frame running -10..10, and fractions run downward.
        assertEquals("curve 4: 0.0..0.5", classify(Interval.of(0, 10)));
        assertEquals("curve 4: 0.5..1.0", classify(Interval.of(-10, 0)));
        assertEquals("curve 4: 0.25..0.75", classify(Interval.of(-5, 5)));
    }

    /** A thin enclosure is a thin span. Giving it a visible thickness is the renderer's decision, not this one. */
    @Test
    void aFlatCurveKeepsZeroThickness() {
        assertEquals("curve 4: 0.5..0.5", classify(Interval.of(0, 0)));
    }

    @Test
    void aColumnLeavingTheFrameIsClippedToIt() {
        assertEquals("curve 4: 0.0..0.75", classify(Interval.of(-5, 40)));
        assertEquals("curve 4: 0.0..1.0", classify(Interval.of(-40, 40)));
    }

    /**
     * Off-frame draws as nothing, and so does a domain gap. They are different facts and the same picture, so
     * they are one span — the classifier does not get to invent a way of showing a distinction that is not
     * visible.
     */
    @Test
    void aColumnEntirelyOutsideTheFrameIsBlank() {
        assertEquals("blank 4", classify(Interval.of(11, 12)));
        assertEquals("blank 4", classify(Interval.of(-99, -10.5)));
        assertEquals("blank 4", classify(Enclosure.UNDEFINED));
    }

    /** The pole. Nothing is known about where inside the column the finite part runs, so the column is painted. */
    @Test
    void anUnboundedColumnIsPaintedWhole() {
        assertEquals("fill 4", classify(Enclosure.UNBOUNDED));
    }

    /** The two singletons are shapes, not places: one instance serves every column that needs one. */
    @Test
    void theBlankAndFillSpansAreShared() {
        assertSame(Span.BLANK, Span.of(Enclosure.UNDEFINED, FRAME));
        assertSame(Span.FILL, Span.of(Enclosure.UNBOUNDED, FRAME));
        assertSame(Span.BLANK, Span.of(Interval.of(50, 60), FRAME));
    }

    /**
     * The whole point of classifying against a frame rather than baking the frame into the enclosure: the same
     * enclosure, reclassified, is a whole y-axis transform — which is why panning and zooming in y never
     * re-evaluates anything.
     */
    @Test
    void reclassifyingIsTheEntireYTransform() {
        Enclosure enclosure = Interval.of(2, 3);
        assertEquals("curve 4: 0.35..0.4", render(4, Span.of(enclosure, FRAME)));
        assertEquals("curve 4: 0.0..0.5", render(4, Span.of(enclosure, FRAME.withY(1, 3))));
        assertEquals("blank 4", render(4, Span.of(enclosure, FRAME.withY(-9, -8))));
    }

    /**
     * The two halves meeting: an expression, enclosed over real columns, classified into what a renderer draws.
     * {@code 1/x} over a frame straddling the origin is the whole story in eight columns — the two columns whose
     * x-interval contains zero are painted, and every other column is an ordinary stretch of curve. No solver
     * was asked where the pole is, and none was told.
     */
    @Test
    void anExpressionsPoleArrivesAsAPaintedColumn() {
        Expr reciprocal = new Expr.Div(new Expr.Const(1.0), new Expr.Param("x"));
        Frame frame = new Frame(-1, 1, -10, 10);
        List<String> kinds = new ArrayList<>();
        Span.Sink sink = kindsInto(kinds);
        for (int i = 0; i < 8; i++) {
            Span.of(reciprocal.enclose(frame.column(i, 8)), frame).emitTo(i, sink);
        }
        assertEquals(List.of("curve", "curve", "curve", "fill", "fill", "curve", "curve", "curve"), kinds);
    }

    private static String classify(Enclosure enclosure) {
        return render(4, Span.of(enclosure, FRAME));
    }

    private static String render(int column, Span span) {
        List<String> drawn = new ArrayList<>();
        span.emitTo(column, recordingInto(drawn));
        return drawn.get(0);
    }

    /** The same, recording only which of the three calls arrived. */
    private static Span.Sink kindsInto(List<String> kinds) {
        return new Span.Sink() {
            @Override
            public void curve(int column, double top, double bottom) {
                kinds.add("curve");
            }

            @Override
            public void fill(int column) {
                kinds.add("fill");
            }

            @Override
            public void blank(int column) {
                kinds.add("blank");
            }
        };
    }

    /** A {@link Span.Sink} that writes down what it was asked to draw — the smallest possible renderer. */
    private static Span.Sink recordingInto(List<String> drawn) {
        return new Span.Sink() {
            @Override
            public void curve(int column, double top, double bottom) {
                drawn.add("curve " + column + ": " + top + ".." + bottom);
            }

            @Override
            public void fill(int column) {
                drawn.add("fill " + column);
            }

            @Override
            public void blank(int column) {
                drawn.add("blank " + column);
            }
        };
    }
}
