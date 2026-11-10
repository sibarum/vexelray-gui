package dev.vexelray.gui.core;

import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.os.HitRegions;

/**
 * The edge or corner a {@link WindowRegion#RESIZE resize grip} sizes the window from: what a node declares with
 * {@link Node#windowRegion(WindowEdge)}.
 *
 * <p>Each edge carries the two facts a grip needs and nothing more: the zone the window manager is told
 * ({@link #zone()}), and the arrow that says which way it drags ({@link #cursor()}). A grip widget sets that cursor on
 * its node like any other control, so the shape reaches the window through the ordinary cursor derivation.
 */
public enum WindowEdge {

    TOP(HitRegions.Zone.TOP, CursorShape.RESIZE_VERTICAL),
    BOTTOM(HitRegions.Zone.BOTTOM, CursorShape.RESIZE_VERTICAL),
    LEFT(HitRegions.Zone.LEFT, CursorShape.RESIZE_HORIZONTAL),
    RIGHT(HitRegions.Zone.RIGHT, CursorShape.RESIZE_HORIZONTAL),
    TOP_LEFT(HitRegions.Zone.TOP_LEFT, CursorShape.RESIZE_NWSE),
    TOP_RIGHT(HitRegions.Zone.TOP_RIGHT, CursorShape.RESIZE_NESW),
    BOTTOM_LEFT(HitRegions.Zone.BOTTOM_LEFT, CursorShape.RESIZE_NESW),
    BOTTOM_RIGHT(HitRegions.Zone.BOTTOM_RIGHT, CursorShape.RESIZE_NWSE);

    private final HitRegions.Zone zone;
    private final CursorShape cursor;

    WindowEdge(HitRegions.Zone zone, CursorShape cursor) {
        this.zone = zone;
        this.cursor = cursor;
    }

    /** What the window manager is told this grip is: the resize zone its drag sizes from. */
    public HitRegions.Zone zone() {
        return zone;
    }

    /** The arrow over this grip, held for the whole drag ({@link CursorShape#whileDragging}). */
    public CursorShape cursor() {
        return cursor;
    }

    /** The edge whose {@link #zone()} is {@code zone}, or {@code null} for a zone that is no edge (or {@code null}). */
    public static WindowEdge of(HitRegions.Zone zone) {
        for (WindowEdge e : values()) {
            if (e.zone == zone) {
                return e;
            }
        }
        return null;
    }
}
