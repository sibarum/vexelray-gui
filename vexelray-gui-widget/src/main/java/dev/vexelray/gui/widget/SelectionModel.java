package dev.vexelray.gui.widget;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * What is selected, and where a range would grow from. One type because Shift-click, Ctrl-click, Shift+Arrow and a
 * rubber band are not four features — they are <b>three operations over one invariant</b>, and a widget that
 * re-derives them gets the fourth one subtly wrong.
 *
 * <h2>The invariant</h2>
 * A selection is a <b>base set</b>, an <b>anchor</b>, and an <b>extent</b> running from that anchor to the lead
 * item. What is selected is the base plus the extent, recomputed rather than accumulated — which is the whole
 * reason the anchor is state. Shift-clicking twice must not select the union of two ranges; the second range
 * <em>replaces</em> the first, from the same anchor. Widgets that keep only a set of selected items cannot express
 * that, so they grow a special case for "shift again" and it disagrees with what Shift+Arrow does.
 *
 * <p>The operations, and every gesture that is one of them:
 * <ul>
 *   <li>{@link #at} — a plain click, or an arrow key. The selection becomes that item; it is the new anchor.</li>
 *   <li>{@link #toggle} — Ctrl-click, Space. Membership flips, and the item becomes the anchor, so a range
 *       afterwards grows from where the pointer last was.</li>
 *   <li>{@link #extendTo} — Shift-click, Shift+Arrow, and every mouse-move of a rubber band. The extent is
 *       recomputed from the unchanged anchor to the new lead.</li>
 *   <li>{@link #lead} — Ctrl+Arrow, and an arrow key in a popup that is building a set. The cursor moves and the
 *       selection does not. It came last because it was found last: the three above all answer <em>what is
 *       chosen</em>, and a multi-select keyboard needs to be able to reach a row without choosing it before Space
 *       has anything to flip.</li>
 * </ul>
 *
 * <h2>Identity, not index</h2>
 * The model holds items, and asks for their order only when a range is being computed ({@link Order}). Indices go
 * stale the moment a row is inserted, removed, expanded or collapsed; identity survives all four. It is what lets
 * a tree keep its selection across a refresh, and what lets a virtualised list select an item whose row does not
 * exist yet.
 *
 * <h2>Mode</h2>
 * A {@link Mode} is a <b>capability</b>, not a branch: an operation the mode does not permit degrades to the
 * nearest one it does, here, once. A single-select list can therefore be given exactly the same handlers as an
 * explorer pane and cannot be talked into holding two items by a widget that forgot to check.
 *
 * @param <T> the item type — a row value, a tree item, an {@link dev.vexelray.gui.core.nav.Address}; anything with
 *            equality
 */
public final class SelectionModel<T> {

    /** How much of the vocabulary a selection permits. Capabilities rather than cases: nothing switches on this. */
    public enum Mode {

        /** At most one item. Every operation lands on {@link SelectionModel#at}. */
        SINGLE(false, false),

        /** Any number of items, chosen one at a time. A checkable list: Ctrl-click yes, Shift-range no. */
        MULTIPLE(true, false),

        /** The full vocabulary: an anchor, and ranges grown from it. */
        RANGE(true, true);

        private final boolean multiple;
        private final boolean ranges;

        Mode(boolean multiple, boolean ranges) {
            this.multiple = multiple;
            this.ranges = ranges;
        }

        /** Whether more than one item may be selected at once. */
        public boolean allowsMultiple() {
            return multiple;
        }

        /** Whether an extent may be grown from the anchor. */
        public boolean allowsRange() {
            return ranges;
        }
    }

    /**
     * The order a range runs along — the items as they are currently shown, which is the only order a user can
     * mean by "everything between these two". Supplied per call rather than held, because it changes underneath a
     * selection constantly: a tree's visible rows after an expand, a table's after a sort.
     *
     * <p>An item the order does not contain has no position, so a range to it selects nothing — the same answer as
     * shift-clicking a row that has just been filtered away, which is the honest one.
     */
    public interface Order<T> {

        /** How many items are in view. */
        int size();

        /** The item at {@code index}, which is in {@code [0, size())}. */
        T at(int index);

        /** Where {@code item} sits, or {@code -1} if it is not currently in view. */
        int indexOf(T item);

        /** The order of a list, read live — the list is not copied, so later changes are seen. */
        static <T> Order<T> of(List<T> items) {
            return new Order<>() {
                @Override
                public int size() {
                    return items.size();
                }

                @Override
                public T at(int index) {
                    return items.get(index);
                }

                @Override
                public int indexOf(T item) {
                    return items.indexOf(item);
                }
            };
        }
    }

    private final Mode mode;
    private final List<Consumer<Set<T>>> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<T>> leadListeners = new CopyOnWriteArrayList<>();

    /** The selection as it stood before the current extent began; the extent is laid over it, never merged in. */
    private final Set<T> base = new LinkedHashSet<>();

    /** Where a range grows from, and where it currently reaches. Null when nothing has been selected yet. */
    private T anchor;
    private T lead;

    /** The last computed selection — kept so a no-op operation can be recognised and not announced. */
    private Set<T> current = Set.of();

    public SelectionModel(Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    /** A selection that holds one item at a time. */
    public static <T> SelectionModel<T> single() {
        return new SelectionModel<>(Mode.SINGLE);
    }

    /** A selection with the full vocabulary: Ctrl to toggle, Shift to range from the anchor. */
    public static <T> SelectionModel<T> range() {
        return new SelectionModel<>(Mode.RANGE);
    }

    public Mode mode() {
        return mode;
    }

    /** What is selected, in the order items were added, as an unmodifiable snapshot. */
    public synchronized Set<T> selection() {
        return current;
    }

    /** Whether {@code item} is selected. */
    public synchronized boolean isSelected(T item) {
        return current.contains(item);
    }

    /** How many items are selected. */
    public synchronized int size() {
        return current.size();
    }

    public synchronized boolean isEmpty() {
        return current.isEmpty();
    }

    /**
     * The one selected item, or null — for the many callers that only ever want one. With several selected this is
     * the {@link #lead}, because that is the one the user touched last and the one every "act on the selection"
     * command means.
     */
    public synchronized T one() {
        return lead != null && current.contains(lead) ? lead : current.stream().findFirst().orElse(null);
    }

    /** Where a range would grow from: the item the last non-extending operation landed on. */
    public synchronized T anchor() {
        return anchor;
    }

    /** Where the current extent reaches — the item the user touched last. */
    public synchronized T lead() {
        return lead;
    }

    // ------------------------------------------------------------------ the three operations

    /** A plain click, or an arrow key: {@code item} alone is selected, and becomes the anchor. */
    public void at(T item) {
        Objects.requireNonNull(item, "item");
        change(() -> {
            base.clear();
            extent.clear();
            base.add(item);
            anchor = item;
            lead = item;
        });
    }

    /**
     * Ctrl-click, or Space: {@code item}'s membership flips and it becomes the anchor, so a range afterwards grows
     * from where the pointer last was. In {@link Mode#SINGLE} this is {@link #at}.
     */
    public void toggle(T item) {
        Objects.requireNonNull(item, "item");
        if (!mode.allowsMultiple()) {
            at(item);
            return;
        }
        change(() -> {
            collapseExtent();
            if (!base.remove(item)) {
                base.add(item);
            }
            anchor = item;
            lead = item;
        });
    }

    /**
     * Ctrl+Arrow, or an arrow key in a popup that is building a set: move the cursor to {@code item} <b>without
     * changing what is selected</b>. It becomes the anchor, so a Shift-range afterwards grows from where the
     * cursor now is and a {@link #toggle} lands on it.
     *
     * <p><b>A cursor is not a selection, and a list that cannot say so has no keyboard for multi-select.</b> Every
     * other operation here answers "what is chosen"; this one answers "where am I", which is the question Space
     * needs a prior answer to. Without it the only way to reach the fourth row is to select it, and reaching it is
     * exactly what must not select it.
     *
     * <p>In a mode with no multiple this is {@link #at}, by the same rule as everything else: a single-select list
     * has nowhere to put a cursor that is not the selection, so moving one <em>is</em> selecting. That degradation
     * is what lets a caller drive both modes with one call and get the right behaviour from each.
     */
    public void lead(T item) {
        Objects.requireNonNull(item, "item");
        if (!mode.allowsMultiple()) {
            at(item);
            return;
        }
        change(() -> {
            collapseExtent();   // the extent belonged to the old anchor; folding it in is what keeps it
            anchor = item;
            lead = item;
        });
    }

    /**
     * Shift-click, Shift+Arrow, or a rubber band dragging over {@code item}: the extent is recomputed from the
     * anchor to {@code item}, <b>replacing</b> whatever the previous extent covered rather than adding to it.
     *
     * <p>With no anchor yet this is {@link #at} — the first Shift-click in a fresh list selects one row, which is
     * what every file manager does. In a mode without ranges it degrades to the nearest operation that mode has.
     */
    public void extendTo(T item, Order<T> order) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(order, "order");
        if (!mode.allowsRange()) {
            if (mode.allowsMultiple()) {
                toggle(item);
            } else {
                at(item);
            }
            return;
        }
        if (anchor == null) {
            at(item);
            return;
        }
        change(() -> {
            lead = item;
            recomputeExtent(order);
        });
    }

    // ------------------------------------------------------------------ bulk changes

    /** Select everything in {@code order} — the Ctrl+A the widget claims. In a single-select model, the lead. */
    public void all(Order<T> order) {
        Objects.requireNonNull(order, "order");
        if (!mode.allowsMultiple()) {
            return;
        }
        change(() -> {
            collapseExtent();
            base.clear();
            for (int i = 0; i < order.size(); i++) {
                base.add(order.at(i));
            }
            anchor = order.size() > 0 ? order.at(0) : null;
            lead = order.size() > 0 ? order.at(order.size() - 1) : null;
        });
    }

    /** Nothing is selected, and there is no anchor — a click on the background. */
    public void clear() {
        change(() -> {
            base.clear();
            extent.clear();
            anchor = null;
            lead = null;
        });
    }

    /**
     * Select exactly {@code items}, as the application rather than the user — restoring a saved selection, or
     * following a change somewhere else in the model. The last item becomes the anchor, so the user's next
     * Shift-click grows from somewhere they can see.
     */
    public void set(Collection<? extends T> items) {
        Objects.requireNonNull(items, "items");
        change(() -> {
            base.clear();
            extent.clear();
            for (T item : items) {
                base.add(item);
                if (!mode.allowsMultiple()) {
                    break;
                }
            }
            anchor = base.isEmpty() ? null : lastOf(base);
            lead = anchor;
        });
    }

    /**
     * Drop items that are no longer there, keeping the rest — what a widget calls after its contents change. The
     * anchor survives if it does; otherwise a range afterwards grows from the lead that is left, and if neither
     * survived the next Shift-click starts a fresh selection rather than reaching to a row nobody can see.
     */
    public void retainAll(Collection<? extends T> live) {
        Objects.requireNonNull(live, "live");
        change(() -> {
            collapseExtent();
            base.retainAll(live);
            if (anchor != null && !live.contains(anchor)) {
                anchor = null;
            }
            if (lead != null && !live.contains(lead)) {
                lead = null;
            }
            if (anchor == null) {
                anchor = lead != null ? lead : (base.isEmpty() ? null : lastOf(base));
                lead = anchor;
            }
        });
    }

    // ------------------------------------------------------------------ notification

    /**
     * React to the selection changing. Called with the new selection, on whatever thread made the change, and only
     * when the selection actually differs — a click on an already-selected row announces nothing.
     */
    public SelectionModel<T> onChange(Consumer<Set<T>> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    /**
     * React to the <b>cursor</b> moving — the lead, whether or not the selection went with it. Called with the new
     * lead (null when there is none), on whatever thread moved it, and only when it actually moved.
     *
     * <p>Separate from {@link #onChange} because {@link #lead} is the operation that deliberately moves one
     * without the other: a listener that only watches the selection sees nothing when the cursor steps down a
     * multi-select list, and a row that draws the cursor would therefore draw it in the wrong place. Every
     * operation that moves the lead announces here, so "where the keyboard is" has one source whichever gesture
     * put it there.
     */
    public SelectionModel<T> onLeadChange(Consumer<T> listener) {
        leadListeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    // ------------------------------------------------------------------ internals

    /**
     * Apply a mutation, recompute the selection, and announce it if it moved. Every public operation goes through
     * here, which is what makes "the selection is base plus extent" true by construction rather than by discipline.
     */
    private void change(Runnable mutation) {
        Set<T> after;
        T leadAfter;
        boolean leadMoved;
        boolean selectionMoved;
        synchronized (this) {
            Set<T> before = current;
            T leadBefore = lead;
            mutation.run();
            after = computed();
            leadAfter = lead;
            leadMoved = !Objects.equals(leadBefore, leadAfter);
            selectionMoved = !after.equals(before);
            current = after;   // the extent may have moved without changing what it covers
        }
        // Two announcements, because they are two facts and {@link #lead} exists to change one without the other.
        // The cursor first: a listener that repaints rows wants the new cursor already in place when it is told
        // the selection moved, rather than painting twice.
        if (leadMoved) {
            for (Consumer<T> listener : leadListeners) {
                listener.accept(leadAfter);
            }
        }
        if (selectionMoved) {
            for (Consumer<Set<T>> listener : listeners) {
                listener.accept(after);
            }
        }
    }

    /** The base with the current extent laid over it. The union is computed, never accumulated. */
    private Set<T> computed() {
        Set<T> out = new LinkedHashSet<>(base);
        out.addAll(extent);
        return Collections.unmodifiableSet(out);
    }

    /**
     * The items the extent covers, from the anchor to the lead along {@code order}, inclusive of both. Replaces
     * whatever the extent covered before — which is the point of holding it separately from the base.
     *
     * <p>An endpoint that is not in the order has no position, so the extent is empty: shift-clicking a row that
     * has been filtered away reaches nothing rather than reaching somewhere arbitrary.
     */
    private void recomputeExtent(Order<T> order) {
        extent.clear();
        int from = order.indexOf(anchor);
        int to = order.indexOf(lead);
        if (from < 0 || to < 0) {
            return;
        }
        for (int i = Math.min(from, to); i <= Math.max(from, to); i++) {
            extent.add(order.at(i));
        }
    }

    /** The items the extent currently covers, computed against the order it was last extended along. */
    private final Set<T> extent = new LinkedHashSet<>();

    /** Fold the extent into the base, so the next operation starts from what is on screen now. */
    private void collapseExtent() {
        base.addAll(extent);
        extent.clear();
    }

    private static <T> T lastOf(Set<T> set) {
        T last = null;
        for (T t : set) {
            last = t;
        }
        return last;
    }
}
