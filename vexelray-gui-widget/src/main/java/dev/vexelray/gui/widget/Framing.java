package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.style.Role;

/**
 * How a widget that holds content in a box of its own (a {@link TextField}, a {@link TreeView}) frames it: in a well,
 * sunk into what is around it, or flush with it.
 *
 * <p>A well is the default, for a field or a tree that stands on a panel among other controls. Set into a card that is
 * already the content's edge, as an editor's document or a navigator's tree is, a well is a second edge inside the
 * first: {@link #FLUSH} paints neither its fill nor its border, and keeps both their room, so a widget switched from
 * one to the other moves nothing (IDE framework-notes FN-5).
 */
public enum Framing {

    /** Sunk: the well's fill and a hairline round it. The default. */
    WELL(Role.WELL, Role.LINE),

    /** Flush with what is around it: nothing painted, the same room kept. */
    FLUSH(Role.NONE, Role.NONE);

    private final Role fill;
    private final Role edge;

    Framing(Role fill, Role edge) {
        this.fill = fill;
        this.edge = edge;
    }

    /** What the box is filled with. */
    Role fill() {
        return fill;
    }

    /** What its border is painted in. */
    Role edge() {
        return edge;
    }
}
