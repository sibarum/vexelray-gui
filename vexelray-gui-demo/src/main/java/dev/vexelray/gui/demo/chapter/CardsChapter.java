package dev.vexelray.gui.demo.chapter;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.drop.DragRelease;
import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Finish;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.demo.Chapter;
import dev.vexelray.gui.demo.Console;
import dev.vexelray.gui.demo.Stage;
import dev.vexelray.gui.demo.Ui;
import dev.vexelray.gui.widget.Board;
import dev.vexelray.gui.widget.Deck;
import dev.vexelray.gui.widget.Placement;
import dev.vexelray.gui.widget.Reorder;
import dev.vexelray.text.TextLayout;
import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Cards on a board: three lanes of them, reordered and moved between lanes by dragging, or from the keyboard.
 *
 * <p>Everything the application writes is here, and it is three things. <b>What a card looks like</b> — any node;
 * the board builds one per task and moves that same node everywhere it goes. <b>What a placement means</b> — a
 * {@link Reorder} per lane returning a {@link Change}, exactly the question a tree asks, so the model is changed
 * only through edits that undo. And <b>saying what each lane holds</b> whenever the model changes — the step every
 * edit shares, which is why a drop, an undo, a shuffle, a new card and a removed one all move the same way.
 *
 * <p>Everything else is the board's: the press that becomes a drag only once it has travelled, the card lifting
 * off and following the hand from exactly where it was grabbed, the lane opening a gap where it would land, the
 * settle, Escape flying it home, and nothing anywhere jumping on the way.
 */
public final class CardsChapter implements Chapter {

    /** One task. A record with an id, so two tasks with the same title are still two tasks. */
    private record Task(int id, String title, String tag, boolean urgent) {
    }

    private static final String[] LANES = {"Backlog", "In progress", "Done"};

    /** The model: each lane's tasks, in order, changed only by {@link #set}. */
    private Map<String, List<Task>> lanes = new LinkedHashMap<>();
    private final Map<String, Deck<Task>> decks = new LinkedHashMap<>();
    private final Map<String, Node> counts = new LinkedHashMap<>();
    private int nextId;
    private History history;
    private Console console;

    @Override
    public String title() {
        return "Cards";
    }

    @Override
    public String blurb() {
        return "Pick a card up, carry it, put it down — within a lane or across to another, with the lane making "
                + "room as you go. Escape sends it home; undo brings it back the same way.";
    }

    @Override
    public Node build(Stage stage) {
        Gui gui = stage.gui();
        this.console = stage.console();
        // One history for every transfer in a window, so Ctrl+Z undoes the last thing moved whichever widget it
        // was moved in. Another chapter may have set it already; a drop and a paste land in the same stack.
        if (gui.dropHistory() == null) {
            gui.dropHistory(new History());
        }
        this.history = gui.dropHistory();

        lanes.put(LANES[0], new ArrayList<>(List.of(
                task("Sketch the onboarding flow", "design", false),
                task("Audit colour contrast", "a11y", true),
                task("Write the release notes", "docs", false),
                task("Profile the cold start", "perf", false))));
        lanes.put(LANES[1], new ArrayList<>(List.of(
                task("Cards you can carry between lanes", "ui", true),
                task("Keyboard reordering", "a11y", false))));
        lanes.put(LANES[2], new ArrayList<>(List.of(
                task("Drag a tree row", "ui", false))));

        // What a card looks like, and how long anything takes to move. The board owns the card's elevation —
        // that is how a card shows it has been picked up — and everything else about it is ours.
        Board<Task> board = new Board<Task>(gui, task -> card(gui, task))
                .motion((progress, done) -> stage.krono().ramp(Dur.ms(200), Ease.OUT_CUBIC, progress, done));

        Node row = gui.row().width(Length.FILL).height(Length.FILL).gap(Ui.GAP);
        for (String lane : LANES) {
            Deck<Task> deck = board.column().reorderable(placeIn(lane));
            // Room either side for a card's shadow: the deck scrolls, and a scroller clips to its own box.
            deck.node().width(Length.FILL).height(Length.grow(1)).padding(Length.ZERO, Length.dp(8));
            decks.put(lane, deck);
            row.append(lane(gui, lane, deck));
        }
        show();

        Node tools = Ui.strip(gui,
                Ui.controls(gui,
                        Ui.button(gui, "Undo", () -> console.note(history.undo() ? "undo" : "nothing to undo")),
                        Ui.button(gui, "Redo", () -> console.note(history.redo() ? "redo" : "nothing to redo")),
                        Ui.button(gui, "Add a card", this::addCard),
                        Ui.button(gui, "Shuffle the backlog", this::shuffleBacklog),
                        Ui.button(gui, "Clear done", this::clearDone),
                        // The user's preference, not the board's: what letting go of the button means. Every drag in
                        // the window follows it, the tree chapter's included.
                        Ui.toggle(gui, "Release keeps", "Release drops", gui.dragRelease().keeps(), keeps -> {
                            gui.dragRelease(keeps ? DragRelease.KEEP : DragRelease.DROP);
                            console.note(keeps ? "letting go keeps the card; a click puts it down"
                                    : "letting go puts the card down");
                        })),
                Ui.prose(gui, "Press a card: it sinks, held. Pull, and it comes loose and stays under your hand "
                        + "exactly where you took hold of it. Let go of the button and it stays with the pointer; "
                        + "the wheel scrolls a lane beneath it, and a click puts it down where you aim. Escape or a "
                        + "right click sends it home, scrolling the lanes back with it. Switch to Release drops for "
                        + "the convention instead: hold the button for the carry, let go to put it down. With a card "
                        + "focused, Alt+Up and Alt+Down move it within its lane and Alt+Left and Alt+Right to the "
                        + "next one. Every move is one undo, and undo moves the card back."));

        return gui.column().width(Length.FILL).height(Length.FILL).gap(Ui.GAP).children(row, tools);
    }

    // ------------------------------------------------------------------------------------------------ look

    /** A lane: a sunken well with a title, a count, and its deck. */
    private Node lane(Gui gui, String name, Deck<Task> deck) {
        Node count = gui.text("").width(Length.AUTO).height(Length.rem(1.5f))
                .textSize(Length.rem(0.8125f)).textColor(Role.FAINT)
                .align(TextLayout.HAlign.RIGHT, TextLayout.VAlign.MIDDLE);
        counts.put(name, count);
        Node header = gui.row().width(Length.FILL).height(Length.AUTO).padding(Length.ZERO, Length.dp(8))
                .children(
                        gui.text(name).width(Length.grow(1)).height(Length.rem(1.5f))
                                .textSize(Length.rem(0.9375f)).textColor(Role.DIM)
                                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE),
                        count);
        return gui.column().width(Length.grow(1)).height(Length.FILL)
                .background(Role.WELL).corner(Length.rem(0.875f))
                .border(Length.rem(0.08f), Role.LINE)
                .padding(Length.dp(10), Length.dp(4)).gap(Length.rem(0.25f))
                .children(header, deck.node());
    }

    /** A card: a lit panel with the task's title, and a tag under it. */
    private static Node card(Gui gui, Task task) {
        Role tagInk = task.urgent() ? Role.ACCENT : Role.DIM;
        Node tag = gui.text(task.tag()).width(Length.AUTO).height(Length.rem(1.25f))
                .padding(Length.ZERO, Length.rem(0.5f)).corner(Length.rem(0.625f))
                .background(tagInk.alpha(0.14f)).textColor(tagInk)
                .textSize(Length.rem(0.75f))
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE);
        return gui.column().width(Length.FILL).height(Length.AUTO)
                .background(Role.PANEL).corner(Length.rem(0.625f))
                .border(Length.rem(0.06f), Role.LINE)
                .lit(Finish.LIT)
                .padding(Length.dp(12), Length.dp(14)).gap(Length.rem(0.5f))
                .children(
                        gui.text(task.title()).width(Length.FILL)
                                .textSize(Length.rem(0.9375f)).textColor(Role.INK)
                                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP),
                        gui.row().width(Length.FILL).height(Length.AUTO).children(tag));
    }

    // ----------------------------------------------------------------------------------------------- model

    private Task task(String title, String tag, boolean urgent) {
        return new Task(nextId++, title, tag, urgent);
    }

    /** Tell every deck what its lane holds. Every edit ends here, undo included — which is why they all animate. */
    private void show() {
        for (Map.Entry<String, Deck<Task>> e : decks.entrySet()) {
            List<Task> tasks = lanes.get(e.getKey());
            e.getValue().items(tasks);
            counts.get(e.getKey()).text(Integer.toString(tasks.size()));
        }
    }

    /** The change that makes the model {@code next}; its reverse is the change back. */
    private Change set(Map<String, List<Task>> next) {
        return () -> {
            Map<String, List<Task>> prev = lanes;
            lanes = next;
            show();
            return set(prev);
        };
    }

    private Map<String, List<Task>> copy() {
        Map<String, List<Task>> next = new LinkedHashMap<>();
        for (Map.Entry<String, List<Task>> e : lanes.entrySet()) {
            next.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return next;
    }

    /** What putting a task at a placement in {@code lane} means — or null, where it would change nothing. */
    private Reorder<Task> placeIn(String lane) {
        return (moved, where, effect) -> {
            Map<String, List<Task>> next = copy();
            for (List<Task> tasks : next.values()) {
                tasks.remove(moved);
            }
            List<Task> into = next.get(lane);
            int at = into.size();
            if (where.reference() != null) {
                int ref = into.indexOf(where.reference());
                at = where.relation() == Placement.Relation.BEFORE ? ref : ref + 1;
            }
            into.add(at, moved);
            return next.equals(lanes) ? null : set(next);
        };
    }

    private void addCard() {
        Map<String, List<Task>> next = copy();
        String[] titles = {"Tidy the settings page", "Name the colour roles", "Try a denser card",
            "Measure the drag latency", "Answer the design review"};
        String[] tags = {"ui", "design", "perf", "docs", "a11y"};
        int i = nextId % titles.length;
        next.get(LANES[0]).add(0, task(titles[i], tags[i], nextId % 3 == 0));
        history.perform(set(next));
        console.note("added a card");
    }

    private void shuffleBacklog() {
        Map<String, List<Task>> next = copy();
        Collections.shuffle(next.get(LANES[0]), new Random(nextId++));
        history.perform(set(next));
        console.note("shuffled the backlog");
    }

    private void clearDone() {
        Map<String, List<Task>> next = copy();
        if (next.get(LANES[2]).isEmpty()) {
            console.note("nothing done to clear");
            return;
        }
        next.get(LANES[2]).clear();
        history.perform(set(next));
        console.note("cleared done");
    }
}
