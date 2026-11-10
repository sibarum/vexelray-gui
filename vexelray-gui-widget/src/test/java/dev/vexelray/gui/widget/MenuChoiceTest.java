package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.Menu;
import dev.vexelray.gui.core.input.MenuItem;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.icons.Icon;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Sender;
import sibarum.atchung.Topic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A menu line can be a setting: on or off by itself (a tick), or one of a radio group (a dot on the one chosen). It
 * says where the setting stands and sends its message as any line does; the column it says it in is the menu's.
 */
class MenuChoiceTest {

    private static List<RetainedNode> rows(HeadlessGui h, ContextMenu menu) {
        return h.retained(menu.node()).children;
    }

    private static int checked(RetainedNode n) {
        int count = "checked".equals(n.role()) ? 1 : 0;
        for (RetainedNode c : n.children) {
            count += checked(c);
        }
        return count;
    }

    private static ContextMenu open(HeadlessGui h, Menu declared) {
        ContextMenu menu = new ContextMenu(h.gui);
        Node target = h.gui.box().width(Length.FILL).height(Length.FILL);
        Seams.of(h.gui).onContextMenu(target, m -> declared.items().forEach(m::item));
        h.gui.root().children(target);
        h.frame();
        h.rightClick(200f, 150f);
        h.frame();
        return menu;
    }

    @Test
    void theBuilderMakesTheLineAddedLastATogglerOrARadioLine() {
        List<String> sent = new ArrayList<>();
        Sender<String> to = Sender.collecting(Topic.of("test.menu", String.class), sent);
        Menu menu = Menu.builder()
                .item("Word wrap", to, "wrap").toggle(true)
                .item("Line numbers", to, "numbers").toggle(false)
                .separator()
                .heading("Sort by")
                .toggle(true)   // after a heading: nothing to make a setting of
                .item("Name", to, "name").radio("sort", true)
                .item("Date", to, "date").radio("sort", false)
                .item("Refresh", to, "refresh")
                .build();
        List<MenuItem> items = menu.items();

        assertTrue(items.get(0).chooses() && items.get(0).choice().on() && !items.get(0).choice().radio());
        assertFalse(items.get(1).choice().on(), "a toggle that is off is still a toggle");
        assertNull(items.get(3).choice(), "a heading takes no choice");
        assertEquals("sort", items.get(4).choice().group());
        assertTrue(items.get(4).choice().on());
        assertFalse(items.get(5).choice().on());
        assertFalse(items.get(6).chooses(), "a command is not a setting");

        items.get(5).action().run();
        assertEquals(List.of("date"), sent, "a radio line sends its own message, as any line does");
    }

    @Test
    void aRadioGroupWithTwoLinesChosenIsRefusedWhereItIsDeclared() {
        Sender<String> to = Sender.collecting(Topic.of("test.menu", String.class), new ArrayList<>());
        Menu.Builder builder = Menu.builder()
                .item("Name", to, "name").radio("sort", true)
                .item("Date", to, "date").radio("sort", true);
        IllegalStateException e = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(e.getMessage().contains("sort"), e.getMessage());

        Menu.builder()
                .item("Name", to, "name").radio("sort", true)
                .item("Small", to, "small").radio("size", true)
                .build();   // one chosen in each of two groups is fine
    }

    @Test
    void aSettingIsTickedOrDottedOnlyWhileOnInAColumnTheWholeMenuShares() {
        try (HeadlessGui h = new HeadlessGui()) {
            Sender<String> to = Sender.collecting(Topic.of("test.menu", String.class), new ArrayList<>());
            ContextMenu menu = open(h, Menu.builder()
                    .item("Word wrap", to, "wrap").toggle(true)
                    .item("Line numbers", to, "numbers").toggle(false)
                    .item("Name", to, "name").radio("sort", true)
                    .item("Date", to, "date").radio("sort", false)
                    .item("Refresh", to, "refresh")
                    .build());

            List<RetainedNode> rows = rows(h, menu);
            assertEquals(5, rows.size());
            assertEquals("menuitemcheckbox", rows.get(0).role());
            assertEquals("menuitemradio", rows.get(2).role());
            assertEquals("menuitem", rows.get(4).role());
            assertEquals(List.of(1, 0, 1, 0, 0), rows.stream().map(MenuChoiceTest::checked).toList(),
                    "a mark on the toggle that is on and the radio line chosen, and nowhere else");
            for (RetainedNode row : rows) {
                assertEquals(2, row.children.size(), "a choice cell and the label, the command's cell empty");
            }
            float labelX = rows.get(0).children.get(1).x;
            for (RetainedNode row : rows) {
                assertEquals(labelX, row.children.get(1).x, 0.01f, "so every label starts at the same x");
            }
            menu.close();
        }
    }

    @Test
    void theChoiceColumnStandsBeforeTheIcons() {
        try (HeadlessGui h = new HeadlessGui()) {
            Sender<String> to = Sender.collecting(Topic.of("test.menu", String.class), new ArrayList<>());
            ContextMenu menu = open(h, Menu.builder()
                    .item(Icon.WINDOW, "MainFrame", to, "window").radio("keys", false)
                    .item(Icon.SHELL, "The shell", to, "shell").radio("keys", true)
                    .build());

            List<RetainedNode> rows = rows(h, menu);
            for (RetainedNode row : rows) {
                assertEquals(3, row.children.size(), "the choice, the icon, the label");
                assertTrue(row.children.get(0).x < row.children.get(1).x, "the choice first");
            }
            assertEquals(0, checked(rows.get(0).children.get(0)));
            assertEquals(1, checked(rows.get(1).children.get(0)), "the dot is on the line chosen");
            menu.close();
        }
    }

    @Test
    void aMenuWithNoSettingsReservesNoChoiceColumn() {
        try (HeadlessGui h = new HeadlessGui()) {
            Sender<String> to = Sender.collecting(Topic.of("test.menu", String.class), new ArrayList<>());
            ContextMenu menu = open(h, Menu.builder()
                    .item("Open", to, "open")
                    .item("Rename", to, "rename")
                    .build());
            for (RetainedNode row : rows(h, menu)) {
                assertEquals(1, row.children.size(), "a label and nothing else, as before there were settings");
            }
            menu.close();
        }
    }
}
