package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.text.Span;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ctrl+F on a multiline field: a bar that floats over the top of the text without reflowing a line of it, a search
 * that is a pure function of a document already in memory, and an answer that is every match — the one you are on
 * selected, the rest washed, and all of them counted.
 *
 * <p>The text is {@code "alpha beta\nbeta gamma\ndelta beta"}: "beta" occurs at 6, 11 and 28, which is what every
 * offset asserted below is measured against.
 */
class TextFieldFindTest {

    private static final String TEXT = "alpha beta\nbeta gamma\ndelta beta";

    @Test
    void ctrlFOpensTheBarAndTypingSelectsTheFirstMatch() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, TEXT);

            assertNull(f.finder(), "a field nobody has searched has not even built a bar");
            h.chord(Key.F, Key.LEFT_CONTROL);
            h.frame();
            assertNotNull(f.finder(), "the chord built it");
            assertTrue(h.retained(f.finder().node()).visible(), "and put it up");

            h.type("beta");
            h.frame();

            // The caret started at the end of the text, so the first answer is round the wrap: a search runs from
            // where the user is looking, and past the last match it comes back to the top.
            assertEquals("beta", f.document().value().selectedText());
            assertEquals(6, f.document().value().selectionStart(), "the first occurrence, reached by wrapping");
            assertEquals("1 of 3", status(h, f), "and the field can afford to say how many others there are");
            f.close();
        }
    }

    /** Enter walks the matches forwards, Shift+Enter backwards, and both come round the ends. */
    @Test
    void enterStepsForwardAndShiftEnterStepsBack() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = find(h, TEXT, "beta");
            assertEquals(6, f.document().value().selectionStart());

            h.tap(Key.ENTER);
            h.frame();
            assertEquals(11, f.document().value().selectionStart(), "Enter steps past the current match");
            assertEquals("2 of 3", status(h, f));

            h.tap(Key.ENTER);
            h.frame();
            assertEquals(28, f.document().value().selectionStart());

            h.tap(Key.ENTER);
            h.frame();
            assertEquals(6, f.document().value().selectionStart(), "and past the last one, round to the first");

            h.chord(Key.ENTER, Key.LEFT_SHIFT);
            h.frame();
            assertEquals(28, f.document().value().selectionStart(),
                    "Shift+Enter steps back, which from the first match is round to the last");
            assertEquals("3 of 3", status(h, f));
            f.close();
        }
    }

    /**
     * Every match is marked and only one is selected — and the marks are added where the document is mirrored onto
     * the node, so the application's own spans are still there underneath. A highlighter and a find bar are two
     * statements about the same text, and neither is allowed to be the other's absence.
     */
    @Test
    void theMatchesYouAreNotOnCarryAWashAndTheApplicationsSpansSurvive() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, TEXT);
            f.setSpans(List.of(Span.underline(0, 5)));   // "alpha", underlined by whatever the application is
            find(h, f, "beta");

            RetainedNode node = h.retained(f.node());
            assertEquals(6, node.selectStart(), "the match the caret is on is the selection");
            assertEquals(10, node.selectEnd());

            List<Span> spans = node.spans();
            assertEquals(1, spans.stream().filter(Span::underline).count(), "the application's span is untouched");
            List<Span> washes = spans.stream().filter(s -> s.bg() != null).toList();
            assertEquals(2, washes.size(), "the other two matches are washed; the selected one wears the selection");
            assertEquals(11, washes.get(0).start());
            assertEquals(28, washes.get(1).start());

            // And the document was never told about any of it: the washes are the widget's, not its content's.
            assertEquals(1, f.document().value().spans().size(), "the document carries only the application's span");
            f.close();
        }
    }

    /** Escape shuts the bar and leaves the match selected, so find-then-type is a replace nobody had to build. */
    @Test
    void escapeShutsTheBarAndHandsBackAFieldWithTheMatchSelected() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = find(h, TEXT, "beta");

            h.tap(Key.ESCAPE);
            h.frame();
            assertFalse(h.retained(f.finder().node()).visible(), "the bar is away");
            assertTrue(h.retained(f.node()).spans().isEmpty(), "and the washes went with it");

            h.type("BETA");
            h.frame();
            assertEquals("alpha BETA\nbeta gamma\ndelta beta", f.text(),
                    "the keyboard came back to the field, with the caret on the answer");
            f.close();
        }
    }

    /**
     * An edit under an open bar re-answers the search. Nothing is invalidated because nothing is stored: the
     * matches are read from the text and the query every time the field mirrors, so there is no list to go stale
     * and no highlight left behind on characters that moved.
     */
    @Test
    void editingUnderAnOpenBarRecountsWithoutStaleHighlights() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = find(h, TEXT, "beta");
            assertEquals("1 of 3", status(h, f));

            h.focus(f.node());          // back to the text, bar still up
            h.tap(Key.RIGHT);           // collapse the selection past the match
            h.type("beta");
            h.frame();

            assertEquals("alpha beta\nbetabeta gamma\ndelta beta", f.text());
            assertEquals("4 matches", status(h, f),
                    "one more match, and the caret is on none of them, so there is nothing to be fourth of");
            assertEquals(4, h.retained(f.node()).spans().size(),
                    "every one of them washed, including the one the typing just made");
            f.close();
        }
    }

    /**
     * The bar floats <em>over</em> a scrolling field and is hit everywhere it draws.
     *
     * <p>Which is the whole reason a float is not subject to the flow's clip: the mask that hides scrolled text is
     * inset by the padding, the line-number gutter and the scrollbar strip, and a bar trimmed by those is chrome
     * cut down by facts about the content underneath it. Clicking near its left edge — well inside the gutter,
     * outside the text viewport — must reach the query field.
     */
    @Test
    void theBarIsPlacedAndHitOverTheGutterOfAScrollingField() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = new TextField(h.gui, "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight")
                    .multiline(true).lineNumbers(true);
            f.node().width(Length.vw(50)).height(Length.rem(4));   // 400x64: six lines of eight fit, so it scrolls
            h.gui.root().children(f.node());
            h.frame();
            h.focus(f.node());
            h.chord(Key.F, Key.LEFT_CONTROL);
            h.frame();

            RetainedNode field = h.retained(f.node());
            RetainedNode bar = h.retained(f.finder().node());
            assertTrue(field.overflowY, "the field scrolls, so its children are clipped to the text area");
            assertEquals(field.w, bar.w, 0.01f, "and the bar spans the whole box regardless");
            assertEquals(field.y, bar.y, 0.01f, "seated at the top of it");
            assertTrue(bar.x < field.viewX, "with its left edge inside the gutter the text starts after");

            RetainedNode query = h.retained(f.finder().queryNode());
            h.click(query.x + 5f, query.y + query.h / 2f);
            h.type("q");
            h.frame();

            assertEquals("q", f.finder().query(), "the click reached the bar, not the text under it");
            assertEquals("one\ntwo\nthree\nfour\nfive\nsix\nseven\neight", f.text(), "and nothing was typed into it");
            f.close();
        }
    }

    /**
     * A shut bar is not a Tab stop, even though its query field is a child of the field that searches with it.
     * Without that, traversing out of an editor lands the caret in a field nobody can see and every keystroke
     * after it disappears.
     *
     * <p>Traversal here is Shift+Tab, because Tab is the multiline field's own indent — and Shift+Tab is left
     * unclaimed for exactly this reason: an editor that took both would be a room with no door.
     */
    @Test
    void aShutBarIsNotSomethingTabCanReach() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = find(h, TEXT, "beta");
            h.tap(Key.ESCAPE);
            h.frame();

            h.chord(Key.TAB, Key.LEFT_SHIFT);   // the field is the one stop, so this comes round to it again
            h.frame();
            h.chord(Key.TAB, Key.LEFT_SHIFT);
            h.frame();
            h.type("!");
            h.frame();

            assertEquals("alpha !\nbeta gamma\ndelta beta", f.text(), "the keyboard never left the field");
            assertEquals("beta", f.finder().query(), "and the bar it cannot see was not typed into");
            f.close();
        }
    }

    /** A single-line field is not searched, which is also what stops a bar being a field being a bar for ever. */
    @Test
    void aSingleLineFieldHasNoBarAtAll() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = new TextField(h.gui, TEXT);
            f.node().width(Length.vw(50));
            h.gui.root().children(f.node());
            h.frame();
            h.focus(f.node());

            h.chord(Key.F, Key.LEFT_CONTROL);
            h.frame();
            assertNull(f.finder(), "no bar, so no query field, so no bar under that one");
            f.close();
        }
    }

    /** Ctrl+F with the caret already in the bar selects the query instead of opening a second one. */
    @Test
    void ctrlFInsideTheBarSelectsWhatIsThereToBeReplaced() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = find(h, TEXT, "beta");

            h.chord(Key.F, Key.LEFT_CONTROL);
            h.frame();
            h.type("gamma");
            h.frame();

            assertEquals("gamma", f.finder().query(), "the query was selected, so what was typed replaced it");
            assertEquals(16, f.document().value().selectionStart(), "and searching for it moved the field");
            f.close();
        }
    }

    /** Ctrl+F with a word selected searches for that word: one gesture, and the user has said which. */
    @Test
    void ctrlFSeedsItselfFromTheSelection() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField f = field(h, TEXT);
            f.select(22, 27);   // "delta"
            h.frame();

            h.chord(Key.F, Key.LEFT_CONTROL);
            h.frame();

            assertEquals("delta", f.finder().query());
            assertEquals("1 of 1", status(h, f), "seeded and answered in the one chord");
            assertEquals(22, f.document().value().selectionStart(), "on the occurrence it was seeded from");
            f.close();
        }
    }

    // --- helpers ---

    /** A focused multiline field, wide and tall enough for a bar to be a usable strip across the top of it. */
    private static TextField field(HeadlessGui h, String text) {
        TextField f = new TextField(h.gui, text).multiline(true);
        f.node().width(Length.vw(50)).height(Length.rem(10));
        h.gui.root().children(f.node());
        h.frame();
        h.focus(f.node());
        return f;
    }

    private static TextField find(HeadlessGui h, String text, String query) {
        TextField f = field(h, text);
        find(h, f, query);
        return f;
    }

    /** Open the bar and type a query into it — the whole gesture, as the user performs it. */
    private static void find(HeadlessGui h, TextField f, String query) {
        h.chord(Key.F, Key.LEFT_CONTROL);
        h.frame();
        h.type(query);
        h.frame();
    }

    private static String status(HeadlessGui h, TextField f) {
        return h.retained(f.finder().statusNode()).textString();
    }
}
