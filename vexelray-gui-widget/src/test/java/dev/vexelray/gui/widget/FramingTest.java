package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Framing}: a field or a tree flush with its card paints neither its well nor its hairline, and takes the room
 * it took in a well, so switching moves nothing (IDE framework-notes FN-5).
 */
class FramingTest {

    private static void assertFlush(HeadlessGui h, Node node, String what) {
        RetainedNode n = h.retained(node);
        Color fill = n.background(h.gui.drawnTheme());
        Color edge = n.borderColor(h.gui.drawnTheme());
        assertTrue(fill.a() <= 0f, what + "'s well is painted: " + fill);
        assertTrue(edge.a() <= 0f, what + "'s hairline is painted: " + edge);
    }

    @Test
    void aFlushFieldPaintsNoWellAndTakesTheSameRoom() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField well = new TextField(h.gui, "in a well");
            TextField flush = new TextField(h.gui, "on the card").frame(Framing.FLUSH);
            h.gui.root().children(well.node(), flush.node());
            h.frame();
            assertTrue(h.retained(well.node()).background(h.gui.drawnTheme()).a() > 0f, "a field is in a well");
            assertFlush(h, flush.node(), "a flush field");
            assertEquals(h.retained(well.node()).borderWidth(), h.retained(flush.node()).borderWidth());
            assertEquals(well.node().layout().rect().h(), flush.node().layout().rect().h());

            flush.frame(Framing.WELL);
            h.frame();
            assertEquals(h.retained(well.node()).background(h.gui.drawnTheme()),
                    h.retained(flush.node()).background(h.gui.drawnTheme()), "and back in a well");
        }
    }

    @Test
    void aFlushTreePaintsNoWellAndKeepsItsBorderWidth() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = new TreeView<String>(h.gui, "files").frame(Framing.FLUSH);
            h.gui.root().children(tree.node());
            h.frame();
            assertFlush(h, tree.node(), "a flush tree");
            assertEquals(Length.rem(0.1f), h.retained(tree.node()).borderWidth());
        }
    }
}
