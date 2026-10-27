package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.Dock;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A docked float keeps its parent's content out of the band between it and the edge it is docked to — the top, as a
 * find bar always has, and now the right and the bottom, where a field's scrollbars sit (docs/plans/scrolling.md §7.3).
 */
class DockEdgeTest {

    private static float noText(RetainedNode n, Axis axis, float px) {
        return 0f;
    }

    private static Length px(float p) {
        return Length.rem(p / 16f);
    }

    /** A 200×100 box holding one flow child that fills it, and a float docked to {@code edge}. */
    private static Node[] box(Gui gui, Dock edge, Length floatX, Length floatY, Length w, Length h) {
        Node content = gui.box().width(Length.FILL).height(Length.FILL);
        Node docked = gui.box().width(w).height(h).floatAt(floatX, floatY).dock(edge);
        Node box = gui.column().width(px(200)).height(px(100)).children(content, docked);
        gui.root().children(box);
        gui.frame(400f, 300f, DockEdgeTest::noText);
        return new Node[]{box, content};
    }

    @Test
    void aFloatDockedRightKeepsTheContentToItsLeft() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node[] n = box(gui, Dock.RIGHT, Length.percent(100), Length.ZERO, px(14), Length.FILL);
            assertEquals(186f, n[1].layout().rect().w(), 0.5f, "the content stops where the float begins");
            assertEquals(100f, n[1].layout().rect().h(), 0.5f, "and keeps its whole height");
        }
    }

    @Test
    void aFloatDockedBottomKeepsTheContentAboveIt() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node[] n = box(gui, Dock.BOTTOM, Length.ZERO, Length.percent(100), Length.FILL, px(14));
            assertEquals(86f, n[1].layout().rect().h(), 0.5f, "the content stops where the float begins");
            assertEquals(200f, n[1].layout().rect().w(), 0.5f, "and keeps its whole width");
        }
    }

    @Test
    void aHiddenDockedFloatReservesNothing() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node content = gui.box().width(Length.FILL).height(Length.FILL);
            Node docked = gui.box().width(px(14)).height(Length.FILL).floatAt(Length.percent(100), Length.ZERO)
                    .dock(Dock.RIGHT).visible(false);
            gui.root().children(gui.column().width(px(200)).height(px(100)).children(content, docked));
            gui.frame(400f, 300f, DockEdgeTest::noText);
            assertEquals(200f, content.layout().rect().w(), 0.5f);
        }
    }
}
