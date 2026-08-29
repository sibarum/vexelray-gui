package dev.vexelray.gui.core.drop;

import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.layout.Rect;

import java.util.Objects;

/**
 * What would happen if the pointer were released where it is: the effect, where to show it, and the change that
 * would be made — as <b>one value</b>.
 *
 * <p>That the three arrive together is the entire design. A drop resolved in three places is a drop that can
 * lie: the indicator drawn between two rows while the commit inserts under a third, the cursor promising a copy
 * while the change performs a move. Each of those is a bug the user only discovers <em>after</em> letting go,
 * which is the worst possible moment, and each of them is unreachable when the picture and the mutation are
 * fields of the same record. A target that wants to change where the indicator sits has to change what the drop
 * does, because there is only one thing to change.
 *
 * <p>It is also why the change is carried rather than performed later from a remembered position. A position
 * outlives the layout that gave it meaning — one scroll, one expanded folder, and "row 4 of the visible list" is
 * a different row. The {@link Change} was built while the resolution was true and does not need re-deriving.
 *
 * @param effect    what releasing here would do; {@link DropEffect#NONE} for a point that accepts nothing
 * @param indicator where to show it, in the same client-space px as the pointer — the row that would receive it,
 *                  the seam that would open, the zero-height line between two things. Meaningful only when the
 *                  effect accepts
 * @param change    the mutation to hand to a {@code History} on release. Null exactly when the effect is
 *                  {@link DropEffect#NONE}
 */
public record Drop(DropEffect effect, Rect indicator, Change change) {

    /** Nothing would happen here. The answer for the gaps, and the resting value of a resolution. */
    public static final Drop NONE = new Drop(DropEffect.NONE, Rect.ZERO, null);

    public Drop {
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(indicator, "indicator");
        if (effect.accepts() == (change == null)) {
            // The two ways to build a drop that cannot be honoured: promising an effect with nothing to perform,
            // and carrying a mutation while telling the user nothing will happen. Both are caught here rather
            // than at release, because at release the user has already committed to the gesture.
            throw new IllegalArgumentException(effect.accepts()
                    ? "a drop that accepts must carry the change it would make: " + effect
                    : "a drop that accepts nothing must carry no change");
        }
    }

    /** A drop that would move the payload here. */
    public static Drop move(Rect indicator, Change change) {
        return new Drop(DropEffect.MOVE, indicator, change);
    }

    /** A drop that would copy the payload here. */
    public static Drop copy(Rect indicator, Change change) {
        return new Drop(DropEffect.COPY, indicator, change);
    }

    /** A drop that would place a reference to the payload here. */
    public static Drop link(Rect indicator, Change change) {
        return new Drop(DropEffect.LINK, indicator, change);
    }

    /** Whether releasing here would do anything. */
    public boolean accepts() {
        return effect.accepts();
    }
}
