package dev.vexelray.gui.icons;

import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.shadelet.Look;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.IconSource;
import dev.vexelray.gui.core.ImageField;
import dev.vexelray.gui.core.ImageSdg;
import dev.vexelray.gui.core.LayeredIcon;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Paint;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.shadelets.Effects;
import dev.vexelray.shadelets.OutlineStyle;
import dev.vexelray.shadelets.OutlineStyleLayout;
import dev.vexelray.shadelets.WeightStyle;
import dev.vexelray.shadelets.WeightStyleLayout;

import java.util.List;

/**
 * A node showing one icon (an {@link Icon}, an application's own {@link BakedIcon}, or any {@link IconSource}): the
 * icon set's answer to a font-icon component.
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
 * <p><b>Drawn as SDG.</b> The icon is one drawing in signed distance graphics on the node ({@link Node#sdg}), over the
 * atlas cell it was baked into: an icon of one colour is one layer in the tint; an icon in colours of its own (a
 * {@link LayeredIcon}) is its layers in the theme's row of its colours, and the tint leaves it alone. Its effects are
 * the bottom layer's.
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
    private volatile Length side = Length.rem(1f);
    /** Whether this view has asked to be painted again on a theme swap: asked once, the first time it needs to. */
    private volatile boolean followsTheme;

    private IconView(Gui gui, IconSource icon) {
        this.gui = gui;
        this.icon = icon;
        this.node = gui.box().size(Length.rem(1f), Length.rem(1f)).scroll(false, false);
        // Chosen against the box the layout settled on, which is in pixels and so already carries the scale.
        Seams.of(gui).onResize(node, computed -> {
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
        this.side = side;
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

    /**
     * Always use {@code size}'s drawing of a {@link BakedIcon} (an {@link Icon}, or an application's own), whatever the
     * size drawn; null to choose by size again.
     */
    public IconView drawing(IconSize size) {
        this.forced = size;
        paint();
        return this;
    }

    private void paint() {
        IconSource i = icon;
        float side = px;
        if (i == null || side <= 0f) {
            node.sdg(null);
            return;
        }
        // What is worked out from the theme rather than named in it (a layered icon's variant for this page, an outline's
        // colour baked into its look) is worked out again when the theme is swapped. Asked once, and only by a view
        // that has such a thing, so a list of plain icons adds nothing to the swap.
        if (!followsTheme && (i instanceof LayeredIcon || outlineRole != null)) {
            followsTheme = true;
            Seams.of(gui).onTheme(this::paint);
        }
        Look effects = ImageSdg.sdgLook(look(side));
        if (i instanceof LayeredIcon layered) {
            node.sdg(layered.sdg(side, LayeredIcon.dark(gui.theme())).look(effects));
            return;
        }
        // The tint as a paint: a role is drawn in whichever theme is drawing, and fades with it when it is swapped. The
        // drawing is built in a placeholder colour, and every layer in the tint role is then painted in that paint.
        Paint c = color != null ? Paint.of(color) : role;
        ImageSdg drawing = forced != null && i instanceof BakedIcon set ? ImageSdg.ofField(set.field(forced, c.in(gui.theme())))
                : i.sdg(side, c.in(gui.theme()));
        node.sdg(drawing.tint(c).look(effects));
    }

    /**
     * The look the icon's effects make, at {@code side} px ({@link ImageField#effects(List, List, float, float)}): the
     * outline in front, over the field source and the weight. Null with none of them, so a plain icon is drawn as the
     * shape alone. The weight and the outline are effects an application composes in, or goes without.
     */
    private Look look(float side) {
        Color band = outlineColor != null ? outlineColor
                : outlineRole != null ? gui.theme().color(outlineRole) : null;
        List<Look.Use> modifiers = weight == 0f ? List.of()
                : List.of(WeightStyleLayout.use(Effects.WEIGHT.name(), new WeightStyle(side * weight)));
        List<Look.Use> front = band == null || outline <= 0f ? List.of()
                : List.of(OutlineStyleLayout.use(Effects.OUTLINE.name(), new OutlineStyle(band, side * outline)));
        return ImageField.effects(modifiers, front, side * glow, side * shadow);
    }
}
