package dev.vexelray.gui.core;

import sibarum.atchung.State;

/**
 * Something a scrollbar can show and move, along one axis: a {@link ScrollRange} published as a {@code State}, and a
 * way to ask for a different offset.
 *
 * <p>Two halves because they travel differently. The range is what is true now, published by whoever owns it; an
 * offset is a request, which the owner clamps to what its content allows and then publishes. So a bar never writes
 * the offset it shows: it asks, and draws what comes back (docs/plans/scrolling.md §3).
 *
 * <p>A {@link Scroller} is one source per axis. Anything else that can be looked at a part at a time can be another
 * — a 3D view publishing how far it pans, a terminal its scrollback, a text leaf its lines — and a bar drives it
 * exactly as it drives a Scroller.
 */
public interface ScrollSource {

    /** The range, as it is now and as it changes. */
    State<ScrollRange> range();

    /** Ask for the view to start at {@code offset}. The owner clamps it to the range. */
    void scrollTo(float offset);

    /** Ask for the view to move by {@code delta} from where it is now. */
    default void scrollBy(float delta) {
        scrollTo(range().value().offset() + delta);
    }
}
