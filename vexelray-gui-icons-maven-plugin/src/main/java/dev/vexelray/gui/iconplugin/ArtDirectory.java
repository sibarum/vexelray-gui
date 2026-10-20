package dev.vexelray.gui.iconplugin;

import java.io.File;

/**
 * A folder of drawings at one size, configured as {@code <directory>} under {@code <directories>}: every image in it
 * is an icon, named by its file name without the extension, and without {@link #suffix} where one is given (only files
 * ending in it are taken then: {@code save-mask.svg} is {@code save} with the suffix {@code -mask}). A name is lower
 * case words joined by hyphens, and becomes the constant {@code SAVE}, {@code PIN_UP}.
 *
 * <pre>
 * &lt;directory&gt;
 *   &lt;path&gt;src/main/icons/small&lt;/path&gt;
 *   &lt;size&gt;small&lt;/size&gt;
 *   &lt;suffix&gt;-mask&lt;/suffix&gt;
 * &lt;/directory&gt;
 * </pre>
 */
public class ArtDirectory {

    /** The folder. Relative to the project. */
    File path;

    /** {@code small} (a 16-unit pixel grid, for 24 px and under) or {@code large} (a 64-unit grid, above). */
    String size = "small";

    /** What every file name ends in before its extension, taken off to name the icon; blank for none. */
    String suffix = "";

    /** Which part of each drawing is the shape: {@code auto}, {@code alpha} or {@code luminance}. See {@link Mask}. */
    String coverage = "auto";

    /** Whether the shape is the other part: light on dark, or the transparent part of a mask. */
    boolean invert;

    /**
     * Whether the folder may be missing, as one size of art an application has not drawn yet; a set with no icons at all
     * still fails. Off, a missing folder fails the build: it is more often a mistyped path than art not yet drawn.
     */
    boolean optional;
}
