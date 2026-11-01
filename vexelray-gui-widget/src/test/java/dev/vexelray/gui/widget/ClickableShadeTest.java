package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.NodeEvent;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Anything clickable answers the pointer with no code of its own: a node with a click handler, painted in a role,
 * lifts under the pointer and shows a press, as the cursor already turns to a hand over it. A node that hears its own
 * state shades itself, as every widget does, and is left alone.
 */
class ClickableShadeTest {

    @Test
    void aClickableBoxInARoleLiftsHoveredAndDeepensPressed() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node box = h.gui.box().width(Length.dp(80)).height(Length.dp(40)).background(Role.WELL)
                    .border(Length.dp(1), Role.LINE);
            h.gui.hear(box, NodeEvent.CLICK, c -> { });
            h.gui.root().children(box);
            h.frame();
            var r = box.layout().rect();
            float x = r.x() + r.w() / 2f;
            float y = r.y() + r.h() / 2f;
            var theme = h.gui.drawnTheme();
            assertEquals(theme.color(Role.WELL), h.retained(box).background(theme), "at rest, as it was painted");

            h.hover(x, y);
            assertEquals(theme.color(Role.WELL, InteractionState.HOVER), h.retained(box).background(theme));
            assertEquals(theme.color(Role.LINE, InteractionState.HOVER), h.retained(box).borderColor(theme));
            h.press(x, y);
            assertEquals(theme.color(Role.WELL, InteractionState.PRESSED), h.retained(box).background(theme),
                    "a press shows the frame the button goes down");
            h.release(x, y);
            h.hover(r.x() + r.w() + 50f, y);
            assertEquals(theme.color(Role.WELL), h.retained(box).background(theme), "and rests again once left");
        }
    }

    @Test
    void aClickableThatHearsItsOwnStateIsLeftToShadeItself() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node box = h.gui.box().width(Length.dp(80)).height(Length.dp(40)).background(Role.WELL);
            h.gui.hear(box, NodeEvent.CLICK, c -> { });
            h.gui.hear(box, NodeEvent.STATE, s -> { });
            h.gui.root().children(box);
            h.frame();
            var r = box.layout().rect();
            var theme = h.gui.drawnTheme();

            h.hover(r.x() + r.w() / 2f, r.y() + r.h() / 2f);
            assertEquals(theme.color(Role.WELL), h.retained(box).background(theme), "its look is its own to change");
        }
    }
}
