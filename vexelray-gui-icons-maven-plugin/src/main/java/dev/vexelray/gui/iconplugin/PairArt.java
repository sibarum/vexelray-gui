package dev.vexelray.gui.iconplugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * One mark, configured as {@code <pair>} under {@code <pairs>}: the drawing, where what is made from it goes, and which
 * of three things to make from it. Each is a conversion of the drawing as an SDG (vexelray-fields-convert):
 *
 * <ul>
 *   <li>the <b>drawing</b> itself, {@code <name>.sdgb}, which a view draws sharp at any size;</li>
 *   <li>the <b>art pair</b>, {@code <name>.pair} and {@code <name>-<size>.vxpx}, which the About window condenses
 *       (SDG → Mask → Shape → MTSDF beside SDG → Raster sizes);</li>
 *   <li>the <b>icon</b>, an {@code .ico} of the OS's sizes (SDG → Raster sizes → Icon), for the window and for
 *       {@code rc.exe} to link into the executable, with any small sizes drawn from drawings of their own.</li>
 * </ul>
 *
 * <pre>
 * &lt;pair&gt;
 *   &lt;art&gt;src/main/rc/pix.svg&lt;/art&gt;
 *   &lt;name&gt;pix&lt;/name&gt;
 *   &lt;icon&gt;pix.ico&lt;/icon&gt;
 *   &lt;smalls&gt;&lt;small&gt;24=src/main/rc/pix-24.svg&lt;/small&gt;&lt;/smalls&gt;
 * &lt;/pair&gt;
 * </pre>
 */
public class PairArt {

    /** The drawing: an SVG, square. Relative to the project. */
    File art;

    /** The resource path of what is made, without an extension: {@code <name>.sdgb}, {@code <name>.pair}, .... */
    String name;

    /** Whether to make the art pair. Off for a mark only the OS shows (a background or a file icon). */
    boolean pair = true;

    /** Whether to make the drawing, {@code <name>.sdgb}. */
    boolean drawing = true;

    /** The drawing's texels across its longer side: what its stencils are made at. */
    int texels = 128;

    /** The colour layer's squares in pixels, one atlas each: the mark's size at each display scale it is shown at. */
    String sizes = "104,208,312,416";

    /** The field's square in texels. Fields scale, so one size serves every atlas. */
    int field = 160;

    /** The room round the shape, as a share of its square, each side: how far out its effects reach. */
    float margin = 0.6f;

    /**
     * The {@code id} of an element of the drawing that is left out of its shape and kept in its colour: a part the
     * application draws live and lands where the drawing has it. Its place is recorded. None if unset.
     */
    String lift;

    /**
     * The icon's file name, as a resource at the class path's root and in {@code target/generated-rc} for
     * {@code rc.exe}: {@code pix.ico}. No icon if unset.
     */
    String icon;

    /** The icon's sizes, in pixels. Windows's, by default: the shell's, the taskbar's and Explorer's at each scale. */
    String iconSizes = "16,20,24,32,40,48,64,96,128,256";

    /**
     * Drawings for the smallest sizes, each {@code <side>=<svg>}: every size up to {@code side} is drawn from the
     * smallest such drawing that covers it, and the sizes above them all from {@link #art}. The suite's background
     * icons have heavier dashes at 24 and 16 px than above.
     */
    List<String> smalls = new ArrayList<>();
}
