package dev.vexelray.gui.draw;

import dev.vexelray.canvas.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@link Picture}. Every method returns {@code this}, so a drawing reads as the sequence of marks it is,
 * and {@link #picture()} takes the immutable value out.
 *
 * <p>Every mark takes an {@link Ink} or a {@link Color}: a name ({@code Role.INK}, from vexelray-gui-core) the look decides
 * when the picture is drawn, so it follows a theme swap, or a colour that is the same in every look.
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
    public Sketch fill(double x, double y, double w, double h, Ink color) {
        return add(new Picture.Fill(x, y, w, h, 0, 0, color, tag));
    }

    /** As above, in a fixed colour: the same in every look. */
    public Sketch fill(double x, double y, double w, double h, Color color) {
        return fill(x, y, w, h, Ink.of(color));
    }

    /** A filled rounded rectangle. */
    public Sketch fill(double x, double y, double w, double h, double radius, Ink color) {
        return add(new Picture.Fill(x, y, w, h, radius, radius, color, tag));
    }

    /** As above, in a fixed colour: the same in every look. */
    public Sketch fill(double x, double y, double w, double h, double radius, Color color) {
        return fill(x, y, w, h, radius, Ink.of(color));
    }

    /** As {@link #fill(double, double, double, double, double, Color)} with independent top and bottom radii. */
    public Sketch fill(double x, double y, double w, double h, double radiusTop, double radiusBottom,
                       Ink color) {
        return add(new Picture.Fill(x, y, w, h, radiusTop, radiusBottom, color, tag));
    }

    /** As above, in a fixed colour: the same in every look. */
    public Sketch fill(double x, double y, double w, double h, double radiusTop, double radiusBottom,
                       Color color) {
        return fill(x, y, w, h, radiusTop, radiusBottom, Ink.of(color));
    }

    /** A filled circle of radius {@code r} centred at {@code (cx, cy)} — the rounded box with nothing straight left. */
    public Sketch circle(double cx, double cy, double r, Ink color) {
        return add(new Picture.Fill(cx - r, cy - r, 2 * r, 2 * r, r, r, color, tag));
    }

    /** As above, in a fixed colour: the same in every look. */
    public Sketch circle(double cx, double cy, double r, Color color) {
        return circle(cx, cy, r, Ink.of(color));
    }

    /** A {@code width}-px outline inside the edge of the rectangle {@code (x, y, w, h)}. */
    public Sketch outline(double x, double y, double w, double h, double width, Ink color) {
        return add(new Picture.Outline(x, y, w, h, 0, 0, width, color, tag));
    }

    /** As above, in a fixed colour: the same in every look. */
    public Sketch outline(double x, double y, double w, double h, double width, Color color) {
        return outline(x, y, w, h, width, Ink.of(color));
    }

    /** A {@code width}-px outline inside the edge of the rounded rectangle. */
    public Sketch outline(double x, double y, double w, double h, double radius, double width, Ink color) {
        return add(new Picture.Outline(x, y, w, h, radius, radius, width, color, tag));
    }

    /** As above, in a fixed colour: the same in every look. */
    public Sketch outline(double x, double y, double w, double h, double radius, double width, Color color) {
        return outline(x, y, w, h, radius, width, Ink.of(color));
    }

    /** An outlined circle: the ring of a marker, without a fill under it. */
    public Sketch ring(double cx, double cy, double r, double width, Ink color) {
        return add(new Picture.Outline(cx - r, cy - r, 2 * r, 2 * r, r, r, width, color, tag));
    }

    /** As above, in a fixed colour: the same in every look. */
    public Sketch ring(double cx, double cy, double r, double width, Color color) {
        return ring(cx, cy, r, width, Ink.of(color));
    }

    /** A line at any angle, {@code thickness} px wide, round-capped. */
    public Sketch line(double x0, double y0, double x1, double y1, double thickness, Ink color) {
        return add(new Picture.Line(x0, y0, x1, y1, thickness, color, tag));
    }

    /** As above, in a fixed colour: the same in every look. */
    public Sketch line(double x0, double y0, double x1, double y1, double thickness, Color color) {
        return line(x0, y0, x1, y1, thickness, Ink.of(color));
    }

    /**
     * A polyline through {@code xs[i], ys[i]} — the joined form of {@link #line}, which is what a point-sampled
     * curve is. One mark per segment, so the picture stays a flat list and a consumer needs no new operation; the
     * round caps make the joints look joined.
     */
    public Sketch polyline(double[] xs, double[] ys, double thickness, Ink color) {
        if (xs.length != ys.length) {
            throw new IllegalArgumentException("polyline xs/ys length mismatch: " + xs.length + " vs " + ys.length);
        }
        for (int i = 1; i < xs.length; i++) {
            line(xs[i - 1], ys[i - 1], xs[i], ys[i], thickness, color);
        }
        return this;
    }

    /** As above, in a fixed colour: the same in every look. */
    public Sketch polyline(double[] xs, double[] ys, double thickness, Color color) {
        return polyline(xs, ys, thickness, Ink.of(color));
    }

    /** A run of text starting at {@code x} with its baseline at {@code y}. */
    public Sketch text(String text, double x, double y, double sizePx, Ink color) {
        return add(new Picture.Text(text, x, y, sizePx, color, tag));
    }

    /** As above, in a fixed colour: the same in every look. */
    public Sketch text(String text, double x, double y, double sizePx, Color color) {
        return text(text, x, y, sizePx, Ink.of(color));
    }

    /**
     * A drawing in signed distance graphics fitted into the box at {@code (x, y)}, its aspect kept: an emblem on a
     * diagram, a symbol on a plot. {@code drawing} and {@code pages} as {@link Picture.Sink#drawing} has them; in a
     * GUI, an {@code ImageSdg}'s {@code source()} and {@code pages()}, kept while it is shown.
     */
    public Sketch drawing(double x, double y, double w, double h, dev.vexelray.fields.sdg.Sdg drawing,
                          java.util.List<? extends dev.vexelray.target.ImageHandle> pages) {
        return add(new Picture.Drawing(x, y, w, h, drawing, pages, tag));
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
