package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.IconSource;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.IconEffects;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.icons.IconView;
import sibarum.tactroller.api.Key;

/**
 * A button that is one icon: a close box, a pop-out, a transport control, a toolbar's tools. {@link Button} with
 * the label taken off the screen and kept as the button's name.
 *
 * <p><b>The name is not optional.</b> An icon is the whole of what a sighted user sees, but a screen reader, an
 * automation agent and a tooltip all read the name, so it is given at construction, held as a hidden label (which
 * is what the semantic snapshot names a control by), and shown as a tooltip on hover when {@link #tip} asks.
 *
 * <p>Like {@code Button}: Enter and Space press it while it is focused, a disabled one leaves the focus order, and
 * nothing changes size in any state — the box is the same with any icon in it, so swapping play for pause moves
 * nothing beside it. Ghost by default: no fill until the pointer is on it. As a {@link #toggle}, on is drawn as
 * the icon in the accent with a glow, the field's own, so a live state (running, recording, pinned) is seen at a
 * glance without a second drawing.
 *
 * <p>Handlers run on a worker thread, like every other widget's.
 */
public final class IconButton {

    private static final Shortcut ENTER = Shortcut.of(Key.ENTER);
    private static final Shortcut SPACE = Shortcut.of(Key.SPACE);

    private final Gui gui;
    private final Node node;
    private final Node label;
    private final IconView glyph;
    private volatile boolean enabled = true;
    private volatile boolean toggle;
    private volatile boolean on;
    private volatile boolean tile;
    private volatile Role hover = Role.SELECTION;
    private volatile Role ink = Role.DIM;
    private volatile InteractionState state = InteractionState.NORMAL;
    private volatile Runnable onPress = () -> { };
    private Tooltip tooltip;
    private volatile String tipText;

    /** A button showing {@code icon}, named {@code name}: 1.75 rem square, the icon 1 rem. */
    public IconButton(Gui gui, IconSource icon, String name) {
        this.gui = gui;
        this.glyph = IconView.of(gui, icon).size(Length.rem(1f));
        glyph.node().hitInert(true);
        this.label = gui.text(name == null ? "" : name).hitInert(true).visible(false);
        this.node = gui.row().role("button")
                .size(Length.rem(1.75f), Length.rem(1.75f))
                .corner(Length.rem(0.35f))
                .justify(Justify.CENTER)
                .alignItems(AlignItems.CENTER)
                .scroll(false, false)
                .children(glyph.node(), label);
        gui.focusable(node, true);
        gui.cursor(node, CursorShape.POINTER);
        gui.onClick(node, this::activate);
        gui.onState(node, s -> {
            this.state = s;
            paint();
        });
        gui.claim(node, ENTER, ClaimScope.FOCUSED, this::activate);
        gui.claim(node, SPACE, ClaimScope.FOCUSED, this::activate);
        paint();
    }

    /** The node to place in a layout. */
    public Node node() {
        return node;
    }

    /** Show {@code icon} instead: play for pause, pin for pinned. The box does not move. */
    public IconButton icon(IconSource icon) {
        glyph.icon(icon);
        return this;
    }

    /** Rename it: the accessible name. A tooltip keeps its own words ({@link #tip}). */
    public IconButton name(String name) {
        label.text(name == null ? "" : name);
        return this;
    }

    /** The box's side and the icon's, for a button bigger or smaller than the default. */
    public IconButton size(Length side, Length icon) {
        node.size(side, side);
        glyph.size(icon);
        return this;
    }

    /**
     * Make it a tile: {@code side} square, the icon {@code icon} across with its name shown under it, on a raised
     * fill with a hairline edge, so a row of them reads as a set of equal choices rather than a toolbar. For a few
     * actions a window is about, where a word is worth its room. The name is still the accessible name.
     */
    public IconButton tile(Length side, Length icon) {
        this.tile = true;
        node.direction(Direction.COLUMN).size(side, side).gap(Length.rem(0.45f))
                .corner(Length.rem(0.8f)).border(Length.dp(1), Role.LINE);
        glyph.size(icon);
        label.visible(true).textSize(Length.rem(0.6875f)).wordWrap(false);
        paint();
        return this;
    }

    /** Show {@code text} in a tooltip when the pointer rests on the button: the name, often, with its shortcut. */
    public synchronized IconButton tip(String text) {
        this.tipText = text;
        if (tooltip == null) {
            tooltip = new Tooltip(gui);
            tooltip.attach(node, () -> tipText);
        }
        return this;
    }

    /** The fill under the pointer: {@link Role#SELECTION} by default, {@link Role#DANGER} for a window's close. */
    public IconButton hover(Role role) {
        this.hover = role == null ? Role.SELECTION : role;
        paint();
        return this;
    }

    /** The icon's colour at rest: {@link Role#DIM} by default. */
    public IconButton ink(Role role) {
        this.ink = role == null ? Role.DIM : role;
        paint();
        return this;
    }

    /** What pressing it does. */
    public IconButton onPress(Runnable handler) {
        this.onPress = handler == null ? () -> { } : handler;
        return this;
    }

    /** Make this a toggle: it holds on or off, announced as such, and on glows. Pressing it still only runs the handler. */
    public IconButton toggle(boolean toggle) {
        this.toggle = toggle;
        node.role(toggle ? "togglebutton" : "button");
        paint();
        return this;
    }

    /** Show it on or off. Silent: the handler decides what a press means and calls this. */
    public IconButton on(boolean on) {
        this.on = on;
        paint();
        return this;
    }

    /** Whether it is shown on. */
    public boolean on() {
        return on;
    }

    /** Enable or disable. A disabled button ignores clicks and keys, and leaves the focus order. */
    public IconButton enabled(boolean enabled) {
        this.enabled = enabled;
        gui.focusable(node, enabled);
        gui.cursor(node, enabled ? CursorShape.POINTER : CursorShape.DEFAULT);
        paint();
        return this;
    }

    private void activate() {
        if (enabled) {
            onPress.run();
        }
    }

    private void paint() {
        InteractionState s = enabled ? state : InteractionState.NORMAL;
        boolean lit = toggle && on;
        // What the icon does under the pointer and when on is the theme's, as the hover fill is.
        IconEffects icons = gui.theme().icons();
        node.background(s == InteractionState.NORMAL ? (tile ? Role.RAISED : null) : hover);
        if (tile) {
            label.textColor(!enabled ? Role.FAINT : s == InteractionState.NORMAL ? Role.DIM : Role.INK);
        }
        if (!enabled) {
            glyph.tint(Role.FAINT).glow(0f).weight(0f);
        } else if (lit) {
            glyph.tint(Role.ACCENT).glow(icons.onGlow())
                    .weight(s == InteractionState.NORMAL ? 0f : icons.hoverWeight());
        } else {
            glyph.tint(s == InteractionState.NORMAL ? ink : Role.INK).glow(0f)
                    .weight(s == InteractionState.NORMAL ? 0f : icons.hoverWeight());
        }
    }
}
