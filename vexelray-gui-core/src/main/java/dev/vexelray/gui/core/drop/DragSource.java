package dev.vexelray.gui.core.drop;

/**
 * What a node offers up when a drag begins on it.
 *
 * <pre>{@code
 * gui.onDragSource(treeBody, (x, y) -> rowAt(y) instanceof TreeRow r ? Payload.of(ROW, r) : null);
 * }</pre>
 *
 * <p>Asked once, at the moment the gesture is recognised as a drag rather than a click — which is later than
 * the press, and deliberately so: at press time nobody yet knows whether this is a drag at all, and building a
 * payload for every click would be work done sixty times for each time it was wanted.
 *
 * <p>Returning null means "nothing here is draggable", and no session opens. That is the ordinary answer for the
 * empty space below the last row, and it is better than opening a drag of nothing and having every target
 * decline it — a drag that cannot land should never have started, because the user would see it start.
 *
 * <p>The point is where the press landed, not where the pointer is now. By the time this is called the pointer
 * has already travelled past the distance threshold, and a source that read the current position would pick up
 * whatever the pointer had wandered onto rather than the thing the user actually grabbed.
 */
@FunctionalInterface
public interface DragSource {

    /**
     * The payload for a drag beginning at this point, or null if nothing here can be dragged.
     *
     * @param x where the press landed, client-space px
     * @param y where the press landed, client-space px
     */
    Payload payloadAt(float x, float y);
}
