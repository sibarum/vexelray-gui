package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.model.RetainedNode;

/**
 * The edge a docked float keeps its parent's content away from ({@code Node.dock}). The float still floats, so showing
 * or hiding it moves nothing around the parent; the parent's own content stops short of the band between the float
 * and that edge — a find bar's band at the top, a scrollbar's at the right of a field (docs/plans/scrolling.md §7.3).
 *
 * <p>Each edge knows how far its band reaches, so the layout asks the dock rather than switching on it.
 */
public enum Dock {

    /** Content starts below the float. */
    TOP {
        @Override
        void reserve(Bands bands, RetainedNode parent, RetainedNode c) {
            bands.top = Math.max(bands.top, c.y + c.h - parent.y);
        }

        @Override
        void floor(Floor floor, float x, float y, float w, float h) {
            floor.top = Math.max(floor.top, y + h);
            floor.across = Math.max(floor.across, x + w);
        }
    },

    /** Content stops left of the float. */
    RIGHT {
        @Override
        void reserve(Bands bands, RetainedNode parent, RetainedNode c) {
            bands.right = Math.max(bands.right, parent.x + parent.w - c.x);
        }

        @Override
        void floor(Floor floor, float x, float y, float w, float h) {
            floor.right = Math.max(floor.right, w);
            floor.along = Math.max(floor.along, y + h);
        }
    },

    /** Content stops above the float. */
    BOTTOM {
        @Override
        void reserve(Bands bands, RetainedNode parent, RetainedNode c) {
            bands.bottom = Math.max(bands.bottom, parent.y + parent.h - c.y);
        }

        @Override
        void floor(Floor floor, float x, float y, float w, float h) {
            floor.bottom = Math.max(floor.bottom, h);
            floor.across = Math.max(floor.across, x + w);
        }
    };

    /** Widen {@code bands} by the band placed child {@code c} keeps clear along this edge of {@code parent}. */
    abstract void reserve(Bands bands, RetainedNode parent, RetainedNode c);

    /**
     * Raise {@code floor} by what a float docked on this edge needs of its parent: its least size {@code w × h} at
     * offsets {@code (x, y)}. A bar along the top or bottom needs its band in height and its whole length across; one
     * along the right needs its band in width and its whole length down.
     */
    abstract void floor(Floor floor, float x, float y, float w, float h);

    /** The smallest box a parent's docked floats all fit in, gathered edge by edge ({@link #floor}). */
    static final class Floor {
        float top;
        float bottom;
        float right;
        /** The longest bar along the top or bottom, offset included. */
        float across;
        /** The longest bar down the right, offset included. */
        float along;

        float width() {
            return Math.max(across, right);
        }

        float height() {
            return Math.max(top + bottom, along);
        }
    }

    /** How far in from each edge a parent's docked floats reach, in px from its border box. */
    static final class Bands {
        float top;
        float right;
        float bottom;

        /** Each band no larger than the box it is in. */
        Bands clampTo(RetainedNode n) {
            top = Math.min(top, n.h);
            bottom = Math.min(bottom, Math.max(0f, n.h - top));
            right = Math.min(right, n.w);
            return this;
        }
    }
}
