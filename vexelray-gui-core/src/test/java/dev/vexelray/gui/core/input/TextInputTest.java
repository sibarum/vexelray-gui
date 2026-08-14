package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.NodeKind;
import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The CharTyped text channel and caret-from-click, both routed by the dispatcher. */
class TextInputTest {

    private static final Topic<ClickEvent> CLICKS = Topic.of("test.clicks", ClickEvent.class);

    private static RetainedNode textNode(long id, String text, float x, float w) {
        RetainedNode n = new RetainedNode(id, NodeKind.TEXT);
        n.x = x;
        n.y = 0;
        n.w = w;
        n.h = 20;
        n.textSizePx = 16f;
        n.set(PropKey.TEXT, text);
        return n;
    }

    @Test
    void charTypedGoesToFocusedCharHandler() {
        Atchung bus = Atchung.create();
        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
        RetainedNode field = textNode(1, "", 0, 100);

        StringBuilder typed = new StringBuilder();
        d.onChar(1, cp -> typed.appendCodePoint(cp));
        d.focus(1);

        bus.publish(InputTopics.INPUT, new InputEvent.CharTyped('h', 0));
        bus.publish(InputTopics.INPUT, new InputEvent.CharTyped('i', 0));
        d.dispatch(field);

        assertEquals("hi", typed.toString(), "typed text reaches the focused editable node");
    }

    @Test
    void charTypedIgnoredWhenNotFocused() {
        Atchung bus = Atchung.create();
        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
        RetainedNode field = textNode(1, "", 0, 100);

        StringBuilder typed = new StringBuilder();
        d.onChar(1, cp -> typed.appendCodePoint(cp));
        // no focus

        bus.publish(InputTopics.INPUT, new InputEvent.CharTyped('x', 0));
        d.dispatch(field);

        assertEquals("", typed.toString(), "no focus means no text delivery");
    }

    @Test
    void clickPlacesCaretAtNearestOffset() {
        Atchung bus = Atchung.create();
        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
        RetainedNode field = textNode(1, "abcdef", 0, 100);

        // Fixed 10px-per-char measurer; text starts at x = node.x + pad (pad = min(10, w*0.25) = 10).
        TextMeasurer m = new TextMeasurer() {
            @Override
            public float intrinsic(RetainedNode n, Axis axis, float px) {
                return 0f;
            }

            @Override
            public int offsetAt(String text, float localX, float px) {
                return Math.max(0, Math.min(text.length(), Math.round(localX / 10f)));
            }
        };

        List<Integer> caretHits = new ArrayList<>();
        d.onCaretHit(1, caretHits::add);

        // Click at x=44: localX = 44 - (0 + 10) = 34 -> round(3.4) = offset 3.
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, 44, 5, 0));
        d.dispatch(field, m);

        assertEquals(List.of(3), caretHits, "click maps to the nearest character offset");
        assertEquals(1, d.focused(), "clicking the field also focuses it");
    }

    @Test
    void dragAfterPressExtendsSelectionToPointer() {
        Atchung bus = Atchung.create();
        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
        RetainedNode field = textNode(1, "abcdef", 0, 100);

        TextMeasurer m = new TextMeasurer() {
            @Override
            public float intrinsic(RetainedNode n, Axis axis, float px) {
                return 0f;
            }

            @Override
            public int offsetAt(String text, float localX, float px) {
                return Math.max(0, Math.min(text.length(), Math.round(localX / 10f)));
            }
        };

        List<Integer> anchors = new ArrayList<>();
        List<Integer> drags = new ArrayList<>();
        d.onCaretHit(1, anchors::add);
        d.onCaretDrag(1, drags::add);

        // Press at x=14 (localX=4 -> offset 0), drag to x=54 (localX=44 -> offset 4), release.
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, 14, 5, 0));
        bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved(54, 5, 40, 0, 0));
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.LEFT, 54, 5, 0));
        // A move after release must not extend anymore.
        bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved(94, 5, 40, 0, 0));
        d.dispatch(field, m);

        assertEquals(List.of(0), anchors, "press sets the selection anchor at the pressed offset");
        assertEquals(List.of(4), drags, "drag extends to the pointer offset; the post-release move is ignored");
    }
}
