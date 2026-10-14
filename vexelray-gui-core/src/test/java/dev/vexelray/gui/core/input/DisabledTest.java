package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.drop.Drop;
import dev.vexelray.gui.core.drop.DropTarget;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code PropKey.DISABLED}: a disabled subtree is closed to the user — opaque to the press, out of the Tab order,
 * deaf to claims, and gives up focus — while hover still reaches the enabled ancestors around it.
 *
 * <p>The tree is a row (1) holding a button (2), under the root (0); the button sits in the row's left half.
 */
class DisabledTest {

    private static final Topic<ClickEvent> CLICKS = Topic.of("test.disabled.clicks", ClickEvent.class);

    private final Atchung bus = Atchung.create();
    private final InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
    private final Map<Long, RetainedNode> index = new HashMap<>();
    private final RetainedNode root = node(0, 0, 0, 200, 200, null);
    private final RetainedNode row = node(1, 0, 0, 200, 40, root);
    private final RetainedNode button = node(2, 0, 0, 100, 40, row);

    DisabledTest() {
        d.nodeLookup(index::get);
    }

    private RetainedNode node(long id, float x, float y, float w, float h, RetainedNode parent) {
        RetainedNode n = new RetainedNode(id);
        n.x = x;
        n.y = y;
        n.w = w;
        n.h = h;
        if (parent != null) {
            n.parent = parent;
            parent.children.add(n);
        }
        index.put(id, n);
        return n;
    }

    private void click(int x, int y) {
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, x, y, 0));
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.LEFT, x, y, 0));
        d.dispatch(root);
    }

    private void key(Key... keys) {
        for (Key k : keys) {
            bus.publish(InputTopics.INPUT, new InputEvent.KeyPressed(k, 0));
        }
        d.dispatch(root);
        for (Key k : keys) {
            bus.publish(InputTopics.INPUT, new InputEvent.KeyReleased(k, 0));
        }
        d.dispatch(root);
    }

    @Test
    void aPressOnADisabledControlDoesNothingAtAll() {
        AtomicInteger rowClicks = new AtomicInteger();
        AtomicInteger buttonClicks = new AtomicInteger();
        d.onClick(1, rowClicks::incrementAndGet);
        d.onClick(2, buttonClicks::incrementAndGet);
        d.setFocusable(1, true);
        d.setFocusable(2, true);
        d.focus(1);
        button.set(PropKey.DISABLED, true);

        click(50, 20);
        assertEquals(0, buttonClicks.get(), "the disabled button is not clicked");
        assertEquals(0, rowClicks.get(), "and the click does not fall through to the row around it");
        assertEquals(1, d.focused(), "nor does it move focus, not even away from what held it");

        click(150, 20);
        assertEquals(1, rowClicks.get(), "the row beside it still answers");

        button.set(PropKey.DISABLED, null);
        click(50, 20);
        assertEquals(1, buttonClicks.get(), "enabled again, it is a button again");
    }

    @Test
    void hoverStaysOnTheRowAcrossADisabledControl() {
        List<InteractionState> rowStates = new ArrayList<>();
        List<InteractionState> buttonStates = new ArrayList<>();
        d.onState(1, rowStates::add);
        d.onState(2, buttonStates::add);
        button.set(PropKey.DISABLED, true);

        bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved(150, 20, 0, 0, 0));
        bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved(50, 20, 0, 0, 0));
        d.dispatch(root);
        assertEquals(List.of(InteractionState.HOVER), rowStates,
                "the row lights once and stays lit as the pointer crosses the disabled button");
        assertTrue(buttonStates.isEmpty(), "the disabled button never reports a hover");
    }

    @Test
    void tabSkipsADisabledSubtree() {
        RetainedNode other = node(3, 0, 40, 200, 40, root);
        d.setFocusable(2, true);
        d.setFocusable(3, true);
        row.set(PropKey.DISABLED, true);

        key(Key.TAB);
        assertEquals(3, d.focused(), "the button inside the disabled row is not a stop");
        key(Key.TAB);
        assertEquals(3, d.focused(), "and the order wraps without it");
        assertSame(other, index.get(3L));
    }

    @Test
    void disablingTakesFocusAwayBeforeTheNextKeyArrives() {
        List<Key> got = new ArrayList<>();
        d.onKey(2, e -> got.add(e.key()));
        d.focus(2);
        row.set(PropKey.DISABLED, true);   // an ancestor: the button is just as closed

        bus.publish(InputTopics.INPUT, new InputEvent.KeyPressed(Key.A, 0));
        d.dispatch(root);
        assertEquals(-1, d.focused(), "focus does not stay on a closed control");
        assertTrue(got.isEmpty(), "and a key in the same frame as the disable goes nowhere");
    }

    @Test
    void aDisabledNodesClaimsDoNotApply() {
        AtomicInteger saves = new AtomicInteger();
        d.claim(2, Shortcut.of(Key.S, Modifier.CONTROL), ClaimScope.VISIBLE, saves::incrementAndGet);
        d.claim(1, Shortcut.of(Key.P, Modifier.CONTROL), ClaimScope.GLOBAL, saves::incrementAndGet);
        row.set(PropKey.DISABLED, true);

        key(Key.LEFT_CONTROL, Key.S);
        key(Key.LEFT_CONTROL, Key.P);
        assertEquals(0, saves.get(), "neither a VISIBLE nor a GLOBAL claim runs from inside a disabled subtree");

        row.set(PropKey.DISABLED, null);
        key(Key.LEFT_CONTROL, Key.S);
        assertEquals(1, saves.get());
    }

    @Test
    void aDisabledTargetAcceptsNothingButItsContainerStillAnswers() {
        DropTarget rowTarget = (payload, x, y) -> Drop.NONE;
        d.onDrop(1, rowTarget);
        d.onDrop(2, DropTarget.NONE);
        d.dispatch(root);
        assertEquals(List.of(DropTarget.NONE, rowTarget), d.dropTargetsAt(50, 20));

        button.set(PropKey.DISABLED, true);
        assertEquals(List.of(rowTarget), d.dropTargetsAt(50, 20), "the disabled button is skipped, the row is not");
    }

    @Test
    void theEnabledPartIsTheNearestAncestorOutsideTheDisabledSubtree() {
        RetainedNode label = node(4, 0, 0, 50, 40, button);
        assertSame(label, RetainedNode.enabledPart(label), "nothing disabled: the hit itself");
        button.set(PropKey.DISABLED, true);
        assertSame(row, RetainedNode.enabledPart(label));
        row.set(PropKey.DISABLED, true);
        assertSame(root, RetainedNode.enabledPart(label), "the outermost disabled node decides");
        assertTrue(label.disabledInTree());
        assertFalse(root.disabledInTree());
        assertNull(RetainedNode.enabledPart(null));
        assertFalse(d.disabled(99), "a node the tree does not hold is not disabled");
        assertTrue(d.disabled(4));
    }
}
