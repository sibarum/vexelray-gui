package dev.vexelray.gui.formatbench;

import dev.vexelray.fields.art.Svg;
import dev.vexelray.fields.sdg.SdgFile;
import sibarum.imagelib.Imagelib;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Every timed case, keyed {@code "draw|art|CONTENDER|side"} or {@code "load|art|form"}: each a function of nothing,
 * its input made beforehand, run once an operation.
 */
final class Cases {

    /** The forms a piece of art is loaded from, and what loading it is. */
    static final String[] FORMS = {"SVG", "SDG .sdgb", "SDG .sdg", "PNG 256", "PNG 1024"};

    private Cases() {
    }

    static Map<String, Supplier<Object>> all() {
        Map<String, Supplier<Object>> m = new LinkedHashMap<>();
        for (String name : Corpus.names()) {
            for (String form : FORMS) {
                m.put("load|" + name + "|" + form, () -> load(Corpus.get(name), form));
            }
            for (Contender c : Contender.all()) {
                for (int side : Corpus.SIZES) {
                    m.put("draw|" + name + "|" + c.name() + "|" + side, () -> c.draw(Corpus.get(name), side));
                }
            }
        }
        return m;
    }

    /** Loading {@code form} of {@code art}: from its bytes to what draws. */
    static Object load(Corpus.Art art, String form) {
        return switch (form) {
            case "SVG" -> Svg.parse(new String(art.svg(), StandardCharsets.UTF_8));
            case "SDG .sdgb" -> SdgFile.read(art.sdgb());
            case "SDG .sdg" -> SdgFile.read(art.sdgXml());
            case "PNG 256" -> Imagelib.decode(art.png().get(256));
            case "PNG 1024" -> Imagelib.decode(art.png().get(1024));
            default -> throw new IllegalArgumentException("no form " + form);
        };
    }

    /** The bytes {@code form} of {@code art} is on disk. */
    static int bytes(Corpus.Art art, String form) {
        return switch (form) {
            case "SVG" -> art.svg().length;
            case "SDG .sdgb" -> art.sdgb().length;
            case "SDG .sdg" -> art.sdgXml().length;
            case "PNG 256" -> art.png().get(256).length;
            case "PNG 1024" -> art.png().get(1024).length;
            default -> throw new IllegalArgumentException("no form " + form);
        };
    }
}
