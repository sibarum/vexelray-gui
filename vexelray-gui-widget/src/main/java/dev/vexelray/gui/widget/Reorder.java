package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.edit.Change;

/**
 * What moving an item to a {@link Placement} actually means — supplied by the application, because the tree does
 * not own the hierarchy.
 *
 * <pre>{@code
 * tree.reorderable((moved, where) -> switch (where.relation()) {
 *     case INTO   -> where.isRoot() ? model.reparent(moved, null) : model.reparent(moved, where.reference());
 *     case BEFORE -> model.insertBefore(moved, where.reference());
 *     case AFTER  -> model.insertAfter(moved, where.reference());
 * });
 * }</pre>
 *
 * <p>A {@code TreeView} reads its hierarchy through a {@code Source} and can therefore say <em>where</em> a drop
 * would land, in the tree's own terms, but not what putting it there does — that is a mutation of a model it can
 * only read. So the tree resolves the position and the application returns the change, and neither has to learn
 * the other's job.
 *
 * <p><b>Returning null refuses the placement</b>, and refusing is expected rather than exceptional. Dropping a
 * folder inside itself, moving into a read-only branch, a placement whose result would be the item exactly where
 * it already is — all of them are ordinary answers, and answering here means the user is told by the indicator
 * <em>before</em> releasing rather than by nothing happening afterwards.
 *
 * <p>Called on the GUI thread, once per frame while the pointer is over a row, so it must be cheap: it decides
 * what to draw, not what to do. The {@link Change} it returns is not applied until the user releases, and is then
 * recorded so the move can be undone.
 */
@FunctionalInterface
public interface Reorder<T> {

    /**
     * The change that puts {@code moved} at {@code where}, or null if that placement is not allowed.
     *
     * @param moved the item being dragged
     * @param where where it would land, relative to an item already in the tree
     */
    Change move(T moved, Placement<T> where);
}
