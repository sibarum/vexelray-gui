package dev.vexelray.gui.core;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;

import java.util.List;

/**
 * An icon in colours of its own: several distance fields drawn one over the other in one box, each in its own colour,
 * with a row of colours for a dark theme and one for a light one. A file-type family is two (its sheet and its
 * emblem); a Java class file is its page, its fold, its emblem and its band.
 *
 * <p>It is still an {@link IconSource}, so it goes wherever one goes. {@link #field} is its silhouette in one tint,
 * for whatever draws icons in a single colour; an {@code IconView} draws {@link #layers} instead, and so does every
 * widget that shows an icon through one, a tree row among them.
 */
public interface LayeredIcon extends IconSource {

    /** The layers back to front, each a field in its own colour, drawn {@code px} pixels square on a dark or light theme. */
    List<ImageField> layers(float px, boolean dark);

    /**
     * The layers as one drawing, each in its own colour, drawn {@code px} pixels square on a dark or light theme: how a
     * layered icon is drawn, in one node. Its effects are its bottom layer's, as they always were.
     */
    default ImageSdg sdg(float px, boolean dark) {
        return ImageSdg.ofFields(layers(px, dark), (dev.vexelray.gui.core.style.Paint) null);
    }

    /** A theme is dark when its ink is light: which of an icon's two rows of colours it is drawn with. */
    static boolean dark(Theme theme) {
        Color ink = theme.color(Role.INK);
        return 0.2126f * ink.r() + 0.7152f * ink.g() + 0.0722f * ink.b() > 0.5f;
    }
}
