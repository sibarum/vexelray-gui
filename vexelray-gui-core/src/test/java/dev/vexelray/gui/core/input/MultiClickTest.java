package dev.vexelray.gui.core.input;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DragEvent#clicks()}: which press of a sequence a press is, counted from the capture times on the press
 * edges — and the modifiers held, carried on the event rather than looked up beside it.
 */
class MultiClickTest {

    private static final Topic<ClickEvent> CLICKS = Topic.of("test.clicks", ClickEvent.class);
    private static final long MS = 1_000_000L;

    private final Atchung bus = Atchung.create();
    private final InputDispatcher dispatcher = new InputDispatcher(bus, CLICKS, Runnable::run);
    private final RetainedNode root = node(0, 0, 0, 300, 100);
    private final List<DragEvent> starts = new ArrayList<>();

    MultiClickTest() {
        RetainedNode field = node(7, 10, 0, 200, 50);
        field.parent = root;
        root.children.add(field);
        dispatcher.onDrag(7, e -> {
            if (e.phase() == DragEvent.Phase.START) {
                starts.add(e);
            }
        });
    }

    private static RetainedNode node(long id, float x, float y, float w, float h) {
        RetainedNode n = new RetainedNode(id);
        n.x = x;
        n.y = y;
        n.w = w;
        n.h = h;
        return n;
    }

    private void click(int x, int y, long atMs) {
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, x, y, atMs * MS));
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.LEFT, x, y, atMs * MS));
        dispatcher.dispatch(root);
    }

    private List<Integer> counts() {
        return starts.stream().map(DragEvent::clicks).toList();
    }

    @Test
    void quickPressesInOnePlaceCountUp() {
        click(50, 20, 1000);
        click(50, 20, 1200);
        click(51, 21, 1400);   // a hand is not perfectly still
        assertEquals(List.of(1, 2, 3), counts());
    }

    @Test
    void aSlowPressStartsAgain() {
        click(50, 20, 1000);
        click(50, 20, 1700);   // past the half second
        assertEquals(List.of(1, 1), counts());
    }

    @Test
    void aPressSomewhereElseStartsAgain() {
        click(50, 20, 1000);
        click(90, 20, 1100);
        assertEquals(List.of(1, 1), counts());
    }

    @Test
    void pressesWithNoTimeToCompareAreSeparateClicks() {
        // What every synthetic press carries: stamped zero, so there is no telling how far apart they were, and
        // two clicks a test meant as two clicks must not become a double-click.
        click(50, 20, 0);
        click(50, 20, 0);
        assertEquals(List.of(1, 1), counts());
    }

    @Test
    void theCountAndModifiersRideEveryEventOfTheGesture() {
        List<DragEvent> all = new ArrayList<>();
        dispatcher.onDrag(7, all::add);
        click(50, 20, 1000);
        bus.publish(InputTopics.INPUT, new InputEvent.KeyPressed(Key.LEFT_SHIFT, 1050 * MS));
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, 50, 20, 1100 * MS));
        bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved(120, 20, 70, 0, 1150 * MS));
        dispatcher.dispatch(root);

        DragEvent move = all.get(all.size() - 1);
        assertEquals(DragEvent.Phase.MOVE, move.phase());
        assertEquals(2, move.clicks(), "a double-click held and dragged is still a double-click");
        assertTrue(move.has(Modifier.SHIFT), "and carries what was held");
    }
}
