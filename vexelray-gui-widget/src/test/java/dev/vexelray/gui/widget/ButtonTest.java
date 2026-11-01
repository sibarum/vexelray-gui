package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.icons.Icon;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a hand-rolled button leaves out and this one carries: the keyboard, a disabled state that means it, and a
 * chip whose pressed value the label, the colour and the callback all agree on.
 */
class ButtonTest {

    @Test
    void enterAndSpaceActivateAFocusedButton() {
        try (HeadlessGui h = new HeadlessGui()) {
            AtomicInteger presses = new AtomicInteger();
            Button b = new Button(h.gui, "Move");
            b.onPress(presses::incrementAndGet);
            h.gui.root().children(b.node());
            h.frame().focus(b.node());

            h.tap(Key.ENTER);
            assertEquals(1, presses.get(), "Enter presses it");
            h.tap(Key.SPACE);
            assertEquals(2, presses.get(), "and so does Space");
        }
    }

    @Test
    void aDisabledButtonIgnoresTheKeyboardAndLeavesTheFocusOrder() {
        try (HeadlessGui h = new HeadlessGui()) {
            AtomicInteger presses = new AtomicInteger();
            Button b = new Button(h.gui, "Delete").onPress(presses::incrementAndGet).enabled(false);
            h.gui.root().children(b.node());
            h.frame().focus(b.node());

            h.tap(Key.ENTER);
            assertEquals(0, presses.get());
            assertFalse(b.enabled());

            b.enabled(true);
            h.frame().focus(b.node());
            h.tap(Key.ENTER);
            assertEquals(1, presses.get(), "enabling it again gives the keyboard back");
        }
    }

    @Test
    void aChipFlipsAndReportsEachFlipOnce() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<Boolean> seen = new ArrayList<>();
            Button chip = new Button(h.gui, "All videos").toggle(true);
            h.gui.mailbox(Button.TOGGLED, chip.address(), t -> seen.add(t.pressed()));
            h.gui.root().children(chip.node());
            h.frame().focus(chip.node());

            h.tap(Key.ENTER);
            h.tap(Key.ENTER);
            assertEquals(List.of(true, false), seen);
            assertFalse(chip.pressed());
        }
    }

    @Test
    void showingAValueIsNotReportedAsAnEdit() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<Boolean> seen = new ArrayList<>();
            Button chip = new Button(h.gui, "Today").toggle(true);
            h.gui.mailbox(Button.TOGGLED, chip.address(), t -> seen.add(t.pressed()));

            chip.show(true);
            h.frame();
            assertTrue(chip.pressed(), "the chip shows what the model chose");
            assertEquals(List.of(), seen, "and tells nobody, or the panel and the model loop");

            chip.pressed(false);
            h.frame();
            assertEquals(List.of(false), seen, "acting as the user would does tell");
        }
    }

    @Test
    void aPlainButtonIsNeverPressed() {
        try (HeadlessGui h = new HeadlessGui()) {
            Button b = new Button(h.gui, "Go");
            b.show(true);
            assertFalse(b.pressed(), "only a chip holds a value");
        }
    }

    /** An icon button pressed looks pressed, not hovered: a click shows at once, before anything it does. */
    @Test
    void anIconButtonPressedLooksOtherThanHovered() {
        try (HeadlessGui h = new HeadlessGui()) {
            IconButton i = new IconButton(h.gui, Icon.CHEVRON_RIGHT, "Next");
            h.gui.root().children(i.node());
            h.frame();
            var r = i.node().layout().rect();
            float x = r.x() + r.w() / 2f;
            float y = r.y() + r.h() / 2f;

            h.hover(x, y);
            var hovered = h.retained(i.node()).background(h.gui.drawnTheme());
            h.press(x, y);
            var pressed = h.retained(i.node()).background(h.gui.drawnTheme());
            h.release(x, y);
            assertFalse(hovered.equals(pressed), "pressed was drawn as hovered");
            assertEquals(hovered, h.retained(i.node()).background(h.gui.drawnTheme()), "and hovered again after");
        }
    }

    /** Two quick presses of a button are two presses, and not a double click on what the button is in. */
    @Test
    void aButtonPressedTwiceQuicklyKeepsItsDoubleClickFromWhatItIsIn() {
        try (HeadlessGui h = new HeadlessGui()) {
            AtomicInteger presses = new AtomicInteger();
            AtomicInteger doubled = new AtomicInteger();
            Button b = new Button(h.gui, "Next").onPress(presses::incrementAndGet);
            IconButton i = new IconButton(h.gui, Icon.CHEVRON_RIGHT, "Next").onPress(presses::incrementAndGet);
            Node stage = h.gui.box().width(Length.FILL).height(Length.FILL).children(b.node(), i.node());
            Seams.of(h.gui).onDoubleClick(stage, doubled::incrementAndGet);
            h.gui.root().children(stage);
            h.frame();

            for (Node n : List.of(b.node(), i.node())) {
                var r = n.layout().rect();
                h.clicks(r.x() + r.w() / 2f, r.y() + r.h() / 2f, 2);
            }
            assertEquals(4, presses.get(), "each press of each button is a press");
            assertEquals(0, doubled.get(), "a button's double click reached the box it is in");

            var r = stage.layout().rect();
            h.clicks(r.x() + r.w() - 2f, r.y() + r.h() - 2f, 2);
            assertEquals(1, doubled.get(), "a double click on the box itself is the box's");
        }
    }
}
