package dev.vexelray.gui.icons;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.IconSource;
import dev.vexelray.gui.core.ImageField;

/**
 * An icon from a baked set: one drawing at each {@link IconSize}, or at one with it standing in for the other, as
 * distance fields in the set's atlas. {@link Icon} is the suite's set; an application's own set, baked by
 * {@code vexelray-gui-icons-maven-plugin} as an extension of it, is another enum implementing this, and goes anywhere
 * an {@code Icon} goes: an {@link IconView}, an {@code IconButton}, a menu item's mark, a tree row.
 */
public interface BakedIcon extends IconSource {

    /** The icon's name in its set: {@code save}, {@code pin-up}. */
    String file();

    /** Whether there is art drawn for {@code size}, rather than the other size standing in. */
    boolean drawn(IconSize size);

    /** This icon's art for {@code size} (or the other size's, if only that is drawn) as a field, in {@code tint}. */
    ImageField field(IconSize size, Color tint);

    /**
     * This icon as a field to hand {@code Node.field}, drawn {@code px} pixels square in {@code tint}: the small art at
     * {@link IconSize#SMALL_MAX} px and under, the large above, either standing in for the other where only one is
     * drawn.
     */
    @Override
    default ImageField field(float px, Color tint) {
        return field(IconSize.forPixels(px), tint);
    }
}
