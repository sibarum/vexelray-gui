package dev.vexelray.gui.scroll;

import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.model.RetainedNode;
import sibarum.atchung.Atchung;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.MouseButton;

/**
 * A window-less {@link Gui} whose handlers run on the calling thread, driven by publishing pointer input and
 * stepping frames — the widget tests' harness, cut down to what a scrollbar needs: presses, drags and the wheel.
 */
final class Headless implements AutoCloseable {

    static {
        Atchung.onFatal(sibarum.atchung.Fatal.THROW);
    }

    static final float W = 400f;
    static final float H = 300f;

    final Atchung bus = Atchung.create();
    final Gui gui = new Gui(bus);
    private RetainedNode root;

    private static float noText(RetainedNode n, Axis axis, float px) {
        return 0f;
    }

    Headless frame() {
        root = Seams.of(gui).frame(W, H, Headless::noText);
        return this;
    }

    /** Two frames: one to dispatch input, one to lay out what its handlers asked for. */
    Headless settle() {
        return frame().frame();
    }

    Headless press(float x, float y) {
        bus.publish(gui.input(), new InputEvent.ButtonPressed(MouseButton.LEFT, (int) x, (int) y, 0));
        return settle();
    }

    Headless move(float x, float y) {
        bus.publish(gui.input(), new InputEvent.PointerMoved((int) x, (int) y, 0, 0, 0));
        return settle();
    }

    Headless release(float x, float y) {
        bus.publish(gui.input(), new InputEvent.ButtonReleased(MouseButton.LEFT, (int) x, (int) y, 0));
        return settle();
    }

    /** {@code notches} of the wheel with the pointer at (x, y); negative is down, as the OS reports it. */
    Headless wheel(double notches, float x, float y) {
        bus.publish(gui.input(), new InputEvent.Scrolled(0, notches, (int) x, (int) y, 0));
        return settle();
    }

    RetainedNode retained(Node handle) {
        return find(root, handle.id());
    }

    private static RetainedNode find(RetainedNode n, long id) {
        if (n == null || n.id == id) {
            return n;
        }
        for (RetainedNode child : n.children) {
            RetainedNode hit = find(child, id);
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
