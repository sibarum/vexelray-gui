package dev.vexelray.gui.core;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.style.Finish;
import dev.vexelray.gui.core.style.PaintMotion;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A theme swapped after the tree is built repaints the tree: a node holds a role, not the colour it resolved to, so
 * the same node is drawn in the new theme's colour with nothing rewritten — and the swap is reported to the paint
 * motion as the colour changes it makes, so a tree that fades its hovers fades from one look to the other.
 */
class RethemeTest {

    private static final Executor SAME_THREAD = Runnable::run;

    private static float noText(RetainedNode n, dev.vexelray.gui.core.layout.LayoutEnums.Axis axis, float px) {
        return 0f;
    }

    /** What it was told, as "channel" lines with the colours, for asserting on. */
    private static final class Recording implements PaintMotion {
        final List<String> told = new ArrayList<>();
        final List<Color[]> colours = new ArrayList<>();

        @Override
        public void repainted(long node, Channel channel, Color from, Color to) {
            told.add(channel.name());
            colours.add(new Color[] {from, to});
        }

        @Override
        public Color drawn(long node, Channel channel, Color settled) {
            return settled;
        }

        @Override
        public boolean moving() {
            return false;
        }
    }

    @Test
    void aRoleWrittenBeforeTheSwapIsDrawnInTheThemeAfterIt() {
        try (Gui gui = new Gui(Atchung.create(), SAME_THREAD)) {
            Node n = gui.box().width(Length.dp(40)).height(Length.dp(20))
                    .background(Role.PANEL).border(Length.dp(1), Role.LINE).lit(Finish.LIT);
            gui.root().children(n);
            RetainedNode node = gui.frame(400f, 300f, RethemeTest::noText).children.get(0);
            assertEquals(Theme.DARK.color(Role.PANEL), node.background(gui.drawnTheme()));
            assertTrue(node.lit(gui.drawnTheme()), "the dark theme lights its surfaces");

            gui.theme(Theme.LIGHT);
            assertSame(Theme.DARK, gui.drawnTheme(), "the swap is an edit, drawn with the frame that applies it");
            gui.frame(400f, 300f, RethemeTest::noText);
            assertSame(Theme.LIGHT, gui.drawnTheme());
            assertEquals(Theme.LIGHT.color(Role.PANEL), node.background(gui.drawnTheme()));
            assertEquals(Theme.LIGHT.color(Role.LINE), node.borderColor(gui.drawnTheme()));
            assertFalse(node.lit(gui.drawnTheme()), "and the light one does not, with nothing rewritten");
        }
    }

    @Test
    void aStateIsShadedByTheThemeThatDrawsIt() {
        try (Gui gui = new Gui(Atchung.create(), SAME_THREAD)) {
            Node n = gui.box().width(Length.dp(40)).height(Length.dp(20))
                    .background(Role.ACTION.at(InteractionState.HOVER));
            gui.root().children(n);
            RetainedNode node = gui.frame(400f, 300f, RethemeTest::noText).children.get(0);
            gui.theme(Theme.LIGHT);
            gui.frame(400f, 300f, RethemeTest::noText);
            assertEquals(Theme.LIGHT.color(Role.ACTION, InteractionState.HOVER), node.background(gui.drawnTheme()));
        }
    }

    @Test
    void theSwapIsReportedAsTheColourChangesItMakes() {
        try (Gui gui = new Gui(Atchung.create(), SAME_THREAD)) {
            Recording paint = new Recording();
            gui.paintMotion(paint);
            Node themed = gui.text("x").width(Length.dp(40)).height(Length.dp(20)).background(Role.PANEL);
            Node fixed = gui.box().width(Length.dp(40)).height(Length.dp(20)).background(Theme.DARK.color(Role.PANEL));
            gui.root().children(themed, fixed);
            gui.frame(400f, 300f, RethemeTest::noText);
            assertTrue(paint.told.isEmpty());

            gui.theme(Theme.LIGHT);
            gui.frame(400f, 300f, RethemeTest::noText);
            // The themed node's fill, and its text in the theme's ink, which it never stated. The fixed colour is the
            // same in every theme, which is exactly what is wrong with writing one.
            assertEquals(List.of("BACKGROUND", "TEXT"), paint.told);
            assertEquals(Theme.DARK.color(Role.PANEL), paint.colours.get(0)[0]);
            assertEquals(Theme.LIGHT.color(Role.PANEL), paint.colours.get(0)[1]);
            assertEquals(Theme.LIGHT.color(Role.INK), paint.colours.get(1)[1]);

            paint.told.clear();
            gui.theme(Theme.LIGHT);
            gui.frame(400f, 300f, RethemeTest::noText);
            assertTrue(paint.told.isEmpty(), "the theme it already has changes nothing");
        }
    }

    @Test
    void writingTheSamePaintAgainChangesNothing() {
        try (Gui gui = new Gui(Atchung.create(), SAME_THREAD)) {
            Node n = gui.box().width(Length.dp(40)).height(Length.dp(20)).background(Role.ACTION.at(InteractionState.HOVER));
            gui.root().children(n);
            gui.frame(400f, 300f, RethemeTest::noText);
            gui.takeDrawNeeded();
            n.background(Role.ACTION.at(InteractionState.HOVER));
            gui.frame(400f, 300f, RethemeTest::noText);
            assertFalse(gui.takeDrawNeeded(), "a state handler firing again with the same state costs no frame");
        }
    }
}
