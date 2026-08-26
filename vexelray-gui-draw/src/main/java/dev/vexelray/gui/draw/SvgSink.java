package dev.vexelray.gui.draw;

import dev.vexelray.canvas.Color;

import java.util.Locale;

/**
 * The file target: a {@link Picture.Sink} that writes each mark as one SVG element.
 *
 * <p>Same pixel frame, same submission order, same numbers — SVG's coordinate system is the canvas's, so a
 * picture exported here is the picture that was on screen and not a redrawing of it. What differs is only what a
 * vector format can say that a vertex buffer cannot, and it is used in exactly two places:
 *
 * <ul>
 *   <li><b>The colour is a presentation attribute</b> ({@code fill=}, {@code stroke=}), which in CSS loses to
 *       every stylesheet rule. So a host page can restyle an exported picture by class without editing it, and a
 *       picture opened with no stylesheet still looks like it did on screen. Both, from one output.</li>
 *   <li><b>A mark's tag becomes its {@code class}</b> — the whole reason a tag is carried. "These are the grid
 *       lines, that is the curve, those are the markers" survives the export, so the semantics an application
 *       knew are still there for a reader that never saw the application.</li>
 * </ul>
 *
 * <p>Elements are appended to a {@link StringBuilder} the caller owns, one per line, with no wrapper — so a
 * caller can nest the output in its own document, or use {@link Svg#document} for a standalone one.
 *
 * <p><b>Text is placed, not measured.</b> A run's {@code x} and baseline {@code y} go straight onto
 * {@code <text>}, which anchors on the baseline too. The glyph advances are then the reader's font's rather than
 * the atlas's, so a label sits exactly where the picture put it and is exactly as wide as the viewer's font makes
 * it. That is the honest trade for a resolution-independent file: the alternative is converting every glyph to a
 * path, which is a font-embedding problem rather than a drawing one.
 */
public final class SvgSink implements Picture.Sink {

    private final StringBuilder out;
    private final String indent;

    /** Append elements to {@code out}, each on its own line, indented by {@code indent}. */
    public SvgSink(StringBuilder out, String indent) {
        this.out = out;
        this.indent = indent == null ? "" : indent;
    }

    /** Append elements to {@code out} with no indentation. */
    public SvgSink(StringBuilder out) {
        this(out, "");
    }

    @Override
    public void fill(double x, double y, double w, double h, double radiusTop, double radiusBottom, Color color,
                     String tag) {
        if (isCircle(w, h, radiusTop, radiusBottom)) {
            out.append(indent).append("<circle");
            circle(x, y, w);
        } else if (radiusTop == radiusBottom) {
            out.append(indent).append("<rect");
            rect(x, y, w, h, radiusTop);
        } else {
            out.append(indent).append("<path d=\"").append(roundedPath(x, y, w, h, radiusTop, radiusBottom))
                    .append('"');
        }
        paint("fill", color);
        cssClass(tag);
        out.append("/>\n");
    }

    /**
     * An outline, inset by half its width. SVG strokes are centred on the path while the canvas's hug the inside
     * edge of the box, so the same numbers would describe two different rings; insetting is what makes the two
     * targets draw the same shape, and it is why an outline never grows the box it describes on either.
     */
    @Override
    public void outline(double x, double y, double w, double h, double radiusTop, double radiusBottom,
                        double width, Color color, String tag) {
        double half = width / 2;
        double ix = x + half;
        double iy = y + half;
        double iw = Math.max(0, w - width);
        double ih = Math.max(0, h - width);
        double rTop = Math.max(0, radiusTop - half);
        double rBottom = Math.max(0, radiusBottom - half);
        if (isCircle(iw, ih, rTop, rBottom)) {
            out.append(indent).append("<circle");
            circle(ix, iy, iw);
        } else if (rTop == rBottom) {
            out.append(indent).append("<rect");
            rect(ix, iy, iw, ih, rTop);
        } else {
            out.append(indent).append("<path d=\"").append(roundedPath(ix, iy, iw, ih, rTop, rBottom)).append('"');
        }
        out.append(" fill=\"none\"");
        paint("stroke", color);
        out.append(" stroke-width=\"").append(num(width)).append('"');
        cssClass(tag);
        out.append("/>\n");
    }

    @Override
    public void line(double x0, double y0, double x1, double y1, double thickness, Color color, String tag) {
        out.append(indent).append("<line x1=\"").append(num(x0)).append("\" y1=\"").append(num(y0))
                .append("\" x2=\"").append(num(x1)).append("\" y2=\"").append(num(y1)).append('"');
        paint("stroke", color);
        out.append(" stroke-width=\"").append(num(thickness)).append('"');
        // The canvas's lines are round-capped, so these are too — a dot is what a zero-length line draws there.
        out.append(" stroke-linecap=\"round\"");
        cssClass(tag);
        out.append("/>\n");
    }

    @Override
    public void glyphs(String text, double x, double y, double sizePx, Color color, String tag) {
        if (text == null || text.isEmpty()) {
            return;
        }
        out.append(indent).append("<text x=\"").append(num(x)).append("\" y=\"").append(num(y))
                .append("\" font-size=\"").append(num(sizePx)).append('"');
        paint("fill", color);
        cssClass(tag);
        out.append('>').append(escape(text)).append("</text>\n");
    }

    // --- internals ------------------------------------------------------------------------------------------

    /**
     * Whether a rounded box has nothing straight left. A {@code <rect>} with {@code rx} at half its side draws
     * the same pixels, so this is for the reader: a plot's markers are circles, and someone opening the file to
     * restyle them should find the element they went looking for.
     */
    private static boolean isCircle(double w, double h, double radiusTop, double radiusBottom) {
        return w > 0 && w == h && radiusTop == radiusBottom && radiusTop >= w / 2;
    }

    private void circle(double x, double y, double diameter) {
        double r = diameter / 2;
        out.append(" cx=\"").append(num(x + r)).append("\" cy=\"").append(num(y + r))
                .append("\" r=\"").append(num(r)).append('"');
    }

    private void rect(double x, double y, double w, double h, double radius) {
        out.append(" x=\"").append(num(x)).append("\" y=\"").append(num(y))
                .append("\" width=\"").append(num(w)).append("\" height=\"").append(num(h)).append('"');
        if (radius > 0) {
            out.append(" rx=\"").append(num(radius)).append('"');
        }
    }

    /**
     * The path of a box whose top and bottom corners round differently — the one shape {@code <rect>} cannot
     * describe, because it has a single {@code rx}. Clockwise from the end of the top-left arc, with each corner
     * an elliptical arc of its own radius; radii are clamped to half the smaller side, as the canvas clamps them.
     */
    private static String roundedPath(double x, double y, double w, double h, double radiusTop,
                                      double radiusBottom) {
        double max = Math.min(w, h) / 2;
        double rt = Math.max(0, Math.min(radiusTop, max));
        double rb = Math.max(0, Math.min(radiusBottom, max));
        StringBuilder p = new StringBuilder();
        p.append('M').append(num(x + rt)).append(' ').append(num(y));
        p.append('L').append(num(x + w - rt)).append(' ').append(num(y));
        arc(p, rt, x + w, y + rt);
        p.append('L').append(num(x + w)).append(' ').append(num(y + h - rb));
        arc(p, rb, x + w - rb, y + h);
        p.append('L').append(num(x + rb)).append(' ').append(num(y + h));
        arc(p, rb, x, y + h - rb);
        p.append('L').append(num(x)).append(' ').append(num(y + rt));
        arc(p, rt, x + rt, y);
        return p.append('Z').toString();
    }

    /**
     * One quarter-turn of a circular corner, sweeping clockwise to {@code (toX, toY)}. A corner of no radius is
     * <em>nothing</em> rather than a line: the edge before it already ended at the corner point, so the square
     * case adds no command at all.
     */
    private static void arc(StringBuilder p, double r, double toX, double toY) {
        if (r <= 0) {
            return;
        }
        p.append('A').append(num(r)).append(' ').append(num(r)).append(" 0 0 1 ")
                .append(num(toX)).append(' ').append(num(toY));
    }

    /**
     * {@code attribute="#rrggbb"}, plus a matching {@code -opacity} when the colour is not fully opaque. Two
     * attributes rather than one {@code rgba(…)} so that a stylesheet overriding the colour keeps the picture's
     * transparency, which is geometry-adjacent (a wash, an enclosure band) rather than a palette choice.
     *
     * <p>A mark with no colour writes {@code "none"} rather than being dropped, which is the one place the two
     * targets differ on purpose: the canvas cannot draw an unpainted shape, while an unpainted element with a
     * class is precisely what a host stylesheet is for.
     */
    private void paint(String attribute, Color color) {
        if (color == null) {
            out.append(' ').append(attribute).append("=\"none\"");
            return;
        }
        out.append(' ').append(attribute).append("=\"").append(hex(color)).append('"');
        if (color.a() < 1f) {
            out.append(' ').append(attribute).append("-opacity=\"").append(num(color.a())).append('"');
        }
    }

    private void cssClass(String tag) {
        if (tag != null && !tag.isEmpty()) {
            out.append(" class=\"").append(escape(tag)).append('"');
        }
    }

    private static String hex(Color c) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", channel(c.r()), channel(c.g()), channel(c.b()));
    }

    private static int channel(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255f)));
    }

    /**
     * A number an SVG reader will parse the same way everywhere: {@link Locale#ROOT} so a decimal point is never
     * a comma, whole values without a fractional part, and three decimals otherwise with the trailing zeros
     * trimmed — enough for a sub-pixel position, short enough that the file stays readable.
     */
    static String num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e7) {
            return Long.toString((long) v);
        }
        String s = String.format(Locale.ROOT, "%.3f", v);
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '.') {
            end--;
        }
        return s.substring(0, end);
    }

    /** XML text and attribute escaping — a label is application data and may contain anything. */
    static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&apos;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
