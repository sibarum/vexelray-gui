package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.spi.Seams;
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
     * A {@link TextField}'s full text inset: the text inset is the box-model one (border + declared padding +
     * the editable caret gutter), and a TextField carries a {@code rem(0.1)} border, so its glyphs start that
     * much further in than the bare gutter. Tests about field geometry measure from these.
     */
    static final float FIELD_BORDER = dev.vexelray.gui.core.layout.Length.rem(0.1f).scalarPx(CTX, 0f);
    static final float FIELD_PAD_X = PAD_X + FIELD_BORDER;
    static final float FIELD_PAD_Y = PAD_Y + FIELD_BORDER;

    static {
        // A test that publishes more input than a frame drains would otherwise hit Fatal.HALT and take the
        // surefire JVM down mid-suite, which surfaces as "the VM crashed" and names nothing. Under test the
        // fault is thrown instead, so the overflow arrives as a failing test that names the topic — the
        // behaviour is identical up to that last step, and the last step is the one a test cannot survive.
        sibarum.atchung.Atchung.onFatal(sibarum.atchung.Fatal.THROW);
    }

    final Atchung bus = Atchung.create();
    /**
     * The tree under test. Its input handlers run on this thread, in {@link #frame}, as they do in an application:
     * 0.2 has no handler pool, so the harness and production run handlers the same way and there is no mode to pick.
     */
    final Gui gui = new Gui(bus);

    /** The default text size in px, at which one cell is exactly {@link #CELL} wide. */
    private static final float BASE_TEXT_PX = 16f;

    /**
     * Cell width at text size {@code px}. A real glyph advance scales with the text size, and the stub has to as
     * well or it silently contradicts the thing it stands in for: under zoom the boxes and insets would grow while
     * the glyphs stayed put, and a test asserting proportion would fail on the harness rather than the code.
     * At the default 16px this is exactly {@link #CELL}, so unzoomed geometry is unchanged.
     */
    private static float cell(float px) {
        return CELL * px / BASE_TEXT_PX;
    }

    private final TextMeasurer measurer = new TextMeasurer() {
        @Override
        public float intrinsic(RetainedNode node, Axis axis, float px) {
            String s = node.textString() == null ? "" : node.textString();
            return axis == Axis.HORIZONTAL ? s.length() * cell(px) : cell(px);
        }

        @Override
        public int offsetAt(String text, float localX, float px) {
            if (text == null || text.isEmpty()) {
                return 0;
            }
            return Math.max(0, Math.min(text.length(), Math.round(localX / cell(px))));
        }

        @Override
        public float[] caretAdvances(String text, float px) {
            int len = text == null ? 0 : text.length();
            float[] xs = new float[len + 1];
            for (int i = 0; i <= len; i++) {
                xs[i] = i * cell(px);
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
            // Both the width and the cell scale with zoom, so the wrap point is invariant under it — which is
            // itself the right behaviour, and worth the stub getting right.
            int perLine = wrapWidth > 0f ? Math.max(1, (int) Math.floor(wrapWidth / cell(px))) : Integer.MAX_VALUE;
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

    /** Drain the bus, dispatch input (handlers run here, inline) and lay out: one GUI frame. */
    HeadlessGui frame() {
        lastRoot = Seams.of(gui).frame(W, H, measurer);
        return this;
    }

    /**
     * {@code n} frames: for what takes more than one, such as a tree's walk, which goes on from each level in the
     * frame after the level's reply lands.
     */
    HeadlessGui frames(int n) {
        for (int i = 0; i < n; i++) {
            frame();
        }
        return this;
    }

    /**
     * What a widget at {@code address} says on {@code topic}, as a view would hear it: collected in the widget tick of
     * each {@link #frame}, in the frame it was said.
     */
    <T extends dev.vexelray.gui.core.Addressed> java.util.List<T> heard(sibarum.atchung.Topic<T> topic,
                                                                     String address) {
        java.util.List<T> out = new java.util.ArrayList<>();
        gui.mailbox(topic, address, out::add);
        return out;
    }

    /** The retained tree the last frame laid out — the thing the renderer would walk. */
    private RetainedNode lastRoot;

    /**
     * The retained node behind {@code handle} as of the last frame, or null if it is not in the tree.
     *
     * <p>{@link Node} is a write-only handle and {@link Node#layout()} answers geometry, which leaves the purely
     * visual props — opacity, colours — with no other way to be asserted. A hidden node is still <em>in</em> the
     * retained tree (that is the whole difference between hiding and removing), so this reads one either way.
     */
    RetainedNode retained(Node handle) {
        return find(lastRoot, handle.id());
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

    /**
     * Lay out {@code other} — a second tree of the same application — at this harness's size and metrics, and
     * answer its root. For a widget that owns a tree of its own for another window: that tree is driven by a
     * frame loop this harness is not, so a test that wants to look at it has to step it itself.
     */
    RetainedNode frame(Gui other) {
        return Seams.of(other).frame(W, H, measurer);
    }

    /** Find {@code handle} in a tree laid out by {@link #frame(Gui)}, or null if it is not in it. */
    static RetainedNode find(RetainedNode root, Node handle) {
        return find(root, handle.id());
    }

    /** Move keyboard focus to {@code node} (as a click would), so typed text and keys route to it. */
    HeadlessGui focus(Node node) {
        gui.focus(node);
        return this;
    }

    /** Publish a key-down edge (no frame). */
    HeadlessGui down(Key k) {
        bus.publish(gui.input(), new InputEvent.KeyPressed(k, 0));
        return this;
    }

    /** Publish a key-up edge (no frame). */
    HeadlessGui up(Key k) {
        bus.publish(gui.input(), new InputEvent.KeyReleased(k, 0));
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
        s.codePoints().forEach(cp -> bus.publish(gui.input(), new InputEvent.CharTyped(cp, 0)));
        return frame();
    }

    /** Publish a wheel scroll of {@code (dx, dy)} notches with the pointer at (x, y), then step a frame. */
    HeadlessGui wheel(double dx, double dy, float x, float y) {
        bus.publish(gui.input(), new InputEvent.Scrolled(dx, dy, (int) x, (int) y, 0));
        return frame();
    }

    /** Left-click at absolute (x, y): press + release, dispatched over a frame. */
    HeadlessGui click(float x, float y) {
        bus.publish(gui.input(), new InputEvent.ButtonPressed(MouseButton.LEFT, (int) x, (int) y, 0));
        frame();
        bus.publish(gui.input(), new InputEvent.ButtonReleased(MouseButton.LEFT, (int) x, (int) y, 0));
        return frame();
    }

    /**
     * The harness's own pointer clock, for presses that have to be told apart in time. {@link #click} stamps its
     * presses zero, which the dispatcher reads as "no time to compare" — so plain clicks never join into a
     * double-click — and these advance a clock instead, a tenth of a second per press, well inside the window.
     */
    private long pointerNanos = 1_000_000_000L;

    /** Click {@code n} times at (x, y) in quick succession: a double-click for 2, a triple for 3. */
    HeadlessGui clicks(float x, float y, int n) {
        pressHeld(x, y, n);
        return release(x, y);
    }

    /**
     * Click {@code n - 1} times at (x, y) in quick succession and press an {@code n}th time, leaving the button
     * down — the start of a double-click-and-drag for 2. Follow with {@link #hover} to drag, {@link #release} to end.
     */
    HeadlessGui pressHeld(float x, float y, int n) {
        for (int i = 1; i < n; i++) {
            press(x, y);
            release(x, y);
        }
        return press(x, y);
    }

    /** A left press at (x, y), stamped on the pointer clock, dispatched. */
    HeadlessGui press(float x, float y) {
        pointerNanos += 100_000_000L;
        bus.publish(gui.input(), new InputEvent.ButtonPressed(MouseButton.LEFT, (int) x, (int) y, pointerNanos));
        return frame();
    }

    /** A left release at (x, y), dispatched. */
    HeadlessGui release(float x, float y) {
        bus.publish(gui.input(), new InputEvent.ButtonReleased(MouseButton.LEFT, (int) x, (int) y, pointerNanos));
        return frame();
    }

    /** Let the pointer clock run past the multi-click window, so the next press starts a sequence of its own. */
    HeadlessGui pause() {
        pointerNanos += 2_000_000_000L;
        return this;
    }

    /** Move the pointer to absolute (x, y) and dispatch — drives hover and interaction state. */
    HeadlessGui hover(float x, float y) {
        bus.publish(gui.input(), new InputEvent.PointerMoved((int) x, (int) y, 0, 0, 0));
        return frame();
    }

    /** Right-click at absolute (x, y): press + release, dispatched over a frame — the context-click gesture. */
    HeadlessGui rightClick(float x, float y) {
        bus.publish(gui.input(), new InputEvent.ButtonPressed(MouseButton.RIGHT, (int) x, (int) y, 0));
        frame();
        bus.publish(gui.input(), new InputEvent.ButtonReleased(MouseButton.RIGHT, (int) x, (int) y, 0));
        return frame();
    }

    @Override
    public void close() {
        gui.close();
    }
}
