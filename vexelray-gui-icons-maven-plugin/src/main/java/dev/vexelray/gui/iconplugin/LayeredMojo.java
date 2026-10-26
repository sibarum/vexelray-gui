package dev.vexelray.gui.iconplugin;

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
import java.util.stream.Stream;

/**
 * Bakes a set of icons in colours of their own: every drawing taken apart into one mask per colour ({@link SvgLayers}),
 * each made a distance field, all packed into one atlas, and named by a generated enum that implements
 * {@code dev.vexelray.gui.core.LayeredIcon}. An {@code IconView} draws one as its layers one over the other, each in its
 * colour, at any size from one texture, as the file-type families are drawn; anything that draws in one colour gets
 * its silhouette, baked as a cell of its own.
 *
 * <p>The art is a folder of {@code dark/<name>.svg} and {@code light/<name>.svg}, as {@code vexelray-icons/java} is:
 * the same drawing in two palettes. The shapes come from the dark one, and the light one must draw the same shapes in
 * the same order, or the build fails naming it; the colours come from each.
 *
 * <pre>
 * &lt;execution&gt;
 *   &lt;id&gt;java-icons&lt;/id&gt;
 *   &lt;goals&gt;&lt;goal&gt;layered&lt;/goal&gt;&lt;/goals&gt;
 *   &lt;configuration&gt;
 *     &lt;packageName&gt;com.example.editor&lt;/packageName&gt;
 *     &lt;enumName&gt;JavaIcon&lt;/enumName&gt;
 *     &lt;directory&gt;src/main/icons/java&lt;/directory&gt;
 *   &lt;/configuration&gt;
 * &lt;/execution&gt;
 * </pre>
 */
@Mojo(name = "layered", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class LayeredMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** The generated enum's package, and the atlas resource's folder. */
    @Parameter(required = true)
    private String packageName;

    /** The generated enum. */
    @Parameter(required = true)
    private String enumName;

    /** The art: {@code dark/<name>.svg} and {@code light/<name>.svg}. */
    @Parameter(required = true)
    private File directory;

    /** The atlas's file name in the package's folder, without {@code .rgba}; the enum's name in kebab case if unset. */
    @Parameter
    private String atlasName;

    /**
     * A layer's square, in texels. The small icons' 48 by default: these are drawn at the sizes a row or a tab shows,
     * and a set of many layers wants cells no larger than that needs.
     */
    @Parameter(defaultValue = "48")
    private int inner;

    /** A layer's margin, in texels: a quarter of the square, as far as a glow or a shadow reaches. */
    @Parameter(defaultValue = "12")
    private int spread;

    /** The atlas's width in texels; as near square as fits if unset. */
    @Parameter
    private Integer width;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/vexelray-icons")
    private File sourceDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-resources/vexelray-icons")
    private File resourceDirectory;

    /** Where the stamp, the layers' masks and the atlas's picture ({@code <enum>.png}, the fields as grey) go. */
    @Parameter(defaultValue = "${project.build.directory}/vexelray-icons")
    private File workDirectory;

    @Parameter(property = "vexelray.icons.skip", defaultValue = "false")
    private boolean skip;

    /** One icon: its name, and its silhouette and layers as masks, with each layer's dark and light colour. */
    record Icon(String name, String silhouette, List<String> masks, List<String> dark, List<String> light) {
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
        String className = packageName + "." + enumName;
        Path dir = directory.toPath();
        List<Path> darks;
        try (Stream<Path> s = Files.list(dir.resolve("dark"))) {
            darks = s.filter(p -> p.getFileName().toString().endsWith(".svg")).sorted().toList();
        } catch (IOException e) {
            throw new MojoExecutionException("cannot list " + dir.resolve("dark"), e);
        }
        if (darks.isEmpty()) {
            throw new MojoFailureException(className + " has no icons: " + dir.resolve("dark") + " holds no SVG.");
        }

        Grids grids = new Grids(inner, spread, inner, spread);
        Output.Stamp stamp = new Output.Stamp().add(className).add(grids.toString()).add(String.valueOf(width))
                .add(String.valueOf(atlasName));
        List<Icon> icons = new ArrayList<>();
        try {
            for (Path dark : darks) {
                String file = dark.getFileName().toString();
                String name = file.substring(0, file.length() - ".svg".length());
                if (!Sources.NAME.matcher(name).matches()) {
                    throw new MojoFailureException(dark + ": an icon's name is lower-case words joined by hyphens");
                }
                Path light = dir.resolve("light").resolve(file);
                if (!Files.isRegularFile(light)) {
                    throw new MojoFailureException(name + " is drawn for dark themes and not for light: no " + light);
                }
                stamp.add(dark).add(light);
                icons.add(icon(name, Files.readString(dark, StandardCharsets.UTF_8),
                        Files.readString(light, StandardCharsets.UTF_8)));
            }
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("cannot read the icons at " + dir, e);
        }

        String atlasFile = (atlasName == null || atlasName.isBlank() ? Sources.kebab(enumName) : atlasName) + ".rgba";
        String folder = packageName.replace('.', '/');
        String atlasClass = enumName + "Atlas";
        Path rgba = resourceDirectory.toPath().resolve(folder).resolve(atlasFile);
        Path enumFile = sourceDirectory.toPath().resolve(folder).resolve(enumName + ".java");
        Path atlasSource = sourceDirectory.toPath().resolve(folder).resolve(atlasClass + ".java");
        Path stampFile = workDirectory.toPath().resolve(className + ".layered.stamp");
        String hex = stamp.hex();
        try {
            if (Files.isRegularFile(stampFile) && Files.readString(stampFile).equals(hex) && Files.isRegularFile(rgba)
                    && Files.isRegularFile(enumFile) && Files.isRegularFile(atlasSource)) {
                getLog().info(className + ": " + icons.size() + " layered icons, up to date.");
                return;
            }
            Files.deleteIfExists(stampFile);

            // Every mask is a large drawing of an entry of its own: the silhouette first, then the layers.
            Path masks = workDirectory.toPath().resolve("layers").resolve(enumName);
            List<Atlas.Entry> entries = new ArrayList<>();
            for (Icon icon : icons) {
                entries.add(entry(masks, icon.name(), "silhouette", icon.silhouette()));
                for (int k = 0; k < icon.masks().size(); k++) {
                    entries.add(entry(masks, icon.name(), String.valueOf(k), icon.masks().get(k)));
                }
            }
            Atlas atlas = Atlas.bake(entries, grids, width, Atlas.FROM_ART);
            if (atlas.width() > Atlas.VULKAN_GUARANTEED_DIMENSION
                    || atlas.height() > Atlas.VULKAN_GUARANTEED_DIMENSION) {
                throw new MojoFailureException(className + "'s atlas is " + atlas.width() + " x " + atlas.height()
                        + ", past the " + Atlas.VULKAN_GUARANTEED_DIMENSION + " texels every Vulkan device must take."
                        + " Set <width> or a smaller <inner>, or split the set.");
            }
            Output.write(rgba, Output.pixels(atlas.rgba(), atlas.width(), atlas.height()));
            Output.write(enumFile, layeredEnum(icons, atlas.large(), atlasClass));
            Output.write(atlasSource, Sources.atlas(packageName, atlasClass, enumName, "/" + folder + "/" + atlasFile,
                    atlas, grids));
            Path picture = workDirectory.toPath().resolve(enumName + ".png");
            Output.picture(picture, atlas.rgba(), atlas.width(), atlas.height());
            Files.createDirectories(stampFile.getParent());
            Files.writeString(stampFile, hex);
            getLog().info(className + ": " + icons.size() + " layered icons, " + entries.size() + " fields in a "
                    + atlas.width() + " x " + atlas.height() + " atlas, " + (atlas.width() * (long) atlas.height() * 4
                    / 1024) + " KiB of texture; " + picture);
        } catch (IOException | RuntimeException e) {
            throw new MojoExecutionException("baking " + className + " failed: " + e.getMessage(), e);
        }
    }

    /** {@code name} taken apart in both palettes, which must agree on every shape. */
    static Icon icon(String name, String darkSvg, String lightSvg) {
        List<SvgLayers.Layer> dark = SvgLayers.split(darkSvg);
        List<SvgLayers.Layer> light = SvgLayers.split(lightSvg);
        boolean same = dark.size() == light.size();
        for (int k = 0; same && k < dark.size(); k++) {
            same = dark.get(k).svg().equals(light.get(k).svg());
        }
        if (!same) {
            throw new IllegalArgumentException(name + ": the light drawing is not the dark one's shapes in other colours"
                    + " (" + dark.size() + " layers dark, " + light.size() + " light)");
        }
        return new Icon(name, SvgLayers.silhouette(darkSvg), dark.stream().map(SvgLayers.Layer::svg).toList(),
                dark.stream().map(SvgLayers.Layer::color).toList(), light.stream().map(SvgLayers.Layer::color).toList());
    }

    private static Atlas.Entry entry(Path folder, String icon, String part, String svg) throws IOException {
        Path file = folder.resolve(icon + "." + part + ".svg");
        Output.write(file, svg);
        return new Atlas.Entry(icon + "." + part, null, new Drawing(file, Mask.Coverage.ALPHA, false));
    }

    private String layeredEnum(List<Icon> icons, int[] cells, String atlasClass) {
        StringBuilder s = new StringBuilder(Sources.HEADER).append("package ").append(packageName).append(";\n\n");
        s.append("""
                import dev.vexelray.canvas.Color;
                import dev.vexelray.gui.core.ImageField;
                import dev.vexelray.gui.core.LayeredIcon;

                import java.util.ArrayList;
                import java.util.List;

                /**
                 * Every icon in this set, by name: an icon in colours of its own, one distance field per colour drawn one
                 * over the other, in a row of colours for a dark theme and one for a light one. An {@code IconView} draws
                 * one as its layers; anything that draws in one colour gets its silhouette.
                 *
                 * <p>Generated by {@code vexelray-gui-icons-maven-plugin} from {@code %s}.
                 * Do not edit: change the art and build again.
                 */
                public enum %s implements LayeredIcon {

                """.formatted(project.getBasedir().toPath().relativize(directory.toPath().toAbsolutePath())
                .toString().replace('\\', '/'), enumName));
        int cell = 0;
        for (int i = 0; i < icons.size(); i++) {
            Icon icon = icons.get(i);
            int silhouette = cells[cell++];
            int[] layers = new int[icon.masks().size()];
            for (int k = 0; k < layers.length; k++) {
                layers[k] = cells[cell++];
            }
            s.append("    /** {@code ").append(icon.name()).append("}: ").append(layers.length)
                    .append(layers.length == 1 ? " layer" : " layers").append(". */\n");
            s.append("    ").append(Sources.constant(icon.name())).append("(\"").append(icon.name()).append("\", ")
                    .append(silhouette).append(",\n            new int[]{").append(ints(layers))
                    .append("},\n            new int[]{").append(colors(icon.dark()))
                    .append("},\n            new int[]{").append(colors(icon.light())).append("})")
                    .append(i == icons.size() - 1 ? ";\n" : ",\n");
        }
        s.append("""

                    private final String file;
                    private final int silhouette;
                    private final int[] cells;
                    private final Color[] dark;
                    private final Color[] light;

                    %1$s(String file, int silhouette, int[] cells, int[] dark, int[] light) {
                        this.file = file;
                        this.silhouette = silhouette;
                        this.cells = cells;
                        this.dark = new Color[dark.length];
                        this.light = new Color[light.length];
                        for (int k = 0; k < cells.length; k++) {
                            this.dark[k] = Color.rgb(dark[k]);
                            this.light[k] = Color.rgb(light[k]);
                        }
                    }

                    /** The icon's name in the set: its art's file name, without {@code .svg}. */
                    public String file() {
                        return file;
                    }

                    /** The silhouette, every layer as one shape, in {@code tint}. */
                    @Override
                    public ImageField field(float px, Color tint) {
                        return %2$s.INSTANCE.large(silhouette, tint);
                    }

                    @Override
                    public List<ImageField> layers(float px, boolean dark) {
                        Color[] colors = dark ? this.dark : this.light;
                        List<ImageField> fields = new ArrayList<>(cells.length);
                        for (int k = 0; k < cells.length; k++) {
                            fields.add(%2$s.INSTANCE.large(cells[k], colors[k]));
                        }
                        return fields;
                    }

                    /** The icon named {@code file} in this set, or null if there is none. */
                    public static %1$s named(String file) {
                        for (%1$s icon : values()) {
                            if (icon.file.equals(file)) {
                                return icon;
                            }
                        }
                        return null;
                    }
                }
                """.formatted(enumName, atlasClass));
        return s.toString();
    }

    private static String ints(int[] values) {
        StringBuilder s = new StringBuilder();
        for (int v : values) {
            s.append(s.isEmpty() ? "" : ", ").append(v);
        }
        return s.toString();
    }

    private static String colors(List<String> colors) {
        StringBuilder s = new StringBuilder();
        for (String c : colors) {
            s.append(s.isEmpty() ? "" : ", ").append("0x").append(c.substring(1));
        }
        return s.toString();
    }
}
