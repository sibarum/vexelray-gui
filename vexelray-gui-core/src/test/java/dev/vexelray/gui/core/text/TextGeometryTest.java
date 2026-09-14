package dev.vexelray.gui.core.text;

import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The compute phase for text, built and run with <b>no {@code Gui} anywhere in the test</b>.
 *
 * <p>That absence is the point rather than a convenience. This code used to live on {@code Gui}, where reaching
 * it meant constructing the composition root, the frame loop and the bus — so nothing tested it directly, and
 * what it computed was only ever observed through a laid-out tree. A component that cannot be built alone has
 * been relocated rather than extracted (docs/plans/gui-decomposition.md §4), and this is what says it was not.
 */
class TextGeometryTest {

    /** A measurer with a glyph atlas: every character {@value #ADVANCE} px wide, every line {@value #LINE_H}. */
    private static final float ADVANCE = 10f;
    private static final float LINE_H = 16f;

    private static final TextMeasurer FIXED_PITCH = new TextMeasurer() {
        @Override
        public float intrinsic(RetainedNode n, Axis axis, float textSizePx) {
            String s = n.textString();
            return axis == Axis.VERTICAL ? LINE_H : (s == null ? 0f : s.length() * ADVANCE);
        }

        @Override
        public float[] caretAdvances(int font, String text, float textSizePx) {
            float[] adv = new float[text.length() + 1];
            for (int i = 0; i <= text.length(); i++) {
                adv[i] = i * ADVANCE;
            }
            return adv;
        }
    };

    /** The stock interface: an implementation with no atlas behind it, which is what the defaults describe. */
    private static final TextMeasurer ATLAS_LESS = (n, axis, px) -> axis == Axis.VERTICAL ? LINE_H : 0f;

    private static RetainedNode field(String text) {
        RetainedNode n = new RetainedNode(1L);
        n.set(PropKey.TEXT, text);
        n.x = 0f;
        n.y = 0f;
        n.w = 100f;
        n.h = 20f;
        n.textSizePx = 16f;
        n.viewX = 0f;
        n.viewY = 0f;
        n.viewW = 100f;
        n.viewH = 20f;
        return n;
    }

    /**
     * An empty document is a document with one empty line, not an absence of one. Without this the metrics were
     * null, so a focused empty field had nowhere to draw a caret — it looked dead exactly when it was inviting
     * the first keystroke.
     */
    @Test
    void anEmptyFieldStillHasOneLineToDrawACaretOn() {
        RetainedNode n = field("");

        TextGeometry.resolve(n, FIXED_PITCH);

        assertNotNull(n.textMetrics, "an empty field must still publish geometry");
        assertEquals(1, n.textMetrics.lines().size(), "one empty visual line");
        assertEquals(1, n.textMetrics.lines().get(0).xs().length, "and one caret boundary, at offset 0");
    }

    /** A field emptied after scrolling snaps back to its origin rather than keeping an offset into nothing. */
    @Test
    void emptyingAScrolledFieldPutsItBackToTheStart() {
        RetainedNode n = field("");
        n.scrollX = 42f;
        n.scrollY = 17f;

        TextGeometry.resolve(n, FIXED_PITCH);

        assertEquals(0f, n.scrollX, "a field with no text has nothing to be scrolled past");
        assertEquals(0f, n.scrollY);
    }

    /**
     * A measurer with no glyph metrics resolves nothing at all, rather than resolving something wrong. The
     * headless paths run against exactly this, so "no atlas" has to be a state the compute phase survives.
     */
    @Test
    void aMeasurerWithNoGlyphMetricsResolvesNothing() {
        RetainedNode n = field("hello");

        TextGeometry.resolve(n, ATLAS_LESS);

        assertNull(n.textMetrics, "without advances there is no caret geometry to bake");
    }

    /**
     * The caret boundaries are the measured advances, offset by where the text actually starts. This is the
     * arithmetic a widget does its caret hit-testing against, and getting the offset wrong is what made
     * click-to-caret miss in a scrolled field.
     */
    @Test
    void caretBoundariesAreTheAdvancesPlacedWhereTheTextStarts() {
        RetainedNode n = field("abcd");

        TextGeometry.resolve(n, FIXED_PITCH);

        assertNotNull(n.textMetrics);
        float[] xs = n.textMetrics.lines().get(0).xs();
        assertEquals(5, xs.length, "four characters is five caret boundaries");
        for (int i = 0; i < xs.length; i++) {
            assertEquals(i * ADVANCE, xs[i] - xs[0], 0.001f, "boundary " + i + " sits one advance on from the last");
        }
    }
}
