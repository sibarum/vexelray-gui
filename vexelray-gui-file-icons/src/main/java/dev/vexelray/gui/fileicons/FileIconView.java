package dev.vexelray.gui.fileicons;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.IconSource;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.icons.IconView;

/**
 * A file's type icon as a node: its family's sheet in the family's hue, with the emblem floated over it in ink, at the
 * same size. The colours are the canvas's palette row for the family, the dark one or the light one by the theme.
 *
 * <pre>{@code
 * Node icon = FileIconView.node(gui, FileFamily.ofExtension("java"), Length.dp(16));
 * }</pre>
 *
 * <p>Inert to the pointer, so a press lands on whatever row or tab holds it. The colours are read from the theme when
 * the node is made; a view that changes theme makes its icons again.
 */
public final class FileIconView {

    private FileIconView() {
    }

    /** {@code family}'s icon as a {@code side} square, in {@code gui}'s theme. */
    public static Node node(Gui gui, FileFamily family, Length side) {
        boolean dark = dark(gui.theme());
        Node sheet = IconView.of(gui, family.sheet()).size(side).tint(family.hue(dark)).node().hitInert(true);
        IconSource emblem = family.emblem();
        if (emblem != null) {
            sheet.append(IconView.of(gui, emblem).size(side).tint(family.ink(dark)).node()
                    .floatAt(Length.ZERO, Length.ZERO).hitInert(true));
        }
        return sheet;
    }

    /** A theme is dark when its ink is light: which of the palette's two rows it is drawn with. */
    public static boolean dark(Theme theme) {
        Color ink = theme.color(Role.INK);
        return 0.2126f * ink.r() + 0.7152f * ink.g() + 0.0722f * ink.b() > 0.5f;
    }
}
