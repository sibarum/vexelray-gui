package dev.vexelray.gui.core.input;

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
 * {@code onDoubleClick}: a double-click needs one handler, not a drag handler counting presses. The clicks still
 * arrive as clicks, carrying which click of the sequence each was.
 */
class DoubleClickTest {

    private static final Topic<ClickEvent> CLICKS = Topic.of("test.clicks", ClickEvent.class);
    private static final long MS = 1_000_000L;

    private final Atchung bus = Atchung.create();
    private final InputDispatcher dispatcher = new InputDispatcher(bus, CLICKS, Runnable::run);
    private final RetainedNode root = node(0, 0, 0, 300, 100);
    private final RetainedNode list = node(1, 0, 0, 300, 100);
    private final RetainedNode row = node(7, 10, 0, 200, 50);
    private final List<String> log = new ArrayList<>();

    DoubleClickTest() {
        list.parent = root;
        root.children.add(list);
        row.parent = list;
        list.children.add(row);
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

    @Test
    void theSecondQuickClickIsADoubleClickAndStillAClick() {
        dispatcher.onClick(7, e -> log.add("click " + e.clicks()));
        dispatcher.onDoubleClick(7, () -> log.add("double"));
        click(50, 20, 1000);
        click(50, 20, 1200);
        assertEquals(List.of("click 1", "click 2", "double"), log);
    }

    @Test
    void aThirdClickDoesNotDoubleClickAgain() {
        dispatcher.onDoubleClick(7, () -> log.add("double"));
        click(50, 20, 1000);
        click(50, 20, 1200);
        click(50, 20, 1400);
        assertEquals(List.of("double"), log);
    }

    @Test
    void twoSlowClicksAreNotADoubleClick() {
        dispatcher.onDoubleClick(7, () -> log.add("double"));
        click(50, 20, 1000);
        click(50, 20, 1700);
        assertEquals(List.of(), log);
    }

    @Test
    void doubleClicksBubbleAlongTheirOwnWalk() {
        // The row opens on a double-click; the list it sits in selects on a click. Neither shadows the other.
        dispatcher.onClick(1, () -> log.add("list click"));
        dispatcher.onDoubleClick(7, () -> log.add("row double"));
        click(50, 20, 1000);
        click(50, 20, 1200);
        assertEquals(List.of("list click", "list click", "row double"), log);
    }

    @Test
    void theClickTopicCarriesTheCount() {
        List<Integer> counts = new ArrayList<>();
        bus.subscribe(CLICKS, e -> counts.add(e.clicks()));
        click(50, 20, 1000);
        click(50, 20, 1200);
        click(50, 20, 1400);
        assertEquals(List.of(1, 2, 3), counts);
    }
}
