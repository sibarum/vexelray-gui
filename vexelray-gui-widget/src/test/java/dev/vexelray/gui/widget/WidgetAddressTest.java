package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.tactroller.api.Key;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What a widget does is a message carrying its address: the tree's name and the widget's own, so a name need only be
 * unique in its tree. A view hears it through a mailbox, in the frame it happened.
 */
class WidgetAddressTest {

    @Test
    void aWidgetsAddressIsItsTreesNameAndItsOwn() {
        try (HeadlessGui h = new HeadlessGui()) {
            Button save = new Button(h.gui, "Save").name("save");
            h.gui.root().children(save.node());
            List<Button.Pressed> heard = h.heard(Button.PRESSED, save.address());
            h.frame().focus(save.node());

            h.tap(Key.ENTER);

            assertEquals(h.gui.name() + ".save", save.address());
            assertEquals(List.of(new Button.Pressed(save.address())), heard, "heard in the frame it was pressed");
        }
    }

    @Test
    void twoTreesWidgetsOfOneNameAreTwoAddresses() {
        try (HeadlessGui a = new HeadlessGui(); HeadlessGui b = new HeadlessGui()) {
            TextField one = new TextField(a.gui).name("path");
            TextField two = new TextField(b.gui).name("path");

            assertNotEquals(one.address(), two.address());
        }
    }

    @Test
    void aGuisNameHoldsNoDotBecauseADotEndsIt() {
        assertThrows(IllegalArgumentException.class, () -> new Gui(Atchung.create(), "editor.2"));
        assertEquals("editor-2.path", new Gui(Atchung.create(), "editor-2").address("path"));
    }
}
