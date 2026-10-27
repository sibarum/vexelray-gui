package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TreeView#revealPath}: opening the tree down a chain the application supplies, and landing on the row at
 * the end of it. The property that matters is that each level is fetched before the next is looked for — a row's
 * children do not exist until its fetch has landed, so a caller that merely asked for the levels in turn would
 * find the deepest missing every time.
 *
 * <p>Everything here runs on {@link HeadlessGui}'s same-thread handler executor, so the walk that a live
 * application pays for off the frame loop happens inline, in the order it would happen in anyway.
 */
class TreeRevealTest {

    /** Deep enough that a reveal has to open more than one level to get to the bottom of it. */
    private static final Map<String, List<String>> KIDS = Map.of(
            "src", List.of("main", "test"),
            "main", List.of("java", "resources"),
            "java", List.of("App.java", "Util.java"),
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

    @Test
    void revealOpensEveryLevelOnTheWayAndSelectsTheEnd() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource();
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            h.frame();

            tree.revealPath(List.of("src", "main", "java", "App.java"), null);
            h.frame();

            assertEquals("App.java", tree.selected(), "the walk lands on the row the chain ends at");
            assertNotNull(tree.rowNode("App.java"), "a selected row is one that was built");
            assertEquals(3, source.fetches.get(), "one fetch per level opened: src, main, java");
            tree.close();
        }
    }

    /** Three levels, as one walk that waits for each fetch, so the deepest row is there when it ends. */
    @Test
    void theWalkIsOneTaskThatWaitsForEachLevelItOpens() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource();
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            h.frame();

            tree.revealPath(List.of("src", "main", "java", "App.java"), null);
            h.frame();

            assertNotNull(tree.rowNode("App.java"), "three levels deep, in one walk");
            assertEquals("App.java", tree.selected());
            tree.close();
        }
    }

    /** Only the way down is opened. A folder beside the chain, and the target itself, are left as they were. */
    @Test
    void revealOpensTheWayDownAndNothingElse() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource();
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            h.frame();

            tree.revealPath(List.of("src", "main", "java"), null);
            h.frame();

            assertNotNull(tree.rowNode("resources"), "the way down was opened");
            assertNull(tree.rowNode("AppTest.java"), "'test' sits beside the chain and stays shut");
            assertNull(tree.rowNode("App.java"), "the row revealed is selected, not expanded");
            tree.close();
        }
    }

    /** A tree already open down to the row keeps every other row the user opened in it: no rebuild, no I/O. */
    @Test
    void revealingIntoAnOpenTreeCostsNothingAndShutsNothing() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource();
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            h.frame();

            tree.revealPath(List.of("src", "test", "AppTest.java"), null);
            tree.revealPath(List.of("src", "main", "java", "App.java"), null);
            h.frame();
            int fetched = source.fetches.get();

            tree.revealPath(List.of("src", "main", "java", "Util.java"), null);
            h.frame();

            assertEquals("Util.java", tree.selected());
            assertEquals(fetched, source.fetches.get(), "everything on the way was already materialised");
            assertNotNull(tree.rowNode("AppTest.java"), "the row opened by the earlier reveal is still there");
            tree.close();
        }
    }

    /** The callback is where a caller puts what needed the row, so it runs after the row is there to be had. */
    @Test
    void theCallbackRunsOnceTheRowExists() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource();
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            h.frame();

            AtomicReference<Node> seen = new AtomicReference<>();
            tree.revealPath(List.of("src", "main", "java", "App.java"), () -> seen.set(tree.rowNode("App.java")));
            h.frame();

            assertNotNull(seen.get(), "the row was built by the time the caller was told the walk had ended");
            tree.close();
        }
    }

    /**
     * A chain through something the source no longer offers stops where it runs out. The caller still gets its
     * turn — it asked about a hierarchy that has moved under it, and that is an answer rather than a failure.
     */
    @Test
    void aChainThatRunsOutSelectsNothingAndStillReportsBack() {
        try (HeadlessGui h = new HeadlessGui()) {
            MapSource source = new MapSource();
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            h.frame();

            AtomicInteger told = new AtomicInteger();
            tree.revealPath(List.of("src", "gone", "App.java"), told::incrementAndGet);
            h.frame();

            assertNull(tree.selected(), "there was no row to land on");
            assertEquals(1, told.get(), "the walk ended, and said so");
            assertTrue(source.fetches.get() >= 1, "it opened as far down as the chain was real");
            tree.close();
        }
    }
}
