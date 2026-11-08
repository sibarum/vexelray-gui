package dev.vexelray.gui.core;

import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.SdgDrawing;
import dev.vexelray.canvas.SdgPages;
import dev.vexelray.canvas.shadelet.CoreShadelets;
import dev.vexelray.canvas.shadelet.Look;
import dev.vexelray.diag.Diagnostics;
import dev.vexelray.fields.sdg.Sdg;
import dev.vexelray.target.ImageHandle;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A drawing in signed distance graphics a node draws ({@link Node#sdg}): its layers, each a distance field and a
 * paint, over pages the renderer uploads to whichever device draws them, once, as {@link Pixels}. The format is
 * vexelray-fields' {@code docs/sdg.md}.
 *
 * <p>A drawing read from a file is made with {@link #of(Sdg)}. An icon is one already: {@link #ofField} and
 * {@link #ofFields} make the drawing of a field, or of a layered icon's fields, over the atlas they are cells of, so
 * the atlas is still the one texture every icon draws from.
 *
 * <p>{@code tint} is the colour a paint in the role {@value #TINT} is drawn in: what a widget gives an icon of one
 * colour, its state's ink. Null draws such a paint as the theme's ink. {@code look} draws the back-most layer with
 * effects (a glow, a shadow, a weight, an outline), as a field's look does, its source
 * {@link CoreShadelets#SDG_STENCIL} and its fill {@link CoreShadelets#SDG_PAINT}; {@code instance} is its other parts'
 * per-instance values. Null and empty for none.
 *
 * <p>Make one per drawing and keep it where you can: the pages' identity is what uploads, and {@link #of(Sdg)} makes
 * them. The tint and the look are cheap to change ({@link #tint(Color)}, {@link #look(Look, float...)}).
 *
 * @param drawing the layers, every one's stencil and paint on one page, and the pages' sizes
 * @param pages   the pages, in order, uploaded as they are (RGBA8, never sRGB-decoded)
 * @param source  the drawing with its texels, when it was made from one ({@link #of(Sdg)}): what a picture's drawing
 *                mark and an export take. Null for an icon over an atlas, whose texels are never read here
 */
public record ImageSdg(SdgDrawing drawing, List<Pixels> pages, Color tint, Look look, float[] instance, Sdg source) {

    /** The role an icon of one colour paints its shape in: the node's {@code tint}. */
    public static final String TINT = "tint";

    private static final float[] NONE = new float[0];

    public ImageSdg {
        pages = List.copyOf(pages);
        if (drawing == null || pages.size() != drawing.pages()) {
            throw new IllegalArgumentException("an SDG drawing and " + pages.size() + " pages for it");
        }
        instance = instance == null || instance.length == 0 ? NONE : instance.clone();
    }

    /** {@code drawing} ready to draw: colocated, its pages as pixels made here; in the theme, with no look. */
    public static ImageSdg of(Sdg drawing) {
        Sdg ready = SdgPages.colocated(drawing);
        List<Pixels> pages = new ArrayList<>();
        for (Sdg.Page page : ready.pages()) {
            pages.add(new Page(page));
        }
        return new ImageSdg(SdgDrawing.of(ready), pages, null, null, NONE, ready);
    }

    /**
     * {@code field} as a drawing of one layer over the same texels: its shape's square the drawing's canvas, its margin
     * past it, painted in its tint (the role {@value #TINT}), with its look's effects. The field's texture must be
     * {@link Pixels}, as every baked atlas is. A look whose source is not the core {@code field} has no SDG
     * equivalent, and is left off, said once.
     */
    public static ImageSdg ofField(ImageField field) {
        return ofFields(List.of(field), field.tint());
    }

    /**
     * {@code fields} back to front as one drawing, each a layer in its own tint: a layered icon. The first field's look
     * draws the first layer, as a layered icon's effects were always its bottom layer's. Every field's texture must be
     * {@link Pixels}; fields of one texture share a page.
     *
     * @param tint the node's tint: given, every layer is painted in the role {@value #TINT} (an icon of one colour);
     *             null, each in its field's own colour (a layered icon)
     */
    public static ImageSdg ofFields(List<ImageField> fields, Color tint) {
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("a drawing of no fields");
        }
        Map<ImageHandle, Integer> pageOf = new IdentityHashMap<>();
        List<Pixels> pages = new ArrayList<>();
        List<Sdg.Layer> layers = new ArrayList<>();
        ImageField first = fields.getFirst();
        int inner = first.inner();
        for (ImageField f : fields) {
            if (!(f.texture() instanceof Pixels pixels)) {
                throw new IllegalArgumentException("a field drawn as SDG must be a cell of Pixels, as a baked atlas is; "
                        + "this one is " + f.texture());
            }
            Integer page = pageOf.get(pixels);
            if (page == null) {
                page = pages.size();
                pageOf.put(pixels, page);
                pages.add(pixels);
            }
            ImageRegion r = f.region();
            int x = Math.round(r.u0() * pixels.width()), y = Math.round(r.v0() * pixels.height());
            int w = Math.round((r.u1() - r.u0()) * pixels.width()), h = Math.round((r.v1() - r.v0()) * pixels.height());
            // The drawing's canvas is the first field's shape square, `inner` units; each field's shape square is laid
            // over the whole of it. Field texel u (its square at spread..spread+inner) is drawing point
            // (u - spread) · inner / f.inner(), and texel u · w / side of the cell.
            double origin = -f.spread() * ((double) inner / f.inner());
            Sdg.Stencil stencil = new Sdg.Stencil(Sdg.Stencil.SDF, page, 0, x, y, w, h, origin, origin,
                    w / ((double) f.side() * inner / f.inner()), 2.0 * f.spread() * w / f.side());
            Color c = f.tint();
            int rgba = byte8(c.r()) << 24 | byte8(c.g()) << 16 | byte8(c.b()) << 8 | byte8(c.a());
            // With a tint, the drawing is an icon of one colour, in the node's tint; without one, each field's own.
            Sdg.Paint.Solid paint = tint != null ? new Sdg.Paint.Solid(rgba, TINT) : new Sdg.Paint.Solid(rgba);
            layers.add(new Sdg.Layer("field " + layers.size(), stencil, paint, 1f));
        }
        int[] widths = new int[pages.size()], heights = new int[pages.size()];
        for (int i = 0; i < pages.size(); i++) {
            widths[i] = pages.get(i).width();
            heights[i] = pages.get(i).height();
        }
        SdgDrawing drawing = new SdgDrawing(inner, inner, layers, widths, heights);
        return new ImageSdg(drawing, pages, tint, sdgLook(first.look()), first.instance(), null);
    }

    /**
     * A field's look as an SDG layer's: the core {@code field} source read as {@code sdgStencil}, and the core
     * {@code fieldFill} painted as {@code sdgPaint}, every other part as it is. Null for none, and for a look whose
     * source is another (a morph between two cells), which an SDG layer cannot draw, said once.
     */
    public static Look sdgLook(Look fieldLook) {
        if (fieldLook == null) {
            return null;
        }
        if (!fieldLook.source().shadelet().equals(CoreShadelets.FIELD.name())) {
            Diagnostics.dropped("ImageSdg.look/" + fieldLook.source().shadelet(),
                    "a field's look whose source is '" + fieldLook.source().shadelet() + "'",
                    "an SDG layer reads its own stencil, so the icon is drawn without the look");
            return null;
        }
        List<Look.Use> painters = new ArrayList<>();
        for (Look.Use p : fieldLook.painters()) {
            painters.add(p.shadelet().equals(CoreShadelets.FIELD_FILL.name())
                    ? Look.Use.of(CoreShadelets.SDG_PAINT.name()) : p);
        }
        return new Look(Look.Use.of(CoreShadelets.SDG_STENCIL.name(), fieldLook.source().shared()),
                fieldLook.modifiers(), painters);
    }

    /** The drawing's width over its height: what a box that shows it whole is shaped like. */
    public double aspect() {
        return drawing.width() / drawing.height();
    }

    /** This drawing with {@code tint} for its paints in the role {@value #TINT}; null for the theme's ink. */
    public ImageSdg tint(Color tint) {
        return new ImageSdg(drawing, pages, tint, look, instance, source);
    }

    /** This drawing's back-most layer drawn by {@code look} with {@code instance}; null for none. */
    public ImageSdg look(Look look, float... instance) {
        return new ImageSdg(drawing, pages, tint, look, instance, source);
    }

    /** The look's per-element values, a copy. */
    @Override
    public float[] instance() {
        return instance.clone();
    }

    /** The look's per-element values, as they are: the renderer's, which reads them every frame and keeps none. */
    public float[] instanceUnsafe() {
        return instance;
    }

    private static int byte8(float v) {
        return Math.round(Math.clamp(v, 0f, 1f) * 255f);
    }

    /** One page as the pixels a renderer uploads. Identity is the object's, as a {@link Pixels}' is. */
    private static final class Page implements Pixels {
        private final Sdg.Page page;

        Page(Sdg.Page page) {
            this.page = page;
        }

        @Override
        public int width() {
            return page.width();
        }

        @Override
        public int height() {
            return page.height();
        }

        @Override
        public byte[] rgba() {
            return page.rgba();
        }
    }
}
