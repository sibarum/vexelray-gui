package dev.vexelray.gui.core;

import dev.vexelray.canvas.SdgPages;
import dev.vexelray.fields.sdg.Sdg;

import java.util.ArrayList;
import java.util.List;

/**
 * A drawing in signed distance graphics a node draws ({@link Node#sdg}): its layers, each a distance field and a
 * paint, and its pages as {@link Pixels}, so the renderer uploads them to whichever device draws them, once, as it
 * does the icon atlas. The format is vexelray-fields' {@code docs/sdg.md}.
 *
 * <p>Made once per drawing and kept: identity is what uploads, so a new one per frame uploads every frame. {@link
 * #of(Sdg)} puts each layer's stencil and paint on one page first ({@link SdgPages#colocated}), as the canvas needs.
 *
 * @param drawing the drawing, every layer's stencil and paint on one page
 * @param pages   its pages, in order, to upload as they are (RGBA8, never sRGB-decoded)
 */
public record ImageSdg(Sdg drawing, List<Pixels> pages) {

    public ImageSdg {
        pages = List.copyOf(pages);
        if (drawing == null || pages.size() != drawing.pages().size()) {
            throw new IllegalArgumentException("an SDG drawing and " + pages.size() + " pages for it");
        }
    }

    /** {@code drawing} ready to draw: colocated, its pages as pixels made here. */
    public static ImageSdg of(Sdg drawing) {
        Sdg ready = SdgPages.colocated(drawing);
        List<Pixels> pages = new ArrayList<>();
        for (Sdg.Page page : ready.pages()) {
            pages.add(new Page(page));
        }
        return new ImageSdg(ready, pages);
    }

    /** The drawing's width over its height: what a box that shows it whole is shaped like. */
    public double aspect() {
        return drawing.width() / drawing.height();
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
