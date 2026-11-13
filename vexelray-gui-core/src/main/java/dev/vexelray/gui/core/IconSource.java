package dev.vexelray.gui.core;

import dev.vexelray.canvas.Color;

/**
 * Something that draws as a distance field at a size: an icon, to whatever names one without knowing the set. The
 * suite's {@code Icon} enum ({@code vexelray-gui-icons}) is the one every widget is given; core declares only this, so a
 * menu item or a tree action can carry an icon while core stays below the icon set.
 */
public interface IconSource {

    /** This icon as a field to hand {@link Node#field}, drawn {@code px} pixels square in {@code tint}. */
    ImageField field(float px, Color tint);

    /**
     * This icon as a drawing to hand {@link Node#sdg}, drawn {@code px} pixels square in {@code tint}, with
     * {@code field}'s look carried over: how an icon is drawn. By default its {@link #field} over the same atlas cell
     * ({@link ImageSdg#ofField}), so every icon set is drawn as SDG with nothing of its own to change.
     */
    default ImageSdg sdg(float px, Color tint) {
        return ImageSdg.ofField(field(px, tint));
    }

    /**
     * This icon as a field drawn {@code px} pixels square in {@code tint}, a paint: a {@code Role} follows the theme,
     * fading with the tree when it is swapped. A field's colour is only its tint, so this is {@link #field(float, Color)}
     * tinted again.
     */
    default ImageField field(float px, dev.vexelray.gui.core.style.Paint tint) {
        return field(px, tint.in(dev.vexelray.gui.core.style.Theme.DARK)).tint(tint);   // any colour: the tint replaces it
    }

    /** As {@link #sdg(float, Color)}, the tint a paint: a {@code Role} follows the theme. */
    default ImageSdg sdg(float px, dev.vexelray.gui.core.style.Paint tint) {
        return ImageSdg.ofField(field(px, tint));
    }
}
