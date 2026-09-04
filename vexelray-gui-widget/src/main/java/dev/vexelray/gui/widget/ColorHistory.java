package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Hex;
import sibarum.atchung.State;

import java.util.ArrayList;
import java.util.List;

/**
 * The colours recently chosen, most recent first — a bounded, de-duplicated MRU list, held as bus
 * {@link State} so anything that shows it updates without being told.
 *
 * <p><b>Not owned by the picker.</b> A recents strip is only useful because it is the <em>same</em> strip
 * everywhere: a colour set on a stroke should be one click away on the fill, and a picker that kept its own
 * history would give the user as many separate pasts as the application has pickers. So the history is a value
 * the application holds and hands to each {@link ColorPicker}, exactly as a document is. A picker built without
 * one gets a private history of its own, which is the right answer for the single-picker case and the wrong one
 * to make everybody opt out of.
 *
 * <p><b>De-duplicated at 8 bits per channel</b>, which is the resolution the colour is displayed, written and
 * stored at. Colours here arrive from a drag, so they are floats a fraction apart that no eye and no
 * {@code #rrggbb} can tell apart; equality on the raw components would fill the strip with one colour. A repeat
 * is a <em>move</em> to the front rather than a second entry — the point of the list is where a colour ranks, and
 * choosing it again is the strongest statement of rank there is.
 *
 * <p>Alpha is part of the identity: a colour and the same colour at half coverage are two different choices.
 */
public final class ColorHistory {

    /** How many colours a history keeps unless told otherwise — one strip's worth at a comfortable swatch size. */
    public static final int DEFAULT_CAPACITY = 12;

    private final int capacity;
    private final State<List<Color>> colors;
    private final sibarum.atchung.Committer<List<Color>, Color> record;

    /** An empty history of {@link #DEFAULT_CAPACITY}. */
    public ColorHistory() {
        this(DEFAULT_CAPACITY, List.of());
    }

    /** An empty history keeping {@code capacity} colours. */
    public ColorHistory(int capacity) {
        this(capacity, List.of());
    }

    /**
     * A history keeping {@code capacity} colours, pre-filled with {@code seed} (most recent first) — how a saved
     * palette is restored at startup.
     */
    public ColorHistory(int capacity, List<Color> seed) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1; got " + capacity);
        }
        this.capacity = capacity;
        List<Color> initial = List.of();
        for (int i = seed.size() - 1; i >= 0; i--) {
            initial = promote(initial, seed.get(i), capacity);        // oldest first, so the seed's order survives
        }
        State.Builder<List<Color>> builder = State.of(initial);
        // Relative, like every other mutation in this codebase: the reducer re-resolves against whatever the list
        // is when it wins, so two pickers recording at once cannot overwrite each other's entry.
        this.record = builder.mutation("use", (current, c) -> promote(current, c, capacity));
        this.colors = builder.build();
    }

    /** How many colours this keeps. Fixed for the life of the history, so a strip can reserve its slots once. */
    public int capacity() {
        return capacity;
    }

    /** The colours, most recent first, as bus state — {@code history.colors().onCommit(...)} to follow it. */
    public State<List<Color>> colors() {
        return colors;
    }

    /** The colours right now, most recent first. Never longer than {@link #capacity()}. */
    public List<Color> current() {
        return colors.value();
    }

    /**
     * Record that {@code color} was chosen: it moves to the front, and the oldest falls off the end if the list
     * was full. Safe from any thread.
     *
     * <p>Call this when a choice is <b>made</b>, not while it is being made — see {@link ColorPicker#onCommit}.
     * Every intermediate colour a drag passes through is a colour nobody chose.
     */
    public void use(Color color) {
        if (color != null) {
            colors.commit(record, color);
        }
    }

    private static List<Color> promote(List<Color> current, Color color, int capacity) {
        String key = Hex.format(color);
        List<Color> next = new ArrayList<>(Math.min(current.size() + 1, capacity));
        next.add(color);
        for (Color existing : current) {
            if (next.size() == capacity) {
                break;
            }
            if (!Hex.format(existing).equals(key)) {
                next.add(existing);
            }
        }
        return List.copyOf(next);
    }
}
