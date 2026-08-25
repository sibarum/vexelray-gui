package dev.vexelray.gui.widget;

import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ctrl+F on the tree: a bar that is not there until it is asked for, a search that walks the hierarchy fetching
 * what it has not seen, and an answer that is one row — revealed, selected, and reached without unfolding
 * everything the walk passed on the way.
 */
class TreeFindTest {

    private static final Map<String, List<String>> KIDS = Map.of(
            "src", List.of("main", "test"),
            "main", List.of("App.java", "Util.java"),
            "test", List.of("AppTest.java"),
            "docs", List.of());

    private static final class MapSource implements TreeView.Source<String> {
        final AtomicInteger fetches = new AtomicInteger();

        @Override
        public List<String> roots() {
            return List.of("src", "docs");
        }

        @Override
        public String label(String item) {
            return item;
        }

        @Override
        public boolean hasChildren(String item) {
            return KIDS.containsKey(item);
        }

        @Override
        public List<String> children(String item) {
            fetches.incrementAndGet();
            return KIDS.getOrDefault(item, List.of());
        }
    }

    /** The whole gesture: the chord opens the bar, and what is typed into it moves the tree. */
    @Test
    void ctrlFOpensTheBarAndTypingRevealsTheFirstMatch() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = tree(h);

            assertFalse(shown(h, tree), "no bar until it is asked for");
            tree.focus();
            h.chord(Key.F, Key.LEFT_CONTROL);
            h.frame();
            assertTrue(shown(h, tree), "the chord opened it");

            h.type("util");
            h.frame();

            assertEquals("Util.java", tree.selected(),
                    "the match was found two levels down, in subtrees that had never been opened");
            assertEquals(List.of("src", "main", "App.java", "Util.java", "test", "docs"), visibleRows(h, tree),
                    "and the path to it was opened, so the row the tree selected is one the user can see");
            assertEquals("", status(h, tree), "nothing to report but the answer");
            tree.close();
        }
    }

    /**
     * The property that makes searching a lazy tree bearable to look at: the walk fetches every subtree it passes
     * and opens none of them. Knowing what is under a row and showing it are two different things, and a search
     * that confused them would leave the whole hierarchy unfolded behind the answer.
     *
     * <p>Searching for the last root proves it: reaching it means walking all of the first one, and the answer has
     * no ancestors to reveal — so a tree that opened anything opened it for no reason.
     */
    @Test
    void theSearchFetchesWhatItWalksThroughWithoutOpeningIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource();
            TreeView<String> tree = tree(h, source);
            find(h, tree, "docs");

            assertEquals("docs", tree.selected());
            assertEquals(3, source.fetches.get(), "it walked the whole of src to get there, fetching every level");
            assertEquals(List.of("src", "docs"), visibleRows(h, tree), "and left all of it shut");
            tree.close();
        }
    }

    /** Enter is the same search resumed past the current match, and past the last one it comes round again. */
    @Test
    void enterFindsTheNextMatchAndWrapsAround() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = tree(h);
            find(h, tree, "app");
            assertEquals("App.java", tree.selected(), "the first match in document order");

            h.tap(Key.ENTER);
            h.frame();
            assertEquals("AppTest.java", tree.selected(), "Enter steps past it to the next");

            h.tap(Key.ENTER);
            h.frame();
            assertEquals("App.java", tree.selected(), "and past the last one, round to the first");
            tree.close();
        }
    }

    /**
     * Shift+Enter steps the other way, which for a hierarchy means the last match the forward walk passed before
     * reaching where the selection is — document order being a property of walking forwards, there is no other way
     * to know what "before" means.
     */
    @Test
    void shiftEnterStepsBackAndWrapsTheOtherWay() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = tree(h);
            find(h, tree, "app");
            assertEquals("App.java", tree.selected(), "the first match in document order");

            h.chord(Key.ENTER, Key.LEFT_SHIFT);
            h.frame();
            assertEquals("AppTest.java", tree.selected(),
                    "nothing matched before it, so back from the first is round to the last");

            h.chord(Key.ENTER, Key.LEFT_SHIFT);
            h.frame();
            assertEquals("App.java", tree.selected(), "and back again to the one before it");
            tree.close();
        }
    }

    /** A miss says so, and leaves the tree exactly as it found it. */
    @Test
    void aQueryThatMatchesNothingSaysSoAndMovesNothing() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = tree(h);
            find(h, tree, "zzz");

            assertEquals("No match", status(h, tree), "the search reached the end of the hierarchy and said so");
            assertEquals(List.of("src", "docs"), visibleRows(h, tree), "nothing was opened to report nothing");
            tree.close();
        }
    }

    /** Escape puts the bar away and hands the keyboard back, with the tree where the search left it. */
    @Test
    void escapeShutsTheBarAndGivesTheKeyboardBackToTheTree() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = tree(h);
            find(h, tree, "util");

            h.tap(Key.ESCAPE);
            h.frame();
            assertFalse(shown(h, tree), "the bar is away");

            h.tap(Key.UP);
            h.frame();
            assertEquals("App.java", tree.selected(),
                    "and the arrow keys drive the tree again, from the row the search landed on");
            tree.close();
        }
    }

    /**
     * A shut bar is not a Tab stop. Without that, Tab takes the caret into a field nobody can see, and every
     * keystroke after it disappears.
     */
    @Test
    void theShutBarIsNotSomethingTabCanReach() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = tree(h);

            h.tap(Key.TAB);    // the tree: its one stop
            h.frame();
            h.tap(Key.TAB);    // round to it again — the bar it contains is not one
            h.frame();
            h.tap(Key.DOWN);
            h.frame();

            assertEquals("src", tree.selected(), "the keyboard never left the tree");
            tree.close();
        }
    }

    // --- helpers ---

    private static TreeView<String> tree(HeadlessGui h) {
        return tree(h, new MapSource());
    }

    private static TreeView<String> tree(HeadlessGui h, MapSource source) {
        TreeView<String> tree = new TreeView<>(h.gui, source);
        h.gui.root().children(tree.node());
        h.frame();
        return tree;
    }

    /** Open the bar and type a query into it — the whole gesture, as the user performs it. */
    private static void find(HeadlessGui h, TreeView<String> tree, String query) {
        tree.focus();
        h.chord(Key.F, Key.LEFT_CONTROL);
        h.frame();
        h.type(query);
        h.frame();
    }

    private static boolean shown(HeadlessGui h, TreeView<String> tree) {
        return h.retained(tree.findBar()).visible();
    }

    private static String status(HeadlessGui h, TreeView<String> tree) {
        return h.retained(tree.findStatus()).textString();
    }

    /** The rows the tree considers visible, read the way the user reads them: Home, then Down to the end. */
    private static List<String> visibleRows(HeadlessGui h, TreeView<String> tree) {
        tree.focus();
        h.tap(Key.HOME);
        h.frame();
        List<String> rows = new ArrayList<>();
        for (String at = tree.selected(); at != null && !rows.contains(at); at = tree.selected()) {
            rows.add(at);
            h.tap(Key.DOWN);
            h.frame();
        }
        return rows;
    }
}
