package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Word wrap and horizontal scrolling are the same choice seen from two sides, and toggling one is what switches
 * the other. A wrapped node never scrolls horizontally — there is nothing to the right of a wrapped line to reach,
 * so a bar there would be chrome for an axis that cannot move — and an unwrapped one reports content wider than
 * its box, which is exactly what grows the bar.
 *
 * <p>Worth pinning down because the two live in different places: the wrap decision is a prop, the overflow is
 * computed by the layout, and the rule joining them is {@code RetainedNode.wrapsText()}. A change to either side
 * that forgot the other would leave a field that wraps and shows a horizontal scrollbar, or one that neither
 * wraps nor lets you reach the end of a line.
 */
class WrapToggleTest {

    /** Long enough that a single line is far wider than the field, at the harness's 10px cell. */
    private static final String LONG_LINE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static TextField field(HeadlessGui h) {
        TextField f = new TextField(h.gui, LONG_LINE);
        f.multiline(true).wordWrap(true);
        f.node().width(Length.rem(20)).height(Length.rem(10));   // 320 x 160
        h.gui.root().children(f.node());
        h.frame();
        return f;
    }

    @Test
    void wrappedTextDoesNotScrollHorizontally() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h);

            assertFalse(f.node().layout().overflowX(), "a wrapped line has nothing to the right to reach");
            assertTrue(f.node().layout().text().lines().size() > 1, "and it broke onto several visual lines");
        }
    }

    @Test
    void turningWrapOffGivesTheFieldAHorizontalScrollbar() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h);

            f.wordWrap(false);
            h.frame();

            assertTrue(f.node().layout().overflowX(), "unwrapped, the long line overflows and the bar appears");
            assertEquals(1, f.node().layout().text().lines().size(),
                    "and the text is one visual line again, not several");
        }
    }

    /** And back, so the toggle is a toggle rather than a one-way door. */
    @Test
    void turningWrapBackOnRemovesIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h);

            f.wordWrap(false);
            h.frame();
            assertTrue(f.node().layout().overflowX());

            f.wordWrap(true);
            h.frame();
            assertFalse(f.node().layout().overflowX(), "wrapping again retires the bar");
            assertTrue(f.node().layout().text().lines().size() > 1);
        }
    }

    /** Spans are offsets into the text, so they survive a reflow untouched — the wrap moved, the ranges did not. */
    @Test
    void spansSurviveTheReflow() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h);
            f.setSpans(java.util.List.of(
                    dev.vexelray.gui.core.text.Span.underline(10, 20)));

            f.wordWrap(false);
            h.frame();
            assertEquals(1, f.spans().size(), "still there after reflowing to one line");
            assertEquals(10, f.spans().get(0).start(), "and still attached to the same characters");
            assertEquals(20, f.spans().get(0).end());
        }
    }
}
