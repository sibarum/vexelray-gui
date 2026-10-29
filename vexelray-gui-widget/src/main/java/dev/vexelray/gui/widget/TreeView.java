package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Timing;
import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.core.Addressed;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.IconSource;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.drop.Drop;
import dev.vexelray.gui.core.drop.DragState;
import dev.vexelray.gui.core.drop.DragWatch;
import dev.vexelray.gui.core.drop.DropEffect;
import dev.vexelray.gui.core.drop.Payload;
import dev.vexelray.gui.core.drop.PayloadType;
import dev.vexelray.gui.core.input.ClickEvent;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums;
import dev.vexelray.gui.core.drop.Transfer;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.KeyEvent;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.input.MenuItem;
import dev.vexelray.gui.core.input.MenuSink;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.style.Finish;
import dev.vexelray.gui.core.style.Paint;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.scroll.Scrollbar;
import dev.vexelray.gui.scroll.Scrollbars;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.icons.IconView;
import dev.vexelray.text.TextLayout;
import sibarum.atchung.Subscription;
import sibarum.atchung.Topic;
import sibarum.probe.Lane;
import sibarum.probe.Probe;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A generic explorer for hierarchical data — a filesystem, an AST, a scene graph, anything recursive the
 * application can describe a level at a time ({@link Children}).
 *
 * <p><b>The tree nests; it does not flatten.</b> Each item is a column of two nodes: its row, and a children
 * container holding the item's subtree. Expand and collapse are one {@code visible()} flip on that container —
 * the same load-bearing choice {@link Tabs} makes, and for the same reason: registrations are keyed by node id
 * and released when a node leaves the tree, so a subtree rebuilt by remove/insert would come back drawn but
 * inert. Nesting also sidesteps the structural API on purpose: {@link Node#append} is append-only, and a
 * flattened row list would need mid-list insertion the model does not offer. A nested subtree only ever appends
 * into its own container.
 *
 * <p><b>Children are asked for by message, and answered by message.</b> The first time an item expands the tree
 * publishes {@link ChildrenWanted} on {@link #CHILDREN_WANTED}, and builds the level when {@link Children} arrives on
 * {@link #CHILDREN}, addressed to the tree's {@link #name()}. Listing a level may hit a disk or a network, and the
 * frame is for bounded work only, so the tree never lists anything itself: the application answers, on whatever
 * thread its logic runs on, and the reply is applied in a frame like any other message. What the tree draws a row
 * from — its label, its icon, whether it opens — comes in the reply as data ({@link Entry}), so nothing of the
 * application's runs in the frame to draw one (vexelray-framework's {@code docs/threading.md}, T7.2 and T7.5). Until
 * the reply lands the item shows as expanded with nothing under it; materialised subtrees are kept (hidden) across
 * collapse, so re-expanding is two prop flips and no request.
 *
 * <p><b>One tab stop, whole-tree keyboard model.</b> The tree is a single focusable node; rows are pointer
 * targets but never focus targets, so Tab crosses the tree in one step instead of one per row. While the tree
 * holds focus, Up/Down walk the visible rows, Right expands (then enters), Left collapses (then exits to the
 * parent), Home/End jump, PageUp/PageDown move by what the viewport holds, and Enter activates. All of it runs
 * as ordered stages on the GUI thread against a widget-side flattened list of visible rows — pure model work,
 * recomputed only when the set of visible rows actually changes.
 *
 * <p><b>Ctrl+F searches the hierarchy, not the rows on screen</b> — in a tree that is {@link #searchable}. The
 * chord opens a {@link FindBar} at the top of the tree (hidden until then, and no part of the layout or the Tab
 * order while it is); typing searches from the
 * top, Enter steps to the next match and Shift+Enter to the previous one, wrapping either way. A tree is searched
 * by walking it, and walking a lazy one means fetching it — so a forward search stops at the first match, opens
 * only the path to it, and is an action like any other, which is what lets the next keystroke overtake the one
 * still walking. Stepping <em>backwards</em> is the direction that costs more, and unavoidably: document order is
 * only known forwards, so it walks from the top remembering the last match it passed and stops at the row the
 * selection is on.
 *
 * <p><b>Every row carries a menu.</b> The tree's own lines are Expand and Collapse — recursive, because the
 * single-level flip is what the disclosure control and the arrow keys already are — and Cut, Copy and Paste where
 * it moves things. An application's commands are {@link MenuAction}s: declared once, offered per row as each row's
 * {@link Entry} says, and announced on {@link #CHOSEN} when chosen, so a right click runs nothing of the
 * application's. <b>The tree runs one walk at a time</b> — expand-all, find, reveal — and starting another, or
 * choosing any line, stops the one running wherever it had got to.
 *
 * <p>Scrolling, clipping and scrollbars come from the container itself (overflow is a layout fact, not a widget
 * feature), so a tree taller than its box scrolls with no code here. Which container is the point: {@link #node()}
 * is a frame that never scrolls, holding the chrome and, under it, the {@link #scroller()} the rows live in. A
 * tree that scrolled as one box would scroll its own find bar away — chrome that leaves when the content moves is
 * not chrome. Call {@link #close()} to release the tree's subscription and registrations when removing it.
 */
public final class TreeView<T> implements AutoCloseable {

    // ------------------------------------------------------------------------------------------- on the bus

    /*
     * The message model (vexelray-framework docs/threading.md, T3.8 and T7.5): a tree asks for each level it needs
     * and is told it, by message. A tree is addressed by its name, given when it is built, because the roots are asked
     * for then and whoever answers has to know which tree is asking.
     */

    /** A tree wants a level listed: every request, in order. Answer each with one {@link Children}. */
    public static final Topic<ChildrenWanted> CHILDREN_WANTED =
            Topic.edge(ChildrenWanted.class);

    /** A level, listed for the tree that asked: applied on the GUI thread, in the frame it arrives. */
    public static final Topic<Children> CHILDREN = Addressed.edge(Children.class);

    /** The selection landing on an item, by a click or the keyboard. The last in a frame. */
    public static final Topic<Selected> SELECTED =
            Addressed.state(Selected.class, Selected::tree);

    /**
     * A row clicked: after it is selected, and whether or not it already was. Not the disclosure, which opens and
     * closes. {@link #SELECTED} cannot stand in for this: an arrow key lands the selection too, so a tree where a click
     * <em>does</em> something (makes a folder the root, say) would do it at every step of a walk with the keyboard.
     * Here the click is the command and the keys only move; Enter is {@link #ACTIVATED}. Every one, in order.
     */
    public static final Topic<Clicked> CLICKED = Addressed.edge(Clicked.class);

    /** The selected item activated, by Enter. Every one, in order. */
    public static final Topic<Activated> ACTIVATED = Addressed.edge(Activated.class);

    /**
     * The row the pointer is on, or none when it leaves the rows altogether: for a tree whose rows are previewed before
     * they are chosen. The last in a frame, so moving from one row to the next is heard as the next.
     */
    public static final Topic<Hovered> HOVERED = Addressed.state(Hovered.class, Hovered::tree);

    /**
     * A row's box clicked, or Space pressed on the selected row, in a {@link #checkable} tree: after the row is
     * selected. The tree changes no box for it; what is ticked is the application's, and it says so with
     * {@link #MARKS}. Every one, in order.
     */
    public static final Topic<Toggled> TOGGLED = Addressed.edge(Toggled.class);

    /** What is ticked, for the tree that is told: applied on the GUI thread, in the frame it arrives. Every one. */
    public static final Topic<Marks> MARKS = Addressed.edge(Marks.class);

    /**
     * An item put somewhere in a {@link #reorderable} tree, by a drop or a paste. The tree moves no row for it: the
     * hierarchy is the application's, and it says when it has changed with {@link #REFRESH}. Every one, in order.
     */
    public static final Topic<Placed> PLACED = Addressed.edge(Placed.class);

    /**
     * The user put {@code item} at {@code where} in the tree at {@code at}, meaning {@code effect}: what
     * {@link #PLACED} carries, addressed to the tree. An announcement, not a request the tree waits on: the
     * arrangement is the application's, so it makes the move in its own model and then says so: it tells the tree to
     * look again ({@link #REFRESH}). A type of the tree's own, so it names one channel (vexelray-framework's
     * threading.md, T4.1).
     *
     * @param at     the tree's address
     * @param item   what was placed
     * @param where  where, relative to an item already there
     * @param effect {@code MOVE} for a drag or a cut, {@code COPY} for a copy
     */
    public record Placed(String at, Object item, Placement<?> where, dev.vexelray.gui.core.drop.DropEffect effect)
            implements dev.vexelray.gui.core.Addressed {
        public Placed {
            java.util.Objects.requireNonNull(at, "at");
            java.util.Objects.requireNonNull(item, "item");
            java.util.Objects.requireNonNull(where, "where");
            java.util.Objects.requireNonNull(effect, "effect");
        }

        @Override
        public Object address() {
            return at;
        }
    }

    /**
     * One of the application's {@link MenuAction}s chosen from a row's menu. The tree does nothing else for it: the work
     * is the application's, done where its logic runs. Every one, in order.
     */
    public static final Topic<Chosen> CHOSEN = Addressed.edge(Chosen.class);

    /**
     * The user chose the {@link MenuAction} {@code action} from the menu of {@code item} in the tree at {@code at}:
     * what {@link #CHOSEN} carries, addressed to the tree. A type of the tree's own, so it names one channel
     * (vexelray-framework's threading.md, T4.1).
     *
     * @param at     the tree's address
     * @param action the action's id
     * @param item   the row's item
     */
    public record Chosen(String at, String action, Object item) implements dev.vexelray.gui.core.Addressed {
        public Chosen {
            java.util.Objects.requireNonNull(at, "at");
            java.util.Objects.requireNonNull(action, "action");
            java.util.Objects.requireNonNull(item, "item");
        }

        @Override
        public Object address() {
            return at;
        }
    }

    /**
     * A {@link #revealPath} walk ended: at its last item, selected, or short of it where the tree no longer offered the
     * way down. Not for a walk superseded by another. Every one, in order.
     */
    public static final Topic<Revealed> REVEALED = Addressed.edge(Revealed.class);

    /** The id of the tree's own Expand: everything under the row, opened. For an {@link Entry} to {@link Entry#hides hide}. */
    public static final String EXPAND = "expand";

    /** The id of the tree's own Collapse: everything under the row, shut. */
    public static final String COLLAPSE = "collapse";

    /** The id of the tree's own Cut, on the menu where the tree moves things. */
    public static final String CUT = "cut";

    /** The id of the tree's own Copy, on the menu where the tree copies things. */
    public static final String COPY = "copy";

    /** The id of the tree's own Paste, on the menu where the tree takes anything at all. */
    public static final String PASTE = "paste";


    /**
     * The hierarchy has changed: the tree that is told asks again for every level it has listed, as
     * {@link #refresh()} does. The last in a frame, since a second in the same frame would ask the same questions.
     */
    public static final Topic<Refresh> REFRESH = Addressed.state(Refresh.class, Refresh::tree);

    /** The tree at {@code tree} has {@code item} selected. */
    public record Selected(String tree, Object item) implements Addressed {
        @Override
        public Object address() {
            return tree;
        }
    }

    /** {@code item}'s row in the tree at {@code tree} was clicked. */
    public record Clicked(String tree, Object item) implements Addressed {
        @Override
        public Object address() {
            return tree;
        }
    }

    /** {@code item} in the tree at {@code tree} was activated. */
    public record Activated(String tree, Object item) implements Addressed {
        @Override
        public Object address() {
            return tree;
        }
    }

    /** The pointer is on {@code item}'s row in the tree at {@code tree}, or on none of its rows when null. */
    public record Hovered(String tree, Object item) implements Addressed {
        @Override
        public Object address() {
            return tree;
        }
    }

    /** The tree at {@code tree} should look at its hierarchy again: something under it has changed. */
    public record Refresh(String tree) implements Addressed {
        @Override
        public Object address() {
            return tree;
        }
    }

    /** A reveal in the tree at {@code tree} toward {@code item} ended, at it ({@code reached}) or short of it. */
    public record Revealed(String tree, Object item, boolean reached) implements Addressed {
        @Override
        public Object address() {
            return tree;
        }
    }

    /** {@code item}'s box in the tree at {@code tree} was clicked, or Space pressed on its row. */
    public record Toggled(String tree, Object item) implements Addressed {
        @Override
        public Object address() {
            return tree;
        }
    }

    /**
     * What is ticked in {@code tree}, for the items named: the rest keep what they were last told, and an item never
     * named is {@link Check#OFF}. A change that touches other rows (a cascade, a parent turning {@link Check#MIXED})
     * names them in the same message, so the tree shows the whole of it in one frame. An item with no row yet is kept
     * for when its row is built, so a level and its marks may arrive in either order.
     */
    public record Marks(String tree, Map<Object, Check> marks) implements Addressed {
        public Marks {
            marks = Map.copyOf(marks);
        }

        /** Routed by the tree's name, as {@link Children} is. */
        @Override
        public Object address() {
            return tree;
        }
    }

    /**
     * {@code tree} wants what is under {@code parent} — its roots when {@code parent} is null. Asked once per item, the
     * first time it is opened or a walk (expand-all, find, a reveal) has to go through it, and again for every level
     * already listed when the tree is {@link #refresh refreshed}.
     */
    public record ChildrenWanted(String tree, Object parent) {
    }

    /**
     * What is under {@code parent} in {@code tree} (its roots when {@code parent} is null), in display order. The
     * answer to a {@link ChildrenWanted}; one that answers nothing the tree asked is dropped, and counted.
     */
    public record Children(String tree, Object parent, List<Entry> entries) implements Addressed {
        public Children {
            entries = List.copyOf(entries);
        }

        /** Routed by the tree's name: see {@link Gui#mailbox(Topic, Object, sibarum.atchung.Subscriber)}. */
        @Override
        public Object address() {
            return tree;
        }
    }

    /**
     * One item as the application describes it: what it is called, its icon, and whether it opens. Data rather than
     * questions the tree asks as it draws, so drawing a row runs nothing of the application's.
     *
     * <p>{@code branch} decides whether a disclosure is shown, so it is known without listing anything: a filesystem
     * answers it from the directory bit.
     *
     * <p>{@code acceptsChildren} is whether the item could <em>hold</em> children — which is not the same question as
     * whether it has any, and is the one a drop has to ask. They were the same question until an empty branch turned
     * up. A folder with nothing in it is a leaf by {@code branch}, so its row divided in two and offered nowhere to
     * drop <em>into</em> — and since taking the last child out of a branch is what empties it, the tree let the user
     * reach a state it gave them no way to leave. Say true where a branch is a branch because of what it <em>is</em>
     * rather than what happens to be in it: a directory, a column on a board, a group. It is not a promise that any
     * drop succeeds: the application still refuses what only its model knows when it hears {@link #PLACED}. This decides whether
     * the band is offered at all; that decides what happens in it.
     *
     * <p><b>What the row's menu offers is part of the entry too</b> ({@code hidden}, {@code greyed}), for the same
     * reason: whether Delete applies to this file is a fact about the file, known to whoever lists it, and asking it
     * at the right click would run the application's code in the frame (vexelray-framework's threading.md, T7.5).
     * Every {@link MenuAction} is shown and enabled unless the entry says otherwise. When the facts change, the
     * application sends {@link #REFRESH}, as it does for a new label. The ids are the actions' own and the tree's
     * {@link #EXPAND}, {@link #COLLAPSE}, {@link #CUT}, {@link #COPY} and {@link #PASTE}; hiding one of the tree's own
     * takes it off this row's menu, and when it is enabled stays the tree's.
     *
     * @param icon   drawn before the label — a folder, a file's type, a class — or null for none. A tree none of whose
     *               entries names an icon is laid out exactly as it was before icons existed.
     * @param hidden the actions not on this row's menu at all: Rename on a header, Extract on what is not an archive
     * @param greyed the actions on this row's menu that cannot be chosen now: a greyed line teaches where it lives
     * @param find   what Ctrl+F looks for the query in, ignoring case: the label unless the entry says otherwise, such
     *               as a whole path, or a SKU beside the name
     */
    public record Entry(Object item, String label, IconSource icon, boolean branch, boolean acceptsChildren,
                        java.util.Set<String> hidden, java.util.Set<String> greyed, String find) {
        public Entry {
            java.util.Objects.requireNonNull(item, "item");
            label = label == null ? String.valueOf(item) : label;
            hidden = hidden == null ? java.util.Set.of() : java.util.Set.copyOf(hidden);
            greyed = greyed == null ? java.util.Set.of() : java.util.Set.copyOf(greyed);
            find = find == null ? label : find;
        }

        /** An entry whose menu offers everything, found by its label. */
        public Entry(Object item, String label, IconSource icon, boolean branch, boolean acceptsChildren) {
            this(item, label, icon, branch, acceptsChildren, null, null, null);
        }

        /** An item that does not open, and takes no drop into it. */
        public static Entry leaf(Object item, String label) {
            return new Entry(item, label, null, false, false);
        }

        /** An item that opens, and takes a drop into it. */
        public static Entry branch(Object item, String label) {
            return new Entry(item, label, null, true, true);
        }

        /** This entry, drawn with {@code icon}. */
        public Entry icon(IconSource icon) {
            return new Entry(item, label, icon, branch, acceptsChildren, hidden, greyed, find);
        }

        /** This entry, taking a drop into it or not: a leaf that holds things, or a branch that refuses them. */
        public Entry acceptsChildren(boolean accepts) {
            return new Entry(item, label, icon, branch, accepts, hidden, greyed, find);
        }

        /** This entry, with the actions {@code ids} off its menu as well as any it already hides. */
        public Entry hides(String... ids) {
            return new Entry(item, label, icon, branch, acceptsChildren, union(hidden, ids), greyed, find);
        }

        /** This entry, with the actions {@code ids} greyed on its menu as well as any it already greys. */
        public Entry greys(String... ids) {
            return new Entry(item, label, icon, branch, acceptsChildren, hidden, union(greyed, ids), find);
        }

        /** This entry, found by Ctrl+F through {@code text} rather than its label. */
        public Entry find(String text) {
            return new Entry(item, label, icon, branch, acceptsChildren, hidden, greyed, text);
        }

        private static java.util.Set<String> union(java.util.Set<String> had, String... ids) {
            java.util.Set<String> all = new java.util.HashSet<>(had);
            all.addAll(List.of(ids));
            return all;
        }
    }

    /**
     * What a checkbox on a row can say. Three states, because two cannot describe a folder half of whose
     * contents are ticked — and a parent that shows unticked while something under it is ticked is a lie the
     * user finds out about later.
     */
    public enum Check {

        /** Not ticked. */
        OFF,

        /** Ticked. */
        ON,

        /** Some of what is under it is ticked and some is not. Drawn as a bar rather than a tick. */
        MIXED;

        /** What a click on a box in this state usually means — {@code MIXED} resolving to ON. */
        public Check toggled() {
            return this == ON ? OFF : ON;
        }
    }

    /**
     * The tree runs one walk at a time — expand-all, collapse-all, find, reveal — and a job is how a running one finds
     * out it is no longer the one: the instant another starts, or a line is chosen from a row's menu, every job handed
     * out before it is dead, and stays dead.
     *
     * <p><b>Superseded, not interrupted.</b> A walk asks between rows and whenever a level it waited for lands, so a
     * superseded walk asks for nothing more; a request already sent is answered, and the answer still builds its
     * level, which is never wrong to have. The tree's own: an application's work, started by {@link #CHOSEN}, runs
     * where its logic does, and stops itself when it hears the next.
     */
    @FunctionalInterface
    private interface Job {
        /** Whether this walk is still the tree's current one. */
        boolean live();
    }

    /**
     * One of the tree's own lines: library code, which the tree runs itself, with when it is offered decided from the
     * tree's own state. The application can only take one off a row, by its id on the {@link Entry}.
     */
    private record Line<T>(String id, Icon mark, String label, BiConsumer<T, Job> body,
                           Predicate<T> shown, Predicate<T> enabled) {
    }

    /** Indent per depth level, in em, so the stagger scales with the text it indents. */
    private static final float INDENT_EM = 1.2f;

    /** Row height in em — enough for the label plus breathing room, uniform so paging arithmetic is exact. */
    private static final float ROW_EM = 1.75f;

    /** Fraction of a row's height at each end that means "between rows" rather than "into this one". */
    private static final float DROP_EDGE = 0.25f;

    /** How thick the between-rows seam indicator is, px. */
    private static final float SEAM_PX = 2f;

    // The disclosure is a chevron that turns a quarter as its row opens, following the subtree's own openness so the
    // two move together. A leaf shows nothing in the same space: the control column keeps its width, so labels at
    // one depth align whether or not they can open.
    private static final Icon DISCLOSURE = Icon.CHEVRON_RIGHT;

    /** The row's inset from the tree's edge at each end, which on the left is part of the disclosure's target. */
    private static final Length ROW_INSET = Length.dp(4);

    /** The gap between a row's parts, in em. The indent carries one more, where it used to sit beside the chevron. */
    private static final float GAP_EM = 0.25f;

    private static final Shortcut CUT_CHORD = Shortcut.of(Key.X, Modifier.CONTROL);
    private static final Shortcut COPY_CHORD = Shortcut.of(Key.C, Modifier.CONTROL);
    private static final Shortcut PASTE_CHORD = Shortcut.of(Key.V, Modifier.CONTROL);

    /** The carried label's fill where releasing would place it, and where it would not: barely see-through. */
    private static final Paint CARRY_ACCEPTS = Role.ACTION.alpha(0.92f);
    private static final Paint CARRY_REFUSES = Role.DANGER.alpha(0.92f);

    /** One item's presence in the tree: its row, its (possibly unmaterialised) children container, its state. */
    private final class Row {
        final T item;
        /** Not final: a row can be dragged into a different branch, and a reconcile reseats it there. */
        Row parent;
        int depth;
        /** Not final either: a leaf that gains children gains a disclosure control, without being rebuilt. */
        boolean canExpand;
        final Node spacer;       // the indent, which is depth and therefore changes when the row is reseated
        final Node entry;        // column: [rowNode, kidsBox]
        final Node rowNode;      // the pointer target and the styled strip
        final Node disclosure;   // the row's start, edge to chevron: the click target that opens and shuts
        final IconView chevron;  // the chevron, turned by the subtree's openness
        final Node checkBox;     // the tick box, out of the layout until the tree is checkable
        final IconView checkMark; // what is drawn inside it: a tick for ON, a bar for MIXED
        final IconView icon;     // the source's icon for the item, out of the layout while it names none
        final Node label;        // the name, one line at its own width
        final Node labelBox;     // the rest of the row, clipping the name at the row's edge
        final Node kidsBox;     // hidden until expanded; children entries append here, never anywhere else
        boolean expanded;
        boolean materialized;    // whether its children have been asked for
        boolean listed;          // whether they have arrived, so the rows under it are the ones there are
        /** What the application last said the item is called, and whether it takes a drop into it. */
        String name;
        boolean acceptsChildren;
        /** What its menu leaves off and greys, and what Ctrl+F looks in: the entry's, as last told. */
        java.util.Set<String> hidden;
        java.util.Set<String> greyed;
        String key;
        /** The walks waiting for this row's children to arrive, resumed when they do. */
        final List<Walk> waiters = new ArrayList<>(0);
        /** The pointer's state on the row as a whole, and whether it is on the disclosure. Guarded by the tree. */
        InteractionState pointer = InteractionState.NORMAL;
        boolean onDisclosure;
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

        Row(T item, Entry entry, Row parent) {
            this.item = item;
            this.parent = parent;
            this.depth = parent == null ? 0 : parent.depth + 1;
            this.canExpand = entry.branch();
            this.name = entry.label();
            this.acceptsChildren = entry.acceptsChildren();
            this.hidden = entry.hidden();
            this.greyed = entry.greyed();
            this.key = entry.find();

            this.spacer = gui.box().width(indent(depth)).scroll(false, false);
            // Turned, never moved: hit-inert, so a click on the column reaches the column whatever angle it is at.
            this.chevron = IconView.of(gui, canExpand ? DISCLOSURE : null).size(Length.rem(0.8f)).tint(Role.DIM);
            chevron.node().hitInert(true);
            Node chevronBox = gui.row()
                    .width(Length.rem(1.2f))
                    .height(Length.rem(1.2f))
                    .justify(LayoutEnums.Justify.CENTER)
                    .alignItems(LayoutEnums.AlignItems.CENTER)
                    .scroll(false, false)
                    .hitInert(true)
                    .children(chevron.node());
            // The disclosure is everything from the row's edge to the chevron, the full height of the row: the indent
            // too, because it is empty and a click a pixel short of a chevron should not do something else entirely.
            // It lights on its own while the pointer is on it, and the row does not, so where one click ends and the
            // other begins is drawn before either is made.
            this.disclosure = gui.row()
                    .height(Length.rem(ROW_EM))
                    .corner(Length.rem(0.4f))
                    .alignItems(LayoutEnums.AlignItems.CENTER)
                    .scroll(false, false)
                    .children(gui.box().width(ROW_INSET).hitInert(true), spacer, chevronBox);
            spacer.hitInert(true);
            // The checkbox: a box, with the set's tick or bar drawn in it. The mark is a field, so it is exact at
            // every zoom and greys or recolours by its tint; the box is still a box, centred in the row rather than
            // on a baseline.
            //
            // Built on every row whether or not anything is checkable, and hidden until something is. A hidden
            // node is out of the layout entirely, so an unchecked tree pays a node per row and no width — and
            // the alternative, adding the box when checkable() arrives, means rebuilding every row that already
            // exists to get one.
            this.checkMark = IconView.of(gui, null).size(Length.rem(0.8f)).tint(Role.ACCENT);
            this.icon = IconView.of(gui, entry.icon()).size(Length.rem(1f));
            icon.node().visible(icon.icon() != null);
            this.checkBox = gui.row()
                    .width(Length.rem(0.95f)).height(Length.rem(0.95f))
                    .corner(Length.rem(0.2f))
                    .border(Length.rem(0.1f), Role.EDGE)
                    .justify(LayoutEnums.Justify.CENTER)
                    .alignItems(LayoutEnums.AlignItems.CENTER)
                    .scroll(false, false)
                    // Shown from the start on a tree that is already checkable, so a branch opened later does
                    // not appear a box narrower than the rows above it and then jump; and marked from the start,
                    // from what the application last said, so a ticked item's row is never drawn empty.
                    .visible(checkable)
                    .children(checkMark.node());
            paintCheck(checks.getOrDefault(item, Check.OFF));
            // Guarded when it runs rather than when it is built, for the same reason the disclosure is: a tree
            // becomes checkable after its first rows already exist.
            Seams.of(gui).onClick(checkBox, () -> {
                if (!checkable) {
                    return;
                }
                // Selecting first is the convention every explorer follows, and it is what makes Space
                // afterwards act on the row the pointer just ticked.
                select(this, true);
                gui.bus().publish(TOGGLED, new Toggled(address, item));
            });
            Seams.of(gui).onState(checkBox, state -> checkBox.border(Length.rem(0.1f),
                    state == InteractionState.NORMAL ? Role.EDGE : Role.ACCENT));

            // One line, cut off at the row's edge. The row is a fixed height — that is what keeps paging exact —
            // and a fixed-height row cannot hold a wrapped label: the second line spills out of the strip and
            // draws over the row below. So the name is never wrapped and never widens the row either.
            //
            // Two nodes, because neither can do it alone. A label wraps at its own width unless told otherwise,
            // so the label is left at AUTO width — its one-line width, which the
            // flex never shrinks — and a grow(1) box takes the row's remaining width and clips it there. clip()
            // masks a node's children, not its own glyphs, which is the other reason the box is a box. The same
            // shape as a Table cell. There is no ellipsis anywhere in the text stack; the unclip below is how a
            // cut name is read in full.
            this.label = gui.text(name)
                    .textSize(Length.rem(1))
                    .textColor(Role.INK)
                    .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
            this.labelBox = gui.row()
                    .width(Length.grow(1))
                    .alignItems(LayoutEnums.AlignItems.CENTER)
                    .scroll(false, false)   // a cut name is truncated, not scrolled: no bar across the row
                    .clip(true)
                    .children(label);
            this.rowNode = gui.row()
                    .role("treeitem")
                    .width(Length.FILL)
                    .height(Length.rem(ROW_EM))
                    .corner(Length.rem(0.4f))
                    .gap(Length.rem(GAP_EM))
                    // Centred rather than stretched: the disclosure and the label are text and centre
                    // themselves inside whatever height they are given, but a checkbox is a box, and a box
                    // stretched to the row height is not a checkbox.
                    .alignItems(LayoutEnums.AlignItems.CENTER)
                    .scroll(false, false)
                    // No padding: the inset at the left is the disclosure's own, so the target reaches the edge,
                    // and the one at the right is a box of its own to match.
                    .children(disclosure, checkBox, icon.node(), labelBox, gui.box().width(ROW_INSET));
            if (rowCursor != null) {
                gui.cursor(rowNode, rowCursor);
            }
            // Its rows are this tree's to move: it grows the box frame by frame as a branch opens, and a window-wide
            // motion chasing each step would leave the rows below trailing the opening (Node.childMotion).
            this.kidsBox = gui.column().width(Length.FILL).visible(false).scroll(false, false).childMotion(false);
            this.entry = gui.column().width(Length.FILL).scroll(false, false).children(rowNode, kidsBox);
            // How a collapsed branch un-hides something inside it, for navigation (Gui.reveals). Only a branch
            // that has been open once has rows in here at all — a branch never expanded has no nodes under it,
            // so there is nothing there for a landmark to name and nothing for this to reveal. Expanding walks
            // outward-in, so an ancestor's own reveal has already run by the time this one is asked.
            Seams.of(gui).reveals(kidsBox, descendant -> {
                if (expanded) {
                    return false;
                }
                expand(item);
                return true;
            });

            // A row click selects; a click on the disclosure itself also toggles. Both arrive on the handler
            // executor and both funnel into synchronized transitions, so a click and a keystroke can interleave
            // but never tear the state.
            Seams.of(gui).onClick(rowNode, () -> {
                select(this, true);
                gui.bus().publish(CLICKED, new Clicked(address, item));
            });
            // A context click selects first — the convention every explorer follows — and that is true whether or
            // not anything ends up on the menu, so it stays a click handler rather than a side effect of building
            // one. It is also what makes the menu's commands legible: they act on the row that just lit up.
            Seams.of(gui).onContextClick(rowNode, e -> select(this, true));
            // The menu is the tree's own lines, then the application's actions, each offered as this row's entry
            // last said: built here from what the tree already holds, with nothing of the application's run.
            Seams.of(gui).onContextMenu(rowNode, menu -> rowMenu(this, menu));
            // Registered whatever the row is today, and guarded when it runs rather than when it is built: a
            // leaf can gain children (a reconcile after a drop moved one into it), and a handler that was never
            // installed cannot be installed later without rebuilding the row and losing everything else on it.
            // A leaf's disclosure is only indent, and a click there is a click on the row, as it was before the
            // indent was part of the disclosure.
            Seams.of(gui).onClick(disclosure, () -> {
                select(this, true);
                if (canExpand) {
                    toggle(this);
                } else {
                    gui.bus().publish(CLICKED, new Clicked(address, item));
                }
            });
            Seams.of(gui).onState(rowNode, state -> {
                restyle(this, state);
                hovered(this, state != InteractionState.NORMAL);
            });
            Seams.of(gui).onState(disclosure, state -> disclosureHovered(this, state != InteractionState.NORMAL));
            // The full name, in place, for a row that cuts it off — and nothing for one that does not. Unclip asks
            // at hover time rather than being kept up to date, so a row costs one state observer until someone
            // rests on it: whether a name is cut depends on the depth, the tree's width and the zoom.
            unclip.attach(rowNode, label);
        }

        /**
         * Put this row under {@code newParent}, at whatever depth that is.
         *
         * <p>The indent is the only thing depth is: a row three levels in is a row with a wider spacer. No
         * recursion, because a reconcile walks the whole materialised subtree and reseats every descendant from
         * its own parent — and a subtree that is <em>not</em> materialised has no rows to reseat.
         */
        void reseat(Row newParent) {
            this.parent = newParent;
            int now = newParent == null ? 0 : newParent.depth + 1;
            if (now != depth) {
                depth = now;
                spacer.width(indent(depth));
            }
        }

        /**
         * Draw {@code state} in this row's box.
         *
         * <p>MIXED is a bar rather than a lighter tick, because the two must not be told apart by weight: a
         * checkbox is read at a glance. A different <em>shape</em> survives being looked at quickly, and survives a
         * user who has never seen the control before.
         */
        void paintCheck(Check state) {
            switch (state) {
                case OFF -> checkMark.icon(null);
                case ON -> checkMark.icon(Icon.CHECK);
                case MIXED -> checkMark.icon(Icon.CHECK_MIXED);
            }
        }

        /** The disclosure as the row now is: a chevron if it can open, nothing in the same space if not. */
        void showDisclosure() {
            chevron.icon(canExpand ? DISCLOSURE : null);
        }

        /** The chevron turned as far as the subtree is open: a quarter turn, pointing down, when it is all the way. */
        void turnDisclosure() {
            chevron.node().rotate(90f * openness);
        }

        /**
         * Bring the row's own appearance back in line with what the application now says it is: its label, its
         * icon, and whether it can be opened.
         *
         * <p>A row that stops being able to hold children is shut on the way, because an open row with no
         * disclosure control is one the user cannot close. One that starts being able to is asked for its children
         * afresh the next time it opens, whatever it was told before.
         */
        void resync(Entry entry) {
            name = entry.label();
            label.text(name);
            icon.icon(entry.icon());
            icon.node().visible(entry.icon() != null);
            acceptsChildren = entry.acceptsChildren();
            hidden = entry.hidden();
            greyed = entry.greyed();
            key = entry.find();
            boolean now = entry.branch();
            if (now == canExpand) {
                return;
            }
            canExpand = now;
            expanded = false;
            materialized = false;
            listed = false;
            children.clear();   // their rows are not reached by the reconcile this runs in, so it sweeps them
            open(this, 0f);
            showDisclosure();
        }
    }

    private final Gui gui;
    /** What this tree is called in its GUI ("files"). */
    private final String name;
    /** Where it is on the bus, the GUI's name and {@link #name}: what every message to or from it carries. */
    private final String address;
    /** The tree's mailboxes for {@link #CHILDREN}, {@link #MARKS} and {@link #REFRESH}, routed by {@link #address}. */
    private final Subscription childrenSub;
    private final Subscription marksSub;
    private final Subscription refreshSub;
    private final Node root;

    /**
     * The rows, and the thing that scrolls. Separate from {@link #root} so the find bar can be chrome rather than
     * content: a bar inside the scroller is a bar that scrolls away, which is not a bar.
     */
    /** The Scroller's node: the box the rows are seen through. See {@link #scroller()}. */
    private final Node rows;
    /** What the Scroller moves: the top-level entries, each holding its subtree. */
    private final Node entries;
    /** The Scroller and its bar. */
    private final Scrollbars scrolling;

    /** All state below is guarded by {@code this}. It is the GUI thread's in 0.2, and the monitor is what 0.1 needed
     *  when a handler pool shared it; taking it out is a refactor owed with the widget library's T7.2 walk. */
    /** How expand and collapse are timed, or null for the instant flip. See {@link #motion}. */
    private volatile Ramp motion;

    private final List<Row> rootRows = new ArrayList<>();
    /** Whether the roots have arrived; until they have, a walk waits for them as it waits for any level. */
    private boolean rootsListed;
    /** Whether the roots have been asked for: in the tree's first frame, by the {@link #REFRESH} it sent itself. */
    private boolean rootsAsked;
    private final List<Walk> rootWaiters = new ArrayList<>(0);

    /**
     * The refresh in flight: the levels asked for again, keyed by their row ({@link #ROOTS} for the roots), and the
     * replies that have landed. Null when none is. Applied whole once the last level lands, never level by level: an
     * item moved from one branch to another is in one listing and missing from the other, and a level applied alone
     * would drop the row that the next one reuses — closing every folder under it.
     */
    private Map<Object, List<Entry>> refreshing;
    private java.util.Set<Object> refreshAwaits;
    /** Whether {@link #refresh} was called again while one was in flight, so another follows it. */
    private boolean refreshAgain;

    /** The key the roots' level is held under in {@link #refreshing}, where every other level is its row. */
    private static final Object ROOTS = new Object();

    private final Map<T, Row> rowsByItem = new HashMap<>();
    private final List<Row> visible = new ArrayList<>();
    private Row selected;
    /** The effects a placement may have, or null until reorderable() is called. */
    private volatile java.util.Set<DropEffect> effects;
    /** Whether checkable() has been called, which is what shows every box. */
    private volatile boolean checkable;
    /** What the application last said is ticked, by item, whether or not the item has a row. Guarded by this. */
    private final Map<Object, Check> checks = new HashMap<>();
    /** The form this tree offers its rows as -- per instance, so two trees do not silently accept each other's. */
    private final PayloadType<T> itemType = PayloadType.of("tree-item");
    /** How the drop target is shown; replaceable, defaulted to the theme accent at construction. */
    private volatile DropIndicator dropIndicator;
    /** Whether an overlay is currently painted, so clearing it is done once rather than every frame. */
    private volatile boolean painting;
    /** The drag subscription, opened by reorderable() and closed with the tree. */
    private Subscription dragSub;
    /** The label that rides with the pointer while a row is carried; built at the first carry. Guarded by this. */
    private Node carried;
    /** What the carried label last showed, and where: written only when it changes. Guarded by this. */
    private Object carriedAs;





    private volatile Role hoverFill = Role.PANEL;
    private volatile Role hoverInk = Role.INK;
    private volatile CursorShape rowCursor;
    /** The row the pointer is on, or null. Guarded by {@code this}. */
    private Row hoveredRow;

    /**
     * Which walk is the current one. Every {@link Job} is a captured value of this counter compared back against
     * it, so starting one is one increment and "am I still it?" is one read — no registry of running work,
     * nothing to unregister, and no way for a job to outlive its own answer.
     */
    private final AtomicLong actions = new AtomicLong();

    /** The tree's own lines, in menu order, with a rule after Collapse. */
    private final List<Line<T>> lines;

    /** What the application declared, in the order it declared it. */
    private final MenuActions extraActions = new MenuActions(EXPAND, COLLAPSE, CUT, COPY, PASTE);

    /**
     * The find bar: null until {@link #searchable} first asks for one, then hidden until Ctrl+F, and no part of
     * the layout or the Tab order until it is up.
     */
    private volatile FindBar find;

    /**
     * Shows the label of a hovered row whole, where it already is, when the row cuts it off — drawn over whatever
     * is beside the tree and never hit there, so it changes nothing under the pointer.
     */
    private final Unclip unclip;

    /**
     * Build a tree called {@code name}, which asks for its roots in its first frame. On the GUI thread, where every
     * widget is built.
     *
     * <p>The name, qualified by the GUI's, is the tree's {@link #address()}: every message to or from it carries that,
     * so the name is unique among a GUI's trees and chosen by whoever builds the view ("files", "outline"). It is given here
     * rather than set afterwards because whoever answers has to know which tree is asking. The roots are asked for in
     * the first frame and not here because whoever answers may be built after the tree, as an application's view is
     * built after the widgets it shows. Items are compared by {@code equals}, and the items of a tree are its own type: what arrives in a
     * {@link Children} is taken to be a {@code T}.
     *
     * @throws IllegalStateException when another tree on this GUI already has the name
     */
    public TreeView(Gui gui, String name) {
        this.gui = gui;
        this.name = java.util.Objects.requireNonNull(name, "name");
        this.address = gui.address(name);
        this.childrenSub = gui.mailbox(CHILDREN, address, this::listed);
        this.marksSub = gui.mailbox(MARKS, address, this::marked);
        this.refreshSub = gui.mailbox(REFRESH, address, r -> refresh());
        ContextMenu.presentOn(gui);   // rows carry whatever menu the application declares; this shows it
        // Marked plus and minus, the set's own for "open everything under it" and "shut it": the row's chevron
        // opens one level, and these walk the whole subtree, so they are told apart from it on purpose. Enabled by
        // what the row is — a leaf has nothing to expand, a shut row has nothing to collapse.
        Predicate<T> always = item -> true;
        // The transfer commands, on the menu as well as on the chords — a cut you can only reach by knowing the
        // chord is half a feature, and the menu is where a user finds out that a tree can do this at all.
        //
        // Shown only where the tree accepts transfers, because reorderable() is what makes any of them mean
        // anything; hidden rather than greyed, since a tree that will never take a paste is not a tree where
        // Paste "does not apply right now". Paste is greyed rather than hidden when nothing is in hand, because that
        // genuinely is "not right now", and the line disappearing would leave the user wondering where it went.
        this.lines = List.of(
                new Line<>(EXPAND, Icon.PLUS, "Expand", this::expandDeep, always, this::canOpen),
                new Line<>(COLLAPSE, Icon.MINUS, "Collapse", this::collapseDeep, always, this::isOpen),
                new Line<>(CUT, Icon.CUT, "Cut", (item, job) -> take(item, DropEffect.MOVE),
                        item -> offers(DropEffect.MOVE), always),
                new Line<>(COPY, Icon.COPY, "Copy", (item, job) -> take(item, DropEffect.COPY),
                        item -> offers(DropEffect.COPY), always),
                new Line<>(PASTE, Icon.PASTE, "Paste", (item, job) -> paste(item),
                        item -> effects != null, item -> gui.transfer().offers(itemType)));
        // The frame and the scroller are two boxes, not one. The frame carries the tree's own look and holds the
        // chrome; the rows go inside a scroller that fills what is left of it. One box could not do both: the bar
        // would be content, and content scrolls.
        this.root = gui.column()
                .role("tree")
                .width(Length.FILL)
                .height(Length.FILL)
                .background(Role.WELL)
                .corner(Length.rem(0.5f))
                .border(Length.rem(0.1f), Role.LINE)
                .padding(Length.dp(4))
                .scroll(false, false);
        // The rows scroll in a Scroller with a bar beside it. `rows` is the Scroller's node -- the box that stays
        // put, its view and offset, where drags start and land, the drop overlay -- and the entries go in the content
        // it moves.
        this.entries = gui.column().width(Length.FILL).childMotion(false);   // see kidsBox
        this.scrolling = Scrollbars.vertical(gui, entries);
        this.rows = scrolling.scroller().node();
        scrolling.node().width(Length.FILL).height(Length.FILL);
        this.dropIndicator = DropIndicator.of(gui.theme().color(Role.ACCENT));
        // The window's reveal, so it fades as every other one does. Before any row: each attaches as it is built.
        this.unclip = Unclip.of(gui);

        // Navigation is an ordered stage: moving a cursor is order-dependent under key repeat, and it is pure
        // lookups on the visible list — exactly what the GUI-thread lane is for. Registering it also makes the
        // tree focusable, which is the single tab stop.
        Seams.of(gui).onKey(this.root, this::onKey);
        // Cut, copy and paste, claimed FOCUSED so they belong to the tree only while it holds focus -- a text
        // field in the same window keeps its own, and neither has to know about the other.
        Seams.of(gui).claim(this.root, CUT_CHORD, ClaimScope.FOCUSED, () -> take(selectedItem(), DropEffect.MOVE));
        Seams.of(gui).claim(this.root, COPY_CHORD, ClaimScope.FOCUSED, () -> take(selectedItem(), DropEffect.COPY));
        Seams.of(gui).claim(this.root, PASTE_CHORD, ClaimScope.FOCUSED, () -> paste(selectedItem()));

        // No find bar until searchable(true) asks for one: a tree nobody searches carries nothing for it.
        root.children(scrolling.node());

        // To itself, so it is heard in the first frame, by which time every answerer is subscribed: see refresh().
        gui.bus().publish(REFRESH, new Refresh(address));
    }

    /** The node to place in a layout (size it there — the tree fills whatever box it is given). */
    public Node node() {
        return root;
    }

    /** What this tree is called in its GUI. */
    public String name() {
        return name;
    }

    /** Where this tree is on the bus ({@code "main.files"}): the {@code tree} of every message to or from it. */
    public String address() {
        return address;
    }

    /** Ask for what is under {@code parent}, or for the roots when it is null. */
    private void ask(T parent) {
        gui.bus().publish(CHILDREN_WANTED, new ChildrenWanted(address, parent));
    }

    /**
     * A level has arrived. It is the first listing of a row that was asked for, or of the roots, and builds that
     * level; or it is one of a refresh's, held until the rest of them land. Anything else answers nothing this tree
     * asked — a second reply, or one for an item that has gone — and is dropped, and counted.
     */
    private void listed(Children reply) {
        List<Walk> resumed;
        Row landed = null;
        synchronized (this) {
            @SuppressWarnings("unchecked")
            T parent = (T) reply.parent();
            Row row = parent == null ? null : rowsByItem.get(parent);
            Object key = parent == null ? ROOTS : row;
            if (refreshAwaits != null && key != null && refreshAwaits.remove(key)) {
                refreshing.put(key, reply.entries());
                if (!refreshAwaits.isEmpty()) {
                    return;
                }
                landed = applyRefresh();
                resumed = List.of();
            } else if (parent == null && !rootsListed) {
                rootsListed = true;
                for (Entry entry : reply.entries()) {
                    Row r = new Row(item(entry), entry, null);
                    rootRows.add(r);
                    rowsByItem.put(r.item, r);
                    entries.append(r.entry);
                }
                refreshVisible();
                resumed = List.copyOf(rootWaiters);
                rootWaiters.clear();
                if (refreshAgain) {
                    refreshAgain = false;
                    startRefresh();
                }
            } else if (row != null && row.materialized && !row.listed) {
                materialize(row, reply.entries());
                resumed = List.copyOf(row.waiters);
                row.waiters.clear();
            } else {
                Probe.count(Lane.BUS, CHILDREN.name() + " unasked");
                return;
            }
        }
        if (landed != null) {
            select(landed, true);
        }
        for (Walk walk : resumed) {
            walk.run();
        }
    }

    @SuppressWarnings("unchecked")
    private T item(Entry entry) {
        return (T) entry.item();
    }

    /**
     * Put a checkbox on every row. The tree draws them and says when one is clicked ({@link #TOGGLED}); the
     * application owns what is ticked and says so ({@link #MARKS}):
     *
     * <pre>{@code
     * tree.checkable();
     * gui.mailbox(TreeView.TOGGLED, tree.address(), t -> logic.send(new Flip(t.item())));
     * // the logic, having flipped it: marks.send(new TreeView.Marks(tree, Map.of(item, Check.ON, parent, Check.MIXED)))
     * }</pre>
     *
     * <p>The same split as the hierarchy, for the same reason. A tick is a fact about the application's model —
     * which files are staged, which options are on — so the tree can no more own it than it owns the tree itself.
     * It draws what it is told and reports the click, and a click changes no box until the application answers,
     * a frame later; a box that ticked itself first would be one the application could then untick under the
     * pointer.
     *
     * <p><b>Cascading is deliberately not here.</b> Whether ticking a folder ticks everything under it, whether a
     * parent goes {@link Check#MIXED} when one child is on, whether ticking a child ticks its parents — those are
     * model rules and they genuinely differ: a staging area cascades down, a filter does not, a permission tree
     * propagates up. A widget that picked one would be wrong for the other two and would have to be fought. A
     * {@link Marks} names every item it changes, so any of those policies is one message.
     *
     * <p>Space toggles the selected row, which is the same command reached the other way — and the reason it is
     * the tree's business rather than the application's is that the tree already owns the selection the chord
     * acts on.
     *
     * <p>Turning this on shows a box on every row that exists and every row built afterwards. There is no way
     * to turn it off again, deliberately: a control that comes and goes underneath the pointer is the thing the
     * hover rule exists to prevent, and a tree that is sometimes checkable is a tree whose rows change width
     * while you are reading them.
     */
    public synchronized TreeView<T> checkable() {
        checkable = true;
        for (Row row : rowsByItem.values()) {
            row.checkBox.visible(true);
        }
        return this;
    }

    /** What is ticked has changed: keep it for every item named, and redraw the boxes of those that have rows. */
    private synchronized void marked(Marks told) {
        for (Map.Entry<Object, Check> mark : told.marks().entrySet()) {
            checks.put(mark.getKey(), mark.getValue());
            Row row = rowsByItem.get(mark.getKey());
            if (row != null) {
                row.paintCheck(mark.getValue());
            }
        }
    }

    /**
     * Ask for the hierarchy again and bring the rows into line with it — what {@link #REFRESH} does, and the other half
     * of {@link #reorderable}.
     *
     * <p>A tree is told its model a level at a time, when it asks, and is never told when that model changes, which
     * is the right split (it is the application's model, mutated by the application's code) and leaves the
     * application holding one obligation: <b>say when</b>. Without this the tree resolved a placement, drew the
     * indicator, refused what could not be done and recorded the change — and then showed the hierarchy as it had
     * been, which made every one of those correct behaviours look broken.
     *
     * <p><b>Every level asked for at once, and applied at once.</b> A {@link ChildrenWanted} goes out for the roots and
     * for every row already listed, and nothing changes on screen until the last of them is answered. Applying each
     * as it landed would be simpler and wrong: an item moved from one branch to another is missing from one listing
     * and present in the other, and whichever landed first would decide whether its row was kept or rebuilt. A
     * refresh asked for while one is in flight follows it.
     *
     * <p><b>Rows are reused, not rebuilt.</b> An item that was already in the tree keeps its row — the same
     * node, the same registrations, its expansion state, and its place in the selection — and is moved to
     * wherever the source now says it is. Rebuilding would be far simpler and is the thing that must not happen:
     * a rebuilt tree closes every folder the user had opened, which after a drag is precisely the state they
     * were working in.
     *
     * <p><b>No new listings.</b> Only rows that have already been listed are asked about again, so a refresh costs
     * exactly the directory listings the user has already paid for. A collapsed folder is re-read when it is
     * next opened, which is when it was going to be read anyway.
     */
    public synchronized TreeView<T> refresh() {
        if (!rootsAsked) {
            // The first: the one the tree sent itself when it was built, heard in its first frame. The roots are asked
            // for here, not in the constructor, because whoever answers may be built after the tree, and a question
            // published before its answerer subscribes is heard by nobody, and waited on for good.
            rootsAsked = true;
            if (!rootsListed) {
                ask(null);
            }
            return this;
        }
        if (refreshAwaits != null || !rootsListed) {
            refreshAgain = true;   // after the one in flight, or once the roots it would be asking about are here
            return this;
        }
        startRefresh();
        return this;
    }

    /** Ask again for the roots and every level already listed. Guarded by {@code this}. */
    private void startRefresh() {
        refreshing = new HashMap<>();
        refreshAwaits = new java.util.HashSet<>();
        refreshAwaits.add(ROOTS);
        List<T> asked = new ArrayList<>();
        for (Row row : rowsByItem.values()) {
            if (row.listed) {
                refreshAwaits.add(row);
                asked.add(row.item);
            }
        }
        ask(null);
        for (T item : asked) {
            ask(item);
        }
    }

    /**
     * The last of a refresh's levels has landed: apply them all. Guarded by {@code this}.
     *
     * @return where the selection lands when its row has gone, or null
     */
    private Row applyRefresh() {
        Map<Object, List<Entry>> listings = refreshing;
        refreshing = null;
        refreshAwaits = null;
        java.util.Set<T> live = new java.util.HashSet<>();
        reconcile(null, listings.get(ROOTS), listings, live);
        // Anything not reached is gone from the model. It cannot be hiding under a collapsed row: an
        // unopened row has no children rows to hide, and an opened one was walked.
        for (java.util.Iterator<Map.Entry<T, Row>> it = rowsByItem.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<T, Row> e = it.next();
            if (!live.contains(e.getKey())) {
                e.getValue().entry.remove();
                it.remove();
            }
        }
        refreshVisible();
        // The selection never rests on a row that is no longer there — the same rule a collapse follows.
        Row landed = selected != null && !rowsByItem.containsKey(selected.item) ? nearestLive(selected) : null;
        if (selected != null && !rowsByItem.containsKey(selected.item)) {
            selected = null;
        }
        if (refreshAgain) {
            refreshAgain = false;
            startRefresh();
        }
        return landed;
    }

    /**
     * Reconcile one level: the rows under {@code parent} become exactly {@code level}, in that order.
     *
     * <p>Each row is inserted at its index whether or not it moved. That is one mutation per row per reconcile,
     * against the bookkeeping needed to work out which of them actually changed — and the bookkeeping is what
     * would be wrong the first time two items swapped places, because a swap has no smallest edit that is
     * obviously right. A mutation that sets a node where it already is costs a list remove and an add.
     *
     * <p>A row listed again goes down into its new listing. One whose children were asked for after the refresh
     * began has none in it, and keeps the rows its own reply built.
     */
    private void reconcile(Row parent, List<Entry> level, Map<Object, List<Entry>> listings, java.util.Set<T> live) {
        Node box = parent == null ? entries : parent.kidsBox;
        List<Row> settled = new ArrayList<>(level.size());
        for (int i = 0; i < level.size(); i++) {
            Entry entry = level.get(i);
            T item = item(entry);
            live.add(item);
            Row row = rowsByItem.get(item);
            if (row == null) {
                row = new Row(item, entry, parent);
                rowsByItem.put(item, row);
            } else {
                row.reseat(parent);
                row.resync(entry);
            }
            box.insert(row.entry, i);
            settled.add(row);
            List<Entry> under = listings.get(row);
            if (under != null && row.listed) {   // not a row the entry has just made a leaf
                reconcile(row, under, listings, live);
            } else {
                keep(row, live);
            }
        }
        List<Row> siblings = parent == null ? rootRows : parent.children;
        siblings.clear();
        siblings.addAll(settled);
    }

    /** Mark everything under {@code row} live as it stands: rows a refresh did not list, and so does not judge. */
    private void keep(Row row, java.util.Set<T> live) {
        for (Row child : row.children) {
            live.add(child.item);
            keep(child, live);
        }
    }

    /** The nearest row still in the tree above a removed one — where a selection lands when its row goes. */
    private Row nearestLive(Row gone) {
        for (Row p = gone.parent; p != null; p = p.parent) {
            if (rowsByItem.containsKey(p.item)) {
                return p;
            }
        }
        return rootRows.isEmpty() ? null : rootRows.get(0);
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
     * Let rows be dragged to new places in the tree, and cut, copied and pasted, with the effects the model has:
     * {@code MOVE} for a drag or a cut, {@code COPY} for a copy, which a model whose items are unique leaves out.
     * Where one lands is announced as {@link #PLACED}; the application moves it in its own model, records it in its
     * own history, and sends {@link #REFRESH} for the tree to show it.
     *
     * <pre>{@code
     * tree.reorderable(DropEffect.MOVE);
     * gui.mailbox(TreeView.PLACED, tree.address(), p -> logic.send(new Move(p.item(), p.where())));
     * // the logic, having moved it: refresh.send(new TreeView.Refresh(tree))
     * }</pre>
     *
     * <p>A press on a row becomes a drag only once it has travelled far enough and been held long enough, so an
     * ordinary click still selects; and a drop that never got as far as being drawn commits nothing, so a click
     * too quick to see cannot reorganise the tree. Escape cancels. All of that is the framework's, not this
     * widget's — {@link dev.vexelray.gui.core.spi.Seams#onDragSource} and {@link dev.vexelray.gui.core.spi.Seams#onDrop} are the whole of what is registered here.
     *
     * <p>The tree draws the rest: a line or a box where the drop would land, and the row's label riding beside the
     * pointer, wherever it is carried. An application draws neither (transfer.md §0).
     *
     * <p><b>Every point in the tree resolves to a placement.</b> There is no dead space between rows, none at the
     * edges, and none below the last row — that space is "into the root", which is how an item is moved back to
     * the top level. A row that can hold children divides into three bands, an outer quarter each side for
     * "before" and "after" and the middle half for "into"; a leaf divides in two, since there is no inside to
     * drop into. What makes it total is that the row is chosen by which one the pointer has reached rather than
     * by which rect contains the point: a sub-pixel gap between two rows would otherwise be a place where drops
     * silently do nothing, and that is exactly the kind of crack a user learns to hunt for.
     *
     * <p><b>What is refused is decided here, in the frame, from what is declared</b> ({@link #placeable}): an item
     * dropped onto or under itself, a move that leaves it where it already is, a place among the children of an
     * entry that does not {@link Entry#acceptsChildren accept} them, an effect not given here. Refusing in the frame
     * means the user sees it before releasing rather than discovering it afterwards. What only the model can know
     * (a name already taken in that folder) the application refuses when it hears the placement, and says why.
     *
     * <p>The payload is offered as {@link #itemType()}, which is private to this tree: two trees do not accept
     * one another's rows unless one is deliberately told about the other's type.
     */
    public TreeView<T> reorderable(DropEffect... effects) {
        java.util.EnumSet<DropEffect> given = java.util.EnumSet.noneOf(DropEffect.class);
        for (DropEffect effect : effects) {
            if (!effect.accepts()) {
                throw new IllegalArgumentException("a placement has an effect: " + effect);
            }
            given.add(effect);
        }
        if (given.isEmpty()) {
            throw new IllegalArgumentException("reorderable with no effect would offer nothing: say MOVE, COPY or both");
        }
        if (this.effects != null) {
            throw new IllegalStateException("this tree is already reorderable");
        }
        this.effects = java.util.Collections.unmodifiableSet(given);
        Seams.of(gui).onDragSource(rows, (x, y) -> {
            Row from = rowAt(y);
            if (from == null) {
                return null;   // the space below the last row is somewhere to drop, not something to pick up
            }
            // Dragging a row is acting on it, so it becomes the selection — the same thing a click would do, and
            // what makes the row menu and the keyboard agree with what the pointer just grabbed.
            select(from, true);
            return Payload.of(itemType, from.item);
        });
        Seams.of(gui).onDrop(rows, (payload, x, y) -> payload.as(itemType)
                .map(moved -> resolvePlacement(moved, y))
                .orElse(Drop.NONE));
        // Drawn from the published drag rather than from the live session: the session is the framework's own
        // state, mutated on the GUI thread inside the frame, and a widget reading it would be reading a value
        // that changes underneath its paint. The State commits once per frame and only on change, so a pointer
        // held still over one seam costs nothing.
        this.dragSub = Seams.of(gui).drag().onCommit(v -> showDrop(v.value()));
        // What is in the hand is the tree's to draw, as the indicator is: the tree knows what a row is called, and
        // an application that had to build this itself would be rebuilding it in every application. Watched
        // rather than read from the published drag, because it follows the pointer, and the published drag is a
        // frame behind it (transfer.md §12.3).
        Seams.of(gui).watchDrag(rows, new DragWatch() {
            @Override
            public void moved(DragState now) {
                carry(now);
            }

            @Override
            public void ended(DragState last, DropEffect performed) {
                carry(DragState.NONE);
            }
        });
        return this;
    }

    /**
     * Show, move or put away the label that rides with the pointer while one of this tree's rows is carried —
     * wherever it is carried, the tree's own rows or any other target. It says what would happen as well as what
     * is held: the accent while releasing would place it, danger where it would not.
     *
     * <p>Beside the hotspot rather than under it, so the label never hides what the pointer is aiming at; and by
     * a distance in dp, which is fixed on screen and does not grow when the content is zoomed. Floating last on
     * the root, put there at the first carry so it is over everything built before it; and hit-inert, because a
     * thing under the pointer that the pointer could see would be a drop target on top of every drop target.
     */
    private void carry(DragState drag) {
        T held = drag.active() ? drag.payload().as(itemType).orElse(null) : null;
        Node label;
        synchronized (this) {
            if (held == null && carried == null) {
                return;
            }
            if (carried == null) {
                carried = gui.text("").width(Length.AUTO).height(Length.rem(2))
                        .padding(Length.ZERO, Length.rem(0.625f)).corner(Length.rem(0.4f))
                        .textSize(Length.rem(0.9375f)).align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE)
                        .lit(Finish.LIT).elevation(gui.theme().elevation(Relief.FLOATING))
                        .hitInert(true).visible(false);
                gui.root().append(carried);
            }
            label = carried;
        }
        // Told every frame of a drag, moved or not; a write that changes nothing would still owe a frame, and that
        // frame another, so the window would never be idle while a row was carried.
        String text = held == null ? null : labelOf(held);
        Object now = held == null ? null : List.of(text, drag.x(), drag.y(), drag.accepts());
        synchronized (this) {
            if (java.util.Objects.equals(now, carriedAs)) {
                return;
            }
            carriedAs = now;
        }
        if (held == null) {
            label.visible(false);
            return;
        }
        float dpi = Math.max(0.0001f, Seams.of(gui).dpi().value());
        label.text(text).visible(true)
                .floatAt(Length.dp(drag.x() / dpi + 14f), Length.dp(drag.y() / dpi + 12f))
                .background(drag.accepts() ? CARRY_ACCEPTS : CARRY_REFUSES)
                .textColor(drag.accepts() ? Role.ON_ACTION : Role.ON_DANGER);
    }

    /** What {@code item}'s row says, or the item itself when it has none here (carried in from another tree). */
    private synchronized String labelOf(T item) {
        Row row = rowsByItem.get(item);
        return row == null ? String.valueOf(item) : row.name;
    }

    /** Whether {@code item} takes a drop into it, as the application last said. */
    private synchronized boolean acceptsChildren(T item) {
        Row row = rowsByItem.get(item);
        return row != null && row.acceptsChildren;
    }

    /**
     * Replace how this tree shows where a drop would land. {@link DropIndicator#NONE} draws nothing; the default
     * is {@link DropIndicator#of} in the theme's accent.
     *
     * <p>Where the indicator goes is not negotiable — it comes from the resolution that produced the change the
     * drop would make, so it cannot point somewhere the drop would not happen. What it looks like is entirely
     * this.
     */
    public TreeView<T> dropIndicator(DropIndicator indicator) {
        this.dropIndicator = java.util.Objects.requireNonNull(indicator, "indicator");
        return this;
    }

    /**
     * Paint, move or clear the drop indicator for the drag as it now stands.
     *
     * <p>Clearing is guarded by whether anything is currently painted, because a drag over some other widget
     * still commits a new state on every move — and writing "no overlay" sixty times a second onto a node that
     * has none is a mutation per frame that changes nothing.
     */
    private void showDrop(DragState drag) {
        Rect box = rows.layout().rect();
        boolean mine = drag.accepts() && box != null && drag.y() >= box.y() && drag.y() < box.y() + box.h();
        if (!mine) {
            if (painting) {
                painting = false;
                rows.overlay(null);
            }
            return;
        }
        Rect at = drag.indicator();
        // Into the node's own frame: a Picture is drawn in the box it is attached to, so it must not carry the
        // absolute position — which is also what keeps it right as the container scrolls under it.
        Rect local = new Rect(at.x() - box.x(), at.y() - box.y(), at.w(), at.h());
        painting = true;
        rows.overlay(dropIndicator.paint(local, drag.effect()));
    }

    /** The form this tree offers its rows as. Pass it to another tree's drop target to allow drags between them. */
    public PayloadType<T> itemType() {
        return itemType;
    }

    /**
     * Which placement the pointer is over, as one value carrying the indicator and the change together.
     *
     * <p>Runs on the GUI thread, once per frame of a drag. Rects come from the published layout, so they are the
     * ones the tree is <em>drawn</em> at — a row still sliding into place after a previous drop resolves where it
     * appears rather than where it is heading.
     */
    private Drop resolvePlacement(T moved, float y) {
        List<Row> rows2;
        synchronized (this) {
            rows2 = List.copyOf(visible);
        }
        for (Row row : rows2) {
            Rect rect = row.rowNode.layout().rect();
            if (rect == null || rect.h() <= 0f) {
                continue;   // not laid out yet; it cannot be under anything
            }
            if (y >= rect.y() + rect.h()) {
                continue;   // the pointer is past this row entirely
            }
            // The first row the pointer has not gone past owns the point, whether or not the rect contains it.
            // That is what closes the gaps: a point in the crack between two rows belongs to the lower one
            // rather than to nothing.
            return bandOf(moved, row, rect, y);
        }
        // Past every row: the top level, which is the only way to drag something out of a branch.
        return placed(moved, Placement.intoRoot(), belowLastRow(rows2));
    }

    /** Which of a row's bands the pointer is in, and the drop that band means. */
    private Drop bandOf(T moved, Row row, Rect rect, float y) {
        float within = clamp01((y - rect.y()) / rect.h());
        // Whether it can *hold* children, not whether it has any, because an empty branch is still a branch and a
        // row that has just been emptied must not stop accepting drops.
        if (!acceptsChildren(row.item)) {
            // Nothing that has no inside, so the row divides in two and the boundary is its middle. Giving such
            // a row an "into" band that then always refused would be a third of it that looks live and is not.
            return within < 0.5f
                    ? placed(moved, Placement.before(row.item), edge(rect, true))
                    : placed(moved, Placement.after(row.item), edge(rect, false));
        }
        if (within < DROP_EDGE) {
            return placed(moved, Placement.before(row.item), edge(rect, true));
        }
        if (within >= 1f - DROP_EDGE) {
            return placed(moved, Placement.after(row.item), edge(rect, false));
        }
        return placed(moved, Placement.into(row.item), rect);
    }

    /** The drop this placement is, if it is offered: landing announces it. A refusal is an ordinary NONE. */
    private Drop placed(T moved, Placement<T> where, Rect indicator) {
        // MOVE, because a pointer drag has no way to say otherwise yet: the gesture carries no modifier state,
        // so there is nothing for a Ctrl-drag to be recognised from. When it does, this is the one line that
        // changes — a paste already places with COPY.
        return placeable(moved, where, DropEffect.MOVE)
                ? Drop.move(indicator, () -> announce(moved, where, DropEffect.MOVE))
                : Drop.NONE;
    }

    /** Say that {@code moved} was put at {@code where}: the application's to make so. */
    private void announce(T moved, Placement<T> where, DropEffect effect) {
        gui.bus().publish(PLACED, new Placed(address, moved, where, effect));
    }

    /** Whether this tree was made reorderable with {@code effect}. */
    private boolean offers(DropEffect effect) {
        java.util.Set<DropEffect> given = effects;
        return given != null && given.contains(effect);
    }

    /**
     * Whether putting {@code moved} at {@code where} with {@code effect} is offered, from what is declared and what
     * the tree can see: never asking the application, because this is asked every frame of a drag.
     *
     * <ul>
     *   <li>The effect is one the tree was made {@link #reorderable} with.
     *   <li>Not onto, beside or under itself: an item cannot be put inside what it contains, and beside itself is
     *       where it is. For a copy too, which would be a folder holding a copy of itself, forever.
     *   <li>The place is among the children of something that {@link Entry#acceptsChildren accepts} them: {@code
     *       INTO} asks the reference, {@code BEFORE} and {@code AFTER} ask its parent, and the top level always does.
     *   <li>A move goes somewhere else: not into the parent it is already in, nor just before the sibling after it or
     *       just after the one before it, each of which is the item exactly where it was. A copy may land there.
     * </ul>
     *
     * <p>Only rows the tree has are consulted: an item carried in from another tree has no row here, and is held
     * to the second and third rule only as far as the rows that exist can say.
     */
    private synchronized boolean placeable(T moved, Placement<T> where, DropEffect effect) {
        if (!offers(effect)) {
            return false;
        }
        Row mine = rowsByItem.get(moved);
        if (where.isRoot()) {
            return effect != DropEffect.MOVE || mine == null || mine.parent != null;
        }
        T ref = where.reference();
        Row at = rowsByItem.get(ref);
        for (Row r = at; r != null; r = r.parent) {
            if (r.item.equals(moved)) {
                return false;   // itself, or something under it
            }
        }
        if (ref.equals(moved)) {
            return false;
        }
        boolean into = where.relation() == Placement.Relation.INTO;
        Row container = into ? at : at == null ? null : at.parent;
        boolean accepts = into ? at != null && at.acceptsChildren : container == null || container.acceptsChildren;
        if (!accepts) {
            return false;
        }
        if (effect != DropEffect.MOVE || mine == null || at == null) {
            return true;
        }
        if (into) {
            return mine.parent != at;
        }
        if (mine.parent != at.parent) {
            return true;
        }
        List<Row> siblings = mine.parent == null ? rootRows : mine.parent.children;
        int from = siblings.indexOf(mine);
        int to = siblings.indexOf(at);
        return where.relation() == Placement.Relation.BEFORE ? to != from + 1 : to != from - 1;
    }

    /** A seam line at the top or bottom edge of a row, thin enough to read as "between" rather than "on". */
    private static Rect edge(Rect rect, boolean top) {
        return new Rect(rect.x(), top ? rect.y() : rect.y() + rect.h() - SEAM_PX, rect.w(), SEAM_PX);
    }

    /** The seam under the last row — where a drop onto the empty space below the tree would land. */
    private Rect belowLastRow(List<Row> laidOut) {
        for (int i = laidOut.size() - 1; i >= 0; i--) {
            Rect rect = laidOut.get(i).rowNode.layout().rect();
            if (rect != null && rect.h() > 0f) {
                return edge(rect, false);
            }
        }
        Rect box = rows.layout().rect();
        return box == null ? Rect.ZERO : new Rect(box.x(), box.y(), box.w(), SEAM_PX);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** Which visible row covers {@code y}, or null past the last one. */
    private Row rowAt(float y) {
        synchronized (this) {
            for (Row row : visible) {
                Rect rect = row.rowNode.layout().rect();
                if (rect != null && rect.h() > 0f && y >= rect.y() && y < rect.y() + rect.h()) {
                    return row;
                }
            }
        }
        return null;
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

    /**
     * The checkbox drawn on {@code item}'s row, or null when that row does not exist — <b>to point at, not to
     * restyle</b>, on the same terms as {@link #rowNode}: read its box, play a {@link Cue} on it, aim a test at
     * it. The tree rewrites what is inside it whenever the marks are refreshed.
     *
     * <p>It has no size at all until {@link #checkable} has been called, because until then it is hidden and a
     * hidden node is out of the layout.
     */
    public synchronized Node checkBoxNode(T item) {
        Row r = rowsByItem.get(item);
        return r == null ? null : r.checkBox;
    }

    /** The text node of {@code item}'s label, or null with no row — package-private, so a test can measure it. */
    synchronized Node labelNode(T item) {
        Row r = rowsByItem.get(item);
        return r == null ? null : r.label;
    }

    /**
     * How a cut-off name is shown whole while its row is hovered: the window's {@link Unclip#of reveal}, so a fade
     * given to it — {@code Unclip.of(gui).motion(...)}, where the clock is attached — is given to every reveal in
     * the window, this one included. It snaps without.
     */
    public Unclip unclip() {
        return unclip;
    }

    /**
     * Let Ctrl+F search the tree, or stop it. Off by default: the bar is a feature an application chooses, the
     * way a field chooses it by being {@link TextField#multiline multiline}.
     *
     * <p>The bar is built on the first {@code true} and kept, shut, across a {@code false} — so turning it off
     * and on again is a claim released and made again, and a bar that was up when the tree stopped being
     * searchable is put away rather than left over the rows.
     */
    public TreeView<T> searchable(boolean on) {
        if (on) {
            FindBar bar = built();
            // Seeded with nothing — a tree has no text to have selected, so the chord can only mean "start".
            bar.openOn(() -> "");
        } else {
            gui.releaseClaim(root, FindBar.FIND);
            FindBar bar = find;
            if (bar != null && bar.isOpen()) {
                bar.dismiss();
            }
        }
        return this;
    }

    /**
     * The bar, built on the first ask: shut, above the scroller rather than inside it — it takes its strip from
     * the rows' box, so opening it moves the rows down and scrolling them moves nothing of it.
     */
    private synchronized FindBar built() {
        if (find == null) {
            FindBar bar = new FindBar(gui, root, new Finder());
            root.insert(bar.node(), 0);
            find = bar;
        }
        return find;
    }

    /** The find bar's strip — package-private, so a test can read whether the user can see it. */
    Node findBar() {
        return built().node();
    }

    /** The find bar's status line — package-private, so a test can read what it says about the last search. */
    Node findStatus() {
        return built().statusNode();
    }

    /** The selected item, or null when nothing is selected yet. */
    public synchronized T selected() {
        return selected == null ? null : selected.item;
    }

    /**
     * The fill and ink of a row under the pointer, where they are {@code PANEL} and {@code INK} by default: a quiet
     * hover suits a tree whose click is cheap, and a tree whose click changes a great deal wants a loud one.
     */
    public TreeView<T> hoverStyle(Role fill, Role ink) {
        this.hoverFill = fill == null ? Role.PANEL : fill;
        this.hoverInk = ink == null ? Role.INK : ink;
        return this;
    }

    /** The pointer's shape over every row; the default is the window's. Set it before the rows are built. */
    public TreeView<T> rowCursor(CursorShape shape) {
        this.rowCursor = shape;
        return this;
    }

    /**
     * Put {@code action} on every row's menu, after the tree's own lines and behind a rule, in the order actions are
     * declared. The row is selected first — the menu that opens is about the row under the pointer, so that row must
     * visibly become the subject — and choosing the line publishes {@link Chosen} on {@link #CHOSEN}. Which rows
     * offer it, and which grey it, each row's {@link Entry} says.
     *
     * <p>Choosing any line stops the walk the tree was running — an expand-all still unfolding underneath the thing
     * the user chose instead. Work the application started for an earlier {@code Chosen} is the application's to stop.
     *
     * {@snippet :
     * tree.action(MenuAction.of("delete", "×", "Delete"));
     * gui.mailbox(TreeView.CHOSEN, tree.address(), chosen -> outbox.send(new Delete((Path) chosen.item())));
     * }
     *
     * @throws IllegalArgumentException when the id is one of the tree's own, or another action already has it
     */
    public TreeView<T> action(MenuAction action) {
        extraActions.add(action);
        return this;
    }

    /**
     * Expand {@code item} and everything under it, exactly as the menu's Expand does — including becoming this
     * tree's current action, so it supersedes whatever was running and is itself superseded by the next one.
     *
     * <p>Every level may have to be asked for, so the walk opens each level as its reply lands rather than at the
     * end: a deep tree over a slow source unfolds, which is both the honest report of what is happening and what
     * makes stopping it half way meaningful.
     */
    public TreeView<T> expandAll(T item) {
        expandDeep(item, begin());
        return this;
    }

    /** Collapse {@code item} and everything under it — the twin of {@link #expandAll}, and never a request. */
    public TreeView<T> collapseAll(T item) {
        collapseDeep(item, begin());
        return this;
    }

    /**
     * Expand {@code item} programmatically. Works top-down: an item whose ancestors have never been expanded has
     * no row yet (its parent's children were never asked for), and then this is a no-op — expand the path from
     * the root downward, or {@link #revealPath reveal} it. The first expansion asks for its children, like any other.
     *
     * <p>The roots are the exception, and are waited for: a tree asks for them when it is built and has none until
     * they land, so a root expanded straight after building the tree opens when they do.
     */
    public synchronized TreeView<T> expand(T item) {
        if (!rootsListed) {
            rootWaiters.add(new Walk(() -> true) {
                @Override
                void start() {
                    expand(item);
                }

                @Override
                boolean arrive(Row row) {
                    return true;
                }

                @Override
                boolean exhausted() {
                    return false;
                }
            });
            return this;
        }
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

    /**
     * Open the tree down to the last item of {@code chain} and select it — the reveal that has to <em>unfold</em>
     * the tree to get there, where {@link #select} only ever lands on a row that is already open.
     *
     * <p><b>{@code chain} is the way down, not a search.</b> Its first item is a root row, each next is a child of
     * the one before, and the last is the one to select. The tree cannot work that out for itself — it is told
     * children and never parents — so the application, which knows its own hierarchy, says it. A caller with only
     * the item and no way down wants {@link #expandAll} or the find bar instead.
     *
     * <p><b>A chain of replies.</b> Each level is opened, and asked for if it has not been, and the walk goes on from
     * it when its {@link Children} land: so it opens each level as it arrives, exactly as {@link #expandAll} does. It
     * is an action like any other, so it supersedes whatever was running and gives way to whatever comes next.
     *
     * <p>When the walk has ended it is announced on {@link #REVEALED}, so whatever wanted the row that now exists (a mark
     * on it being the case this was written for) hears it in the frame it landed. A superseded walk announces
     * nothing, which is the same rule the walk itself obeys.
     *
     * <p><b>Not reaching the end is not an error.</b> A level the source no longer offers stops the walk where it
     * is: the tree is left open as far as it got, nothing is selected, and it is still announced, as not reached —
     * the caller asked about a hierarchy that has moved under it, and that is an answer rather than a failure.
     *
     * @throws IllegalArgumentException when {@code chain} is empty
     */
    public TreeView<T> revealPath(List<T> chain) {
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("a reveal is a way down to something: the chain is empty");
        }
        new RevealWalk(begin(), List.copyOf(chain)).run();
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
        if (dragSub != null) {
            dragSub.close();
        }
        synchronized (this) {
            if (carried != null) {
                carried.remove();
                carried = null;
            }
        }
        FindBar bar = find;
        if (bar != null) {
            bar.close();   // takes the bar's own claims with it; the root's Ctrl+F goes with the root below
        }
        childrenSub.close();
        marksSub.close();
        refreshSub.close();
        begin();   // a walk waiting on a level stays waiting; the reply that would resume it no longer arrives
        synchronized (this) {
            for (Row r : rowsByItem.values()) {
                gui.releaseNode(r.rowNode);
                gui.releaseNode(r.disclosure);
            }
        }
        scrolling.verticalBar().ifPresent(Scrollbar::close);
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
            // The keyboard half of the checkbox. It is here rather than in the application because the chord
            // acts on the selection, and the selection is the tree's.
            case SPACE -> toggleChecked();
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
        gui.bus().publish(ACTIVATED, new Activated(address, item));
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
     * Whether {@code row} answers {@code query}: its entry's {@link Entry#find find} text holding the query, ignoring
     * case. The label unless the entry says otherwise, which is what a user typing into a find bar means by "search";
     * an application that knows better (a filesystem searched by whole path, an inventory by SKU) says so on the
     * entry, so the walk runs nothing of the application's in the frame.
     */
    private boolean matches(Row row, String query) {
        return row.key.toLowerCase().contains(query.toLowerCase());
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

        /** Shut: stop whatever the bar started. */
        @Override
        public void closed() {
            begin();   // the search is an action, and closing the bar is the user done with it
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
            built().status("");
            // Nothing to search for, but the bar asking has just taken a strip out of the rows' box — so the row
            // the user was on comes back into what is left of it, rather than being scrolled off by the chrome.
            revealSelected();
            return;
        }
        built().status("Searching…");
        (forward ? new FindForward(job, query, from) : new FindBack(job, query, from)).run();
    }

    /** A search has ended with {@code found}, or with nothing: say so, and go to it. */
    private void found(Row found) {
        built().status(found == null ? "No match" : "");
        if (found != null) {
            reveal(found);
            select(found, true);
        }
    }

    /**
     * A walk over the hierarchy in document order, which goes down into each row it reaches once that row's children
     * are here — asking for them, and waiting for the reply, when they are not.
     *
     * <p><b>Resumable, not blocking.</b> A level that has to be asked for ends the walk's turn: it waits on that row,
     * and the reply resumes it where it stopped. Nothing waits on a thread, so the tree never blocks the frame and a
     * slow source only makes the walk slower. A walk superseded while it waits finds out when it resumes and asks for
     * nothing more.
     *
     * <p>The row is arrived at, then gone down into, and the two are separate steps because a walk that stopped
     * between them for a reply must not arrive twice: a search would test the row again, and the anchor it is
     * stepping on from would answer itself.
     */
    private abstract class Walk implements Runnable {

        final Job job;
        final Deque<Row> pending = new ArrayDeque<>();
        /** The row arrived at whose children the walk goes down into next. */
        private Row below;
        private boolean started;

        Walk(Job job) {
            this.job = job;
        }

        /** Where the walk starts, once the roots are here. */
        abstract void start();

        /** Arrive at {@code row}; @return whether to go on, which a search that has its answer does not. */
        abstract boolean arrive(Row row);

        /** Whether to go down into {@code row} at all. */
        boolean descends(Row row) {
            return row.canExpand;
        }

        /** Nothing is left to walk; @return whether the walk carries on, having pushed more to walk. */
        abstract boolean exhausted();

        /** On the GUI thread: where the walk began, and from every reply it waited for. */
        @Override
        public final void run() {
            if (!job.live()) {
                return;
            }
            if (!started) {
                if (!awaitRoots(this)) {
                    return;
                }
                started = true;
                start();
            }
            while (job.live()) {
                if (below != null) {
                    if (!awaitChildren(below, this)) {
                        return;
                    }
                    synchronized (TreeView.this) {
                        // Reversed onto the stack, so the subtree comes off it top down — the order it is drawn in.
                        for (int i = below.children.size() - 1; i >= 0; i--) {
                            pending.push(below.children.get(i));
                        }
                    }
                    below = null;
                    continue;
                }
                Row row = pending.poll();
                if (row == null) {
                    if (exhausted()) {
                        continue;
                    }
                    return;
                }
                if (!arrive(row)) {
                    return;
                }
                if (descends(row)) {
                    below = row;
                }
            }
        }

        /** Put the roots on the stack, top first. */
        final void pushRoots() {
            synchronized (TreeView.this) {
                pending.clear();
                for (int i = rootRows.size() - 1; i >= 0; i--) {
                    pending.push(rootRows.get(i));
                }
            }
        }
    }

    /**
     * Whether the roots are here; when they are not, {@code walk} is resumed once they are.
     */
    private synchronized boolean awaitRoots(Walk walk) {
        if (rootsListed) {
            return true;
        }
        rootWaiters.add(walk);
        return false;
    }

    /**
     * Whether {@code row}'s children are here, so a walk can go down into it; when they are not, they are asked for if
     * nobody has, and {@code walk} is resumed once they land. A row that cannot open has none, and is here already.
     */
    private boolean awaitChildren(Row row, Walk walk) {
        T ask;
        synchronized (this) {
            if (!row.canExpand || row.listed) {
                return true;
            }
            row.waiters.add(walk);
            ask = claimFetch(row) ? row.item : null;
        }
        if (ask != null) {
            ask(ask);
        }
        return false;
    }

    /**
     * Search forward for {@code query} from {@code from} (or from the top when it is null), wrapping once past the
     * end when it started somewhere other than the top, and stop at the first row that answers.
     *
     * <p>It expands nothing on the way. Listing a row and opening it are two different things — the first is "what
     * is under here", the second is "show it" — and a search that opened every row it passed would leave the tree
     * unfolded behind it. Only the path to the answer is opened, by {@link #reveal}.
     */
    private final class FindForward extends Walk {
        private final String query;
        private final T from;
        private boolean past;
        private boolean wrapped;

        FindForward(Job job, String query, T from) {
            super(job);
            this.query = query;
            this.from = from;
            this.past = from == null;
            this.wrapped = past;
        }

        @Override
        void start() {
            pushRoots();
        }

        @Override
        boolean arrive(Row row) {
            if (!past) {
                past = row.item.equals(from);   // the anchor itself is the one row a "next" must not answer with
            } else if (matches(row, query)) {
                found(row);
                return false;
            }
            return true;
        }

        @Override
        boolean exhausted() {
            if (wrapped) {
                found(null);
                return false;
            }
            // Round again from the top, now matching everything: the anchor was never reached from where we started,
            // or there was nothing after it. Once only — the second pass ends the search either way.
            wrapped = true;
            past = true;
            pushRoots();
            return true;
        }
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
    private final class FindBack extends Walk {
        private final String query;
        private final T from;
        private boolean past;
        private Row before;   // the last match strictly before the anchor
        private Row last;     // and the last match anywhere, which is what the wrap lands on

        FindBack(Job job, String query, T from) {
            super(job);
            this.query = query;
            this.from = from;
            this.past = from == null;
        }

        @Override
        void start() {
            pushRoots();
        }

        @Override
        boolean arrive(Row row) {
            boolean anchor = !past && row.item.equals(from);
            past |= anchor;
            if (matches(row, query)) {
                last = row;      // a wrap lands on the last match anywhere — including the anchor itself, which is
                if (!past) {     // the right answer when it is the only match there is
                    before = row;
                }
            }
            if (anchor && before != null) {
                found(before);   // nothing further on can be nearer to the anchor from behind
                return false;
            }
            return true;
        }

        @Override
        boolean exhausted() {
            found(before != null ? before : last);   // nothing behind the anchor, so round to the end
            return false;
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
            openLocked(ancestors.get(i));   // walked through, so listed: this opens and asks for nothing
        }
    }

    // --- actions: the row menu, and the one-at-a-time rule ---

    /**
     * A row's menu: the tree's own lines, then the application's actions, each offered as the row's entry last said.
     * The row is the GUI thread's, as is this, so what the entry said is read without asking anyone.
     */
    private void rowMenu(Row row, MenuSink menu) {
        for (int i = 0; i < lines.size(); i++) {
            Line<T> line = lines.get(i);
            if (line.shown().test(row.item) && !row.hidden.contains(line.id())) {
                T item = row.item;
                boolean enabled = line.enabled().test(item) && !row.greyed.contains(line.id());
                menu.item(new MenuItem(null, line.label(), () -> line.body().accept(item, begin()), enabled,
                        line.mark()));
            }
            // A rule after Collapse and after Paste. Unconditional: a rule that would open the menu or double
            // another is dropped by the sink, so this needs no test of what survived above or below it.
            if (line.id().equals(COLLAPSE) || line.id().equals(PASTE)) {
                menu.separator();
            }
        }
        extraActions.offer(menu, gui, CHOSEN, Chosen::new, address, row.item, row.hidden, row.greyed, this::begin);
    }

    /**
     * Claim the tree for a new walk and hand back its job. One increment: everything running is now superseded,
     * and this is what the next {@link Job#live()} will be measured against.
     */
    private Job begin() {
        long generation = actions.incrementAndGet();
        return () -> actions.get() == generation;
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
     * Open {@code item} and its whole subtree, depth first, asking for each level as it is reached and going on from
     * it when it lands. {@code job} is asked between rows and whenever a reply resumes the walk, so a superseded
     * expand asks for nothing more. A row whose children were already asked for, by a click, is waited on rather than
     * asked again.
     */
    private void expandDeep(T item, Job job) {
        new Walk(job) {
            @Override
            void start() {
                synchronized (TreeView.this) {
                    Row row = rowsByItem.get(item);
                    if (row != null) {
                        pending.push(row);
                    }
                }
            }

            @Override
            boolean arrive(Row row) {
                openLocked(row);
                return true;
            }

            @Override
            boolean exhausted() {
                return false;
            }
        }.run();
    }

    /**
     * Walk a path from its root row down, opening every level but the last, and select what it ends on.
     *
     * <p><b>Each level is opened and listed before the next is looked for</b>, which is the whole of why this is
     * a walk rather than a handful of {@link #expand} calls: a row's children do not exist until its reply has
     * landed, so a caller that asked for the levels one after another would be looking for rows nobody had built
     * yet, and would find the deepest of them missing every time.
     */
    private final class RevealWalk extends Walk {
        private final List<T> path;
        private int at;

        RevealWalk(Job job, List<T> path) {
            super(job);
            this.path = path;
        }

        @Override
        void start() {
            // Not the stack: a path is one row per level, found by item as each level lands.
        }

        @Override
        boolean arrive(Row row) {
            return true;
        }

        @Override
        boolean exhausted() {
            while (at < path.size() - 1) {
                Row row;
                synchronized (TreeView.this) {
                    row = rowsByItem.get(path.get(at));
                }
                if (row == null) {
                    break;   // a level that is no longer there: as far as this goes, and the caller still runs
                }
                openLocked(row);
                at++;
                if (!awaitChildren(row, this)) {
                    return false;   // resumed by the reply, which finds this level done and looks for the next
                }
            }
            boolean reached = false;
            if (at >= path.size() - 1) {
                Row target;
                synchronized (TreeView.this) {
                    target = rowsByItem.get(path.getLast());
                }
                if (target != null) {
                    select(target, true);
                    reached = true;
                }
            }
            gui.bus().publish(REVEALED, new Revealed(address, path.getLast(), reached));
            return false;
        }
    }

    /**
     * Put {@code row} in the expanded state, whatever state it was in, asking for its children if nobody has. Its
     * subtree opens now when they are here, and when they land when they are not: there is nothing to open onto
     * before that.
     */
    private void openLocked(Row row) {
        boolean ask;
        synchronized (this) {
            if (!row.canExpand) {
                return;
            }
            ask = claimFetch(row);
            if (!row.expanded) {
                row.expanded = true;
                row.showDisclosure();
            }
            if (row.listed) {
                open(row, 1f);
            }
            refreshVisible();
        }
        if (ask) {
            ask(row.item);
        }
    }

    /**
     * Claim {@code row}'s one request without opening it; @return whether the caller must now send it.
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
        row.showDisclosure();
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
            gui.bus().publish(SELECTED, new Selected(address, item));
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
     * Flip {@code row} between expanded and collapsed. The visual flip is immediate; the first expansion also asks
     * for the row's children, and the subtree opens when they land. Collapsing with the selection inside the
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
        row.showDisclosure();
        if (row.expanded && !row.listed) {
            if (claimFetch(row)) {
                ask(row.item);
            }
            refreshVisible();
            return;
        }
        open(row, row.expanded ? 1f : 0f);
        refreshVisible();
        if (!row.expanded && selected != null && isUnder(selected, row)) {
            select(row, true);
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
    public synchronized TreeView<T> motion(Timing timing) {
        this.motion = Ramp.of(gui, timing);
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
        row.kidsBox.height(Length.rem(row.openness * contentEm(row)));
        row.turnDisclosure();
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
        row.turnDisclosure();
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

    /** Build {@code row}'s children from the level that has just arrived for it. Guarded by {@code this}. */
    private void materialize(Row row, List<Entry> level) {
        row.listed = true;
        for (Entry entry : level) {
            Row r = new Row(item(entry), entry, row);
            row.children.add(r);
            rowsByItem.put(r.item, r);
            row.kidsBox.append(r.entry);
        }
        if (row.children.isEmpty()) {
            // The application said it opens and listed nothing under it (an emptied directory): demote to a leaf.
            row.expanded = false;
            row.chevron.icon(null);
        }
        // The animation starts here rather than at the toggle: until the reply lands there is nothing to open,
        // and a subtree animating to a height of zero content would be a flourish over an empty box.
        open(row, row.expanded ? 1f : 0f);
        refreshVisible();
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

    /**
     * Take the selected row in hand, to be pasted somewhere else.
     *
     * <p>Nothing is changed here and nothing is removed: a cut is an <em>intention</em>, and the model is not
     * touched until the paste lands. That is what makes a cut that is never pasted cost nothing, and a cut
     * followed by Escape or by another cut a non-event — rather than a hole in the tree waiting to be filled.
     */
    private void take(T item, DropEffect effect) {
        if (item == null || !offers(effect)) {
            return;   // nothing to take, or a tree that does not take this kind of transfer at all
        }
        gui.transfer(new Transfer(Payload.of(itemType, item), effect));
    }

    /** The selected item, or null -- what a chord acts on, where a menu acts on the row it was opened over. */
    private synchronized T selectedItem() {
        return selected == null ? null : selected.item;
    }

    /**
     * Put what is held at the selection: <em>into</em> the selected row if it can hold children, otherwise after
     * it, and into the root if nothing is selected.
     *
     * <p>Into-if-it-can-hold is the rule every file manager uses, and it is expressible here only because the
     * tree already had to ask that question for drops. A paste onto a folder means "in this folder"; a paste
     * onto a file means "beside this file"; and the two are told apart by the same answer the middle band of a
     * row is offered on.
     *
     * <p>Offered on the same terms as a drop ({@link #placeable}), so a paste that cannot happen does nothing rather
     * than doing something unexpected — and a copy in a tree that was not made reorderable with {@code COPY} is
     * refused rather than quietly turning into a move. What lands is {@link #PLACED}, as a drop is.
     */
    private void paste(T target) {
        Transfer held = gui.transfer();
        if (!held.offers(itemType)) {
            return;
        }
        T moved = held.payload().as(itemType).orElse(null);
        if (moved == null) {
            return;
        }
        Placement<T> where = target == null ? Placement.intoRoot()
                : acceptsChildren(target) ? Placement.into(target) : Placement.after(target);
        if (!placeable(moved, where, held.effect())) {
            return;
        }
        announce(moved, where, held.effect());
        if (held.effect() == DropEffect.MOVE) {
            // A cut lands once. A copy stays in hand, because pasting the same thing into three places is the
            // reason copy exists and making the user press Ctrl+C between each would be answering that with no.
            gui.transfer(Transfer.NONE);
        }
    }

    /** Space on the selected row: the same command the box's click runs, reached from the keyboard. */
    private void toggleChecked() {
        Row row;
        synchronized (this) {
            row = selected;
        }
        if (!checkable || row == null) {
            return;
        }
        gui.bus().publish(TOGGLED, new Toggled(address, row.item));
    }

    private void restyle(Row row, InteractionState state) {
        synchronized (this) {
            row.pointer = state;
            styleLocked(row, state);
        }
    }

    /**
     * The pointer coming onto or off a row's disclosure. The row's own state arrives separately and in either order,
     * so each is kept and the row is styled from both.
     */
    private void disclosureHovered(Row row, boolean over) {
        synchronized (this) {
            row.onDisclosure = over;
            styleLocked(row, row.pointer);
        }
    }

    private void styleLocked(Row row, InteractionState state) {
        // The disclosure lights instead of the row, never with it: one of them is what a click there would do. EDGE
        // rather than the panel level, which is the level a card already is and so lit nothing, and neutral whatever
        // the tree's hover, so it does not borrow the look of a pick where choosing a row is loud.
        boolean opener = row.onDisclosure && row.canExpand;
        row.disclosure.background(opener ? Role.EDGE : null);
        row.chevron.tint(opener ? Role.INK : Role.DIM);
        if (row == selected) {
            row.rowNode.background(Role.SELECTION);
            row.label.textColor(Role.ACCENT);
        } else {
            // Transparent at rest, so an unselected row is the well it sits in rather than a rectangle of its own.
            boolean over = state != InteractionState.NORMAL && !opener;
            row.rowNode.background(over ? hoverFill : null);
            row.label.textColor(over ? hoverInk : Role.INK);
        }
    }

    /** The width of the indent at {@code depth}, with the gap that sets it off from the chevron. */
    private static Length indent(int depth) {
        return Length.rem(depth * INDENT_EM + GAP_EM);
    }

    /**
     * Track which row the pointer is on and report changes. The leave of one row and the enter of the next can
     * arrive in either order, so a leave only clears the row that is still the hovered one; and {@link #HOVERED} is
     * state, so a leave and the next enter in one frame are heard as the enter: off the rows altogether is the one
     * null anyone hears.
     */
    private void hovered(Row row, boolean over) {
        synchronized (this) {
            if (over) {
                if (hoveredRow == row) {
                    return;
                }
                hoveredRow = row;
            } else {
                if (hoveredRow != row) {
                    return;
                }
                hoveredRow = null;
            }
        }
        gui.bus().publish(HOVERED, new Hovered(address, over ? row.item : null));
    }
}
