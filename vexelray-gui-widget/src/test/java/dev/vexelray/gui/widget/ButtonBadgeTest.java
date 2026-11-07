package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.style.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A button's badge ({@link Button#badge}): a count at the button's far end that is part of the button.
 *
 * <p>What is pinned: the badge sits <b>inside the button, at its far end</b>; a click on it <b>presses the button</b>;
 * it turns <b>accent while a chip is pressed</b>; and empty takes it away.
 */
class ButtonBadgeTest {

    private static RetainedNode badgeOf(HeadlessGui h, Button b) {
        return h.retained(b.node()).children.get(1);
    }

    private static Rect rect(HeadlessGui h, RetainedNode n) {
        return h.gui.layoutSnapshot().node(n.id).rect();
    }

    @Test
    void theBadgeSitsAtTheFarEndInsideTheButton() {
        try (HeadlessGui h = new HeadlessGui()) {
            Button b = new Button(h.gui, "Just this file").badge("1");
            b.node().width(Length.dp(300));
            h.gui.root().children(b.node());
            h.frame();

            Rect button = rect(h, h.retained(b.node()));
            Rect badge = rect(h, badgeOf(h, b));
            assertTrue(badge.w() > 0, "a badge with words is shown");
            assertTrue(badge.x() >= button.x() && badge.x() + badge.w() <= button.x() + button.w(),
                    "inside the button");
            assertTrue(badge.x() > button.x() + button.w() / 2f, "at its far end, past the words");
        }
    }

    @Test
    void aClickOnTheBadgePressesTheButton() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<Button.Pressed> pressed = new ArrayList<>();
            h.bus.subscribe(Button.PRESSED, pressed::add);
            Button b = new Button(h.gui, "Tabs").badge("12");
            h.gui.root().children(b.node());
            h.frame();

            Rect badge = rect(h, badgeOf(h, b));
            h.click(badge.x() + badge.w() / 2f, badge.y() + badge.h() / 2f);
            h.frame();
            assertEquals(1, pressed.size(), "the badge is part of what is pressed");
        }
    }

    @Test
    void aPressedChipsBadgeIsAccent() {
        try (HeadlessGui h = new HeadlessGui()) {
            Button chip = new Button(h.gui, "Always").toggle(true).badge("3");
            h.gui.root().children(chip.node());
            h.frame();
            var off = badgeOf(h, chip).background(h.gui.drawnTheme());

            chip.show(true);
            h.frame();
            var on = badgeOf(h, chip).background(h.gui.drawnTheme());
            assertNotEquals(off, on, "picking the chip recolours its badge");
            assertEquals(h.gui.drawnTheme().color(Role.ACCENT), on, "to the accent");
        }
    }

    @Test
    void emptyTakesItAway() {
        try (HeadlessGui h = new HeadlessGui()) {
            Button b = new Button(h.gui, "Open").badge("2").badge("");
            h.gui.root().children(b.node());
            h.frame();
            assertFalse(badgeOf(h, b).visible(), "an empty badge is not shown");
        }
    }
}
