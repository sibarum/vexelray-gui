package dev.vexelray.gui.core.app;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.Reconciler;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render-on-change (docs/plans/frame-loop.md): a frame is owed a draw when, and only when, something it would show
 * changed — and the change itself is what says so. Headless: the question is answered by {@link Gui}, before any
 * window is involved, which is what lets a host skip the draw.
 */
class DrawOnChangeTest {

    private static final float W = 400f;
    private static final float H = 300f;

    private static float noText(RetainedNode n, Axis axis, float px) {
        return 0f;
    }

    /** Run one frame and answer whether it has anything new to show. */
    private static boolean frameDraws(Gui gui) {
        gui.frame(W, H, DrawOnChangeTest::noText);
        return gui.takeDrawNeeded();
    }

    @Test
    void theFirstFrameDrawsAndAStillTreeDoesNot() {
        try (Gui gui = new Gui(Atchung.create())) {
            gui.root().children(gui.box().width(Length.dp(40)).height(Length.dp(20)));
            assertTrue(frameDraws(gui), "the first frame always draws");
            assertFalse(frameDraws(gui), "nothing changed, so nothing to draw");
            assertFalse(frameDraws(gui), "and still nothing, however many times the loop wakes");
        }
    }

    @Test
    void writingTheValueAPropAlreadyHasIsNotAChange() {
        try (Gui gui = new Gui(Atchung.create())) {
            var colour = gui.theme().color(Role.ACCENT);
            Node box = gui.box().width(Length.dp(40)).height(Length.dp(20)).background(colour);
            gui.root().children(box);
            frameDraws(gui);

            box.background(colour);
            assertFalse(frameDraws(gui), "the same colour again changes nothing a frame would show");

            box.background(gui.theme().color(Role.DANGER));
            assertTrue(frameDraws(gui), "a different colour does");
            assertFalse(frameDraws(gui), "once, and then the tree is still again");
        }
    }

    @Test
    void textCountsOnlyWhenItChanges() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node label = gui.text("x = 2");
            gui.root().children(label);
            frameDraws(gui);

            label.text("x = 2");
            assertFalse(frameDraws(gui), "the same text is not an edit");
            label.text("x = 3");
            assertTrue(frameDraws(gui), "different text is");
        }
    }

    @Test
    void aNewWindowSizeDrawsWithoutAnyEdit() {
        try (Gui gui = new Gui(Atchung.create())) {
            gui.root().children(gui.box().width(Length.FILL).height(Length.FILL));
            frameDraws(gui);
            gui.frame(W + 50f, H, DrawOnChangeTest::noText);
            assertTrue(gui.takeDrawNeeded(), "a resize relays out, and a relayout is owed a draw");
        }
    }

    @Test
    void requestFrameIsTheDoorForWhatTheTreeCannotSee() {
        try (Gui gui = new Gui(Atchung.create())) {
            gui.root().children(gui.box().width(Length.dp(40)).height(Length.dp(20)));
            frameDraws(gui);

            gui.requestFrame();
            gui.requestFrame();
            assertTrue(frameDraws(gui), "a requested frame draws, with no edit to the tree");
            assertFalse(frameDraws(gui), "and two requests before it arrived asked for one draw, not two");
        }
    }

    @Test
    void aThemeChangeDrawsBecauseTheRenderersChromeReadsItDirectly() {
        try (Gui gui = new Gui(Atchung.create())) {
            gui.root().children(gui.box().width(Length.dp(40)).height(Length.dp(20)));
            frameDraws(gui);
            gui.theme(Theme.LIGHT);
            assertTrue(frameDraws(gui), "scrollbars and shadows take their colour from the theme, not a prop");
        }
    }

    @Test
    void aFrameRunAndNotDrawnKeepsWhatItFoundUntilAsked() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node box = gui.box().width(Length.dp(40)).height(Length.dp(20));
            gui.root().children(box);
            frameDraws(gui);

            box.background(gui.theme().color(Role.DANGER));
            gui.frame(W, H, DrawOnChangeTest::noText);   // run, but the host did not ask (a minimized window)
            gui.frame(W, H, DrawOnChangeTest::noText);
            assertTrue(gui.takeDrawNeeded(), "the change is still owed to the first frame that is drawn");
        }
    }

    /**
     * The reconciler's one exception to "equal means unchanged": a collection posted again as the same object may
     * have been edited in place, and the reference cannot say whether it was.
     */
    @Test
    void theSameListObjectPostedAgainCountsAsAChangeAndAnEqualCopyDoesNot() {
        Reconciler r = new Reconciler(1L);
        r.create(1L, Map.of());
        r.takeDrawDirty();

        List<String> spans = new ArrayList<>(List.of("a"));
        r.setProp(1L, PropKey.SPANS, spans);
        assertTrue(r.takeDrawDirty(), "a new value is a change");

        spans.add("b");
        r.setProp(1L, PropKey.SPANS, spans);
        assertTrue(r.takeDrawDirty(), "the same list, edited in place and posted again, must still draw");

        r.setProp(1L, PropKey.SPANS, new ArrayList<>(spans));
        assertFalse(r.takeDrawDirty(), "a different list with equal contents is not a change");

        r.setProp(1L, PropKey.SPANS, null);
        assertTrue(r.takeDrawDirty(), "clearing a prop that was set is a change");
        r.setProp(1L, PropKey.SPANS, null);
        assertFalse(r.takeDrawDirty(), "clearing one that was already clear is not");
    }
}
