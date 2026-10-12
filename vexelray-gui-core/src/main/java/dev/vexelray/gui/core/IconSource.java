package dev.vexelray.gui.core;

import dev.vexelray.canvas.Color;

/**
 * Something that draws as a distance field at a size: an icon, to whatever names one without knowing the set. The
 * suite's {@code Icon} enum ({@code vexelray-gui-icons}) is the one every widget is given; core declares only this, so a
 * menu item or a tree action can carry an icon while core stays below the icon set.
 */
public interface IconSource {

    /** This icon as a field to hand {@link Node#field}, drawn {@code px} pixels square in {@code tint}. */
    ImageField field(float px, Color tint);
}
