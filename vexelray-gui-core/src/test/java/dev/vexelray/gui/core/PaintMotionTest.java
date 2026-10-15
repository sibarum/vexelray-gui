package dev.vexelray.gui.core;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.PaintMotion;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which colour writes a {@link PaintMotion} is told about: a change from one colour to another on a node's
 * background, border or text, and nothing else. What it does with them is the implementation's (gui-krono's
 * {@code Fades} is one, and is tested there).
 */
class PaintMotionTest {

    private static final Executor SAME_THREAD = Runnable::run;
    private static final Color A = Color.rgb(0.1f, 0.2f, 0.3f);
    private static final Color B = Color.rgb(0.7f, 0.6f, 0.5f);

    private static float noText(dev.vexelray.gui.core.model.RetainedNode n,
                                dev.vexelray.gui.core.layout.LayoutEnums.Axis axis, float px) {
        return 0f;
    }

    /** What it was told, as "channel from->to" lines, and whether it says it is still moving. */
    private static final class Recording implements PaintMotion {
        final List<String> told = new ArrayList<>();
        boolean moving;

        @Override
        public void repainted(long node, Channel channel, Color from, Color to) {
            told.add(channel + " " + (from.equals(A) ? "A" : "B") + "->" + (to.equals(A) ? "A" : "B"));
        }

        @Override
        public Color drawn(long node, Channel channel, Color settled) {
            return settled;
        }

        @Override
        public boolean moving() {
            return moving;
        }
    }

    @Test
    void aColourChangedIsReportedAndAFirstColourIsNot() {
        try (Gui gui = new Gui(Atchung.create(), SAME_THREAD)) {
            Recording paint = new Recording();
            gui.paintMotion(paint);
            Node n = gui.text("x").width(Length.dp(40)).height(Length.dp(20));
            gui.root().children(n);
            n.background(A).border(Length.dp(1), A).textColor(A);
            gui.frame(400f, 300f, PaintMotionTest::noText);
            assertTrue(paint.told.isEmpty(), "a first colour has nowhere to come from: " + paint.told);

            n.background(B).border(Length.dp(1), B).textColor(B);
            gui.frame(400f, 300f, PaintMotionTest::noText);
            assertEquals(List.of("BACKGROUND A->B", "BORDER A->B", "TEXT A->B"), paint.told);

            paint.told.clear();
            n.background(B);
            gui.frame(400f, 300f, PaintMotionTest::noText);
            assertTrue(paint.told.isEmpty(), "writing the colour it already has changes nothing");
        }
    }

    @Test
    void aMotionInProgressAsksForTheNextFrame() {
        try (Gui gui = new Gui(Atchung.create(), SAME_THREAD)) {
            Recording paint = new Recording();
            gui.paintMotion(paint);
            gui.frame(400f, 300f, PaintMotionTest::noText);
            gui.takeDrawNeeded();

            paint.moving = true;
            gui.frame(400f, 300f, PaintMotionTest::noText);
            assertTrue(gui.takeDrawNeeded(), "a fade on its way draws a different frame next");
        }
    }
}
