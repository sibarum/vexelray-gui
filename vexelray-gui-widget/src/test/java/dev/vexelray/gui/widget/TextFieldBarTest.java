package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Dock;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The third row of the text-field ladder (docs/plans/scrolling.md §7.3): text longer than the room a field has scrolls
 * inside it, with a real scrollbar docked inside its right edge — no strip reserved by the text node.
 */
class TextFieldBarTest {

    private static final float BAR = 0.85f * 16f;   // Scrollbar.THICKNESS at the default root em

    /** The vertical bar a multi-line field carries: the scrollbar docked to its right edge (hidden ones have no box). */
    private static RetainedNode bar(HeadlessGui h, TextField f) {
        for (RetainedNode c : h.retained(f.node()).children) {
            if ("scrollbar".equals(c.role()) && c.dockEdge() == Dock.RIGHT) {
                return c;
            }
        }
        return null;
    }

    /** A multi-line field of {@code lines} lines in a pane its parent sizes, so it scrolls rather than grows. */
    private static TextField inPane(HeadlessGui h, int lines) {
        TextField f = new TextField(h.gui, "line\n".repeat(lines - 1) + "last").multiline(true);
        f.node().width(Length.FILL).height(Length.FILL);
        Node pane = h.gui.column().width(Length.rem(15)).height(Length.rem(10)).children(f.node());
        h.gui.root().children(h.gui.row().width(Length.FILL).height(Length.FILL).children(pane));
        h.frame().frame().frame();   // the bar is shown a frame after the range says the text overflows
        return f;
    }

    @Test
    void textLongerThanItsRoomShowsABarAndStopsShortOfIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = inPane(h, 40);
            RetainedNode field = h.retained(f.node());
            RetainedNode bar = bar(h, f);
            assertTrue(field.overflowY, "forty lines do not fit");
            assertEquals(0f, field.scrollbarPx, "the text node reserves no strip");
            assertTrue(bar != null && bar.visible(), "a real scrollbar is shown instead");
            assertEquals(BAR, bar.w, 0.5f);
            assertEquals(field.x + field.w, bar.x + bar.w, 0.5f, "inside the field's right edge");
            assertTrue(field.viewX + field.viewW <= bar.x + 0.5f, "and the text stops short of it");
        }
    }

    @Test
    void textThatFitsHasNoBarAndTheWholeWidth() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = inPane(h, 2);
            RetainedNode bar = bar(h, f);
            assertTrue(bar != null && !bar.visible(), "nothing to scroll: the bar takes no room");
            RetainedNode field = h.retained(f.node());
            assertTrue(field.viewX + field.viewW > field.x + field.w - BAR, "the text has the room the bar would take");
        }
    }

    @Test
    void theWheelOverTheBarScrollsTheText() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = inPane(h, 40);
            RetainedNode bar = bar(h, f);
            h.wheel(0, -1, bar.x + bar.w / 2f, bar.y + bar.h / 2f).frame();
            assertTrue(f.node().layout().scrollY() > 0f, "one notch over the bar moved the text");
        }
    }

    @Test
    void anUnwrappedFieldAlsoGetsABarAlongItsBottomAndASingleLineFieldNone() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField code = new TextField(h.gui, "x".repeat(200)).multiline(true).wordWrap(false);
            TextField line = new TextField(h.gui, "y".repeat(200));
            h.gui.root().children(h.gui.column().width(Length.FILL).children(code.node(), line.node()));
            h.frame();
            long bottomBars = h.retained(code.node()).children.stream()
                    .filter(c -> "scrollbar".equals(c.role()) && c.dockEdge() == Dock.BOTTOM).count();
            assertEquals(1, bottomBars, "a field that does not wrap can scroll sideways, so it has a bar for it");
            assertTrue(h.retained(line.node()).children.isEmpty(), "a single-line field never has one");

            code.multiline(false);
            h.frame();
            assertFalse(h.retained(code.node()).children.stream().anyMatch(c -> "scrollbar".equals(c.role())),
                    "and a field that stops being multi-line gives its bars up");
        }
    }

    /** Growing past its slot (overhang) comes first: a fitted field shows its text whole, and only then scrolls. */
    @Test
    void aFittedFieldGrowsFirstAndShowsNoBarUntilItReachesTheRoom() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = new TextField(h.gui, "line\n".repeat(7) + "last").multiline(true);
            f.node().width(Length.rem(15)).height(Length.rem(4));
            h.gui.root().children(h.gui.column().width(Length.FILL).children(f.node()));
            h.frame();
            h.focus(f.node()).frame().frame().frame();
            NodeLayout l = f.node().layout();
            assertTrue(l.rect().h() > 64f, "eight lines in a 4rem box: it grew");
            assertFalse(h.retained(f.node()).overflowY, "and shows them all, so there is nothing to scroll");
            assertFalse(bar(h, f).visible(), "and no bar");
        }
    }
}
