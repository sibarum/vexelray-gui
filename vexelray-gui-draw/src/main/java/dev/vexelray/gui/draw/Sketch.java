package dev.vexelray.gui.draw;

import dev.vexelray.canvas.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@link Picture}. Every method returns {@code this}, so a drawing reads as the sequence of marks it is,
 * and {@link #picture()} takes the immutable value out.
 *
 * <p>A sketch is a builder and nothing more: it holds no geometry of its own, resolves no units, and knows no
 * colours. What it does hold is the <b>current tag</b> — {@link #tag(String)} stamps a name onto every mark added
 * after it, the same stamping idiom the engine's canvas uses for its clip and its translation, so a renderer can
 * say "these are the grid lines" once instead of on every call.
 *
 * <p>Not thread-safe, and not meant to be: a picture is built on whatever thread computed it and handed over as
 * a value.
 */
public final class Sketch {

    private final List<Picture.Mark> marks = new ArrayList<>();
    private String tag;

    /**
     * Name what is drawn from here on: {@code null} clears it. The name reaches SVG as a CSS class and is ignored
     * on the canvas — see {@link Picture.Sink}.
     */
    public Sketch tag(String tag) {
        this.tag = tag;
        return this;
    }

    /** The tag currently being stamped, or null. */
    public String currentTag() {
        return tag;
    }

    // --- shapes ---------------------------------------------------------------------------------------------

    /** A filled rectangle with its top-left at {@code (x, y)}. */
    public Sketch fill(double x, double y, double w, double h, Color color) {
        return add(new Picture.Fill(x, y, w, h, 0, 0, color, tag));
    }

    /** A filled rounded rectangle. */
    public Sketch fill(double x, double y, double w, double h, double radius, Color color) {
        return add(new Picture.Fill(x, y, w, h, radius, radius, color, tag));
    }

    /** As {@link #fill(double, double, double, double, double, Color)} with independent top and bottom radii. */
    public Sketch fill(double x, double y, double w, double h, double radiusTop, double radiusBottom,
                       Color color) {
        return add(new Picture.Fill(x, y, w, h, radiusTop, radiusBottom, color, tag));
    }

    /** A filled circle of radius {@code r} centred at {@code (cx, cy)} — the rounded box with nothing straight left. */
    public Sketch circle(double cx, double cy, double r, Color color) {
        return add(new Picture.Fill(cx - r, cy - r, 2 * r, 2 * r, r, r, color, tag));
    }

    /** A {@code width}-px outline inside the edge of the rectangle {@code (x, y, w, h)}. */
    public Sketch outline(double x, double y, double w, double h, double width, Color color) {
        return add(new Picture.Outline(x, y, w, h, 0, 0, width, color, tag));
    }

    /** A {@code width}-px outline inside the edge of the rounded rectangle. */
    public Sketch outline(double x, double y, double w, double h, double radius, double width, Color color) {
        return add(new Picture.Outline(x, y, w, h, radius, radius, width, color, tag));
    }

    /** An outlined circle: the ring of a marker, without a fill under it. */
    public Sketch ring(double cx, double cy, double r, double width, Color color) {
        return add(new Picture.Outline(cx - r, cy - r, 2 * r, 2 * r, r, r, width, color, tag));
    }

    /** A line at any angle, {@code thickness} px wide, round-capped. */
    public Sketch line(double x0, double y0, double x1, double y1, double thickness, Color color) {
        return add(new Picture.Line(x0, y0, x1, y1, thickness, color, tag));
    }

    /**
     * A polyline through {@code xs[i], ys[i]} — the joined form of {@link #line}, which is what a point-sampled
     * curve is. One mark per segment, so the picture stays a flat list and a consumer needs no new operation; the
     * round caps make the joints look joined.
     */
    public Sketch polyline(double[] xs, double[] ys, double thickness, Color color) {
        if (xs.length != ys.length) {
            throw new IllegalArgumentException("polyline xs/ys length mismatch: " + xs.length + " vs " + ys.length);
        }
        for (int i = 1; i < xs.length; i++) {
            line(xs[i - 1], ys[i - 1], xs[i], ys[i], thickness, color);
        }
        return this;
    }

    /** A run of text starting at {@code x} with its baseline at {@code y}. */
    public Sketch text(String text, double x, double y, double sizePx, Color color) {
        return add(new Picture.Text(text, x, y, sizePx, color, tag));
    }

    // --- composition ----------------------------------------------------------------------------------------

    /** Add an authored mark as it is — including a kind this builder has no method for. Its own tag is kept. */
    public Sketch add(Picture.Mark mark) {
        marks.add(mark);
        return this;
    }

    /** Draw {@code picture} with its origin at {@code (dx, dy)}. Its marks keep their own tags. */
    public Sketch place(Picture picture, double dx, double dy) {
        marks.addAll(picture.shifted(dx, dy).marks());
        return this;
    }

    /** How many marks have been added — a renderer's own budget check, and what a test counts. */
    public int size() {
        return marks.size();
    }

    /** The picture built so far. The sketch stays usable; the value taken out is independent of it. */
    public Picture picture() {
        return new Picture(marks);
    }
}
