package dev.vexelray.gui.demo.chapter;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.drop.DragState;
import dev.vexelray.gui.core.drop.Drop;
import dev.vexelray.gui.core.drop.DropEffect;
import dev.vexelray.gui.core.drop.PayloadType;
import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.demo.Chapter;
import dev.vexelray.gui.demo.Console;
import dev.vexelray.gui.demo.Stage;
import dev.vexelray.gui.demo.Ui;
import dev.vexelray.gui.widget.TreeView;
import dev.vexelray.text.TextLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drag and drop, on something worth dragging: a project outline whose tasks are moved between columns, ordered
 * within one, promoted out to the top level, or dropped on a target that is not a tree at all.
 *
 * <p>The registration is two calls — {@code onDragSource} and {@code onDrop} — and everything that makes a drag
 * a drag comes from the framework: the travel-and-hold threshold that keeps an ordinary click an ordinary click,
 * the flick too quick to have been drawn committing nothing, Escape cancelling, the undo record, and the focus
 * move that keeps Ctrl+Z pointed at the history the drop wrote into.
 *
 * <p><b>The split is the whole design.</b> A tree reads its hierarchy through a {@code Source}, so it can say
 * where a drop would land <em>in the tree's own terms</em> — before this row, into that branch, out to the root —
 * but it cannot say what putting it there does, because that is a mutation of a model it can only read. So the
 * tree resolves a placement and the application returns a {@link Change}. <b>Refusal is the application's too</b>,
 * by returning null: a task into itself, a placement that would leave it exactly where it already is. Answering
 * there is what makes the indicator disappear <em>before</em> the user releases rather than the drop being
 * swallowed afterwards.
 *
 * <p>And the third part, which the application also owns: <b>saying when the model changed</b>. The tree is never
 * told, so every change here ends in {@code refresh()}. Undo works the same way, because the inverse change is a
 * change like any other.
 */
public final class DragChapter implements Chapter {

    /** A task, or a column of them — the hierarchy is one thing, not two. */
    private static final class Task {

        private String name;
        private final List<Task> kids = new ArrayList<>();
        private Task parent;

        Task(String name, Task... children) {
            this.name = name;
            for (Task kid : children) {
                kid.parent = this;
                kids.add(kid);
            }
        }

        boolean isUnder(Task other) {
            for (Task p = this; p != null; p = p.parent) {
                if (p == other) {
                    return true;
                }
            }
            return false;
        }
    }

    private final Task root = new Task("Project",
            new Task("Backlog",
                    new Task("Ghost under the cursor"),
                    new Task("Reorder inside a column"),
                    new Task("Promote to a column of its own")),
            new Task("In progress",
                    new Task("Move a task between columns"),
                    new Task("Refuse an impossible drop")),
            new Task("Done",
                    new Task("Resolve every point to a placement")));

    private final History history = new History();
    private final List<Task> archived = new ArrayList<>();

    /** What the pointer is currently carrying, for the ghost to show. */
    private final AtomicReference<String> carrying = new AtomicReference<>("");

    private final List<Node> archiveRows = new ArrayList<>();

    private Gui gui;
    private Node archive;
    private Node archiveList;
    private Node ghost;
    private Console console;
    private TreeView<Task> board;

    @Override
    public String title() {
        return "Drag & drop";
    }

    @Override
    public String blurb() {
        return "Move tasks between columns, reorder them, promote one to the top level, or drop it on a "
                + "target that is not a tree — all of it undoable.";
    }

    @Override
    public Node build(Stage stage) {
        this.gui = stage.gui();
        Gui gui = this.gui;
        Theme theme = stage.theme();
        this.console = stage.console();

        // Without a history, a drop resolves and draws but commits nothing — deliberately, because a drop that
        // mutated with no way back would be the one operation in the framework the user could not take back, and
        // silently so. Setting one is the application saying where those changes go.
        gui.dropHistory(history);

        board = new TreeView<>(gui, new TreeView.Source<Task>() {

            @Override
            public List<Task> roots() {
                return List.copyOf(root.kids);
            }

            @Override
            public String label(Task task) {
                return task.name;
            }

            @Override
            public boolean hasChildren(Task task) {
                return !task.kids.isEmpty();
            }

            @Override
            public List<Task> children(Task task) {
                return List.copyOf(task.kids);
            }

            @Override
            public boolean acceptsChildren(Task task) {
                // Anything on this board can hold anything else — a column holds tasks, a task holds subtasks —
                // and, crucially, that stays true of a column the user has just emptied. Answering this with
                // hasChildren is what makes the last card out of a column the last card ever to go into it.
                return true;
            }
        });
        board.node().width(Length.FILL).height(Length.FILL);
        // Opened before the motion is installed, and the order is load-bearing rather than tidy. A board whose
        // columns are shut is a board with nothing to drag, so these have to be open on the first frame — and an
        // animated open is a change spread over frames a capture run never presents, which would photograph
        // three columns with their contents still at zero height. With no motion installed yet the tree takes
        // its instant path, which is the same reason Tabs defaults to no transition.
        for (Task column : root.kids) {
            board.expand(column);
        }
        // From here on, opening a column slides the rows below it down rather than teleporting them. A reordered
        // row still lands at once, which is deliberate: the whole board settles on the same frame, and half a
        // view animating while the other half snaps is worse than either (see the Motion chapter).
        board.motion((progress, done) ->
                stage.krono().ramp(sibarum.kronometer.Dur.ms(180), sibarum.kronometer.anim.Ease.OUT_CUBIC,
                        progress, done));

        // What each placement means, in the model's own terms — and which ones mean nothing, which is the more
        // interesting half. Every point over the rows resolves to a placement, so this is asked about all of
        // them, once per frame, while the pointer moves. It decides what to draw, not what to do: the change it
        // returns is not applied until the user releases.
        board.reorderable((moved, where, effect) -> {
            carrying.set(moved.name);
            // A copy has nothing to refuse about where it came from: it is a new task, so it can land inside
            // the one it was copied from, and landing where the original already sits is not a no-op. So the
            // item being placed is decided first, and every question below is asked about *that* item.
            Task placing = effect == DropEffect.COPY ? copyOf(moved) : moved;
            if (effect != DropEffect.MOVE && effect != DropEffect.COPY) {
                return null;   // LINK is not a thing a board has
            }
            if (where.isRoot()) {
                return put(placing, root, root.kids.size());
            }
            Task reference = where.reference();
            if (reference.isUnder(placing)) {
                return null;   // into itself or its own descendant: not a failure, an answer
            }
            return switch (where.relation()) {
                case INTO -> reference.kids.contains(placing) ? null
                        : put(placing, reference, reference.kids.size());
                case BEFORE -> beside(placing, reference, 0);
                case AFTER -> beside(placing, reference, 1);
            };
        });

        // A target that is not a tree: an ordinary box, a drop target, and a subscription to the published drag
        // so it can light up. The payload type is the tree's own, which is what stops two unrelated trees in one
        // window from silently accepting each other's rows.
        PayloadType<Task> taskType = board.itemType();
        archiveList = gui.column().width(Length.FILL).height(Length.grow(1)).gap(Length.rem(0.25f))
                .scroll(false, true);
        archive = gui.column().width(Length.FILL).height(Length.FILL)
                .background(theme.color(Role.WELL)).corner(Length.rem(0.75f))
                .border(Length.rem(0.15f), theme.color(Role.LINE))
                .padding(Length.dp(14)).gap(Length.rem(0.375f))
                .children(
                        gui.text("Archive").width(Length.FILL).height(Length.rem(1.5f))
                                .textSize(Length.rem(1)).textColor(theme.color(Role.INK))
                                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE),
                        gui.text("drop a task here to take it off the board")
                                .width(Length.FILL)
                                .textSize(Length.rem(0.875f)).textColor(theme.color(Role.FAINT))
                                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP),
                        archiveList);
        gui.onDrop(archive, (payload, x, y) -> payload.as(taskType)
                .map(task -> {
                    carrying.set(task.name);
                    return task.parent == null ? Drop.NONE : Drop.move(rectOf(archive), archiving(task));
                })
                .orElse(Drop.NONE));

        // The ghost: a floating, hit-inert last child of the root, which is the framework's whole overlay story —
        // no layer machinery, no z-order, no second tree. Hit-inert because a thing under the cursor that the
        // pointer can see would be a drop target sitting on top of every drop target.
        //
        // It is the application's, and it has to be: only the application knows what a payload looks like. What
        // the framework supplies is the pointer position, published once per frame, and a place to put a node
        // that is not in the flow.
        ghost = gui.text("").width(Length.AUTO).height(Length.rem(2))
                .padding(Length.ZERO, Length.em(0.625f))
                .background(Color.withAlpha(theme.color(Role.ACTION), 0.92f))
                .corner(Length.rem(0.4f)).textColor(theme.color(Role.ON_ACTION))
                .textSize(Length.rem(0.9375f))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE)
                .lit(theme.lit()).elevation(Length.rem(1f))
                .hitInert(true)
                .visible(false);
        gui.root().append(ghost);

        // Read from the *published* drag rather than the live session: the session is the framework's own state,
        // mutated on the GUI thread inside the frame, and reading it from here would be reading a value that
        // changes underneath the paint. The State commits once per frame and only on change, so a pointer held
        // still costs nothing at all.
        gui.drag().onCommit(v -> {
            DragState drag = v.value();
            if (!drag.active()) {
                ghost.visible(false);
                lightArchive(false, theme);
                return;
            }
            // Offset from the hotspot so the label never sits under the cursor itself. dp, not rem: the ghost
            // trails the pointer by a fixed distance on screen, which is not a quantity that should grow when
            // the user zooms the content.
            ghost.text(carrying.get()).visible(true)
                    .floatAt(Length.dp(drag.x() + 14f), Length.dp(drag.y() + 12f))
                    // It says what would happen, not merely what is held: the framework has already asked every
                    // target under the pointer, and the answer is in the same value that carries the position.
                    .background(Color.withAlpha(
                            theme.color(drag.accepts() ? Role.ACTION : Role.DANGER), 0.92f))
                    .textColor(theme.color(drag.accepts() ? Role.ON_ACTION : Role.ON_DANGER));
            lightArchive(over(archive, drag) && drag.accepts(), theme);
        });

        Node tools = Ui.strip(gui,
                Ui.controls(gui,
                        Ui.button(gui, "Undo", () ->
                                console.note(history.undo() ? "undo" : "nothing to undo")),
                        Ui.button(gui, "Redo", () ->
                                console.note(history.redo() ? "redo" : "nothing to redo")),
                        Ui.button(gui, "Open every column", () -> {
                            for (Task column : root.kids) {
                                board.expand(column);
                            }
                        })),
                Ui.prose(gui, "Ctrl+X, Ctrl+C and Ctrl+V move and duplicate tasks from the keyboard, and are on "
                        + "every row's menu. A paste lands inside the selected task if it can hold children and "
                        + "beside it otherwise, which is the same question the middle band of a row answers. "
                        + "Press a task and drag it. A row that can hold children divides into three bands — "
                        + "an outer quarter each side for before and after, the middle half for into — and a "
                        + "leaf divides in two, because a band that always refused would be a third of a row "
                        + "that looks live and is not. Below the last row is the top level, which is the only "
                        + "way to drag a task out of its column. Escape cancels; a flick too quick to have been "
                        + "drawn commits nothing."));

        return gui.column().width(Length.FILL).height(Length.FILL).gap(Ui.GAP)
                .children(
                        gui.row().width(Length.FILL).height(Length.FILL).gap(Ui.GAP)
                                .children(
                                        Ui.card(gui, Ui.heading(gui, "The board"), board.node()),
                                        gui.column().width(Length.rem(15)).height(Length.FILL)
                                                .children(archive)),
                        tools);
    }

    /**
     * The change that puts {@code moved} at index {@code at} under {@code parent} — or null, if that would not
     * be a change at all.
     *
     * <p>Refusing here rather than at the drop is what puts the answer in front of the user while they can still
     * act on it: no indicator means no drop, and they see that before letting go.
     */
    private Change moveTo(Task moved, Task parent, int at) {
        Task from = moved.parent;
        if (from == null) {
            return null;
        }
        int back = from.kids.indexOf(moved);
        if (from == parent && (back == at || back + 1 == at)) {
            return null;   // it would land exactly where it already is
        }
        return placing(moved, parent, at);
    }

    /**
     * The change itself, with no opinion about whether it is worth making — that was settled above, while the
     * pointer was over the seam.
     *
     * <p>Where the task came from is read when the change is <em>applied</em>, not when it is built. A drop is
     * resolved once per frame while the pointer moves and applied at most once, on release, so a change that
     * closed over the task's position at build time would be describing a board that had already moved on.
     *
     * <p>{@code apply} performs and hands back its own inverse, so nothing anywhere records a kind of change or
     * switches on one. Undo is a stack of reverses.
     */
    private Change placing(Task moved, Task parent, int at) {
        return () -> {
            Task from = moved.parent;
            int back = from == null ? -1 : from.kids.indexOf(moved);
            if (from != null) {
                from.kids.remove(moved);
            }
            archived.remove(moved);
            parent.kids.add(Math.min(at, parent.kids.size()), moved);
            moved.parent = parent;
            shown("moved \"" + moved.name + "\" into " + parent.name);
            return from == null ? archiving(moved) : placing(moved, from, back);
        };
    }

    /** Before ({@code offset} 0) or after ({@code offset} 1) a sibling. */
    private Change beside(Task placing, Task reference, int offset) {
        Task parent = reference.parent == null ? root : reference.parent;
        return put(placing, parent, parent.kids.indexOf(reference) + offset);
    }

    /**
     * Place something that is either already on the board or has just been made.
     *
     * <p>The two differ in exactly one way: a task already on the board can be asked to land where it already
     * is, and that is refused; a task that does not exist yet cannot, so nothing about its destination is a
     * no-op.
     */
    private Change put(Task placing, Task parent, int at) {
        return placing.parent == null ? placing(placing, parent, at) : moveTo(placing, parent, at);
    }

    /**
     * A new task with the same name and the same subtasks under it.
     *
     * <p>Deep, because a column copied without its cards is not a copy of that column — and because the model
     * owns what duplication means, which is the reason the framework asks rather than doing it. Built during
     * resolution, which for a drag runs once a frame: the copies for the frames the user moved through are
     * garbage, and only the one belonging to the frame they released on is ever applied.
     */
    private Task copyOf(Task task) {
        Task copy = new Task(task.name);
        for (Task kid : task.kids) {
            Task kidCopy = copyOf(kid);
            kidCopy.parent = copy;
            copy.kids.add(kidCopy);
        }
        return copy;
    }

    /** Taking a task off the board and into the archive, and putting it back exactly where it was. */
    private Change archiving(Task task) {
        return () -> {
            Task parent = task.parent;
            int at = parent.kids.indexOf(task);
            parent.kids.remove(task);
            task.parent = null;
            archived.add(task);
            shown("archived \"" + task.name + "\"");
            return placing(task, parent, at);
        };
    }

    /**
     * Every change ends here: the model has moved, so the views are told.
     *
     * <p>Two of them, and neither is the tree's business to know about. {@code refresh()} is the obligation the
     * {@code Source} split leaves with the application — the tree reads the hierarchy and is never notified when
     * it changes — and the archive is an ordinary list of nodes this chapter owns outright.
     */
    private void shown(String what) {
        board.refresh();
        redrawArchive();
        console.say(what);
    }

    /**
     * The archive list, rebuilt from the model.
     *
     * <p>Rebuilt rather than reconciled, and the difference from what {@code TreeView.refresh} does is the point:
     * these rows carry nothing — no expansion, no selection, no handlers — so there is nothing for reuse to
     * preserve, and the simplest correct thing is the right one. Reuse is worth its complexity exactly where
     * something would be lost without it.
     */
    private synchronized void redrawArchive() {
        for (Node row : archiveRows) {
            row.remove();
        }
        archiveRows.clear();
        for (Task task : archived) {
            Node row = gui.text(task.name).width(Length.FILL).height(Length.rem(1.75f))
                    .padding(Length.ZERO, Length.em(0.5f))
                    .background(gui.theme().color(Role.PANEL)).corner(Length.rem(0.3f))
                    .textSize(Length.rem(0.875f)).textColor(gui.theme().color(Role.DIM))
                    .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
            archiveList.append(row);
            archiveRows.add(row);
        }
    }

    /** Light the archive while a drag it would take is over it, and put it back when the drag leaves. */
    private void lightArchive(boolean on, Theme theme) {
        archive.background(on ? Color.withAlpha(theme.color(Role.ACCENT), 0.18f) : theme.color(Role.WELL))
                .border(Length.rem(0.15f), theme.color(on ? Role.ACCENT : Role.LINE));
    }

    private static boolean over(Node node, DragState drag) {
        Rect box = node.layout().rect();
        return box != null && drag.x() >= box.x() && drag.x() < box.x() + box.w()
                && drag.y() >= box.y() && drag.y() < box.y() + box.h();
    }

    /** A zone's own box, as the indicator for a drop onto it: the promise and the drop are one value. */
    private static Rect rectOf(Node node) {
        Rect box = node.layout().rect();
        return box == null ? Rect.ZERO : box;
    }
}
