package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.drop.Payload;
import dev.vexelray.gui.core.drop.PayloadType;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.input.Menu;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Sender;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the dispatcher asks, a node declares (vexelray-framework's {@code docs/threading.md}, T7.5): a menu, a chord
 * and a drag's payload are set on the node ahead of time, the dispatcher reads them without calling anyone, and what
 * leaves is a message.
 */
class DeclarationsTest {

    record Open(String file) { }

    record Delete(String file) { }

    record Save() { }

    private static final Topic<Open> OPEN = Topic.edge("test.declarations.open", Open.class);
    private static final Topic<Delete> DELETE = Topic.edge("test.declarations.delete", Delete.class);
    private static final Topic<Save> SAVE = Topic.edge("test.declarations.save", Save.class);
    private static final PayloadType<String> FILE = PayloadType.of("file");

    /** A view's senders, as the framework would hand them to it: from an inbox of its own. */
    private static final class View {
        final Sender<Open> open;
        final Sender<Delete> delete;
        final Sender<Save> save;

        View(HeadlessGui h) {
            var inbox = h.bus.tick(() -> { }).inbox("view");
            open = inbox.sender(OPEN);
            delete = inbox.sender(DELETE);
            save = inbox.sender(SAVE);
        }
    }

    private static Node page(HeadlessGui h) {
        Node page = h.gui.box().width(Length.FILL).height(Length.FILL);
        h.gui.root().children(page);
        return page;
    }

    private static List<String> labels(ContextMenu menu) {
        return menu.items().stream().map(i -> i.separator() ? "-" : i.label()).toList();
    }

    @Test
    void aDeclaredMenuOpensAtTheClickAndAnItemSendsItsMessage() {
        try (HeadlessGui h = new HeadlessGui()) {
            View view = new View(h);
            List<Object> heard = new ArrayList<>();
            h.bus.subscribe(OPEN, heard::add);
            Node row = page(h);
            h.gui.menu(row, Menu.builder()
                    .item("Open", view.open, new Open("a.txt"))
                    .separator()
                    .item("Delete", false, view.delete, new Delete("a.txt"))
                    .build());
            h.frame();

            h.rightClick(200f, 150f);
            ContextMenu menu = (ContextMenu) h.gui.menus();

            assertTrue(menu.isOpen(), "the menu opened in the frame of the click, with nothing asked");
            assertEquals(List.of("Open", "-", "Delete"), labels(menu));
            assertFalse(menu.items().get(2).enabled(), "a declared item can be shown greyed");

            menu.items().get(0).action().run();
            assertEquals(List.of(new Open("a.txt")), heard, "choosing an item sends the message it names");
        }
    }

    @Test
    void declaringAgainReplacesTheMenuAndNoneTakesItAway() {
        try (HeadlessGui h = new HeadlessGui()) {
            View view = new View(h);
            Node row = page(h);
            h.gui.menu(row, Menu.builder().item("Open", view.open, new Open("a")).build());
            // The facts behind the menu changed: the view declares it again, as it would set a label.
            h.gui.menu(row, Menu.builder().item("Delete", view.delete, new Delete("a")).build());
            h.frame();

            h.rightClick(200f, 150f);
            ContextMenu menu = (ContextMenu) h.gui.menus();
            assertEquals(List.of("Delete"), labels(menu), "the last declaration is the menu");
            menu.hide();
            h.frame();

            h.gui.menu(row, Menu.NONE);
            h.rightClick(200f, 150f);
            assertFalse(menu.isOpen(), "a node whose menu was taken away opens nothing");
        }
    }

    @Test
    void aWidgetsOwnItemsComeFirstAndTheDeclaredOnesFollow() {
        try (HeadlessGui h = new HeadlessGui()) {
            View view = new View(h);
            Node row = page(h);
            h.gui.onContextMenu(row, m -> m.item("Copy", () -> { }));
            h.gui.menu(row, Menu.builder().item("Open", view.open, new Open("a")).build());
            h.frame();

            h.rightClick(200f, 150f);
            assertEquals(List.of("Copy", "-", "Open"), labels((ContextMenu) h.gui.menus()));
        }
    }

    @Test
    void theNearestNodeWithADeclaredMenuOwnsTheClick() {
        try (HeadlessGui h = new HeadlessGui()) {
            View view = new View(h);
            Node panel = page(h);
            Node inner = h.gui.box().width(Length.dp(100)).height(Length.dp(100));
            panel.children(inner);
            h.gui.menu(panel, Menu.builder().item("Panel", view.open, new Open("panel")).build());
            h.frame();

            h.rightClick(50f, 50f);   // on the inner box, which declares nothing: the panel's menu answers
            assertEquals(List.of("Panel"), labels((ContextMenu) h.gui.menus()));
        }
    }

    @Test
    void aShortcutDeclaredWithAMessageSendsIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            View view = new View(h);
            List<Save> heard = new ArrayList<>();
            h.bus.subscribe(SAVE, heard::add);
            page(h);
            h.gui.shortcut(Shortcut.of(Key.S, Modifier.CONTROL), view.save, new Save());
            h.frame();

            h.chord(Key.S, Key.LEFT_CONTROL);

            assertEquals(List.of(new Save()), heard);
        }
    }

    @Test
    void aClaimDeclaredWithAMessageSendsItWhileItApplies() {
        try (HeadlessGui h = new HeadlessGui()) {
            View view = new View(h);
            List<Delete> heard = new ArrayList<>();
            h.bus.subscribe(DELETE, heard::add);
            Node row = page(h);
            h.gui.claim(row, Shortcut.of(Key.DELETE), ClaimScope.FOCUSED, view.delete, new Delete("a"));
            h.frame();

            h.tap(Key.DELETE);
            assertTrue(heard.isEmpty(), "a focused claim does nothing while its node is not focused");

            h.focus(row).tap(Key.DELETE);
            assertEquals(List.of(new Delete("a")), heard);
        }
    }

    @Test
    void aDraggableNodeCarriesTheDeclaredPayloadAndNullTakesItAway() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node card = page(h);
            h.gui.draggable(card, Payload.of(FILE, "a.txt"));
            h.frame();

            drag(h, 20, 20, 20, 80);
            assertNotNull(h.gui.dragSession(), "a press that travelled became a drag");
            assertEquals("a.txt", h.gui.dragSession().payload().as(FILE).orElseThrow(),
                    "carrying what the node declared, with nothing asked when it began");
            h.bus.publish(InputTopics.INPUT, new InputEvent.KeyPressed(Key.ESCAPE, 0));
            h.bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.LEFT, 20, 80, 0));
            h.frame();
            assertNull(h.gui.dragSession());

            h.gui.draggable(card, null);
            drag(h, 20, 20, 20, 80);
            assertNull(h.gui.dragSession(), "a node that is no longer draggable starts no drag");
        }
    }

    /** Press at one point and travel to another, timestamps zero so the frame's own tick supplies the hold. */
    private static void drag(HeadlessGui h, int x0, int y0, int x1, int y1) {
        h.bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, x0, y0, 0));
        h.bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved(x1, y1, x1 - x0, y1 - y0, 0));
        h.frame();
    }
}
