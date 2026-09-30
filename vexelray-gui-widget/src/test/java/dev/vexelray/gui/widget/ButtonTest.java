package dev.vexelray.gui.widget;

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
            Button chip = new Button(h.gui, "All videos").toggle(true).onToggle(seen::add);
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
            Button chip = new Button(h.gui, "Today").toggle(true).onToggle(seen::add);

            chip.show(true);
            assertTrue(chip.pressed(), "the chip shows what the model chose");
            assertEquals(List.of(), seen, "and tells nobody, or the panel and the model loop");

            chip.pressed(false);
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
}
