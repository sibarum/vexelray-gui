package dev.vexelray.gui.demo.chapter;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.ImageRegion;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.demo.Chapter;
import dev.vexelray.gui.demo.Console;
import dev.vexelray.gui.demo.Stage;
import dev.vexelray.gui.demo.Ui;
import dev.vexelray.vulkan.present.SampledImage;
import sibarum.imagelib.Frames;
import sibarum.imagelib.Imagelib;
import sibarum.kronometer.Dur;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pictures the application brought with it — and the division of labour that makes them cheap.
 *
 * <p><b>The framework decodes nothing.</b> No module in this repo names an image format, and this chapter is
 * where you can see why that costs nothing: {@code GuiApp.texture(rgba, w, h)} takes the one layout every
 * sampler agrees on, and turning a PNG or an SVG into that layout is the application's business. Here it is
 * {@code imagelib-wrapper} — Panama bindings over one C ABI — and swapping it for something else would touch
 * this file and no other.
 *
 * <p><b>A node showing a picture is still a box.</b> It sizes by flex, rounds at its corners, takes a border,
 * clips, fades and translates with its subtree, because none of those ever knew what was inside a box. That is
 * the same claim a viewport makes (architecture.md §6.9) and it is why there is no image node kind.
 *
 * <p><b>The sheet is the point of the page.</b> Ten marks arrive as one 640x256 PNG, upload once, and are shown
 * by ten nodes that each name a rectangle of it. One texture, one descriptor set, one run, one draw — and the
 * animation below costs nothing on top, because advancing a frame writes four floats and never touches the GPU.
 * Watch the frame counter against the upload counter: one keeps going up.
 *
 * <p><b>The vector row is the other verb.</b> A raster file dictates its own pixel size; an SVG has none until a
 * layout gives it one. So the three marks on the bottom row are three <em>rasterisations</em> of one document at
 * three sizes, not one raster scaled three ways — which is what resolution independence actually costs, and all
 * that it costs.
 *
 * <p><b>This page is blank under {@code --capture}, and that is not a bug here.</b> The headless path builds the
 * whole tree and its vertex buffer <em>before</em> {@code GuiApp.capture} creates a device, so there is no moment
 * at which an application could upload a texture: {@link Stage#onApp} is never drained and the wells stay empty.
 * Every other chapter survives that because it uses {@code onApp} for handlers rather than for content, and this
 * is the first one whose content needs a device. Fixing it means capture creating its device first and offering
 * the application a hook before the tree is built — a change to the framework, not to this page.
 */
public final class ImageChapter implements Chapter {

    /** The sheet's grid — five across, two down, 128px cells. See vexelray-icons/README.md. */
    private static final int COLUMNS = 5;
    private static final int ROWS = 2;
    private static final int CELLS = COLUMNS * ROWS;

    private static final Length CELL = Length.rem(3.5f);
    private static final Length CORNER = Length.rem(0.5f);

    /** Slow enough to read as a sequence of marks rather than as a flicker. */
    private static final Dur FRAME = Dur.ms(500);

    private final AtomicInteger frame = new AtomicInteger();

    @Override
    public String title() {
        return "Images";
    }

    @Override
    public String blurb() {
        return "One decoded sheet, ten nodes showing a rectangle of it each, and an animation that costs no "
                + "upload — plus the same SVG rasterised at three sizes, because a vector has none of its own.";
    }

    @Override
    public Node build(Stage stage) {
        Gui gui = stage.gui();
        Theme theme = stage.theme();

        // Built empty and filled in when the window exists: a texture belongs to a device, and there is no
        // device until there is a window. Stage.onApp is exactly this shape of problem — see its javadoc.
        Node whole = plate(gui, theme, Length.rem(14f), Length.rem(5.6f));
        Node[] cells = new Node[CELLS];
        for (int i = 0; i < CELLS; i++) {
            cells[i] = plate(gui, theme, CELL, CELL);
        }
        Node player = plate(gui, theme, Length.rem(5.5f), Length.rem(5.5f));
        Node caption = Ui.prose(gui, "decoding…");

        Node[] vectors = {
                plate(gui, theme, Length.rem(1.5f), Length.rem(1.5f)),
                plate(gui, theme, Length.rem(3f), Length.rem(3f)),
                plate(gui, theme, Length.rem(6f), Length.rem(6f)),
        };

        stage.onApp(app -> load(app, stage, whole, cells, player, caption, vectors));

        return gui.column().width(Length.FILL).height(Length.FILL).scroll(false, true).gap(Ui.GAP)
                .children(
                        Ui.strip(gui,
                                Ui.heading(gui, "Decoded, then uploaded once"),
                                Ui.prose(gui, "A 640x256 PNG becomes RGBA8 in the application and a "
                                        + "SampledImage in one call. The node showing it is an ordinary box: "
                                        + "the rounded corners and the border below are the node's, not the "
                                        + "picture's."),
                                row(gui, whole)),
                        Ui.strip(gui,
                                Ui.heading(gui, "One texture, ten pictures"),
                                Ui.prose(gui, "Each of these names a rectangle of that same texture with "
                                        + "ImageRegion. Nothing was uploaded a second time, the bound handle "
                                        + "never changes, and the whole row is one run and one draw."),
                                row(gui, cells)),
                        Ui.strip(gui,
                                Ui.heading(gui, "A frame is four floats"),
                                Ui.prose(gui, "The same texture again, with the region advanced on the "
                                        + "gallery's clock. An animation is a sheet and a moving rectangle — "
                                        + "there is no per-frame upload here, and nothing to tear."),
                                row(gui, player),
                                caption),
                        Ui.strip(gui,
                                Ui.heading(gui, "A vector has no size until you give it one"),
                                Ui.prose(gui, "Three rasterisations of one SVG at three sizes — not one "
                                        + "raster scaled three ways. Look at the thin strokes: they are the "
                                        + "same weight in each, which is the thing a scaled bitmap cannot do."),
                                row(gui, vectors)));
    }

    /** A well to draw a picture in, so an image that is transparent still reads as a thing on the page. */
    private static Node plate(Gui gui, Theme theme, Length w, Length h) {
        return gui.box().size(w, h).corner(CORNER)
                .background(theme.color(Role.WELL))
                .border(Length.rem(0.1f), theme.color(Role.LINE));
    }

    private static Node row(Gui gui, Node... children) {
        return gui.row().width(Length.FILL).height(Length.AUTO).gap(Ui.GAP)
                .alignItems(AlignItems.CENTER).scroll(false, false)
                .children(children);
    }

    /**
     * Decode, upload, and hand out regions — the whole of what an application has to do, and the reason this
     * method is short.
     *
     * <p>Failure is reported and survivable on purpose. A missing asset or a decoder that will not load must
     * leave a gallery page with empty wells and a line in the activity rail, not take the window down: the
     * point being demonstrated is that an image is a prop on an ordinary box, and a box with no image is the
     * same box.
     */
    private void load(GuiApp app, Stage stage, Node whole, Node[] cells, Node player, Node caption,
                      Node[] vectors) {
        Console console = stage.console();
        Gui gui = stage.gui();
        try {
            Frames sheet = Imagelib.decode(read("/demo/marks-sheet.png"));
            // One upload, for everything above the vector row.
            SampledImage texture = app.texture(sheet.rgba(), sheet.width(), sheet.height());
            console.good("decoded " + sheet + " and uploaded it once");

            whole.image(texture);
            for (int i = 0; i < cells.length; i++) {
                cells[i].image(texture, ImageRegion.cell(i, COLUMNS, ROWS));
            }
            player.image(texture, ImageRegion.cell(0, COLUMNS, ROWS));

            // The animation: one prop write per tick, and the texture is never touched again.
            stage.krono().every(FRAME, () -> {
                int next = frame.incrementAndGet();
                player.image(texture, ImageRegion.cell(next % CELLS, COLUMNS, ROWS));
                caption.text("frame " + next + " — uploads: 1");
            });
            caption.text("frame 0 — uploads: 1");

            // The other verb: the box decides, so each size is its own rasterisation.
            byte[] document = read("/demo/mark.svg");
            Imagelib.Size intrinsic = Imagelib.svgSize(document);
            int[] px = {24, 48, 96};
            for (int i = 0; i < vectors.length; i++) {
                Frames raster = Imagelib.rasterize(document, px[i], px[i]);
                vectors[i].image(app.texture(raster.rgba(), raster.width(), raster.height()));
            }
            console.note("the SVG asks to be " + Math.round(intrinsic.width()) + "px; rasterised at 24, 48 "
                    + "and 96 instead, once each");
        } catch (RuntimeException e) {
            // Named, not swallowed: a page that quietly shows nothing is worse than one that says why.
            console.refused("images unavailable: " + e.getMessage());
            caption.text("images unavailable — see the activity rail");
            caption.textColor(gui.theme().color(Role.DIM));
        }
    }

    private static byte[] read(String resource) {
        try (InputStream in = ImageChapter.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new UncheckedIOException(new IOException("asset missing from the jar: " + resource));
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + resource, e);
        }
    }
}
