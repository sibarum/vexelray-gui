package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a scrollbar's room comes from (docs/plans/scrollbar-room.md): a box sized to its content grows by its bar and
 * its neighbours move; a box whose size was declared, or given by its parent, takes the strip from inside as before.
 */
class ScrollbarRoomTest {

    private static final float EPS = 0.01f;

    private static float noText(RetainedNode n, Axis axis, float px) {
        return 0f;
    }

    private static RetainedNode find(RetainedNode from, long id) {
        if (from.id == id) {
            return from;
        }
        for (RetainedNode c : from.children) {
            RetainedNode hit = find(c, id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** Twenty items 4rem by 2rem: far wider than an 800px window when laid side by side. */
    private static Node[] items(Gui gui, float wRem, float hRem) {
        Node[] items = new Node[20];
        for (int i = 0; i < items.length; i++) {
            items[i] = gui.box().width(Length.rem(wRem)).height(Length.rem(hRem));
        }
        return items;
    }

    private static RetainedNode frame(Gui gui, Node content) {
        gui.root().children(content);
        return gui.frame(800f, 600f, ScrollbarRoomTest::noText);
    }

    @Test
    void aRowSizedToItsContentGrowsByItsBarAndTheBodyMovesDown() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            Node bar = gui.row().width(Length.FILL).scroll(true, false).children(items(gui, 4f, 2f));
            Node body = gui.box().width(Length.FILL).height(Length.grow(1f));
            RetainedNode root = frame(gui, gui.column().width(Length.FILL).height(Length.FILL).children(bar, body));
            RetainedNode b = find(root, bar.id());
            RetainedNode first = b.children.get(0);
            assertTrue(b.overflowX);
            assertEquals(32f + b.scrollbarPx, b.h, EPS, "as tall as its items, and its bar under them");
            assertEquals(32f, b.viewH, EPS, "the bar takes nothing from the items");
            assertEquals(32f, first.h, EPS);
            assertEquals(b.y + b.h, find(root, body.id()).y, EPS, "the body below gives up the strip");
        }
    }

    @Test
    void anUndeclaredShortRowGrowsInsteadOfBeingSquashed() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            Node strip = gui.row().width(Length.FILL).children(items(gui, 4f, 1.25f));
            RetainedNode root = frame(gui, gui.column().width(Length.FILL).height(Length.FILL).children(strip));
            RetainedNode s = find(root, strip.id());
            assertTrue(s.overflowX, "long enough along x to scroll there unasked");
            assertEquals(20f, s.viewH, EPS, "and its 1.25em items keep all of their height");
        }
    }

    @Test
    void paddingStaysOutsideTheContentTheBarMakesRoomFor() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            Node bar = gui.row().width(Length.FILL).padding(Length.rem(0.5f)).scroll(true, false)
                    .children(items(gui, 4f, 2f));
            RetainedNode root = frame(gui, gui.column().width(Length.FILL).height(Length.FILL).children(bar));
            RetainedNode b = find(root, bar.id());
            assertEquals(32f + 16f + b.scrollbarPx, b.h, EPS);
            assertEquals(32f, b.viewH, EPS);
        }
    }

    @Test
    void aColumnSizedToItsContentWidensByItsBarAndTheNeighbourMovesRight() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            Node list = gui.column().children(items(gui, 6f, 4f));   // 20 × 64px in a 600px window
            Node rest = gui.box().width(Length.grow(1f));
            RetainedNode root = frame(gui, gui.row().width(Length.FILL).height(Length.FILL).children(list, rest));
            RetainedNode l = find(root, list.id());
            assertTrue(l.overflowY);
            assertEquals(96f + l.scrollbarPx, l.w, EPS, "as wide as its items, and its bar beside them");
            assertEquals(96f, l.viewW, EPS);
            assertEquals(l.x + l.w, find(root, rest.id()).x, EPS);
        }
    }

    @Test
    void aDeclaredHeightStillTakesItsStripFromInside() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            Node bar = gui.row().width(Length.FILL).height(Length.rem(2.25f)).scroll(true, false)
                    .children(items(gui, 4f, 2f));
            RetainedNode root = frame(gui, gui.column().width(Length.FILL).height(Length.FILL).children(bar));
            RetainedNode b = find(root, bar.id());
            assertEquals(36f, b.h, EPS, "a declared size is kept");
            assertEquals(36f - b.scrollbarPx, b.viewH, EPS, "so the strip comes out of it");
        }
    }

    @Test
    void aBoxItsParentSizesIsAViewportAsBefore() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            Node body = gui.column().width(Length.FILL).height(Length.grow(1f)).children(items(gui, 6f, 4f));
            RetainedNode root = frame(gui, gui.column().width(Length.FILL).height(Length.FILL).children(body));
            RetainedNode b = find(root, body.id());
            assertTrue(b.overflowY);
            assertEquals(800f, b.w, EPS);
            assertEquals(800f - b.scrollbarPx, b.viewW, EPS);
        }
    }

    @Test
    void contentThatFitsMeasuresNoStrip() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            Node bar = gui.row().width(Length.FILL).scroll(true, false)
                    .children(gui.box().width(Length.rem(4)).height(Length.rem(2)));
            RetainedNode root = frame(gui, gui.column().width(Length.FILL).height(Length.FILL).children(bar));
            RetainedNode b = find(root, bar.id());
            assertFalse(b.overflowX);
            assertEquals(32f, b.h, EPS);
        }
    }
}
