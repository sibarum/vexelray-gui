package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.spi.Seams;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A menu walked from the keyboard: Up and Down along its lines, past rules, headings and greyed lines and round the
 * ends; Home and End; Enter to choose; Right into a submenu and Left back out. The keys are the menu's only while it is
 * open, and the pointer resting on a line moves the keyboard there.
 */
class ContextMenuKeysTest {

    /** A menu of: A, a rule, a greyed B, a heading, C, and a submenu More of D and E. Opened by a right click. */
    private static ContextMenu opened(HeadlessGui h, List<String> chose) {
        Node target = h.gui.box().width(Length.FILL).height(Length.FILL);
        ContextMenu menu = new ContextMenu(h.gui);
        Seams.of(h.gui).onContextMenu(target, m -> m
                .item("A", () -> chose.add("A"))
                .separator()
                .item("B", false, () -> chose.add("B"))
                .heading("Group")
                .item("C", () -> chose.add("C"))
                .submenu("More", sub -> sub
                        .item("D", () -> chose.add("D"))
                        .item("E", () -> chose.add("E"))));
        h.gui.root().children(target);
        h.frame();
        h.rightClick(200f, 150f);
        h.frame();
        return menu;
    }

    private static void key(HeadlessGui h, Key key) {
        h.chord(key);
        h.frame();
    }

    @Test
    void aMenuOpenedByThePointerHasNothingMarkedUntilAKey() {
        try (HeadlessGui h = new HeadlessGui()) {
            ContextMenu menu = opened(h, new ArrayList<>());
            assertNull(menu.marked(), "a desktop menu opened by the pointer marks no line");
            key(h, Key.DOWN);
            assertEquals("0.0", menu.marked(), "Down marks the first line");
        }
    }

    @Test
    void upAndDownSkipWhatCannotBeChosenAndGoRoundTheEnds() {
        try (HeadlessGui h = new HeadlessGui()) {
            ContextMenu menu = opened(h, new ArrayList<>());
            key(h, Key.DOWN);
            key(h, Key.DOWN);
            assertEquals("0.4", menu.marked(), "past the rule, the greyed B and the heading, to C");
            key(h, Key.DOWN);
            assertEquals("0.5", menu.marked(), "then More");
            key(h, Key.DOWN);
            assertEquals("0.0", menu.marked(), "and round to A");
            key(h, Key.UP);
            assertEquals("0.5", menu.marked(), "and back round the other way");
            key(h, Key.HOME);
            assertEquals("0.0", menu.marked());
            key(h, Key.END);
            assertEquals("0.5", menu.marked());
        }
    }

    @Test
    void enterChoosesTheMarkedLineAndClosesTheMenu() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<String> chose = new ArrayList<>();
            ContextMenu menu = opened(h, chose);
            key(h, Key.DOWN);
            key(h, Key.DOWN);
            key(h, Key.ENTER);
            assertEquals(List.of("C"), chose);
            assertFalse(menu.isOpen());
        }
    }

    @Test
    void rightStepsIntoASubmenuAndLeftStepsBackOut() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<String> chose = new ArrayList<>();
            ContextMenu menu = opened(h, chose);
            key(h, Key.END);
            key(h, Key.RIGHT);
            assertEquals(1, menu.openSubmenus(), "Right on More opens it");
            assertEquals("1.0", menu.marked(), "with the keyboard on its first line");
            key(h, Key.DOWN);
            assertEquals("1.1", menu.marked());
            key(h, Key.LEFT);
            assertEquals(0, menu.openSubmenus(), "Left closes it");
            assertEquals("0.5", menu.marked(), "and goes back to the line that opened it");
            key(h, Key.ENTER);
            assertEquals(1, menu.openSubmenus(), "Enter on a submenu opens it too");
            key(h, Key.DOWN);
            key(h, Key.ENTER);
            assertEquals(List.of("E"), chose);
            assertFalse(menu.isOpen(), "a command in a submenu closes them all");
        }
    }

    @Test
    void thePointerMovesTheKeyboard() {
        try (HeadlessGui h = new HeadlessGui()) {
            ContextMenu menu = opened(h, new ArrayList<>());
            Node c = h.gui.landmarkNode("menu.c").orElseThrow();
            var r = h.gui.layoutSnapshot().node(h.retained(c).id).rect();
            h.hover(r.x() + r.w() / 2f, r.y() + r.h() / 2f);
            h.frame();
            assertEquals("0.4", menu.marked(), "resting on C puts the keyboard there");
            key(h, Key.DOWN);
            assertEquals("0.5", menu.marked(), "and the keys go on from it");
        }
    }

    @Test
    void theKeysAreTheMenusOnlyWhileItIsOpen() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<String> chose = new ArrayList<>();
            ContextMenu menu = opened(h, chose);
            key(h, Key.ESCAPE);
            h.frame();
            assertFalse(menu.isOpen());
            key(h, Key.DOWN);
            key(h, Key.ENTER);
            assertTrue(chose.isEmpty(), "a closed menu takes no keys");
            assertNull(menu.marked());
        }
    }

    @Test
    void markFirstPutsTheKeyboardOnTheFirstLineThatCanBeChosen() {
        try (HeadlessGui h = new HeadlessGui()) {
            ContextMenu menu = opened(h, new ArrayList<>());
            menu.markFirst();
            assertEquals("0.0", menu.marked());
        }
    }
}
