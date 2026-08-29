package dev.vexelray.gui.widget;

import java.util.Objects;

/**
 * Where a dragged item would land in a tree, said relative to an item already in it.
 *
 * <p>Relative rather than absolute, and that is the whole of the design. "Under parent P at index 4" is only
 * meaningful while P's children are what they were when it was computed — one sibling removed elsewhere and index
 * 4 is a different child. "After Q" survives that, because Q is a thing rather than a count of things, and it is
 * still the right answer after any edit that did not remove Q itself. A tree reorder is undone minutes later,
 * after other edits, so the difference is not hypothetical.
 *
 * @param reference the item the placement is measured against; null only with {@link Relation#INTO}, meaning the
 *                  root of the tree — the answer for the empty space below the last row
 * @param relation  which side of it, or inside it
 */
public record Placement<T>(T reference, Relation relation) {

    /** Which side of the reference, or inside it. */
    public enum Relation {

        /** Immediately above the reference, as its sibling. */
        BEFORE,

        /** Immediately below the reference, as its sibling. */
        AFTER,

        /** As a child of the reference — or, with a null reference, of the root. */
        INTO
    }

    public Placement {
        Objects.requireNonNull(relation, "relation");
        if (reference == null && relation != Relation.INTO) {
            throw new IllegalArgumentException(
                    "only INTO may have a null reference (the tree root); got " + relation);
        }
    }

    /** Immediately above {@code reference}. */
    public static <T> Placement<T> before(T reference) {
        return new Placement<>(Objects.requireNonNull(reference, "reference"), Relation.BEFORE);
    }

    /** Immediately below {@code reference}. */
    public static <T> Placement<T> after(T reference) {
        return new Placement<>(Objects.requireNonNull(reference, "reference"), Relation.AFTER);
    }

    /** As a child of {@code reference}. */
    public static <T> Placement<T> into(T reference) {
        return new Placement<>(Objects.requireNonNull(reference, "reference"), Relation.INTO);
    }

    /** As a child of the tree's root — the placement for the space below the last row. */
    public static <T> Placement<T> intoRoot() {
        return new Placement<>(null, Relation.INTO);
    }

    /** Whether this places the item at the top level. */
    public boolean isRoot() {
        return reference == null;
    }
}
