package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * The caret blink clock: <b>one</b> phase, shared by every text field on a {@link Gui}.
 *
 * <p>Blink is a property of time, not of a widget. Giving each field its own timer made the phase per-instance —
 * so two carets could never agree, and a screen of editable cells was a screen of threads, each running forever
 * with no way to stop it because the field it belonged to had no disposal at all. One clock per GUI, ticking only
 * while something is registered, is both the cheaper and the more correct model.
 *
 * <p>The thread is a daemon and exits once the last registration closes; the next registration starts a new one.
 */
final class CaretBlink {

    /** Half-period of the blink, in milliseconds. */
    private static final long BLINK_MILLIS = 530L;

    private static final Map<Gui, CaretBlink> CLOCKS = new ConcurrentHashMap<>();

    /** One registered caret: the node to drive, and whether its owner currently holds focus. */
    private record Entry(Node node, BooleanSupplier focused) {
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private volatile boolean phase = true;
    private Thread thread;

    /** A registered caret; {@link #close} removes it from the clock. */
    interface Registration extends AutoCloseable {

        /** Show the caret solid this instant — called after typing or caret motion, so motion feels responsive. */
        void wake();

        @Override
        void close();
    }

    private CaretBlink() {
    }

    /**
     * Drive {@code node}'s caret blink while {@code focused} reports true. The node's {@code caretOn} prop is set
     * only while focused, so an unfocused field publishes no mutations and costs the frame loop nothing.
     */
    static Registration register(Gui gui, Node node, BooleanSupplier focused) {
        CaretBlink clock = CLOCKS.computeIfAbsent(gui, g -> new CaretBlink());
        Entry entry = new Entry(node, focused);
        clock.entries.add(entry);
        clock.ensureRunning();
        return new Registration() {
            @Override
            public void wake() {
                clock.phase = true;
                if (focused.getAsBoolean()) {
                    node.caretOn(true);
                }
            }

            @Override
            public void close() {
                clock.entries.remove(entry);
            }
        };
    }

    private synchronized void ensureRunning() {
        if (thread != null && thread.isAlive()) {
            return;
        }
        thread = new Thread(this::run, "vexelray-caret-blink");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        try {
            while (!entries.isEmpty()) {
                Thread.sleep(BLINK_MILLIS);
                phase = !phase;
                boolean on = phase;
                for (Entry e : entries) {
                    if (e.focused().getAsBoolean()) {
                        e.node().caretOn(on);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
