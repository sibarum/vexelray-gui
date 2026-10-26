package dev.vexelray.gui.fileicons;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.LayeredIcon;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.icons.IconView;

/**
 * A file's type icon as a node: its family's sheet in the family's hue, with the emblem over it in ink, at the same
 * size. The colours are the canvas's palette row for the family, the dark one or the light one by the theme.
 *
 * <pre>{@code
 * Node icon = FileIconView.node(gui, FileFamily.ofExtension("java"), Length.dp(16));
 * }</pre>
 *
 * <p>A family is a {@link LayeredIcon}, so this is an {@link IconView} of it; anything that takes an icon, a tree row
 * among them, draws one the same way. Inert to the pointer, so a press lands on whatever row or tab holds it. The
 * colours are read from the theme when the icon is drawn.
 */
public final class FileIconView {

    private FileIconView() {
    }

    /** {@code family}'s icon as a {@code side} square, in {@code gui}'s theme. */
    public static Node node(Gui gui, FileFamily family, Length side) {
        return IconView.of(gui, family).size(side).node().hitInert(true);
    }

    /** A theme is dark when its ink is light: which of the palette's two rows it is drawn with. */
    public static boolean dark(Theme theme) {
        return LayeredIcon.dark(theme);
    }
}
