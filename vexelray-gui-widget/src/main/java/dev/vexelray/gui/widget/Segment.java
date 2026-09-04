package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;

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
 * Options are added as (label, value) so the thing that comes back out of {@link #onChange} is what the
 * application already calls it — {@code "sss"}, not {@code "SSS"}, and not an index that silently means something
 * else the moment an option is inserted. A value is compared by {@link Object#equals}, so an enum constant, a
 * record or a string all work and none of them needs to be a string first.
 *
 * <p>Reads flow out through {@link #onChange}; the handler runs on a worker thread, so it must not touch the
 * retained tree except through {@link Node} handles.
 */
public final class Segment<T> {

    private final Gui gui;
    private final Node bar;
    private final Map<T, Node> cells = new LinkedHashMap<>();
    private final List<T> order = new ArrayList<>();
    private volatile T selected;
    private volatile Consumer<T> onChange = v -> { };

    /** Build an empty segment on {@code gui}; add options with {@link #option}. */
    public Segment(Gui gui) {
        this.gui = gui;
        Theme theme = gui.theme();
        // A hairline gap between cells over a sunken ground is what separates them: the ground shows through as
        // the divider, so there is one border for the whole control rather than one per cell meeting its neighbour
        // at a doubled line.
        this.bar = gui.row().role("segment")
                .background(theme.color(Role.WELL))
                .border(Length.dp(1), theme.color(Role.EDGE))
                .corner(Length.rem(0.3f))
                .padding(Length.dp(1))
                .gap(Length.dp(1))
                .alignItems(AlignItems.CENTER)
                .scroll(false, false);
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
        gui.onClick(cell, () -> select(value));
        gui.onState(cell, s -> paintOne(value, s == InteractionState.HOVER));
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

    /** Select a value; also notifies {@link #onChange}. A value with no option is ignored. */
    public Segment<T> select(T value) {
        if (!cells.containsKey(value)) {
            return this;
        }
        this.selected = value;
        paint();
        onChange.accept(value);
        return this;
    }

    /** React to selection changes (fired on click and on {@link #select}). Runs on a worker thread. */
    public Segment<T> onChange(Consumer<T> handler) {
        this.onChange = handler == null ? v -> { } : handler;
        return this;
    }

    private void paint() {
        for (T value : order) {
            paintOne(value, false);
        }
    }

    private void paintOne(T value, boolean hover) {
        Theme theme = gui.theme();
        Node cell = cells.get(value);
        if (cell == null) {
            return;
        }
        boolean isSelected = value.equals(selected);
        // The selected cell is the only one with a fill, so the control reads at a glance from its silhouette
        // rather than from a text colour that has to be found first.
        cell.background(isSelected ? theme.color(Role.SELECTION)
                : hover ? theme.color(Role.CHROME) : theme.color(Role.NONE));
        cell.textColor(theme.color(isSelected ? Role.INK : hover ? Role.DIM : Role.FAINT));
    }
}
