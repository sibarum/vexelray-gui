package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.drop.DropEffect;
import dev.vexelray.gui.core.edit.Change;

/**
 * What putting an item at a {@link Placement} actually means — supplied by the application, because the tree does
 * not own the hierarchy.
 *
 * <pre>{@code
 * tree.reorderable((moved, where, effect) -> switch (effect) {
 *     case MOVE -> model.move(moved, where);
 *     case COPY -> model.duplicate(moved, where);
 *     default   -> null;                            // LINK is not a thing this model has
 * });
 * }</pre>
 *
 * <p>A {@code TreeView} reads its hierarchy through a {@code Source} and can therefore say <em>where</em> a drop
 * would land, in the tree's own terms, but not what putting it there does — that is a mutation of a model it can
 * only read. So the tree resolves the position and the application returns the change, and neither has to learn
 * the other's job.
 *
 * <p><b>The effect is a question, not an instruction.</b> {@code MOVE} and {@code COPY} are genuinely different
 * changes — one relocates a thing, the other has to make one — and only the application knows whether its items
 * can be duplicated at all, or what a duplicate of one even is. Refusing an effect is an ordinary answer:
 * returning null for {@code COPY} is a model whose items are unique, and it reads to the user as a copy that
 * cannot be pasted there rather than as one that silently moved.
 *
 * <p><b>Returning null refuses the placement</b>, and refusing is expected rather than exceptional. Dropping a
 * folder inside itself, moving into a read-only branch, a placement whose result would be the item exactly where
 * it already is — all of them are ordinary answers, and answering here means the user is told by the indicator
 * <em>before</em> releasing rather than by nothing happening afterwards.
 *
 * <p>Called on the GUI thread, once per frame while the pointer is over a row, so it must be cheap: it decides
 * what to draw, not what to do. The {@link Change} it returns is not applied until the user releases — or, for a
 * paste, until the chord is pressed — and is then recorded so the transfer can be undone.
 */
@FunctionalInterface
public interface Reorder<T> {

    /**
     * The change that puts {@code moved} at {@code where} with {@code effect}, or null if that is not allowed.
     *
     * @param moved  the item being transferred
     * @param where  where it would land, relative to an item already in the tree
     * @param effect what landing there should mean — {@code MOVE} for a drag or a cut, {@code COPY} for a copy
     */
    Change move(T moved, Placement<T> where, DropEffect effect);
}
