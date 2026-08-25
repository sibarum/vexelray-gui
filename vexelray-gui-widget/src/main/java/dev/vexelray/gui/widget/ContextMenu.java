package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.ClickEvent;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.MenuItem;
import dev.vexelray.gui.core.input.MenuPresenter;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.text.TextLayout;
import sibarum.atchung.Subscription;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The panel a context menu is drawn as: the framework's {@link MenuPresenter}. It does not decide what is on a
 * menu or whose menu it is — dispatch does that, from the sources declared with {@code Gui.onContextMenu} — it
 * decides what one looks like, where it sits, and how it goes away.
 *
 * <p><b>One per tree, installed by whoever needs it first.</b> Constructing one installs it
 * ({@code Gui.menus(this)}), and the widgets that ship default menus call {@link #presentOn} in their
 * constructors, so a right click on a text field opens something without the application wiring anything. An
 * application that wants a different-looking menu constructs its own implementation and installs it before
 * building the UI.
 *
 * <p><b>The menu is a floating last child of the root</b> — the overlay primitive, not a special layer. It floats
 * out of the flow ({@code Node.floatAt}), so opening it reflows nothing; being the last child, it paints over and
 * is hit before the page; and the layout clamps a floating node into its parent, so a menu opened near an edge
 * slides in rather than cropping. It attaches itself on first use — after the page is built — and is hidden, never
 * removed, between openings.
 *
 * <p><b>Its rows, though, are rebuilt per opening</b>, because that is what the menu <em>is</em>: the answer to
 * "what applies right now". The panel, its subscription, its claim and its identity survive; the rows are released
 * when the next menu takes their place, the same way {@link Modals} treats a dialog's buttons.
 *
 * <p><b>Dismissal is claims and observation, not modality.</b> While open, the menu claims Escape at
 * {@link ClaimScope#VISIBLE} — released on hide, because a hidden node keeps its claims. Click-away rides the
 * {@code clicks()} topic, which publishes every click on every node: a left click whose target is not one of the
 * menu's own nodes closes it. Right clicks are deliberately ignored there — the dispatch that re-anchors the menu
 * publishes one too, and racing it would close what it just opened. Nothing is blocked while the menu is up; the
 * click that lands elsewhere still does what it always did.
 *
 * <p><b>A row is a strip, not a line of text</b>, because an item may carry a mark: the icon column is reserved
 * once per opening, for the whole menu, whenever anything on it has one — so the labels line up under each other,
 * an item with no mark still takes its share of the column, and a menu of plain items is drawn with no column at
 * all, exactly as it was before there were icons.
 *
 * <p>A disabled row is inert by construction: it carries no click handler, so choosing it does nothing — and it is
 * still one of the menu's own nodes, so the menu stays open rather than treating the click as "somewhere else".
 *
 * <p>Anchor coordinates are client-space pixels (what {@link ClickEvent} carries); the menu converts them to
 * {@code dp} against the current density, so the position is faithful at any DPI and ignores zoom — a pointer
 * position is a physical fact, not content.
 */
public final class ContextMenu implements MenuPresenter {

    private static final Shortcut ESCAPE = Shortcut.of(Key.ESCAPE);

    private final Gui gui;
    private final Node menu;
    private final Subscription clickSub;

    /** Every node this menu owns, so click-away can tell "one of mine" from "somewhere else". */
    private final Set<Long> ownIds = ConcurrentHashMap.newKeySet();

    /** The rows of the menu currently built, released when the next one replaces them. Guarded by {@code this}. */
    private final List<Node> rows = new ArrayList<>();

    /** The items those rows were built from, in the same order — a handle is write-only, so this is the record. */
    private final List<MenuItem> items = new ArrayList<>();

    private volatile boolean attached;
    private volatile boolean shown;

    /** Build the panel and install it as {@code gui}'s menu presenter, replacing any presenter already there. */
    public ContextMenu(Gui gui) {
        this.gui = gui;
        this.menu = gui.column()
                .visible(false)
                .background(gui.theme().color(Role.PANEL))
                .corner(Length.rem(0.5f))
                .border(Length.rem(0.1f), gui.theme().color(Role.LINE))
                .lit(gui.theme().lit())
                .elevation(Length.rem(1f))
                .padding(Length.dp(4))
                .scroll(false, false);
        ownIds.add(menu.id());
        this.clickSub = gui.bus().subscribe(gui.clicks(), this::onAnyClick);
        gui.menus(this);
    }

    /**
     * Make sure {@code gui} can show context menus, without disturbing a presenter it already has. Called by every
     * widget that declares a default menu, so the defaults work in a UI that never mentions menus at all.
     */
    public static void presentOn(Gui gui) {
        if (gui != null && gui.menus() == null) {
            new ContextMenu(gui);   // installs itself
        }
    }

    /** The menu's own node — for tests and for styling beyond the defaults. */
    public Node node() {
        return menu;
    }

    /** Whether the menu is currently open. */
    public boolean shown() {
        return shown;
    }

    /** What is currently on the menu, in order — for tests, and for anything that wants to inspect a menu. */
    public synchronized List<MenuItem> items() {
        return List.copyOf(items);
    }

    /**
     * Show the items at the click that asked for them: the rows are rebuilt from scratch, then the panel is
     * anchored at the pointer and made visible. Reopening while already open just re-anchors it.
     */
    @Override
    public synchronized void present(ClickEvent where, List<MenuItem> menuItems) {
        rebuild(menuItems);
        if (!attached) {
            attached = true;
            gui.root().append(menu);
        }
        float dpi = Math.max(0.0001f, gui.dpi().value());
        menu.floatAt(Length.dp(where.x() / dpi), Length.dp(where.y() / dpi));
        menu.visible(true);
        if (!shown) {
            shown = true;
            // Claimed only while open (and released on hide): a hidden node keeps its claims, and a closed menu
            // owning Escape would shadow whatever the page wants it for.
            gui.claim(menu, ESCAPE, ClaimScope.VISIBLE, this::hide);
        }
    }

    /** Close the menu. Hidden, not removed: the panel keeps its identity, and its rows keep theirs until replaced. */
    public void hide() {
        if (!shown) {
            return;
        }
        shown = false;
        menu.visible(false);
        gui.releaseClaim(menu, ESCAPE);
    }

    /** Release the menu's subscription, registrations and nodes. */
    @Override
    public synchronized void close() {
        clickSub.close();
        hide();
        rebuild(List.of());
        gui.releaseNode(menu);
        if (attached) {
            menu.remove();
        }
    }

    /**
     * Replace the rows with {@code menuItems}. The old ones leave the tree and give up their handlers — a row is the
     * one part of a menu that cannot be reused, because a menu's whole point is that its contents are decided
     * afresh each time it opens.
     */
    private void rebuild(List<MenuItem> menuItems) {
        for (Node row : rows) {
            gui.releaseNode(row);
            row.remove();
        }
        rows.clear();
        this.items.clear();
        // Every id the panel owns goes with its rows, because a row is a strip with nodes inside it now and
        // click-away judges "one of mine" against the node the pointer actually hit — which is the label, not the
        // strip around it. Rebuilt wholesale rather than removed one at a time: the set is exactly the panel plus
        // whatever this menu is made of.
        ownIds.clear();
        ownIds.add(menu.id());
        // The icon column belongs to the menu, not to the item: it is reserved once, when anything on this menu
        // carries a mark, so every label starts at the same x whether or not its own item has one. A menu of plain
        // items has no column at all and is drawn exactly as it was before there were icons. Decided per opening,
        // which is the only time it can be decided without something moving under the pointer.
        boolean iconColumn = menuItems.stream().anyMatch(i -> i.icon() != null);
        for (MenuItem item : menuItems) {
            Node row = item.separator() ? own(rule()) : row(item, iconColumn);
            rows.add(row);
            this.items.add(item);
            menu.append(row);
        }
    }

    /** Record {@code node} as one of the menu's own, so a click on it is not a click away from the menu. */
    private Node own(Node node) {
        ownIds.add(node.id());
        return node;
    }

    /** One command row: full-width, hover-shaded while it can be chosen, dimmed and inert when it cannot. */
    private Node row(MenuItem item, boolean iconColumn) {
        Node row = own(gui.row()
                .width(Length.FILL)
                .corner(Length.rem(0.4f))
                .padding(Length.dp(4), Length.dp(12))
                .gap(Length.em(0.5f))
                .scroll(false, false));
        if (iconColumn) {
            // A cell of fixed width whether or not this item fills it: the column is what aligns the labels, so an
            // item with no mark still takes its share of it instead of sliding its label into the gap. Dimmer than
            // the label by a step — the mark is what the line is about, the label is what it says.
            row.append(own(gui.text(item.icon() == null ? "" : item.icon())
                    .width(Length.em(1.2f))
                    .textSize(Length.rem(1))
                    .textColor(gui.theme().color(item.enabled() ? Role.DIM : Role.FAINT))
                    .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE)));
        }
        row.append(own(gui.text(item.label())
                .width(Length.FILL)
                .textSize(Length.rem(1))
                .textColor(gui.theme().color(item.enabled() ? Role.INK : Role.FAINT))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE)));
        if (!item.enabled()) {
            return row;   // no handlers at all: nothing to hover, nothing to choose
        }
        // Transparent at rest so the menu's own surface shows through, and the hover fill is the theme's, not a
        // constant of this widget's: an item is a panel the pointer is on.
        gui.onState(row, state -> row.background(
                state == InteractionState.NORMAL ? null : gui.theme().color(Role.SELECTION)));
        gui.onClick(row, () -> {
            hide();
            item.action().run();   // already on the handler executor — the lane app callbacks run on
        });
        return row;
    }

    /** A thin horizontal rule between groups. */
    private Node rule() {
        return gui.box().width(Length.FILL).height(Length.dp(1)).background(gui.theme().color(Role.LINE))
                .margin(Length.dp(3)).scroll(false, false);
    }

    /**
     * Click-away: every click on every node is published here, so "the user clicked somewhere else" is an
     * observation, not a capture. Left button only — the right click that would re-anchor this menu publishes
     * too, and must not race the show it triggers.
     */
    private void onAnyClick(ClickEvent e) {
        if (shown && e.button() == MouseButton.LEFT && !ownIds.contains(e.nodeId())) {
            hide();
        }
    }
}
