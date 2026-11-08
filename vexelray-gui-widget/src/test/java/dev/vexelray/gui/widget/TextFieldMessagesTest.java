package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.input.InputTopics;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Inbox;
import sibarum.atchung.Sender;
import sibarum.atchung.Tick;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The text field on the message model (vexelray-framework's {@code docs/threading.md}, T3.8 and T3.9): typing stays
 * in the widget, what the application must know leaves as a message, and what it wants done arrives as one.
 *
 * <p>The application's side is an atchung {@link Tick}, run by the test where a framework's logic lane would run it:
 * after the GUI's frame. Nothing here touches the handler lane, and the first test says so by counting it.
 */
class TextFieldMessagesTest {

    private static final long LATER = Long.MAX_VALUE;

    private static TextField named(HeadlessGui h, String name, String initial) {
        TextField f = new TextField(h.gui, initial).name(name);
        h.gui.root().children(f.node());
        h.frame();
        h.focus(f.node());
        return f;
    }

    @Test
    void typingStaysInTheFieldAndOnlyTheFramesLastTextCrosses() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = named(h, "path", "");
            Tick tick = h.bus.tick(null);
            List<String> heard = new ArrayList<>();
            tick.inbox("editor").subscribe(TextField.CHANGED, c -> heard.add(c.text()), 1);

            "hello".codePoints().forEach(cp -> h.bus.publish(h.gui.input(), new InputEvent.CharTyped(cp, 0)));
            h.frame();

            assertEquals("hello", f.text(), "the edit is the widget's, applied in the frame, with no round trip");

            tick.run(LATER);
            assertEquals(List.of("hello"), heard,
                    "five keystrokes in one frame are one message: CHANGED is state, keyed by the field");
        }
    }

    @Test
    void enterIsAnEdgeEveryOneArrives() {
        try (HeadlessGui h = new HeadlessGui()) {
            named(h, "path", "C:/a");
            Tick tick = h.bus.tick(null);
            List<String> submitted = new ArrayList<>();
            tick.inbox("editor").subscribe(TextField.SUBMITTED, s -> submitted.add(s.field() + " " + s.text()), 16);

            h.tap(Key.ENTER);
            h.type("b");
            h.tap(Key.ENTER);
            tick.run(LATER);

            assertEquals(List.of(h.gui.address("path") + " C:/a", h.gui.address("path") + " C:/ab"), submitted);
        }
    }

    @Test
    void aCommandFromTheLogicIsAppliedInTheNextFrameAndUndoes() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = named(h, "path", "");
            Tick tick = h.bus.tick(null);
            Inbox editor = tick.inbox("editor");
            Sender<TextField.Command> fields = editor.sender(TextField.COMMANDS);
            // The logic: whatever is submitted, normalise its slashes and put it back.
            editor.subscribe(TextField.SUBMITTED, s -> fields.send(
                    new TextField.Replace(s.field(), s.text().replace('\\', '/'), TextField.ANY)), 16);

            h.type("C:\\x");
            h.tap(Key.ENTER);
            tick.run(LATER);
            assertEquals("C:\\x", f.text(), "a command is the widget's to apply, on its thread, in its frame");
            h.frame();

            assertEquals("C:/x", f.text());
            h.chord(Key.Z, Key.LEFT_CONTROL);
            assertEquals("C:\\x", f.text(), "a Replace is an edit, so the person can undo what the logic did");
        }
    }

    @Test
    void aReplaceComputedFromOldTextIsDroppedAndTheFieldSaysWhatItSaysNow() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = named(h, "query", "");
            Tick tick = h.bus.tick(null);
            Inbox completer = tick.inbox("completer");
            Sender<TextField.Command> fields = completer.sender(TextField.COMMANDS);
            List<String> heard = new ArrayList<>();
            // The logic: complete "ap" to "apple", from the text and version it was told.
            completer.subscribe(TextField.CHANGED, c -> {
                heard.add(c.text());
                if (c.text().equals("ap")) {
                    fields.send(new TextField.Replace(c.field(), "apple", c.version()));
                }
            }, 1);

            h.type("ap");
            tick.run(LATER);          // the logic proposes "apple", computed from "ap"
            h.type("r");              // the person kept typing before the proposal was applied
            h.frame();                // the proposal arrives: "ap" is no longer what the field says

            assertEquals("apr", f.text(), "the person's keystroke wins over a completion of text they had moved past");
            tick.run(LATER);
            assertEquals(List.of("ap", "apr"), heard,
                    "and the logic is told what the field says now, so it can compute from that instead");
        }
    }

    @Test
    void whatOneLogicTickWritesAppearsInOneFrame() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField first = named(h, "first", "");
            TextField last = new TextField(h.gui, "").name("last");
            h.gui.root().children(first.node(), last.node());
            h.frame();
            Tick tick = h.bus.tick(null);
            Inbox form = tick.inbox("form");
            Sender<TextField.Command> fields = form.sender(TextField.COMMANDS);
            form.subscribe(TextField.SUBMITTED, s -> {
                fields.send(new TextField.SetText(h.gui.address("first"), "Ada"));
                fields.send(new TextField.SetText(h.gui.address("last"), "Lovelace"));
            }, 16);

            h.bus.publish(TextField.SUBMITTED, new TextField.Submitted("name", "Ada Lovelace"));
            tick.run(LATER);
            assertEquals("", first.text());
            assertEquals("", last.text(), "nothing yet: the frame has not run");

            h.frame();
            assertEquals("Ada", first.text());
            assertEquals("Lovelace", last.text(), "both, in the same frame: a tick reaches the GUI whole");
        }
    }

    @Test
    void aCommandForAnotherFieldIsNotThisOnes() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField path = named(h, "path", "a");
            TextField search = new TextField(h.gui, "b").name("search");
            h.gui.root().children(path.node(), search.node());
            h.frame();

            h.bus.publish(TextField.COMMANDS, new TextField.SetText(h.gui.address("search"), "found"));
            h.frame();

            assertEquals("a", path.text());
            assertEquals("found", search.text());
        }
    }
}
