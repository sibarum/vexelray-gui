package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.spi.Seams;
import sibarum.atchung.Topic;
import dev.vexelray.gui.core.Addressed;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A row of mutually exclusive options, all of them on show: {@code Solid | Additive | SSS}.
 *
 * <h2>Why this is not a menu, and not three switches</h2>
 *
 * A menu hides every option but the chosen one, which costs a click and a guess to find out what the alternatives
 * even are — fine for a long list, wrong for three words. Three switches say the wrong thing in the other
 * direction: nothing in their shape forbids turning two on, so the exclusivity lives in a handler and the user
 * finds out about it by being corrected. A segment is the shape that says <em>one of these</em> before it is
 * touched, which is why it is worth being its own widget rather than a styling of something else.
 *
 * <h2>The value is the caller's, and the label is only a label</h2>
 *
 * Options are added as (label, value) so the thing that comes back out in {@link #CHANGED} is what the
 * application already calls it — {@code "sss"}, not {@code "SSS"}, and not an index that silently means something
 * else the moment an option is inserted. A value is compared by {@link Object#equals}, so an enum constant, a
 * record or a string all work and none of them needs to be a string first.
 *
 * <p>A choice is published on {@link #CHANGED}, carrying the segment's {@link #address() address}: the last in a
 * frame, because it is state (vexelray-framework's {@code docs/threading.md}, T3.8).
 */
public final class Segment<T> {

    /** The value chosen, when the user (or {@link #select}) chooses one. The last in a frame. */
    public static final Topic<Changed> CHANGED =
            Addressed.state(Changed.class, Changed::segment);

    /** The segment at {@code segment} now has {@code value} chosen: the value its option was added with. */
    public record Changed(String segment, Object value) implements Addressed {
        @Override
        public Object address() {
            return segment;
        }
    }

    private final Gui gui;
    private final Node bar;
    private final Map<T, Node> cells = new LinkedHashMap<>();
    private final List<T> order = new ArrayList<>();
    private volatile T selected;
    private volatile Consumer<T> onChange = v -> { };
    private final WidgetName name;
    /** The way back from each choice, into the tree's history. */
    private final ValueUndo<T> undo;

    /** Build an empty segment on {@code gui}; add options with {@link #option}. */
    public Segment(Gui gui) {
        this.gui = gui;
        // A hairline gap between cells over a sunken ground is what separates them: the ground shows through as
        // the divider, so there is one border for the whole control rather than one per cell meeting its neighbour
        // at a doubled line.
        this.bar = gui.row().role("segment")
                .background(Role.WELL)
                .border(Length.dp(1), Role.EDGE)
                .corner(Length.rem(0.3f))
                .padding(Length.dp(1))
                .gap(Length.dp(1))
                .alignItems(AlignItems.CENTER)
                .scroll(false, false);
        this.name = new WidgetName(gui, "segment", bar);
        this.undo = new ValueUndo<>(gui, bar, () -> selected, v -> {
            show(v);
            changed(v);
        }, false);
    }

    /**
     * Whether choices are undoable: on by default, since a segment usually chooses a value. An application may
     * turn it off for one that switches a view, if it would rather those steps did not stand between the user and
     * what they changed — a call made case by case (docs/plans/undo.md §5).
     */
    public Segment<T> undoable(boolean undoable) {
        undo.enabled(undoable);
        return this;
    }

    /** Add an option. The first one added is selected unless {@link #select} says otherwise. */
    public Segment<T> option(String label, T value) {
        // Small, and smaller than the label beside it: a segment sits in a narrow inspector row, and at body size
        // three options plus their padding take the whole row and squeeze the label to nothing.
        Node cell = gui.text(label).role("segment-option")
                .textSize(Length.rem(0.62f))
                .wordWrap(false)
                .padding(Length.rem(0.18f), Length.rem(0.36f))
                .corner(Length.rem(0.2f))
                .align(dev.vexelray.text.TextLayout.HAlign.CENTER, dev.vexelray.text.TextLayout.VAlign.MIDDLE);
        cells.put(value, cell);
        order.add(value);
        gui.focusable(cell, true);
        gui.cursor(cell, CursorShape.POINTER);
        Seams.of(gui).onClick(cell, () -> select(value));
        Seams.of(gui).onState(cell, s -> paintOne(value, s == InteractionState.HOVER));
        bar.append(cell);
        if (selected == null) {
            selected = value;
        }
        paint();
        return this;
    }

    /** The node to place in a layout. */
    public Node node() {
        return bar;
    }

    /** The selected value, or null if no option has been added. */
    public T selected() {
        return selected;
    }

    /** Select a value, as the user would: publishes {@link #CHANGED}. A value with no option is ignored. */
    public Segment<T> select(T value) {
        if (!cells.containsKey(value)) {
            return this;
        }
        T was = selected;
        show(value);
        changed(value);
        undo.edited(was, value);
        return this;
    }

    /**
     * Show a selection the segment did not make, <b>without</b> publishing {@link #CHANGED}. A value with no
     * option is ignored.
     *
     * <p>A sync is not an edit. Where a panel re-reads its model and writes what it finds back into its rows,
     * {@link #select} tells the application the user just clicked this — so it writes to the model, the model
     * republishes the panel, and the panel syncs again, without bound. See {@link Toggle#show}.
     */
    public Segment<T> show(T value) {
        if (!cells.containsKey(value)) {
            return this;
        }
        this.selected = value;
        paint();
        return this;
    }

    /** Call this segment {@code name} in its tree ("units"): its {@link #address()} is the tree's name and this. */
    public Segment<T> name(String name) {
        this.name.name(name);
        return this;
    }

    /** Where this segment is on the bus: what its {@link Changed} carries. */
    public String address() {
        return name.address();
    }

    /** A value chosen: the hook, then the message. */
    private void changed(T value) {
        onChange.accept(value);
        gui.bus().publish(CHANGED, new Changed(name.address(), value));
    }

    /**
     * The widget library's hook on a choice, for a widget built from segments: run as the choice is made, and as well
     * as {@link #CHANGED} rather than instead. Package-private, because an application hears a segment by its message.
     */
    Segment<T> onChange(Consumer<T> handler) {
        this.onChange = handler == null ? v -> { } : handler;
        return this;
    }

    private void paint() {
        for (T value : order) {
            paintOne(value, false);
        }
    }

    private void paintOne(T value, boolean hover) {
        Node cell = cells.get(value);
        if (cell == null) {
            return;
        }
        boolean isSelected = value.equals(selected);
        // The selected cell is the only one with a fill, so the control reads at a glance from its silhouette
        // rather than from a text colour that has to be found first.
        cell.background(isSelected ? Role.SELECTION : hover ? Role.CHROME : Role.NONE);
        cell.textColor(isSelected ? Role.INK : hover ? Role.DIM : Role.FAINT);
    }
}
