package dev.vexelray.gui.core;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.draw.Picture;

/**
 * The drawing grid every caption glyph is authored on — the window controls and the instruments beside them — so
 * the strip reads as one family rather than seven icons from seven places.
 *
 * <p><b>One unit, one stroke.</b> A glyph is laid out in grid units {@code u} measured from the centre of its button,
 * where {@code u} is one density-independent pixel of the 32dp bar; and every stroke is the same weight, one
 * {@code u} rounded to whole device pixels, never less than one. The weight is what made the old strip look
 * assembled: a 1px close beside a 1.5px camera beside filled keyboard keys is three hands, however carefully each
 * was drawn.
 *
 * <p><b>Snapped to the device grid.</b> The engine draws every mark as a distance field, so a stroke is anti-aliased
 * wherever it lands — which is exactly why it has to land on the pixel grid: an outline whose edge falls between
 * two pixels is drawn as two half-lit pixels, a grey smear where a hairline was meant. Edges are therefore rounded to
 * whole device pixels and line centres to pixel centres, in the <em>window's</em> frame (the box can sit at a
 * fractional x at 125%), and only diagonals, which have no grid to sit on, are left where they fall.
 *
 * @param box    the button's box, in window pixels; a glyph is drawn in its local frame
 * @param u      one grid unit in pixels
 * @param stroke the weight of every line, in whole pixels
 */
public record CaptionGrid(Rect box, double u, double stroke) {

    /** The grid for a caption button's box: the bar is 32 grid units tall. */
    public static CaptionGrid of(Rect box) {
        double u = Math.max(0.25, Math.min(box.w(), box.h()) / 32.0);
        return new CaptionGrid(box, u, Math.max(1, Math.round(u)));
    }

    /** {@code v} grid units, in pixels. */
    public double u(double v) {
        return v * u;
    }

    /** The local x of an edge {@code gx} units right of centre, on a pixel boundary. */
    public double edgeX(double gx) {
        return Math.round(box.x() + box.w() * 0.5 + gx * u) - box.x();
    }

    /** The local y of an edge {@code gy} units below centre, on a pixel boundary. */
    public double edgeY(double gy) {
        return Math.round(box.y() + box.h() * 0.5 + gy * u) - box.y();
    }

    /** The local x of a vertical stroke's centre line {@code gx} units right of centre, crisp at {@link #stroke}. */
    public double lineX(double gx) {
        return mid(box.x(), box.w() * 0.5 + gx * u);
    }

    /** The local y of a horizontal stroke's centre line {@code gy} units below centre, crisp at {@link #stroke}. */
    public double lineY(double gy) {
        return mid(box.y(), box.h() * 0.5 + gy * u);
    }

    private double mid(double origin, double local) {
        double abs = origin + local;
        return (stroke % 2 == 1 ? Math.floor(abs) + 0.5 : Math.round(abs)) - origin;
    }

    /** A rounded box outlined at the grid's stroke: top-left {@code (gx, gy)}, {@code gw × gh} units, radius {@code r}. */
    public Picture.Mark outline(double gx, double gy, double gw, double gh, double r, Color ink) {
        return outline(gx, gy, gw, gh, r, r, ink);
    }

    /** As {@link #outline(double, double, double, double, double, Color)}, with separate top and bottom radii. */
    public Picture.Mark outline(double gx, double gy, double gw, double gh, double rTop, double rBottom, Color ink) {
        double x0 = edgeX(gx);
        double y0 = edgeY(gy);
        return new Picture.Outline(x0, y0, edgeX(gx + gw) - x0, edgeY(gy + gh) - y0, u(rTop), u(rBottom), stroke,
                ink, null);
    }

    /** A rounded box filled: what an outline is drawn over when something behind it must not show through. */
    public Picture.Mark fill(double gx, double gy, double gw, double gh, double r, Color color) {
        double x0 = edgeX(gx);
        double y0 = edgeY(gy);
        return new Picture.Fill(x0, y0, edgeX(gx + gw) - x0, edgeY(gy + gh) - y0, u(r), u(r), color, null);
    }

    /** A horizontal stroke at {@code gy}, from {@code gx0} to {@code gx1}, its round caps inside the ends. */
    public Picture.Mark hline(double gx0, double gx1, double gy, Color ink) {
        double y = lineY(gy);
        double inset = stroke * 0.5;
        return new Picture.Line(edgeX(gx0) + inset, y, edgeX(gx1) - inset, y, stroke, ink, null);
    }

    /** A vertical stroke at {@code gx}, from {@code gy0} to {@code gy1}, its round caps inside the ends. */
    public Picture.Mark vline(double gx, double gy0, double gy1, Color ink) {
        double x = lineX(gx);
        double inset = stroke * 0.5;
        return new Picture.Line(x, edgeY(gy0) + inset, x, edgeY(gy1) - inset, stroke, ink, null);
    }

    /** A stroke between two grid points, unsnapped: a diagonal has no grid to sit on. */
    public Picture.Mark line(double gx0, double gy0, double gx1, double gy1, Color ink) {
        double cx = box.w() * 0.5;
        double cy = box.h() * 0.5;
        return new Picture.Line(cx + gx0 * u, cy + gy0 * u, cx + gx1 * u, cy + gy1 * u, stroke, ink, null);
    }

    /** A filled disc of diameter {@code d} units centred {@code (gx, gy)} from centre. */
    public Picture.Mark dot(double gx, double gy, double d, Color color) {
        double r = Math.max(stroke * 0.5, u(d) * 0.5);
        double cx = box.w() * 0.5 + gx * u;
        double cy = box.h() * 0.5 + gy * u;
        return new Picture.Fill(cx - r, cy - r, r * 2, r * 2, r, r, color, null);
    }
}
