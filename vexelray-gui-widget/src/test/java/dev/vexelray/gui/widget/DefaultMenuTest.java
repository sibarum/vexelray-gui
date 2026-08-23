package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.input.MenuItem;
import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The menus the framework's widgets carry without being asked. A right click that opens nothing is the commonest
 * way a UI feels unfinished, so a field and a tab answer one by default — and each answers with what actually
 * applies to it at that moment, which is the whole reason a menu is built per click rather than declared once.
 */
class DefaultMenuTest {

    @Test
    void anEditableFieldOffersTheClipboardAndGreysWhatCannotApply() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField field = new TextField(h.gui, "hello");
            field.node().width(Length.FILL);
            h.gui.root().children(field.node());
            h.frame();

            rightClickField(h, field);

            assertEquals(List.of("Copy", "Cut", "Paste"), labels(h), "the clipboard, in the platform order");
            assertFalse(enabled(h, "Copy"), "nothing is selected, so there is nothing to copy");
            assertFalse(enabled(h, "Cut"), "or to cut");
            assertFalse(enabled(h, "Paste"), "and the clipboard is empty");
            field.close();
        }
    }

    @Test
    void aSelectionAndAFullClipboardLightTheItemsUp() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField field = new TextField(h.gui, "hello");
            field.node().width(Length.FILL);
            h.gui.root().children(field.node());
            h.frame();
            h.gui.clipboard().set("world");
            h.focus(field.node());
            h.chord(Key.A, Key.LEFT_CONTROL);   // select all
            h.frame();

            rightClickField(h, field);

            assertTrue(enabled(h, "Copy"));
            assertTrue(enabled(h, "Cut"));
            assertTrue(enabled(h, "Paste"));
            field.close();
        }
    }

    /** The menu describes the field *now*: select, open it, clear the selection, open it again. */
    @Test
    void theSameFieldAnswersDifferentlyAsItsStateChanges() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField field = new TextField(h.gui, "hello");
            field.node().width(Length.FILL);
            h.gui.root().children(field.node());
            h.frame();
            h.focus(field.node());
            h.chord(Key.A, Key.LEFT_CONTROL);
            h.frame();

            rightClickField(h, field);
            assertTrue(enabled(h, "Copy"), "with a selection");

            h.tap(Key.ESCAPE);   // dismiss: a right click inside an open menu is a click on the menu
            h.frame();
            h.tap(Key.LEFT);     // collapses the selection
            h.frame();
            rightClickField(h, field);
            assertFalse(enabled(h, "Copy"), "and without one, from the same field and the same source");
            field.close();
        }
    }

    @Test
    void aReadOnlyFieldOffersCopyAlone() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField field = new TextField(h.gui, "hello").readOnly(true);
            field.node().width(Length.FILL);
            h.gui.root().children(field.node());
            h.frame();

            rightClickField(h, field);

            assertEquals(List.of("Copy"), labels(h),
                    "cut and paste are exactly the channel a read-only field closed");
            field.close();
        }
    }

    /** Read-only is about the user, not the content: the keyboard is refused, the application is not. */
    @Test
    void aReadOnlyFieldRefusesTheKeyboardAndStillSelectsAndCopies() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField field = new TextField(h.gui, "hello").readOnly(true);
            field.node().width(Length.FILL);
            h.gui.root().children(field.node());
            h.frame();
            h.focus(field.node());

            h.type("X");
            h.frame();
            h.tap(Key.BACKSPACE);
            h.frame();
            h.gui.clipboard().set("paste me");
            h.chord(Key.V, Key.LEFT_CONTROL);
            h.frame();
            assertEquals("hello", field.text(), "typing, deleting and pasting all bounced");

            field.insert(" world");   // the application's own write, which read-only says nothing about
            h.frame();
            assertEquals("hello world", field.text(), "and the application writes to it as before");

            h.chord(Key.A, Key.LEFT_CONTROL);
            h.frame();
            h.chord(Key.C, Key.LEFT_CONTROL);
            h.frame();
            assertEquals("hello world", h.gui.clipboard().get(),
                    "while selecting and copying — the point of a selectable field — still work");
            field.close();
        }
    }

    @Test
    void anApplicationAddsToTheFieldMenuWithoutRestatingTheClipboard() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField field = new TextField(h.gui, "hello");
            field.onContextMenu(menu -> menu.separator().item("Look up", () -> { }));
            field.node().width(Length.FILL);
            h.gui.root().children(field.node());
            h.frame();

            rightClickField(h, field);

            assertEquals(java.util.Arrays.asList("Copy", "Cut", "Paste", null, "Look up"), labels(h),
                    "the widget's items first, then the application's, with the rule it asked for");
            field.close();
        }
    }

    @Test
    void aTabOffersCloseAndClosingItRemovesTheTab() {
        try (HeadlessGui h = new HeadlessGui()) {
            Tabs tabs = new Tabs(h.gui);
            tabs.add("Editor", h.gui.box());
            tabs.add("Files", h.gui.box());
            h.gui.root().children(tabs.node());
            h.frame();

            var header = tabs.node().layout();   // the bar is the first child; aim at the second header
            rightClick(h, headerCenterX(tabs, 1), headerCenterY(tabs));
            assertEquals(List.of("Close"), labels(h), "the one thing every tab bar can do");

            choose(h, 0, 1);

            assertEquals(1, tabs.count(), "choosing Close removed the tab that was right-clicked");
            assertEquals(0, tabs.selected(), "and the selection moved to a surviving one");
            assertFalse(header == null);
        }
    }

    @Test
    void anApplicationAddsToTheTabMenuAndIsToldWhichTab() {
        try (HeadlessGui h = new HeadlessGui()) {
            int[] askedFor = {-1};
            Tabs tabs = new Tabs(h.gui);
            tabs.onContextMenu((index, menu) -> {
                askedFor[0] = index;
                menu.item("Duplicate", () -> { });
            });
            tabs.add("Editor", h.gui.box());
            tabs.add("Files", h.gui.box());
            h.gui.root().children(tabs.node());
            h.frame();

            rightClick(h, headerCenterX(tabs, 1), headerCenterY(tabs));

            assertEquals(1, askedFor[0], "the source was told which tab the click was on");
            assertEquals(List.of("Close", "Duplicate"), labels(h));
        }
    }

    /** A widget with a default menu installs a presenter, so the defaults work in a UI that never mentions menus. */
    @Test
    void aFieldInstallsWhateverIsNeededToShowItsOwnMenu() {
        try (HeadlessGui h = new HeadlessGui()) {
            assertTrue(h.gui.menus() == null, "a bare tree shows no menus");
            TextField field = new TextField(h.gui, "hello");
            assertTrue(h.gui.menus() instanceof ContextMenu, "building the field installed one");

            ContextMenu installed = (ContextMenu) h.gui.menus();
            TextField second = new TextField(h.gui, "world");
            assertEquals(installed, h.gui.menus(), "and the next widget did not replace it");
            field.close();
            second.close();
        }
    }

    // --- helpers ---

    private static void rightClickField(HeadlessGui h, TextField field) {
        var r = field.node().layout().rect();
        rightClick(h, r.x() + r.w() / 2f, r.y() + r.h() / 2f);
    }

    private static void rightClick(HeadlessGui h, float x, float y) {
        h.rightClick(x, y);
        h.frame();
    }

    /** Choose item {@code index} of a menu of {@code count} rows, by arithmetic on its published box. */
    private static void choose(HeadlessGui h, int index, int count) {
        var m = menu(h).node().layout().rect();
        float itemH = (m.h() - 8f) / count;
        h.click(m.x() + m.w() / 2f, m.y() + 4f + index * itemH + itemH / 2f);
        h.frame();
    }

    private static ContextMenu menu(HeadlessGui h) {
        return (ContextMenu) h.gui.menus();
    }

    private static List<String> labels(HeadlessGui h) {
        return menu(h).items().stream().map(MenuItem::label).toList();
    }

    private static boolean enabled(HeadlessGui h, String label) {
        return menu(h).items().stream().anyMatch(i -> label.equals(i.label()) && i.enabled());
    }

    private static float headerCenterX(Tabs tabs, int index) {
        var r = tabs.header(index).layout().rect();
        return r.x() + r.w() / 2f;
    }

    private static float headerCenterY(Tabs tabs) {
        var r = tabs.header(0).layout().rect();
        return r.y() + r.h() / 2f;
    }
}
