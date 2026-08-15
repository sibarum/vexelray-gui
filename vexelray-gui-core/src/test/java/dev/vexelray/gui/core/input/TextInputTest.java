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

    // Note: caret placement from a click/drag is no longer a dispatcher concern — it moved to the widget, which
    // maps the pointer to an offset via the layout read-model (node.layout().text()). See TextFieldTest /
    // TextMetricsTest in the widget module. The dispatcher just delivers raw pointer events (onDrag) and focus.
}
