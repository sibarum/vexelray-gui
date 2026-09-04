package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A column of icons that chooses which panel is on show, and a panel beside it that shows it.
 *
 * <h2>This is not {@link Tabs} turned on its side</h2>
 *
 * A tab bar and a rail answer different questions. Tabs say <em>which of these documents am I looking at</em>, so
 * one is always selected and the bar is part of the content beneath it. A rail says <em>which tool am I holding</em>,
 * so <b>none</b> is a legitimate answer: clicking the selected icon puts the panel away and leaves the rail, which
 * is the whole reason a rail is worth having over a bar — the work is the canvas, and the panel is on loan from it.
 * That difference is a state a tab bar does not have and should not grow, so this is its own widget rather than an
 * orientation flag on that one.
 *
 * <h2>Pages are built once and hidden</h2>
 *
 * A page is a {@link Page} builder rather than a node, and it is built the first time it is shown and kept
 * thereafter — the same choice {@link Tabs} makes for its pages and {@link Popout} for its two hosts, for the
 * same reason. A {@link Node}'s registrations are keyed by node id inside the tree that minted it and released
 * when it leaves, so a page that was removed and rebuilt would come back drawing correctly and unable to take a
 * keystroke. Building lazily is the affordable half of that: a rail with six panels costs one panel's nodes until
 * the others are asked for.
 *
 * <h2>The rail does not move</h2>
 *
 * The icons keep their places whether the panel is open or shut, and the panel takes its space from the content
 * rather than from the rail. Nothing here appears, grows or shifts under the pointer: the icon under the pointer
 * when the panel opens is the icon under it afterwards.
 */
public final class Rail {

    private static final Length TILE = Length.rem(1.9f);

    /** Builds a panel's contents, once, into the node it is given. */
    @FunctionalInterface
    public interface Page {
        void build(Gui gui, Node into);
    }

    private final Gui gui;
    private final Node bar;
    private final Node panel;
    private final Node panelTitle;
    private final Node panelHint;
    private final Node panelBody;
    private final List<Item> items = new ArrayList<>();
    private volatile String selected;
    private volatile Consumer<String> onSelect = k -> { };

    private record Item(String key, String title, Node tile, Node body, Page page, boolean[] built) {
    }

    /** Build an empty rail on {@code gui}; add panels with {@link #item}. */
    public Rail(Gui gui) {
        this.gui = gui;
        Theme theme = gui.theme();
        this.bar = gui.column().role("rail")
                .gap(Length.rem(0.2f))
                .padding(Length.rem(0.35f))
                .corner(Length.rem(0.5f))
                .background(theme.color(Role.CHROME))
                .border(Length.dp(1), theme.color(Role.EDGE))
                .lit(theme.lit()).elevation(theme.elevation(Relief.FLOATING))
                .alignItems(AlignItems.CENTER)
                .scroll(false, false);

        this.panelTitle = gui.text("").width(Length.grow(1f))
                .textSize(Length.rem(0.62f)).wordWrap(false).textColor(theme.color(Role.DIM));
        this.panelHint = gui.text("")
                .textSize(Length.rem(0.6f)).wordWrap(false).textColor(theme.color(Role.FAINT));
        Node close = gui.text("×").size(Length.rem(1.2f), Length.rem(1.2f))
                .corner(Length.rem(0.25f))
                .textColor(theme.color(Role.FAINT))
                .align(dev.vexelray.text.TextLayout.HAlign.CENTER, dev.vexelray.text.TextLayout.VAlign.MIDDLE);
        gui.focusable(close, true);
        gui.cursor(close, CursorShape.POINTER);
        gui.onClick(close, this::close);
        gui.onState(close, s -> close.background(
                theme.color(s == InteractionState.NORMAL ? Role.NONE : Role.SELECTION)));
        Node head = gui.row().role("rail-panel-head")
                .width(Length.grow(1f)).gap(Length.rem(0.5f))
                .padding(Length.rem(0.4f), Length.rem(0.55f))
                .alignItems(AlignItems.CENTER)
                .children(panelTitle, panelHint, close);
        Node headRule = gui.box().width(Length.percent(100)).height(Length.dp(1))
                .background(theme.color(Role.LINE));
        this.panelBody = gui.column().width(Length.grow(1f)).height(Length.grow(1f)).scroll(false, true);
        this.panel = gui.column().role("rail-panel")
                .corner(Length.rem(0.5f))
                .background(theme.color(Role.PANEL))
                .border(Length.dp(1), theme.color(Role.EDGE))
                .lit(theme.lit()).elevation(theme.elevation(Relief.FLOATING))
                .clip(true)
                .visible(false)
                .children(head, headRule, panelBody);
    }

    /**
     * Add a panel.
     *
     * @param key   how the application names this panel — what {@link #select} takes and {@link #onSelect} hands back
     * @param icon  the tile's contents; the application's, because this module has no icon set and should not grow one
     * @param title shown in the panel's heading
     * @param page  builds the panel's contents, the first time it is shown
     */
    public Rail item(String key, Node icon, String title, Page page) {
        Theme theme = gui.theme();
        Node tile = gui.box().role("rail-item")
                .size(TILE, TILE)
                .corner(Length.rem(0.35f))
                .alignItems(AlignItems.CENTER)
                .children(icon.width(Length.percent(100)).height(Length.percent(100))
                        .align(dev.vexelray.text.TextLayout.HAlign.CENTER,
                                dev.vexelray.text.TextLayout.VAlign.MIDDLE));
        Node body = gui.column().width(Length.grow(1f)).visible(false);
        Item item = new Item(key, title, tile, body, page, new boolean[1]);
        items.add(item);
        gui.focusable(tile, true);
        gui.cursor(tile, CursorShape.POINTER);
        gui.onClick(tile, () -> toggle(key));
        gui.onState(tile, s -> paintTile(item, s == InteractionState.HOVER));
        bar.append(tile);
        panelBody.append(body);
        paintTile(item, false);
        return this;
    }

    /**
     * Add a divider, and below it an icon that runs an action rather than opening a panel — a reset, a fit, a
     * home. It never becomes the selection, so opening it does not put away whatever panel is on show.
     */
    public Rail action(Node icon, Runnable act) {
        Theme theme = gui.theme();
        Node rule = gui.box().width(Length.percent(70)).height(Length.dp(1))
                .margin(Length.rem(0.15f))
                .background(theme.color(Role.LINE));
        Node tile = gui.box().role("rail-action")
                .size(TILE, TILE)
                .corner(Length.rem(0.35f))
                .alignItems(AlignItems.CENTER)
                .children(icon.width(Length.percent(100)).height(Length.percent(100))
                        .align(dev.vexelray.text.TextLayout.HAlign.CENTER,
                                dev.vexelray.text.TextLayout.VAlign.MIDDLE));
        gui.focusable(tile, true);
        gui.cursor(tile, CursorShape.POINTER);
        gui.onClick(tile, act);
        gui.onState(tile, s -> {
            tile.background(theme.color(s == InteractionState.NORMAL ? Role.NONE : Role.SELECTION));
            tile.textColor(theme.color(s == InteractionState.NORMAL ? Role.FAINT : Role.INK));
        });
        tile.textColor(theme.color(Role.FAINT));
        bar.append(rule);
        bar.append(tile);
        return this;
    }

    /** The icon column. Place it against the edge it belongs to. */
    public Node node() {
        return bar;
    }

    /** The panel. Place it beside {@link #node()}; it is hidden while nothing is selected. */
    public Node panel() {
        return panel;
    }

    /** The selected panel's key, or null when the panel is shut. */
    public String selected() {
        return selected;
    }

    /** Show a panel, building it if this is the first time. A key with no item shuts the panel. */
    public Rail select(String key) {
        Item found = null;
        for (Item i : items) {
            if (i.key().equals(key)) {
                found = i;
            }
        }
        this.selected = found == null ? null : key;
        for (Item i : items) {
            i.body().visible(found != null && i == found);
            paintTile(i, false);
        }
        if (found != null) {
            if (!found.built()[0]) {
                found.built()[0] = true;
                found.page().build(gui, found.body());
            }
            panelTitle.text(found.title().toUpperCase(java.util.Locale.ROOT));
        }
        panel.visible(found != null);
        onSelect.accept(this.selected);
        return this;
    }

    /** Shut the panel, leaving the rail. */
    public Rail close() {
        return select(null);
    }

    /** Show a panel, or shut it if it is already the one on show. */
    public Rail toggle(String key) {
        return select(key.equals(selected) ? null : key);
    }

    /** A short word beside the panel's title — a count, a mode, a unit. */
    public Rail hint(String text) {
        panelHint.text(text);
        return this;
    }

    /** How wide the panel is. Its height is the layout's to decide. */
    public Rail panelWidth(Length width) {
        panel.width(width);
        return this;
    }

    /** React to the selection, including to null when the panel shuts. Runs on a worker thread. */
    public Rail onSelect(Consumer<String> handler) {
        this.onSelect = handler == null ? k -> { } : handler;
        return this;
    }

    /** Attach hover titles for every item added so far. */
    public Rail titles(Tooltip tooltip) {
        for (Item i : items) {
            tooltip.attach(i.tile(), i.title());
        }
        return this;
    }

    private void paintTile(Item item, boolean hover) {
        Theme theme = gui.theme();
        boolean isSelected = item.key().equals(selected);
        item.tile().background(theme.color(
                isSelected ? Role.SELECTION : hover ? Role.RAISED : Role.NONE));
        item.tile().textColor(theme.color(isSelected ? Role.ACCENT : hover ? Role.INK : Role.DIM));
    }
}
