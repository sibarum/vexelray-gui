package dev.vexelray.gui.iconplugin;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Bakes a set of icons: every drawing configured (SVG, a black-on-white raster, or a raster whose alpha is the shape;
 * see {@link Mask}) made a signed distance field, packed into one atlas, and named by a generated enum that implements
 * {@code dev.vexelray.gui.icons.BakedIcon}, so each icon goes wherever an {@code IconSource} goes and draws at any size
 * and colour from one texture. Nothing at run time reads an image format: the atlas is in the font atlas's layout.
 *
 * <p>The generated sources and the atlas go under {@code target} and are added to the build, so nothing generated is
 * committed and nothing goes stale; a bake whose art and configuration are unchanged is skipped. A set also writes a
 * manifest into its jar ({@link SetManifest}), and a set that names it in {@code <extends>} takes its grids and may not
 * reuse its names: the suite's set is {@code dev.vexelray.gui.icons.Icon}, and an application's own icons extend it.
 *
 * <pre>
 * &lt;plugin&gt;
 *   &lt;groupId&gt;dev.vexelray.gui&lt;/groupId&gt;
 *   &lt;artifactId&gt;vexelray-gui-icons-maven-plugin&lt;/artifactId&gt;
 *   &lt;executions&gt;&lt;execution&gt;&lt;goals&gt;&lt;goal&gt;bake&lt;/goal&gt;&lt;/goals&gt;&lt;/execution&gt;&lt;/executions&gt;
 *   &lt;configuration&gt;
 *     &lt;extends&gt;dev.vexelray.gui.icons.Icon&lt;/extends&gt;
 *     &lt;packageName&gt;dev.sibarum.pix&lt;/packageName&gt;
 *     &lt;enumName&gt;PixIcon&lt;/enumName&gt;
 *     &lt;directories&gt;
 *       &lt;directory&gt;&lt;path&gt;src/main/icons/small&lt;/path&gt;&lt;size&gt;small&lt;/size&gt;&lt;/directory&gt;
 *       &lt;directory&gt;&lt;path&gt;src/main/icons/large&lt;/path&gt;&lt;size&gt;large&lt;/size&gt;&lt;/directory&gt;
 *     &lt;/directories&gt;
 *   &lt;/configuration&gt;
 * &lt;/plugin&gt;
 * </pre>
 */
@Mojo(name = "bake", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE)
public class BakeMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** The generated enum's package, and the atlas resource's folder. */
    @Parameter(required = true)
    private String packageName;

    /** The generated enum: {@code Icon} for the suite's set, the application's own name for one that extends it. */
    @Parameter(defaultValue = "Icon")
    private String enumName;

    /** The atlas's file name in the package's folder, without {@code .rgba}; the enum's name in kebab case if unset. */
    @Parameter
    private String atlasName;

    /**
     * The set this one extends, by its enum's fully qualified name, found on the compile class path: its grids are
     * this set's, and its names are not.
     */
    @Parameter(alias = "extends")
    private String base;

    /** Folders of drawings at one size each. */
    @Parameter
    private List<ArtDirectory> directories;

    /** Icons named outright. */
    @Parameter
    private List<IconArt> icons;

    /** The large drawing's square, in texels; the base set's, or 64. */
    @Parameter
    private Integer largeInner;

    /** The large drawing's margin, in texels; the base set's, or 16. */
    @Parameter
    private Integer largeSpread;

    /** The small drawing's square, in texels; the base set's, or 48. */
    @Parameter
    private Integer smallInner;

    /** The small drawing's margin, in texels; the base set's, or 12. */
    @Parameter
    private Integer smallSpread;

    /** The atlas's width in texels; as near square as fits if unset. */
    @Parameter
    private Integer width;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/vexelray-icons")
    private File sourceDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-resources/vexelray-icons")
    private File resourceDirectory;

    /** Where the stamp and the atlas's picture ({@code <enum>.png}, the fields as grey) go. */
    @Parameter(defaultValue = "${project.build.directory}/vexelray-icons")
    private File workDirectory;

    /** Skip the bake; the sources and resources already under {@code target} are still added. */
    @Parameter(property = "vexelray.icons.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        project.addCompileSourceRoot(sourceDirectory.getPath());
        Resource resource = new Resource();
        resource.setDirectory(resourceDirectory.getPath());
        project.addResource(resource);
        if (skip) {
            getLog().info("Skipping the icon bake.");
            return;
        }
        String className = packageName + "." + enumName;
        SetManifest baseSet = base == null || base.isBlank() ? null : base(base.strip());
        Grids grids = (baseSet != null ? baseSet.grids() : Grids.SUITE)
                .with(largeInner, largeSpread, smallInner, smallSpread);
        if (baseSet != null && !grids.equals(baseSet.grids())) {
            getLog().warn(className + " bakes at " + grids + " and extends " + baseSet.className() + ", baked at "
                    + baseSet.grids() + ": its icons will not match the base's weight or reach of effect.");
        }
        List<Atlas.Entry> entries;
        try {
            entries = Gather.entries(project.getBasedir().toPath(),
                    directories == null ? List.of() : directories, icons == null ? List.of() : icons,
                    baseSet == null ? List.of() : baseSet.reserved(),
                    baseSet == null ? "" : baseSet.className());
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("cannot read the icons' folders", e);
        }
        if (entries.isEmpty()) {
            throw new MojoFailureException(className + " has no icons: configure <directories> or <icons>.");
        }

        String file = (atlasName == null || atlasName.isBlank() ? Sources.kebab(enumName) : atlasName) + ".rgba";
        String folder = packageName.replace('.', '/');
        String atlasClass = enumName + "Atlas";
        Path atlasFile = resourceDirectory.toPath().resolve(folder).resolve(file);
        Path enumFile = sourceDirectory.toPath().resolve(folder).resolve(enumName + ".java");
        Path atlasSource = sourceDirectory.toPath().resolve(folder).resolve(atlasClass + ".java");
        Path manifestFile = resourceDirectory.toPath().resolve(SetManifest.resource(className));
        Path stampFile = workDirectory.toPath().resolve(className + ".stamp");

        String stamp;
        try {
            Output.Stamp s = new Output.Stamp().add(className).add(file).add(grids.toString())
                    .add(String.valueOf(width)).add(String.valueOf(baseSet == null ? "" : baseSet.text()));
            for (Atlas.Entry e : entries) {
                s.add(e.name());
                for (Drawing d : new Drawing[]{e.small(), e.large()}) {
                    s.add(d == null ? "-" : d.coverage() + " " + d.invert());
                    if (d != null) {
                        s.add(d.file());
                    }
                }
            }
            stamp = s.hex();
            if (Files.isRegularFile(stampFile) && Files.readString(stampFile).equals(stamp)
                    && Files.isRegularFile(atlasFile) && Files.isRegularFile(enumFile)
                    && Files.isRegularFile(atlasSource) && Files.isRegularFile(manifestFile)) {
                getLog().info(className + ": " + entries.size() + " icons, up to date.");
                return;
            }
            Files.deleteIfExists(stampFile);
        } catch (IOException e) {
            throw new MojoExecutionException("cannot read the icons' art", e);
        }

        Atlas atlas;
        try {
            atlas = Atlas.bake(entries, grids, width, Atlas.FROM_ART);
        } catch (IOException | RuntimeException e) {
            throw new MojoExecutionException("baking " + className + " failed: " + e.getMessage(), e);
        }
        if (atlas.width() > Atlas.VULKAN_GUARANTEED_DIMENSION || atlas.height() > Atlas.VULKAN_GUARANTEED_DIMENSION) {
            throw new MojoFailureException(className + "'s atlas is " + atlas.width() + " x " + atlas.height()
                    + ", past the " + Atlas.VULKAN_GUARANTEED_DIMENSION + " texels every Vulkan device must take."
                    + " Set <width>, or split the set.");
        }
        List<String> names = entries.stream().map(Atlas.Entry::name).toList();
        String from = from();
        try {
            Output.write(atlasFile, Output.pixels(atlas.rgba(), atlas.width(), atlas.height()));
            Output.write(enumFile, Sources.iconEnum(packageName, enumName, atlasClass, names, atlas.small(),
                    atlas.large(), baseSet == null ? null : baseSet.className(), from));
            Output.write(atlasSource, Sources.atlas(packageName, atlasClass, enumName, "/" + folder + "/" + file,
                    atlas, grids));
            Output.write(manifestFile, new SetManifest(className, grids, names,
                    baseSet == null ? List.of() : baseSet.reserved()).text());
            Path picture = workDirectory.toPath().resolve(enumName + ".png");
            Output.picture(picture, atlas.rgba(), atlas.width(), atlas.height());
            Files.createDirectories(stampFile.getParent());
            Files.writeString(stampFile, stamp);
            long large = entries.stream().filter(e -> e.large() != null).count();
            long small = entries.stream().filter(e -> e.small() != null).count();
            String count = entries.size() == 1 ? "1 icon" : entries.size() + " icons";
            getLog().info(className + ": " + count + " (" + large + " large, " + small + " small) in a "
                    + atlas.width() + " x " + atlas.height() + " atlas, " + (atlas.width() * (long) atlas.height() * 4
                    / 1024) + " KiB of texture; " + picture);
        } catch (IOException e) {
            throw new MojoExecutionException("cannot write " + className + "'s atlas and sources", e);
        }
    }

    /** The manifest of the set {@code className}, from the compile class path. */
    private SetManifest base(String className) throws MojoExecutionException, MojoFailureException {
        List<URL> urls = new ArrayList<>();
        try {
            for (String element : project.getCompileClasspathElements()) {
                urls.add(new File(element).toURI().toURL());
            }
        } catch (DependencyResolutionRequiredException | MalformedURLException e) {
            throw new MojoExecutionException("cannot read the compile class path", e);
        }
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), null);
             InputStream in = loader.getResourceAsStream(SetManifest.resource(className))) {
            if (in == null) {
                throw new MojoFailureException("<extends> names " + className + ", and no jar on the compile class path"
                        + " has its manifest (" + SetManifest.resource(className) + "). Depend on the artifact that"
                        + " bakes it: dev.vexelray.gui:vexelray-gui-icons for dev.vexelray.gui.icons.Icon.");
            }
            return SetManifest.read(in);
        } catch (IOException | IllegalArgumentException e) {
            throw new MojoExecutionException("cannot read " + className + "'s manifest", e);
        }
    }

    /** Where the art is, for the enum's documentation: the folders, relative to the project. */
    private String from() {
        List<String> where = new ArrayList<>();
        Path basedir = project.getBasedir().toPath();
        for (ArtDirectory d : directories == null ? List.<ArtDirectory>of() : directories) {
            where.add("{@code " + basedir.relativize(basedir.resolve(d.path.toPath())).toString().replace('\\', '/')
                    + "}");
        }
        if (icons != null && !icons.isEmpty()) {
            where.add("the icons its build names");
        }
        return String.join(" and ", where) + " in " + project.getArtifactId();
    }
}
