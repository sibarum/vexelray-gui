package dev.vexelray.gui.core.layout;

/**
 * A rectangle a floating node must not cover ({@code Node.keepClear}): the control a tooltip describes, the row a
 * submenu opens from. Offsets and size are from the float's parent's border-box origin, as {@code Node.floatAt}'s are.
 *
 * <p><b>One invariant, not a list of sides.</b> A float is placed at its own offsets and kept inside its parent, as
 * every float is. When the parent's edge would push it back over this rectangle — a tooltip under a button in a
 * bottom bar, slid up by the clamp onto the button — it goes to the other side of the rectangle instead, at the same
 * distance it asked for: first the far side along the axis it was placed on (below becomes above), then either side
 * along the other axis. A side is taken only if the float fits there whole; it is then kept inside the parent along
 * the other axis only, which cannot bring it back over the rectangle. Where no side has room — a float larger than the
 * parent less the rectangle — it takes the side with the most room, and covering the rectangle there is the parent
 * being too small, not a placement.
 */
public record KeepClear(Length x, Length y, Length w, Length h) {

    /**
     * Where a {@code w x h} float asked to be at {@code (askX, askY)} goes inside a {@code roomW x roomH} parent, all
     * in px from the parent's border-box origin, with {@code ctx} resolving this rectangle. Answers {x, y}.
     */
    public float[] place(float askX, float askY, float w, float h, float roomW, float roomH, LayoutContext ctx) {
        float kx = x.scalarPx(ctx, roomW);
        float ky = y.scalarPx(ctx, roomH);
        float kr = kx + Math.max(0f, this.w.scalarPx(ctx, roomW));
        float kb = ky + Math.max(0f, this.h.scalarPx(ctx, roomH));
        float maxX = Math.max(0f, roomW - w);
        float maxY = Math.max(0f, roomH - h);

        float cx = clamp(askX, 0f, maxX);
        float cy = clamp(askY, 0f, maxY);
        if (kr <= kx || kb <= ky || !overlaps(cx, cy, w, h, kx, ky, kr, kb)) {
            return new float[]{cx, cy};
        }

        // How far from the rectangle it asked to be, along the axis that separates them most: the gap kept on
        // whichever side it lands, and the side tried first.
        float below = askY - kb;
        float above = ky - (askY + h);
        float right = askX - kr;
        float left = kx - (askX + w);
        float gap = Math.max(0f, Math.max(Math.max(below, above), Math.max(right, left)));
        boolean vertical = Math.max(below, above) >= Math.max(right, left);
        boolean far = vertical ? above > below : left > right;

        // The four sides as {position along their own axis, room left there}, in the order tried.
        float[][] sides = {
                {kb + gap, maxY - (kb + gap)},          // below
                {ky - gap - h, ky - gap - h},           // above
                {kr + gap, maxX - (kr + gap)},          // right
                {kx - gap - w, kx - gap - w},           // left
        };
        int first = (vertical ? 0 : 2) + (far ? 1 : 0);
        int[] order = {first, first ^ 1, (first + 2) % 4, ((first + 2) % 4) ^ 1};

        int best = order[0];
        for (int side : order) {
            if (sides[side][1] >= 0f) {
                best = side;
                break;
            }
            if (sides[side][1] > sides[best][1]) {
                best = side;
            }
        }
        float along = sides[best][0];
        return best < 2
                ? new float[]{cx, clamp(along, 0f, maxY)}
                : new float[]{clamp(along, 0f, maxX), cy};
    }

    private static boolean overlaps(float x, float y, float w, float h, float kx, float ky, float kr, float kb) {
        return x < kr && x + w > kx && y < kb && y + h > ky;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
