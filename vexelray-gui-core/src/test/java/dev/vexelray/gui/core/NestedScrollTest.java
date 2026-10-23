package dev.vexelray.gui.core;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two scrollers along one axis, one filling the other, are reported (docs/reference/scrolling.md §3): two bars side
 * by side read as a style, so without the warning nothing ever says they were not meant.
 */
class NestedScrollTest {

    private static float noText(dev.vexelray.gui.core.model.RetainedNode n,
                                dev.vexelray.gui.core.layout.LayoutEnums.Axis axis, float px) {
        return 0f;
    }

    @BeforeEach
    void silence() {
        Diagnostics.reset();
    }

    private static List<String> nested() {
        return Diagnostics.recorded().stream().filter(m -> m.startsWith("one scrollbar along")).toList();
    }

    /**
     * The VexelRay Inspector's window, which showed this: a title bar, a body that grows into the window and scrolls
     * content taller than it, and a status bar, under a minimum sized to the content (58em) rather than to what
     * cannot scroll.
     */
    private static Node inspectorShape(Gui gui) {
        Node body = gui.column().width(Length.FILL).height(Length.grow(1f))
                .children(gui.box().size(Length.dp(900), Length.dp(1500)));
        gui.root().direction(Direction.COLUMN).children(
                gui.box().size(Length.FILL, Length.dp(32)), body, gui.box().size(Length.FILL, Length.dp(16)));
        gui.minSize(Length.rem(51), Length.rem(58));   // 816 x 928 at 1x
        return body;
    }

    @Test
    void aWindowUnderAMinimumSizedToTheContentScrollsTwiceAndSaysWhy() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node body = inspectorShape(gui);
            gui.frame(1040f, 860f, NestedScrollTest::noText);

            assertTrue(body.layout().overflowY() && gui.root().layout().overflowY(), "the picture: two bars");
            List<String> said = nested();
            assertEquals(1, said.size(), Diagnostics.recorded().toString());
            String m = said.getFirst();
            assertTrue(m.startsWith("one scrollbar along y — box " + body.id() + " scrolls inside box "
                    + gui.root().id() + ", the root,"), m);
            assertTrue(m.contains("canvas 928px tall in a 860px window: Gui.minSize is larger than the window"), m);
        }
    }

    @Test
    void theSameWindowAtItsMinimumScrollsOnceAndSaysNothing() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node body = inspectorShape(gui);
            gui.frame(1040f, 940f, NestedScrollTest::noText);

            assertTrue(body.layout().overflowY(), "the body scrolls the content");
            assertFalse(gui.root().layout().overflowY(), "and the root does not");
            assertEquals(List.of(), nested());
        }
    }

    /** A list inside a scrolling page is shorter than the page's view: its bar is beside its rows, not the page's. */
    @Test
    void aShorterScrollerInsideAScrollingPageIsMeant() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node list = gui.column().size(Length.FILL, Length.dp(200))
                    .children(gui.box().size(Length.dp(100), Length.dp(1000)));
            gui.root().direction(Direction.COLUMN)
                    .children(gui.box().size(Length.FILL, Length.dp(900)), list, gui.box().size(Length.FILL, Length.dp(900)));
            gui.frame(800f, 600f, NestedScrollTest::noText);

            assertTrue(list.layout().overflowY() && gui.root().layout().overflowY(), "both scroll");
            assertEquals(List.of(), nested());
        }
    }

    /**
     * Without a minimum the cause is the outer scroller's own content, and the warning says that instead. The inner
     * one is as tall as the outer's view outright: a FILL or grow one would take only the room its sibling leaves,
     * and nothing would scroll twice.
     */
    @Test
    void aFillingScrollerInsideAnOverflowingBoxNamesTheBoxAsTheCause() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node inner = gui.column().size(Length.FILL, Length.dp(400))
                    .children(gui.box().size(Length.dp(100), Length.dp(1000)));
            Node outer = gui.column().size(Length.dp(600), Length.dp(400))
                    .children(inner, gui.box().size(Length.FILL, Length.dp(50)));
            gui.root().children(outer);
            gui.frame(800f, 600f, NestedScrollTest::noText);

            List<String> said = nested();
            assertEquals(1, said.size(), Diagnostics.recorded().toString());
            assertTrue(said.getFirst().contains("box " + inner.id() + " scrolls inside box " + outer.id()), said.getFirst());
            assertTrue(said.getFirst().contains("box " + outer.id() + " scrolls because its content is longer than its view"),
                    said.getFirst());
            assertFalse(said.getFirst().contains("minSize"), said.getFirst());
        }
    }
}
