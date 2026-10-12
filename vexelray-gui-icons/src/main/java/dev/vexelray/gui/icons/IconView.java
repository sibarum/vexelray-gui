package dev.vexelray.gui.icons;

import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.FieldStyle;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.IconSource;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;

/**
 * A node showing one icon (an {@link Icon}, or any {@link IconSource}): the icon set's answer to a font-icon component.
 * Name the icon, give it a size and a colour, and place {@link #node()} like any other box.
 *
 * <pre>{@code
 * Node save = IconView.of(gui, Icon.SAVE).size(Length.dp(16)).tint(Role.ACCENT).node();
 * }</pre>
 *
 * <p><b>It keeps itself right.</b> The drawing is chosen against the pixels the layout gave the box, so a 16 dp icon
 * on a 200% display is the large drawing at 32 px and the small one again at 100%. A glow and a shadow are given as
 * shares of the drawn size, so they scale with it. Changing the icon, the colour or an effect rewrites one prop;
 * the atlas is uploaded once per device by the renderer, and nothing here holds a texture.
 *
 * <p><b>Decorative by default.</b> An icon beside a word adds nothing for a screen reader, so the node has no role.
 * An icon that is the whole of a control is named by that control (its button's label, hidden if need be), not here.
 *
 * <p>Thread-safe like a node: every setter posts a mutation.
 */
public final class IconView {

    private final Gui gui;
    private final Node node;
    private volatile IconSource icon;
    private volatile Role role = Role.INK;
    private volatile Color color;
    private volatile float glow;
    private volatile float shadow;
    private volatile IconSize forced;
    private volatile float px;

    private IconView(Gui gui, IconSource icon) {
        this.gui = gui;
        this.icon = icon;
        this.node = gui.box().size(Length.rem(1f), Length.rem(1f)).scroll(false, false);
        // Chosen against the box the layout settled on, which is in pixels and so already carries the scale.
        gui.onResizeUi(node, computed -> {
            float side = Math.min(computed.rect().w(), computed.rect().h());
            if (side != px) {
                px = side;
                paint();
            }
        });
    }

    /** A view of {@code icon}, 1 rem square, in the theme's ink. */
    public static IconView of(Gui gui, IconSource icon) {
        return new IconView(gui, icon);
    }

    /** The node to place in a layout. */
    public Node node() {
        return node;
    }

    /** The icon shown. */
    public IconSource icon() {
        return icon;
    }

    /** Show {@code icon} instead; null shows nothing, and keeps the space. */
    public IconView icon(IconSource icon) {
        this.icon = icon;
        paint();
        return this;
    }

    /** The box's side. The icon fills it; its glow and shadow spill past it, into the margin the field carries. */
    public IconView size(Length side) {
        node.size(side, side);
        return this;
    }

    /** Draw in the theme's colour for {@code role}. The default is {@link Role#INK}. */
    public IconView tint(Role role) {
        this.role = role;
        this.color = null;
        paint();
        return this;
    }

    /** Draw in {@code color}, whatever the theme. */
    public IconView tint(Color color) {
        this.color = color;
        paint();
        return this;
    }

    /** A halo of the tint reaching {@code share} of the icon's size past its edge: 0 for none, 0.15 for a clear glow. */
    public IconView glow(float share) {
        this.glow = Math.max(0f, share);
        paint();
        return this;
    }

    /** A soft shadow under the icon, {@code share} of its size soft and falling as far: 0 for none, 0.07 for a lift. */
    public IconView shadow(float share) {
        this.shadow = Math.max(0f, share);
        paint();
        return this;
    }

    /** Always use {@code size}'s drawing of an {@link Icon}, whatever the size drawn; null to choose by size again. */
    public IconView drawing(IconSize size) {
        this.forced = size;
        paint();
        return this;
    }

    private void paint() {
        IconSource i = icon;
        float side = px;
        if (i == null || side <= 0f) {
            node.field(null);
            return;
        }
        Color c = color != null ? color : gui.theme().color(role);
        FieldStyle style = new FieldStyle(c, side * glow, side * shadow);
        if (forced != null && i instanceof Icon set) {
            node.field(set.field(forced, style));
        } else {
            node.field(i.field(side, style));
        }
    }
}
