package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.RetainedNode;
import sibarum.atchung.Atchung;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.MouseButton;

/**
 * A headless, deterministic GUI for widget tests — the payoff of routing every device event through Atchung.
 * There is no window, no Vulkan, and (crucially) no worker threads: the {@link Gui} is built with a same-thread
 * handler executor, so an input event published on the bus is dispatched and fully handled inside {@link #frame},
 * on the calling thread. A test therefore drives the widget purely by publishing input and stepping frames, with
 * exact control over what fires and in what order (so it can reproduce specific interleavings).
 *
 * <p>Text metrics come from a fixed monospace stub ({@value #CELL} px per character), so caret/selection/offset
 * geometry is exact and independent of any real font atlas.
 */
final class HeadlessGui implements AutoCloseable {

    static final float CELL = 10f;      // px advance per character (monospace stub)
    private static final float W = 800f;
    private static final float H = 600f;

    final Atchung bus = Atchung.create();
    final Gui gui = new Gui(bus, Runnable::run); // synchronous handlers → deterministic, no races

    private final TextMeasurer measurer = new TextMeasurer() {
        @Override
        public float intrinsic(RetainedNode node, Axis axis, float px) {
            String s = node.textString() == null ? "" : node.textString();
            return axis == Axis.HORIZONTAL ? s.length() * CELL : CELL;
        }

        @Override
        public int offsetAt(String text, float localX, float px) {
            if (text == null || text.isEmpty()) {
                return 0;
            }
            return Math.max(0, Math.min(text.length(), Math.round(localX / CELL)));
        }
    };

    /** Drain the bus + dispatch input + lay out — one GUI frame. Handlers run inline (same thread). */
    HeadlessGui frame() {
        gui.frame(W, H, measurer);
        return this;
    }

    /** Move keyboard focus to {@code node} (as a click would), so typed text and keys route to it. */
    HeadlessGui focus(Node node) {
        gui.focus(node);
        return this;
    }

    /** Publish a key-down edge (no frame). */
    HeadlessGui down(Key k) {
        bus.publish(InputTopics.INPUT, new InputEvent.KeyPressed(k, 0));
        return this;
    }

    /** Publish a key-up edge (no frame). */
    HeadlessGui up(Key k) {
        bus.publish(InputTopics.INPUT, new InputEvent.KeyReleased(k, 0));
        return this;
    }

    /** Press then (after a frame) release a key — a full tap that dispatches this frame. */
    HeadlessGui tap(Key k) {
        down(k).frame().up(k);
        return this;
    }

    /** Press a key while {@code held} modifiers are down, dispatch, then release everything. */
    HeadlessGui chord(Key k, Key... held) {
        for (Key h : held) {
            down(h);
        }
        down(k).frame();
        up(k);
        for (Key h : held) {
            up(h);
        }
        return this;
    }

    /** Type a string as CharTyped events (one frame dispatches them all). */
    HeadlessGui type(String s) {
        s.codePoints().forEach(cp -> bus.publish(InputTopics.INPUT, new InputEvent.CharTyped(cp, 0)));
        return frame();
    }

    /** Left-click at absolute (x, y): press + release, dispatched over a frame. */
    HeadlessGui click(float x, float y) {
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, (int) x, (int) y, 0));
        frame();
        bus.publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.LEFT, (int) x, (int) y, 0));
        return frame();
    }

    @Override
    public void close() {
        gui.close();
    }
}
