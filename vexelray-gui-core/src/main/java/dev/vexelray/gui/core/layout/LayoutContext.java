package dev.vexelray.gui.core.layout;

/**
 * The ambient scale factors a {@link Length} resolves against. {@code rootEmPx} is the flat root em size in
 * pixels; {@code zoom} is a user/app zoom; {@code dpi} a display scale; {@code viewportW/H} the drawable size in
 * pixels (for {@code vw/vh}); {@code emPx} is one {@link Length.Em} in pixels: the text size of the node being
 * resolved, or the root em ({@code rootEmPx · zoom · dpi}) where there is no node.
 */
public record LayoutContext(float rootEmPx, float zoom, float dpi, float viewportW, float viewportH, float emPx) {

    /** A context at the root, where there is no node and so one em is the root em. */
    public LayoutContext(float rootEmPx, float zoom, float dpi, float viewportW, float viewportH) {
        this(rootEmPx, zoom, dpi, viewportW, viewportH, rootEmPx * zoom * dpi);
    }

    /** A 1:1 context with the given viewport and a 16px root em. */
    public static LayoutContext of(float viewportW, float viewportH) {
        return new LayoutContext(16f, 1f, 1f, viewportW, viewportH);
    }

    /** One {@link Length.Rem} in pixels: {@code rootEmPx · zoom · dpi}. */
    public float remPx() {
        return rootEmPx * zoom * dpi;
    }

    /** This context with one em being {@code emPx}: the context a node whose text size is {@code emPx} resolves in. */
    public LayoutContext withEm(float emPx) {
        return new LayoutContext(rootEmPx, zoom, dpi, viewportW, viewportH, emPx);
    }

    /** Whether {@code other} has the same root, ignoring which node's em each carries. Allocates nothing. */
    public boolean sameRoot(LayoutContext other) {
        return other.rootEmPx == rootEmPx && other.zoom == zoom && other.dpi == dpi
                && other.viewportW == viewportW && other.viewportH == viewportH;
    }
}
