package dev.vexelray.gui.core.layout;

/**
 * A size along one axis, resolved to pixels only at layout time. Deliberately <b>no pixel unit</b>: sizes are
 * expressed relative to the root em, the viewport, or the containing box, so a UI scales with font size, zoom, DPI
 * and window size instead of being pinned to device pixels.
 *
 * <p>Fixed units resolve to a concrete pixel basis:
 * <ul>
 *   <li>{@link Em} / {@link Rem} — {@code v · rootEmPx · zoom · dpi} (flat root, no cascade)</li>
 *   <li>{@link Vw} / {@link Vh} — {@code v/100 · viewport width/height}</li>
 *   <li>{@link Percent} — {@code v/100 · basis}, where the basis is supplied by the caller (for width/height it is
 *       the parent's content extent along that axis; for padding/border/gap/corner it is the node's own border-box
 *       width; for a margin it is the parent's content extent along the main axis)</li>
 * </ul>
 * The flex keywords carry no fixed size: {@link #AUTO} means "size to intrinsic content"; {@link #FILL} takes all
 * remaining main-axis space (grow 1); {@link Grow} takes remaining space weighted by its factor. For scalar
 * properties (padding, margin, border, gap, corner, text size) the flex keywords are meaningless and resolve to 0.
 */
public sealed interface Length
        permits Length.Em, Length.Rem, Length.Dp, Length.Percent, Length.Vw, Length.Vh,
                Length.Grow, Length.Auto, Length.FillT {

    record Em(float v) implements Length { }
    record Rem(float v) implements Length { }

    /**
     * Density-independent pixels: {@code v · dpi}, honouring display density but <b>not</b> zoom. One dp is one
     * pixel at density 1.
     *
     * <p>This is not a pixel unit sneaking back in. §6's rule was doing two jobs at once — never pin to the device
     * grid, <em>and</em> scale with the user's zoom — and those are separable. {@code Dp} keeps the first, which is
     * the one whose violation breaks a UI on a dense display, and opts out of the second. Same distinction as
     * Android's {@code dp} vs {@code sp}.
     *
     * <p><b>For chrome that is not proportional to text</b>: card padding, gaps between panels, margins,
     * separators. Zoom is a request to make <em>content</em> legible, and tripling the frame around the content to
     * match means the user sees less of what they zoomed in to read. Text is the other case entirely — anything
     * sizing or containing glyphs stays in {@link Em}, or at 3× you get triple-height text inside an unchanged
     * inset, nearly touching its border.
     */
    record Dp(float v) implements Length { }
    /** Percentage of a caller-supplied basis (see the interface doc for which basis applies where). */
    record Percent(float v) implements Length { }
    record Vw(float v) implements Length { }
    record Vh(float v) implements Length { }
    /** Flex-grow weight; basis 0, shares remaining main-axis space in proportion to {@code factor}. */
    record Grow(float factor) implements Length { }
    /** Size to intrinsic content (grow 0). */
    record Auto() implements Length { }
    /** Take all remaining main-axis space (equivalent to {@code Grow(1)}). */
    record FillT() implements Length { }

    Length AUTO = new Auto();
    Length FILL = new FillT();
    /** Zero size — the default for padding, margin, border, gap and corner. */
    Length ZERO = new Em(0f);

    static Length em(float v) {
        return new Em(v);
    }

    static Length rem(float v) {
        return new Rem(v);
    }

    /** Density-independent pixels — honours DPI, ignores zoom. See {@link Dp} for when to reach for it. */
    static Length dp(float v) {
        return new Dp(v);
    }

    /** A percentage (0..100+) of the relevant basis; see the interface doc. */
    static Length percent(float v) {
        return new Percent(v);
    }

    static Length vw(float v) {
        return new Vw(v);
    }

    static Length vh(float v) {
        return new Vh(v);
    }

    static Length grow(float factor) {
        return new Grow(factor);
    }

    /**
     * Resolve a fixed length to pixels against {@code ctx} and {@code basisPx}, or return {@code -1} for the flex
     * keywords (Auto/Fill/Grow) so the layout decides. {@link Percent} uses {@code basisPx}; pass {@code 0} when no
     * basis is available (e.g. during intrinsic measure) and a percent resolves to 0.
     */
    default float resolve(LayoutContext ctx, float basisPx) {
        return switch (this) {
            case Em e -> e.v() * ctx.rootEmPx() * ctx.zoom() * ctx.dpi();
            case Rem r -> r.v() * ctx.rootEmPx() * ctx.zoom() * ctx.dpi();
            case Dp d -> d.v() * ctx.dpi();   // density only: no root em, and deliberately no zoom
            case Percent p -> p.v() / 100f * basisPx;
            case Vw w -> w.v() / 100f * ctx.viewportW();
            case Vh h -> h.v() / 100f * ctx.viewportH();
            case Grow g -> -1f;
            case Auto a -> -1f;
            case FillT f -> -1f;
        };
    }

    /**
     * Resolve to pixels for a <b>scalar</b> property (padding/margin/border/gap/corner/text size): fixed units
     * resolve as usual (never negative), and the flex keywords — meaningless here — resolve to 0.
     */
    default float scalarPx(LayoutContext ctx, float basisPx) {
        float px = resolve(ctx, basisPx);
        return px > 0f ? px : 0f;
    }

    /** The flex-grow weight this length implies (Fill = 1, Grow(f) = f, everything else 0). */
    default float growFactor() {
        return switch (this) {
            case FillT f -> 1f;
            case Grow g -> Math.max(0f, g.factor());
            default -> 0f;
        };
    }
}
