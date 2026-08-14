package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.model.NodeKind;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KeyboardFocusTest {

    private static final Topic<ClickEvent> CLICKS = Topic.of("test.clicks", ClickEvent.class);

    private static RetainedNode node(long id, float x, float y, float w, float h) {
        RetainedNode n = new RetainedNode(id, NodeKind.BOX);
        n.x = x;
        n.y = y;
        n.w = w;
        n.h = h;
        return n;
    }

    private static void keyDown(Atchung bus, Key k) {
        bus.publish(InputTopics.INPUT, new InputEvent.KeyPressed(k, 0));
    }

    private static void keyUp(Atchung bus, Key k) {
        bus.publish(InputTopics.INPUT, new InputEvent.KeyReleased(k, 0));
    }

    @Test
    void ctrlSFiresShortcutAndModifierReleaseStopsIt() {
        Atchung bus = Atchung.create();
        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
        AtomicInteger saves = new AtomicInteger();
        d.registerShortcut(Shortcut.of(Key.S, Modifier.CONTROL), saves::incrementAndGet);

        keyDown(bus, Key.LEFT_CONTROL);
        keyDown(bus, Key.S);
        d.dispatch(node(0, 0, 0, 10, 10));
        assertEquals(1, saves.get(), "Ctrl+S fires the shortcut");

        keyUp(bus, Key.LEFT_CONTROL);
        keyDown(bus, Key.S);
        d.dispatch(node(0, 0, 0, 10, 10));
        assertEquals(1, saves.get(), "plain S (no Control held) does not");
    }

    @Test
    void unclaimedKeyRoutesToFocusedNode() {
        Atchung bus = Atchung.create();
        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
        RetainedNode root = node(0, 0, 0, 100, 100);
        RetainedNode field = node(1, 0, 0, 100, 20);
        field.parent = root;
        root.children.add(field);

        List<Key> got = new ArrayList<>();
        d.onKey(1, e -> got.add(e.key()));
        d.focus(1);

        keyDown(bus, Key.A);
        d.dispatch(root);
        assertEquals(List.of(Key.A), got, "the focused node receives the key");
    }

    @Test
    void tabTraversesFocusInTreeOrder() {
        Atchung bus = Atchung.create();
        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
        RetainedNode root = node(0, 0, 0, 100, 100);
        RetainedNode a = node(1, 0, 0, 100, 20);
        RetainedNode b = node(2, 0, 20, 100, 20);
        a.parent = root;
        b.parent = root;
        root.children.add(a);
        root.children.add(b);
        d.setFocusable(1, true);
        d.setFocusable(2, true);

        keyDown(bus, Key.TAB);
        d.dispatch(root);
        assertEquals(1, d.focused(), "Tab focuses the first focusable");

        keyDown(bus, Key.TAB);
        d.dispatch(root);
        assertEquals(2, d.focused(), "Tab advances");

        keyDown(bus, Key.LEFT_SHIFT);
        keyDown(bus, Key.TAB);
        d.dispatch(root);
        assertEquals(1, d.focused(), "Shift+Tab goes back");
    }

    @Test
    void clickFocusesAndPublishesFocusEvents() {
        Atchung bus = Atchung.create();
        Topic<FocusEvent> focus = Topic.of("test.focus", FocusEvent.class);
        List<FocusEvent> events = new ArrayList<>();
        bus.subscribe(focus, events::add);

        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
        d.focusTopic(focus);
        RetainedNode root = node(0, 0, 0, 100, 100);
        RetainedNode a = node(1, 0, 0, 100, 40);
        RetainedNode b = node(2, 0, 40, 100, 40);
        a.parent = root;
        b.parent = root;
        root.children.add(a);
        root.children.add(b);
        d.setFocusable(1, true);
        d.setFocusable(2, true);

        bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, 10, 10, 0)); // in a
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, 10, 60, 0)); // in b
        d.dispatch(root);

        assertEquals(2, d.focused());
        assertEquals(List.of(new FocusEvent(1, true), new FocusEvent(1, false), new FocusEvent(2, true)), events);
    }

    @Test
    void tabDoesNothingWithNoFocusables() {
        Atchung bus = Atchung.create();
        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
        keyDown(bus, Key.TAB);
        d.dispatch(node(0, 0, 0, 10, 10));
        assertEquals(-1, d.focused());
        assertNull(null); // no crash / no focus
    }
}
