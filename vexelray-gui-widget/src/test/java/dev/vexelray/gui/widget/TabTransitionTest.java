package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.Test;

import java.util.function.DoubleConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tab change with motion attached, which turns an instantaneous swap into something with a <em>duration</em> —
 * and a duration is a window in which the user can act again.
 *
 * <p>The property being protected is the one the widget can be held to whatever the motion does: <b>the panel
 * always comes back to exactly one visible page, in flow, opaque, and hittable.</b> Everything below is a way to
 * arrive there off the happy path — a second click mid-fade, a transition that finishes long after it was
 * superseded, a tab closed while it was still fading. A page that never comes back is the failure mode, and it is
 * the one that looks like the application hanging.
 *
 * <p>No clock here on purpose. The transition is driven by hand, which is possible precisely because {@code Tabs}
 * names no clock: the seam is a callback, so a test is just another thing that can decline to call it.
 */
class TabTransitionTest {

    private static Node page(HeadlessGui h, String label) {
        return h.gui.text(label).width(Length.FILL).height(Length.FILL);
    }

    private static float heightOf(Node n) {
        return n.layout().rect().h();
    }

    private static float opacityOf(HeadlessGui h, Node n) {
        return h.retained(n).opacity();
    }

    /** A transition the test drives: it records what it was handed and holds {@code done} until released. */
    private static final class Manual implements Tabs.TabTransition {
        Node outgoing;
        Node incoming;
        Tabs.Change change;
        Runnable done;
        int started;

        @Override
        public void run(Tabs.Change change, Runnable done) {
            this.change = change;
            this.outgoing = change.outgoing();
            this.incoming = change.incoming();
            this.done = done;
            this.started++;
        }

        /** Report completion, as a real transition would when its last frame lands. */
        void finish() {
            done.run();
        }
    }

    /**
     * The default is not "a very fast transition" — it is no transition, and the distinction matters because it
     * is what lets every existing panel, and every test written before there was any motion, behave identically.
     */
    @Test
    void withNoTransitionInstalledTheSwapIsStillInstant() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node one = page(h, "one");
            Node two = page(h, "two");
            Tabs tabs = new Tabs(h.gui);
            tabs.add("One", one);
            tabs.add("Two", two);
            h.gui.root().children(tabs.node());
            h.frame();

            tabs.select(1);
            h.frame();

            assertEquals(0f, heightOf(one), 0.01f, "the old page is gone in the same frame, as it always was");
            assertTrue(heightOf(two) > 0f);
            assertEquals(1f, opacityOf(h, one), 1e-6f, "and nothing was left faded behind it");
            assertEquals(1f, opacityOf(h, two), 1e-6f);
        }
    }

    /**
     * The overlap that makes a crossfade possible at all: for the duration, both pages fill the content area.
     * The outgoing one is out of flow, so the column does not split its height between them — a page that
     * shrank to half as it faded would be a transition nobody asked for.
     */
    @Test
    void bothPagesFillTheContentAreaWhileTheTransitionRuns() {
        try (HeadlessGui h = new HeadlessGui()) {
            Manual motion = new Manual();
            Node one = page(h, "one");
            Node two = page(h, "two");
            Tabs tabs = new Tabs(h.gui).transition(motion);
            tabs.add("One", one);
            tabs.add("Two", two);
            h.gui.root().children(tabs.node());
            h.frame();
            float full = heightOf(one);
            assertTrue(full > 0f);

            tabs.select(1);
            h.frame();

            assertEquals(1, motion.started, "the transition was asked to run");
            assertEquals(one.id(), motion.outgoing.id(), "and handed the page that is leaving");
            assertEquals(two.id(), motion.incoming.id());
            assertEquals(full, heightOf(one), 0.01f, "the outgoing page still fills the area, out of flow");
            assertEquals(full, heightOf(two), 0.01f, "and so does the incoming one, underneath it");

            motion.finish();
            h.frame();

            assertEquals(0f, heightOf(one), 0.01f, "only on `done` does the old page actually go");
            assertEquals(full, heightOf(two), 0.01f);
        }
    }

    /**
     * Neither page takes the pointer while the two are in motion, and both take it again the moment they stop.
     *
     * <p>Two reasons that land on the same rule. The page on its way out still covers the one arriving, so a
     * click during a transition would otherwise go to the page just navigated away from. And the arriving page is
     * <em>translated</em>, which is a fact about drawing only — it is still hit where layout put it — so while it
     * travels, its pointer target and its picture disagree. Rather than let them, the panel takes both pages out
     * of hit-testing for the duration, which is also the better behaviour on its own terms: a moving target is
     * not a thing to ask anyone to click.
     *
     * <p>Asserted through actual clicks rather than by reading props: what matters is where the event goes.
     */
    @Test
    void neitherPageTakesClicksWhileTheyAreMoving() {
        try (HeadlessGui h = new HeadlessGui()) {
            Manual motion = new Manual();
            boolean[] hit = {false, false};
            Node one = page(h, "one");
            Node two = page(h, "two");
            Tabs tabs = new Tabs(h.gui).transition(motion);
            tabs.add("One", one);
            tabs.add("Two", two);
            h.gui.onClick(one, () -> hit[0] = true);
            h.gui.onClick(two, () -> hit[1] = true);
            h.gui.root().children(tabs.node());
            h.frame();

            tabs.select(1);
            h.frame();

            float y = two.layout().rect().y() + two.layout().rect().h() / 2f;
            h.click(50f, y);

            assertFalse(hit[0], "the page that is leaving is pointer-transparent, however solid it still looks");
            assertFalse(hit[1], "and so is the arriving one, which is not yet where it appears to be");

            motion.finish();
            h.frame();
            h.click(50f, y);

            assertTrue(hit[1], "once it has arrived and stopped, it takes the pointer again");
            assertFalse(hit[0], "and the page that left is gone, not merely quiet");
        }
    }

    /**
     * The interruption rule: a new selection settles the previous transition before starting its own. Without it
     * the first outgoing page is left floating, faded and visible forever — its own {@code done} arrives to find
     * a panel that has moved on, and there is no honest thing left for it to do.
     */
    @Test
    void aSecondSelectionSettlesTheFirstTransition() {
        try (HeadlessGui h = new HeadlessGui()) {
            Manual motion = new Manual();
            Node one = page(h, "one");
            Node two = page(h, "two");
            Node three = page(h, "three");
            Tabs tabs = new Tabs(h.gui).transition(motion);
            tabs.add("One", one);
            tabs.add("Two", two);
            tabs.add("Three", three);
            h.gui.root().children(tabs.node());
            h.frame();

            tabs.select(1);      // one -> two, left hanging
            h.frame();
            tabs.select(2);      // two -> three, before the first finished
            h.frame();

            assertEquals(2, motion.started);
            assertEquals(0f, heightOf(one), 0.01f, "the abandoned page is hidden rather than left floating");
            assertEquals(1f, opacityOf(h, one), 1e-6f, "and opaque again, ready to be shown as it was");
            assertEquals(1f, opacityOf(h, two), 1e-6f, "so is the page that was arriving and is now leaving");
            assertTrue(heightOf(three) > 0f);
            assertEquals(2, tabs.selected());
        }
    }

    /**
     * And the other half of that rule: the superseded transition's {@code done} still arrives — it was a frame
     * loop or a thread, and neither can be recalled. It has to be a no-op, not a delayed hide of whatever page
     * happens to be showing by then.
     */
    @Test
    void aSupersededTransitionFinishingLateChangesNothing() {
        try (HeadlessGui h = new HeadlessGui()) {
            Manual first = new Manual();
            Node one = page(h, "one");
            Node two = page(h, "two");
            Node three = page(h, "three");
            Tabs tabs = new Tabs(h.gui).transition(first);
            tabs.add("One", one);
            tabs.add("Two", two);
            tabs.add("Three", three);
            h.gui.root().children(tabs.node());
            h.frame();

            tabs.select(1);
            h.frame();
            Runnable stale = first.done;   // the callback belonging to one -> two
            tabs.select(2);
            h.frame();

            stale.run();                   // arrives late, from a frame loop that had already been overtaken
            h.frame();

            assertTrue(heightOf(three) > 0f, "the page that is actually selected is still showing");
            assertEquals(2, tabs.selected());
            assertEquals(0f, heightOf(one), 0.01f);
            assertEquals(0f, heightOf(two), 0.01f);
        }
    }

    /**
     * Going back to the page that is currently fading out — click A, then B, then A again before B has arrived.
     * The settle hides the outgoing page, so it has to happen <em>before</em> the incoming one is shown, or the
     * two writes cancel and the panel ends up with no visible page at all.
     */
    @Test
    void returningToTheFadingPageMidTransitionShowsItAgain() {
        try (HeadlessGui h = new HeadlessGui()) {
            Manual motion = new Manual();
            Node one = page(h, "one");
            Node two = page(h, "two");
            Tabs tabs = new Tabs(h.gui).transition(motion);
            tabs.add("One", one);
            tabs.add("Two", two);
            h.gui.root().children(tabs.node());
            h.frame();
            float full = heightOf(one);

            tabs.select(1);      // one is fading out
            h.frame();
            tabs.select(0);      // straight back to it
            h.frame();

            assertEquals(0, tabs.selected());
            assertEquals(full, heightOf(one), 0.01f, "the page it went back to is showing, at full size");
            assertEquals(1f, opacityOf(h, one), 1e-6f, "and opaque — the interrupted fade left nothing behind");
            assertEquals(full, heightOf(two), 0.01f, "two is now the one leaving, so it is still up");

            motion.finish();
            h.frame();

            assertEquals(full, heightOf(one), 0.01f);
            assertEquals(0f, heightOf(two), 0.01f, "and when the second transition ends, only one page remains");
        }
    }

    /** Closing a tab mid-transition: the page leaves the tree, and nothing is left holding a reference to it. */
    @Test
    void removingATabDuringATransitionSettlesFirst() {
        try (HeadlessGui h = new HeadlessGui()) {
            Manual motion = new Manual();
            Node one = page(h, "one");
            Node two = page(h, "two");
            Node three = page(h, "three");
            Tabs tabs = new Tabs(h.gui).transition(motion);
            tabs.add("One", one);
            tabs.add("Two", two);
            tabs.add("Three", three);
            h.gui.root().children(tabs.node());
            h.frame();

            tabs.select(1);      // one is fading out
            h.frame();
            tabs.remove(0);      // and is then closed outright, still mid-fade
            h.frame();

            assertEquals(2, tabs.count());
            assertTrue(heightOf(two) > 0f, "the selected page survives its neighbour being closed mid-transition");
            assertEquals(1f, opacityOf(h, two), 1e-6f);
        }
    }

    // ------------------------------------------------------------------ the crossfade itself

    /** A {@link Tabs.Ramp} the test steps by hand — standing in for a frame clock, which is all a ramp ever is. */
    private static final class Steps implements Tabs.Ramp {
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
    }

    /**
     * The crossfade drives both pages through {@code opacity} and nothing else — which is what makes it free of
     * layout, and what makes {@code Tabs} able to undo it without being told — and it drives them at
     * <em>different</em> rates: the page you are done with clears twice as fast as the one you asked for
     * arrives. Matching rates is the obvious thing and it reads as the old page loitering, because for the whole
     * first half it is still the more solid of the two.
     */
    @Test
    void theLeavingPageClearsTwiceAsFastAsTheArrivingOneAppears() {
        try (HeadlessGui h = new HeadlessGui()) {
            Steps ramp = new Steps();
            Node one = page(h, "one");
            Node two = page(h, "two");
            Tabs tabs = new Tabs(h.gui).transition(Tabs.crossfade(ramp));
            tabs.add("One", one);
            tabs.add("Two", two);
            h.gui.root().children(tabs.node());
            h.frame();
            float full = heightOf(one);

            tabs.select(1);   // rightward, so the arriving page is painted on top
            h.frame();
            assertEquals(0f, opacityOf(h, two), 1e-6f, "the arriving page starts invisible, not half-drawn");
            assertEquals(1f, opacityOf(h, one), 1e-6f, "and the page underneath it is opaque, carrying the box");

            ramp.to(0.25);
            h.frame();
            assertEquals(0.25f, opacityOf(h, two), 1e-6f, "the arriving page fades up across the whole duration");
            assertEquals(0.5f, opacityOf(h, one), 1e-6f, "the leaving one is already half gone at a quarter in");

            ramp.to(0.5);
            h.frame();
            assertEquals(0f, opacityOf(h, one), 1e-6f, "and entirely gone by halfway, with half the fade left");
            assertEquals(0.5f, opacityOf(h, two), 1e-6f);
            assertEquals(full, heightOf(one), 0.01f, "and no layout ran for any of it");
            assertEquals(full, heightOf(two), 0.01f);

            ramp.to(1.0);
            ramp.done.run();
            h.frame();
            assertEquals(1f, opacityOf(h, two), 1e-6f, "the page that arrived is left untouched, not at 0.999");
            assertEquals(0f, heightOf(one), 0.01f);
            assertEquals(1f, opacityOf(h, one), 1e-6f, "and the one that left is opaque again for next time");
        }
    }

    /**
     * The two properties the dissolve is a compromise between, checked together because they pull against each
     * other and either one alone can be satisfied by something that looks wrong.
     *
     * <p><b>The leaving page is invisible before it is hidden.</b> Hiding is instantaneous, so anything still on
     * screen when it happens does not fade out, it disappears — and a page that is only text never covers the one
     * behind it, so there is nothing to hide the disappearance. That was the actual bug: with the outgoing page
     * held opaque throughout, switching to a page with no background of its own left the old page at full
     * strength for the whole transition and then cut it.
     *
     * <p><b>The backdrop leak stays bounded, and symmetric.</b> Src-over puts {@code (1-a)(1-b)} of the panel's
     * own content surface on screen, and the only way to make that zero is to hold one page opaque — which is
     * the first property's failure. Clearing the leaving page early costs coverage around the crossing point and
     * buys a transition that does not read as the old page loitering; the floor is a deliberate trade rather
     * than a number to minimise, and the point of pinning it is that it should not drift without someone
     * deciding it should. It is the same floor in both directions, which is worth its own assertion: the timing
     * used to depend on paint order, so moving left and moving right were visibly different transitions.
     *
     * <p>Coverage is measured rather than opacities asserted, because it is the quantity that matters and it
     * stays meaningful for any transition that moves the pages some other way.
     */
    @Test
    void theDissolveNeitherSnapsNorLetsTheBackdropThrough() {
        for (int from = 0; from <= 1; from++) {
            int to = 1 - from;
            try (HeadlessGui h = new HeadlessGui()) {
                Steps ramp = new Steps();
                Node one = page(h, "one");
                Node two = page(h, "two");
                Tabs tabs = new Tabs(h.gui).transition(Tabs.crossfade(ramp));
                tabs.add("One", one);
                tabs.add("Two", two);
                h.gui.root().children(tabs.node());
                h.frame();
                if (from != 0) {
                    tabs.select(from);
                    h.frame();
                    ramp.to(1.0);
                    ramp.done.run();
                    h.frame();
                }

                Node leaving = from == 0 ? one : two;
                tabs.select(to);
                h.frame();
                float worst = 1f;
                for (int step = 0; step <= 20; step++) {
                    ramp.to(step / 20d);
                    h.frame();
                    // Page one is painted first, page two on top of it — child order, both directions.
                    float bottom = opacityOf(h, one);
                    float top = opacityOf(h, two);
                    worst = Math.min(worst, top + (1f - top) * bottom);
                }
                assertEquals(0.5f, worst, 1e-5f, "going " + from + "->" + to + " the pages cover as little as "
                        + worst + " of the box at their thinnest — the same floor whichever way the selection "
                        + "moved, and not one that should change without someone choosing to change it");
                assertEquals(0f, opacityOf(h, leaving), 1e-5f,
                        "going " + from + "->" + to + " the leaving page is still visible when the ramp ends, so "
                                + "hiding it is a cut rather than the end of a fade");
            }
        }
    }

    /** A crossfade cut short must not leave the incoming page part-faded: it is the page that is staying. */
    @Test
    void aCrossfadeInterruptedHalfwayLeavesNoPageFaded() {
        try (HeadlessGui h = new HeadlessGui()) {
            Steps ramp = new Steps();
            Node one = page(h, "one");
            Node two = page(h, "two");
            Node three = page(h, "three");
            Tabs tabs = new Tabs(h.gui).transition(Tabs.crossfade(ramp));
            tabs.add("One", one);
            tabs.add("Two", two);
            tabs.add("Three", three);
            h.gui.root().children(tabs.node());
            h.frame();

            tabs.select(1);
            h.frame();
            ramp.to(0.4);
            h.frame();
            assertEquals(0.4f, opacityOf(h, two), 1e-6f, "mid-fade, as a control for what follows");

            tabs.select(2);   // interrupted at 40%
            h.frame();

            assertEquals(1f, opacityOf(h, one), 1e-6f);
            assertEquals(1f, opacityOf(h, two), 1e-6f, "the half-faded page is restored, not left at 0.4");
            assertTrue(heightOf(three) > 0f);
        }
    }
}
