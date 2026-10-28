package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.drop.DropEffect;
import dev.vexelray.gui.core.drop.PayloadType;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dragging rows between two lists told about the same payload type: what is carried, and where it lands, announced on
 * {@code LANDED}. What landing means, a row that will not take it included, is the application's.
 */
class ListViewTransferTest {

    private static final PayloadType<List<String>> NAMES = PayloadType.of("names");

    private final List<String> drops = new ArrayList<>();

    /** Write down what lands on {@code list}, and where. */
    private void recording(HeadlessGui h, ListView<String> list) {
        h.bus.subscribe(ListView.LANDED, l -> {
            if (l.list().equals(list.address())) {
                drops.add(l.held() + " onto " + (l.onto() == null ? "the list" : l.onto()));
            }
        });
    }

    private static final List<String> LEFT = List.of("a.txt", "b.txt", "c.txt");
    private static final List<String> RIGHT = List.of("x.txt", "y/");

    private record Two(ListView<String> left, ListView<String> right) {
    }

    private Two two(HeadlessGui h) {
        ListView<String> left = new ListView<>(h.gui, 2f, (g, s) -> g.text(s));
        ListView<String> right = new ListView<>(h.gui, 2f, (g, s) -> g.text(s));
        for (var l : List.of(left, right)) {
            l.draggable(NAMES, held -> held.size() == 1 ? held.get(0) : held.size() + " items");
            l.node().width(Length.grow(1f));
        }
        left.droppable(NAMES, DropEffect.MOVE);
        right.droppable(NAMES, DropEffect.MOVE);
        recording(h, left);
        recording(h, right);
        left.items(LEFT);
        right.items(RIGHT);
        h.gui.root().children(h.gui.row().width(Length.FILL).height(Length.FILL)
                .children(left.node(), right.node()));
        h.frame();
        h.frame();
        return new Two(left, right);
    }

    private static Rect rect(ListView<String> list, String item) {
        Rect r = list.rowNode(item).layout().rect();
        assertNotNull(r, "row must be laid out");
        return r;
    }

    private static void press(HeadlessGui h, float x, float y) {
        h.bus.publish(h.gui.input(), new InputEvent.ButtonPressed(MouseButton.LEFT, (int) x, (int) y, 0));
    }

    private static void move(HeadlessGui h, float x, float y) {
        h.bus.publish(h.gui.input(), new InputEvent.PointerMoved((int) x, (int) y, 0, 40, 0));
    }

    private static void release(HeadlessGui h, float x, float y) {
        h.bus.publish(h.gui.input(), new InputEvent.ButtonReleased(MouseButton.LEFT, (int) x, (int) y, 0));
    }

    /** Picked up, let go (which latches), and put down with a click, over frames the user would have seen. */
    private static void drag(HeadlessGui h, float fx, float fy, float tx, float ty) {
        press(h, fx, fy);
        move(h, (fx + tx) / 2f, (fy + ty) / 2f);
        h.frame();
        move(h, tx, ty);
        h.frame();
        release(h, tx, ty);
        h.frame();
        press(h, tx, ty);
        release(h, tx, ty);
        h.frame();
        h.frame();          // and what landed is delivered
    }

    private static float midX(Rect r) {
        return r.x() + r.w() / 2f;
    }

    private static float midY(Rect r) {
        return r.y() + r.h() / 2f;
    }

    @Test
    @DisplayName("a row dropped on a row of the other list lands on it")
    void ontoARow() {
        try (HeadlessGui h = new HeadlessGui()) {
            Two t = two(h);
            Rect from = rect(t.left(), "b.txt");
            Rect to = rect(t.right(), "y/");
            drag(h, midX(from), midY(from), midX(to), midY(to));
            assertEquals(List.of("[b.txt] onto y/"), drops);
            assertEquals(Set.of("b.txt"), t.left().selection().selection(), "what was grabbed is the selection");
        }
    }

    @Test
    @DisplayName("the whole selection is carried, in order")
    void theSelectionIsCarried() {
        try (HeadlessGui h = new HeadlessGui()) {
            Two t = two(h);
            t.left().selection().set(Set.of("c.txt", "a.txt"));
            Rect from = rect(t.left(), "c.txt");
            Rect to = rect(t.right(), "x.txt");   // a file: what landing on it means is the application's
            drag(h, midX(from), midY(from), midX(to), midY(to));
            assertEquals(List.of("[a.txt, c.txt] onto x.txt"), drops);
        }
    }

    @Test
    @DisplayName("below the last row is the list itself, for a drop and never for a pick-up")
    void belowTheRows() {
        try (HeadlessGui h = new HeadlessGui()) {
            Two t = two(h);
            Rect from = rect(t.left(), "a.txt");
            Rect last = rect(t.right(), "y/");
            float below = last.y() + last.h() * 3f;
            drag(h, midX(from), midY(from), midX(last), below);
            assertEquals(List.of("[a.txt] onto the list"), drops);

            drops.clear();
            drag(h, midX(last), below, midX(from), midY(from));   // nothing there to pick up
            assertEquals(List.of(), drops);
        }
    }
}
