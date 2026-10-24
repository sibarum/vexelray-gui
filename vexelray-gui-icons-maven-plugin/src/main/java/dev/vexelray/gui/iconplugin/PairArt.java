package dev.vexelray.gui.iconplugin;

import java.io.File;

/**
 * One mark baked as an art pair, configured as {@code <pair>} under {@code <pairs>}: the drawing, where its pair goes,
 * the sizes it is drawn at, and what part of it is lifted out of its shape.
 *
 * <pre>
 * &lt;pair&gt;
 *   &lt;art&gt;src/main/rc/vexelray-logo.svg&lt;/art&gt;
 *   &lt;name&gt;dev/vexelray/framework/shell/vexelray-logo&lt;/name&gt;
 *   &lt;sizes&gt;104,208,312,416&lt;/sizes&gt;
 *   &lt;lift&gt;orb&lt;/lift&gt;
 * &lt;/pair&gt;
 * </pre>
 */
public class PairArt {

    /** The drawing: a square SVG. Relative to the project. */
    File art;

    /** The resource path of the pair, without an extension: {@code <name>.pair} and {@code <name>-<size>.vxpx}. */
    String name;

    /** The colour layer's squares in pixels, one atlas each: the mark's size at each display scale it is shown at. */
    String sizes = "104,208,312,416";

    /** The field's square in texels. Fields scale, so one size serves every atlas. */
    int field = 160;

    /** The room round the shape, as a share of its square, each side: how far out its effects reach. */
    float margin = 0.6f;

    /**
     * The {@code id} of a top-level element of the drawing that is left out of its shape and kept in its colour: a
     * part the application draws live and lands where the drawing has it. Its place is recorded. None if unset.
     */
    String lift;
}
