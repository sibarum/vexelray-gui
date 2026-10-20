package dev.vexelray.gui.iconplugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Every icon a set's configuration names, in name order: each folder's images, then each icon named outright, two
 * drawings of one name at one size refused, and a name its base already holds refused.
 */
final class Gather {

    /** What imagelib reads, by extension: SVG and every raster it decodes. */
    static final Set<String> EXTENSIONS = Set.of("svg", "svgz", "png", "jpg", "jpeg", "bmp", "gif", "tga", "tif",
            "tiff", "webp", "qoi", "ico", "pnm", "pbm", "pgm", "ppm");

    private Gather() {
    }

    /**
     * The icons in {@code directories} and {@code icons}, relative paths against {@code base}; none of them named in
     * {@code reserved} (the names of the set extended, which is {@code extended} in a message).
     */
    static List<Atlas.Entry> entries(Path base, List<ArtDirectory> directories, List<IconArt> icons,
                                     List<String> reserved, String extended) throws IOException {
        Map<String, Drawing[]> found = new TreeMap<>();
        for (ArtDirectory d : directories) {
            if (d.path == null) {
                throw new IllegalArgumentException("a <directory> without a <path>");
            }
            Path dir = base.resolve(d.path.toPath());
            if (!Files.isDirectory(dir) && d.optional) {
                continue;
            }
            if (!Files.isDirectory(dir)) {
                throw new IllegalArgumentException("no folder of icons at " + dir);
            }
            int slot = slot(d.size, dir.toString());
            String suffix = d.suffix == null ? "" : d.suffix;
            Mask.Coverage coverage = Mask.Coverage.parse(d.coverage);
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                    String file = p.getFileName().toString();
                    int dot = file.lastIndexOf('.');
                    if (dot < 0 || !EXTENSIONS.contains(file.substring(dot + 1).toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    String stem = file.substring(0, dot);
                    if (!stem.endsWith(suffix)) {
                        continue;
                    }
                    put(found, stem.substring(0, stem.length() - suffix.length()), slot,
                            new Drawing(p, coverage, d.invert));
                }
            }
        }
        for (IconArt a : icons) {
            if (a.name == null || a.small == null && a.large == null) {
                throw new IllegalArgumentException("an <icon> wants a <name> and a <small> or <large> drawing, or both");
            }
            Mask.Coverage coverage = Mask.Coverage.parse(a.coverage);
            for (int slot = 0; slot < 2; slot++) {
                File f = slot == 0 ? a.small : a.large;
                if (f == null) {
                    continue;
                }
                Path p = base.resolve(f.toPath());
                if (!Files.isRegularFile(p)) {
                    throw new IllegalArgumentException("icon '" + a.name + "' names " + p + ", which is not there");
                }
                put(found, a.name, slot, new Drawing(p, coverage, a.invert));
            }
        }
        List<Atlas.Entry> entries = new ArrayList<>();
        for (Map.Entry<String, Drawing[]> e : found.entrySet()) {
            String name = e.getKey();
            if (!Sources.NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("'" + name + "' (" + any(e.getValue()) + ") is not a name: lower case"
                        + " letters and digits, in words joined by hyphens");
            }
            Sources.constant(name);
            if (reserved.contains(name)) {
                throw new IllegalArgumentException("'" + name + "' (" + any(e.getValue()) + ") is already in " + extended
                        + ", as " + Sources.constant(name) + ". Use that one, or name this drawing for what it is"
                        + " that the other is not.");
            }
            entries.add(new Atlas.Entry(name, e.getValue()[0], e.getValue()[1]));
        }
        return entries;
    }

    private static int slot(String size, String where) {
        String s = size == null ? "" : size.strip().toLowerCase(Locale.ROOT);
        if (s.equals("small")) {
            return 0;
        }
        if (s.equals("large")) {
            return 1;
        }
        throw new IllegalArgumentException("<size> of " + where + " is '" + size + "', neither small nor large");
    }

    private static void put(Map<String, Drawing[]> found, String name, int slot, Drawing d) {
        Drawing[] pair = found.computeIfAbsent(name, k -> new Drawing[2]);
        if (pair[slot] != null) {
            throw new IllegalArgumentException("two " + (slot == 0 ? "small" : "large") + " drawings of '" + name
                    + "': " + pair[slot].file() + " and " + d.file());
        }
        pair[slot] = d;
    }

    private static Path any(Drawing[] pair) {
        return pair[0] != null ? pair[0].file() : pair[1].file();
    }
}
