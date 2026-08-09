package dev.vexelray.gui.core.layout;

/**
 * The ambient scale factors a {@link Length} resolves against. {@code rootEmPx} is the flat root em size in
 * pixels (no cascade — a deliberate choice); {@code zoom} is a user/app zoom; {@code dpi} a display scale;
 * {@code viewportW/H} the drawable size in pixels (for {@code vw/vh}).
 */
public record LayoutContext(float rootEmPx, float zoom, float dpi, float viewportW, float viewportH) {

    /** A 1:1 context with the given viewport and a 16px root em. */
    public static LayoutContext of(float viewportW, float viewportH) {
        return new LayoutContext(16f, 1f, 1f, viewportW, viewportH);
    }
}
