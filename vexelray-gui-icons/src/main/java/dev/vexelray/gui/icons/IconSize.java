package dev.vexelray.gui.icons;

/**
 * Which of an icon's two drawings to use. The set draws most icons twice, rather than shrinking one: small on a
 * 16-unit pixel grid, with fewer parts and strokes on whole pixels, for true 16 px and up to {@value #SMALL_MAX} px;
 * large on a 64-unit grid, detailed, for toolbars and tiles.
 */
public enum IconSize {

    /** The 16-unit drawing: menus, trees, tabs, status bars, title bars. */
    SMALL,
    /** The 64-unit drawing: toolbars, tiles, empty states. */
    LARGE;

    /** The largest size, in pixels, the small drawing is chosen for. */
    public static final float SMALL_MAX = 24f;

    /** The drawing for an icon drawn {@code px} pixels square. */
    public static IconSize forPixels(float px) {
        return px <= SMALL_MAX ? SMALL : LARGE;
    }
}
