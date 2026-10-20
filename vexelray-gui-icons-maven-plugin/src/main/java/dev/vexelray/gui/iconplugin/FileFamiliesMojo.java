package dev.vexelray.gui.iconplugin;

import dev.vexelray.canvas.DistanceField;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bakes the file-type families into an atlas of their own: only a file browser draws them, and one that does wants
 * the whole set. A family is two fields, its sheet (shared with every family of its kind) and its emblem, both on the
 * 16-unit grid, so one grid of cells holds them: the sheets first, then the emblems. The colours stay out of the
 * atlas; each field is one solid colour, chosen where it is drawn, from the family's row of {@code families.tsv}.
 *
 * <p>Reads {@code <directory>/families.tsv}, {@code sheet/<sheet>.svg} and {@code emblem/<family>.svg}; writes
 * {@code file-icons.rgba} and the {@code FileSheet}, {@code FileFamily} and {@code AtlasLayout} sources under
 * {@code target}, added to the build. {@code FileIconAtlas} and {@code FileIconView} are the module's own.
 */
@Mojo(name = "file-families", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class FileFamiliesMojo extends AbstractMojo {

    static final int INNER = 48;
    static final int SPREAD = 12;
    static final int CELL = INNER + 2 * SPREAD;
    static final int COLUMNS = 8;
    static final int WIDTH = COLUMNS * CELL;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** The families' folder: {@code families.tsv}, {@code sheet/}, {@code emblem/}. */
    @Parameter(required = true)
    private File directory;

    @Parameter(defaultValue = "dev.vexelray.gui.fileicons")
    private String packageName;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/vexelray-icons")
    private File sourceDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-resources/vexelray-icons")
    private File resourceDirectory;

    @Parameter(defaultValue = "${project.build.directory}/vexelray-icons")
    private File workDirectory;

    @Parameter(property = "vexelray.icons.skip", defaultValue = "false")
    private boolean skip;

    /**
     * One family: its name, its sheet's name, its emblem (null for a family that is its sheet alone), its palette row
     * (a hue and an ink for a dark theme and for a light one, {@code #rrggbb}, the inks blank for a family without an
     * emblem) and the extensions it covers, space-separated.
     */
    record Family(String name, String sheet, Path emblem, String darkHue, String darkInk, String lightHue,
                  String lightInk, String extensions) {
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        project.addCompileSourceRoot(sourceDirectory.getPath());
        Resource resource = new Resource();
        resource.setDirectory(resourceDirectory.getPath());
        project.addResource(resource);
        if (skip) {
            return;
        }
        Path dir = directory.toPath();
        List<Family> families = new ArrayList<>();
        List<String> sheets = new ArrayList<>();
        Output.Stamp stamp = new Output.Stamp().add(packageName);
        try {
            Path table = dir.resolve("families.tsv");
            stamp.add(table);
            List<String> rows = Files.readAllLines(table, StandardCharsets.UTF_8);
            for (String row : rows.subList(1, rows.size())) {
                if (row.isBlank()) {
                    continue;
                }
                String[] f = row.split("\t", -1);
                if (f.length < 7) {
                    throw new MojoFailureException("families.tsv has a row of " + f.length + " columns, not 7: " + row);
                }
                Path emblem = dir.resolve("emblem/" + f[0] + ".svg");
                families.add(new Family(f[0], f[1], Files.exists(emblem) ? emblem : null, f[2], f[3], f[4], f[5],
                        f[6].strip()));
                if (!sheets.contains(f[1])) {
                    sheets.add(f[1]);
                }
            }
            if (families.stream().noneMatch(f -> f.name.equals("unknown"))) {
                throw new MojoFailureException("families.tsv has no unknown family, which an unclaimed extension gets");
            }
            for (String sheet : sheets) {
                stamp.add(dir.resolve("sheet/" + sheet + ".svg"));
            }
            for (Family f : families) {
                if (f.emblem != null) {
                    stamp.add(f.emblem);
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("cannot read the file families at " + dir, e);
        }

        String folder = packageName.replace('.', '/');
        Path rgba = resourceDirectory.toPath().resolve(folder).resolve("file-icons.rgba");
        Path sources = sourceDirectory.toPath().resolve(folder);
        Path stampFile = workDirectory.toPath().resolve(packageName + ".file-families.stamp");
        String hex = stamp.hex();
        try {
            if (Files.isRegularFile(stampFile) && Files.readString(stampFile).equals(hex) && Files.isRegularFile(rgba)
                    && Files.isRegularFile(sources.resolve("FileFamily.java"))) {
                getLog().info(families.size() + " file families, up to date.");
                return;
            }
            Files.deleteIfExists(stampFile);

            int cells = sheets.size() + (int) families.stream().filter(f -> f.emblem != null).count();
            int height = Atlas.ceil(cells, COLUMNS) * CELL;
            byte[] atlas = new byte[WIDTH * height * 4];
            for (int i = 3; i < atlas.length; i += 4) {
                atlas[i] = (byte) 255;
            }
            int next = 0;
            for (String sheet : sheets) {
                place(atlas, dir.resolve("sheet/" + sheet + ".svg"), next++);
            }
            int[] emblem = new int[families.size()];
            for (int i = 0; i < families.size(); i++) {
                emblem[i] = families.get(i).emblem == null ? -1 : next;
                if (emblem[i] >= 0) {
                    place(atlas, families.get(i).emblem, next++);
                }
            }
            Output.write(rgba, Output.pixels(atlas, WIDTH, height));
            Output.write(sources.resolve("FileSheet.java"), sheetEnum(sheets));
            Output.write(sources.resolve("FileFamily.java"), familyEnum(families, emblem));
            Output.write(sources.resolve("AtlasLayout.java"), layout(height));
            Path picture = workDirectory.toPath().resolve("file-icons.png");
            Output.picture(picture, atlas, WIDTH, height);
            Files.createDirectories(stampFile.getParent());
            Files.writeString(stampFile, hex);
            getLog().info(families.size() + " file families (" + sheets.size() + " sheets, " + (cells - sheets.size())
                    + " emblems) in a " + WIDTH + " x " + height + " atlas; " + picture);
        } catch (IOException | RuntimeException e) {
            throw new MojoExecutionException("baking the file families failed: " + e.getMessage(), e);
        }
    }

    private static void place(byte[] atlas, Path svg, int cell) throws IOException {
        DistanceField f = DistanceField.of(Mask.read(svg, DistanceField.maskSide(INNER), Mask.Coverage.ALPHA, false),
                INNER, SPREAD);
        int x0 = (cell % COLUMNS) * CELL;
        int y0 = (cell / COLUMNS) * CELL;
        for (int y = 0; y < CELL; y++) {
            System.arraycopy(f.rgba(), y * CELL * 4, atlas, ((y0 + y) * WIDTH + x0) * 4, CELL * 4);
        }
    }

    private String sheetEnum(List<String> sheets) {
        StringBuilder s = new StringBuilder(Sources.HEADER).append("package ").append(packageName).append(";\n\n");
        s.append("""
                /**
                 * The silhouettes every file icon is cut from, one distance field each: what kind of thing a file is.
                 * Every {@link FileFamily} of a kind shares its sheet, filled with the family's own colour.
                 *
                 * <p>Generated by {@code vexelray-gui-icons-maven-plugin} from the families' {@code sheet} folder.
                 * Do not edit: change the art and build again.
                 */
                public enum FileSheet implements dev.vexelray.gui.core.IconSource {

                """);
        for (int i = 0; i < sheets.size(); i++) {
            s.append("    /** {@code ").append(sheets.get(i)).append("}. */\n");
            s.append("    ").append(Sources.constant(sheets.get(i))).append("(").append(i).append(")")
                    .append(i == sheets.size() - 1 ? ";\n" : ",\n");
        }
        s.append("""

                    private final int cell;

                    FileSheet(int cell) {
                        this.cell = cell;
                    }

                    /** The sheet as a field to hand {@code Node.field}, in {@code tint}. One drawing serves every size. */
                    @Override
                    public dev.vexelray.gui.core.ImageField field(float px, dev.vexelray.canvas.Color tint) {
                        return AtlasLayout.cell(cell, tint);
                    }
                }
                """);
        return s.toString();
    }

    private String familyEnum(List<Family> families, int[] emblem) {
        StringBuilder s = new StringBuilder(Sources.HEADER).append("package ").append(packageName).append(";\n\n");
        s.append("""
                /**
                 * Every family of file, by name: its {@link FileSheet} and its emblem, two distance fields drawn one over
                 * the other, the sheet in the family's colour and the emblem in ink. The emblem lies inside the sheet
                 * already, so the two fields are drawn in the same box at the same size.
                 *
                 * <p>Each family carries the canvas's palette row for it, a hue and an ink for a dark theme and for a
                 * light one, and the extensions it covers; {@code FileIconView} draws one in those colours.
                 *
                 * <p>Generated by {@code vexelray-gui-icons-maven-plugin} from the families' {@code families.tsv} and
                 * art. Do not edit: change the art and build again.
                 */
                public enum FileFamily {

                """);
        for (int i = 0; i < families.size(); i++) {
            Family f = families.get(i);
            s.append("    /** {@code ").append(f.name()).append("}").append(f.emblem() == null ? ": the sheet alone" : "")
                    .append(". */\n");
            s.append("    ").append(Sources.constant(f.name())).append("(\"").append(f.name()).append("\", FileSheet.")
                    .append(Sources.constant(f.sheet())).append(", ").append(emblem[i]).append(",\n            ")
                    .append(hex(f.darkHue())).append(", ").append(hex(f.darkInk())).append(", ")
                    .append(hex(f.lightHue())).append(", ").append(hex(f.lightInk())).append(", \"")
                    .append(f.extensions()).append("\")").append(i == families.size() - 1 ? ";\n" : ",\n");
        }
        s.append("""

                    private static final java.util.Map<String, FileFamily> BY_EXTENSION = byExtension();

                    private final String file;
                    private final FileSheet sheet;
                    private final int emblem;
                    private final dev.vexelray.canvas.Color darkHue;
                    private final dev.vexelray.canvas.Color darkInk;
                    private final dev.vexelray.canvas.Color lightHue;
                    private final dev.vexelray.canvas.Color lightInk;
                    private final java.util.List<String> extensions;

                    FileFamily(String file, FileSheet sheet, int emblem, int darkHue, int darkInk, int lightHue,
                               int lightInk, String extensions) {
                        this.file = file;
                        this.sheet = sheet;
                        this.emblem = emblem;
                        this.darkHue = dev.vexelray.canvas.Color.rgb(darkHue);
                        this.darkInk = darkInk < 0 ? null : dev.vexelray.canvas.Color.rgb(darkInk);
                        this.lightHue = dev.vexelray.canvas.Color.rgb(lightHue);
                        this.lightInk = lightInk < 0 ? null : dev.vexelray.canvas.Color.rgb(lightInk);
                        this.extensions = extensions.isEmpty() ? java.util.List.of() : java.util.List.of(extensions.split(" "));
                    }

                    /** The family's name in the set: {@code vexelray-icons/file-types/families/emblem/<name>.svg}. */
                    public String file() {
                        return file;
                    }

                    /** The silhouette, drawn first, in the family's colour. */
                    public FileSheet sheet() {
                        return sheet;
                    }

                    /** The emblem, drawn over the sheet in ink; null for a family that is its sheet alone. */
                    public dev.vexelray.gui.core.IconSource emblem() {
                        return emblem < 0 ? null : (px, tint) -> AtlasLayout.cell(emblem, tint);
                    }

                    /** The sheet's colour on a dark theme or a light one: the canvas's palette row. */
                    public dev.vexelray.canvas.Color hue(boolean dark) {
                        return dark ? darkHue : lightHue;
                    }

                    /** The emblem's colour on a dark theme or a light one; null for a family that is its sheet alone. */
                    public dev.vexelray.canvas.Color ink(boolean dark) {
                        return dark ? darkInk : lightInk;
                    }

                    /** The extensions this family covers, lower case and without the dot; none for folders and unknown. */
                    public java.util.List<String> extensions() {
                        return extensions;
                    }

                    /** The family named {@code file} in the set, or null if there is none. */
                    public static FileFamily named(String file) {
                        for (FileFamily family : values()) {
                            if (family.file.equals(file)) {
                                return family;
                            }
                        }
                        return null;
                    }

                    /** The family of an extension, without its dot, in any case; {@link #UNKNOWN} when none claims it. */
                    public static FileFamily ofExtension(String extension) {
                        return BY_EXTENSION.getOrDefault(extension.toLowerCase(java.util.Locale.ROOT), UNKNOWN);
                    }

                    private static java.util.Map<String, FileFamily> byExtension() {
                        java.util.Map<String, FileFamily> m = new java.util.HashMap<>();
                        for (FileFamily family : values()) {
                            for (String e : family.extensions) {
                                FileFamily other = m.putIfAbsent(e, family);
                                if (other != null) {
                                    throw new IllegalStateException("." + e + " is claimed by " + other + " and " + family);
                                }
                            }
                        }
                        return java.util.Map.copyOf(m);
                    }
                }
                """);
        return s.toString();
    }

    /** A palette entry as a Java int literal; {@code -1} for a blank one. */
    private static String hex(String color) {
        return color.isBlank() ? "-1" : "0x" + color.strip().substring(1).toLowerCase(Locale.ROOT);
    }

    private String layout(int height) {
        return Sources.HEADER + "package " + packageName + ";\n\n" + """
                import dev.vexelray.canvas.Color;
                import dev.vexelray.gui.core.ImageField;
                import dev.vexelray.gui.core.ImageRegion;

                /** Where each cell of the file-icon atlas is. Generated by {@code vexelray-gui-icons-maven-plugin} with the atlas. */
                final class AtlasLayout {

                    static final int WIDTH = %d;
                    static final int HEIGHT = %d;
                    static final int INNER = %d;
                    static final int SPREAD = %d;
                    static final int CELL = INNER + 2 * SPREAD;
                    static final int COLUMNS = %d;

                    private AtlasLayout() {
                    }

                    static ImageField cell(int cell, Color tint) {
                        int x = (cell %% COLUMNS) * CELL;
                        int y = (cell / COLUMNS) * CELL;
                        ImageRegion region = new ImageRegion((float) x / WIDTH, (float) y / HEIGHT,
                                (float) (x + CELL) / WIDTH, (float) (y + CELL) / HEIGHT);
                        return new ImageField(FileIconAtlas.INSTANCE, region, CELL, SPREAD, tint);
                    }
                }
                """.formatted(WIDTH, height, INNER, SPREAD, COLUMNS);
    }
}
