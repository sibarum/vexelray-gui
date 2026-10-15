package dev.vexelray.gui.demo.chapter;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.edit.Change;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.edit.Outcome;
import dev.vexelray.gui.core.edit.UndoEvent;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.demo.Chapter;
import dev.vexelray.gui.demo.Console;
import dev.vexelray.gui.demo.Stage;
import dev.vexelray.gui.demo.Ui;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.Lattice;
import dev.vexelray.gui.widget.NumberField;
import dev.vexelray.gui.widget.Segment;
import dev.vexelray.gui.widget.Select;
import dev.vexelray.gui.widget.Slider;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.Toggle;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Undo: every control here undoes, and nothing on this page asked it to (docs/plans/undo.md).
 *
 * <p>The window's history is bound once, in {@code Demo.main}, the way the framework binds one on every window it
 * builds; each control then records its own edits into it. Ctrl+Z walks back through all of it in the order it
 * was done — taking the user to each control before changing it — and Ctrl+Y walks forward again. The only code on
 * this page that knows about undo is the "Add an item" button, which states its command as a {@link Change}
 * because a {@code Runnable} cannot be inverted, and the readouts.
 *
 * <p>The bottom card is the three ways an undo is refused: a control the user cannot edit (locked, so it is shown
 * and ringed), a control that has gone (removed, so the step fails once and is cleared away), and a value written
 * underneath (a sync, so taking the step back would overwrite something newer). Every control and readout has a
 * landmark, which is what {@code docs/scenes/undo.txt} drives.
 */
public final class UndoChapter implements Chapter {

    @Override
    public String title() {
        return "Undo";
    }

    @Override
    public String blurb() {
        return "Change anything, then Ctrl+Z. Every control undoes, and nothing on this page wired it.";
    }

    @Override
    public Node build(Stage stage) {
        Gui gui = stage.gui();
        Console console = stage.console();
        History bound = gui.history();
        if (bound == null) {
            bound = new History();   // built into a tree nobody gave a window history: this page brings its own
            gui.history(bound);
        }
        History history = bound;

        Toggle toggle = new Toggle(gui, false);
        Slider slider = new Slider(gui, 0f).lattice(Lattice.intervals(10));
        slider.node().width(Length.rem(10));   // a slider has no width of its own, and in a row it would have none
        NumberField number = new NumberField(gui, 1, 1);
        Segment<String> segment = new Segment<String>(gui).option("Low", "low").option("Mid", "mid")
                .option("High", "high");
        Select<String> select = new Select<String>(gui, s -> s).options(List.of("Red", "Green", "Blue"));
        select.show("Red");
        select.node().width(Length.rem(10));
        TextField text = new TextField(gui, "");
        text.node().width(Length.rem(12));
        Button chip = new Button(gui, "Bold").toggle(true);
        List<String> items = new CopyOnWriteArrayList<>(List.of("first"));
        Button add = new Button(gui, "Add an item").kind(Button.Kind.PRIMARY)
                .perform(() -> adding(items, "item " + (items.size() + 1)));

        Node values = Ui.label(gui, "", Length.FILL);
        Node status = Ui.label(gui, "", Length.FILL);
        Runnable readOut = () -> values.text(String.format(Locale.ROOT,
                "toggle=%s slider=%.1f number=%s segment=%s select=%s%s text=%s chip=%s items=%d",
                toggle.on() ? "on" : "off", slider.value(), plain(number.value()), segment.selected(),
                select.value(), select.isOpen() ? "(open)" : "", text.text().isEmpty() ? "-" : text.text(),
                chip.pressed() ? "on" : "off", items.size()));
        // The popup opens on a handler thread, a moment after the click that asked for it; a scene waits for
        // "(open)" before it presses the keys the popup claims, rather than racing it.
        gui.onResize(select.popupNode(), computed -> readOut.run());
        // And the live preview, so a scene sees the cursor land before it presses Enter: both keys are claims, run
        // on handler threads, and two of those are not ordered against each other.
        select.selection().onChange(chosen -> readOut.run());

        toggle.onChange(v -> readOut.run());
        slider.onChange(v -> readOut.run());
        number.onChange(v -> readOut.run());
        segment.onChange(v -> readOut.run());
        select.onCommit(v -> readOut.run());
        text.onChange(v -> readOut.run());
        chip.onToggle(v -> readOut.run());
        history.status().onCommit(s -> {
            readOut.run();   // a command's change has no handler of its own to say so
            status.text(depths(s.value()) + "  ·  last: " + last);
        });

        // ---- the three refusals ----------------------------------------------------------------------------

        Toggle locked = new Toggle(gui, false).undoable(false);   // locking is not an edit
        locked.onChange(on -> toggle.node().disabled(on));
        TextField scratch = new TextField(gui, "");
        scratch.node().width(Length.rem(12));
        Node scratchRow = gui.row().width(Length.FILL).gap(Ui.GAP).scroll(false, false).children(scratch.node());
        Button remove = new Button(gui, "Remove the scratch field").onPress(scratch.node()::remove);
        Button underneath = new Button(gui, "Set the number to 99 underneath").onPress(() -> {
            number.show(99);   // a sync: the model changed, nobody edited the field
            readOut.run();
        });

        // What the framework's own presenter does in an application — a ring on a refused control, a prompt for
        // the rest — done here with the gallery's console, and said in a readout a scene can wait on.
        gui.onUndo(e -> {
            last = e.outcome().name().toLowerCase(Locale.ROOT);
            status.text(depths(history.status().value()) + "  ·  last: " + last);
            report(e, stage, console);
        });

        gui.landmark("undo.toggle", toggle.node());
        gui.landmark("undo.slider", slider.node());
        gui.landmark("undo.number", number.node());
        gui.landmark("undo.segment", segment.node());
        gui.landmark("undo.select", select.node());
        gui.landmark("undo.text", text.node());
        gui.landmark("undo.chip", chip.node());
        gui.landmark("undo.add", add.node());
        gui.landmark("undo.values", values);
        gui.landmark("undo.status", status);
        gui.landmark("undo.lock", locked.node());
        gui.landmark("undo.scratch", scratch.node());
        gui.landmark("undo.remove", remove.node());
        gui.landmark("undo.underneath", underneath.node());
        readOut.run();
        status.text(depths(history.status().value()) + "  ·  last: " + last);

        Node page = gui.column().width(Length.FILL).height(Length.AUTO).gap(Ui.GAP).scroll(false, false).children(
                Ui.card(gui,
                        Ui.heading(gui, "Every control"),
                        Ui.prose(gui, "Change any of these, in any order, then press Ctrl+Z until nothing is "
                                + "left and Ctrl+Y to bring it all back. A control you are not at is shown "
                                + "first, by the press before the one that changes it."),
                        row(gui, toggle.node(), slider.node(), number.node(), segment.node()),
                        row(gui, select.node(), text.node(), chip.node(), add.node()),
                        values, status),
                Ui.card(gui,
                        Ui.heading(gui, "When an undo is refused"),
                        Ui.prose(gui, "Lock the switch, then undo a flip of it: it is shown and ringed, and not "
                                + "changed. Type in the scratch field and remove it: undoing the typing fails "
                                + "once, and the step is cleared away. Step the number, set it underneath, and "
                                + "undo: the older number is not written over the newer one."),
                        row(gui, Ui.label(gui, "Lock the switch", Length.AUTO), locked.node(),
                                underneath.node()),
                        scratchRow, remove.node()));
        gui.landmark("undo.page", page);
        return page;
    }

    /** The last outcome, for the status line. Written from handler threads; a word, so a torn read is not one. */
    private volatile String last = "none";

    private static String depths(History.Status s) {
        return "undo " + s.undoDepth() + "  ·  redo " + s.redoDepth();
    }

    private static void report(UndoEvent e, Stage stage, Console console) {
        Outcome o = e.outcome();
        String what = e.redo() ? "redo" : "undo";
        if (o == Outcome.REFUSED && e.target() != null) {
            stage.cues().play(e.target(), Cue.ring(stage.theme().color(Role.DANGER)));
            console.refused(what + " refused: that control is locked");
        } else if (o == Outcome.UNREACHABLE) {
            console.refused(what + " failed: what it changed is no longer here, so the step is cleared away");
        } else if (o == Outcome.CONFLICT) {
            console.refused(what + " refused: the value was changed underneath since");
        } else if (o == Outcome.REVEALING) {
            console.note(what + ": showing what the next press changes");
        }
    }

    /** Append {@code item}, and hand back the way home — the button's command, stated as an edit. */
    private static Change adding(List<String> items, String item) {
        return () -> {
            items.add(item);
            return () -> {
                items.remove(item);
                return adding(items, item);
            };
        };
    }

    private static Node row(Gui gui, Node... children) {
        return gui.row().width(Length.FILL).gap(Ui.GAP).alignItems(
                dev.vexelray.gui.core.layout.LayoutEnums.AlignItems.CENTER).scroll(false, false).children(children);
    }

    private static String plain(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
