package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tabs, and the visibility primitive underneath them.
 *
 * <p>The property worth protecting is that a page is <b>hidden, not removed</b>. Both look the same on screen and
 * they are not the same: registrations are keyed by node id and released when a node leaves the tree, so a page
 * rebuilt by remove/insert comes back drawn but inert. Switching away and back has to return a working page, with
 * its content and caret intact, or the widget is a trap for anything interactive placed on it.
 */
class TabsTest {

    private static Node page(HeadlessGui h, String label) {
        return h.gui.text(label).width(Length.FILL).height(Length.FILL);
    }

    @Test
    void theFirstPageAddedIsSelected() {
        try (HeadlessGui h = new HeadlessGui()) {
            Tabs tabs = new Tabs(h.gui);
            tabs.add("One", page(h, "one"));
            tabs.add("Two", page(h, "two"));
            h.gui.root().children(tabs.node());
            h.frame();

            assertEquals(0, tabs.selected(), "a freshly built panel is never blank");
            assertEquals(2, tabs.count());
        }
    }

    @Test
    void onlyTheSelectedPageIsLaidOut() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node one = page(h, "one");
            Node two = page(h, "two");
            Tabs tabs = new Tabs(h.gui);
            tabs.add("One", one);
            tabs.add("Two", two);
            h.gui.root().children(tabs.node());
            h.frame();

            assertTrue(one.layout().rect().h() > 0f, "the selected page occupies the content area");
            assertEquals(0f, two.layout().rect().h(), 0.01f, "the hidden one occupies nothing at all");

            tabs.select(1);
            h.frame();

            assertEquals(0f, one.layout().rect().h(), 0.01f, "and they swap");
            assertTrue(two.layout().rect().h() > 0f);
        }
    }

    /** The reason visibility exists rather than remove/insert: an interactive page has to survive a round trip. */
    @Test
    void aTextFieldOnAHiddenPageStillWorksWhenItComesBack() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField field = new TextField(h.gui, "");
            Tabs tabs = new Tabs(h.gui);
            tabs.add("Edit", field.node());
            tabs.add("Other", page(h, "other"));
            h.gui.root().children(tabs.node());
            h.frame();
            h.focus(field.node());
            h.type("ab");
            assertEquals("ab", field.text());

            tabs.select(1);
            h.frame();
            tabs.select(0);
            h.frame();

            h.focus(field.node());
            h.type("c");
            assertEquals("abc", field.text(),
                    "the field kept its content and its handlers across the round trip — hidden, not removed");
        }
    }

    /** A hidden page cannot be clicked, so nothing invisible is ever a pointer target. */
    @Test
    void aHiddenPageIsNotHitTestable() {
        try (HeadlessGui h = new HeadlessGui()) {
            boolean[] hit = {false, false};
            Node one = page(h, "one");
            Node two = page(h, "two");
            Tabs tabs = new Tabs(h.gui);
            tabs.add("One", one);
            tabs.add("Two", two);
            h.gui.onClick(one, () -> hit[0] = true);
            h.gui.onClick(two, () -> hit[1] = true);
            h.gui.root().children(tabs.node());
            h.frame();

            float y = one.layout().rect().y() + one.layout().rect().h() / 2f;
            h.click(50f, y);
            assertTrue(hit[0], "the shown page takes the click");
            assertFalse(hit[1], "the hidden one is not under the pointer, whatever its old rect said");
        }
    }

    @Test
    void clickingAHeaderSelectsItsPage() {
        try (HeadlessGui h = new HeadlessGui()) {
            Tabs tabs = new Tabs(h.gui);
            tabs.add("One", page(h, "one"));
            tabs.add("Two", page(h, "two"));
            h.gui.root().children(tabs.node());
            h.frame();

            var r = tabs.header(1).layout().rect();
            h.click(r.x() + r.w() / 2f, r.y() + r.h() / 2f);
            h.frame();
            assertEquals(1, tabs.selected(), "the second header was clicked");
        }
    }

    /** Arrow keys walk the bar, as FOCUSED claims on the header that holds focus. */
    @Test
    void arrowKeysMoveBetweenTabsWhileAHeaderIsFocused() {
        try (HeadlessGui h = new HeadlessGui()) {
            Tabs tabs = new Tabs(h.gui);
            tabs.add("One", page(h, "one"));
            tabs.add("Two", page(h, "two"));
            tabs.add("Three", page(h, "three"));
            h.gui.root().children(tabs.node());
            h.frame();
            tabs.focus();

            h.tap(Key.RIGHT);
            assertEquals(1, tabs.selected());

            h.frame();
            tabs.focus();
            h.tap(Key.RIGHT);
            assertEquals(2, tabs.selected());
        }
    }

    /** Selection clamps, so an arrow at either end stays put rather than wrapping or throwing. */
    @Test
    void selectionClampsAtBothEnds() {
        try (HeadlessGui h = new HeadlessGui()) {
            Tabs tabs = new Tabs(h.gui);
            tabs.add("One", page(h, "one"));
            tabs.add("Two", page(h, "two"));
            h.gui.root().children(tabs.node());
            h.frame();

            tabs.select(-5);
            assertEquals(0, tabs.selected());
            tabs.select(99);
            assertEquals(1, tabs.selected());
        }
    }

    @Test
    void selectionIsReported() {
        try (HeadlessGui h = new HeadlessGui()) {
            int[] seen = {-1};
            Tabs tabs = new Tabs(h.gui).onSelect(i -> seen[0] = i);
            tabs.add("One", page(h, "one"));
            tabs.add("Two", page(h, "two"));
            h.gui.root().children(tabs.node());
            h.frame();

            tabs.select(1);
            assertEquals(1, seen[0]);
        }
    }
}
