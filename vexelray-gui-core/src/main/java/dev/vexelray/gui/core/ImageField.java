package dev.vexelray.gui.core;

import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.shadelet.Look;
import dev.vexelray.target.ImageHandle;

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
}
