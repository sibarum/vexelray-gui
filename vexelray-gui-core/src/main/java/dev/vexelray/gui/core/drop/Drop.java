package dev.vexelray.gui.core.drop;

import dev.vexelray.gui.core.layout.Rect;

import java.util.Objects;

/**
 * What would happen if the pointer were released where it is: the effect, where to show it, and what landing does
 * — as <b>one value</b>.
 *
 * <p>That the three arrive together is the entire design. A drop resolved in three places is a drop that can
 * lie: the indicator drawn between two rows while the landing inserts under a third, the cursor promising a copy
 * while the landing performs a move. Each of those is a bug the user only discovers <em>after</em> letting go,
 * which is the worst possible moment, and each of them is unreachable when the picture and the landing are fields
 * of the same record. A target that wants to change where the indicator sits has to change what the drop does,
 * because there is only one thing to change.
 *
 * <p>It is also why the landing is carried rather than worked out later from a remembered position. A position
 * outlives the layout that gave it meaning — one scroll, one expanded folder, and "row 4 of the visible list" is
 * a different row. The landing was built while the resolution was true and does not need re-deriving.
 *
 * <p><b>Landing is the widget library's, and says what happened.</b> A drop target is a seam of the widget library
 * ({@code Seams.onDrop}), so the landing runs in the dispatch on the GUI thread, and what it does is tell: a tree
 * publishes where the item was placed, and the application moves it in its own model, records it in its own history,
 * and says when the tree should look again (vexelray-framework's threading.md, T7.2). The GUI keeps no history of
 * drops: the change is the application's, so its undo is too.
 *
 * @param effect    what releasing here would do; {@link DropEffect#NONE} for a point that accepts nothing
 * @param indicator where to show it, in the same client-space px as the pointer — the row that would receive it,
 *                  the seam that would open, the zero-height line between two things. Meaningful only when the
 *                  effect accepts
 * @param landed    what releasing here does. Null exactly when the effect is {@link DropEffect#NONE}
 */
public record Drop(DropEffect effect, Rect indicator, Runnable landed) {

    /** Nothing would happen here. The answer for the gaps, and the resting value of a resolution. */
    public static final Drop NONE = new Drop(DropEffect.NONE, Rect.ZERO, null);

    public Drop {
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(indicator, "indicator");
        if (effect.accepts() == (landed == null)) {
            // The two ways to build a drop that cannot be honoured: promising an effect with nothing to perform,
            // and carrying a landing while telling the user nothing will happen. Both are caught here rather
            // than at release, because at release the user has already committed to the gesture.
            throw new IllegalArgumentException(effect.accepts()
                    ? "a drop that accepts must carry what landing does: " + effect
                    : "a drop that accepts nothing must carry no landing");
        }
    }

    /** A drop that would move the payload here. */
    public static Drop move(Rect indicator, Runnable landed) {
        return new Drop(DropEffect.MOVE, indicator, landed);
    }

    /** A drop that would copy the payload here. */
    public static Drop copy(Rect indicator, Runnable landed) {
        return new Drop(DropEffect.COPY, indicator, landed);
    }

    /** A drop that would place a reference to the payload here. */
    public static Drop link(Rect indicator, Runnable landed) {
        return new Drop(DropEffect.LINK, indicator, landed);
    }

    /** Whether releasing here would do anything. */
    public boolean accepts() {
        return effect.accepts();
    }
}
