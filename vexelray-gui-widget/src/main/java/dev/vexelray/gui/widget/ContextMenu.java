package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.ClickEvent;
import dev.vexelray.gui.core.input.Commands;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.MenuItem;
import dev.vexelray.gui.core.input.MenuPresenter;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.input.Trigger;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Finish;
import dev.vexelray.gui.core.style.Paint;
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
 * <p><b>The keyboard walks it.</b> While open it also claims Up and Down (to the line before or after, past rules,
 * headings and greyed lines, round the ends), Home and End, Enter (choose, or open a submenu), Right (into a submenu)
 * and Left (back out). The line under the keyboard is painted as the pointer would paint it, and the pointer resting on
 * a line moves the keyboard there. A menu opened by the pointer marks nothing until a key; one dropped by a key
 * ({@code TitleBar.open}) starts on its first line ({@link #markFirst}).
 *
 * <p><b>Dismissal is claims and observation, not modality.</b> While open, the menu claims Escape at
 * {@link ClaimScope#VISIBLE} — released on hide with the walking keys, because a hidden node keeps its claims. Click-away rides the
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

    /**
     * The keys that walk an open menu, claimed with Escape while it is open and released with it: Up and Down to the
     * line before or after (wrapping, past rules, headings and greyed lines), Home and End to the first or last, Enter
     * to choose, Right into a submenu, Left back out of one.
     */
    private static final Shortcut UP = Shortcut.of(Key.UP);
    private static final Shortcut DOWN = Shortcut.of(Key.DOWN);
    private static final Shortcut HOME = Shortcut.of(Key.HOME);
    private static final Shortcut END = Shortcut.of(Key.END);
    private static final Shortcut ENTER = Shortcut.of(Key.ENTER);
    private static final Shortcut NUMPAD_ENTER = Shortcut.of(Key.NUMPAD_ENTER);
    private static final Shortcut RIGHT = Shortcut.of(Key.RIGHT);
    private static final Shortcut LEFT = Shortcut.of(Key.LEFT);
    private static final Shortcut[] WALK = {UP, DOWN, HOME, END, ENTER, NUMPAD_ENTER, RIGHT, LEFT};

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

    /**
     * The line under the keyboard: the depth of its panel (0 the menu's own) and its index among that panel's rows,
     * or {@code keyAt} -1 for none. The pointer resting on a line moves it there too, so the keys go on from wherever
     * the hand left off. Guarded by {@code this}.
     */
    private int keyDepth;
    private int keyAt = -1;

    /** Each command row's icon, so the keyboard's mark paints a row as the pointer's does. Guarded by {@code this}. */
    private final java.util.Map<Node, IconView> rowIcons = new java.util.HashMap<>();

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

    /**
     * A panel rows are drawn on: the menu's own, and each submenu's. A card floating over the page, as Rasterware 0.1's
     * settings were: the panel's surface, a hairline edge, rounded well inside the rows' own corners.
     */
    private static Node panel(Gui gui) {
        return gui.column()
                .role("menu")
                .visible(false)
                .background(Role.PANEL)
                .corner(Length.rem(0.75f))
                .border(Length.dp(1), Role.LINE)
                .lit(Finish.LIT)
                .elevation(gui.theme().elevation(Relief.FLOATING))
                .padding(Length.dp(PAD_DP))
                .gap(Length.dp(1))
                .scroll(false, false);
    }

    /** A panel's padding: how far its first row stands below its top edge, which a submenu is raised by. */
    private static final float PAD_DP = 6f;

    /** A line's icon: a little larger than its words, so its detail reads, in a cell a little larger again. */
    private static final Length ICON = Length.rem(1.25f);
    private static final Length ICON_CELL = Length.rem(1.5f);

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
        // Whatever opened the menu before has lost it: this opening is someone else's (or its own again, which says so
        // after this returns).
        hidden();
        opener = where;
        closeSubmenus(0);
        keyAt = -1;
        rebuild(menuItems);
        float dpi = Math.max(0.0001f, Seams.of(gui).dpi().value());
        menu.floatAt(Length.dp(where.x() / dpi), Length.dp(where.y() / dpi));
        if (!shown) {
            shown = true;
            closing = null;
            // Faded up rather than shown at once, and faded out rather than hidden at once (hide): the window's motion
            // fades an opacity as it fades a colour. The first opening is the panel arriving in the tree, which the
            // window's presence motion fades up and raises into its place. Never removed between openings, because
            // removing a node releases it and everything keyed by it.
            if (!attached) {
                attached = true;
                gui.root().append(menu.visible(true).opacity(1f).hitInert(false));
            } else {
                menu.visible(true).hitInert(false).opacity(1f);
            }
            // Claimed only while open (and released on hide): a hidden node keeps its claims, and a closed menu
            // owning Escape would shadow whatever the page wants it for.
            Seams.of(gui).claim(menu, ESCAPE, ClaimScope.VISIBLE, this::hide);
            Seams.of(gui).claim(menu, UP, ClaimScope.VISIBLE, () -> step(-1));
            Seams.of(gui).claim(menu, DOWN, ClaimScope.VISIBLE, () -> step(1));
            Seams.of(gui).claim(menu, HOME, ClaimScope.VISIBLE, () -> edge(true));
            Seams.of(gui).claim(menu, END, ClaimScope.VISIBLE, () -> edge(false));
            Seams.of(gui).claim(menu, ENTER, ClaimScope.VISIBLE, this::choose);
            Seams.of(gui).claim(menu, NUMPAD_ENTER, ClaimScope.VISIBLE, this::choose);
            Seams.of(gui).claim(menu, RIGHT, ClaimScope.VISIBLE, this::into);
            Seams.of(gui).claim(menu, LEFT, ClaimScope.VISIBLE, this::outOf);
        }
    }

    // ------------------------------------------------------------------ the keyboard

    /**
     * Put the keyboard on the menu's first line that can be chosen: what a menu opened by a key does, so the next key
     * moves from there ({@code TitleBar.open}). A menu opened by the pointer starts with nothing marked, as a desktop
     * menu does, and the first Down or Up marks its first or last line.
     */
    public synchronized void markFirst() {
        if (shown) {
            keyDepth = 0;
            edge(true);
        }
    }

    /**
     * Drop {@code menu} from {@code target}'s lower left, as a menu bar's menu drops, or close whichever menu is open:
     * the one behaviour of everything whose plain press is a menu (a title-bar menu, a {@link Button#menu menu
     * button}). {@code at} is the click, or null for a key, which puts the keyboard on the first line
     * ({@link #markFirst}). Nothing for an empty menu or a target not laid out yet. In the dispatch, as a click's is.
     */
    static void drop(Gui gui, Node target, ClickEvent at, dev.vexelray.gui.core.input.Menu menu) {
        if (menu.isEmpty()) {
            return;
        }
        if (Seams.of(gui).menus() instanceof ContextMenu open && open.isOpen()) {
            open.hide();
            return;
        }
        dev.vexelray.gui.core.layout.NodeLayout l = target.layout();
        if (!l.present()) {
            return;   // not laid out: nowhere to hang it from
        }
        float dpi = Math.max(0.0001f, Seams.of(gui).dpi().value());
        ClickEvent under = new ClickEvent(target.id(), at == null ? MouseButton.LEFT : at.button(),
                l.rect().x(), l.rect().y() + l.rect().h() + 6 * dpi, 1, at == null ? Set.of() : at.modifiers());
        Seams.of(gui).openMenu(under, sink -> {
            for (MenuItem item : menu.items()) {
                sink.item(item);
            }
        });
        if (at == null && Seams.of(gui).menus() instanceof ContextMenu open && open.isOpen()) {
            open.markFirst();
        }
    }

    /**
     * {@link #drop(Gui, Node, ClickEvent, dev.vexelray.gui.core.input.Menu)}, and {@code onOpen} told {@code true} when
     * the menu opens from {@code target} and {@code false} when it closes, however it closes (a line chosen, a click
     * away, Escape, another menu opening in its place): what lets a target stay lit while its menu is open, as a menu
     * bar's open menu is.
     */
    static void drop(Gui gui, Node target, ClickEvent at, dev.vexelray.gui.core.input.Menu menu,
                     java.util.function.Consumer<Boolean> onOpen) {
        drop(gui, target, at, menu);
        whileOpen(gui, onOpen);
    }

    /**
     * If a menu is open on {@code gui} now, tell {@code onOpen} {@code true}, and {@code false} once it closes or is
     * replaced: called right after something opens a menu, by the thing that opened it. Nothing if none is open.
     */
    static void whileOpen(Gui gui, java.util.function.Consumer<Boolean> onOpen) {
        if (Seams.of(gui).menus() instanceof ContextMenu open && open.isOpen()) {
            open.untilHidden(() -> onOpen.accept(false));
            onOpen.accept(true);
        }
    }

    /** What to run when the menu next hides or is opened again: one at a time, the latest opener's. Guarded by this. */
    private Runnable onHidden;

    /** Run {@code r} once, when the menu next hides or opens again; replaces one not yet run, running it first. */
    synchronized void untilHidden(Runnable r) {
        hidden();
        onHidden = r;
    }

    /** Tell whoever is waiting that the menu is no longer theirs. */
    private void hidden() {
        Runnable r;
        synchronized (this) {
            r = onHidden;
            onHidden = null;
        }
        if (r != null) {
            r.run();
        }
    }

    /** The line under the keyboard as {@code depth.index}, or null for none. For tests. */
    synchronized String marked() {
        return keyAt < 0 ? null : keyDepth + "." + keyAt;
    }

    /** A submenu the pointer closed may have held the keyboard: it goes back to the deepest panel still open. */
    private void clamp() {
        if (keyDepth > depth) {
            keyDepth = depth;
            keyAt = -1;
        }
    }

    private List<Node> rowsAt(int d) {
        return d == 0 ? rows : levels.get(d - 1).rows;
    }

    private List<MenuItem> itemsAt(int d) {
        return d == 0 ? items : levels.get(d - 1).item.children();
    }

    private static boolean choosable(MenuItem item) {
        return !item.separator() && !item.heading() && item.enabled();
    }

    /** Move the keyboard {@code delta} lines along its panel, wrapping, past what cannot be chosen. */
    private synchronized void step(int delta) {
        if (!shown) {
            return;
        }
        clamp();
        if (keyAt < 0) {
            keyDepth = 0;
            edge(delta > 0);
            return;
        }
        List<MenuItem> line = itemsAt(keyDepth);
        int n = line.size();
        for (int k = 1; k <= n; k++) {
            int i = Math.floorMod(keyAt + delta * k, n);
            if (choosable(line.get(i))) {
                mark(keyDepth, i);
                return;
            }
        }
    }

    /** The first or the last line of the keyboard's panel that can be chosen. */
    private synchronized void edge(boolean first) {
        clamp();
        List<MenuItem> line = itemsAt(keyDepth);
        int n = line.size();
        for (int k = 0; k < n; k++) {
            int i = first ? k : n - 1 - k;
            if (choosable(line.get(i))) {
                mark(keyDepth, i);
                return;
            }
        }
    }

    /** Put the keyboard's mark on line {@code i} of the panel at {@code d}, painting it as the pointer would. */
    private synchronized void mark(int d, int i) {
        unmark();
        keyDepth = d;
        keyAt = i;
        Node row = rowsAt(d).get(i);
        paint(row, rowIcons.get(row), itemsAt(d).get(i), InteractionState.HOVER);
    }

    /** Take the mark off its line, which goes back to rest unless it is the way to an open submenu. */
    private synchronized void unmark() {
        if (keyAt < 0 || keyDepth > depth || keyAt >= rowsAt(keyDepth).size()) {
            keyAt = -1;
            return;
        }
        Node row = rowsAt(keyDepth).get(keyAt);
        if (!opens(row)) {
            paint(row, rowIcons.get(row), itemsAt(keyDepth).get(keyAt), InteractionState.NORMAL);
        }
        keyAt = -1;
    }

    /** The pointer is resting on {@code row}, at depth {@code at}: the keyboard goes on from there. */
    private synchronized void pointed(int at, Node row) {
        int i = rowsAt(at).indexOf(row);
        if (i < 0 || (keyDepth == at && keyAt == i)) {
            return;
        }
        unmark();
        keyDepth = at;
        keyAt = i;
    }

    /** Enter: choose the marked command, or open the marked submenu and step into it. */
    private void choose() {
        Runnable action;
        synchronized (this) {
            clamp();
            if (!shown || keyAt < 0) {
                return;
            }
            MenuItem item = itemsAt(keyDepth).get(keyAt);
            if (item.submenu()) {
                into();
                return;
            }
            action = item.action();
        }
        hide();
        action.run();   // on the GUI thread, in the dispatch of the key, as a click's is in the click's
    }

    /** Right: open the marked line's submenu, and put the keyboard on its first line. */
    private synchronized void into() {
        clamp();
        if (!shown || keyAt < 0) {
            return;
        }
        MenuItem item = itemsAt(keyDepth).get(keyAt);
        if (!item.submenu() || !item.enabled()) {
            return;
        }
        int at = keyDepth;
        openSubmenu(at, rowsAt(at).get(keyAt), item);
        if (depth > at) {
            keyAt = -1;   // the row it opened from stays lit as the way to what is open
            keyDepth = at + 1;
            edge(true);
        }
    }

    /** Left: close the submenu the keyboard is in, and put it back on the line that opened it. */
    private synchronized void outOf() {
        clamp();
        if (!shown || keyDepth == 0) {
            return;
        }
        int parent = keyDepth - 1;
        Node opener = levels.get(parent).opener;
        unmark();
        closeSubmenus(parent);
        int i = rowsAt(parent).indexOf(opener);
        keyDepth = parent;
        if (i >= 0) {
            mark(parent, i);
        }
    }

    /** How long a closed menu takes to fade out before it is hidden: the window's presence motion, near enough. */
    static final long FADE_MILLIS = 160L;

    /** What hides the faded panel once its fade is done; replaced by an opening, which keeps it. Guarded by this. */
    private TimerTask closing;

    /**
     * Close the menu. It stops taking the pointer at once and fades out where it stands, then is hidden, not removed:
     * the panel keeps its identity, and its rows keep theirs until replaced.
     */
    public void hide() {
        if (!shown) {
            return;
        }
        shown = false;
        synchronized (this) {
            settle();
            closeSubmenus(0);
            menu.hitInert(true).opacity(0f);
            if (timer == null) {
                timer = new Timer("vexelray-menu", true);
            }
            TimerTask faded = new TimerTask() {
                @Override
                public void run() {
                    synchronized (ContextMenu.this) {
                        if (closing == this) {
                            closing = null;
                            menu.visible(false);
                        }
                    }
                }
            };
            closing = faded;
            timer.schedule(faded, FADE_MILLIS);
        }
        gui.releaseClaim(menu, ESCAPE);
        for (Shortcut key : WALK) {
            gui.releaseClaim(menu, key);
        }
        hidden();
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
            float dpi = Math.max(0.0001f, Seams.of(gui).dpi().value());
            level.panel.floatAt(Length.dp((l.rect().x() + l.rect().w()) / dpi + PAD_DP),
                    Length.dp(l.rect().y() / dpi - PAD_DP));
        }
        level.panel.visible(true);
        row.background(Role.RAISED).border(Length.dp(1), Role.EDGE.alpha(0.5f));   // the way to what is open stays lit
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
                level.opener.background(Role.NONE).border(Length.dp(1), Role.NONE);
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
            rowIcons.remove(row);
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
            Node row = item.separator() ? own(rule()) : item.heading() ? own(heading(item)) : row(item, iconColumn, at);
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
    /**
     * What {@code item} says at its right: its command's first trigger as the tree's {@link Commands} has it now, so a
     * rebound chord is what the menu shows; or, naming no command, the chord it states; or nothing. A command that is
     * named and has no trigger now says nothing, rather than a chord the keyboard no longer has.
     */
    private String chord(MenuItem item) {
        if (item.command() != null) {
            List<Trigger> now = Seams.of(gui).commands().triggers(item.command());
            return now.isEmpty() ? "" : now.getFirst().text();
        }
        return item.shortcut() == null ? "" : item.shortcut().text();
    }

    private Node row(MenuItem item, boolean iconColumn, int at) {
        Node row = own(gui.row()
                .role("menuitem")
                .width(Length.FILL)
                .corner(Length.rem(0.5f))
                .padding(Length.dp(6), Length.dp(10))
                .gap(Length.rem(0.625f))
                .alignItems(dev.vexelray.gui.core.layout.LayoutEnums.AlignItems.CENTER)
                .border(Length.dp(1), Role.NONE)
                .scroll(false, false));
        IconView mark = null;
        if (iconColumn) {
            // A cell of fixed width whether or not this item fills it: the column is what aligns the labels, so an
            // item with no mark still takes its share of it instead of sliding its label into the gap. Dimmer than
            // the label by a step — the mark is what the line is about, the label is what it says. An icon from the
            // set wins over a glyph; either sits centred in the same cell.
            Role tone = item.enabled() ? Role.DIM : Role.FAINT;
            Node cell = own(gui.row()
                    .width(ICON_CELL)
                    .height(ICON_CELL)
                    .justify(dev.vexelray.gui.core.layout.LayoutEnums.Justify.CENTER)
                    .alignItems(dev.vexelray.gui.core.layout.LayoutEnums.AlignItems.CENTER)
                    .scroll(false, false));
            if (item.mark() != null) {
                mark = IconView.of(gui, item.mark()).size(ICON).tint(tone);
                cell.append(own(mark.node()));
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
        String chord = chord(item);
        if (!chord.isEmpty()) {
            // The chord that does the same, dim at the line's end: said here so a person learns it from the menu, and
            // bound where every chord is, not by this line.
            row.append(own(gui.text(chord)
                    .textSize(Length.rem(0.9f))
                    .wordWrap(false)
                    .textColor(item.enabled() ? Role.DIM : Role.FAINT)
                    .align(TextLayout.HAlign.RIGHT, TextLayout.VAlign.MIDDLE)));
        }
        if (item.submenu()) {
            // The sign that this line goes further rather than doing something: a chevron at its end, where the
            // submenu will stand.
            row.append(own(IconView.of(gui, Icon.CHEVRON_RIGHT).size(Length.rem(0.8f))
                    .tint(item.enabled() ? Role.DIM : Role.FAINT).node()));
        }
        IconView icon = mark;
        if (icon != null) {
            rowIcons.put(row, icon);
        }
        paint(row, icon, item, InteractionState.NORMAL);
        if (!item.enabled()) {
            return row;   // no handlers at all: nothing to hover, nothing to choose
        }
        if (item.submenu()) {
            // Opened by pointing at it, as a desktop's submenus are, once the pointer rests there (see hovered), and
            // at once by a click, for a hand that does not hover (a touch, a script). It stays lit while what it
            // opened is open.
            Seams.of(gui).onState(row, state -> {
                if (state != InteractionState.NORMAL) {
                    pointed(at, row);
                    paint(row, icon, item, state);
                    hovered(() -> openSubmenu(at, row, item));
                } else if (!opens(row)) {
                    paint(row, icon, item, state);
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
                if (state != InteractionState.NORMAL) {
                    pointed(at, row);
                }
                paint(row, icon, item, state);
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

    /**
     * How a command line looks in {@code state}, as a choice in Rasterware 0.1's settings did. Bare at rest, so a menu
     * reads as a list of words. Under the pointer the raised surface fills it, an edge closes round it and its icon
     * lifts off its own shadow; pressed, the fill deepens. A {@link MenuItem#current() current} line is edged in the
     * accent at every state, its icon glowing in it. Colour only, inside a box that was always the line's, so nothing
     * moves or grows under the pointer; the window's motion fades between the states.
     */
    private static void paint(Node row, IconView icon, MenuItem item, InteractionState state) {
        boolean lit = state != InteractionState.NORMAL;
        if (item.current()) {
            row.background(Role.ACCENT.alpha(lit ? 0.24f : 0.16f)).border(Length.dp(1), Role.ACCENT);
        } else {
            row.background(lit ? Paint.of(Role.RAISED, state) : Role.NONE)
                    .border(Length.dp(1), lit ? Role.EDGE.alpha(0.5f) : Role.NONE);
        }
        if (icon != null && item.enabled()) {
            icon.tint(item.current() ? Role.ACCENT : lit ? Role.INK : Role.DIM)
                    .glow(item.current() ? 0.2f : 0f)
                    .shadow(!item.current() && lit ? 0.08f : 0f);
        }
    }

    /** A group's heading: small capitals in the secondary ink, standing a little off the line above. */
    private Node heading(MenuItem item) {
        return gui.text(item.label().toUpperCase(java.util.Locale.ROOT))
                .role("heading")
                .width(Length.FILL)
                .textSize(Length.rem(0.75f))
                .textColor(Role.DIM)
                .wordWrap(false)
                .padding(Length.dp(6), Length.dp(11))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
    }

    /** A thin horizontal rule between groups. */
    private Node rule() {
        return gui.box().width(Length.FILL).height(Length.dp(1)).background(Role.LINE)
                .margin(Length.dp(5)).scroll(false, false);
    }

    /**
     * Click-away: every click on every node is published here, so "the user clicked somewhere else" is an
     * observation, not a capture. Left button only — the right click that would re-anchor this menu publishes
     * too, and must not race the show it triggers.
     */
    private void onAnyClick(ClickEvent e) {
        // Nor the left click that opened it, which a button whose click is a menu (a title-bar instrument's) publishes
        // too, and which may arrive here after the show.
        if (shown && e.button() == MouseButton.LEFT && !ownIds.contains(e.nodeId()) && !opened(e)) {
            hide();
        }
    }

    /**
     * Whether {@code e} is the click this menu was opened at. By node, button and count rather than by place, because a
     * menu hung from its button (the title bar's lockup) is anchored somewhere other than where the click landed.
     */
    private boolean opened(ClickEvent e) {
        ClickEvent o = opener;
        return o != null && o.nodeId() == e.nodeId() && o.button() == e.button() && o.clicks() == e.clicks();
    }
}
