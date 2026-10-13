package dev.vexelray.gui.core;

import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.shadelet.CoreShadelets;
import dev.vexelray.canvas.shadelet.Look;
import dev.vexelray.target.ImageHandle;

import java.util.ArrayList;
import java.util.List;

/**
 * A shape a node draws from a signed distance field ({@link Node#field}): the texture, the {@code region} of it the
 * field fills, {@code side} texels square with {@code spread} texels of margin round the shape's square, the colour
 * it is drawn in, and what else draws it.
 *
 * <p>The texture is a {@code SampledImage} the application uploaded, or a {@link Pixels} the renderer uploads itself:
 * the icon set's atlas is the second, one texture of many fields, each a region of it.
 *
 * <p>{@code look}, when there is one, is the shadelets that draw it: a field source, modifiers (a weight), and painters
 * front to back (an outline, the core {@code fieldFill} in {@code tint}, a {@code glow}, a {@code dropShadow}). A look
 * naming an effect the application's canvas shader was not composed with draws without that effect, said once. Null
 * draws the shape alone in {@code tint}.
 */
public record ImageField(ImageHandle texture, ImageRegion region, int side, int spread, Color tint, Look look) {

    public ImageField {
        if (texture == null || region == null || tint == null || side <= 2 * spread || spread < 0) {
            throw new IllegalArgumentException("a field " + side + " texels across with " + spread + " round it");
        }
    }

    /** The shape alone, in {@code tint}. */
    public ImageField(ImageHandle texture, ImageRegion region, int side, int spread, Color tint) {
        this(texture, region, side, spread, tint, null);
    }

    /** A field filling the whole of {@code texture}. */
    public ImageField(ImageHandle texture, int side, int spread, Color tint) {
        this(texture, ImageRegion.WHOLE, side, spread, tint);
    }

    /** The shape's own square, in texels. */
    public int inner() {
        return side - 2 * spread;
    }

    /** The margin as a share of the shape's square, each side: the room a look's effects have past the shape. */
    public float margin() {
        return (float) spread / inner();
    }

    /** This field in another colour: what a state change often is, since the texture stays as it is. */
    public ImageField tint(Color tint) {
        return new ImageField(texture, region, side, spread, tint, look);
    }

    /** This field drawn by {@code look}; null for the shape alone. */
    public ImageField look(Look look) {
        return new ImageField(texture, region, side, spread, tint, look);
    }

    /**
     * This field with a halo of its tint reaching {@code glow} px past its edge and a shadow {@code shadow} px soft
     * under it, at the size it is drawn; 0 for none of either. See {@link #effects(List, List, float, float)}.
     */
    public ImageField effects(float glow, float shadow) {
        return look(effects(List.of(), List.of(), glow, shadow));
    }

    /**
     * The look a field's effects make, front to back: {@code front} (an outline, say), the field's own fill, a glow of
     * the tint reaching {@code glow} px, a shadow {@code shadow} px soft; over the field source and {@code modifiers}
     * (a weight). The glow and the shadow are the core set's, so every application draws them. Null with none of
     * them, so the field is drawn as the shape alone.
     */
    public static Look effects(List<Look.Use> modifiers, List<Look.Use> front, float glow, float shadow) {
        if (modifiers.isEmpty() && front.isEmpty() && glow <= 0f && shadow <= 0f) {
            return null;
        }
        List<Look.Use> painters = new ArrayList<>(front);
        painters.add(Look.Use.of(CoreShadelets.FIELD_FILL.name()));
        if (glow > 0f) {
            painters.add(Look.Use.of(CoreShadelets.GLOW.name(), glow));
        }
        if (shadow > 0f) {
            painters.add(Look.Use.of(CoreShadelets.DROP_SHADOW.name(), shadow));
        }
        return new Look(Look.Use.of(CoreShadelets.FIELD.name()), modifiers, painters);
    }
}
