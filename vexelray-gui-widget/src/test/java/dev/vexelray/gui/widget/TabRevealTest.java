package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.layout.Rect;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tab brings what it lands on into view; a click does not.
 *
 * <p>Focus moved by the keyboard onto a field scrolled out of its column used to stay there: the ring is clipped
 * with the node, so the user could not see where focus was, and the next keystroke went somewhere off-screen. The
 * pointer is the opposite case — it is already over what it focused, and scrolling would move the thing out from
 * under it.
 */
class TabRevealTest {

    private static final float EPS = 0.5f;

    @Test
    void tabbingBelowTheFoldScrollsTheFieldIntoView() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<TextField> fields = new ArrayList<>();
            Node scroller = mount(h, fields);
            assertTrue(scroller.layout().overflowY(), "the column must overflow for the test to mean anything");

            TextField last = fields.get(fields.size() - 1);
            assertTrue(last.node().layout().clippedAway(), "the last field starts below the fold");

            for (int i = 0; i < fields.size(); i++) {
                h.tap(Key.TAB);
            }
            h.frame();
            assertTrue(h.gui.semanticSnapshot().node(last.node().id()).focused(), "six Tabs from nothing land on the sixth field");

            Rect view = scroller.layout().content();
            Rect r = last.node().layout().rect();
            assertTrue(r.y() >= view.y() - EPS && r.y() + r.h() <= view.y() + view.h() + EPS,
                    "the focused field lies within the scroller's viewport: " + r + " in " + view);
            assertFalse(last.node().layout().clipped(), "and nothing of it is clipped, ring included");
        }
    }

    /** Least scrolling, the same as {@code scrollIntoView}: Shift+Tab back to the top brings the top back. */
    @Test
    void shiftTabBackToTheFirstFieldScrollsBackUp() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<TextField> fields = new ArrayList<>();
            Node scroller = mount(h, fields);
            h.focus(fields.get(fields.size() - 1).node());
            h.tap(Key.TAB);                                  // wraps to the first...
            h.chord(Key.TAB, Key.LEFT_SHIFT);                // ...and back to the last, scrolling down
            h.frame();
            assertTrue(scroller.layout().scrollY() > 0f, "the last field brought the column down");

            for (int i = 0; i < fields.size() - 1; i++) {
                h.chord(Key.TAB, Key.LEFT_SHIFT);
            }
            h.frame();
            assertTrue(h.gui.semanticSnapshot().node(fields.get(0).node().id()).focused());
            assertEquals(0f, scroller.layout().scrollY(), EPS, "the first field is at the top, so the column is too");
        }
    }

    @Test
    void clickingAPartlyHiddenFieldDoesNotScroll() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<TextField> fields = new ArrayList<>();
            Node scroller = mount(h, fields);
            TextField partial = null;
            for (TextField f : fields) {
                NodeLayout l = f.node().layout();
                if (l.clipped() && !l.clippedAway()) {
                    partial = f;
                    break;
                }
            }
            assertNotNull(partial, "the column's fold cuts through one of the fields");

            float before = scroller.layout().scrollY();
            Rect visible = partial.node().layout().visibleRect();
            h.click(visible.centreX(), visible.centreY());
            h.frame();
            assertTrue(h.gui.semanticSnapshot().node(partial.node().id()).focused(), "the click focused the field");
            assertEquals(before, scroller.layout().scrollY(), EPS,
                    "the pointer is already on it, so the column stays where the pointer found it");
        }
    }

    /** A 6rem column of six fields, as in the demo's Focus chapter: the fold falls part-way down the list. */
    private static Node mount(HeadlessGui h, List<TextField> into) {
        List<Node> nodes = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            TextField f = new TextField(h.gui, "Row " + i);
            into.add(f);
            nodes.add(f.node());
        }
        Node scroller = h.gui.column().height(Length.rem(6)).scroll(false, true).children(nodes.toArray(Node[]::new));
        h.gui.root().children(scroller);
        h.frame().frame();
        return scroller;
    }
}
