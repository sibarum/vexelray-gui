package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Horizontal scrolling of text nodes.
 *
 * <p>Two rules, and they are complementary: <b>wrapped text never scrolls horizontally</b> — a wrapped line has
 * nothing to its right to reach, so an h-scrollbar would be chrome for an axis that cannot move — and
 * <b>unwrapped text overflows horizontally when it is wider than its box</b>, like any other content.
 *
 * <p>Geometry: an 80px-wide field at the origin with the {@value HeadlessGui#CELL}px monospace stub leaves 60px
 * of text area, so 6 characters are visible and a 20-character string is 200px wide.
 */
class TextScrollTest {

    private static final String LONG = "abcdefghijklmnopqrst";   // 20 chars = 200px

    private static TextField field(HeadlessGui h, boolean multiline, boolean wrap) {
        TextField f = new TextField(h.gui, "").multiline(multiline).wordWrap(wrap);
        f.node().width(Length.vw(10)).height(Length.rem(2.5f));
        h.gui.root().children(f.node());
        h.frame();
        h.focus(f.node());
        h.type(LONG);
        return f;
    }

    @Test
    void wrappedTextNeverScrollsHorizontally() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, true, true);

            NodeLayout L = f.node().layout();
            assertFalse(L.overflowX(), "a wrapped field has nothing to the right to scroll to");
            assertEquals(0f, L.scrollX(), 0.01f, "and its horizontal offset stays pinned at the origin");
        }
    }

    @Test
    void aLabelNeverScrollsHorizontallyBecauseItAlwaysWraps() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node label = h.gui.text(LONG).width(Length.vw(10));
            h.gui.root().children(label);
            h.frame();

            assertFalse(label.layout().overflowX(), "labels always wrap, so they never overflow sideways");
        }
    }

    @Test
    void unwrappedTextOverflowsHorizontallyWhenWiderThanItsBox() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, false, false);

            NodeLayout L = f.node().layout();
            assertTrue(L.overflowX(), "20 characters (200px) do not fit in 60px of text area");
            assertEquals(200f, L.contentW(), 0.5f, "the content extent is the widest line");
            assertEquals(60f, L.viewW(), 0.5f, "against a 60px viewport, so the field is scrollable by 140px");
        }
    }

    @Test
    void aShortStringDoesNotOverflow() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = new TextField(h.gui, "abc");
            f.node().width(Length.vw(10)).height(Length.rem(2.5f));
            h.gui.root().children(f.node());
            h.frame();

            assertFalse(f.node().layout().overflowX(), "3 characters fit in 6 columns");
        }
    }

    /**
     * Now that an overflowing field is a real scroll target, the wheel and the scrollbar can move it — so
     * caret-follow must run on caret <em>movement</em>, not every frame. Following unconditionally would drag the
     * view back to the caret the instant the user scrolled away from it.
     */
    @Test
    void scrollingAwayFromTheCaretSticksUntilTheCaretMoves() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, false, false);
            float followed = f.node().layout().scrollX();
            assertTrue(followed > 0f, "precondition: typing to the end scrolled the view right");

            h.wheel(-1, 0, 40f, 20f);                       // one notch left, pointer over the field
            float scrolled = f.node().layout().scrollX();
            assertTrue(scrolled < followed, "the wheel moved the view left, away from the caret");

            h.frame();
            assertEquals(scrolled, f.node().layout().scrollX(), 0.01f,
                    "and a frame with no caret movement leaves it exactly where the user put it");

            h.tap(Key.LEFT);                                 // the caret moves: following resumes
            assertTrue(f.node().layout().scrollX() > scrolled,
                    "moving the caret brings the view back to it");
        }
    }
}
