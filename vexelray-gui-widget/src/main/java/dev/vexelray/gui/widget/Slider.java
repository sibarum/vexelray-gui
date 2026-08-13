package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;

import java.util.function.DoubleConsumer;

/**
 * A horizontal slider: a track with a thumb positioned by a 0..1 value. Dragging the track (anywhere on it) sets
 * the value from the pointer's fraction across the track, with pointer capture so the drag continues off the track
 * — built entirely on {@code Gui.onDrag}. The thumb is placed between two grow-weighted spacers ({@code grow(value)}
 * and {@code grow(1-value)}) that split the free space (track minus thumb), so the thumb lands exactly at the right
 * edge at 100% with no pixel math and no overflow.
 *
 * <p>Reads flow out through {@link #onChange}; the handler runs on a worker thread (the drag dispatch thread), so
 * it must not touch the retained tree except through {@link Node} handles (which are thread-safe).
 */
public final class Slider {

    private static final Color TRACK = Color.rgb(0x2b3346);
    private static final Color THUMB = Color.rgb(0x3aa0ff);

    private final Node track;
    private final Node leftSpacer;
    private final Node rightSpacer;
    private volatile float value;
    private volatile DoubleConsumer onChange = v -> { };

    /** Build a slider on {@code gui} with the given initial value (0..1). */
    public Slider(Gui gui, float initial) {
        this.value = clamp01(initial);
        // The two spacers grow in proportion value : (1-value), splitting the space left over by the thumb, so the
        // thumb's centre tracks the value and never pushes past the track edge.
        this.leftSpacer = gui.box().width(Length.grow(this.value)).height(Length.percent(100));
        this.rightSpacer = gui.box().width(Length.grow(1f - this.value)).height(Length.percent(100));
        Node thumb = gui.box().width(Length.rem(1.1f)).height(Length.percent(100))
                .background(THUMB).corner(Length.rem(0.55f));
        this.track = gui.row().height(Length.rem(1.1f)).background(TRACK).corner(Length.rem(0.55f))
                .alignItems(AlignItems.CENTER).scroll(false, false) // a slider never scrolls
                .children(leftSpacer, thumb, rightSpacer);
        gui.onDrag(track, e -> set(e.fractionX()));
    }

    /** The node to place in a layout (the track). */
    public Node node() {
        return track;
    }

    /** Current value, 0..1. */
    public float value() {
        return value;
    }

    /** Set the value programmatically (0..1); also notifies {@link #onChange}. */
    public Slider value(float v) {
        set(v);
        return this;
    }

    /** React to value changes (fired on drag and on {@link #value(float)}). Runs on a worker thread. */
    public Slider onChange(DoubleConsumer handler) {
        this.onChange = handler == null ? v -> { } : handler;
        return this;
    }

    private void set(float raw) {
        float v = clamp01(raw);
        this.value = v;
        leftSpacer.width(Length.grow(v));
        rightSpacer.width(Length.grow(1f - v));
        onChange.accept(v);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
