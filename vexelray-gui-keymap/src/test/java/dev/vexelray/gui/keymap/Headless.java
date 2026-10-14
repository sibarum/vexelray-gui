package dev.vexelray.gui.keymap;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.text.TextLayout;
import sibarum.atchung.Atchung;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;

import java.util.List;

/**
 * A window-less {@link Gui} whose handlers run on the calling thread, driven by publishing input and stepping
 * frames — the same harness the widget tests use, cut down to what a key map needs: chords, and reading text.
 */
final class Headless implements AutoCloseable {

    static {
        Atchung.onFatal(sibarum.atchung.Fatal.THROW);
    }

    private static final float CELL = 10f;

    final Atchung bus = Atchung.create();
    final Gui gui = new Gui(bus, Runnable::run);
    private RetainedNode last;

    private final TextMeasurer measurer = new TextMeasurer() {
        @Override
        public float intrinsic(RetainedNode node, Axis axis, float px) {
            String s = node.textString() == null ? "" : node.textString();
            return axis == Axis.HORIZONTAL ? s.length() * CELL : CELL;
        }

        @Override
        public int offsetAt(String text, float localX, float px) {
            return text == null ? 0 : Math.max(0, Math.min(text.length(), Math.round(localX / CELL)));
        }

        @Override
        public float[] caretAdvances(String text, float px) {
            int len = text == null ? 0 : text.length();
            float[] xs = new float[len + 1];
            for (int i = 0; i <= len; i++) {
                xs[i] = i * CELL;
            }
            return xs;
        }

        @Override
        public List<TextLayout.LineSpan> lineSpans(String text, float wrapWidth, float px) {
            int len = text == null ? 0 : text.length();
            return List.of(new TextLayout.LineSpan(0, len, true));
        }
    };

    Headless frame() {
        last = gui.frame(800f, 600f, measurer);
        return this;
    }

    Headless down(Key k) {
        bus.publish(InputTopics.INPUT, new InputEvent.KeyPressed(k, 0));
        return this;
    }

    Headless up(Key k) {
        bus.publish(InputTopics.INPUT, new InputEvent.KeyReleased(k, 0));
        return this;
    }

    /** Press {@code k} with {@code held} down, dispatch, and release everything. */
    Headless chord(Key k, Key... held) {
        for (Key h : held) {
            down(h);
        }
        down(k).frame();
        up(k);
        for (Key h : held) {
            up(h);
        }
        return frame();
    }

    /** The text {@code node} shows as of the last frame. */
    String text(Node node) {
        RetainedNode n = find(last, node.id());
        return n == null ? null : n.textString();
    }

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

    @Override
    public void close() {
        gui.close();
    }
}
