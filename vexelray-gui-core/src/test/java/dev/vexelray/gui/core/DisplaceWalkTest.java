package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.LayoutMotion;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the frame walks the tree for {@link LayoutMotion}: while the source has something in flight, once more after
 * the last frame that drew anything displaced, and otherwise not at all — while moves are still detected on every
 * layout pass.
 */
class DisplaceWalkTest {

    private static float noText(RetainedNode n, Axis axis, float px) {
        return 0f;
    }

    private static RetainedNode frame(Gui gui) {
        return gui.frame(400f, 300f, DisplaceWalkTest::noText);
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

    /** One displacement for one node, written by the test, and a count of the moves it was told about. */
    private static final class Scripted implements LayoutMotion {
        long target = -1L;
        float dy;
        int moves;

        @Override
        public void moved(RetainedNode node, float fromX, float fromY, float toX, float toY) {
            moves++;
        }

        @Override
        public float displacementX(RetainedNode node) {
            return 0f;
        }

        @Override
        public float displacementY(RetainedNode node) {
            return node.id == target ? dy : 0f;
        }

        @Override
        public boolean active() {
            return dy != 0f;
        }
    }

    private static Node row(Gui gui) {
        return gui.box().width(Length.dp(40)).height(Length.dp(20));
    }

    @Test
    void aStillTreeWithAQuietSourceWalksNothing() {
        Scripted s = new Scripted();
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            gui.motion(s);
            gui.root().children(gui.column().children(row(gui), row(gui)));
            frame(gui);
            long before = gui.displaceWalks();
            for (int i = 0; i < 5; i++) {
                frame(gui);
            }
            assertEquals(before, gui.displaceWalks(), "nothing in flight and nothing displaced: no walk");
        }
    }

    @Test
    void anActiveSourceIsWalkedAndTheFrameAfterItWritesTheRestBack() {
        Scripted s = new Scripted();
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            gui.motion(s);
            Node a = row(gui);
            gui.root().children(gui.column().children(a));
            float restY = find(frame(gui), a.id()).y;

            s.target = a.id();
            s.dy = 7f;
            long before = gui.displaceWalks();
            assertEquals(restY + 7f, find(frame(gui), a.id()).y, "drawn displaced while active");
            assertEquals(before + 1, gui.displaceWalks());

            s.dy = 0f;
            assertEquals(restY, find(frame(gui), a.id()).y, "the walk after the last displaced frame writes zeros");
            assertEquals(before + 2, gui.displaceWalks());

            frame(gui);
            frame(gui);
            assertEquals(before + 2, gui.displaceWalks(), "and then it stops");
            assertEquals(restY, find(frame(gui), a.id()).y);
        }
    }

    @Test
    void aMoveIsReportedWhileTheWalkIsSkipped() {
        Scripted s = new Scripted();
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            gui.motion(s);
            Node a = row(gui);
            Node b = row(gui);
            Node list = gui.column().children(a, b);
            gui.root().children(list);
            frame(gui);
            frame(gui);
            long before = gui.displaceWalks();

            list.arrange(b, a);
            frame(gui);
            assertTrue(s.moves > 0, "settle still reports the reorder");
            assertEquals(before, gui.displaceWalks(), "a source that started nothing is still not walked");
        }
    }

    /** The window moving under the user's hand is not a node going somewhere: a whole window may be enrolled. */
    @Test
    void aResizeMovesNothing() {
        Scripted s = new Scripted();
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            gui.motion(s);
            Node half = gui.box().width(Length.percent(50)).height(Length.dp(20));
            Node after = row(gui);
            gui.root().children(gui.row().children(half, after));
            frame(gui);
            float x = find(frame(gui), after.id()).x;

            RetainedNode wider = gui.frame(600f, 300f, DisplaceWalkTest::noText);

            assertTrue(find(wider, after.id()).x > x, "the resize did move it");
            assertEquals(0, s.moves, "and nothing was told it moved");
        }
    }

    @Test
    void aSourceThatDoesNotSayIsWalkedEveryFrame() {
        LayoutMotion silent = new LayoutMotion() {
            @Override
            public void moved(RetainedNode node, float fromX, float fromY, float toX, float toY) {
            }

            @Override
            public float displacementX(RetainedNode node) {
                return 0f;
            }

            @Override
            public float displacementY(RetainedNode node) {
                return 0f;
            }
        };
        try (Gui gui = new Gui(Atchung.create(), Runnable::run)) {
            gui.motion(silent);
            gui.root().children(row(gui));
            frame(gui);
            long before = gui.displaceWalks();
            frame(gui);
            frame(gui);
            assertEquals(before + 2, gui.displaceWalks(), "the default active() is always true");
        }
    }
}
