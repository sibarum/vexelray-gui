package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClickEvent;
import dev.vexelray.gui.core.input.FocusEvent;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.KeyEvent;
import dev.vexelray.gui.core.input.MenuSink;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.text.TextLayout;
import sibarum.atchung.Subscription;
import sibarum.tactroller.api.Key;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A generic explorer for hierarchical data — a filesystem, an AST, a scene graph, anything recursive the
 * application can describe through a {@link Source}.
 *
 * <p><b>The tree nests; it does not flatten.</b> Each item is a column of two nodes: its row, and a children
 * container holding the item's subtree. Expand and collapse are one {@code visible()} flip on that container —
 * the same load-bearing choice {@link Tabs} makes, and for the same reason: registrations are keyed by node id
 * and released when a node leaves the tree, so a subtree rebuilt by remove/insert would come back drawn but
 * inert. Nesting also sidesteps the structural API on purpose: {@link Node#append} is append-only, and a
 * flattened row list would need mid-list insertion the model does not offer. A nested subtree only ever appends
 * into its own container.
 *
 * <p><b>Children are pulled, not pushed, and never on the frame loop.</b> {@link Source#children} runs on the
 * handler executor the first time an item expands — it may hit a disk or a network, and the ordered input stages
 * are for bounded model work only. Until the fetch lands the item shows as expanded with nothing under it;
 * materialised subtrees are kept (hidden) across collapse, so re-expanding is two prop flips and no I/O.
 *
 * <p><b>One tab stop, whole-tree keyboard model.</b> The tree is a single focusable node; rows are pointer
 * targets but never focus targets, so Tab crosses the tree in one step instead of one per row. While the tree
 * holds focus, Up/Down walk the visible rows, Right expands (then enters), Left collapses (then exits to the
 * parent), Home/End jump, PageUp/PageDown move by what the viewport holds, and Enter activates. All of it runs
 * as ordered stages on the GUI thread against a widget-side flattened list of visible rows — pure model work,
 * recomputed only when the set of visible rows actually changes.
 *
 * <p><b>Ctrl+F searches the hierarchy, not the rows on screen.</b> The chord opens a {@link FindBar} at the top of
 * the tree (hidden until then, and no part of the layout or the Tab order while it is); typing searches from the
 * top, Enter steps to the next match and Shift+Enter to the previous one, wrapping either way. A tree is searched
 * by walking it, and walking a lazy one means fetching it — so a forward search stops at the first match, opens
 * only the path to it, and is an action like any other, which is what lets the next keystroke overtake the one
 * still walking. Stepping <em>backwards</em> is the direction that costs more, and unavoidably: document order is
 * only known forwards, so it walks from the top remembering the last match it passed and stops at the row the
 * selection is on.
 *
 * <p><b>Every row carries a menu, and everything on it is an {@link Action}.</b> Expand and Collapse are two of
 * them — recursive, because the single-level flip is what the disclosure control and the arrow keys already are —
 * and an application's own commands are the same kind of thing, with the same icon, the same per-item
 * availability, and the same place in the queue. Which is the other half: <b>the tree runs one action at a time</b>
 * (see {@link Job}), so triggering a second one supersedes the first wherever it had got to.
 *
 * <p>Scrolling, clipping and scrollbars come from the container itself (overflow is a layout fact, not a widget
 * feature), so a tree taller than its box scrolls with no code here. Which container is the point: {@link #node()}
 * is a frame that never scrolls, holding the chrome and, under it, the {@link #scroller()} the rows live in. A
 * tree that scrolled as one box would scroll its own find bar away — chrome that leaves when the content moves is
 * not chrome. Call {@link #close()} to release the tree's subscription and registrations when removing it.
 */
public final class TreeView<T> implements AutoCloseable {

    /**
     * How the application describes its hierarchy. {@link #children} is called at most once per item, on the
     * handler executor, the first time that item expands — it may do I/O. {@link #hasChildren} must be cheap:
     * it decides whether a disclosure control is shown, and it is asked when the row is built, not when it opens
     * (a filesystem answers it from the directory bit without listing anything).
     */
    public interface Source<T> {
        /** The top-level items, in display order. */
        List<T> roots();

        /** The row text for {@code item}. */
        String label(T item);

        /** Whether {@code item} can expand at all. Cheap; asked once, when the row is built. */
        boolean hasChildren(T item);

        /** The children of {@code item}, in display order. Called once, lazily, on the handler executor. */
        List<T> children(T item);
    }

    /**
     * The tree runs one action at a time, and a job is how a running one finds out it is no longer the one: the
     * instant another action is triggered, every job handed out before it is dead, and stays dead.
     *
     * <p><b>Superseded, not interrupted.</b> The tree cannot pull a thread out of application code, so a body that
     * never asks simply runs to the end — what is guaranteed is that the question is always answerable. A body
     * that does anything worth stopping asks between its steps, which for the built-ins means between rows: the
     * expensive part of expanding a subtree is a fetch per level, so a check per level is as prompt as the tree
     * can be without pretending {@link Source#children} can be cancelled.
     */
    @FunctionalInterface
    public interface Job {
        /** Whether this action is still the tree's current one. */
        boolean live();
    }

    /**
     * What an {@link Action} does: told which item it was invoked on, and which job it is running as. Runs on the
     * handler executor, so it is free to touch a disk or a network — and to take long enough that consulting
     * {@code job} matters.
     */
    @FunctionalInterface
    public interface Command<T> {
        /** Do the work, returning early once {@code job} is no longer {@link Job#live()}. */
        void run(T item, Job job);
    }

    /**
     * One command on the row menu: a mark, a label, what it does, and — per item — whether it is offered at all
     * and whether it can be chosen.
     *
     * <p><b>Absent and greyed say different things</b>, so both are here rather than one standing in for the other.
     * {@link #enabledWhen} is for a command that belongs on this kind of row but does not apply to this one right
     * now — a greyed line teaches where the command lives. {@link #shownWhen} is for a command that has no business
     * on this row at all — Rename on a header, Extract on something that is not an archive — where a permanently
     * greyed line would be furniture that never lights up.
     *
     * <p>Built and tuned before the tree is shown; the predicates are asked at the moment of each right click, on
     * a worker thread, and may read whatever application state they like.
     */
    public static final class Action<T> {

        private final String icon;
        private final String label;
        private final Command<T> body;
        private volatile Predicate<T> shown = item -> true;
        private volatile Predicate<T> enabled = item -> true;

        private Action(String icon, String label, Command<T> body) {
            this.icon = icon;
            this.label = label;
            this.body = body == null ? (item, job) -> { } : body;
        }

        /** A command marked with {@code icon} (a glyph, or null for none), offered on every row by default. */
        public static <T> Action<T> of(String icon, String label, Command<T> body) {
            return new Action<>(icon, label, body);
        }

        /** Offer this action only for items {@code test} accepts; for the rest it is not on the menu at all. */
        public Action<T> shownWhen(Predicate<T> test) {
            this.shown = test == null ? item -> true : test;
            return this;
        }

        /** Show this action greyed for items {@code test} rejects — it belongs here, it just does not apply. */
        public Action<T> enabledWhen(Predicate<T> test) {
            this.enabled = test == null ? item -> true : test;
            return this;
        }

        /** The glyph drawn beside the label, or null. */
        public String icon() {
            return icon;
        }

        /** The text shown. */
        public String label() {
            return label;
        }
    }

    /** Indent per depth level, in em, so the stagger scales with the text it indents. */
    private static final float INDENT_EM = 1.2f;

    /** Row height in em — enough for the label plus breathing room, uniform so paging arithmetic is exact. */
    private static final float ROW_EM = 1.75f;

    // The atlas carries no triangle glyphs, so the disclosure affordance is the classic +/− pair. A leaf shows
    // a space: the control column keeps its width, so labels at one depth align whether or not they can open.
    private static final String GLYPH_COLLAPSED = "+";
    private static final String GLYPH_EXPANDED = "−";   // MINUS SIGN, full-width unlike hyphen
    private static final String GLYPH_LEAF = " ";

    /** One item's presence in the tree: its row, its (possibly unmaterialised) children container, its state. */
    private final class Row {
        final T item;
        final Row parent;
        final int depth;
        final boolean canExpand;
        final Node entry;        // column: [rowNode, kidsBox]
        final Node rowNode;      // the pointer target and the styled strip
        final Node disclosure;   // the +/− glyph
        final Node label;
        final Node kidsBox;      // hidden until expanded; children entries append here, never anywhere else
        boolean expanded;
        boolean materialized;    // whether Source.children has been asked
        final List<Row> children = new ArrayList<>();

        /**
         * How far this row's subtree is open, 0 to 1 — the whole of the animation state, and a fraction rather
         * than a flag so that a subtree caught mid-open can be reversed from wherever it got to instead of
         * snapping. At rest it is exactly 0 or 1 and {@link #kidsBox} carries no height of its own.
         */
        float openness;
        float animFrom;
        float animTo;
        /** Bumped per animation, so a ramp still running for a superseded toggle finds nothing to write. */
        long animGen;

        Row(T item, Row parent) {
            this.item = item;
            this.parent = parent;
            this.depth = parent == null ? 0 : parent.depth + 1;
            this.canExpand = source.hasChildren(item);

            Node spacer = gui.box().width(Length.em(depth * INDENT_EM)).scroll(false, false);
            this.disclosure = gui.text(canExpand ? GLYPH_COLLAPSED : GLYPH_LEAF)
                    .width(Length.em(1.2f))
                    .textSize(Length.rem(1))
                    .textColor(gui.theme().color(Role.DIM))
                    .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE);
            // grow(1), not auto: the label takes the row's remaining width, so the whole strip past the glyph
            // belongs to the name — and a name longer than the row wraps at the row edge instead of widening it.
            this.label = gui.text(source.label(item))
                    .width(Length.grow(1))
                    .textSize(Length.rem(1))
                    .textColor(gui.theme().color(Role.INK))
                    .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
            this.rowNode = gui.row()
                    .width(Length.FILL)
                    .height(Length.em(ROW_EM))
                    .corner(Length.rem(0.4f))
                    .padding(Length.ZERO, Length.dp(4))
                    .gap(Length.em(0.25f))
                    .scroll(false, false)
                    .children(spacer, disclosure, label);
            this.kidsBox = gui.column().width(Length.FILL).visible(false).scroll(false, false);
            this.entry = gui.column().width(Length.FILL).scroll(false, false).children(rowNode, kidsBox);

            // A row click selects; a click on the disclosure itself also toggles. Both arrive on the handler
            // executor and both funnel into synchronized transitions, so a click and a keystroke can interleave
            // but never tear the state.
            gui.onClick(rowNode, () -> select(this, true));
            // A context click selects first — the convention every explorer follows — and that is true whether or
            // not anything ends up on the menu, so it stays a click handler rather than a side effect of building
            // one. It is also what makes the menu's commands legible: they act on the row that just lit up.
            gui.onContextClick(rowNode, e -> select(this, true));
            // Two sources, and the order is the menu's shape: what the tree knows how to do to any row, then what
            // the application knows about this one. Both go on through the same door — the app's items are wrapped
            // so that choosing one supersedes whatever action was running, exactly as choosing a built-in does.
            gui.onContextMenu(rowNode, menu -> rowMenu(item, menu));
            gui.onContextMenu(rowNode, menu -> contextMenu.accept(item, new Preempting(menu)));
            if (canExpand) {
                gui.onClick(disclosure, () -> {
                    select(this, true);
                    toggle(this);
                });
            }
            gui.onState(rowNode, state -> restyle(this, state));
        }
    }

    private final Gui gui;
    private final Source<T> source;
    private final Node root;

    /**
     * The rows, and the thing that scrolls. Separate from {@link #root} so the find bar can be chrome rather than
     * content: a bar inside the scroller is a bar that scrolls away, which is not a bar.
     */
    private final Node rows;
    private final Subscription focusSub;

    /** All state below is guarded by {@code this}. The GUI-thread stages and the handler executor both mutate
     *  it; the monitor makes their transitions atomic, and every callback leaves the monitor before dispatch. */
    /** How expand and collapse are timed, or null for the instant flip. See {@link #motion}. */
    private volatile Ramp motion;

    private final List<Row> rootRows = new ArrayList<>();
    private final Map<T, Row> rowsByItem = new HashMap<>();
    private final List<Row> visible = new ArrayList<>();
    private Row selected;

    private volatile boolean focused;
    private volatile Consumer<T> onSelect = t -> { };
    private volatile Consumer<T> onActivate = t -> { };
    private volatile BiConsumer<T, MenuSink> contextMenu = (item, menu) -> { };

    /**
     * Which action is the current one. Every {@link Job} is a captured value of this counter compared back against
     * it, so starting an action is one increment and "am I still it?" is one read — no registry of running work,
     * nothing to unregister, and no way for a job to outlive its own answer.
     */
    private final AtomicLong actions = new AtomicLong();

    /** The two the tree ships. Public handles, because their availability is the application's to retune. */
    private final Action<T> expandAction;
    private final Action<T> collapseAction;

    /** What the application added, in the order it added it. Read on every right click, written at build time. */
    private final List<Action<T>> extraActions = new CopyOnWriteArrayList<>();

    /** The find bar: hidden until Ctrl+F, and no part of the layout or the Tab order until it is up. */
    private final FindBar find;

    /** How a row answers a query. Replaceable: only the application knows what its items are searchable by. */
    private volatile BiPredicate<T, String> matcher;

    /** Build a tree over {@code source}; roots are listed immediately (on the calling thread), collapsed. */
    public TreeView(Gui gui, Source<T> source) {
        this.gui = gui;
        this.source = source;
        ContextMenu.presentOn(gui);   // rows carry whatever menu the application declares; this shows it
        // The marks are the disclosure control's own +/−, and deliberately so: the menu item and the glyph on the
        // row are two ways to reach the same state, and a user who has learnt one has learnt the other. Enabled by
        // what the row is — a leaf has nothing to expand, a shut row has nothing to collapse — which is a default
        // the application can replace, and a reason it is stated as a predicate rather than baked into the walk.
        this.expandAction = Action.<T>of(GLYPH_COLLAPSED, "Expand", this::expandDeep).enabledWhen(this::canOpen);
        this.collapseAction = Action.<T>of(GLYPH_EXPANDED, "Collapse", this::collapseDeep).enabledWhen(this::isOpen);
        // The frame and the scroller are two boxes, not one. The frame carries the tree's own look and holds the
        // chrome; the rows go inside a scroller that fills what is left of it. One box could not do both: the bar
        // would be content, and content scrolls.
        this.root = gui.column()
                .width(Length.FILL)
                .height(Length.FILL)
                .background(gui.theme().color(Role.WELL))
                .corner(Length.rem(0.5f))
                .border(Length.rem(0.1f), gui.theme().color(Role.LINE))
                .padding(Length.dp(4))
                .scroll(false, false);
        this.rows = gui.column().width(Length.FILL).height(Length.FILL);

        // Navigation is an ordered stage: moving a cursor is order-dependent under key repeat, and it is pure
        // lookups on the visible list — exactly what the GUI-thread lane is for. Registering it also makes the
        // tree focusable, which is the single tab stop.
        gui.onKeyUi(this.root, this::onKey);
        this.focusSub = gui.bus().subscribe(gui.focusEvents(), this::onFocus);

        // The find bar, built shut, above the scroller rather than inside it: it takes its strip from the rows'
        // box, so opening it moves the rows down and scrolling them moves nothing of it. Ctrl+F opens it seeded
        // with nothing — a tree has no text to have selected, so the chord can only mean "start".
        this.matcher = (item, query) -> source.label(item).toLowerCase().contains(query.toLowerCase());
        this.find = new FindBar(gui, new Finder()).openOn(this.root, () -> "");
        root.children(find.node(), rows);

        synchronized (this) {
            for (T item : source.roots()) {
                Row r = new Row(item, null);
                rootRows.add(r);
                rowsByItem.put(item, r);
                rows.append(r.entry);
            }
            refreshVisible();
        }
    }

    /** The node to place in a layout (size it there — the tree fills whatever box it is given). */
    public Node node() {
        return root;
    }

    /**
     * The box the rows scroll in — inside {@link #node()}, below whatever chrome is up.
     *
     * <p>Public because it is what an application asking a scrolling question about a tree has to ask: the outer
     * node is a frame that never scrolls, so its {@code scrollY} is always zero and its viewport includes the
     * find bar's strip. Reading it is the only thing to do with it; the tree does the scrolling itself.
     */
    public Node scroller() {
        return rows;
    }

    /**
     * The row strip drawn for {@code item} — <b>to point at, not to restructure</b>: read its layout, play a
     * {@link Cue} on it. Do not add children to it or write the properties the tree paints, which it rewrites
     * whenever the selection or the pointer moves.
     *
     * <p><b>Null is the ordinary answer, not the exceptional one</b>, and this is the whole care a caller owes
     * it: a tree materialises rows as folders open, so an item under a collapsed parent — or one that has been
     * scrolled past, or that no longer exists — has no row and never had one. A caller marking a row it has just
     * asked the tree to {@link #select} is in the good case and still has to check, because selecting an item
     * the source no longer offers leaves nothing to mark.
     *
     * <p><b>Why an application asks.</b> The tree can be told to select something without anything visibly
     * happening — a {@code reveal} of a file in a folder already open, on a row already selected, is a correct
     * no-op that is indistinguishable from a command that failed. Marking the row is what tells those apart, and
     * only the tree knows which node it is.
     */
    public synchronized Node rowNode(T item) {
        Row r = rowsByItem.get(item);
        return r == null ? null : r.rowNode;
    }

    /** The find bar's strip — package-private, so a test can read whether the user can see it. */
    Node findBar() {
        return find.node();
    }

    /** The find bar's status line — package-private, so a test can read what it says about the last search. */
    Node findStatus() {
        return find.statusNode();
    }

    /** The selected item, or null when nothing is selected yet. */
    public synchronized T selected() {
        return selected == null ? null : selected.item;
    }

    /** React to the selection landing on an item (click or keyboard). Runs on the handler executor. */
    public TreeView<T> onSelect(Consumer<T> handler) {
        this.onSelect = handler == null ? t -> { } : handler;
        return this;
    }

    /** React to an item being activated (Enter). Runs on the handler executor. */
    public TreeView<T> onActivate(Consumer<T> handler) {
        this.onActivate = handler == null ? t -> { } : handler;
        return this;
    }

    /**
     * Give every row a context menu. The row is selected first — the menu that opens is about the row under the
     * pointer, so that row must visibly become the subject — and then this is asked what should be on it, with the
     * item in hand. That is the context a tree can add that the framework cannot: which <em>thing</em> was clicked.
     *
     * <p>Runs on a worker thread at the moment of the click. Contributing nothing still leaves the tree's own
     * items on the menu; what this adds lands after them.
     *
     * <p>This is the free-form door — anything a {@link MenuSink} can express, decided at click time. An
     * application whose commands <em>are</em> commands on the item, with a mark and an availability, wants
     * {@link #action} instead: the difference is that an {@code Action} is a peer of Expand and Collapse and the
     * tree runs it, where a source contributes lines and runs them itself. Either way the action is one of the
     * tree's: choosing anything from a row's menu supersedes whatever was running.
     */
    public TreeView<T> onContextMenu(BiConsumer<T, MenuSink> source) {
        this.contextMenu = source == null ? (item, menu) -> { } : source;
        return this;
    }

    /**
     * Add a command to every row's menu, after Expand and Collapse and behind a rule. Added actions appear in the
     * order they were added, each shown, greyed or dropped per item by its own predicates.
     *
     * {@snippet :
     * tree.action(TreeView.Action.<Path>of("×", "Delete", (path, job) -> delete(path))
     *         .enabledWhen(Files::isWritable)
     *         .shownWhen(path -> !path.equals(root)));
     * }
     */
    public TreeView<T> action(Action<T> action) {
        if (action != null) {
            extraActions.add(action);
        }
        return this;
    }

    /**
     * The built-in recursive Expand, so an application can retune it: {@code shownWhen}/{@code enabledWhen} to
     * change when it is offered, or {@code shownWhen(item -> false)} to take it off the menu entirely. Enabled by
     * default for any row that can open at all.
     */
    public Action<T> expandAction() {
        return expandAction;
    }

    /** The built-in recursive Collapse — the twin of {@link #expandAction}, enabled for any row that is open. */
    public Action<T> collapseAction() {
        return collapseAction;
    }

    /**
     * Expand {@code item} and everything under it, exactly as the menu's Expand does — including becoming this
     * tree's current action, so it supersedes whatever was running and is itself superseded by the next one.
     *
     * <p>The walk runs on the handler executor because every level of it may fetch children, and it opens each
     * level as it lands rather than at the end: a deep tree over a slow source unfolds, which is both the honest
     * report of what is happening and what makes stopping it half way meaningful.
     */
    public TreeView<T> expandAll(T item) {
        Job job = begin();
        gui.handlers().execute(() -> expandDeep(item, job));
        return this;
    }

    /** Collapse {@code item} and everything under it — the twin of {@link #expandAll}, and never any I/O. */
    public TreeView<T> collapseAll(T item) {
        Job job = begin();
        gui.handlers().execute(() -> collapseDeep(item, job));
        return this;
    }

    /**
     * Expand {@code item} programmatically. Works top-down: an item whose ancestors have never been expanded has
     * no row yet (its parent's children were never asked for), and then this is a no-op — expand the path from
     * the root downward. The first expansion fetches children asynchronously, like any other.
     */
    public synchronized TreeView<T> expand(T item) {
        Row r = rowsByItem.get(item);
        if (r != null && r.canExpand && !r.expanded) {
            toggleLocked(r);
        }
        return this;
    }

    /** Collapse {@code item} if it is expanded. The subtree is hidden, not discarded. */
    public synchronized TreeView<T> collapse(T item) {
        Row r = rowsByItem.get(item);
        if (r != null && r.expanded) {
            toggleLocked(r);
        }
        return this;
    }

    /** Select {@code item} if it has a row and every ancestor is expanded; otherwise a no-op. */
    public TreeView<T> select(T item) {
        Row r;
        synchronized (this) {
            r = rowsByItem.get(item);
            if (r == null || !isVisible(r)) {
                return this;
            }
        }
        select(r, true);
        return this;
    }

    /** Move keyboard focus to the tree, so the arrow keys drive it. */
    public TreeView<T> focus() {
        gui.focus(root);
        return this;
    }

    /** Release the tree's subscription and registrations. Call when removing it from the layout. */
    @Override
    public void close() {
        focusSub.close();
        find.close();   // takes the bar's own claims with it; the root's Ctrl+F goes with the root below
        synchronized (this) {
            for (Row r : rowsByItem.values()) {
                gui.releaseNode(r.rowNode);
                gui.releaseNode(r.disclosure);
            }
        }
        gui.releaseNode(root);
    }

    // --- keyboard, as an ordered stage on the GUI thread ---

    private void onKey(KeyEvent e) {
        switch (e.key()) {
            case UP -> moveSelection(-1);
            case DOWN -> moveSelection(1);
            case PAGE_UP -> moveSelection(-pageRows());
            case PAGE_DOWN -> moveSelection(pageRows());
            case HOME -> selectAt(0);
            case END -> selectAt(Integer.MAX_VALUE);
            case RIGHT -> expandOrEnter();
            case LEFT -> collapseOrExit();
            case ENTER -> activate();
            default -> { }
        }
    }

    private void moveSelection(int by) {
        Row target;
        synchronized (this) {
            if (visible.isEmpty()) {
                return;
            }
            int at = selected == null ? -1 : visible.indexOf(selected);
            int next = at < 0 ? (by > 0 ? 0 : visible.size() - 1)
                    : Math.max(0, Math.min(visible.size() - 1, at + by));
            target = visible.get(next);
        }
        select(target, true);
    }

    private void selectAt(int index) {
        Row target;
        synchronized (this) {
            if (visible.isEmpty()) {
                return;
            }
            target = visible.get(Math.max(0, Math.min(visible.size() - 1, index)));
        }
        select(target, true);
    }

    /** Right: open a closed item; step into an open one; do nothing on a leaf. */
    private void expandOrEnter() {
        Row down = null;
        synchronized (this) {
            Row s = selected;
            if (s == null || !s.canExpand) {
                return;
            }
            if (!s.expanded) {
                toggleLocked(s);
                return;
            }
            if (!s.children.isEmpty()) {
                down = s.children.get(0);
            }
        }
        if (down != null) {
            select(down, true);
        }
    }

    /** Left: close an open item; step out to the parent of anything else. */
    private void collapseOrExit() {
        Row up = null;
        synchronized (this) {
            Row s = selected;
            if (s == null) {
                return;
            }
            if (s.expanded) {
                toggleLocked(s);
                return;
            }
            up = s.parent;
        }
        if (up != null) {
            select(up, true);
        }
    }

    private void activate() {
        T item;
        synchronized (this) {
            if (selected == null) {
                return;
            }
            item = selected.item;
        }
        Consumer<T> handler = onActivate;
        gui.handlers().execute(() -> handler.accept(item));
    }

    /**
     * Rows the viewport holds, for PageUp/PageDown — rows are uniform height, so this is one division.
     *
     * <p>The scroller's viewport, not the frame's: a page is what one press can bring into view, and while the
     * find bar is up its strip is part of the frame and not part of that.
     */
    private int pageRows() {
        float viewH = rows.layout().viewH();
        float rowH;
        synchronized (this) {
            rowH = visible.isEmpty() ? 0f : visible.get(0).rowNode.layout().rect().h();
        }
        return rowH > 0f && viewH > 0f ? Math.max(1, (int) Math.floor(viewH / rowH)) : 1;
    }

    // --- find: Ctrl+F, and the search that is an action like any other ---

    /**
     * How a row answers a query. The default is a case-insensitive substring of the row's label, which is what a
     * user typing into a find bar means by "search" — and wrong for exactly the applications that know better: a
     * filesystem tree searched by full path, a symbol tree searched by kind, an inventory searched by SKU.
     *
     * <p>Asked on the handler executor, once per row the walk reaches, so it may read application state — and
     * should be cheap, because the walk's other half is already paying for I/O.
     */
    public TreeView<T> matcher(BiPredicate<T, String> test) {
        this.matcher = test == null
                ? (item, query) -> source.label(item).toLowerCase().contains(query.toLowerCase())
                : test;
        return this;
    }

    /**
     * What the find bar asks of the tree. Typing is a fresh search from the top — which is what makes it
     * incremental, and what makes typing "src" three searches of which the first two are superseded mid-walk —
     * and Enter is the same query asked again from the row the last answer left the selection on.
     */
    private final class Finder implements FindBar.Search {

        @Override
        public void first(String query) {
            startFind(query, null, true);
        }

        @Override
        public void next(String query) {
            startFind(query, selected(), true);
        }

        @Override
        public void previous(String query) {
            startFind(query, selected(), false);
        }

        /** Shut: stop whatever the bar started, and give the keyboard back to the tree. */
        @Override
        public void closed() {
            begin();   // the search is an action, and closing the bar is the user done with it
            gui.focus(root);
        }
    }

    /**
     * Search for {@code query} from {@code from} (or from the top when it is null) and stop at the first row that
     * answers: reveal it, select it, and leave the rest of the tree alone.
     *
     * <p><b>Stopping at the first match is what makes this affordable.</b> A tree searches by walking, and walking
     * a lazy hierarchy means fetching it — so a search that collected every match would fetch the whole tree
     * before it could say anything, on every keystroke. Stopping at the first bounds the work by the distance to
     * an answer, and Enter pays for the next one only when it is asked for.
     *
     * <p>A miss is the expensive case: nothing matched means the walk reached the end, which for a filesystem is
     * a real walk. That is exactly why it is an action — the next keystroke supersedes it, Escape supersedes it,
     * and so does anything else the user does to the tree.
     */
    private void startFind(String query, T from, boolean forward) {
        Job job = begin();
        if (query == null || query.isEmpty()) {
            find.status("");
            // Nothing to search for, but the bar asking has just taken a strip out of the rows' box — so the row
            // the user was on comes back into what is left of it, rather than being scrolled off by the chrome.
            revealSelected();
            return;
        }
        find.status("Searching…");
        Row found = forward ? findFrom(query, from, job) : findBack(query, from, job);
        if (!job.live()) {
            return;   // superseded mid-walk: the search that replaced this one owns the status line now
        }
        find.status(found == null ? "No match" : "");
        if (found != null) {
            reveal(found);
            select(found, true);
        }
    }

    /**
     * Walk the whole hierarchy in document order looking for {@code query}, fetching levels as it reaches them and
     * wrapping once past the end when it started somewhere other than the top.
     *
     * <p>It expands nothing on the way. Materialising a row and opening it are two different things — the first is
     * "what is under here", the second is "show it" — and a search that opened every row it passed would leave the
     * tree unfolded behind it. Only the path to the answer is opened, by {@link #reveal}.
     */
    private Row findFrom(String query, T from, Job job) {
        BiPredicate<T, String> test = matcher;
        Deque<Row> pending = new ArrayDeque<>();
        pushRoots(pending);
        boolean past = from == null;
        boolean wrapped = past;
        while (job.live()) {
            Row row = pending.poll();
            if (row == null) {
                if (wrapped) {
                    return null;
                }
                // Round again from the top, now matching everything: the anchor was never reached from where we
                // started, or there was nothing after it. Once only — the second pass ends the search either way.
                wrapped = true;
                past = true;
                pushRoots(pending);
                continue;
            }
            if (!past) {
                past = row.item.equals(from);   // the anchor itself is the one row a "next" must not answer with
            } else if (test.test(row.item, query)) {
                return row;
            }
            if (claimFetch(row)) {
                materialize(row);   // outside the monitor: the search pays the same I/O an expansion would
            }
            synchronized (this) {
                for (int i = row.children.size() - 1; i >= 0; i--) {
                    pending.push(row.children.get(i));
                }
            }
        }
        return null;
    }

    /**
     * The last match before {@code from}, wrapping round to the last match of all — Shift+Enter.
     *
     * <p><b>Backwards is the expensive direction, and it is expensive for a reason worth stating.</b> Document
     * order is a property of the forward walk: which row comes before a given row is not known until everything up
     * to it has been visited. So this one walks from the top, remembering the last match it passed, and stops the
     * moment it reaches the anchor with an answer in hand — the distance to where the user already is, rather than
     * to a match. Only the wrap case is a full walk, and only because "the last match of all" says the whole tree.
     * It is an action like every other, so the next keystroke overtakes it where it stands.
     */
    private Row findBack(String query, T from, Job job) {
        BiPredicate<T, String> test = matcher;
        Deque<Row> pending = new ArrayDeque<>();
        pushRoots(pending);
        Row before = null;   // the last match strictly before the anchor
        Row last = null;     // and the last match anywhere, which is what the wrap lands on
        boolean past = from == null;
        while (job.live()) {
            Row row = pending.poll();
            if (row == null) {
                return before != null ? before : last;   // nothing behind the anchor, so round to the end
            }
            boolean anchor = !past && row.item.equals(from);
            past |= anchor;
            if (test.test(row.item, query)) {
                last = row;      // a wrap lands on the last match anywhere — including the anchor itself, which is
                if (!past) {     // the right answer when it is the only match there is
                    before = row;
                }
            }
            if (anchor && before != null) {
                return before;   // nothing further on can be nearer to the anchor from behind
            }
            if (claimFetch(row)) {
                materialize(row);   // the same I/O an expansion would pay, for the same reason
            }
            synchronized (this) {
                for (int i = row.children.size() - 1; i >= 0; i--) {
                    pending.push(row.children.get(i));
                }
            }
        }
        return null;
    }

    private synchronized void pushRoots(Deque<Row> pending) {
        pending.clear();
        for (int i = rootRows.size() - 1; i >= 0; i--) {
            pending.push(rootRows.get(i));
        }
    }

    /** Open every ancestor of {@code row}, top down, so a match found inside shut subtrees is one the user sees. */
    private void reveal(Row row) {
        List<Row> ancestors = new ArrayList<>();
        synchronized (this) {
            for (Row p = row.parent; p != null; p = p.parent) {
                ancestors.add(p);
            }
        }
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            Row ancestor = ancestors.get(i);
            if (openLocked(ancestor)) {
                materialize(ancestor);   // it was walked through, so this is all but unreachable — and correct
            }
        }
    }

    // --- actions: the row menu, and the one-at-a-time rule ---

    /** What the tree itself puts on a row's menu: its own two commands, then whatever the application added. */
    private void rowMenu(T item, MenuSink menu) {
        offer(expandAction, item, menu);
        offer(collapseAction, item, menu);
        // Unconditional: a rule that would open the menu or double another is dropped by the sink, so this needs
        // no test for whether anything above it or below it survived its own predicates.
        menu.separator();
        for (Action<T> action : extraActions) {
            offer(action, item, menu);
        }
    }

    /** Put {@code action} on {@code menu} for {@code item} — shown or not, enabled or not, as it says. */
    private void offer(Action<T> action, T item, MenuSink menu) {
        if (!action.shown.test(item)) {
            return;
        }
        menu.item(action.icon(), action.label(), action.enabled.test(item), () -> action.body.run(item, begin()));
    }

    /**
     * Claim the tree for a new action and hand back its job. One increment: everything running is now superseded,
     * and this is what the next {@link Job#live()} will be measured against.
     */
    private Job begin() {
        long generation = actions.incrementAndGet();
        return () -> actions.get() == generation;
    }

    /**
     * The sink handed to a {@link #onContextMenu} source: the same menu, with each contributed action made one of
     * the tree's. A source that adds "Delete" gets the one-at-a-time rule for free, and a long expand does not
     * carry on unfolding underneath the thing the user chose instead.
     */
    private final class Preempting implements MenuSink {

        private final MenuSink delegate;

        Preempting(MenuSink delegate) {
            this.delegate = delegate;
        }

        @Override
        public ClickEvent event() {
            return delegate.event();
        }

        @Override
        public MenuSink item(String icon, String label, boolean enabled, Runnable action) {
            delegate.item(icon, label, enabled, action == null ? null : () -> {
                begin();
                action.run();
            });
            return this;
        }

        @Override
        public MenuSink separator() {
            delegate.separator();
            return this;
        }
    }

    /** Whether {@code item}'s row can open at all — Expand's default availability. */
    private synchronized boolean canOpen(T item) {
        Row row = rowsByItem.get(item);
        return row != null && row.canExpand;
    }

    /** Whether {@code item}'s row is open — Collapse's default availability: a shut row is already collapsed. */
    private synchronized boolean isOpen(T item) {
        Row row = rowsByItem.get(item);
        return row != null && row.expanded;
    }

    /**
     * Open {@code item} and its whole subtree, depth first, fetching each level as it is reached.
     *
     * <p>An explicit stack rather than recursion, and one task rather than one per row: the walk is a single piece
     * of work that happens to have levels, and cutting it into tasks would put the check that stops it — and the
     * fetch that blocks it — in the wrong place. {@code job} is asked between rows, which is between fetches.
     *
     * <p>Materialisation is claimed under the monitor and performed outside it, so a slow {@link Source#children}
     * never holds the lock the frame's stages need. A row whose fetch is already in flight from a click is left to
     * that fetch; the walk finds its children when it gets there, or not at all if it gets there first.
     */
    private void expandDeep(T item, Job job) {
        Deque<Row> pending = new ArrayDeque<>();
        synchronized (this) {
            Row row = rowsByItem.get(item);
            if (row == null) {
                return;
            }
            pending.push(row);
        }
        while (job.live()) {
            Row row = pending.poll();
            if (row == null) {
                return;
            }
            if (openLocked(row)) {
                materialize(row);   // may touch a disk: outside the monitor, on the handler executor
            }
            synchronized (this) {
                // Reversed onto the stack, so the subtree comes off it top down — the order it is drawn in, and
                // the order a source is asked to produce it in.
                for (int i = row.children.size() - 1; i >= 0; i--) {
                    pending.push(row.children.get(i));
                }
            }
        }
    }

    /**
     * Put {@code row} in the expanded state, whatever state it was in; @return whether its children still have to
     * be fetched — in which case the caller does that (and {@link #materialize} does the opening, once there is
     * something to open onto).
     */
    private synchronized boolean openLocked(Row row) {
        if (!row.canExpand) {
            return false;
        }
        boolean fetch = claimFetch(row);
        if (!row.expanded) {
            row.expanded = true;
            row.disclosure.text(GLYPH_EXPANDED);
        }
        if (!fetch) {
            open(row, 1f);
        }
        refreshVisible();
        return fetch;
    }

    /**
     * Claim {@code row}'s one fetch without opening it; @return whether the caller must now materialise it.
     *
     * <p>The two are separate because searching needs the first without the second: a walk has to know what is
     * under a row to keep going, and must leave it looking exactly as it found it.
     */
    private synchronized boolean claimFetch(Row row) {
        if (!row.canExpand || row.materialized) {
            return false;
        }
        row.materialized = true;
        return true;
    }

    /**
     * Shut {@code item} and everything under it.
     *
     * <p><b>Deepest first</b>, and that is the difference between this and hiding the top: a subtree collapsed all
     * the way down comes back <em>shut</em> when its top is opened again, which is what "collapse" means to
     * someone who then re-opens it. Shutting only the top would leave every descendant still expanded behind the
     * hidden box, and re-opening would spill the whole tree back out.
     *
     * <p>No I/O anywhere in it — nothing here can reach a row that was never materialised — so the only reason it
     * consults {@code job} is size: a large materialised subtree is still a walk, and the rule is that any action
     * gives way to the next.
     */
    private void collapseDeep(T item, Job job) {
        Row top;
        synchronized (this) {
            top = rowsByItem.get(item);
        }
        if (top == null) {
            return;
        }
        List<Row> subtree = new ArrayList<>();
        Deque<Row> pending = new ArrayDeque<>();
        pending.push(top);
        while (job.live()) {
            Row row = pending.poll();
            if (row == null) {
                break;
            }
            subtree.add(row);
            synchronized (this) {
                pending.addAll(row.children);
            }
        }
        Row landed = null;
        synchronized (this) {
            for (int i = subtree.size() - 1; i >= 0 && job.live(); i--) {
                shutLocked(subtree.get(i));
            }
            refreshVisible();
            // The selection follows the same rule the single-level collapse follows: it never rests on a row that
            // is no longer visible, so it comes up to the one the user collapsed.
            if (selected != null && isUnder(selected, top)) {
                landed = top;
            }
        }
        if (landed != null) {
            select(landed, true);
        }
    }

    /** Put {@code row} in the collapsed state if it is not already. Guarded by {@code this}. */
    private void shutLocked(Row row) {
        if (!row.expanded) {
            return;
        }
        row.expanded = false;
        row.disclosure.text(GLYPH_COLLAPSED);
        open(row, 0f);
    }

    // --- state transitions ---

    private void select(Row row, boolean notify) {
        T item;
        synchronized (this) {
            if (row == selected) {
                return;
            }
            Row previous = selected;
            selected = row;
            if (previous != null) {
                styleLocked(previous, InteractionState.NORMAL);
            }
            styleLocked(row, InteractionState.NORMAL);
            item = row.item;
        }
        // Every route to a selection ends here — an arrow key, a click, a search, the collapse that pulls the
        // selection up out of a subtree — so this is the one place that has to ask for it to be on screen. Asked
        // for the strip, not the entry: the entry is the row plus its whole subtree, and bringing *that* into
        // view would align the top of a folder's contents rather than the folder.
        row.rowNode.scrollIntoView();
        if (notify) {
            Consumer<T> handler = onSelect;
            gui.handlers().execute(() -> handler.accept(item));
        }
    }

    /** Ask for the selected row to be on screen again, without selecting anything or telling anyone. */
    private void revealSelected() {
        Row row;
        synchronized (this) {
            row = selected;
        }
        if (row != null) {
            row.rowNode.scrollIntoView();
        }
    }

    private synchronized void toggle(Row row) {
        toggleLocked(row);
    }

    /**
     * Flip {@code row} between expanded and collapsed. The visual flip is immediate; the first expansion also
     * dispatches the children fetch to the handler executor, because {@link Source#children} may do I/O and this
     * method runs on the GUI thread when the flip came from a key. Collapsing with the selection inside the
     * subtree pulls the selection up onto the collapsed row, so it never rests on a hidden node.
     *
     * <p><b>A flip is an action too</b>, and takes the tree over like any other. Clicking a disclosure glyph or
     * pressing Left while a recursive expand is still unfolding says what the user wants at least as plainly as
     * choosing a menu item does — and a walk that carried on would re-open, one level later, the very row that was
     * just shut. Moving the selection is not an action: it changes nothing about what is open.
     */
    private void toggleLocked(Row row) {
        if (!row.canExpand) {
            return;
        }
        begin();
        row.expanded = !row.expanded;
        row.disclosure.text(row.expanded ? GLYPH_EXPANDED : GLYPH_COLLAPSED);
        if (row.expanded && !row.materialized) {
            row.materialized = true;
            gui.handlers().execute(() -> materialize(row));
            refreshVisible();
            return;
        }
        open(row, row.expanded ? 1f : 0f);
        refreshVisible();
        if (!row.expanded && selected != null && isUnder(selected, row)) {
            Row landed = row;
            gui.handlers().execute(() -> select(landed, true));
        }
    }

    /**
     * Install the motion for expand and collapse: the subtree grows and shrinks over {@code ramp}, and everything
     * below it slides to make room. Passing null, or never calling this, keeps the instant flip — which is what
     * every tree here did before there was any motion, and what the reduced-motion path is.
     *
     * <p>Unlike the visual transforms, <b>this one is a layout animation</b>: the subtree's height is a real
     * height, so the flex pass runs on every frame of it. That is the mechanism the effect requires rather than a
     * shortcut — displacing the rows below with a transform would move them without making room, so the tree's
     * own extent, its overflow and its scrollbar would all describe a tree that is not the one on screen.
     */
    public synchronized TreeView<T> motion(Ramp motion) {
        this.motion = motion;
        return this;
    }

    /**
     * Take {@code row}'s subtree to {@code to} — 1 open, 0 shut — animating if a ramp is installed.
     *
     * <p>From wherever it currently is, not from the other end: toggling a subtree caught half-open reverses it
     * from there. Anything else means a fast second click has to watch the subtree jump to a state it was never
     * in before travelling back.
     */
    private void open(Row row, float to) {
        long gen = ++row.animGen;
        Ramp ramp = motion;
        if (ramp == null || row.openness == to) {
            settleOpen(row, gen, to);
            return;
        }
        row.animFrom = row.openness;
        row.animTo = to;
        // Visible for the duration whichever way it is going — a subtree cannot be watched closing if it was
        // hidden when the animation started — and clipped, because for the duration the box is shorter than what
        // is inside it. Scrolling containers clip themselves; this one does not scroll and has to say so.
        row.kidsBox.visible(true).clip(true);
        stepOpen(row, gen, 0f);
        ramp.run(p -> stepOpen(row, gen, (float) p), () -> settleOpen(row, gen, to));
    }

    /** One frame of an open/shut animation, ignored if the toggle it belongs to has been superseded. */
    private synchronized void stepOpen(Row row, long gen, float p) {
        if (gen != row.animGen) {
            return;
        }
        float t = Math.max(0f, Math.min(1f, p));
        row.openness = row.animFrom + (row.animTo - row.animFrom) * t;
        row.kidsBox.height(Length.em(row.openness * contentEm(row)));
    }

    /**
     * Put {@code row}'s subtree at rest: exactly open or exactly shut, with no height of its own.
     *
     * <p>Handing the height back to {@code AUTO} matters more than it looks. An explicit height is a promise
     * about content that has not happened yet — materialising a subtree, renaming a row, a theme change that
     * moves the row height — and a box still holding the number that was right when it stopped animating would
     * quietly clip or gap. At rest the box is what is in it.
     */
    private synchronized void settleOpen(Row row, long gen, float to) {
        if (gen != row.animGen) {
            return;
        }
        row.openness = to;
        row.kidsBox.clip(false).height(Length.AUTO).visible(to > 0f);
    }

    /**
     * The height of {@code row}'s children container, in em, as it should be <em>right now</em>.
     *
     * <p>Exact arithmetic rather than a measurement, because every row is a fixed {@code ROW_EM} tall — which is
     * what spares this the usual expand-animation dance of showing the content to find out how big it is and
     * then hiding it again, always one frame too late to be invisible.
     *
     * <p>Recursive through {@code openness} so a subtree that is itself mid-animation contributes what it is
     * currently showing, not what it would show when finished. Without that, opening a row inside a row that is
     * still opening gives the outer box a height for content it does not yet have.
     */
    private float contentEm(Row row) {
        float em = 0f;
        for (Row child : row.children) {
            em += ROW_EM + child.openness * contentEm(child);
        }
        return em;
    }

    /** Fetch and build {@code row}'s children — handler executor, so the source is free to touch a disk. */
    private void materialize(Row row) {
        List<T> kids = source.children(row.item);
        synchronized (this) {
            for (T item : kids) {
                Row r = new Row(item, row);
                row.children.add(r);
                rowsByItem.put(item, r);
                row.kidsBox.append(r.entry);
            }
            if (row.children.isEmpty()) {
                // The source promised children and delivered none (an emptied directory): demote to a leaf.
                row.expanded = false;
                row.disclosure.text(GLYPH_LEAF);
            }
            // The animation starts here rather than at the toggle: until the fetch lands there is nothing to
            // open, and a subtree animating to a height of zero content would be a flourish over an empty box.
            open(row, row.expanded ? 1f : 0f);
            refreshVisible();
        }
    }

    /** Recompute the flattened visible-row list — the thing the keyboard walks. Guarded by {@code this}. */
    private void refreshVisible() {
        visible.clear();
        for (Row r : rootRows) {
            appendVisible(r);
        }
    }

    private void appendVisible(Row row) {
        visible.add(row);
        if (row.expanded) {
            for (Row child : row.children) {
                appendVisible(child);
            }
        }
    }

    private boolean isVisible(Row row) {
        for (Row p = row.parent; p != null; p = p.parent) {
            if (!p.expanded) {
                return false;
            }
        }
        return true;
    }

    private boolean isUnder(Row row, Row ancestor) {
        for (Row p = row.parent; p != null; p = p.parent) {
            if (p == ancestor) {
                return true;
            }
        }
        return false;
    }

    // --- styling ---

    private void restyle(Row row, InteractionState state) {
        synchronized (this) {
            styleLocked(row, state);
        }
    }

    private void styleLocked(Row row, InteractionState state) {
        Theme theme = gui.theme();
        if (row == selected) {
            row.rowNode.background(theme.color(Role.SELECTION));
            row.label.textColor(theme.color(Role.ACCENT));
        } else {
            // Transparent at rest, so an unselected row is the well it sits in rather than a rectangle of its own.
            row.rowNode.background(state == InteractionState.NORMAL ? null : theme.color(Role.PANEL));
            row.label.textColor(theme.color(Role.INK));
        }
    }

    private void onFocus(FocusEvent e) {
        if (e.nodeId() != root.id()) {
            return;
        }
        focused = e.gained();
        root.border(Length.rem(0.1f), gui.theme().color(focused ? Role.ACCENT : Role.LINE));
    }
}
