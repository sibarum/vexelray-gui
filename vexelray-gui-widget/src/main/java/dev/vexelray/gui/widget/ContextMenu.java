package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.ClickEvent;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.MenuItem;
import dev.vexelray.gui.core.input.MenuPresenter;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Finish;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.icons.IconView;
import dev.vexelray.text.TextLayout;
import sibarum.atchung.Subscription;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.MouseButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The panel a context menu is drawn as: the framework's {@link MenuPresenter}. It does not decide what is on a
 * menu or whose menu it is — dispatch does that, from the sources declared with {@code Gui.onContextMenu} — it
 * decides what one looks like, where it sits, and how it goes away.
 *
 * <p><b>One per tree, installed by whoever needs it first.</b> Constructing one installs it
 * ({@code Gui.menus(this)}). It is registered as core's {@link MenuPresenter.Default} ({@link Default}), so the first
 * {@code Gui.onContextMenu} on a tree without a presenter builds one, whether that menu is a widget's or only the
 * application's; the widgets that ship default menus also call {@link #presentOn} in their constructors. An
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
 * <p><b>A submenu is a second panel</b> ({@link MenuItem#submenu()}): its row ends in a chevron, and pointing at it
 * (or clicking it) floats a panel drawn as this one is beside it, its first line level with the row. Pointing at
 * another line of the same panel closes it, choosing a command anywhere closes them all, and every panel is one of
 * the menu's own for click-away. Panels are kept per depth and hidden between uses, as the menu's own is.
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

    /**
     * The open submenus, outermost first: each one a panel of its own, floated beside the row that opened it. A
     * submenu is drawn as the menu is, so it is a second panel rather than rows indented into the first; the panels
     * are made the first time a depth is reached, and hidden rather than removed, as the menu's own is. Guarded by
     * {@code this}.
     */
    private final List<Level> levels = new ArrayList<>();

    /** How many of {@link #levels} are open now. Guarded by {@code this}. */
    private int depth;

    /** One depth of submenu: its panel, the rows it holds now, and the row and item that opened it. */
    private static final class Level {
        final Node panel;
        final List<Node> rows = new ArrayList<>();
        Node opener;
        MenuItem item;

        Level(Node panel) {
            this.panel = panel;
        }
    }

    private volatile boolean attached;
    private volatile boolean shown;
    /** The click this menu was opened at: not a click away from it, whichever button it was. */
    private volatile ClickEvent opener;

    /** Build the panel and install it as {@code gui}'s menu presenter, replacing any presenter already there. */
    public ContextMenu(Gui gui) {
        this.gui = gui;
        this.menu = panel(gui);
        ownIds.add(menu.id());
        this.clickSub = gui.bus().subscribe(gui.clicks(), this::onAnyClick);
        Seams.of(gui).menus(this);
    }

    /** A panel rows are drawn on: the menu's own, and each submenu's. */
    private static Node panel(Gui gui) {
        return gui.column()
                .role("menu")
                .visible(false)
                .background(Role.PANEL)
                .corner(Length.rem(0.5f))
                .border(Length.rem(0.1f), Role.LINE)
                .lit(Finish.LIT)
                .elevation(gui.theme().elevation(Relief.FLOATING))
                .padding(Length.dp(PAD_DP))
                .scroll(false, false);
    }

    /** A panel's padding: how far its first row stands below its top edge, which a submenu is raised by. */
    private static final float PAD_DP = 4f;

    /**
     * Make sure {@code gui} can show context menus, without disturbing a presenter it already has. Called by every
     * widget that declares a default menu, so the defaults work in a UI that never mentions menus at all.
     */
    public static void presentOn(Gui gui) {
        if (gui != null && Seams.of(gui).menus() == null) {
            new ContextMenu(gui);   // installs itself
        }
    }

    /**
     * This menu as the presenter core installs on a tree's first {@code Gui.onContextMenu}, found through
     * {@code META-INF/services}. Public with a public no-argument constructor because {@code ServiceLoader} makes it.
     */
    public static final class Default implements MenuPresenter.Default {
        @Override
        public MenuPresenter create(Gui gui) {
            return new ContextMenu(gui);
        }
    }

    /** The menu's own node — for tests and for styling beyond the defaults. */
    public Node node() {
        return menu;
    }

    /** Whether the menu is currently open. The question {@link Select}, {@code FindBar} and {@link Tooltip} answer
     *  under the same name. */
    public boolean isOpen() {
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
        opener = where;
        closeSubmenus(0);
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
            Seams.of(gui).claim(menu, ESCAPE, ClaimScope.VISIBLE, this::hide);
        }
    }

    /** Close the menu. Hidden, not removed: the panel keeps its identity, and its rows keep theirs until replaced. */
    public void hide() {
        if (!shown) {
            return;
        }
        shown = false;
        synchronized (this) {
            settle();
            closeSubmenus(0);
        }
        menu.visible(false);
        gui.releaseClaim(menu, ESCAPE);
    }

    /** Release the menu's subscription, registrations and nodes. */
    @Override
    public synchronized void close() {
        clickSub.close();
        hide();
        rebuild(List.of());
        for (Level level : levels) {
            release(level.rows);
            gui.releaseNode(level.panel);
            level.panel.remove();
        }
        levels.clear();
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        gui.releaseNode(menu);
        if (attached) {
            menu.remove();
        }
    }

    /** Whether a submenu is open beside the menu now: for tests. */
    public synchronized int openSubmenus() {
        return depth;
    }

    /** The panel of the submenu open at {@code at} (0 for the first beside the menu), or null. For tests. */
    synchronized Node submenuNode(int at) {
        return at < depth ? levels.get(at).panel : null;
    }

    /** What the innermost open submenu holds, in order; empty when none is open. For tests. */
    public synchronized List<MenuItem> submenuItems() {
        return depth == 0 ? List.of() : levels.get(depth - 1).item.children();
    }

    /**
     * Open {@code item}'s submenu beside {@code row}, a row at {@code at} (the menu's own rows are at 0): whatever was
     * open from that depth or deeper closes first, since only one submenu per depth can be followed. Asked again for
     * the one already open, which every pointer move over its row does, it stays as it is.
     */
    private synchronized void openSubmenu(int at, Node row, MenuItem item) {
        if (!shown) {
            return;
        }
        if (depth > at && levels.get(at).opener == row) {
            closeSubmenus(at + 1);
            return;
        }
        closeSubmenus(at);
        while (levels.size() <= at) {
            Level made = new Level(panel(gui));
            ownIds.add(made.panel.id());
            // Appended after the menu, so it paints over it and is hit before it: the overlay order is the tree's.
            gui.root().append(made.panel);
            levels.add(made);
        }
        Level level = levels.get(at);
        level.opener = row;
        level.item = item;
        fill(level.panel, level.rows, null, item.children(), at + 1);
        // Beside the row, its first row level with it. The row's rect is a frame old, which is the frame the pointer
        // found it in; px are converted to dp against the density, as the menu's own anchor is. Clamped into the
        // window by the layout, so one that would run off the right edge stands over the menu instead.
        dev.vexelray.gui.core.layout.NodeLayout l = row.layout();
        if (l.present()) {
            float dpi = Math.max(0.0001f, gui.dpi().value());
            level.panel.floatAt(Length.dp((l.rect().x() + l.rect().w()) / dpi + PAD_DP),
                    Length.dp(l.rect().y() / dpi - PAD_DP));
        }
        level.panel.visible(true);
        row.background(Role.SELECTION);   // the way to what is open stays lit while it is
        depth = at + 1;
    }

    /**
     * How long the pointer rests on a line before pointing at it does anything to the submenus. A hand on its way from
     * a submenu's line to one of the submenu's own goes diagonally, across the lines below the one it left; acting at
     * once on each of them would close the submenu before the hand arrived. Long enough for the crossing, short enough
     * not to be waited for.
     */
    static final long HOVER_MILLIS = 250L;

    /** The source of the causes the hover delay's firings begin. */
    private static final sibarum.probe.Cause.Source HOVER =
            sibarum.probe.Cause.source("menu.hover", sibarum.probe.Cause.Kind.TIMER);

    /** The hover delay's timer, made on first use, and what it will do. Guarded by {@code this}. */
    private Timer timer;
    private TimerTask pending;

    /**
     * What pointing at a line does, done once the pointer has rested there for {@link #HOVER_MILLIS}: the last line
     * pointed at wins, and a line pointed at on the way to it does nothing.
     */
    private synchronized void hovered(Runnable effect) {
        settle();
        if (timer == null) {
            timer = new Timer("vexelray-menu", true);
        }
        pending = new TimerTask() {
            @Override
            public void run() {
                // A timer firing: a cause of its own, not the hover's (atchung's docs/correlation.md).
                sibarum.probe.Cause.begin(HOVER, () -> {
                    synchronized (ContextMenu.this) {
                        if (pending == this) {
                            pending = null;
                            effect.run();
                        }
                    }
                });
            }
        };
        timer.schedule(pending, HOVER_MILLIS);
    }

    /** Drop what a hover was about to do: a click, or the menu closing, has said what is wanted. */
    private synchronized void settle() {
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
    }

    /** Close every submenu at {@code from} and deeper. */
    private void closeSubmenus(int from) {
        for (int i = depth - 1; i >= from; i--) {
            Level level = levels.get(i);
            level.panel.visible(false);
            if (level.opener != null) {
                level.opener.background((Role) null);
            }
            level.opener = null;
            level.item = null;
        }
        depth = Math.min(depth, from);
    }

    /** Whether {@code row} is the row an open submenu was opened from. */
    private synchronized boolean opens(Node row) {
        for (int i = 0; i < depth; i++) {
            if (levels.get(i).opener == row) {
                return true;
            }
        }
        return false;
    }

    /** Release {@code rows} and take them out of the tree. */
    private void release(List<Node> rows) {
        for (Node row : rows) {
            gui.releaseNode(row);
            row.remove();
        }
        rows.clear();
    }

    /**
     * Replace the rows with {@code menuItems}. The old ones leave the tree and give up their handlers — a row is the
     * one part of a menu that cannot be reused, because a menu's whole point is that its contents are decided
     * afresh each time it opens.
     */
    private void rebuild(List<MenuItem> menuItems) {
        // Every id the panel owns goes with its rows, because a row is a strip with nodes inside it now and
        // click-away judges "one of mine" against the node the pointer actually hit — which is the label, not the
        // strip around it. Rebuilt wholesale rather than removed one at a time: the set is exactly the panels plus
        // whatever this menu is made of.
        ownIds.clear();
        ownIds.add(menu.id());
        for (Level level : levels) {
            ownIds.add(level.panel.id());
        }
        fill(menu, rows, this.items, menuItems, 0);
    }

    /**
     * Put {@code menuItems} on {@code panel} as rows at {@code at}, releasing the ones {@code rows} held, and record
     * the items in {@code record} when there is one.
     */
    private void fill(Node panel, List<Node> rows, List<MenuItem> record, List<MenuItem> menuItems, int at) {
        release(rows);
        if (record != null) {
            record.clear();
        }
        // The icon column belongs to the panel, not to the item: it is reserved once, when anything on this panel
        // carries a mark, so every label starts at the same x whether or not its own item has one. A menu of plain
        // items has no column at all and is drawn exactly as it was before there were icons. Decided per opening,
        // which is the only time it can be decided without something moving under the pointer.
        boolean iconColumn = menuItems.stream().anyMatch(MenuItem::marked);
        for (MenuItem item : menuItems) {
            Node row = item.separator() ? own(rule()) : row(item, iconColumn, at);
            rows.add(row);
            if (record != null) {
                record.add(item);
            }
            panel.append(row);
        }
    }

    /** Record {@code node} as one of the menu's own, so a click on it is not a click away from the menu. */
    private Node own(Node node) {
        ownIds.add(node.id());
        return node;
    }

    /**
     * One command row: full-width, hover-shaded while it can be chosen, dimmed and inert when it cannot. {@code at} is
     * the depth of the panel it is on, the menu's own being 0.
     */
    private Node row(MenuItem item, boolean iconColumn, int at) {
        Node row = own(gui.row()
                .role("menuitem")
                .width(Length.FILL)
                .corner(Length.rem(0.4f))
                .padding(Length.dp(4), Length.dp(12))
                .gap(Length.rem(0.5f))
                .scroll(false, false));
        if (iconColumn) {
            // A cell of fixed width whether or not this item fills it: the column is what aligns the labels, so an
            // item with no mark still takes its share of it instead of sliding its label into the gap. Dimmer than
            // the label by a step — the mark is what the line is about, the label is what it says. An icon from the
            // set wins over a glyph; either sits centred in the same cell.
            Role tone = item.enabled() ? Role.DIM : Role.FAINT;
            Node cell = own(gui.row()
                    .width(Length.rem(1.2f))
                    .height(Length.rem(1.2f))
                    .justify(dev.vexelray.gui.core.layout.LayoutEnums.Justify.CENTER)
                    .alignItems(dev.vexelray.gui.core.layout.LayoutEnums.AlignItems.CENTER)
                    .scroll(false, false));
            if (item.mark() != null) {
                cell.append(own(IconView.of(gui, item.mark()).size(Length.rem(1f)).tint(tone).node()));
            } else if (item.icon() != null) {
                cell.append(own(gui.text(item.icon())
                        .textSize(Length.rem(1))
                        .textColor(tone)
                        .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE)));
            }
            row.append(cell);
        }
        row.append(own(gui.text(item.label())
                .width(Length.FILL)
                .textSize(Length.rem(1))
                .textColor(item.enabled() ? Role.INK : Role.FAINT)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE)));
        if (item.submenu()) {
            // The sign that this line goes further rather than doing something: a chevron at its end, where the
            // submenu will stand.
            row.append(own(IconView.of(gui, Icon.CHEVRON_RIGHT).size(Length.rem(0.8f))
                    .tint(item.enabled() ? Role.DIM : Role.FAINT).node()));
        }
        if (!item.enabled()) {
            return row;   // no handlers at all: nothing to hover, nothing to choose
        }
        if (item.submenu()) {
            // Opened by pointing at it, as a desktop's submenus are, once the pointer rests there (see hovered), and
            // at once by a click, for a hand that does not hover (a touch, a script). It stays lit while what it
            // opened is open.
            Seams.of(gui).onState(row, state -> {
                if (state != InteractionState.NORMAL) {
                    row.background(Role.SELECTION);
                    hovered(() -> openSubmenu(at, row, item));
                } else if (!opens(row)) {
                    row.background((Role) null);
                }
            });
            Seams.of(gui).onClick(row, () -> {
                settle();
                openSubmenu(at, row, item);
            });
        } else {
            // Transparent at rest so the menu's own surface shows through, and the hover fill is the theme's, not a
            // constant of this widget's: an item is a panel the pointer is on. Pointing at a command closes any
            // submenu opened beside a row at its own depth, once the pointer rests there: it has moved on from it.
            Seams.of(gui).onState(row, state -> {
                row.background(state == InteractionState.NORMAL ? null : Role.SELECTION);
                if (state != InteractionState.NORMAL) {
                    hovered(() -> closeSubmenus(at));
                }
            });
            Seams.of(gui).onClick(row, () -> {
                hide();
                item.action().run();   // on the GUI thread, in the dispatch: an item sends its message from here
            });
        }
        // Addressable by what it says, so a script can choose it: menu.restart-in-debug-mode. Released with the row.
        gui.landmark("menu." + slug(item.label()), row);
        return row;
    }

    /** {@code Restart in debug mode} as {@code restart-in-debug-mode}: lower case, a dash for every run of anything else. */
    static String slug(String label) {
        String s = label.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("^-+|-+$", "");
    }

    /** A thin horizontal rule between groups. */
    private Node rule() {
        return gui.box().width(Length.FILL).height(Length.dp(1)).background(Role.LINE)
                .margin(Length.dp(3)).scroll(false, false);
    }

    /**
     * Click-away: every click on every node is published here, so "the user clicked somewhere else" is an
     * observation, not a capture. Left button only — the right click that would re-anchor this menu publishes
     * too, and must not race the show it triggers.
     */
    private void onAnyClick(ClickEvent e) {
        // Nor the left click that opened it, which a button whose click is a menu (a title-bar instrument's) publishes
        // too, and which may arrive here after the show.
        if (shown && e.button() == MouseButton.LEFT && !ownIds.contains(e.nodeId()) && !e.equals(opener)) {
            hide();
        }
    }
}
