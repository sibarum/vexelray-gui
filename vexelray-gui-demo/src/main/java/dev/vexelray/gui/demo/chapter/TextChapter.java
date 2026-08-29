package dev.vexelray.gui.demo.chapter;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.edit.History;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.core.text.Span;
import dev.vexelray.gui.demo.Chapter;
import dev.vexelray.gui.demo.Console;
import dev.vexelray.gui.demo.Stage;
import dev.vexelray.gui.demo.Ui;
import dev.vexelray.gui.widget.Cue;
import dev.vexelray.gui.widget.Cues;
import dev.vexelray.gui.widget.Ramp;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.canvas.Color;
import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;

import java.util.List;

/**
 * Editing: a real multiline field, the spans that survive editing, the history behind Ctrl+Z, and the find bar
 * that floats over the text without reflowing a line of it.
 *
 * <p>The reason this is chapter one is that a text field is where a GUI framework's claims get tested. It needs
 * focus, key routing, character input, a caret with a sticky column, wrapping, selection, a clipboard, an undo
 * stack, and a second widget searching it — and every one of those has to be reached through the same input
 * pipeline as a button click, or the field is a special case with a private door into the framework.
 */
public final class TextChapter implements Chapter {

    private static final String BODY = """
            Built through Node handles, mutated by messages, laid out by flex — no hard-coded rects anywhere in \
            this window, including this field.

            This paragraph is editable. It wraps at the card's width, Enter starts a new line, and Up/Down keep \
            your column across the short ones. Keep typing and the view follows the caret; the field is a fixed \
            box that scrolls internally rather than one that grows.

            Ctrl+F floats a find bar over the top of this field without reflowing a line of it: typing counts \
            every match and washes the ones you are not on, Enter and Shift+Enter step through them, and Escape \
            leaves the caret on the one you stopped at.

            Ctrl+Z and Ctrl+Y walk the history beside the field. Watch the counter under it — undo is a stack of \
            reverses, and "clean" is a position in that stack rather than a flag, which is why saving in the \
            middle and then undoing back to it says clean again.""";

    @Override
    public String title() {
        return "Text";
    }

    @Override
    public String blurb() {
        return "A multiline editor: wrapping, line numbers, formatting spans that follow their text through "
                + "edits, an undo history, and a find bar that costs the text no reflow.";
    }

    @Override
    public Node build(Stage stage) {
        Gui gui = stage.gui();
        Theme theme = stage.theme();
        Console console = stage.console();

        TextField notes = new TextField(gui, BODY).multiline(true).wordWrap(true).lineNumbers(true);
        notes.node().width(Length.FILL).height(Length.FILL);
        // Formatting spans, set once and never touched again. The point is what happens next: type before or
        // inside one and it follows its text, because every edit remaps every span through its own diff. Nothing
        // re-runs a highlighter here — the spans are not recomputed at all, which is the difference between a
        // framework that supports syntax highlighting and one that supports a highlighter being written.
        notes.setSpans(List.of(
                Span.foreground(14, 26, theme.color(Role.ACCENT)),      // "Node handles"
                Span.underline(39, 47),                                 // "messages"
                Span.background(61, 65, theme.color(Role.LINE))));      // "flex"

        // The history is the field's own. Reading it is a subscription rather than a poll: the status commits
        // when it changes and not once per frame, so a counter that is right is also a counter that is free.
        History history = notes.history();
        Node counter = Ui.label(gui, "", Length.FILL);
        java.util.function.Consumer<History.Status> show = s -> {
            counter.text(String.format("undo %d  ·  redo %d  ·  %s",
                    s.undoDepth(), s.redoDepth(), s.clean() ? "clean" : "modified"));
            counter.textColor(theme.color(s.clean() ? Role.FAINT : Role.DIM));
        };
        history.status().onCommit(v -> show.accept(v.value()));
        // Subscribing says what happens *next*; the counter also has to be right before anything has happened,
        // which is a read of the value the state already holds. A subscription that also replayed its current
        // value on registration would remove this line, and would mean every observer had to be ready to be
        // called during its own construction.
        show.accept(history.status().value());

        // A single-line field, and the three moments a field has to be able to report. Acceptance is a scanline
        // sweeping the field that took the command; a write the user did not cause is a wash, because that is
        // the case with nothing for them to have watched; refusal is a ring, and it gets longer than the other
        // two because it is asking to be read rather than merely noticed.
        Cues cues = stage.cues();
        Cue accepted = Cue.scanline(theme.color(Role.ACCENT));
        Cue written = Cue.wash(Color.withAlpha(theme.color(Role.ACCENT), 0.5f));
        Cue rejected = Cue.ring(theme.color(Role.DANGER))
                .with(Cue.wash(Color.withAlpha(theme.color(Role.DANGER), 0.45f)));
        Ramp insistent = (progress, done) -> stage.krono().ramp(Dur.ms(650), Ease.LINEAR, progress, done);

        TextField line = new TextField(gui, "type here, Enter to log — long text scrolls and is masked at the edge");
        line.node().width(Length.grow(1));
        line.setSpans(List.of(
                Span.foreground(0, 4, theme.color(Role.ACCENT)),        // "type"
                Span.background(5, 9, theme.color(Role.LINE)),          // "here"
                Span.underline(11, 16)));                               // "Enter"
        line.onSubmit(s -> {
            if (s.isBlank()) {
                cues.play(line.node(), rejected, insistent);
                console.refused("nothing to submit");
                return;
            }
            cues.play(line.node(), accepted);
            console.good("submitted: " + s);
        });

        Node editor = Ui.card(gui,
                Ui.heading(gui, "Editor"),
                notes.node(),
                counter,
                Ui.rule(gui),
                Ui.controls(gui,
                        Ui.label(gui, "Submit:", Length.rem(4.5f)),
                        line.node(),
                        Ui.button(gui, "Fill it in", () -> {
                            // The programmatic write: the case the user did not cause, and therefore the one
                            // that most needs saying out loud. The field changes under them with nothing for
                            // them to have been watching, and the cue is what turns that into a visible event.
                            line.text("written from somewhere else");
                            cues.play(line.node(), written);
                            console.note("wrote into the field from a handler");
                        })));

        Node tools = Ui.strip(gui, Ui.controls(gui,
                Ui.button(gui, "Undo", () -> {
                    console.note(history.undo() ? "undo" : "nothing to undo");
                }),
                Ui.button(gui, "Redo", () -> {
                    console.note(history.redo() ? "redo" : "nothing to redo");
                }),
                Ui.button(gui, "Mark saved", () -> {
                    history.mark();
                    console.good("marked clean at this point in the stack");
                }),
                Ui.toggle(gui, "Wrap: on", "Wrap: off", true, on -> {
                    // Wrap versus horizontal scroll, on the same field. Turning wrap off makes the node report
                    // content wider than its box, and that is what grows a horizontal scrollbar — a text leaf is
                    // a scroll citizen like any container. A wrapped node never scrolls sideways, because there
                    // is nothing to the right of a wrapped line to reach.
                    notes.wordWrap(on);
                    console.note("word wrap " + (on ? "on" : "off"));
                }),
                Ui.toggle(gui, "Numbers: on", "Numbers: off", true, notes::lineNumbers)));

        return gui.column().width(Length.FILL).height(Length.FILL).gap(Ui.GAP)
                .children(editor, tools);
    }
}
