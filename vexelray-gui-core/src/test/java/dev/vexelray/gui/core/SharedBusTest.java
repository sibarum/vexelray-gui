package dev.vexelray.gui.core;

import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Two trees on one bus (vexelray-framework's {@code docs/threading.md}, T4.6): every topic a tree uses for itself is
 * named by the tree, so neither applies the other's edits or hears the other's input, and a name is one open tree's.
 */
class SharedBusTest {

    private static final float W = 400f;
    private static final float H = 300f;

    private static float noText(RetainedNode n, Axis axis, float px) {
        return 0f;
    }

    private static RetainedNode frame(Gui gui) {
        return Seams.of(gui).frame(W, H, SharedBusTest::noText);
    }

    private static Node fill(Gui gui) {
        Node box = gui.box().width(Length.FILL).height(Length.FILL);
        gui.root().children(box);
        return box;
    }

    @Test
    void eachTreeAppliesOnlyItsOwnEdits() {
        Atchung bus = Atchung.create();
        try (Gui a = new Gui(bus, "a"); Gui b = new Gui(bus, "b")) {
            fill(a);
            fill(a);

            assertEquals(2, frame(a).children.size(), "a has what it was given");
            assertEquals(0, frame(b).children.size(), "and b, on the same bus, none of it");
        }
    }

    @Test
    void eachTreeHearsOnlyItsOwnInput() {
        Atchung bus = Atchung.create();
        try (Gui a = new Gui(bus, "a"); Gui b = new Gui(bus, "b")) {
            List<String> clicked = new ArrayList<>();
            Seams.of(a).onClick(fill(a), () -> clicked.add("a"));
            Seams.of(b).onClick(fill(b), () -> clicked.add("b"));
            frame(a);
            frame(b);

            bus.publish(b.input(), new InputEvent.ButtonPressed(MouseButton.LEFT, 20, 20, 0));
            bus.publish(b.input(), new InputEvent.ButtonReleased(MouseButton.LEFT, 20, 20, 0));
            frame(a);
            frame(b);

            assertEquals(List.of("b"), clicked, "the click was b's window's, and only b heard it");
        }
    }

    @Test
    void aNameIsOneOpenTreesOnABus() {
        Atchung bus = Atchung.create();
        Gui first = new Gui(bus, "main");
        assertThrows(IllegalStateException.class, () -> new Gui(bus, "main"), "two trees would share every topic");
        try (Gui elsewhere = new Gui(Atchung.create(), "main")) {
            assertEquals("main", elsewhere.name(), "another bus is another namespace");
        }

        first.close();
        try (Gui again = new Gui(bus, "main")) {
            assertEquals("main", again.name(), "closing a tree gives its name back");
        }
    }
}
