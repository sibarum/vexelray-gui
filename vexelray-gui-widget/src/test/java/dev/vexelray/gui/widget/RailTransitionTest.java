package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rail panel with motion attached, where the interesting case is the one {@link Tabs} does not have: the panel
 * can end up showing <b>nothing</b>, and a panel on its way out has to still be there while it goes.
 *
 * <p>So the property under test is not really "does it fade" — it is that <b>hiding the panel is the last thing
 * that happens, not the first</b>, and that whatever route is taken through a duration, the rail comes back to
 * exactly one visible page and a panel whose visibility agrees with the selection. A close that cannot be watched
 * is the defect this file exists for; a panel that never comes back is the failure mode it guards against.
 *
 * <p>No clock here, on purpose. The motion is driven by hand, which is possible precisely because {@code Rail}
 * names no clock: the seam is a callback, so a test is just another thing that can decline to call it.
 */
class RailTransitionTest {

    /** A transition the test drives: it records what it was handed and holds {@code done} until released. */
    private static final class Manual implements Rail.PanelTransition {
        Rail.Change change;
        Runnable done;
        int started;

        @Override
        public void run(Rail.Change change, Runnable done) {
            this.change = change;
            this.done = done;
            this.started++;
        }

        void finish() {
            done.run();
        }
    }

    /** A {@link Ramp} the test steps by hand — standing in for a frame clock, which is all a ramp ever is. */
    private static final class Steps implements Ramp {
        DoubleConsumer progress;
        Runnable done;

        @Override
        public void run(DoubleConsumer progress, Runnable done) {
            this.progress = progress;
            this.done = done;
        }

        void to(double p) {
            progress.accept(p);
        }

        void finish() {
            done.run();
        }
    }

    /** A rail with two panels, whose page nodes the test keeps hold of — the builder is handed the body itself. */
    private static final class Two {
        final Rail rail;
        final AtomicReference<Node> layers = new AtomicReference<>();
        final AtomicReference<Node> colour = new AtomicReference<>();

        Two(HeadlessGui h) {
            this.rail = new Rail(h.gui)
                    .item("layers", h.gui.text("L"), "Layers", (g, into) -> {
                        layers.set(into);
                        into.append(g.text("layer rows"));
                    })
                    .item("colour", h.gui.text("C"), "Colour", (g, into) -> {
                        colour.set(into);
                        into.append(g.text("colour rows"));
                    });
            h.gui.root().children(rail.node(), rail.panel());
        }
    }

    private static boolean showing(HeadlessGui h, Node handle) {
        return h.retained(handle) != null && h.retained(handle).visible();
    }

    private static float opacityOf(HeadlessGui h, Node handle) {
        return h.retained(handle).opacity();
    }

    /**
     * The default is not "a very fast transition" — it is no transition, which is what lets every rail written
     * before there was any motion behave exactly as it did, and is the reduced-motion path.
     */
    @Test
    void withNothingInstalledThePanelAppearsAndGoesInOneStep() {
        try (HeadlessGui h = new HeadlessGui()) {
            Two two = new Two(h);
            h.frame();

            two.rail.select("layers");
            h.frame();
            assertTrue(showing(h, two.rail.panel()));
            assertEquals(1f, opacityOf(h, two.rail.panel()), 1e-6f, "and nothing left part-faded");

            two.rail.close();
            h.frame();
            assertFalse(showing(h, two.rail.panel()), "gone in the same frame, as it always was");
            assertEquals(1f, opacityOf(h, two.rail.panel()), 1e-6f);
            assertFalse(showing(h, two.layers.get()));
        }
    }

    /**
     * The whole point of the seam: a closing panel is still on show, and is put away only when the motion says it
     * is done. Before this there was nothing to animate — the panel was hidden on the way into the change.
     */
    @Test
    void aClosingPanelIsStillOnShowUntilTheMotionSaysOtherwise() {
        try (HeadlessGui h = new HeadlessGui()) {
            Manual motion = new Manual();
            Two two = new Two(h);
            two.rail.transition(motion);
            h.frame();

            two.rail.select("layers");
            h.frame();
            motion.finish();
            h.frame();

            two.rail.close();
            h.frame();

            assertEquals(2, motion.started);
            assertTrue(motion.change.closing(), "and the transition was told which of the three changes this is");
            assertFalse(motion.change.opening());
            assertNull(motion.change.incoming(), "there is no page arriving");
            assertEquals(two.layers.get().id(), motion.change.outgoing().id());
            assertTrue(showing(h, two.rail.panel()), "the panel is still up, which is what makes a close watchable");
            assertTrue(showing(h, two.layers.get()), "and it still has its page on it, not an empty frame");
            assertNull(two.rail.selected(), "while the selection itself is already the new answer");

            motion.finish();
            h.frame();

            assertFalse(showing(h, two.rail.panel()), "only on done does the panel actually go");
            assertFalse(showing(h, two.layers.get()));
        }
    }

    /**
     * And the ordering that makes an application's own motion possible alongside: the handler is told before the
     * motion starts, so the panel it is being told about is still on screen. Told afterwards — which is what
     * happened before — a handler learning of a close could only ever have been looking at a panel already gone.
     */
    @Test
    void theSelectionIsToldBeforeThePanelIsMoved() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<String> order = new ArrayList<>();
            Two two = new Two(h);
            two.rail.transition((change, done) -> {
                order.add(change.closing() ? "moved: closing" : "moved: opening");
                done.run();
            });
            two.rail.onSelect(k -> order.add("told: " + k));
            h.frame();

            two.rail.select("layers");
            two.rail.close();
            h.frame();

            assertEquals(List.of("told: layers", "moved: opening", "told: null", "moved: closing"), order);
        }
    }

    /**
     * Which nodes move, and it is not always the same ones: the panel moves when it appears or goes, and the
     * pages move when they trade places inside a panel that stays. Fading both at once would compose the two —
     * a page arriving at 50% inside a panel at 50% is drawn at a quarter strength nobody asked for.
     */
    @Test
    void thePanelMovesWhenItAppearsAndThePagesMoveWhenTheyTradePlaces() {
        try (HeadlessGui h = new HeadlessGui()) {
            Steps ramp = new Steps();
            Two two = new Two(h);
            two.rail.transition(Rail.fade(ramp));
            h.frame();

            two.rail.select("layers");
            h.frame();
            assertEquals(0f, opacityOf(h, two.rail.panel()), 1e-6f, "the panel arrives, so the panel is what fades");
            assertEquals(1f, opacityOf(h, two.layers.get()), 1e-6f, "the page on it is not faded as well");

            ramp.to(0.5);
            h.frame();
            assertEquals(0.5f, opacityOf(h, two.rail.panel()), 1e-6f);

            ramp.to(1.0);
            ramp.finish();
            h.frame();
            assertEquals(1f, opacityOf(h, two.rail.panel()), 1e-6f);

            two.rail.select("colour");
            h.frame();
            assertEquals(1f, opacityOf(h, two.rail.panel()), 1e-6f, "a swap leaves the panel alone: it is not going anywhere");
            assertEquals(0f, opacityOf(h, two.colour.get()), 1e-6f, "the arriving page starts invisible");
            assertEquals(1f, opacityOf(h, two.layers.get()), 1e-6f, "and the leaving one opaque");

            ramp.to(0.25);
            h.frame();
            assertEquals(0.25f, opacityOf(h, two.colour.get()), 1e-6f);
            assertEquals(0.5f, opacityOf(h, two.layers.get()), 1e-6f, "the page you are done with clears twice as fast");

            ramp.to(1.0);
            ramp.finish();
            h.frame();
            assertTrue(showing(h, two.colour.get()));
            assertFalse(showing(h, two.layers.get()), "and one page is left, opaque and in flow");
            assertEquals(1f, opacityOf(h, two.colour.get()), 1e-6f);
            assertEquals(1f, opacityOf(h, two.layers.get()), 1e-6f);
        }
    }

    /**
     * The travel is the panel's and only on the way in and out, for the same reason: a rail's icons are tools
     * rather than a sequence, so a swap between two of them has no direction to come from.
     */
    @Test
    void onlyThePanelTravelsAndOnlyWhenItComesAndGoes() {
        try (HeadlessGui h = new HeadlessGui()) {
            Steps ramp = new Steps();
            Two two = new Two(h);
            two.rail.transition(Rail.slide(ramp, -1.5f));
            h.frame();

            two.rail.select("layers");
            h.frame();
            assertEquals(-1.5f, h.retained(two.rail.panel()).translateX(), 1e-6f, "it starts out under the rail");

            ramp.to(0.5);
            h.frame();
            float halfway = h.retained(two.rail.panel()).translateX();
            assertTrue(halfway > -1.5f && halfway < 0f, "and travels: " + halfway);

            ramp.to(1.0);
            ramp.finish();
            h.frame();
            assertEquals(0f, h.retained(two.rail.panel()).translateX(), 1e-6f, "arriving exactly where it belongs");

            two.rail.select("colour");
            h.frame();
            ramp.to(0.5);
            h.frame();
            assertEquals(0f, h.retained(two.rail.panel()).translateX(), 1e-6f, "a swap does not move the panel");
            assertEquals(0f, h.retained(two.colour.get()).translateX(), 1e-6f, "nor the pages, which have no direction");
        }
    }

    /**
     * The interruption rule, which is {@code Tabs}'s: a new selection settles the previous change before starting
     * its own, so the only page an in-flight transition can still be holding is one the new call is taking charge
     * of anyway. Here that includes re-opening the panel that was in the middle of closing.
     */
    @Test
    void reopeningMidCloseLeavesOnePageAndAPanelThatIsProperlyUp() {
        try (HeadlessGui h = new HeadlessGui()) {
            Manual motion = new Manual();
            Two two = new Two(h);
            two.rail.transition(motion);
            h.frame();

            two.rail.select("layers");
            motion.finish();
            h.frame();

            two.rail.close();            // the panel is on its way out
            h.frame();
            Runnable stale = motion.done;
            two.rail.select("colour");   // and is asked back before it got there
            h.frame();

            assertEquals(3, motion.started);
            assertEquals("colour", two.rail.selected());
            assertTrue(showing(h, two.rail.panel()));
            assertEquals(1f, opacityOf(h, two.rail.panel()), 1e-6f, "the interrupted fade left nothing behind");
            assertTrue(motion.change.opening(), "and it counts as an opening: nothing was on show to leave");

            // The abandoned close still reports in — it was a frame loop or a thread, and neither can be
            // recalled. The most it can do is cut the change that replaced it short of the state that change was
            // already heading for.
            stale.run();
            motion.finish();
            h.frame();

            assertEquals("colour", two.rail.selected());
            assertTrue(showing(h, two.rail.panel()));
            assertTrue(showing(h, two.colour.get()), "exactly one page, and it is the selected one");
            assertFalse(showing(h, two.layers.get()));
            assertEquals(1f, opacityOf(h, two.colour.get()), 1e-6f);
        }
    }

    /**
     * A page on its way out, or a panel on its way out, must not be what a click lands on however solid it still
     * looks. Asserted through actual clicks rather than by reading props: what matters is where the event goes.
     */
    @Test
    void nothingInMotionTakesTheClickAndEverythingAtRestDoes() {
        try (HeadlessGui h = new HeadlessGui()) {
            Manual motion = new Manual();
            boolean[] hit = {false};
            Two two = new Two(h);
            two.rail.transition(motion).panelWidth(dev.vexelray.gui.core.layout.Length.rem(8f));
            h.frame();

            two.rail.select("layers");
            motion.finish();
            h.frame();
            h.gui.onClick(two.layers.get(), () -> hit[0] = true);
            h.frame();

            dev.vexelray.gui.core.layout.Rect r = two.layers.get().layout().rect();
            float x = r.x() + r.w() / 2f;
            float y = r.y() + r.h() / 2f;
            h.click(x, y);
            assertTrue(hit[0], "at rest the page takes the pointer");

            hit[0] = false;
            two.rail.close();
            h.frame();
            h.click(x, y);
            assertFalse(hit[0], "a panel being put away is pointer-transparent for the whole of it");

            motion.finish();
            h.frame();
        }
    }
}
