package dev.vexelray.gui.krono;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Kron;
import sibarum.kronometer.anim.Ease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;

/**
 * Transitions installed on a real frame: a still tree costs no displacement walk, a reorder still slides, and the
 * walks stop again once it has arrived.
 */
class TransitionsWalkTest {

    private static final Dur OVER = ms(100);

    private static float noText(RetainedNode n, Axis axis, float px) {
        return 0f;
    }

    private static RetainedNode find(RetainedNode from, long id) {
        if (from.id == id) {
            return from;
        }
        for (RetainedNode c : from.children) {
            RetainedNode hit = find(c, id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** One frame at logical time {@code at}: the clock first, then the frame, as {@code GuiApp.run} orders them. */
    private static RetainedNode at(KronoGui krono, long at) {
        krono.tick(ms(at));
        return krono.gui().frame(400f, 300f, TransitionsWalkTest::noText);
    }

    private static Node row(Gui gui) {
        return gui.box().width(Length.dp(40)).height(Length.dp(20));
    }

    @Test
    void stillFramesWalkNothingAndAReorderStillSlides() {
        Gui gui = new Gui(Atchung.create(), Runnable::run);
        try (KronoGui krono = KronoGui.attach(gui, Kron.driven())) {
            Transitions moves = Transitions.on(krono);
            gui.motion(moves);
            Node a = row(gui);
            Node b = row(gui);
            Node list = gui.column().children(a, b);
            gui.root().children(list);
            moves.followChildren(list, OVER, Ease.LINEAR);
            at(krono, 0);
            float aTop = find(at(krono, 16), a.id()).y;
            float bTop = find(at(krono, 32), b.id()).y;
            assertTrue(bTop > aTop);

            long before = gui.displaceWalks();
            for (int t = 48; t <= 200; t += 16) {
                at(krono, t);
            }
            assertEquals(before, gui.displaceWalks(), "Transitions installed, nothing moving: no walk");

            list.children(b, a);
            RetainedNode moved = find(at(krono, 200), b.id());
            assertEquals(bTop, moved.y, 0.01f, "drawn where it was in the frame the reorder lands");
            assertFalse(moves.settled());
            long during = gui.displaceWalks();
            assertTrue(during > before, "a move is still walked");

            RetainedNode half = find(at(krono, 250), b.id());
            assertTrue(half.y > aTop && half.y < bTop, "half-way up: " + half.y);

            at(krono, 300);
            at(krono, 316);
            assertTrue(moves.settled());
            assertEquals(aTop, find(at(krono, 332), b.id()).y, "arrived exactly");
            long after = gui.displaceWalks();
            for (int t = 348; t <= 500; t += 16) {
                at(krono, t);
            }
            assertEquals(after, gui.displaceWalks(), "the walks stop once the slide has arrived");
            assertEquals(aTop, find(at(krono, 516), b.id()).y);
        }
    }
}
