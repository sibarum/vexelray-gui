package dev.vexelray.gui.core.edit;

/**
 * What one Ctrl+Z or Ctrl+Y came to, as told to whoever presents it ({@code Gui.onUndo}). The presenter turns a
 * {@link Outcome#failed() failure} into something the user sees — a prompt when the target is
 * {@link Outcome#UNREACHABLE unreachable}, a cue on the {@link #node() node} when it {@link Outcome#REFUSED
 * refused} — and has nothing to show for the rest (docs/plans/undo.md §2).
 *
 * @param history the history the press was for
 * @param redo    whether it was a redo rather than an undo
 * @param outcome what happened
 * @param node    the target's node id, or {@link Target#NO_NODE} when the entry names none or it could not be found
 */
public record UndoEvent(History history, boolean redo, Outcome outcome, long node) {
}
