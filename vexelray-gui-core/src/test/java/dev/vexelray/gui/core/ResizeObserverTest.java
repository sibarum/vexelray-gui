package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code onResize}: the seam for "my geometry is real now".
 *
 * <p>The first test is the bug this exists for, written down. An application that wants to draw into a node's
 * box has nowhere to be told that the box has arrived: {@code onCreated} fires before anything is measured, and
 * a node read before the first layout answers {@link NodeLayout#ABSENT} — silently, so the code that read it
 * does nothing, reports nothing, and is never asked again. That shipped as a plot window that opened blank and
 * stayed blank until an unrelated control provoked a redraw.
 */
class ResizeObserverTest {

    /** A trivial measurer — these trees are fixed-size boxes, so no text metrics are needed. */
    private static float noText(dev.vexelray.gui.core.model.RetainedNode n,
                                dev.vexelray.gui.core.layout.LayoutEnums.Axis axis, float px) {
        return 0f;
    }

    /** Handlers run inline, so a test asserts about them without waiting for anything. */
    private static final Executor SAME_THREAD = Runnable::run;

    @Test
    void theFirstBoxIsAChangeAndIsAnnounced() {
        try (Gui gui = new Gui(Atchung.create(), SAME_THREAD)) {
            Node canvas = gui.box().width(Length.FILL).height(Length.FILL);
            gui.root().children(canvas);
            List<NodeLayout> seen = new ArrayList<>();
            gui.onResize(canvas, seen::add);

            assertEquals(List.of(), seen, "nothing has been laid out, so there is nothing to announce");

            gui.frame(400f, 300f, ResizeObserverTest::noText);

            assertEquals(1, seen.size(), "the box arriving is the change that matters most");
            assertEquals(400f, seen.get(0).rect().w(), 0.5f);
            assertEquals(300f, seen.get(0).rect().h(), 0.5f);
        }
    }

    @Test
    void aWindowResizeIsAnnouncedToo() {
        try (Gui gui = new Gui(Atchung.create(), SAME_THREAD)) {
            Node canvas = gui.box().width(Length.FILL).height(Length.FILL);
            gui.root().children(canvas);
            List<NodeLayout> seen = new ArrayList<>();
            gui.onResize(canvas, seen::add);

            gui.frame(400f, 300f, ResizeObserverTest::noText);
            gui.frame(500f, 300f, ResizeObserverTest::noText);

            assertEquals(2, seen.size());
            assertEquals(500f, seen.get(1).rect().w(), 0.5f);
        }
    }

    /**
     * The property that makes this safe to react to by mutating: a handler that repaints causes a layout, and a
     * layout that changes nothing must not call the handler again, or every repaint would schedule the next one.
     */
    @Test
    void aFrameThatChangedNothingIsSilent() {
        try (Gui gui = new Gui(Atchung.create(), SAME_THREAD)) {
            Node canvas = gui.box().width(Length.FILL).height(Length.FILL);
            gui.root().children(canvas);
            List<NodeLayout> seen = new ArrayList<>();
            gui.onResize(canvas, seen::add);

            gui.frame(400f, 300f, ResizeObserverTest::noText);
            gui.frame(400f, 300f, ResizeObserverTest::noText);
            gui.frame(400f, 300f, ResizeObserverTest::noText);

            assertEquals(1, seen.size(), "the box never moved after the first frame");
        }
    }

    /** Zoom is not a resize of the window, but it is a resize of everything sized in em. */
    @Test
    void aZoomChangeReachesANodeSizedInEm() {
        try (Gui gui = new Gui(Atchung.create(), SAME_THREAD)) {
            Node card = gui.box().width(Length.rem(4)).height(Length.rem(2));
            gui.root().children(card);
            List<NodeLayout> seen = new ArrayList<>();
            gui.onResize(card, seen::add);

            gui.frame(400f, 300f, ResizeObserverTest::noText);
            float first = seen.get(0).rect().w();
            gui.zoom(2f);
            gui.frame(400f, 300f, ResizeObserverTest::noText);

            assertEquals(2, seen.size(), "an em is resolved against zoom, so zooming resizes the box");
            assertEquals(2 * first, seen.get(1).rect().w(), 0.5f);
        }
    }

    /**
     * A scroll moves a container's contents, not the container. Firing here would wake a repaint on every wheel
     * notch over a list, which is the opposite of what an observer is for.
     */
    @Test
    void scrollingAContainerIsNotAResize() {
        try (Gui gui = new Gui(Atchung.create(), SAME_THREAD)) {
            Node list = gui.column().width(Length.FILL).height(Length.rem(4)).scroll(false, true);
            list.children(gui.box().width(Length.FILL).height(Length.rem(20)));
            gui.root().children(list);
            List<NodeLayout> seen = new ArrayList<>();
            gui.onResize(list, seen::add);

            gui.frame(400f, 300f, ResizeObserverTest::noText);
            assertEquals(1, seen.size());
            assertTrue(seen.get(0).overflowY(), "the fixture only means something if it actually overflows");

            list.scrollToEdge();
            gui.frame(400f, 300f, ResizeObserverTest::noText);

            assertEquals(1, seen.size(), "the viewport did not move; its contents did");
        }
    }

    /** Two lanes, the same split the input seams already have: worker by default, GUI thread on demand. */
    @Test
    void theOrderedVariantRunsInsideTheFrameAndTheOrdinaryOneDoesNot() {
        List<String> lanes = new ArrayList<>();
        Executor recording = command -> {
            lanes.add("worker");
            command.run();
        };
        try (Gui gui = new Gui(Atchung.create(), recording)) {
            Node a = gui.box().width(Length.FILL).height(Length.rem(1));
            Node b = gui.box().width(Length.FILL).height(Length.rem(1));
            gui.root().children(a, b);
            gui.onResize(a, layout -> lanes.add("onResize"));
            gui.onResizeUi(b, layout -> lanes.add("onResizeUi"));

            gui.frame(400f, 300f, ResizeObserverTest::noText);

            assertTrue(lanes.contains("onResizeUi"), "the ordered handler ran");
            assertTrue(lanes.contains("onResize"), "and so did the worker one");
            assertTrue(lanes.indexOf("worker") < lanes.indexOf("onResize"),
                    "the ordinary lane goes through the handler executor, the ordered one does not: " + lanes);
            assertEquals(1, lanes.stream().filter("worker"::equals).count(),
                    "and only the ordinary one does: " + lanes);
        }
    }

    /** An observer is a registration like any other, and goes when the node's registrations go. */
    @Test
    void releasingANodeDropsItsObserver() {
        try (Gui gui = new Gui(Atchung.create(), SAME_THREAD)) {
            Node canvas = gui.box().width(Length.FILL).height(Length.FILL);
            gui.root().children(canvas);
            List<NodeLayout> seen = new ArrayList<>();
            gui.onResize(canvas, seen::add);

            gui.frame(400f, 300f, ResizeObserverTest::noText);
            assertEquals(1, seen.size());

            gui.releaseNode(canvas);
            gui.frame(500f, 300f, ResizeObserverTest::noText);

            assertEquals(1, seen.size(), "released means released");
        }
    }

    /** Removing a node from the tree does it too, through the reconciler's own removal seam. */
    @Test
    void removingANodeDropsItsObserver() {
        try (Gui gui = new Gui(Atchung.create(), SAME_THREAD)) {
            Node canvas = gui.box().width(Length.FILL).height(Length.FILL);
            gui.root().children(canvas);
            List<NodeLayout> seen = new ArrayList<>();
            gui.onResize(canvas, seen::add);

            gui.frame(400f, 300f, ResizeObserverTest::noText);
            canvas.remove();
            gui.frame(500f, 300f, ResizeObserverTest::noText);

            assertEquals(1, seen.size());
        }
    }

    /**
     * A node laid out to nothing is reported as nothing rather than withheld. Withholding it would be the same
     * silence this seam exists to end: a consumer that cannot draw at zero can see that it cannot, and knows it
     * will be told again.
     */
    @Test
    void aBoxOfZeroIsStillAnAnswer() {
        try (Gui gui = new Gui(Atchung.create(), SAME_THREAD)) {
            Node hidden = gui.box().width(Length.FILL).height(Length.FILL).visible(false);
            gui.root().children(hidden);
            List<NodeLayout> seen = new ArrayList<>();
            gui.onResize(hidden, seen::add);

            gui.frame(400f, 300f, ResizeObserverTest::noText);
            hidden.visible(true);
            gui.frame(400f, 300f, ResizeObserverTest::noText);

            assertEquals(400f, seen.get(seen.size() - 1).rect().w(), 0.5f,
                    "and the real box arrives when there is one");
        }
    }

    /** Re-registering replaces rather than adding, so a handler cannot be installed twice by accident. */
    @Test
    void registeringAgainReplaces() {
        try (Gui gui = new Gui(Atchung.create(), SAME_THREAD)) {
            Node canvas = gui.box().width(Length.FILL).height(Length.FILL);
            gui.root().children(canvas);
            List<String> seen = new ArrayList<>();
            gui.onResize(canvas, layout -> seen.add("first"));
            gui.onResize(canvas, layout -> seen.add("second"));

            gui.frame(400f, 300f, ResizeObserverTest::noText);

            assertEquals(List.of("second"), seen);
        }
    }

    @Test
    void aNullHandlerRemovesTheObserver() {
        try (Gui gui = new Gui(Atchung.create(), SAME_THREAD)) {
            Node canvas = gui.box().width(Length.FILL).height(Length.FILL);
            gui.root().children(canvas);
            List<NodeLayout> seen = new ArrayList<>();
            assertSame(gui, gui.onResize(canvas, seen::add));
            gui.onResize(canvas, null);

            gui.frame(400f, 300f, ResizeObserverTest::noText);

            assertEquals(List.of(), seen);
        }
    }
}
