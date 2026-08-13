package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.model.NodeKind;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves hover/pressed state transitions headlessly: synthetic pointer + button edges on the input topic drive the
 * dispatcher, and we observe the {@link InteractionState} callbacks. Handlers run synchronously (direct executor)
 * for deterministic assertions.
 */
class InteractionStateTest {

    private static final Topic<ClickEvent> CLICKS = Topic.of("test.clicks", ClickEvent.class);

    private static RetainedNode node(long id, float x, float y, float w, float h) {
        RetainedNode n = new RetainedNode(id, NodeKind.BOX);
        n.x = x;
        n.y = y;
        n.w = w;
        n.h = h;
        return n;
    }

    private static void move(Atchung bus, int x, int y) {
        bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved(x, y, 0, 0, 0));
    }

    private static void press(Atchung bus, int x, int y) {
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, x, y, 0));
    }

    private static void release(Atchung bus, int x, int y) {
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.LEFT, x, y, 0));
    }

    @Test
    void hoverPressReleaseLeaveSequence() {
        Atchung bus = Atchung.create();
        InputDispatcher dispatcher = new InputDispatcher(bus, CLICKS, Runnable::run);

        RetainedNode root = node(0, 0, 0, 200, 200);
        RetainedNode btn = node(7, 10, 10, 80, 30);   // covers x[10,90) y[10,40)
        btn.parent = root;
        root.children.add(btn);

        List<InteractionState> states = new ArrayList<>();
        dispatcher.onState(7, states::add);

        move(bus, 20, 20);      // enter -> HOVER
        press(bus, 20, 20);     // hold  -> PRESSED
        release(bus, 20, 20);   // let go, still over -> HOVER
        move(bus, 150, 150);    // leave -> NORMAL
        dispatcher.dispatch(root);

        assertEquals(List.of(InteractionState.HOVER, InteractionState.PRESSED,
                InteractionState.HOVER, InteractionState.NORMAL), states);
    }

    @Test
    void draggingOffAPressedNodeReleasesThePressedLook() {
        Atchung bus = Atchung.create();
        InputDispatcher dispatcher = new InputDispatcher(bus, CLICKS, Runnable::run);

        RetainedNode root = node(0, 0, 0, 200, 200);
        RetainedNode btn = node(7, 10, 10, 80, 30);
        btn.parent = root;
        root.children.add(btn);

        List<InteractionState> states = new ArrayList<>();
        dispatcher.onState(7, states::add);

        press(bus, 20, 20);     // HOVER? no prior hover -> PRESSED (press implies over)
        move(bus, 150, 150);    // drag off while held -> NORMAL (not over)
        move(bus, 20, 20);      // drag back while held -> PRESSED again
        dispatcher.dispatch(root);

        assertEquals(List.of(InteractionState.PRESSED, InteractionState.NORMAL, InteractionState.PRESSED), states);
    }

    @Test
    void hoveringAChildCountsAsHoveringTheContainer() {
        Atchung bus = Atchung.create();
        InputDispatcher dispatcher = new InputDispatcher(bus, CLICKS, Runnable::run);

        RetainedNode root = node(0, 0, 0, 200, 200);
        RetainedNode container = node(5, 0, 0, 100, 100);
        RetainedNode label = node(6, 10, 10, 40, 20); // child painted on top
        container.parent = root;
        root.children.add(container);
        label.parent = container;
        container.children.add(label);

        List<InteractionState> states = new ArrayList<>();
        dispatcher.onState(5, states::add); // handler on the container

        move(bus, 20, 15);      // over the label -> container is HOVER
        dispatcher.dispatch(root);

        assertEquals(List.of(InteractionState.HOVER), states);
    }
}
