package dev.vexelray.gui.core.edit;

import dev.vexelray.gui.core.Node;

/**
 * What one Ctrl+Z or Ctrl+Y came to, as told to whoever presents it ({@code Gui.onUndo}). The presenter turns a
 * {@link Outcome#failed() failure} into something the user sees — a prompt when the target is
 * {@link Outcome#UNREACHABLE unreachable}, a cue on the {@link #target() target} when it {@link Outcome#REFUSED
 * refused} — and has nothing to show for the rest (docs/plans/undo.md §2).
 *
 * @param history the history the press was for
 * @param redo    whether it was a redo rather than an undo
 * @param outcome what happened
 * @param target  the node the entry edits, as a handle to play a cue on; null when the entry names none or the
 *                node could not be found
 */
public record UndoEvent(History history, boolean redo, Outcome outcome, Node target) {
}
