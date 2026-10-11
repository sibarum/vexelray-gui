package dev.vexelray.gui.core.drop;

/**
 * Hears about a drag in the frame it happens in, and hears how it ended.
 *
 * <pre>{@code
 * gui.watchDrag(deck, new DragWatch() {
 *     public void moved(DragState now) { card.floatAt(...); }              // lands this frame, not the next
 *     public void ended(DragState last, DropEffect performed) {
 *         if (performed.accepts()) settle(); else flyHome();                // a drop and a cancel look different
 *     }
 * });
 * }</pre>
 *
 * <h2>Why there is a second way to watch a drag</h2>
 *
 * <p>{@code Gui.drag()} publishes the drag as a {@code State}, latest-wins, after layout. That is the right thing
 * for something that draws <em>about</em> a drag — an indicator, a target lighting up — and it is one frame
 * behind, because a mutation made from it is applied by the next frame's drain. For the thing the pointer is
 * <em>carrying</em> that frame is visible: the card trails the hand, and on a fast flick it trails by a card's
 * width. This is delivered on the GUI thread during input dispatch, ahead of the drain, so a handle written here
 * is laid out and drawn in the frame whose pointer position it was computed from.
 *
 * <p>The other half is the ending. A published {@link DragState} goes to {@link DragState#NONE} whether the user
 * dropped or cancelled, so nothing reading it can tell the two apart — and they are opposite animations: a drop
 * settles the thing where it was put, a cancel returns it to where it came from. {@link DragSession#commit} says
 * the return is the caller's to animate; this is how the caller finds out it is owed one.
 *
 * <h2>Rules</h2>
 *
 * <p>It runs while the framework holds the frame, like every {@code *Ui} seam: mutate handles and return. It is
 * told about <b>every</b> drag in the tree, not only those over its node — a source has to keep drawing its card
 * after the pointer has left it, and a target has to close the gap it opened when the pointer walks off — so a
 * watch decides for itself whether the drag is one it is concerned with. The node is only what bounds the
 * registration's lifetime: it is dropped with the node, as every other per-node handler is.
 *
 * <p>Every watch sees the same {@link DragState} instance in a given frame, so several watches belonging to one
 * owner can tell by identity that they have already handled it.
 */
public interface DragWatch {

    /**
     * A press has landed somewhere a drag could begin, and the gesture is not yet a drag: the button is down and
     * the pointer has not yet travelled far enough, or been held long enough, to tell a drag from a click. Called
     * every frame of that, with where the press landed and where the pointer is now.
     *
     * <p>A state the user is in, and so one that should show: whatever was pressed is <em>held</em> and has not
     * come loose. The moment it does is the first {@link #moved}; if it never does, {@link #disarmed} — the
     * gesture was a click after all.
     *
     * @param pressX where the press landed, client-space px
     * @param pressY where the press landed, client-space px
     * @param x      where the pointer is now
     * @param y      where the pointer is now
     */
    default void armed(float pressX, float pressY, float x, float y) {
    }

    /** The press that {@link #armed} was about ended without becoming a drag: a click, or a press abandoned. */
    default void disarmed() {
    }

    /**
     * The drag as it stands this frame, after it was re-resolved against the tree. Called every frame a drag is
     * in flight, including the frame it began on — whether or not the pointer moved, since the tree under a
     * still pointer can move.
     */
    void moved(DragState now);

    /**
     * The drag is over. Told after a drop has moved focus to what was under the release point, so a watch that
     * knows the thing it carried is somewhere else — still flying to its place — may move focus to it.
     *
     * @param last      the drag as it was when it ended: where the pointer was released, and what was resolved
     *                  there
     * @param performed what the release actually did. {@link DropEffect#NONE} for every way of doing nothing —
     *                  Escape, a release over nothing, a drop the user never saw, a tree with no history to record
     *                  into — and the resolved effect when a change was performed. Not necessarily
     *                  {@code last.effect()}: that is what <em>would</em> have happened, this is what did
     */
    void ended(DragState last, DropEffect performed);
}
