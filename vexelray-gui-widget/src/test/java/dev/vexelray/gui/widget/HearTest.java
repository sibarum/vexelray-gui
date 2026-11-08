package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.NodeEvent;
import dev.vexelray.gui.core.input.ClickEvent;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.KeyEvent;
import dev.vexelray.gui.core.input.PointerEvent;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A view hears a node's events as messages ({@code Gui.hear}): published by the dispatcher on the tree's own topics,
 * delivered in the widget tick, in the frame the input arrived, and folded per node where the kind is state.
 */
class HearTest {

    private static Node page(HeadlessGui h) {
        Node page = h.gui.box().width(Length.FILL).height(Length.FILL);
        h.gui.root().children(page);
        return page;
    }

    @Test
    void aClickIsHeardAndWhatTheViewWritesIsDrawnInTheSameFrame() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node page = page(h);
            Node label = h.gui.text("before");
            page.children(label);
            List<ClickEvent> heard = new ArrayList<>();
            h.gui.hear(page, NodeEvent.CLICK, click -> {
                heard.add(click);
                label.text("after");
            });
            h.frame();

            h.click(300f, 300f);   // the release is dispatched in the last frame of this

            assertEquals(1, heard.size());
            assertEquals("after", h.retained(label).textString(),
                    "the view heard the click in the widget tick, before the drain, so its write is in that frame");
        }
    }

    @Test
    void aClickBubblesToTheNearestNodeThatHearsClicks() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node page = page(h);
            Node inner = h.gui.box().width(Length.dp(100)).height(Length.dp(100));
            page.children(inner);
            List<String> heard = new ArrayList<>();
            h.gui.hear(page, NodeEvent.CLICK, click -> heard.add("page"));
            h.frame();

            h.click(20f, 20f);   // on the inner box, which hears nothing

            assertEquals(List.of("page"), heard);
        }
    }

    @Test
    void hoverIsStateSoOnlyWhereThePointerEndedInAFrameIsHeard() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node page = page(h);
            Node row = h.gui.box().width(Length.dp(100)).height(Length.dp(100));
            page.children(row);
            List<InteractionState> heard = new ArrayList<>();
            h.gui.hear(row, NodeEvent.STATE, heard::add);
            h.frame();
            heard.clear();

            h.hover(20f, 20f);
            assertEquals(List.of(InteractionState.HOVER), heard, "into the row");
            heard.clear();

            // Out, in and out again within one frame: three transitions the dispatcher saw, one that was ever drawn.
            h.bus.publish(h.gui.input(), new InputEvent.PointerMoved(300, 300, 0, 0, 0));
            h.bus.publish(h.gui.input(), new InputEvent.PointerMoved(20, 20, 0, 0, 0));
            h.bus.publish(h.gui.input(), new InputEvent.PointerMoved(300, 300, 0, 0, 0));
            h.frame();
            assertEquals(List.of(InteractionState.NORMAL), heard,
                    "the dispatcher reported three crossings; the row was told where the pointer ended");
        }
    }

    @Test
    void whereThePointerIsOverANodeIsStateSoTheNewestInAFrameIsHeardAndThenItsLeave() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node page = page(h);
            Node chart = h.gui.box().width(Length.dp(100)).height(Length.dp(100));
            page.children(chart);
            List<PointerEvent> heard = new ArrayList<>();
            h.gui.hear(chart, NodeEvent.POINTER, heard::add);
            h.frame();

            // Three moves over the chart in one frame: the view draws once, so it is told where the pointer ended.
            h.bus.publish(h.gui.input(), new InputEvent.PointerMoved(10, 10, 0, 0, 0));
            h.bus.publish(h.gui.input(), new InputEvent.PointerMoved(20, 30, 0, 0, 0));
            h.bus.publish(h.gui.input(), new InputEvent.PointerMoved(40, 50, 0, 0, 0));
            h.frame();
            assertEquals(1, heard.size(), heard.toString());
            assertTrue(heard.get(0).over());
            assertEquals(40f, heard.get(0).x());
            assertEquals(50f, heard.get(0).y());
            heard.clear();

            h.hover(300f, 300f);
            assertEquals(1, heard.size(), heard.toString());
            assertFalse(heard.get(0).over(), "off the chart: its leave");
        }
    }

    @Test
    void aWidgetsNodeIsTheWidgetsAndHearingItIsRefused() {
        try (HeadlessGui h = new HeadlessGui()) {
            Button button = new Button(h.gui, "Go");
            h.gui.root().children(button.node());

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> h.gui.hear(button.node(), NodeEvent.CLICK, click -> { }));
            assertTrue(e.getMessage().contains("click") && e.getMessage().contains("widget"), e.getMessage());
        }
    }

    @Test
    void hearingKeysMakesANodeFocusableAndItHearsThemWhileFocused() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node page = page(h);
            List<Key> heard = new ArrayList<>();
            h.gui.hear(page, NodeEvent.KEY, (KeyEvent key) -> heard.add(key.key()));
            h.frame();

            h.focus(page).tap(Key.A);

            assertEquals(List.of(Key.A), heard);
        }
    }

    @Test
    void aSizeIsHeardTheFirstTimeTheNodeHasOne() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node page = page(h);
            List<NodeLayout> heard = new ArrayList<>();
            h.gui.hear(page, NodeEvent.RESIZE, heard::add);

            h.frame().frame();   // laid out in the first, heard in the widget tick of the second

            assertEquals(1, heard.size(), "one size, heard once");
            assertTrue(heard.getFirst().rect().w() > 0f, "and it is the node's real one: " + heard.getFirst());
        }
    }

    @Test
    void aNodesHearingIsReleasedWithItsRegistrations() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node page = page(h);
            List<String> heard = new ArrayList<>();
            h.gui.hear(page, NodeEvent.CLICK, click -> heard.add("first"));
            h.gui.releaseNode(page);

            // Released, so hearing it again is a first hearing, not a second mailbox on one address.
            h.gui.hear(page, NodeEvent.CLICK, click -> heard.add("second"));
            h.frame();
            h.click(300f, 300f);

            assertEquals(List.of("second"), heard);
        }
    }
}
