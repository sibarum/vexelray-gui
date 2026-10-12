package dev.vexelray.gui.core;

import dev.vexelray.target.ImageHandle;

/**
 * Pixels a node can show with no texture in hand: the renderer uploads them to whichever device draws them, the first
 * time it does, and keeps them for as long as that device lives. What the font atlas has always been, for anything
 * else drawn the same everywhere: the icon set's atlas is the reason it exists.
 *
 * <p>A widget is built from a {@link Gui}, which has no device, and an upload needs one. Asking every widget that
 * wants an icon for its window's {@code GuiApp} would put a texture in the hands of code that only wants a shape; this
 * keeps the texture with the renderer, which has the device already. A headless capture builds its own device, and
 * uploads its own copy, so a tree with icons captures as it presents.
 *
 * <p>Identity is the object's: hold one instance for one set of pixels (a constant, or a lazily made singleton), not
 * a new one per frame, or each frame uploads again. {@link #rgba()} is called once per device, on the GUI thread, and
 * may be as slow as reading a resource.
 */
public interface Pixels extends ImageHandle {

    /** Texels across. */
    int width();

    /** Texels down. */
    int height();

    /** The texels, straight RGBA8, top row first: {@code width() * height() * 4} bytes. */
    byte[] rgba();
}
