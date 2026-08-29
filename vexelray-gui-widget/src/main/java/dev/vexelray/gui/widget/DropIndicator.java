package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.drop.DropEffect;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.draw.Picture;

/**
 * How a widget shows where a drop would land.
 *
 * <pre>{@code
 * tree.dropIndicator(DropIndicator.of(theme.color(Role.ACCENT)));      // the default, restated
 * tree.dropIndicator((where, effect) -> Picture.of(myOwnMark(where))); // or anything at all
 * tree.dropIndicator(DropIndicator.NONE);                              // or nothing
 * }</pre>
 *
 * <p><b>It is told where, and decides only how.</b> The rectangle comes from the same resolution that produced
 * the change the drop would perform, so an indicator cannot be drawn somewhere the drop would not happen — the
 * one failure that matters here, because the user finds out about it only after letting go. Everything left over
 * is appearance, and all of it is replaceable.
 *
 * <p>Coordinates are in the painted node's own pixel frame, as {@link Picture}'s always are: {@code (0, 0)} is
 * that node's top-left corner, so an indicator never needs to know where on screen the widget ended up, and the
 * same one works in a scrolled container.
 *
 * <h2>The shape says which kind of drop it is</h2>
 *
 * <p>A drop <em>between</em> two things is a thin rectangle on the seam; a drop <em>into</em> something is that
 * thing's whole box. An implementation can tell them apart by the rectangle it is handed, which is better than
 * being told in a separate argument that could disagree with it — the geometry is the fact, and a flag beside it
 * would be a second copy of the same fact free to drift.
 */
@FunctionalInterface
public interface DropIndicator {

    /** Draws nothing. For a widget that shows drops some other way, or not at all. */
    DropIndicator NONE = (where, effect) -> Picture.EMPTY;

    /** Above this many pixels tall, a rectangle is a thing rather than a seam between things. */
    float SEAM_MAX_PX = 4f;

    /**
     * The marks to paint for a drop that would land at {@code where}.
     *
     * @param where  the target, in the painted node's own pixel frame
     * @param effect what the drop would do — never {@link DropEffect#NONE}, since nothing is drawn for a point
     *               that accepts nothing
     */
    Picture paint(Rect where, DropEffect effect);

    /**
     * The default: a solid bar on a seam, and a ring around a box.
     *
     * <p>A ring rather than a wash, because a filled highlight over a row competes with the row's own selected
     * and hovered states — three fills on one strip, and the user has to learn which is which. An outline is a
     * different kind of mark from anything the row already paints, so it reads immediately as "this one" without
     * having to be brighter than everything else.
     */
    static DropIndicator of(Color color) {
        return (where, effect) -> where.h() <= SEAM_MAX_PX
                ? Picture.of(new Picture.Fill(where.x(), where.y(), where.w(), where.h(),
                        where.h() / 2f, where.h() / 2f, color))
                : Picture.of(new Picture.Outline(where.x(), where.y(), where.w(), where.h(),
                        4f, 4f, 2f, color));
    }
}
