package dev.vexelray.gui.core;

/**
 * The part of a texture a node samples, in normalised coordinates — {@code (0,0)} the top-left texel and
 * {@code (1,1)} the bottom-right, whatever the texture's pixel size.
 *
 * <p>This is what makes <b>one texture serve many pictures</b>. An icon set is one sheet and a region per icon; an
 * animation is one sheet and a region per frame, advanced on a clock. Neither costs a second upload, a second
 * descriptor set, or a second run in the vertex buffer — consecutive draws of the same handle stay in one run, so
 * a sheet is also the cheapest way to draw twenty images at once.
 *
 * <p><b>Normalised rather than pixels</b>, because the region has to survive the texture it came from: a sheet
 * rebaked at 2x is the same regions over larger texels, and a caller that stored pixel rects would have to rewrite
 * every one of them. {@link #pixels} converts when a caller does know the texture's size — a packer's output, say —
 * and the conversion happens once, at the edge.
 *
 * <p>Coordinates outside {@code 0..1} are not an error: the sampler clamps to the edge, so an over-wide region
 * smears its border texels rather than wrapping or failing.
 */
public record ImageRegion(float u0, float v0, float u1, float v1) {

    /** The whole texture — what a node samples when it names no region. */
    public static final ImageRegion WHOLE = new ImageRegion(0f, 0f, 1f, 1f);

    /**
     * Cell {@code index} of a {@code columns} x {@code rows} grid, counted row-major from the top-left — the
     * sprite-sheet case, and the reason this type exists.
     *
     * <p>An animation is then {@code cell(frame, columns, rows)} with {@code frame} advanced on a clock, and the
     * only thing that changes between frames is four floats in a prop.
     */
    public static ImageRegion cell(int index, int columns, int rows) {
        if (columns <= 0 || rows <= 0) {
            throw new IllegalArgumentException("a sheet needs at least one cell: " + columns + "x" + rows);
        }
        if (index < 0 || index >= columns * rows) {
            throw new IllegalArgumentException(
                    "cell " + index + " is outside a " + columns + "x" + rows + " sheet");
        }
        float w = 1f / columns;
        float h = 1f / rows;
        int column = index % columns;
        int row = index / columns;
        return new ImageRegion(column * w, row * h, (column + 1) * w, (row + 1) * h);
    }

    /**
     * The pixel rectangle {@code (x, y, width, height)} within a texture of {@code textureWidth} x
     * {@code textureHeight} — for a caller reading a packer's output, which speaks in pixels.
     */
    public static ImageRegion pixels(int x, int y, int width, int height, int textureWidth, int textureHeight) {
        if (textureWidth <= 0 || textureHeight <= 0) {
            throw new IllegalArgumentException(
                    "a texture has no size to divide: " + textureWidth + "x" + textureHeight);
        }
        return new ImageRegion(
                (float) x / textureWidth, (float) y / textureHeight,
                (float) (x + width) / textureWidth, (float) (y + height) / textureHeight);
    }

    /** Whether this is the whole texture — what a renderer checks to take the cheaper overload. */
    public boolean whole() {
        return u0 == 0f && v0 == 0f && u1 == 1f && v1 == 1f;
    }
}
