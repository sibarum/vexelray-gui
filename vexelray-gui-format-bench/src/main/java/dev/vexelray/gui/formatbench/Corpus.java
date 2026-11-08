package dev.vexelray.gui.formatbench;

import dev.vexelray.fields.art.Svg;
import dev.vexelray.fields.sdg.Png;
import dev.vexelray.fields.sdg.Sdg;
import dev.vexelray.fields.sdg.SdgFile;
import dev.vexelray.fields.sdg.SvgToSdg;
import sibarum.imagelib.Frames;
import sibarum.imagelib.Imagelib;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The art every format is measured on, the suite's own marks from simple to busy, and each in every form: the SVG it
 * is drawn in; the SDG made from it ({@value #TEXELS} texels across, as the icon plugin makes a mark), compiled and as
 * XML; and a PNG of it at 256 and at 1024 pixels, as an application ships a picture, drawn by imagelib.
 */
final class Corpus {

    /** The SDG's resolution: its stencils' texels across the drawing's longer side. */
    static final int TEXELS = 128;

    /** The sizes every contender draws at, in pixels square. */
    static final int[] SIZES = {64, 256, 1024};

    /** The rasters shipped, in pixels square. */
    static final int[] SHIPPED = {256, 1024};

    /** What each piece of art is, in words, in the order the document lists them. */
    static final Map<String, String> ART = new LinkedHashMap<>();

    static {
        ART.put("badge", "a gradient, flat fills, a stroke and a star: vexelray-fields' test badge");
        ART.put("rasterware", "Rasterware's mark: rounded panels, bars and a needle");
        ART.put("vexelray-logo", "the VexelRay mark: facets, an orb with a gradient");
        ART.put("mainframe", "MainFrame's mark: a terminal, text strokes");
        ART.put("background-icon", "a hidden window's icon: a dashed rim and a group at half opacity");
        ART.put("file-icon", "a file-type icon: a page cut by a clip path");
    }

    /** One piece of art in every form. */
    record Art(String name, String what, byte[] svg, Svg parsed, Sdg sdg, byte[] sdgb, byte[] sdgXml,
               Map<Integer, byte[]> png, Map<Integer, Frames> raster) {
    }

    private static final Map<String, Art> LOADED = new LinkedHashMap<>();

    static synchronized Art get(String name) {
        return LOADED.computeIfAbsent(name, Corpus::load);
    }

    static List<String> names() {
        return List.copyOf(ART.keySet());
    }

    private static Art load(String name) {
        byte[] svg;
        try (InputStream in = Corpus.class.getResourceAsStream("/corpus/" + name + ".svg")) {
            if (in == null) {
                throw new IllegalArgumentException("no art " + name);
            }
            svg = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Svg parsed = Svg.parse(new String(svg, StandardCharsets.UTF_8));
        Sdg sdg = SvgToSdg.convert(parsed, SvgToSdg.Options.across(parsed, TEXELS));
        Map<Integer, byte[]> png = new LinkedHashMap<>();
        Map<Integer, Frames> raster = new LinkedHashMap<>();
        for (int side : SHIPPED) {
            Frames f = Imagelib.rasterize(svg, side, side);
            png.put(side, Png.write(f.width(), f.height(), f.rgba()));
            raster.put(side, Imagelib.decode(png.get(side)));
        }
        return new Art(name, ART.get(name), svg, parsed, sdg, SdgFile.bytes(sdg),
                SdgFile.xml(sdg).getBytes(StandardCharsets.UTF_8), png, raster);
    }
}
