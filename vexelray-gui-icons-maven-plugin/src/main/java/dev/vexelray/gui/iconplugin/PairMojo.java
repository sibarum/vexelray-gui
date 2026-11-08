package dev.vexelray.gui.iconplugin;

import dev.vexelray.fields.art.Svg;
import dev.vexelray.fields.atlas.ArtPair;
import dev.vexelray.fields.convert.Convert;
import dev.vexelray.fields.sdg.Sdg;
import dev.vexelray.fields.sdg.SdgFile;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Makes an application's marks from their drawings ({@link PairArt}): each drawing as an SDG, and from the SDG its art
 * pair for the About window and its icon for the OS, every one of them a conversion in vexelray-fields-convert, named
 * by its hops in the build's log.
 *
 * <pre>
 * &lt;execution&gt;
 *   &lt;id&gt;marks&lt;/id&gt;
 *   &lt;goals&gt;&lt;goal&gt;pair&lt;/goal&gt;&lt;/goals&gt;
 *   &lt;configuration&gt;
 *     &lt;pairs&gt;
 *       &lt;pair&gt;&lt;art&gt;src/main/rc/pix.svg&lt;/art&gt;&lt;name&gt;pix&lt;/name&gt;&lt;icon&gt;pix.ico&lt;/icon&gt;&lt;/pair&gt;
 *     &lt;/pairs&gt;
 *   &lt;/configuration&gt;
 * &lt;/execution&gt;
 * </pre>
 *
 * Generated under {@code target} and added to the build's resources, so nothing generated is committed; a mark whose
 * drawings and configuration are unchanged is not made again. An icon also goes to {@link #rcDirectory}, where an
 * application's {@code .rc} finds it with {@code rc.exe /i}.
 */
@Mojo(name = "pair", defaultPhase = LifecyclePhase.GENERATE_RESOURCES, threadSafe = true)
public class PairMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** The marks to make. */
    @Parameter(required = true)
    private List<PairArt> pairs;

    @Parameter(defaultValue = "${project.build.directory}/generated-resources/vexelray-pairs")
    private File resourceDirectory;

    /** Where icons go for {@code rc.exe} as well. */
    @Parameter(defaultValue = "${project.build.directory}/generated-rc")
    private File rcDirectory;

    /** Where the stamps go. */
    @Parameter(defaultValue = "${project.build.directory}/vexelray-pairs")
    private File workDirectory;

    @Parameter(property = "vexelray.pairs.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Resource resource = new Resource();
        resource.setDirectory(resourceDirectory.getPath());
        project.addResource(resource);
        if (skip) {
            getLog().info("Skipping the marks.");
            return;
        }
        for (PairArt p : pairs) {
            make(p);
        }
    }

    private void make(PairArt p) throws MojoExecutionException, MojoFailureException {
        if (p.art == null || p.name == null || p.name.isBlank()) {
            throw new MojoFailureException("a <pair> needs its <art> and its <name>");
        }
        Path base = project.getBasedir().toPath();
        Path art = base.resolve(p.art.toPath());
        int[] sizes = ints(p.sizes, "<sizes> of " + p.name);
        int[] iconSizes = ints(p.iconSizes, "<iconSizes> of " + p.name);
        List<Path> smallArt = new ArrayList<>();
        List<Integer> smallSides = new ArrayList<>();
        for (String s : p.smalls) {
            int eq = s.indexOf('=');
            if (eq < 1) {
                throw new MojoFailureException("a <small> of " + p.name + " is <side>=<svg>, not " + s);
            }
            smallSides.add(ints(s.substring(0, eq), "a <small> of " + p.name)[0]);
            smallArt.add(base.resolve(s.substring(eq + 1).strip()));
        }

        String name = p.name.strip();
        Path out = resourceDirectory.toPath().resolve(name);
        Path stampFile = workDirectory.toPath().resolve(name.replace('/', '.') + ".stamp");
        String stamp;
        try {
            Output.Stamp st = new Output.Stamp().addCode(Convert.class, ArtPair.class, Sdg.class, Svg.class).add(art)
                    .add(p.pair + " " + p.drawing + " " + p.texels + " " + Arrays.toString(sizes) + " " + p.field
                            + " " + p.margin + " " + p.lift + " " + p.icon + " " + Arrays.toString(iconSizes)
                            + " " + smallSides + " " + p.roles);
            for (Path s : smallArt) {
                st.add(s);
            }
            stamp = st.hex();
            if (Files.isRegularFile(stampFile) && Files.readString(stampFile).equals(stamp)) {
                getLog().info(name + ": up to date.");
                return;
            }
        } catch (IOException e) {
            throw new MojoExecutionException("cannot read the drawings of " + name, e);
        }
        try {
            Svg svg = Svg.read(art);
            // The drawing as an SDG when anything here is made from one; an icon alone is drawn from the SVG itself, which
            // may composite (a group's opacity, a clip) as an SDG cannot.
            Sdg sdg = p.pair || p.drawing ? Convert.svgToSdg(p.texels).apply(svg) : null;
            if (sdg != null && !p.roles.isEmpty()) {
                java.util.Map<String, String> roles = new java.util.LinkedHashMap<>();
                for (String r : p.roles) {
                    int eq = r.indexOf('=');
                    if (eq < 1) {
                        throw new MojoFailureException("a <role> of " + name + " is <id>=<role>, not " + r);
                    }
                    roles.put(r.substring(0, eq).strip(), r.substring(eq + 1).strip());
                }
                sdg = sdg.withRoles(roles);
            }
            List<String> made = new ArrayList<>();
            if (p.drawing) {
                Path file = out.resolveSibling(out.getFileName() + ".sdgb");
                Files.createDirectories(file.getParent());
                SdgFile.write(sdg, file);
                made.add("the drawing (" + sdg.layers().size() + " layers, " + Files.size(file) / 1024 + " KiB)");
            }
            if (p.pair) {
                var route = Convert.sdgToArtPair(p.field, p.margin, sizes, p.lift);
                ArtPair pair = route.apply(sdg);
                long bytes = 0;
                for (ArtPair.Atlas a : pair.atlases()) {
                    Output.write(out.resolveSibling(out.getFileName() + "-" + a.size() + ".vxpx"),
                            Output.pixels(a.rgba(), a.width(), a.height()));
                    bytes += (long) a.width() * a.height() * 4;
                }
                Output.write(out.resolveSibling(out.getFileName() + ".pair"), pair.descriptor());
                made.add("the art pair, " + route.name() + " (a " + pair.fieldSide() + " texel field, "
                        + sizes.length + " colour sizes, " + bytes / 1024 + " KiB of texture"
                        + (pair.lift() == null ? "" : ", '" + p.lift + "' lifted") + ")");
            }
            if (p.icon != null && !p.icon.isBlank()) {
                byte[] ico;
                String route;
                if (sdg != null) {
                    List<Convert.Small> smalls = new ArrayList<>();
                    for (int i = 0; i < smallArt.size(); i++) {
                        smalls.add(new Convert.Small(smallSides.get(i),
                                Convert.svgToSdg(p.texels).apply(Svg.read(smallArt.get(i)))));
                    }
                    var c = Convert.sdgToIcon(iconSizes, smalls);
                    ico = c.apply(sdg).ico();
                    route = c.name();
                } else {
                    List<Convert.SmallSvg> smalls = new ArrayList<>();
                    for (int i = 0; i < smallArt.size(); i++) {
                        smalls.add(new Convert.SmallSvg(smallSides.get(i), Svg.read(smallArt.get(i))));
                    }
                    var c = Convert.svgToIcon(iconSizes, smalls);
                    ico = c.apply(svg).ico();
                    route = c.name();
                }
                Output.write(resourceDirectory.toPath().resolve(p.icon.strip()), ico);
                Output.write(rcDirectory.toPath().resolve(Path.of(p.icon.strip()).getFileName()), ico);
                made.add("the icon " + p.icon.strip() + ", " + route + " (" + iconSizes.length + " sizes"
                        + (smallArt.isEmpty() ? "" : ", " + smallArt.size() + " small drawings") + ")");
            }
            Files.createDirectories(stampFile.getParent());
            Files.writeString(stampFile, stamp);
            getLog().info(name + ": " + String.join("; ", made));
        } catch (IOException | RuntimeException | MojoFailureException e) {
            throw new MojoExecutionException("making the mark " + name + " from " + art + " failed: "
                    + e.getMessage(), e);
        }
    }

    private static int[] ints(String list, String what) throws MojoFailureException {
        try {
            return Arrays.stream(list.split("[,\\s]+")).filter(s -> !s.isBlank()).mapToInt(Integer::parseInt)
                    .toArray();
        } catch (NumberFormatException e) {
            throw new MojoFailureException(what + " is not a list of whole numbers: " + list);
        }
    }
}
