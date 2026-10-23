package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.vexelray.gui.core.input.InteractionState.HOVER;
import static dev.vexelray.gui.core.input.InteractionState.NORMAL;
import static dev.vexelray.gui.core.input.InteractionState.PRESSED;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins which nodes get which {@link InteractionState}, and when their observers fire, across the cases the
 * refresh's shortcuts (one walk per chain, and skipping a refresh whose inputs are unchanged) must not disturb:
 * nesting, a press dragged off, a disabled ancestor, and a node removed or re-registered under the pointer.
 */
class StateRefreshTest {

    private static final Topic<ClickEvent> CLICKS = Topic.of("test.state.refresh.clicks", ClickEvent.class);

    private final Atchung bus = Atchung.create();
    private final InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
    private final RetainedNode root = node(0, 0, 0, 300, 300, null);
    /** Every observed node's deliveries, in registration order. */
    private final Map<Long, List<InteractionState>> seen = new LinkedHashMap<>();

    private static RetainedNode node(long id, float x, float y, float w, float h, RetainedNode parent) {
        RetainedNode n = new RetainedNode(id);
        n.x = x;
        n.y = y;
        n.w = w;
        n.h = h;
        if (parent != null) {
            n.parent = parent;
            parent.children.add(n);
        }
        return n;
    }

    private void observe(long... ids) {
        for (long id : ids) {
            List<InteractionState> list = seen.computeIfAbsent(id, k -> new ArrayList<>());
            d.onState(id, list::add);
        }
    }

    private List<InteractionState> of(long id) {
        return seen.get(id);
    }

    private void move(int x, int y) {
        bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved(x, y, 0, 0, 0));
        d.dispatch(root);
    }

    private void press(int x, int y) {
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, x, y, 0));
        d.dispatch(root);
    }

    private void release(int x, int y) {
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.LEFT, x, y, 0));
        d.dispatch(root);
    }

    @Test
    void nestedHoverLightsTheWholeChainAndOnlyItsChanges() {
        RetainedNode outer = node(1, 0, 0, 200, 200, root);
        RetainedNode inner = node(2, 0, 0, 100, 100, outer);
        node(3, 0, 0, 50, 50, inner);
        node(4, 150, 150, 40, 40, outer);
        observe(1, 2, 3, 4);

        move(10, 10);     // leaf 3: 1, 2, 3 all hover
        move(11, 12);     // within the same leaf: nothing new
        move(70, 70);     // up to 2: 3 lets go, 1 and 2 stay lit without a second delivery
        move(160, 160);   // across to 4: 2 lets go, 1 stays
        move(250, 250);   // out to the root: 1 and 4 let go

        assertEquals(List.of(HOVER, NORMAL), of(1));
        assertEquals(List.of(HOVER, NORMAL), of(2));
        assertEquals(List.of(HOVER, NORMAL), of(3));
        assertEquals(List.of(HOVER, NORMAL), of(4));
    }

    @Test
    void aPressDraggedOffLeavesTheContainerPressedAndTheSiblingOnlyHovered() {
        RetainedNode box = node(1, 0, 0, 200, 100, root);
        node(2, 0, 0, 100, 100, box);
        node(3, 100, 0, 100, 100, box);
        observe(1, 2, 3);

        press(10, 10);    // on 2: 1 and 2 pressed
        move(150, 10);    // onto sibling 3, still held: 2 lets go; 1 stays pressed; 3 is merely hovered
        move(160, 20);    // within 3: nothing new
        move(250, 250);   // out of the box: 1 and 3 let go
        move(10, 10);     // back onto 2, still held: pressed again, and the box with it
        release(10, 10);  // up over 2: hovered

        assertEquals(List.of(PRESSED, NORMAL, PRESSED, HOVER), of(1));
        assertEquals(List.of(PRESSED, NORMAL, PRESSED, HOVER), of(2));
        assertEquals(List.of(HOVER, NORMAL), of(3));
    }

    @Test
    void aDisabledAncestorHandsHoverToTheEnabledPartAndChangesOnlyWithTheNextMove() {
        RetainedNode card = node(1, 0, 0, 200, 200, root);
        RetainedNode row = node(2, 0, 0, 200, 50, card);
        node(3, 0, 0, 50, 50, row);
        observe(1, 2, 3);
        row.set(PropKey.DISABLED, true);

        move(10, 10);     // over the button inside the disabled row: only the card lights
        press(10, 10);    // a press on a disabled control: no pressed state anywhere
        release(10, 10);
        assertEquals(List.of(HOVER), of(1));
        assertEquals(List.of(), of(2));
        assertEquals(List.of(), of(3));

        row.set(PropKey.DISABLED, null);
        assertEquals(List.of(), of(3), "enabling reports nothing until the pointer moves");
        move(11, 11);
        assertEquals(List.of(HOVER), of(2));
        assertEquals(List.of(HOVER), of(3));

        row.set(PropKey.DISABLED, true);
        move(12, 12);
        assertEquals(List.of(HOVER, NORMAL), of(2));
        assertEquals(List.of(HOVER, NORMAL), of(3));
        assertEquals(List.of(HOVER), of(1), "the card stays lit throughout");
    }

    @Test
    void aNodeRemovedWhileHoveredIsForgottenAndItsReplacementReportsAfresh() {
        RetainedNode parent = node(1, 0, 0, 100, 100, root);
        RetainedNode leaf = node(2, 0, 0, 50, 50, parent);
        observe(1, 2);
        move(10, 10);

        d.clearHandlers(2);
        parent.children.remove(leaf);
        leaf.parent = null;
        move(11, 11);     // now over the parent alone: it stays lit; the removed leaf hears nothing
        assertEquals(List.of(HOVER), of(1));
        assertEquals(List.of(HOVER), of(2));

        // The reconciler puts a node with the same id back under the pointer and registers it again.
        RetainedNode again = node(2, 0, 0, 50, 50, parent);
        List<InteractionState> fresh = new ArrayList<>();
        d.onState(2, fresh::add);
        move(12, 12);
        assertEquals(List.of(HOVER), fresh, "the replacement starts from NORMAL and is told it is hovered");
        assertEquals(List.of(HOVER), of(1));
        assertEquals(2, again.id);
    }

    @Test
    void anObserverRegisteredUnderAStillPointerIsToldOnTheNextMoveEvenWithinTheSameLeaf() {
        RetainedNode parent = node(1, 0, 0, 100, 100, root);
        node(2, 0, 0, 50, 50, parent);
        observe(2);
        move(10, 10);

        observe(1);
        move(11, 11);     // the chain under the pointer has not changed, the set of observers has
        assertEquals(List.of(HOVER), of(1));
        assertEquals(List.of(HOVER), of(2));
    }

    @Test
    void handlersClearedAndReRegisteredInPlaceReportAfresh() {
        RetainedNode parent = node(1, 0, 0, 100, 100, root);
        node(2, 0, 0, 50, 50, parent);
        observe(2);
        move(10, 10);

        // Same node, same place, same chain under the pointer: only its registration went round.
        d.clearHandlers(2);
        List<InteractionState> fresh = new ArrayList<>();
        d.onState(2, fresh::add);
        move(10, 10);
        assertEquals(List.of(HOVER), fresh);
        assertEquals(List.of(HOVER), of(2));
    }
}
