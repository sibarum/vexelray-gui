package dev.vexelray.gui.core.drop;

import dev.vexelray.gui.core.edit.History;
import sibarum.atchung.Committer;
import sibarum.atchung.State;

/**
 * What is held for a paste, and the drag in flight as a published read-model.
 *
 * <p>Small on purpose, and it is the pair that makes it a component rather than two loose fields: a drag and a
 * cut are the same change made two ways, so what is carried under the pointer and what is carried in hand belong
 * in one place and record into one history. {@code docs/reference/transfer.md} is where that reasoning lives.
 *
 * <p>The recognising, hit-testing and target resolution are not here and are not meant to be — those are the
 * dispatcher's, which owns every pointer gesture. This holds the state that outlives a gesture.
 */
public final class Transfers {

    /** What is held for a paste. */
    private volatile Transfer held = Transfer.NONE;

    /** Where drops and pastes record; the same stack, because they are the same change made two ways. */
    private volatile History history;

    /** The live drag, as a coalesced State — the read-model half of {@link DragSession}. */
    private final State<DragState> dragState;
    private final Committer<DragState, DragState> setDrag;

    /** What was last published, so an unchanged drag does not wake every subscriber sixty times a second. */
    private DragState lastPublished = DragState.NONE;

    public Transfers() {
        // Latest-wins is exactly right for a drag: a subscriber that missed an intermediate position has missed
        // nothing it could still have drawn.
        State.Builder<DragState> gb = State.of(DragState.NONE);
        this.setDrag = gb.mutation("set", (current, next) -> next);
        this.dragState = gb.build();
    }

    /** What is held for a paste, or {@link Transfer#NONE}. */
    public Transfer held() {
        return held;
    }

    /** Put something in hand for a paste, or {@link Transfer#NONE} to drop what is held. Safe from any thread. */
    public void hold(Transfer transfer) {
        this.held = transfer == null ? Transfer.NONE : transfer;
    }

    /** Where drops and pastes record, or null if nothing has said. */
    public History history() {
        return history;
    }

    /** Record drops and pastes into {@code history}. */
    public void history(History history) {
        this.history = history;
    }

    /** The live drag as a coalesced {@code State}, for a renderer drawing the ghost and the drop indicator. */
    public State<DragState> drag() {
        return dragState;
    }

    /**
     * Publish {@code live}, if it has changed since the last frame.
     *
     * <p>Called after layout and displacement, so the indicator a target resolved is published in the same frame
     * as the geometry it was resolved against — a subscriber drawing from it is never a frame out of step with
     * the rows it is drawing between.
     *
     * <p>On change only: a drag held still over one seam is the common case, and re-committing an identical
     * value sixty times a second would wake every subscriber for nothing. The record's equality is what makes
     * that comparison honest rather than a hand-written field-by-field check that forgets one.
     */
    public void publish(DragSession live) {
        DragState next = live == null
                ? DragState.NONE
                : new DragState(true, live.x(), live.y(), live.drop().effect(), live.drop().indicator());
        if (next.equals(lastPublished)) {
            return;
        }
        lastPublished = next;
        dragState.commit(setDrag, next);
    }
}
