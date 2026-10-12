package dev.vexelray.gui.core;

import dev.vexelray.canvas.ImageFilter;

/**
 * How a node's image is scaled as it is drawn: the {@link ImageFilter}, and the texture's size in texels, which the
 * filter needs to find the texture's pixels and which a texture handle does not say ({@code ImageHandle} declares
 * nothing). Whoever sets the image knows its size, so whoever sets the image says this too.
 *
 * <p>Absent, an image is drawn with the sampler's own bilinear, as it always was.
 */
public record ImageScaling(ImageFilter filter, int textureWidth, int textureHeight) {

    public ImageScaling {
        if (filter == null || textureWidth <= 0 || textureHeight <= 0) {
            throw new IllegalArgumentException("a filter and a texture size: " + filter + " " + textureWidth + "x"
                    + textureHeight);
        }
    }
}
