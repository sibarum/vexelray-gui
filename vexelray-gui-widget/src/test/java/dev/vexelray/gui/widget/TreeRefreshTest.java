package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TreeView#refresh} — the other half of {@link TreeView#reorderable}.
 *
 * <p>A tree reads its hierarchy through a {@code Source} and is never told when that model changes. That is the
 * right split, and it leaves the application one obligation: say when. Until this existed the application could
 * not discharge it, so a drop resolved, drew its indicator, refused what could not be done, recorded an undoable
 * change — and then showed the hierarchy exactly as it had been. Every one of those correct behaviours looked
 * broken, which is the worst way for a feature to be half-finished.
 *
 * <p>The properties that matter are about what <b>survives</b>. A reconcile that rebuilt the rows would be four
 * lines and would close every folder the user had opened — which, immediately after a drag, is precisely the
 * state they were working in.
 */
class TreeRefreshTest {

    /** A hierarchy the test can rearrange, standing in for the application's model. */
    private final Map<String, List<String>> kids = new LinkedHashMap<>();
    private final List<String> roots = new ArrayList<>();
    private final AtomicInteger fetches = new AtomicInteger();

    private final TreeView.Source<String> source = new TreeView.Source<>() {

        @Override
        public List<String> roots() {
            return List.copyOf(roots);
        }

        @Override
        public String label(String item) {
            return item;
        }

        @Override
        public boolean hasChildren(String item) {
            return !kids.getOrDefault(item, List.of()).isEmpty();
        }

        @Override
        public List<String> children(String item) {
            fetches.incrementAndGet();
            return List.copyOf(kids.getOrDefault(item, List.of()));
        }
    };

    TreeRefreshTest() {
        roots.addAll(List.of("src", "lib", "docs", "notes.txt"));
        kids.put("src", new ArrayList<>(List.of("main", "test")));
        kids.put("lib", new ArrayList<>(List.of("Core.java")));
        kids.put("docs", new ArrayList<>());
    }

    /** Move {@code item} out of wherever it is and into {@code parent} — the model change a drop would make. */
    private void move(String item, String parent) {
        roots.remove(item);
        kids.values().forEach(list -> list.remove(item));
        if (parent == null) {
            roots.add(item);
        } else {
            kids.computeIfAbsent(parent, k -> new ArrayList<>()).add(item);
        }
    }

    /** The visible rows, top to bottom, as the frame actually laid them out. */
    private static List<String> shown(TreeView<String> tree, List<String> candidates) {
        record Placed(String item, float y) { }
        List<Placed> placed = new ArrayList<>();
        for (String item : candidates) {
            Node row = tree.rowNode(item);
            if (row == null || row.layout().rect() == null || row.layout().rect().h() <= 0f) {
                continue;
            }
            placed.add(new Placed(item, row.layout().rect().y()));
        }
        placed.sort(java.util.Comparator.comparing(Placed::y));
        return placed.stream().map(Placed::item).toList();
    }

    private static final List<String> ALL =
            List.of("src", "main", "test", "lib", "Core.java", "docs", "notes.txt");

    @Test
    void aMovedItemIsShownUnderItsNewParent() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            tree.expand("src");
            h.frame();
            assertEquals(List.of("src", "main", "test", "lib", "docs", "notes.txt"), shown(tree, ALL));

            move("notes.txt", "src");
            tree.refresh();
            h.frame();

            assertEquals(List.of("src", "main", "test", "notes.txt", "lib", "docs"), shown(tree, ALL),
                    "the row is where the model now says it is");
            tree.close();
        }
    }

    @Test
    void reorderingSiblingsReordersTheRows() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            tree.expand("src");
            h.frame();

            kids.get("src").sort(java.util.Comparator.reverseOrder());   // main, test -> test, main
            tree.refresh();
            h.frame();

            assertEquals(List.of("src", "test", "main", "lib", "docs", "notes.txt"), shown(tree, ALL));
            tree.close();
        }
    }

    /**
     * The property the whole design turns on: the row is the same node, so everything hanging off it — its
     * handlers, its selection, the folder the user had open — is still there afterwards.
     */
    @Test
    void aRowSurvivesTheMoveRatherThanBeingRebuilt() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            tree.expand("src");
            tree.expand("lib");
            h.frame();
            long before = tree.rowNode("main").id();

            move("main", "lib");
            tree.refresh();
            h.frame();

            assertEquals(before, tree.rowNode("main").id(), "the same node, moved — not a copy of it");
            assertEquals(List.of("src", "test", "lib", "Core.java", "main", "docs", "notes.txt"),
                    shown(tree, ALL), "and it is drawn under its new parent");
            tree.close();
        }
    }

    /**
     * Moved into a branch that has never been opened, the row goes away — and that is right rather than a
     * shortfall. A collapsed folder has no rows under it at all; the item gets one when the folder is opened,
     * which is also when the tree first reads what is in it.
     */
    @Test
    void anItemMovedIntoAnUnopenedBranchLosesItsRowUntilThatBranchOpens() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            tree.expand("src");
            h.frame();

            move("main", "lib");        // lib has never been opened
            tree.refresh();
            h.frame();
            assertNull(tree.rowNode("main"), "nothing under a shut folder has a row");

            tree.expand("lib");
            h.frame();
            assertNotNull(tree.rowNode("main"), "and opening it is what reads the branch");
            tree.close();
        }
    }

    @Test
    void anOpenFolderStaysOpenAcrossARefresh() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            tree.expand("src");
            h.frame();

            move("notes.txt", null);   // a change elsewhere entirely
            tree.refresh();
            h.frame();

            assertTrue(shown(tree, ALL).containsAll(List.of("main", "test")),
                    "src was open before the refresh and is open after it");
            tree.close();
        }
    }

    /**
     * A reconcile costs the listings the user has already paid for and no others. Walking into a collapsed
     * folder to "keep it up to date" would turn a drag inside one directory into I/O across the whole tree.
     */
    @Test
    void aCollapsedBranchIsNotWalkedInto() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            h.frame();
            int before = fetches.get();

            tree.refresh();
            h.frame();

            assertEquals(before, fetches.get(), "nothing was open, so nothing was read");
            tree.close();
        }
    }

    @Test
    void anItemThatLeftTheModelLosesItsRow() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            tree.expand("src");
            h.frame();
            assertNotNull(tree.rowNode("test"));

            kids.get("src").remove("test");
            tree.refresh();
            h.frame();

            assertNull(tree.rowNode("test"), "gone from the model is gone from the tree");
            tree.close();
        }
    }

    /** The selection never rests on a row that is no longer there — the same rule a collapse follows. */
    @Test
    void aSelectionOnARemovedRowComesUpToItsParent() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            tree.expand("src");
            tree.select("test");
            h.frame();
            assertEquals("test", tree.selected());

            kids.get("src").remove("test");
            tree.refresh();
            h.frame();

            assertEquals("src", tree.selected());
            tree.close();
        }
    }

    /** A leaf that gains children gains a disclosure control, without being rebuilt to get one. */
    @Test
    void aLeafThatGainsChildrenCanBeOpened() {
        try (HeadlessGui h = new HeadlessGui()) {
            TreeView<String> tree = new TreeView<>(h.gui, source);
            h.gui.root().children(tree.node());
            h.frame();
            long before = tree.rowNode("notes.txt").id();

            move("main", "notes.txt");   // notes.txt is a leaf no longer
            tree.refresh();
            h.frame();
            tree.expand("notes.txt");
            h.frame();

            assertEquals(before, tree.rowNode("notes.txt").id(), "still the same row");
            assertNotNull(tree.rowNode("main"), "and it opens onto what it now holds");
            tree.close();
        }
    }
}
