package dev.vexelray.gui.core.drop;

import dev.vexelray.gui.core.layout.Rect;

/**
 * What a drag looks like right now, published on the bus so a widget can draw it.
 *
 * <p>A {@link DragSession} is the framework's own live state, mutated on the GUI thread inside the frame; this is
 * the immutable, latest-wins view of it that everything else reads — the same command/query split the layout
 * read-model uses, and for the same reason. A widget that reached into the session would be reading a value that
 * changes underneath it mid-paint, and would have to be on the GUI thread to do it at all.
 *
 * <p>It carries the resolved {@link #indicator} rather than leaving a widget to work out where the drop would go.
 * That is the same rule {@link Drop} exists to enforce, one layer out: the rectangle drawn and the change that
 * would be performed came from a single resolution, so an indicator cannot disagree with the drop it depicts —
 * and it stays true even when a widget draws it in a box other than the one that resolved it.
 *
 * @param active    whether a drag is in flight at all; every other field is meaningless when false
 * @param x         pointer x in client-space px
 * @param y         pointer y in client-space px
 * @param effect    what releasing now would do; {@link DropEffect#NONE} over a point that accepts nothing
 * @param indicator where to show it, in the same client-space px — meaningful only when {@code effect} accepts
 */
public record DragState(boolean active, float x, float y, DropEffect effect, Rect indicator) {

    /** No drag. The resting value, and what is published the moment one ends. */
    public static final DragState NONE = new DragState(false, 0f, 0f, DropEffect.NONE, Rect.ZERO);

    /** Whether a drag is in flight <em>and</em> would land somewhere. */
    public boolean accepts() {
        return active && effect.accepts();
    }
}
