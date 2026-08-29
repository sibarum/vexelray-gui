package dev.vexelray.gui.demo.chapter;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.demo.Chapter;
import dev.vexelray.gui.demo.Console;
import dev.vexelray.gui.demo.Stage;
import dev.vexelray.gui.demo.Ui;
import dev.vexelray.gui.draw.Picture;
import dev.vexelray.gui.draw.Sketch;
import dev.vexelray.gui.plot.Enclosure;
import dev.vexelray.gui.plot.Expr;
import dev.vexelray.gui.plot.Frame;
import dev.vexelray.gui.plot.Interval;
import dev.vexelray.gui.plot.Landmark;
import dev.vexelray.gui.plot.Landmarks;
import dev.vexelray.gui.widget.Slider;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reliable graphing: evaluate over a <em>column</em> of x and get back an enclosure that provably contains every
 * value the function takes there.
 *
 * <p>Point sampling cannot be made honest. A curve drawn by evaluating at N points and joining them will draw a
 * confident line straight through an asymptote, and no sample count fixes it — {@code tan(1/x)} has infinitely
 * many poles in any neighbourhood of the origin, so between any two adjacent samples there can be thousands.
 * Evaluating over the column instead gives an answer that is allowed to be vague but is never wrong: the striped
 * columns below are the plot saying "there is detail here finer than a pixel", which is true, rather than
 * inventing a line, which is not.
 *
 * <p><b>The module draws nothing.</b> It has no node vocabulary and no dependency that would give it one, so it
 * classifies each column into one of three answers and hands them to a sink — and this chapter's sink is forty
 * lines of {@link Sketch}. That is the seam working rather than the seam missing: the arithmetic is the part
 * that is hard to get right, and it is the part the framework holds.
 */
public final class PlotChapter implements Chapter {

    /** The functions on offer, and the reason each one is here. */
    private record Curve(String label, Expr expr, String note) {
    }

    private static final Expr X = new Expr.Param("x");

    private static final Curve[] CURVES = {
            new Curve("tan(1/x)", new Expr.Tan(new Expr.Div(Expr.ONE, X)),
                    "infinitely many poles near zero — no sample count reaches them"),
            new Curve("1/x", new Expr.Div(Expr.ONE, X),
                    "one pole, found by a divisor range containing zero rather than by a solver"),
            new Curve("sin(x)·x", new Expr.Mul(new Expr.Sin(X), X),
                    "bounded and well behaved: the enclosure is thin, so the curve is thin"),
            new Curve("log(x)", new Expr.Log(X),
                    "undefined to the left of zero, and undefined draws as nothing rather than as zero"),
    };

    /** Plot-space half-width. The slider drives it; y is refitted to match. */
    private static final double MIN_HALF = 0.35;
    private static final double MAX_HALF = 12.0;

    private final AtomicReference<Curve> curve = new AtomicReference<>(CURVES[0]);
    private final AtomicReference<double[]> half = new AtomicReference<>(new double[]{3.0});
    private final AtomicReference<float[]> size = new AtomicReference<>(new float[]{0f, 0f});

    private Node figure;
    private Node caption;
    private Console console;
    private Theme theme;

    @Override
    public String title() {
        return "Plot";
    }

    @Override
    public String blurb() {
        return "Interval arithmetic over columns of x: a pole is found by the arithmetic, and a column too "
                + "detailed to draw says so instead of guessing.";
    }

    @Override
    public Node build(Stage stage) {
        Gui gui = stage.gui();
        this.theme = stage.theme();
        this.console = stage.console();

        figure = gui.box().width(Length.FILL).height(Length.grow(1));
        gui.onResizeUi(figure, layout -> {
            size.set(new float[]{layout.rect().w(), layout.rect().h()});
            redraw();
        });

        caption = Ui.prose(gui, "");

        Node picker = Ui.controls(gui, Ui.label(gui, "Function:", Length.rem(5.5f)));
        for (Curve c : CURVES) {
            picker.append(Ui.button(gui, c.label(), () -> {
                curve.set(c);
                redraw();
                console.note(c.label() + " — " + c.note());
                report(c);
            }));
        }

        Slider zoom = new Slider(gui, 0.35f).onChange(v -> {
            half.set(new double[]{MIN_HALF + v * (MAX_HALF - MIN_HALF)});
            redraw();
        });
        zoom.node().width(Length.rem(14));

        return Ui.card(gui,
                Ui.heading(gui, "Enclosures, not samples"),
                figure,
                caption,
                Ui.rule(gui),
                picker,
                Ui.controls(gui, Ui.label(gui, "Window:", Length.rem(5.5f)), zoom.node(),
                        Ui.button(gui, "Find landmarks", () -> report(curve.get()))));
    }

    /**
     * Ask the module about every column the figure is wide, classify each against the frame, and paint what
     * comes back.
     *
     * <p>The split between evaluating and classifying is what would make this cheap to pan: an enclosure is in
     * plot space and knows nothing of the frame, so moving the window in y is re-classification alone and
     * re-evaluates nothing. This chapter re-evaluates everything on every change, because it is a demo of the
     * arithmetic rather than of a cache, and saying so is more useful than pretending otherwise.
     */
    private void redraw() {
        float[] wh = size.get();
        float w = wh[0];
        float h = wh[1];
        if (w < 8f || h < 8f) {
            return;   // no box yet; the resize observer will call again the moment there is one
        }
        double halfWidth = half.get()[0];
        Curve c = curve.get();
        Frame frame = new Frame(-halfWidth, halfWidth, -halfWidth, halfWidth);

        Sketch sketch = new Sketch();
        sketch.fill(0, 0, w, h, 6, theme.color(Role.WELL));

        // Axes, in plot space rather than in the middle of the box: the frame owns the conversion, so a window
        // that is not centred on the origin still puts the axes where the origin is.
        Color line = theme.color(Role.LINE);
        double zeroY = frame.fractionOf(0) * h;
        double zeroX = (0 - frame.xLo()) / frame.width() * w;
        sketch.line(0, zeroY, w, zeroY, 1, line);
        sketch.line(zeroX, 0, zeroX, h, 1, line);

        int columns = Math.max(1, (int) w);
        double columnPx = w / columns;
        Color ink = theme.color(Role.ACCENT);
        // A column that spills to infinity is painted whole, and it is painted in the danger colour on purpose:
        // it is not the curve, it is the plot declining to claim one.
        Color spill = Color.withAlpha(theme.color(Role.DANGER), 0.55f);

        int[] tally = new int[3];   // curve, fill, blank — reported under the figure
        dev.vexelray.gui.plot.Span.Sink sink = new dev.vexelray.gui.plot.Span.Sink() {

            @Override
            public void curve(int column, double top, double bottom) {
                tally[0]++;
                double y = top * h;
                // A flat curve encloses to a stretch thinner than any pixel. Giving it a minimum visible
                // thickness is the renderer's business, which is exactly why the classifier declined to.
                double height = Math.max(1.5, (bottom - top) * h);
                sketch.fill(column * columnPx, y, Math.max(1.0, columnPx), height, ink);
            }

            @Override
            public void fill(int column) {
                tally[1]++;
                sketch.fill(column * columnPx, 0, Math.max(1.0, columnPx), h, spill);
            }

            @Override
            public void blank(int column) {
                tally[2]++;
            }
        };

        for (int i = 0; i < columns; i++) {
            Interval column = frame.column(i, columns);
            Enclosure enclosure = c.expr().enclose(column);
            dev.vexelray.gui.plot.Span.of(enclosure, frame).emitTo(i, sink);
        }

        figure.picture(sketch.picture());
        caption.text(String.format("%s over x in [%.2f, %.2f]  ·  %d columns drawn, %d too detailed to draw, "
                        + "%d undefined or off-frame  ·  %s",
                c.label(), frame.xLo(), frame.xHi(), tally[0], tally[1], tally[2], c.note()));
    }

    /**
     * Landmarks run the other way from everything else here: an enclosure over-approximates, because a plot that
     * over-warns is conservative, but a landmark is a claim that something is <em>there</em>. So the arithmetic
     * proposes and a narrower test disposes, and nothing survives unconfirmed — which is why a suppressed kind
     * is worth reporting rather than hiding.
     */
    private void report(Curve c) {
        double halfWidth = half.get()[0];
        Landmarks.Survey survey = Landmarks.survey(c.expr(), "x", -halfWidth, halfWidth);
        if (survey.found().isEmpty()) {
            console.note(c.label() + ": no landmarks confirmed in this window");
        } else {
            console.good(c.label() + ": " + survey.found().size() + " landmarks");
            for (Landmark landmark : survey.found()) {
                console.note(String.format("   %s at x = %.4f", landmark.kind(), landmark.x()));
            }
        }
        for (Landmark.Kind kind : survey.suppressed()) {
            console.refused("   too many " + kind + "s to list — reported as suppressed rather than truncated");
        }
    }

    /** Kept for the record: a constant in the module's own arithmetic is a {@link BigDecimal}, not a double. */
    @SuppressWarnings("unused")
    private static Expr constant(double v) {
        return new Expr.Const(BigDecimal.valueOf(v));
    }
}
