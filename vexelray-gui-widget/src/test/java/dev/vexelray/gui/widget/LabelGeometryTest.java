package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.text.TextMetrics;
import dev.vexelray.text.TextLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A label's published geometry must describe where its text is actually drawn.
 *
 * <p>This is a compliance condition, not a feature: the read-model exists so that <em>anything</em> — a widget, a
 * devtool, a test, a remote client with no atlas — can know where a glyph sits. A label used to draw through
 * {@code canvas.text} with its own alignment while its metrics described a left-aligned block at a different
 * height, so the geometry was true only for the one renderer that ignored it. That is checkable today, with no
 * second consumer present, which is exactly what makes it a condition rather than a someday-feature.
 *
 * <p>Geometry: 80px wide leaves 60px of text area at 10px padding, so six {@value HeadlessGui#CELL}px columns.
 */
class LabelGeometryTest {

    private static Node label(HeadlessGui h, String s, TextLayout.HAlign ha, TextLayout.VAlign va) {
        Node n = h.gui.text(s).width(Length.vw(10)).align(ha, va);
        h.gui.root().children(n);
        h.frame();
        return n;
    }

    @Test
    void aCentredLabelReportsCentredGeometry() {
        try (HeadlessGui h = new HeadlessGui()) {
            // "abc" is 30px inside a 60px text area, so 15px of slack either side of a centred line.
            Node n = label(h, "abc", TextLayout.HAlign.CENTER, TextLayout.VAlign.TOP);

            TextMetrics m = n.layout().text();
            assertNotNull(m);
            assertEquals(25f, m.caretX(0), 0.5f, "text origin 10 + half the 30px of slack");
            assertEquals(55f, m.caretX(3), 0.5f, "and it ends symmetrically");
        }
    }

    @Test
    void aRightAlignedLabelReportsRightAlignedGeometry() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node n = label(h, "abc", TextLayout.HAlign.RIGHT, TextLayout.VAlign.TOP);

            assertEquals(70f, n.layout().text().caretX(3), 0.5f, "the line ends at the right edge of the text area");
        }
    }

    @Test
    void alignmentIsPerLineSoAWrappedLabelCentresEachRow() {
        try (HeadlessGui h = new HeadlessGui()) {
            // "abcdefgh" wraps at 6 columns into "abcdef" (no slack) and "gh" (40px of slack).
            Node n = label(h, "abcdefgh", TextLayout.HAlign.CENTER, TextLayout.VAlign.TOP);

            TextMetrics m = n.layout().text();
            assertEquals(2, m.lines().size());
            assertEquals(10f, m.lines().get(0).xs()[0], 0.5f, "a full row has no slack to centre in");
            assertEquals(30f, m.lines().get(1).xs()[0], 0.5f, "the short row is centred on its own");
        }
    }

    @Test
    void vAlignPlacesTheWholeBlockNotOneLine() {
        try (HeadlessGui h = new HeadlessGui()) {
            // Two wrapped rows (20px of text) in a 60px-tall box: MIDDLE leaves 20px above.
            Node n = h.gui.text("abcdefgh").width(Length.vw(10)).height(Length.rem(3.75f))
                    .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
            h.gui.root().children(n);
            h.frame();

            TextMetrics m = n.layout().text();
            assertEquals(2, m.lines().size());
            assertEquals(20f, m.lines().get(0).top(), 0.5f, "the two-row block is centred, not the first row");
            assertEquals(30f, m.lines().get(1).top(), 0.5f);
        }
    }

    /**
     * The general invariant, stated once: every line a label reports sits inside the label's own box. Alignment
     * and wrapping are exactly the two things that used to break it.
     */
    @Test
    void everyReportedLineSitsInsideTheLabelsBox() {
        try (HeadlessGui h = new HeadlessGui()) {
            for (TextLayout.HAlign ha : TextLayout.HAlign.values()) {
                for (TextLayout.VAlign va : TextLayout.VAlign.values()) {
                    try (HeadlessGui g = new HeadlessGui()) {
                        Node n = label(g, "abcdefgh", ha, va);
                        NodeLayout box = n.layout();
                        TextMetrics m = box.text();
                        assertNotNull(m, ha + "/" + va);
                        for (TextMetrics.VisualLine line : m.lines()) {
                            assertTrue(line.top() >= box.rect().y() - 0.5f
                                            && line.bottom() <= box.rect().y() + box.rect().h() + 0.5f,
                                    ha + "/" + va + ": line at y=" + line.top() + " escapes the box");
                            assertTrue(line.xs()[0] >= box.rect().x() - 0.5f
                                            && line.xs()[line.xs().length - 1]
                                                    <= box.rect().x() + box.rect().w() + 0.5f,
                                    ha + "/" + va + ": line spans x=" + line.xs()[0]
                                            + ".." + line.xs()[line.xs().length - 1] + ", outside the box");
                        }
                    }
                }
            }
        }
    }
}
