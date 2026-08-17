package dev.vexelray.gui.widget;

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
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;

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

    /** The layout context this harness's frames resolve {@link dev.vexelray.gui.core.layout.Length}s against. */
    private static final dev.vexelray.gui.core.layout.LayoutContext CTX =
            dev.vexelray.gui.core.layout.LayoutContext.of(W, H);

    /**
     * The text insets in px, resolved from their {@code Length}s at this harness's context — so a test that needs
     * to click "at the start of the text" says so, rather than repeating a pixel value that only happens to be
     * right at 100% zoom.
     */
    static final float PAD_X = dev.vexelray.gui.core.text.TextMetrics.PAD_X.scalarPx(CTX, 0f);
    static final float PAD_Y = dev.vexelray.gui.core.text.TextMetrics.PAD_Y.scalarPx(CTX, 0f);

    /**
     * An {@link Executor} that queues tasks instead of running them, so a test can release input handlers one at
     * a time and interleave them with other work — reproducing a specific ordering / race deterministically.
     */
    static final class ManualExecutor implements Executor {
        private final Deque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable r) {
            queue.add(r);
        }

        /** Run the next queued task; @return false if the queue was empty. */
        boolean runOne() {
            Runnable r = queue.poll();
            if (r == null) {
                return false;
            }
            r.run();
            return true;
        }

        /** Run all queued tasks (including any they enqueue); @return how many ran. */
        int drain() {
            int n = 0;
            while (runOne()) {
                n++;
            }
            return n;
        }

        int pending() {
            return queue.size();
        }
    }

    final Atchung bus = Atchung.create();
    /** Non-null only in manual mode ({@link #manual()}); the queue of deferred input handlers. */
    final ManualExecutor tasks;
    final Gui gui;

    HeadlessGui() {
        this(Mode.SYNCHRONOUS);
    }

    /** How input handlers reach their widget — the property under test in {@link HandlerOrderingTest}. */
    private enum Mode { SYNCHRONOUS, MANUAL, THREADED }

    private HeadlessGui(Mode mode) {
        this.tasks = mode == Mode.MANUAL ? new ManualExecutor() : null;
        // Synchronous mode runs handlers inline during dispatch; manual mode queues them for the test to release;
        // threaded mode uses Gui's own worker pool — the production path, and the only one that can expose an
        // ordering defect in the handoff (see HandlerOrderingTest).
        this.gui = switch (mode) {
            case MANUAL -> new Gui(bus, tasks);
            case SYNCHRONOUS -> new Gui(bus, Runnable::run);
            case THREADED -> new Gui(bus);
        };
    }

    /** A harness whose input handlers are queued, not run — for deterministic interleaving via {@link #tasks}. */
    static HeadlessGui manual() {
        return new HeadlessGui(Mode.MANUAL);
    }

    /**
     * A harness that dispatches input handlers on {@link Gui}'s <b>real worker pool</b>, exactly as an application
     * does. Every other mode collapses the handoff onto one thread, which is what makes them deterministic — and
     * also what makes them structurally unable to observe whether delivery order survives it. A widget whose state
     * transitions depend on arrival order has to be tested here or not at all.
     */
    static HeadlessGui threaded() {
        return new HeadlessGui(Mode.THREADED);
    }

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

        @Override
        public float[] caretAdvances(String text, float px) {
            int len = text == null ? 0 : text.length();
            float[] xs = new float[len + 1];
            for (int i = 0; i <= len; i++) {
                xs[i] = i * CELL;
            }
            return xs;
        }

        /**
         * Monospace line breaking: split on {@code '\n'}, then wrap every {@code floor(wrapWidth / CELL)}
         * characters. Deliberately character-wrapping rather than word-wrapping — a test asserting where a line
         * broke should be reading arithmetic it can do in its head, not the engine's word-break rules.
         */
        @Override
        public java.util.List<TextLayout.LineSpan> lineSpans(String text, float wrapWidth, float px) {
            String s = text == null ? "" : text;
            int perLine = wrapWidth > 0f ? Math.max(1, (int) Math.floor(wrapWidth / CELL)) : Integer.MAX_VALUE;
            java.util.List<TextLayout.LineSpan> out = new java.util.ArrayList<>();
            int start = 0;
            while (true) {
                int hard = s.indexOf('\n', start);
                int lineEnd = hard < 0 ? s.length() : hard;
                // Emit as many wrapped segments as this hard line needs (at least one, so empty lines survive).
                int at = start;
                do {
                    int end = Math.min(lineEnd, at > Integer.MAX_VALUE - perLine ? lineEnd : at + perLine);
                    out.add(new TextLayout.LineSpan(at, end, end == lineEnd));
                    at = end;
                } while (at < lineEnd);
                if (hard < 0) {
                    return out;
                }
                start = hard + 1;   // the '\n' itself belongs to no line
            }
        }
    };

    /**
     * Drain the bus + dispatch input + lay out — one GUI frame. In synchronous mode handlers ran inline during
     * dispatch; in manual mode this also drains the queued handlers (so the fluent helpers still work). For
     * interleaving control in manual mode, use {@link #dispatchOnly()} + {@link #tasks} instead.
     */
    HeadlessGui frame() {
        gui.frame(W, H, measurer);
        if (tasks != null) {
            tasks.drain();
        }
        return this;
    }

    /** Manual mode: dispatch a frame but leave the input handlers <em>queued</em> in {@link #tasks} for the test
     *  to release in a chosen order. */
    HeadlessGui dispatchOnly() {
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

    /** Publish a wheel scroll of {@code (dx, dy)} notches with the pointer at (x, y), then step a frame. */
    HeadlessGui wheel(double dx, double dy, float x, float y) {
        bus.publish(InputTopics.INPUT, new InputEvent.Scrolled(dx, dy, (int) x, (int) y, 0));
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
