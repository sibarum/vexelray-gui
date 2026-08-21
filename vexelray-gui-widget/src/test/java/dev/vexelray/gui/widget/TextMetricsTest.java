package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.text.TextMetrics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The text metrics published in the layout read-model (step 2): a text node reports absolute-space caret geometry
 * through {@code node.layout().text()}, so point↔offset is a pure lookup with no measurer at the call site.
 */
class TextMetricsTest {

    @Test
    void textFieldPublishesAbsoluteCaretGeometry() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = new TextField(h.gui, "abcd");
            h.gui.root().children(f.node());
            h.frame();

            NodeLayout L = f.node().layout();
            assertTrue(L.present());
            TextMetrics T = L.text();
            assertNotNull(T, "a text node carries caret metrics");

            // The text origin is the field's full inset — its border plus the editable caret gutter,
            // FIELD_PAD_X = 11.6. Monospace, so caret x for offset k is FIELD_PAD_X + k*10.
            float origin = HeadlessGui.FIELD_PAD_X;
            assertEquals(origin, T.caretX(0), 0.5f);
            assertEquals(origin + 20f, T.caretX(2), 0.5f);
            assertEquals(origin + 40f, T.caretX(4), 0.5f);

            // point → offset: a click just past the offset-2 boundary lands on 2 (nearest), in the line's y-band.
            float y = T.caretTop(0) + T.caretHeight(0) * 0.5f;
            assertEquals(2, T.offsetAt(origin + 21f, y));
            assertEquals(4, T.offsetAt(999f, y), "past the end clamps to the last offset");
        }
    }

    /**
     * An empty document is a document with one empty visual line, not "no text": a focused empty field must have
     * somewhere for its blinking caret to be, and that somewhere must come from the same published read-model
     * everything else reads. Metrics that go null on empty are a field that looks dead exactly when it is
     * inviting the first keystroke.
     */
    @Test
    void anEmptyFieldStillPublishesCaretGeometry() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = new TextField(h.gui, "");
            h.gui.root().children(f.node());
            h.frame();
            h.focus(f.node());
            h.frame();

            NodeLayout L = f.node().layout();
            TextMetrics T = L.text();
            assertNotNull(T, "an empty document still carries metrics — one empty visual line");
            assertEquals(1, T.lines().size());
            assertEquals(HeadlessGui.FIELD_PAD_X, T.caretX(0), 0.5f, "the caret boundary sits at the text origin");
            assertTrue(T.caretTop(0) >= L.rect().y() && T.caretTop(0) + T.caretHeight(0)
                            <= L.rect().y() + L.rect().h() + 0.5f,
                    "and inside the field's own box");
            assertEquals(1, T.lines().get(0).number(), "the empty line is hard line 1, so a gutter can number it");
        }
    }

    /** Typing into the empty field, then deleting back to empty, round-trips the geometry rather than losing it. */
    @Test
    void emptyingAFieldKeepsItsCaretGeometry() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = new TextField(h.gui, "");
            h.gui.root().children(f.node());
            h.frame();
            h.focus(f.node());
            h.type("ab");
            h.frame();
            h.tap(sibarum.tactroller.api.Key.BACKSPACE);
            h.frame();
            h.tap(sibarum.tactroller.api.Key.BACKSPACE);
            h.frame();

            assertEquals("", f.text());
            TextMetrics T = f.node().layout().text();
            assertNotNull(T, "emptied, not dead");
            assertEquals(HeadlessGui.FIELD_PAD_X, T.caretX(0), 0.5f,
                    "the caret is back at the origin, with the scroll snapped back too");
        }
    }
}
