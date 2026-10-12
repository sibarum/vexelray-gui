package dev.vexelray.gui.icons;

import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.FieldStyle;
import dev.vexelray.canvas.shadelet.CoreShadelets;
import dev.vexelray.canvas.shadelet.Look;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.IconSource;
import dev.vexelray.gui.core.ImageField;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.shadelets.Effects;
import dev.vexelray.shadelets.OutlineStyle;
import dev.vexelray.shadelets.OutlineStyleLayout;
import dev.vexelray.shadelets.WeightStyle;
import dev.vexelray.shadelets.WeightStyleLayout;

import java.util.ArrayList;
import java.util.List;

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
    private volatile float weight;
    private volatile Role outlineRole;
    private volatile Color outlineColor;
    private volatile float outline;
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

    /**
     * Draw the icon bolder ({@code share > 0}) or lighter by {@code share} of its size: its edge moved out, or in.
     * 0.04 is a clear thickening. An effect: drawn only where the application's canvas shader was composed with
     * {@code Effects.WEIGHT}, and without it, said once, where it was not.
     */
    public IconView weight(float share) {
        this.weight = share;
        paint();
        return this;
    }

    /**
     * A band along the icon's edge in the theme's colour for {@code role}, {@code share} of its size wide, half in and
     * half out; a null role for none. An effect, like {@link #weight}: {@code Effects.OUTLINE}.
     */
    public IconView outline(Role role, float share) {
        this.outlineRole = role;
        this.outlineColor = null;
        this.outline = Math.max(0f, share);
        paint();
        return this;
    }

    /** As {@link #outline(Role, float)} in {@code color}, whatever the theme; a null colour for none. */
    public IconView outline(Color color, float share) {
        this.outlineColor = color;
        this.outlineRole = null;
        this.outline = Math.max(0f, share);
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
        ImageField field = forced != null && i instanceof Icon set ? set.field(forced, style) : i.field(side, style);
        node.field(field.look(look(side)));
    }

    /**
     * The look the icon's effects make, at {@code side} px: the field source, the weight if any, the outline if any in
     * front of the field's own fill. Null with neither, so a plain icon draws exactly as it did before looks.
     */
    private Look look(float side) {
        Color band = outlineColor != null ? outlineColor
                : outlineRole != null ? gui.theme().color(outlineRole) : null;
        boolean outlined = band != null && outline > 0f;
        if (weight == 0f && !outlined) {
            return null;
        }
        List<Look.Use> modifiers = weight == 0f ? List.of()
                : List.of(WeightStyleLayout.use(Effects.WEIGHT.name(), new WeightStyle(side * weight)));
        List<Look.Use> painters = new ArrayList<>();
        if (outlined) {
            painters.add(OutlineStyleLayout.use(Effects.OUTLINE.name(), new OutlineStyle(band, side * outline)));
        }
        painters.add(Look.Use.of(CoreShadelets.FIELD_FILL.name()));
        return new Look(Look.Use.of(CoreShadelets.FIELD.name()), modifiers, painters);
    }
}
