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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Bakes marks as art pairs ({@link ArtPair}): each drawing's shape as a distance field and its colour at each size it
 * is shown at, one atlas per size, with a descriptor beside them. What the About window condenses its marks from.
 *
 * <pre>
 * &lt;execution&gt;
 *   &lt;id&gt;art-pairs&lt;/id&gt;
 *   &lt;goals&gt;&lt;goal&gt;pair&lt;/goal&gt;&lt;/goals&gt;
 *   &lt;configuration&gt;
 *     &lt;pairs&gt;
 *       &lt;pair&gt;&lt;art&gt;src/main/rc/pix.svg&lt;/art&gt;&lt;name&gt;dev/sibarum/pix/pix&lt;/name&gt;&lt;/pair&gt;
 *     &lt;/pairs&gt;
 *   &lt;/configuration&gt;
 * &lt;/execution&gt;
 * </pre>
 *
 * Generated under {@code target} and added to the build's resources, so nothing generated is committed; a pair whose
 * art and configuration are unchanged is not baked again.
 */
@Mojo(name = "pair", defaultPhase = LifecyclePhase.GENERATE_RESOURCES, threadSafe = true)
public class PairMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** The marks to bake. */
    @Parameter(required = true)
    private List<PairArt> pairs;

    @Parameter(defaultValue = "${project.build.directory}/generated-resources/vexelray-pairs")
    private File resourceDirectory;

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
            getLog().info("Skipping the art pairs.");
            return;
        }
        for (PairArt p : pairs) {
            bake(p);
        }
    }

    private void bake(PairArt p) throws MojoExecutionException, MojoFailureException {
        if (p.art == null || p.name == null || p.name.isBlank()) {
            throw new MojoFailureException("a <pair> needs its <art> and its <name>");
        }
        Path art = project.getBasedir().toPath().resolve(p.art.toPath());
        int[] sizes;
        try {
            sizes = Arrays.stream(p.sizes.split("[,\\s]+")).filter(s -> !s.isBlank()).mapToInt(Integer::parseInt)
                    .toArray();
        } catch (NumberFormatException e) {
            throw new MojoFailureException("<sizes> of " + p.name + " is not a list of whole numbers: " + p.sizes);
        }
        String name = p.name.strip();
        Path base = resourceDirectory.toPath().resolve(name);
        Path descriptor = base.resolveSibling(base.getFileName() + ".pair");
        Path stampFile = workDirectory.toPath().resolve(name.replace('/', '.') + ".stamp");
        String stamp;
        try {
            stamp = new Output.Stamp().add(ArtPair.class.getName()).add(art).add(Arrays.toString(sizes))
                    .add(p.field + " " + p.margin + " " + p.lift).hex();
            if (Files.isRegularFile(stampFile) && Files.readString(stampFile).equals(stamp)
                    && Files.isRegularFile(descriptor)) {
                getLog().info(name + ": up to date.");
                return;
            }
        } catch (IOException e) {
            throw new MojoExecutionException("cannot read " + art, e);
        }
        try {
            ArtPair pair = ArtPair.bake(Files.readAllBytes(art), p.field, p.margin, sizes, p.lift);
            long bytes = 0;
            for (ArtPair.Atlas a : pair.atlases()) {
                Output.write(base.resolveSibling(base.getFileName() + "-" + a.size() + ".vxpx"),
                        Output.pixels(a.rgba(), a.width(), a.height()));
                bytes += (long) a.width() * a.height() * 4;
            }
            Output.write(descriptor, pair.descriptor());
            Files.createDirectories(stampFile.getParent());
            Files.writeString(stampFile, stamp);
            getLog().info(name + ": a " + pair.fieldSide() + " texel field and " + sizes.length + " colour sizes, "
                    + bytes / 1024 + " KiB of texture" + (pair.lift() == null ? "" : ", '" + p.lift + "' lifted"));
        } catch (IOException | RuntimeException e) {
            throw new MojoExecutionException("baking the pair " + name + " from " + art + " failed: "
                    + e.getMessage(), e);
        }
    }
}
