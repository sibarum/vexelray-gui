package dev.vexelray.gui.fileicons;

import dev.vexelray.gui.core.Pixels;
import dev.vexelray.text.AtlasPixels;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The atlas every {@link FileSheet} and emblem is a cell of: {@code file-icons.rgba} on the class path, baked by
 * {@code vexelray-gui-icons-maven-plugin} in the font atlas's layout. One instance, so the renderer uploads it once
 * per device ({@link Pixels}), and only in an application that draws a file icon. Its size is known without reading
 * it; its texels are read the first time a frame draws one, and not kept here once the renderer has them.
 */
final class FileIconAtlas implements Pixels {

    static final FileIconAtlas INSTANCE = new FileIconAtlas();

    static final String RESOURCE = "/dev/vexelray/gui/fileicons/file-icons.rgba";

    private FileIconAtlas() {
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
            throw new IllegalStateException("the file-icon atlas is " + pixels.width() + " x " + pixels.height()
                    + " and FileFamily was generated for " + AtlasLayout.WIDTH + " x " + AtlasLayout.HEIGHT
                    + ": bake again, so the two are made together");
        }
        return pixels.rgba();
    }

    static AtlasPixels read() {
        try (InputStream in = FileIconAtlas.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("the file-icon atlas is not on the class path: " + RESOURCE);
            }
            return AtlasPixels.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the file-icon atlas " + RESOURCE, e);
        }
    }

    @Override
    public String toString() {
        return "file-icon atlas " + AtlasLayout.WIDTH + " x " + AtlasLayout.HEIGHT;
    }
}
