package dev.vexelray.gui.icons;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.ImageField;
import dev.vexelray.gui.core.ImageRegion;
import dev.vexelray.gui.core.Pixels;
import dev.vexelray.text.AtlasPixels;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The atlas a baked set's icons are cells of, in the font atlas's layout ({@code VXPX}, width, height, deflated
 * RGBA8): large cells fill the top rows, small cells the rows from {@link #smallTop()} down. Each set's plugin-made
 * subclass is one instance, so the renderer uploads it once per device ({@link Pixels}); its size is known without
 * reading it, and its texels are read the first time a frame draws one of its icons.
 *
 * <p>The subclass opens the resource itself, with a constant name, so that a native image finds and includes it
 * with no resource configuration.
 */
public abstract class BakedAtlas implements Pixels {

    private final int width;
    private final int height;
    private final int smallTop;
    private final int largeInner;
    private final int largeSpread;
    private final int smallInner;
    private final int smallSpread;

    protected BakedAtlas(int width, int height, int smallTop, int largeInner, int largeSpread, int smallInner,
                         int smallSpread) {
        this.width = width;
        this.height = height;
        this.smallTop = smallTop;
        this.largeInner = largeInner;
        this.largeSpread = largeSpread;
        this.smallInner = smallInner;
        this.smallSpread = smallSpread;
    }

    /** The atlas's resource, opened; null if it is not on the class path. */
    protected abstract InputStream open();

    /** Where the resource is, for a message. */
    protected abstract String resource();

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    /** The first row of small cells, in texels from the top. */
    public int smallTop() {
        return smallTop;
    }

    @Override
    public byte[] rgba() {
        AtlasPixels pixels = read();
        if (pixels.width() != width || pixels.height() != height) {
            throw new IllegalStateException(resource() + " is " + pixels.width() + " x " + pixels.height()
                    + " and its icons were generated for " + width + " x " + height
                    + ": build again, so the two are made together");
        }
        return pixels.rgba();
    }

    /** The atlas's pixels as they are on the class path. */
    public AtlasPixels read() {
        try (InputStream in = open()) {
            if (in == null) {
                throw new IllegalStateException("an icon atlas is not on the class path: " + resource());
            }
            return AtlasPixels.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the icon atlas " + resource(), e);
        }
    }

    /** Large cell {@code cell} as a field in {@code tint}. */
    public ImageField large(int cell, Color tint) {
        int side = largeInner + 2 * largeSpread;
        int columns = width / side;
        return field(side, largeSpread, (cell % columns) * side, (cell / columns) * side, tint);
    }

    /** Small cell {@code cell} as a field in {@code tint}. */
    public ImageField small(int cell, Color tint) {
        int side = smallInner + 2 * smallSpread;
        int columns = width / side;
        return field(side, smallSpread, (cell % columns) * side, smallTop + (cell / columns) * side, tint);
    }

    private ImageField field(int side, int spread, int x, int y, Color tint) {
        ImageRegion region = new ImageRegion((float) x / width, (float) y / height,
                (float) (x + side) / width, (float) (y + side) / height);
        return new ImageField(this, region, side, spread, tint);
    }

    @Override
    public String toString() {
        return "icon atlas " + width + " x " + height + " (" + resource() + ")";
    }
}
