package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The two padding forms write different props — {@code PADDING}, and {@code PADDING_Y}/{@code PADDING_X} — and the
 * per-axis pair wins where both are set. So the one-argument form clears the pair: the last call wins, whichever
 * form, and a node "reset" with {@code padding(Length.ZERO)} is not left padded by an axis call nobody can see.
 * {@code Board} hit it (docs/plans/todo.md §9.1): a landed card sat 5px low.
 */
class PaddingShorthandTest {

    private static float noText(RetainedNode n, dev.vexelray.gui.core.layout.LayoutEnums.Axis axis, float px) {
        return 0f;
    }

    /** The child's offset from its padded parent's corner, after one frame. */
    private static float[] inset(Gui gui, Node parent, Node child) {
        gui.frame(400f, 200f, PaddingShorthandTest::noText);
        return new float[]{child.layout().rect().x() - parent.layout().rect().x(),
                child.layout().rect().y() - parent.layout().rect().y()};
    }

    @Test
    void aLaterUniformPaddingReplacesAnEarlierPerAxisOne() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node cell = gui.box().width(Length.dp(10)).height(Length.dp(10));
            Node box = gui.row().padding(Length.dp(5), Length.dp(20)).children(cell);
            gui.root().children(box);
            float[] before = inset(gui, box, cell);
            assertEquals(20f, before[0], 0.01f, "the axis form pads left by its horizontal");
            assertEquals(5f, before[1], 0.01f, "and top by its vertical");

            box.padding(Length.ZERO);
            float[] after = inset(gui, box, cell);
            assertEquals(0f, after[0], 0.01f, "padding(ZERO) takes the horizontal padding away");
            assertEquals(0f, after[1], 0.01f, "and the vertical");
        }
    }

    @Test
    void aLaterPerAxisPaddingStillWinsOverAnEarlierUniformOne() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node cell = gui.box().width(Length.dp(10)).height(Length.dp(10));
            Node box = gui.row().padding(Length.dp(7)).padding(Length.dp(5), Length.dp(20)).children(cell);
            gui.root().children(box);
            float[] inset = inset(gui, box, cell);
            assertEquals(20f, inset[0], 0.01f);
            assertEquals(5f, inset[1], 0.01f);
        }
    }
}
