package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.input.Menu;
import dev.vexelray.gui.core.layout.Rect;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Sender;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A menu button ({@link Button#menu}): a left click, Enter or Space drops a declared menu from the button, and a line
 * sends its own message.
 *
 * <p>What is pinned: the menu <b>hangs under the button</b>, not at the pointer; the press <b>publishes no
 * {@link Button#PRESSED}</b>; a second press closes it; a key opens it with the first line marked; and
 * {@link Menu#NONE} makes it a plain button again.
 */
class MenuButtonTest {

    record Program(String name) { }

    private static final Topic<Program> OPEN_WITH = Topic.edge("test.menubutton.open-with", Program.class);

    private static Menu programs(List<Program> sent) {
        Sender<Program> open = Sender.collecting(OPEN_WITH, sent);
        return Menu.builder()
                .item("Rasterware", open, new Program("rasterware"))
                .item("The desktop's", open, new Program("desktop"))
                .build();
    }

    private static Rect rect(HeadlessGui h, Button b) {
        return h.gui.layoutSnapshot().node(h.retained(b.node()).id).rect();
    }

    private static void click(HeadlessGui h, Button b) {
        Rect r = rect(h, b);
        h.click(r.x() + r.w() / 2f, r.y() + r.h() / 2f);
        h.frame();
    }

    private static List<String> labels(ContextMenu menu) {
        return menu.items().stream().map(i -> i.label()).toList();
    }

    @Test
    void aClickDropsTheMenuUnderTheButtonAndALineSendsItsMessage() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<Program> sent = new ArrayList<>();
            List<Button.Pressed> pressed = new ArrayList<>();
            h.bus.subscribe(Button.PRESSED, pressed::add);
            ContextMenu menu = new ContextMenu(h.gui);
            Button b = new Button(h.gui, "Open with").menu(programs(sent));
            h.gui.root().children(b.node());
            h.frame();

            click(h, b);
            assertTrue(menu.isOpen(), "a left click opens it");
            assertEquals(List.of("Rasterware", "The desktop's"), labels(menu));
            Rect button = rect(h, b);
            Rect panel = h.gui.layoutSnapshot().node(h.retained(menu.node()).id).rect();
            assertTrue(panel.y() >= button.y() + button.h(), "it hangs under the button, not at the pointer");
            assertTrue(pressed.isEmpty(), "a menu button's press is its menu, not a PRESSED");

            Rect line = h.gui.layoutSnapshot().node(h.retained(h.gui.landmarkNode("menu.rasterware").orElseThrow()).id).rect();
            h.click(line.x() + line.w() / 2f, line.y() + line.h() / 2f);
            h.frame();
            assertEquals(List.of(new Program("rasterware")), sent);
            assertFalse(menu.isOpen(), "choosing a line closes it");
        }
    }

    @Test
    void aSecondClickClosesIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            ContextMenu menu = new ContextMenu(h.gui);
            Button b = new Button(h.gui, "Open with").menu(programs(new ArrayList<>()));
            h.gui.root().children(b.node());
            h.frame();

            click(h, b);
            assertTrue(menu.isOpen());
            click(h, b);
            assertFalse(menu.isOpen(), "the button that opened it puts it away");
        }
    }

    @Test
    void enterOpensItWithTheFirstLineMarked() {
        try (HeadlessGui h = new HeadlessGui()) {
            ContextMenu menu = new ContextMenu(h.gui);
            Button b = new Button(h.gui, "Open with").menu(programs(new ArrayList<>()));
            h.gui.root().children(b.node());
            h.frame().focus(b.node());

            h.tap(Key.ENTER);
            h.frame();
            assertTrue(menu.isOpen(), "Enter drops it");
            assertEquals("0.0", menu.marked(), "with its first line under the keyboard");
        }
    }

    @Test
    void anIconButtonDropsOneToo() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<Button.Pressed> pressed = new ArrayList<>();
            h.bus.subscribe(Button.PRESSED, pressed::add);
            ContextMenu menu = new ContextMenu(h.gui);
            IconButton b = new IconButton(h.gui, dev.vexelray.gui.icons.Icon.OPEN, "Open with")
                    .menu(programs(new ArrayList<>()));
            h.gui.root().children(b.node());
            h.frame();

            Rect r = h.gui.layoutSnapshot().node(h.retained(b.node()).id).rect();
            h.click(r.x() + r.w() / 2f, r.y() + r.h() / 2f);
            h.frame();
            assertTrue(menu.isOpen(), "a left click opens it");
            assertTrue(pressed.isEmpty(), "and publishes no PRESSED");
            h.frame().focus(b.node());
            h.tap(Key.SPACE);
            h.frame();
            assertFalse(menu.isOpen(), "Space puts it away again");
        }
    }

    @Test
    void noneMakesItAPlainButtonAgain() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<Button.Pressed> pressed = new ArrayList<>();
            h.bus.subscribe(Button.PRESSED, pressed::add);
            ContextMenu menu = new ContextMenu(h.gui);
            Button b = new Button(h.gui, "Open").menu(programs(new ArrayList<>())).menu(Menu.NONE);
            h.gui.root().children(b.node());
            h.frame();

            click(h, b);
            assertFalse(menu.isOpen());
            assertEquals(1, pressed.size(), "it presses as any button does");
        }
    }
}
