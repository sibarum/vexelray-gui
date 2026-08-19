package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.text.TextMetrics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Zoom is the check on §6's "no pixel unit": every length resolves through it, so doubling the factor must double
 * <em>everything</em>, and anything still pinned to device pixels stands still while the rest grows.
 *
 * <p>The text inset is the case worth pinning down, because it is the one that used to fail. It was a bare
 * {@code 10f} in two places while the scrollbar beside it was already {@code 0.85em} — so at 2× the box grew and
 * the gap between the border and the first glyph did not.
 */
class ZoomTest {

    @Test
    void theTextInsetScalesWithZoom() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = new TextField(h.gui, "abc");
            h.gui.root().children(f.node());
            h.frame();

            float inset1x = f.node().layout().text().caretX(0) - f.node().layout().rect().x();
            assertEquals(HeadlessGui.FIELD_PAD_X, inset1x, 0.5f,
                    "at 1x the inset is the field's border plus the resolved PAD_X gutter");

            h.gui.zoom(2f);
            h.frame();

            float inset2x = f.node().layout().text().caretX(0) - f.node().layout().rect().x();
            assertEquals(2f * inset1x, inset2x, 0.5f,
                    "at 2x the text inset doubles like everything else — it is an em, not ten pixels");
        }
    }

    /** A fixed box scales too, which is the baseline the inset has to keep pace with. */
    @Test
    void boxesAndTextSizeScaleWithZoom() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node box = h.gui.box().width(Length.rem(2)).height(Length.rem(2));
            h.gui.root().children(box);
            h.frame();
            assertEquals(32f, box.layout().rect().w(), 0.5f, "2rem = 32px at the default 16px em");
            float textPx1x = box.layout().textSizePx();

            h.gui.zoom(1.5f);
            h.frame();
            assertEquals(48f, box.layout().rect().w(), 0.5f, "and 48px at 1.5x");
            assertEquals(1.5f * textPx1x, box.layout().textSizePx(), 0.5f, "text size scales in step");
        }
    }

    /** Zoom is clamped, so a runaway shortcut cannot drive the layout to zero or to absurdity. */
    @Test
    void zoomIsClamped() {
        try (HeadlessGui h = new HeadlessGui()) {
            h.gui.zoom(1000f);
            assertEquals(4f, h.gui.zoom().value(), 0.001f);
            h.gui.zoom(0f);
            assertEquals(0.25f, h.gui.zoom().value(), 0.001f);
        }
    }

    /** Changing zoom relays out on the next frame, without anything writing the reconciler off-thread. */
    @Test
    void changingZoomRepublishesTheReadModel() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node box = h.gui.box().width(Length.rem(2)).height(Length.rem(2));
            h.gui.root().children(box);
            h.frame();
            long version = h.gui.layoutSnapshot().version();

            h.gui.zoom(2f);
            h.frame();

            org.junit.jupiter.api.Assertions.assertTrue(h.gui.layoutSnapshot().version() > version,
                    "a zoom change is a layout change, so the read-model republishes");
            assertEquals(64f, box.layout().rect().w(), 0.5f);
        }
    }

    /** The gutter gap is an em as well, so line numbers keep their spacing at any factor. */
    @Test
    void theGutterScalesWithZoom() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = new TextField(h.gui, "one\ntwo");
            f.multiline(true).lineNumbers(true);
            f.node().width(Length.rem(20)).height(Length.rem(10));
            h.gui.root().children(f.node());
            h.frame();
            float textLeft1x = f.node().layout().text().caretX(0) - f.node().layout().rect().x();

            h.gui.zoom(2f);
            h.frame();
            float textLeft2x = f.node().layout().text().caretX(0) - f.node().layout().rect().x();

            assertEquals(2f * textLeft1x, textLeft2x, 1f,
                    "inset + gutter together scale, so the text starts twice as far in");
        }
    }

    /** Guard against the constants drifting apart again: one resolved value, three stages. */
    @Test
    void theRendererAndTheMetricsShareOneInset() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = new TextField(h.gui, "abc");
            h.gui.root().children(f.node());
            h.frame();
            // TextMetrics.PAD_X is the single gutter declaration; the layout resolves it (plus the field's own
            // border) into one inset and everyone reads that.
            var ctx = dev.vexelray.gui.core.layout.LayoutContext.of(800f, 600f);
            assertEquals(TextMetrics.PAD_X.scalarPx(ctx, 0f) + Length.rem(0.1f).scalarPx(ctx, 0f),
                    f.node().layout().text().caretX(0) - f.node().layout().rect().x(), 0.5f);
        }
    }
}
