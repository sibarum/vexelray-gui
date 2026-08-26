package dev.vexelray.gui.draw;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.Color;
import dev.vexelray.text.TextLayout;

/**
 * The screen target: a {@link Picture.Sink} that issues engine {@link Canvas} calls.
 *
 * <p>There is nothing to translate. Every one of the four operations is a canvas primitive already — which is not
 * a coincidence but the reason the alphabet is those four (see {@link Picture}). A picture therefore costs the
 * vertices its marks need and nothing else: it batches into the same one vertex buffer as the panel it is drawn
 * on, in submission order, so a drawing inside a window is not a second pass, a second draw call, or a texture.
 *
 * <p>The picture is drawn in <b>the canvas's current frame</b>: the caller positions and clips it by pushing a
 * translation and a clip before emitting, exactly as it would for any other run of drawing. This class adds no
 * frame of its own, so a picture's {@code (0, 0)} is wherever the caller put it.
 *
 * <p>{@code alpha} multiplies every colour, so a picture inside a fading subtree fades with it — the same
 * treatment the tree renderer gives every colour it paints.
 */
public final class CanvasSink implements Picture.Sink {

    private final Canvas canvas;
    private final TextLayout text;
    private final float alpha;

    /**
     * Draw into {@code canvas}, setting glyphs with {@code text}, at {@code alpha}.
     *
     * <p>{@code text} may be null — a canvas with no font atlas resolved. Then a picture's shapes draw and its
     * text does not, which is the same degradation the tree renderer already makes for a text node under a null
     * face, and the alternative (failing the frame) loses the whole window over a missing label.
     */
    public CanvasSink(Canvas canvas, TextLayout text, float alpha) {
        this.canvas = canvas;
        this.text = text;
        this.alpha = alpha;
    }

    /** Fully opaque. */
    public CanvasSink(Canvas canvas, TextLayout text) {
        this(canvas, text, 1f);
    }

    @Override
    public void fill(double x, double y, double w, double h, double radiusTop, double radiusBottom, Color color,
                     String tag) {
        if (color == null) {
            return;
        }
        canvas.fillRoundRect((float) x, (float) y, (float) w, (float) h, (float) radiusTop, (float) radiusBottom,
                fade(color));
    }

    @Override
    public void outline(double x, double y, double w, double h, double radiusTop, double radiusBottom,
                        double width, Color color, String tag) {
        if (color == null) {
            return;
        }
        canvas.strokeRoundRect((float) x, (float) y, (float) w, (float) h, (float) radiusTop, (float) radiusBottom,
                (float) width, fade(color));
    }

    @Override
    public void line(double x0, double y0, double x1, double y1, double thickness, Color color, String tag) {
        if (color == null) {
            return;
        }
        canvas.strokeLine((float) x0, (float) y0, (float) x1, (float) y1, (float) thickness, fade(color));
    }

    /**
     * A run on its baseline. The canvas places a block by its top-left, and the ascent of a face is a function of
     * the pixel size alone, so the conversion is one subtraction and needs no measurement of the string.
     */
    @Override
    public void glyphs(String s, double x, double y, double sizePx, Color color, String tag) {
        if (text == null || color == null || s == null || s.isEmpty()) {
            return;
        }
        float px = (float) sizePx;
        float top = (float) y - text.glyphLayout().ascent(px);
        canvas.text(text, s, (float) x, top, TextLayout.TextStyle.of(px).withWrap(TextLayout.WrapMode.NONE),
                fade(color));
    }

    /**
     * {@code color} at this sink's opacity. A mark with no colour is not drawn at all rather than drawn
     * transparent: the vertices of an invisible shape are still vertices, and every operation above has already
     * returned by the time this is reached.
     */
    private Color fade(Color color) {
        return alpha >= 1f ? color : Color.withAlpha(color, color.a() * alpha);
    }
}
