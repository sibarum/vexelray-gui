package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A slot's place never changes: what it says may. */
class StatusBarTest {

    @Test
    void changingWhatALaterSlotSaysMovesNothingBeforeIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            StatusBar bar = new StatusBar(h.gui)
                    .slot("items", StatusBar.Side.LEFT, "16 items")
                    .slot("selected", StatusBar.Side.LEFT, "0 selected");
            bar.node().width(Length.FILL).height(Length.rem(2));
            h.gui.root().children(bar.node());
            h.frame();
            float x = bar.slot("selected").layout().rect().x();
            assertTrue(x > 0f, "the slot is laid out");

            bar.text("selected", "9 selected").text("items", "16 items");
            h.frame();
            assertEquals(x, bar.slot("selected").layout().rect().x(),
                    "the text before it did not change, so nothing moved");
        }
    }

    @Test
    void anUndeclaredSlotIsIgnoredAndADuplicateIsRefused() {
        try (HeadlessGui h = new HeadlessGui()) {
            StatusBar bar = new StatusBar(h.gui).slot("a", StatusBar.Side.RIGHT, "x");
            assertDoesNotThrow(() -> bar.text("nope", "y"));
            assertThrows(IllegalArgumentException.class, () -> bar.slot("a", StatusBar.Side.LEFT, "z"));
        }
    }
}
