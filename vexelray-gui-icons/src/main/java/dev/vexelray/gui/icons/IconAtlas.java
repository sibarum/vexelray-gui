package dev.vexelray.gui.icons;

import dev.vexelray.gui.core.Pixels;
import dev.vexelray.text.AtlasPixels;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The atlas every {@link Icon} is a cell of: {@code icons.rgba} on the class path, baked by
 * {@code vexelray-icons/tools/Bake.java} in the font atlas's layout. One instance, so the renderer uploads it once
 * per device ({@link Pixels}). Its size is known without reading it; its texels are read the first time a frame
 * draws an icon, and not kept here once the renderer has them.
 */
final class IconAtlas implements Pixels {

    static final IconAtlas INSTANCE = new IconAtlas();

    static final String RESOURCE = "/dev/vexelray/gui/icons/icons.rgba";

    private IconAtlas() {
    }

    @Override
    public int width() {
        return AtlasLayout.WIDTH;
    }

    @Override
    public int height() {
        return AtlasLayout.HEIGHT;
    }

    @Override
    public byte[] rgba() {
        AtlasPixels pixels = read();
        if (pixels.width() != AtlasLayout.WIDTH || pixels.height() != AtlasLayout.HEIGHT) {
            throw new IllegalStateException("the icon atlas is " + pixels.width() + " x " + pixels.height()
                    + " and Icon was generated for " + AtlasLayout.WIDTH + " x " + AtlasLayout.HEIGHT
                    + ": bake again, so the two are made together");
        }
        return pixels.rgba();
    }

    static AtlasPixels read() {
        try (InputStream in = IconAtlas.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("the icon atlas is not on the class path: " + RESOURCE);
            }
            return AtlasPixels.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the icon atlas " + RESOURCE, e);
        }
    }

    @Override
    public String toString() {
        return "icon atlas " + AtlasLayout.WIDTH + " x " + AtlasLayout.HEIGHT;
    }
}
