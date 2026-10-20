package dev.vexelray.gui.iconplugin;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;

/**
 * What a baked set says about itself, so another can extend it: written into the set's jar beside its atlas, at
 * {@code META-INF/vexelray-icons/<enum class>.properties}, and read off the class path of a project that extends it.
 * It holds the set's grids, which an extension takes, and every name the set and its own base use, which an
 * extension may not take: an application's {@code save} beside the suite's {@code Icon.SAVE} would be two drawings
 * of one thing, and which one a screen shows would depend on which enum it happened to import.
 *
 * @param className the generated enum, fully qualified: the set's identity, and what {@code <extends>} names
 * @param grids     the cell sizes it baked with
 * @param names     its own icons' names
 * @param inherited every name of the sets it extends, all the way down
 */
record SetManifest(String className, Grids grids, List<String> names, List<String> inherited) {

    static final String FOLDER = "META-INF/vexelray-icons/";

    static String resource(String className) {
        return FOLDER + className + ".properties";
    }

    /** Every name this set and its bases hold. */
    List<String> reserved() {
        List<String> all = new ArrayList<>(inherited);
        all.addAll(names);
        return all;
    }

    /**
     * As properties text, keys sorted and no date line, so the same set writes the same bytes. Nothing in it needs
     * escaping: names are lower case words and hyphens, and a class name is an identifier.
     */
    String text() {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("class", className);
        p.put("large.inner", Integer.toString(grids.largeInner()));
        p.put("large.spread", Integer.toString(grids.largeSpread()));
        p.put("small.inner", Integer.toString(grids.smallInner()));
        p.put("small.spread", Integer.toString(grids.smallSpread()));
        p.put("names", String.join(",", names));
        p.put("inherited", String.join(",", inherited));
        StringBuilder s = new StringBuilder("# Written by vexelray-gui-icons-maven-plugin: what a set extending ")
                .append(className).append(" takes from it.\n");
        p.forEach((k, v) -> s.append(k).append('=').append(v).append('\n'));
        return s.toString();
    }

    static SetManifest read(InputStream in) throws IOException {
        Properties p = new Properties();
        p.load(in);
        return new SetManifest(p.getProperty("class"),
                new Grids(integer(p, "large.inner"), integer(p, "large.spread"), integer(p, "small.inner"),
                        integer(p, "small.spread")),
                list(p.getProperty("names")), list(p.getProperty("inherited")));
    }

    private static int integer(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null) {
            throw new IllegalArgumentException("an icon set manifest without " + key);
        }
        return Integer.parseInt(v.strip());
    }

    private static List<String> list(String v) {
        return v == null || v.isBlank() ? List.of() : Arrays.stream(v.split(",")).map(String::strip).toList();
    }
}
