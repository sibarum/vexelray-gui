package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The divider moves the first pane by the pointer's displacement, inside the two minimums, and says where it ended. */
class SplitPaneTest {

    private record Mounted(SplitPane pane, Node first, Node second) { }

    private static Mounted mount(HeadlessGui h, SplitPane.Orientation o) {
        Node first = h.gui.box().width(Length.FILL).height(Length.FILL);
        Node second = h.gui.box().width(Length.FILL).height(Length.FILL);
        SplitPane pane = new SplitPane(h.gui, o, first, second)
                .size(Length.rem(10)).minFirst(Length.rem(4)).minSecond(Length.rem(4));
        h.gui.root().children(pane.node());
        h.frame().frame();
        return new Mounted(pane, first, second);
    }

    private static void drag(HeadlessGui h, float x, float y, float dx, float dy) {
        h.bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, (int) x, (int) y, 0));
        h.frame();
        h.bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved((int) (x + dx), (int) (y + dy), (int) dx, (int) dy, 0));
        h.frame();
        h.bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.LEFT, (int) (x + dx), (int) (y + dy), 0));
        h.frame().frame();
    }

    @Test
    void theFirstPaneStartsAtTheSizeItWasGiven() {
        try (HeadlessGui h = new HeadlessGui()) {
            Mounted m = mount(h, SplitPane.Orientation.SIDE_BY_SIDE);
            assertEquals(160f, m.first().layout().rect().w(), 1f, "ten rem at the harness's 16px em");
        }
    }

    @Test
    void draggingTheDividerMovesTheFirstPaneByTheDisplacement() {
        try (HeadlessGui h = new HeadlessGui()) {
            Mounted m = mount(h, SplitPane.Orientation.SIDE_BY_SIDE);
            Rect r = m.first().layout().rect();
            List<Float> ended = new ArrayList<>();
            m.pane().onResize(ended::add);

            drag(h, r.x() + r.w() + 2f, r.y() + 50f, 40f, 0f);

            assertEquals(200f, m.first().layout().rect().w(), 1.5f);
            assertEquals(1, ended.size(), "one report, at the end of the gesture");
            assertEquals(200f, ended.get(0), 1.5f);
        }
    }

    @Test
    void neitherPaneCanBeDraggedAwayEntirely() {
        try (HeadlessGui h = new HeadlessGui()) {
            Mounted m = mount(h, SplitPane.Orientation.SIDE_BY_SIDE);
            Rect r = m.first().layout().rect();
            drag(h, r.x() + r.w() + 2f, r.y() + 50f, -5000f, 0f);
            assertEquals(64f, m.first().layout().rect().w(), 1.5f, "the first pane keeps its minimum");

            Rect r2 = m.first().layout().rect();
            drag(h, r2.x() + r2.w() + 2f, r2.y() + 50f, 5000f, 0f);
            assertTrue(m.second().layout().rect().w() >= 63f,
                    "the second keeps its minimum: " + m.second().layout().rect().w());
        }
    }

    @Test
    void whenTheSecondPaneIsTheSizedOneDraggingTheDividerUpGrowsIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node first = h.gui.box().width(Length.FILL).height(Length.FILL);
            Node second = h.gui.box().width(Length.FILL).height(Length.FILL);
            SplitPane pane = new SplitPane(h.gui, SplitPane.Orientation.STACKED, first, second)
                    .sized(SplitPane.Pane.SECOND).size(Length.rem(10))
                    .minFirst(Length.rem(4)).minSecond(Length.rem(4));
            h.gui.root().children(pane.node());
            h.frame().frame();
            Rect r = second.layout().rect();
            assertEquals(160f, r.h(), 1f, "the second pane has the size, the first has the rest");

            // The divider is just above the second pane; pulling it up by 40 makes the second pane 40 taller.
            drag(h, r.x() + 100f, r.y() - 3f, 0f, -40f);

            assertEquals(200f, second.layout().rect().h(), 1.5f);
            assertEquals(200f, pane.sizeDp(), 1.5f);
        }
    }

    @Test
    void aStackedPaneMovesOnTheVerticalAxis() {
        try (HeadlessGui h = new HeadlessGui()) {
            Mounted m = mount(h, SplitPane.Orientation.STACKED);
            Rect r = m.first().layout().rect();
            drag(h, r.x() + 100f, r.y() + r.h() + 2f, 0f, 30f);
            assertEquals(190f, m.first().layout().rect().h(), 1.5f);
        }
    }
}
