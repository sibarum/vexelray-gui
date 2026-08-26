package dev.vexelray.gui.draw;

import dev.vexelray.canvas.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * An ordered list of marks in one pixel frame — a drawing, authored once and shown wherever it is wanted.
 *
 * <p>Coordinates are in <b>pixels</b>, top-left origin, {@code y} down: the same frame the engine's
 * {@code Canvas} uses, and the same one SVG uses. A picture placed on a node is drawn in the node's own box, so
 * {@code (0, 0)} is that box's top-left corner and nothing here needs to know where on screen the box ended up.
 *
 * <h2>Why a picture rather than more nodes</h2>
 * Before this, an application that wanted marks on screen had exactly two ways to get them: one node per mark, or
 * a GPU viewport. Nodes are the layout engine, so a plot's grid lines, columns and markers each became a laid-out,
 * hit-testable, reconciled box — hundreds of them, for a picture that does not participate in layout at all and
 * changes wholesale whenever the window moves. A viewport is the other extreme: a whole render target and a
 * pipeline, for two lines and a label.
 *
 * <p>A picture is the middle that was missing. It is not laid out, not hit-tested, and not reconciled mark by
 * mark; it is a value, and replacing it is one prop write.
 *
 * <h2>Why the alphabet is small, and what bounds it</h2>
 * {@link Sink} is the closed set of operations a consumer must implement — four, and a consumer that implements
 * all four can draw any mark, including kinds written after it. {@link Mark} is <b>open</b>: an application may
 * define its own, so long as it can say what it is in terms of the sink. (The same inversion as
 * {@code Placed.Draw}/{@code Placed.Sink} in {@code -typeset}, and for the same reason: a switch over a sealed
 * draw type is hand-written dispatch.)
 *
 * <p>What the four are is not a matter of taste. <b>The alphabet is what the engine's rounded-box SDF can
 * actually draw</b> — a rounded box (so also a rectangle and a circle), that box outlined, a line at any angle
 * (a rotated box with round caps), and a run of glyphs. Nothing here is approximated on one target to suit the
 * other, which is what lets the same picture be honest on screen and in a file.
 *
 * <p>So <b>there is no filled polygon, and no curve</b>, and that is a statement about the engine rather than
 * about this module: the uber-shader has no triangle or convex-polygon kind, so a polygon could only be faked
 * on the Canvas side while SVG drew it exactly, and a picture that means two different things on two targets is
 * worse than one that cannot express the shape. Widening {@link Sink} is therefore a visible decision with a
 * prerequisite, and the prerequisite lands in the engine.
 *
 * <p><b>A diagonal is expressible</b>, which is new — the node tree is axis-aligned, so a commutative-diagram
 * arrow or a point-sampled polyline could not be drawn at all before this.
 *
 * @param marks what to paint, in submission order — first mark first, so later marks draw on top
 */
public record Picture(List<Mark> marks) {

    /** The empty picture: a node carrying it draws exactly what it would have drawn anyway. */
    public static final Picture EMPTY = new Picture(List.of());

    public Picture {
        marks = List.copyOf(marks);
    }

    /** A picture of exactly these marks. */
    public static Picture of(Mark... marks) {
        return new Picture(List.of(marks));
    }

    /** True when there is nothing to draw — what a consumer checks before pushing a clip it will not use. */
    public boolean isEmpty() {
        return marks.isEmpty();
    }

    /** Emit every mark to {@code sink}, in order. The whole of how a consumer reads a picture. */
    public void emitTo(Sink sink) {
        for (Mark m : marks) {
            m.emitTo(sink);
        }
    }

    /** This picture translated by {@code (dx, dy)} px — how a drawing places a sub-drawing. */
    public Picture shifted(double dx, double dy) {
        if (dx == 0 && dy == 0) {
            return this;
        }
        List<Mark> out = new ArrayList<>(marks.size());
        for (Mark m : marks) {
            out.add(m.shifted(dx, dy));
        }
        return new Picture(out);
    }

    /**
     * Something paintable. <b>Open</b>: define your own, so long as it can express itself on a {@link Sink}.
     */
    public interface Mark {

        /** This mark translated by {@code (dx, dy)} px. */
        Mark shifted(double dx, double dy);

        /** Express this mark as calls on {@code sink}. The one method a new mark kind has to write. */
        void emitTo(Sink sink);
    }

    /**
     * What a consumer of a picture implements — the closed alphabet every mark reduces to.
     *
     * <p><b>No default methods, deliberately.</b> A target that silently dropped one of four operations would be
     * a bug that looks like a blank area, so the compiler is the right place to catch it. (The opposite tuning
     * from an event sink, where ignoring most of the stream is the normal case.)
     *
     * <p>Every operation carries a {@code tag}: a caller-supplied name for what the mark <em>is</em>, or null.
     * It is metadata rather than geometry — {@link SvgSink} emits it as a CSS class so a host stylesheet can
     * restyle a picture it did not author, and {@link CanvasSink} ignores it. It is the same insurance as a
     * typeset run's source reference: cheap to carry, impossible to add retroactively to pictures already
     * authored.
     */
    public interface Sink {

        /**
         * A filled rounded box with its top-left at {@code (x, y)}. Corner radii are per vertical half —
         * {@code radiusTop} above the centre line, {@code radiusBottom} below — so a rectangle is {@code (0, 0)},
         * a circle is a square with both radii at half its side, and a tab is {@code (r, 0)}.
         */
        void fill(double x, double y, double w, double h, double radiusTop, double radiusBottom, Color color,
                  String tag);

        /**
         * A {@code width}-px outline hugging the <b>inside</b> edge of that same rounded box, so an outline never
         * grows the shape it describes. Not a fill-then-inset: it is one stroke, and it composites as one.
         */
        void outline(double x, double y, double w, double h, double radiusTop, double radiusBottom, double width,
                     Color color, String tag);

        /** A line from {@code (x0, y0)} to {@code (x1, y1)}, {@code thickness} px wide, with round caps. */
        void line(double x0, double y0, double x1, double y1, double thickness, Color color, String tag);

        /**
         * A single run of text, {@code x} at its start and {@code y} at its <b>baseline</b>, set at
         * {@code sizePx}. A run, not a paragraph: there is no box, no wrapping and no alignment here, because a
         * picture places its own text — the caller already knows where the label goes, which is why it is drawing
         * rather than laying out.
         */
        void glyphs(String text, double x, double y, double sizePx, Color color, String tag);
    }

    // --- the four marks -------------------------------------------------------------------------------------

    /** A filled rounded box — see {@link Sink#fill}. */
    public record Fill(double x, double y, double w, double h, double radiusTop, double radiusBottom, Color color,
                       String tag) implements Mark {

        /** Untagged. */
        public Fill(double x, double y, double w, double h, double radiusTop, double radiusBottom, Color color) {
            this(x, y, w, h, radiusTop, radiusBottom, color, null);
        }

        @Override
        public Mark shifted(double dx, double dy) {
            return new Fill(x + dx, y + dy, w, h, radiusTop, radiusBottom, color, tag);
        }

        @Override
        public void emitTo(Sink sink) {
            sink.fill(x, y, w, h, radiusTop, radiusBottom, color, tag);
        }
    }

    /** An outlined rounded box — see {@link Sink#outline}. */
    public record Outline(double x, double y, double w, double h, double radiusTop, double radiusBottom,
                          double width, Color color, String tag) implements Mark {

        /** Untagged. */
        public Outline(double x, double y, double w, double h, double radiusTop, double radiusBottom, double width,
                       Color color) {
            this(x, y, w, h, radiusTop, radiusBottom, width, color, null);
        }

        @Override
        public Mark shifted(double dx, double dy) {
            return new Outline(x + dx, y + dy, w, h, radiusTop, radiusBottom, width, color, tag);
        }

        @Override
        public void emitTo(Sink sink) {
            sink.outline(x, y, w, h, radiusTop, radiusBottom, width, color, tag);
        }
    }

    /** A line at any angle — see {@link Sink#line}. */
    public record Line(double x0, double y0, double x1, double y1, double thickness, Color color,
                       String tag) implements Mark {

        /** Untagged. */
        public Line(double x0, double y0, double x1, double y1, double thickness, Color color) {
            this(x0, y0, x1, y1, thickness, color, null);
        }

        @Override
        public Mark shifted(double dx, double dy) {
            return new Line(x0 + dx, y0 + dy, x1 + dx, y1 + dy, thickness, color, tag);
        }

        @Override
        public void emitTo(Sink sink) {
            sink.line(x0, y0, x1, y1, thickness, color, tag);
        }
    }

    /** A run of text on a baseline — see {@link Sink#glyphs}. */
    public record Text(String text, double x, double y, double sizePx, Color color, String tag) implements Mark {

        /** Untagged. */
        public Text(String text, double x, double y, double sizePx, Color color) {
            this(text, x, y, sizePx, color, null);
        }

        @Override
        public Mark shifted(double dx, double dy) {
            return new Text(text, x + dx, y + dy, sizePx, color, tag);
        }

        @Override
        public void emitTo(Sink sink) {
            sink.glyphs(text, x, y, sizePx, color, tag);
        }
    }
}
