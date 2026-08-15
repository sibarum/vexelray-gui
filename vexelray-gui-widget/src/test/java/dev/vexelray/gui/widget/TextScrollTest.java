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
 * Which text nodes scroll, on which axis, and what moves them.
 *
 * <ul>
 *   <li><b>Wrapped text never scrolls horizontally</b> — a wrapped line has nothing to its right to reach.</li>
 *   <li><b>A single-line input does not either</b>, by default: it masks at its edge and slides with the caret,
 *       because a scrollbar under a one-line box is chrome nobody asked for. Overridable per node.</li>
 *   <li><b>An unwrapped multi-line editor does</b>, when its widest line exceeds the text area.</li>
 *   <li><b>A multi-line editor scrolls vertically</b> when its lines exceed the visible height.</li>
 * </ul>
 *
 * <p>Geometry: an 80px-wide field at the origin with the {@value HeadlessGui#CELL}px monospace stub gives 60px of
 * text area (6 characters); {@code rem(2.5)} = 40px tall less the 6px vertical inset each side leaves 28px, so
 * 2 whole lines are visible out of however many exist.
 */
class TextScrollTest {

    private static final String LONG = "abcdefghijklmnopqrst";   // 20 chars = 200px

    private static TextField field(HeadlessGui h, boolean multiline, boolean wrap) {
        TextField f = new TextField(h.gui, "").multiline(multiline).wordWrap(wrap);
        f.node().width(Length.vw(10)).height(Length.rem(2.5f));
        h.gui.root().children(f.node());
        h.frame();
        h.focus(f.node());
        return f;
    }

    /** Type lines, pressing Enter between them — '\n' rides the key channel, never CharTyped. */
    private static void typeLines(HeadlessGui h, String... lines) {
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                h.tap(Key.ENTER);
            }
            h.type(lines[i]);
        }
    }

    // --- horizontal ---

    @Test
    void wrappedTextNeverScrollsHorizontally() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, true, true);
            h.type(LONG);

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
    void aSingleLineInputMasksAtItsEdgeInsteadOfGrowingAScrollbar() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, false, false);
            h.type(LONG);

            NodeLayout L = f.node().layout();
            assertFalse(L.overflowX(), "no scrollbar under a one-line input");
            assertTrue(L.scrollX() > 0f, "but it still slid along to keep the caret visible");
            assertTrue(f.node().layout().text().caretX(LONG.length()) <= L.content().x() + L.viewW() + 0.5f,
                    "with the caret inside the visible text area");
        }
    }

    @Test
    void anUnwrappedEditorOverflowsHorizontallyWhenWiderThanItsBox() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, true, false);   // multiline, wrap off
            h.type(LONG);

            NodeLayout L = f.node().layout();
            assertTrue(L.overflowX(), "20 characters (200px) do not fit in 60px of text area");
            assertEquals(200f, L.contentW(), 0.5f, "the content extent is the widest line");
        }
    }

    // --- vertical ---

    @Test
    void aMultilineEditorOverflowsVerticallyWhenItsLinesExceedTheBox() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, true, false);
            typeLines(h, "a", "b", "c", "d", "e");   // 5 lines of 10px in 28px of visible height

            NodeLayout L = f.node().layout();
            assertTrue(L.overflowY(), "5 lines do not fit in 28px");
            assertEquals(50f, L.contentH(), 0.5f, "the content extent is lineCount * lineHeight");
            assertEquals(28f, L.viewH(), 0.5f, "against the text area's height");
        }
    }

    @Test
    void aSingleLineInputNeverScrollsVertically() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, false, false);
            h.type(LONG);

            assertFalse(f.node().layout().overflowY(), "one line cannot overflow its own box");
        }
    }

    /**
     * Now that an overflowing editor is a real scroll target, the wheel and the scrollbar move it — so
     * caret-follow must run on caret <em>movement</em>, not every frame. Following unconditionally would drag the
     * view back to the caret the instant the user scrolled away from it.
     */
    @Test
    void scrollingAwayFromTheCaretSticksUntilTheCaretMoves() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, true, false);
            typeLines(h, "a", "b", "c", "d", "e");
            float followed = f.node().layout().scrollY();
            assertTrue(followed > 0f, "precondition: typing to the last line scrolled the view down");

            h.wheel(0, 1, 40f, 20f);                        // one notch up, pointer over the field
            float scrolled = f.node().layout().scrollY();
            assertTrue(scrolled < followed, "the wheel moved the view up, away from the caret");

            h.frame();
            assertEquals(scrolled, f.node().layout().scrollY(), 0.01f,
                    "and a frame with no caret movement leaves it exactly where the user put it");

            h.tap(Key.UP);                                   // the caret moves: following resumes
            assertTrue(f.node().layout().scrollY() > scrolled, "moving the caret brings the view back to it");
        }
    }
}
