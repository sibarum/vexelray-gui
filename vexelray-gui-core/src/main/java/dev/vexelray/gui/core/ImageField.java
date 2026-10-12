package dev.vexelray.gui.core;

import dev.vexelray.canvas.FieldStyle;
import dev.vexelray.canvas.shadelet.Look;
import dev.vexelray.target.ImageHandle;

/**
 * A shape a node draws from a signed distance field ({@link Node#field}): the texture, the {@code region} of it the
 * field fills, {@code side} texels square with {@code spread} texels of margin round the shape's square, and how it is
 * drawn.
 *
 * <p>The texture is a {@code SampledImage} the application uploaded, or a {@link Pixels} the renderer uploads itself:
 * the icon set's atlas is the second, one texture of many fields, each a region of it.
 *
 * <p>{@code look}, when there is one, is the shadelets that draw it: a field source, modifiers, and painters ending in
 * the core {@code fieldFill}, which draws {@code style} as it would alone. A look naming an effect the application's
 * canvas shader was not composed with draws without that effect, said once. Null draws {@code style} the canvas's own
 * way, as before looks.
 */
public record ImageField(ImageHandle texture, ImageRegion region, int side, int spread, FieldStyle style, Look look) {

    public ImageField {
        if (texture == null || region == null || style == null || side <= 2 * spread || spread < 0) {
            throw new IllegalArgumentException("a field " + side + " texels across with " + spread + " round it");
        }
    }

    /** Drawn by its style alone. */
    public ImageField(ImageHandle texture, ImageRegion region, int side, int spread, FieldStyle style) {
        this(texture, region, side, spread, style, null);
    }

    /** A field filling the whole of {@code texture}. */
    public ImageField(ImageHandle texture, int side, int spread, FieldStyle style) {
        this(texture, ImageRegion.WHOLE, side, spread, style);
    }

    /** The shape's own square, in texels. */
    public int inner() {
        return side - 2 * spread;
    }

    /** The margin as a share of the shape's square, each side. */
    public float margin() {
        return (float) spread / inner();
    }

    /** This field drawn another way: what a state change is, since the texture stays as it is. */
    public ImageField style(FieldStyle style) {
        return new ImageField(texture, region, side, spread, style, look);
    }

    /** This field drawn by {@code look}; null for its style alone. */
    public ImageField look(Look look) {
        return new ImageField(texture, region, side, spread, style, look);
    }
}
