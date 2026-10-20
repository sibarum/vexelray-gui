package dev.vexelray.gui.iconplugin;

import java.io.File;

/**
 * One icon named outright, configured as {@code <icon>} under {@code <icons>}: its name and its drawing at either size
 * or both. For art that is not in a folder of its own, or whose file name is not the name it should have.
 *
 * <pre>
 * &lt;icon&gt;
 *   &lt;name&gt;brush&lt;/name&gt;
 *   &lt;large&gt;art/brush-64.png&lt;/large&gt;
 *   &lt;coverage&gt;luminance&lt;/coverage&gt;
 * &lt;/icon&gt;
 * </pre>
 */
public class IconArt {

    /** Lower case words joined by hyphens: {@code brush}, {@code pen-pressure}. */
    String name;

    /** The 16-unit drawing, for 24 px and under. Relative to the project. */
    File small;

    /** The 64-unit drawing, above. Relative to the project. */
    File large;

    /** Which part of each drawing is the shape: {@code auto}, {@code alpha} or {@code luminance}. See {@link Mask}. */
    String coverage = "auto";

    /** Whether the shape is the other part: light on dark, or the transparent part of a mask. */
    boolean invert;
}
