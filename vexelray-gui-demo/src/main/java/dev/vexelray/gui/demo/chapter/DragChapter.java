package dev.vexelray.gui.demo.chapter;

import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.drop.DragState;
import dev.vexelray.gui.core.drop.Drop;
import dev.vexelray.gui.core.drop.DropEffect;
import dev.vexelray.gui.core.drop.PayloadType;
import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.scroll.Scrollbars;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.demo.Chapter;
import dev.vexelray.gui.demo.Console;
import dev.vexelray.gui.demo.Stage;
import dev.vexelray.gui.demo.Ui;
import dev.vexelray.gui.widget.Placement;
import dev.vexelray.gui.widget.TreeView;
import dev.vexelray.text.TextLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Drag and drop, on something worth dragging: a project outline whose tasks are moved between columns, ordered
 * within one, promoted out to the top level, or dropped on a target that is not a tree at all.
 *
 * <p><b>The container is the drag.</b> Dragging is not something added to the rows; it is what a
 * {@link TreeView} does once it is told what a placement means, the way a {@code Board} is in the Cards chapter.
 * The container knows how its layout answers a drag — a tree draws a line between two rows and leaves them where
 * they are; a board opens a gap and carries the card itself — so it registers the source and the target, draws the
 * indicator and draws what is in the hand. This chapter writes none of that. It says what the tree shows, what a
 * placement means, and when the model changed; the archive is the one thing here that is not a container, and so
 * the one place this chapter registers a drop target of its own.
 *
 * <p>Under the container, the framework supplies what makes a drag a drag: the travel-and-hold threshold that keeps
 * an ordinary click an ordinary click, the flick too quick to have been drawn committing nothing, Escape
 * cancelling, and the focus move that keeps Ctrl+Z pointed at what was just dropped.
 *
 * <p><b>The split is the whole design.</b> A tree is told its hierarchy a level at a time, so it can say
 * where a drop would land <em>in the tree's own terms</em> — before this row, into that branch, out to the root —
 * but it cannot say what putting it there does, because that is a mutation of a model it can only read. So the
 * tree resolves a placement, refuses in the frame what it can see is no placement (a task into itself, one left
 * exactly where it is), and announces the rest; the application makes it a {@link Change} in its own history. That
 * the tree refuses in the frame is what makes the indicator disappear <em>before</em> the user releases rather than
 * the drop being swallowed afterwards.
 *
 * <p>And the third part, which the application also owns: <b>saying when the model changed</b>. The tree is never
 * told, so every change here ends in a {@code TreeView.REFRESH}. Undo works the same way, because the inverse change is a
 * change like any other.
 */
public final class DragChapter implements Chapter {

    /** The board's name on the bus: what its requests carry, and what this answers to. */
    private static final String BOARD = "board";

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

    private final List<Node> archiveRows = new ArrayList<>();

    private Gui gui;
    private Node archive;
    private Node archiveList;
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
        this.console = stage.console();

        // The tree asks for each level and this answers it. Inline, on the thread that asks, which is the GUI thread:
        // the model is in memory and is changed there, by the drops below, so it is read where it is written. A model
        // behind a disk answers on a thread of its own (the Trees chapter). Subscribed before the tree is built,
        // because building it is what asks for the columns.
        gui.bus().subscribe(TreeView.CHILDREN_WANTED, wanted -> {
            if (!wanted.tree().equals(gui.address(BOARD))) {
                return;
            }
            Task parent = (Task) wanted.parent();
            List<TreeView.Entry> level = new ArrayList<>();
            for (Task task : (parent == null ? root : parent).kids) {
                // Anything on this board can hold anything else — a column holds tasks, a task holds subtasks — and,
                // crucially, that stays true of a column the user has just emptied. Answering this with whether it
                // has children is what makes the last card out of a column the last card ever to go into it.
                level.add(new TreeView.Entry(task, task.name, null, !task.kids.isEmpty(), true));
            }
            gui.bus().publish(TreeView.CHILDREN, new TreeView.Children(wanted.tree(), parent, level));
        });
        board = new TreeView<>(gui, BOARD);
        board.node().width(Length.FILL).height(Length.FILL);
        // A board whose columns are shut is a board with nothing to drag, so they are opened from the start: when
        // the columns land, and each one's tasks a frame after that.
        for (Task column : root.kids) {
            board.expand(column);
        }
        // Opening a column slides the rows below it down rather than teleporting them, the first opening included:
        // the tasks land after the columns, and arriving is a change like any other. A capture plays it to rest. A
        // reordered row still lands at once, which is deliberate: the whole board settles on the same frame, and half
        // a view animating while the other half snaps is worse than either (see the Motion chapter).
        board.motion((progress, done) ->
                stage.krono().ramp(sibarum.kronometer.Dur.ms(180), sibarum.kronometer.anim.Ease.OUT_CUBIC,
                        progress, done));

        // Moves and copies, by drag and by Ctrl+X, Ctrl+C and Ctrl+V. The tree decides in the frame which placements
        // it offers, from what it can see (a task into itself, a move that changes nothing) and from what each entry
        // accepts; it announces where one lands, and what that means is ours, in the model's own terms, recorded in
        // our history so it undoes.
        board.reorderable(DropEffect.MOVE, DropEffect.COPY);
        gui.mailbox(TreeView.PLACED, board.address(), placed -> {
            Change change = placement((Task) placed.item(), placed.where(), placed.effect());
            if (change != null) {
                history.perform(change);
            }
        });

        // A target that is not a tree: an ordinary box, a drop target, and a subscription to the published drag
        // so it can light up. The payload type is the tree's own, which is what stops two unrelated trees in one
        // window from silently accepting each other's rows.
        PayloadType<Task> taskType = board.itemType();
        archiveList = gui.column().width(Length.FILL).gap(Length.rem(0.25f));
        Node archived = Scrollbars.vertical(gui, archiveList).node().width(Length.FILL).height(Length.grow(1));
        archive = gui.column().width(Length.FILL).height(Length.FILL)
                .background(Role.WELL).corner(Length.rem(0.75f))
                .border(Length.rem(0.15f), Role.LINE)
                .padding(Length.dp(14)).gap(Length.rem(0.375f))
                .children(
                        gui.text("Archive").width(Length.FILL).height(Length.rem(1.5f))
                                .textSize(Length.rem(1)).textColor(Role.INK)
                                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE),
                        gui.text("drop a task here to take it off the board")
                                .width(Length.FILL)
                                .textSize(Length.rem(0.875f)).textColor(Role.FAINT)
                                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP),
                        archived);
        Seams.of(gui).onDrop(archive, (payload, x, y) -> payload.as(taskType)
                .map(task -> task.parent == null ? Drop.NONE
                        : Drop.move(rectOf(archive), () -> history.perform(archiving(task))))
                .orElse(Drop.NONE));

        // Nothing here draws the task in the hand: the tree carries its own rows, wherever they are carried, the
        // archive included — what a row looks like in flight is the tree's business, as the indicator between two
        // rows is. What is the chapter's is the archive answering, so it lights while a task over it would land.
        //
        // That is drawing *about* a drag, so it reads the published drag: once per frame, only on change, a frame
        // behind the pointer, which nobody can see on a box lighting up. The drag is the window's, not this
        // chapter's — a card picked up in the Cards chapter is published here too — so the archive lights only for
        // a drag whose payload is one of the tree's tasks.
        Seams.of(gui).drag().onCommit(v -> {
            DragState drag = v.value();
            boolean task = drag.active() && drag.payload().as(taskType).isPresent();
            lightArchive(task && over(archive, drag) && drag.accepts());
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
                        + "way to drag a task out of its column. Let go of the button and the task stays with the "
                        + "pointer; a click puts it down. Escape or a right click cancels; a flick too quick "
                        + "to have been drawn commits nothing."));

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
     * What putting {@code moved} at {@code where} with {@code effect} means here, or null where it means nothing.
     * The tree has already refused what it can see; what is left is the model's own say.
     */
    private Change placement(Task moved, Placement<?> where, DropEffect effect) {
        // A copy has nothing to refuse about where it came from: it is a new task, so it can land inside the one it
        // was copied from, and landing where the original already sits is not a no-op. So the item being placed is
        // decided first, and every question below is asked about *that* item.
        Task placing = effect == DropEffect.COPY ? copyOf(moved) : moved;
        if (where.isRoot()) {
            return put(placing, root, root.kids.size());
        }
        Task reference = (Task) where.reference();
        return switch (where.relation()) {
            case INTO -> put(placing, reference, reference.kids.size());
            case BEFORE -> beside(placing, reference, 0);
            case AFTER -> beside(placing, reference, 1);
        };
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
     * <p>Two of them, and neither is the tree's business to know about. {@code REFRESH} is the obligation the
     * request-and-reply split leaves with the application — the tree asks for the hierarchy and is never told when
     * it changes — and the archive is an ordinary list of nodes this chapter owns outright.
     */
    private void shown(String what) {
        gui.bus().publish(TreeView.REFRESH, new TreeView.Refresh(board.address()));
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
                    .padding(Length.ZERO, Length.rem(0.5f))
                    .background(Role.PANEL).corner(Length.rem(0.3f))
                    .textSize(Length.rem(0.875f)).textColor(Role.DIM)
                    .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
            archiveList.append(row);
            archiveRows.add(row);
        }
    }

    /** Light the archive while a drag it would take is over it, and put it back when the drag leaves. */
    private void lightArchive(boolean on) {
        archive.background(on ? Role.ACCENT.alpha(0.18f) : Role.WELL)
                .border(Length.rem(0.15f), on ? Role.ACCENT : Role.LINE);
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
