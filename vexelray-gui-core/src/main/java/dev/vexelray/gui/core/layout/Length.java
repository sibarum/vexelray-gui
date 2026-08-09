package dev.vexelray.gui.core.layout;

/**
 * A size along one axis, resolved to pixels only at layout time. A strict sum type: fixed lengths
 * ({@link Px}, {@link Em}, {@link Rem}, {@link Vw}, {@link Vh}) resolve to a concrete pixel basis;
 * {@link #AUTO} means "size to intrinsic content"; {@link #FILL} takes all remaining main-axis space
 * (flex-grow 1); {@link Grow} takes remaining space weighted by its factor.
 *
 * <p>Resolution (architecture.md §8): {@code em = v·rootEmPx·zoom·dpi}; {@code rem} = same (flat root,
 * no cascade); {@code vw/vh = v/100·viewport}. {@code em→px} happens only here, at layout.
 */
public sealed interface Length
        permits Length.Px, Length.Em, Length.Rem, Length.Vw, Length.Vh, Length.Grow, Length.Auto, Length.FillT {

    record Px(float v) implements Length { }
    record Em(float v) implements Length { }
    record Rem(float v) implements Length { }
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

    static Length px(float v) {
        return new Px(v);
    }

    static Length em(float v) {
        return new Em(v);
    }

    static Length rem(float v) {
        return new Rem(v);
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

    /** Resolve a fixed length to pixels, or return {@code -1} if this is Auto/Fill/Grow (flex decides). */
    default float fixedPx(LayoutContext ctx) {
        return switch (this) {
            case Px p -> p.v();
            case Em e -> e.v() * ctx.rootEmPx() * ctx.zoom() * ctx.dpi();
            case Rem r -> r.v() * ctx.rootEmPx() * ctx.zoom() * ctx.dpi();
            case Vw w -> w.v() / 100f * ctx.viewportW();
            case Vh h -> h.v() / 100f * ctx.viewportH();
            case Grow g -> -1f;
            case Auto a -> -1f;
            case FillT f -> -1f;
        };
    }

    /** The flex-grow weight this length implies (Fill = 1, Grow(f) = f, everything else 0). */
    default float growFactor() {
        return switch (this) {
            case FillT f -> 1f;
            case Grow g -> g.factor();
            default -> 0f;
        };
    }
}
