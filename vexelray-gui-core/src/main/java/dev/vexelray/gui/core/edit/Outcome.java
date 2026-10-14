package dev.vexelray.gui.core.edit;

/**
 * What one undo or redo did. Every value but {@link #APPLIED} and {@link #REVEALING} is a failure, and a failure
 * moves nothing: the entry stays where it was and the other stack is untouched, so redo still works after an undo
 * failed (docs/plans/undo.md §2, rule 7).
 *
 * <p>{@link History} itself answers only {@link #APPLIED}, {@link #EMPTY}, {@link #CONFLICT} and
 * {@link #REFUSED}: it knows nothing about nodes. {@link #REVEALING} and {@link #UNREACHABLE} come from whatever
 * drives it against a tree, which takes the user to an entry's {@link Target} before letting it apply. They are
 * one type so that whoever presents a failure handles one vocabulary.
 */
public enum Outcome {

    /** The entry was applied and moved to the other stack. */
    APPLIED,

    /** The entry was not applied; the user is being taken to its target, and the next press applies it. */
    REVEALING,

    /** There was nothing to undo, or nothing to redo. Not shown to the user as an error. */
    EMPTY,

    /** The target cannot be navigated to: it has left the tree, or a container declined to reveal it. */
    UNREACHABLE,

    /** The target is not open to edits — disabled, or the change declined for a reason of its own. */
    REFUSED,

    /** The target no longer holds what this entry left behind, so applying it would act on the wrong state. */
    CONFLICT;

    /** Whether this outcome is one the user should be told about. */
    public boolean failed() {
        return this == UNREACHABLE || this == REFUSED || this == CONFLICT;
    }
}
