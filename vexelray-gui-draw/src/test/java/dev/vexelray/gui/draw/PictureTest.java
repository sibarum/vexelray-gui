package dev.vexelray.gui.draw;

import dev.vexelray.canvas.Color;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The picture value and the sketch that builds it, checked <b>through the sink</b> rather than by reading the
 * mark records back — that is the seam every consumer sees, and a test that unwrapped the records would be
 * quietly asserting that consumers may.
 */
class PictureTest {

    /** A colour a test names to tell two marks apart. Not a palette: nothing here is drawn for a person. */
    private static final Color INK = Color.rgb(0.1f, 0.2f, 0.3f);

    /** Every operation, in the order it arrived, as text — the whole of what a consumer of a picture receives. */
    private static final class Recorder implements Picture.Sink {

        private final List<String> ops = new ArrayList<>();

        @Override
        public void fill(double x, double y, double w, double h, double rTop, double rBottom, Color color,
                         String tag) {
            ops.add("fill " + x + "," + y + " " + w + "x" + h + " r" + rTop + "/" + rBottom + tagged(tag));
        }

        @Override
        public void outline(double x, double y, double w, double h, double rTop, double rBottom, double width,
                            Color color, String tag) {
            ops.add("outline " + x + "," + y + " " + w + "x" + h + " r" + rTop + "/" + rBottom + " w" + width
                    + tagged(tag));
        }

        @Override
        public void line(double x0, double y0, double x1, double y1, double thickness, Color color, String tag) {
            ops.add("line " + x0 + "," + y0 + "->" + x1 + "," + y1 + " w" + thickness + tagged(tag));
        }

        @Override
        public void glyphs(String text, double x, double y, double sizePx, Color color, String tag) {
            ops.add("glyphs \"" + text + "\" " + x + "," + y + " @" + sizePx + tagged(tag));
        }

        private static String tagged(String tag) {
            return tag == null ? "" : " [" + tag + "]";
        }
    }

    private static List<String> emitted(Picture picture) {
        Recorder r = new Recorder();
        picture.emitTo(r);
        return r.ops;
    }

    @Test
    void marksReachTheSinkInSubmissionOrder() {
        // Paint order is submission order, which is what lets a drawing say "this goes on top" by saying it last.
        Picture p = new Sketch()
                .fill(0, 0, 10, 4, INK)
                .line(0, 0, 10, 4, 1, INK)
                .text("y", 2, 3, 12, INK)
                .picture();
        assertEquals(List.of("fill 0.0,0.0 10.0x4.0 r0.0/0.0",
                        "line 0.0,0.0->10.0,4.0 w1.0",
                        "glyphs \"y\" 2.0,3.0 @12.0"),
                emitted(p));
    }

    @Test
    void aCircleIsARoundedBoxWithNothingStraightLeft() {
        // The alphabet has no circle, because the engine's rounded-box SDF already is one at r = half the side.
        assertEquals(List.of("fill 5.0,15.0 10.0x10.0 r5.0/5.0"),
                emitted(new Sketch().circle(10, 20, 5, INK).picture()));
    }

    @Test
    void aTagIsStampedOnEveryMarkUntilItIsChanged() {
        // The stamping idiom the canvas uses for its clip and translation: said once, not per call.
        Picture p = new Sketch()
                .tag("grid").line(0, 0, 0, 10, 1, INK).line(5, 0, 5, 10, 1, INK)
                .tag("curve").line(0, 10, 5, 2, 2, INK)
                .tag(null).text("0", 0, 12, 9, INK)
                .picture();
        assertEquals(List.of("line 0.0,0.0->0.0,10.0 w1.0 [grid]",
                        "line 5.0,0.0->5.0,10.0 w1.0 [grid]",
                        "line 0.0,10.0->5.0,2.0 w2.0 [curve]",
                        "glyphs \"0\" 0.0,12.0 @9.0"),
                emitted(p));
    }

    @Test
    void aPolylineIsOneLinePerSegment() {
        // A joined curve needs no new operation, so no consumer has to learn one: three points, two segments,
        // and the round caps the canvas draws anyway are what make the joint look joined.
        Picture p = new Sketch()
                .polyline(new double[]{0, 1, 2}, new double[]{0, 3, 1}, 1.5, INK)
                .picture();
        assertEquals(List.of("line 0.0,0.0->1.0,3.0 w1.5", "line 1.0,3.0->2.0,1.0 w1.5"), emitted(p));
    }

    @Test
    void aPolylineOfOnePointDrawsNothingAndMismatchedArraysAreRejected() {
        assertTrue(new Sketch().polyline(new double[]{1}, new double[]{2}, 1, INK).picture().isEmpty(),
                "one point is no segment: a curve of a single sample has nothing to join");
        assertThrows(IllegalArgumentException.class,
                () -> new Sketch().polyline(new double[]{0, 1}, new double[]{0}, 1, INK));
    }

    @Test
    void placingASubPictureShiftsItsMarksAndKeepsTheirTags() {
        Picture legend = new Sketch().tag("legend").fill(0, 0, 4, 2, INK).picture();
        Picture p = new Sketch().place(legend, 100, 50).picture();
        assertEquals(List.of("fill 100.0,50.0 4.0x2.0 r0.0/0.0 [legend]"), emitted(p));
        assertEquals(List.of("fill 0.0,0.0 4.0x2.0 r0.0/0.0 [legend]"), emitted(legend),
                "the sub-picture is a value, so placing it cannot have moved it");
    }

    @Test
    void shiftingByNothingIsTheSamePicture() {
        // Worth pinning: a renderer that offsets by a scroll position of zero should not be copying a list per
        // frame to say so.
        Picture p = new Sketch().fill(0, 0, 1, 1, INK).picture();
        assertSame(p, p.shifted(0, 0));
    }

    @Test
    void aSketchStaysUsableAfterTheValueIsTakenOut() {
        Sketch s = new Sketch().fill(0, 0, 1, 1, INK);
        Picture first = s.picture();
        s.fill(1, 1, 1, 1, INK);
        assertEquals(1, first.marks().size(), "the value taken out is independent of the builder");
        assertEquals(2, s.picture().marks().size());
    }

    @Test
    void anAuthoredMarkKindNoConsumerHasSeenStillDraws() {
        // The whole point of the mark being open and the sink closed: a kind written after a consumer can only
        // express itself in operations that consumer already implements, so it works there with no change.
        record Cross(double cx, double cy, double r, Color color) implements Picture.Mark {
            @Override
            public Picture.Mark shifted(double dx, double dy) {
                return new Cross(cx + dx, cy + dy, r, color);
            }

            @Override
            public void emitTo(Picture.Sink sink) {
                sink.line(cx - r, cy - r, cx + r, cy + r, 1, color, "cross");
                sink.line(cx - r, cy + r, cx + r, cy - r, 1, color, "cross");
            }
        }
        Picture p = new Sketch().add(new Cross(10, 10, 2, INK)).picture();
        assertEquals(List.of("line 8.0,8.0->12.0,12.0 w1.0 [cross]", "line 8.0,12.0->12.0,8.0 w1.0 [cross]"),
                emitted(p));
        assertEquals(List.of("line 108.0,8.0->112.0,12.0 w1.0 [cross]", "line 108.0,12.0->112.0,8.0 w1.0 [cross]"),
                emitted(p.shifted(100, 0)), "and it travels, because that is the other method it had to write");
    }

    @Test
    void theEmptyPictureSaysSo() {
        assertTrue(Picture.EMPTY.isEmpty());
        assertTrue(new Sketch().picture().isEmpty());
        assertEquals(List.of(), emitted(Picture.EMPTY));
    }
}
