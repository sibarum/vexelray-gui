package dev.vexelray.gui.core.layout;

/**
 * The smallest client area a window showing a tree may be dragged to, in layout px: the larger, on each axis, of what
 * its docked chrome needs ({@link FlexLayout#frameFloor}) and the canvas floor it was given ({@code Gui.minSize}).
 *
 * <p>A derivation, like the hit regions: re-derived from each laid-out tree and pushed to the window by the host, so
 * a zoom that widens the title bar raises it and a narrower bar lowers it, with nothing registered. The window manager
 * enforces it during a drag; it never resizes a window by itself.
 *
 * @param width  the least client width, {@code 0} for none
 * @param height the least client height, {@code 0} for none
 */
public record WindowFloor(int width, int height) {

    /** No floor: what a tree with no docked chrome and no minimum asks for. */
    public static final WindowFloor NONE = new WindowFloor(0, 0);

    public WindowFloor {
        width = Math.max(0, width);
        height = Math.max(0, height);
    }
}
