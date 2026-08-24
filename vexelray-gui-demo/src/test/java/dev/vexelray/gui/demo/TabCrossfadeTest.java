package dev.vexelray.gui.demo;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.Tabs;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Kron;
import sibarum.kronometer.anim.Ease;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tab crossfade end to end: a real {@link Gui}, a real {@link Tabs}, and a real {@link KronoGui} driving it,
 * with the frame loop supplied by hand.
 *
 * <p>This lives in the demo because the demo is the only module that can see all three — which is exactly why the
 * seam needed testing here. The widget tests drive the transition by calling the ramp themselves, and the krono
 * tests drive a ramp with no widget on the end of it; both pass with the two halves never actually connected.
 * The failure this pins is precisely that shape: the transition's <em>duration</em> was real (the swap visibly
 * waited) while every intermediate value was lost, so the tab change was a delay followed by a pop.
 */
class TabCrossfadeTest {

    /** Deterministic metrics, so no atlas or font is involved. */
    private static final TextMeasurer TM = (n, axis, sizePx) -> axis == Axis.HORIZONTAL
            ? (n.textString() == null ? 0 : n.textString().length()) * sizePx * 0.5f
            : sizePx;

    private static final float W = 800f;
    private static final float H = 600f;

    private static RetainedNode find(RetainedNode n, long id) {
        if (n == null || n.id == id) {
            return n;
        }
        for (RetainedNode c : n.children) {
            RetainedNode hit = find(c, id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    @Test
    void theCrossfadeProducesIntermediateOpacitiesAcrossFrames() throws Exception {
        Gui gui = new Gui(Atchung.create());
        try (KronoGui krono = KronoGui.attach(gui, Kron.driven())) {
            Node one = gui.text("one").width(Length.FILL).height(Length.FILL);
            Node two = gui.text("two").width(Length.FILL).height(Length.FILL);
            Tabs tabs = new Tabs(gui).transition(Tabs.crossfade(
                    (progress, done) -> krono.ramp(Dur.ms(100), Ease.LINEAR, progress, done)));
            tabs.add("One", one);
            tabs.add("Two", two);
            gui.root().children(tabs.node());
            gui.frame(W, H, TM);

            tabs.select(1);

            // Ten frames across a 100 ms ramp, the way a 100 fps loop would deliver them: tick first (the
            // application edge hook), then lay out — the order GuiApp.run uses, so a mutation an animation posts
            // this frame is drained by this frame.
            List<Float> outgoing = new ArrayList<>();
            List<Float> incoming = new ArrayList<>();
            for (int frame = 1; frame <= 12; frame++) {
                krono.tick(Dur.ms(10).times(frame));
                RetainedNode root = gui.frame(W, H, TM);
                outgoing.add(find(root, one.id()).opacity());
                incoming.add(find(root, two.id()).opacity());
            }

            // Selecting rightward, so the arriving page is the one painted on top and carrying the dissolve.
            assertTrue(incoming.stream().anyMatch(o -> o > 0.05f && o < 0.95f),
                    "the arriving page should be part-faded on some frame; saw " + incoming);
            assertTrue(outgoing.contains(0f),
                    "and the leaving page has to be fully faded on a frame that is actually presented, or "
                            + "hiding it is a cut rather than the end of a fade; saw " + outgoing);
            assertEquals(1f, incoming.get(incoming.size() - 1), 1e-6f, "and it ends up fully opaque");

            // The duration has to be spent doing something visible. Frame 5 of 10 is the middle of a 100 ms ramp,
            // and under a linear ease the fade is half done there. This is the assertion that would have caught
            // the first version: it used OUT_CUBIC, which is 87% faded at its own halfway point, so two thirds of
            // the duration was a stall — and every endpoint assertion above still passed, because the endpoints
            // were never the problem. A transition is judged by its middle.
            assertEquals(0.5f, incoming.get(4), 0.15f,
                    "halfway through the ramp the fade should be about halfway done; saw " + incoming);
        } finally {
            gui.close();
        }
    }

    /**
     * The second transition has to animate as well as the first. Every test so far ran exactly one, which is the
     * blind spot: anything a ramp leaves behind — a cancelled per-frame effect, a spent cell, a rate domain with
     * a broken subscriber list — costs nothing the first time and everything afterwards.
     */
    @Test
    void everyTransitionAnimates() throws Exception {
        Gui gui = new Gui(Atchung.create());
        try (KronoGui krono = KronoGui.attach(gui, Kron.driven())) {
            List<Node> pages = List.of(
                    gui.text("one").width(Length.FILL).height(Length.FILL),
                    gui.text("two").width(Length.FILL).height(Length.FILL),
                    gui.text("three").width(Length.FILL).height(Length.FILL));
            Tabs tabs = new Tabs(gui).transition(Tabs.crossfade(
                    (progress, done) -> krono.ramp(Dur.ms(100), Ease.LINEAR, progress, done)));
            tabs.add("One", pages.get(0));
            tabs.add("Two", pages.get(1));
            tabs.add("Three", pages.get(2));
            gui.root().children(tabs.node());
            gui.frame(W, H, TM);

            long elapsed = 0;
            int previous = 0;
            for (int round = 1; round <= 3; round++) {
                int next = round % 3;
                tabs.select(next);
                Node leaving = pages.get(previous);
                Node arriving = pages.get(next);
                // The arriving page fades up across the whole duration, so its opacity is how far the change has
                // travelled — and it means the same thing whichever way the selection moved, which it did not
                // when the timing depended on which page the renderer painted last. Rounds 1 and 2 move
                // rightward and round 3 wraps back to the first tab, so both directions are covered.
                List<Float> share = new ArrayList<>();
                RetainedNode root = null;
                for (int frame = 1; frame <= 12; frame++) {
                    elapsed += 10;
                    krono.tick(Dur.ms(elapsed));
                    root = gui.frame(W, H, TM);
                    share.add(find(root, arriving.id()).opacity());
                }
                assertEquals(0.5f, share.get(4), 0.15f,
                        "transition " + round + " should be half blended at its halfway point; saw " + share);
                // The end state is read from the tree rather than from `share`: once the transition settles, the
                // leaving page is restored to opaque before being hidden, so a blend computed from its alpha
                // stops meaning anything the moment it is no longer part of the picture.
                assertEquals(1f, find(root, arriving.id()).opacity(), 1e-6f,
                        "transition " + round + " leaves the arriving page opaque");
                assertTrue(find(root, arriving.id()).visible(), "and showing");
                assertFalse(find(root, leaving.id()).visible(), "and the one it replaced hidden");
                previous = next;
            }
        } finally {
            gui.close();
        }
    }

    /**
     * The guard's proof of life, and the shape of the bug it exists for: an {@code OUT_CUBIC} ramp reaches 87.5%
     * at the halfway point of its own duration and spends the rest of it between 0.94 and 1, where nothing is
     * visible. Stated as arithmetic rather than driven through a GUI because that is all it ever was — the
     * plumbing was right, the curve was wrong, and every endpoint check passed throughout.
     */
    @Test
    void aFrontLoadedEaseSpendsMostOfItsDurationInvisible() {
        // How much of the fade is left for the second half of the duration to do.
        float cubicTail = Ease.OUT_CUBIC.at(1f) - Ease.OUT_CUBIC.at(0.5f);
        float linearTail = Ease.LINEAR.at(1f) - Ease.LINEAR.at(0.5f);

        assertEquals(0.875f, Ease.OUT_CUBIC.at(0.5f), 1e-3f, "out-cubic is 87.5% done at its halfway point");
        assertEquals(0.125f, cubicTail, 1e-3f,
                "so half the duration is spent moving opacity by an eighth, which nobody can see");
        assertEquals(0.5f, linearTail, 1e-3f, "a linear ramp spreads the change evenly across its duration");
        assertTrue(linearTail > cubicTail * 3f,
                "which is the whole difference between a fade and a stall followed by a jump");
    }
}
