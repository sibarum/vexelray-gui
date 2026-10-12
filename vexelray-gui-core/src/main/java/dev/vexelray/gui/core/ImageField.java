package dev.vexelray.gui.core;

import dev.vexelray.canvas.FieldStyle;
import dev.vexelray.target.ImageHandle;

/**
 * A shape a node draws from a signed distance field ({@link Node#field}): the texture, the {@code region} of it the
 * field fills, {@code side} texels square with {@code spread} texels of margin round the shape's square, and how it is
 * drawn.
 *
 * <p>The texture is a {@code SampledImage} the application uploaded, or a {@link Pixels} the renderer uploads itself:
 * the icon set's atlas is the second, one texture of many fields, each a region of it.
 */
public record ImageField(ImageHandle texture, ImageRegion region, int side, int spread, FieldStyle style) {

    public ImageField {
        if (texture == null || region == null || style == null || side <= 2 * spread || spread < 0) {
            throw new IllegalArgumentException("a field " + side + " texels across with " + spread + " round it");
        }
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
        return new ImageField(texture, region, side, spread, style);
    }
}
