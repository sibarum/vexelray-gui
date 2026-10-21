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
 * Which containers scroll without being asked: one long enough along an axis to hold a scrollbar with somewhere to
 * travel, and not one so short that a pill would be squeezed into it. A declaration either way always wins.
 */
class SensibleScrollTest {

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

    /** A column {@code heightRem} tall holding content three times that. */
    private static Node overfull(Gui gui, float heightRem) {
        return gui.column().width(Length.rem(10)).height(Length.rem(heightRem))
                .children(gui.box().width(Length.rem(4)).height(Length.rem(heightRem * 3f)));
    }

    private static RetainedNode laidOut(Gui gui, Node node) {
        gui.root().children(node);
        return find(gui.frame(800f, 600f, SensibleScrollTest::noText), node.id());
    }

    @Test
    void aBoxTooShortForAScrollbarOverflowsInsteadOfScrolling() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            RetainedNode chip = laidOut(gui, overfull(gui, 2f));
            assertFalse(chip.overflowY, "no pill squeezed into a two-em box");
            assertEquals(chip.w, chip.viewW, "and no gutter taken from its content");
        }
    }

    @Test
    void aBoxLongEnoughStillScrollsByDefault() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            RetainedNode panel = laidOut(gui, overfull(gui, 10f));
            assertTrue(panel.overflowY, "a panel that overflows still gets its scrollbar, unasked");
            assertTrue(panel.viewW < panel.w, "reserving its gutter, as before");
        }
    }

    @Test
    void aDeclarationWinsEitherWay() {
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            Node shortButAsked = overfull(gui, 2f).scroll(false, true);
            Node longButRefused = overfull(gui, 10f).scroll(false, false);
            gui.root().children(gui.column().children(shortButAsked, longButRefused));
            RetainedNode root = gui.frame(800f, 900f, SensibleScrollTest::noText);
            assertTrue(find(root, shortButAsked.id()).overflowY, "a short box that says it scrolls, does");
            assertFalse(find(root, longButRefused.id()).overflowY);
        }
    }
}
