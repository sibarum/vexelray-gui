package dev.vexelray.gui.widget;

import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A tree asks for each level by message ({@link TreeView#CHILDREN_WANTED}) and builds it when the answer arrives
 * ({@link TreeView#CHILDREN}): nothing of the application's runs in the frame to list a level or to draw a row. Here
 * the answers are held and sent by hand, so a test says exactly when each one lands, as an application's logic on a
 * thread of its own would.
 */
class TreeChildrenTest {

    /** What the trees here asked for, in order, and the means to answer. */
    private static final class Asked {
        final List<TreeView.ChildrenWanted> wanted = new ArrayList<>();
        final HeadlessGui h;

        Asked(HeadlessGui h) {
            this.h = h;
            h.bus.subscribe(TreeView.CHILDREN_WANTED, wanted::add);
        }

        /** Answer the tree called {@code tree} for {@code parent} (null for the roots): a branch for each name ending in '/', a leaf for the rest. */
        void answer(String tree, String parent, String... names) {
            List<TreeView.Entry> level = new ArrayList<>();
            for (String name : names) {
                level.add(name.endsWith("/") ? TreeView.Entry.branch(name, name) : TreeView.Entry.leaf(name, name));
            }
            h.bus.publish(TreeView.CHILDREN, new TreeView.Children(h.gui.address(tree), parent, level));
        }

        List<Object> parents() {
            List<Object> out = new ArrayList<>();
            for (TreeView.ChildrenWanted w : wanted) {
                out.add(w.parent() == null ? "<roots>" : w.parent());
            }
            return out;
        }
    }

    @Test
    void aTreeAsksForItsRootsByNameAndShowsThemWhenTheyLand() {
        try (HeadlessGui h = new HeadlessGui()) {
            Asked asked = new Asked(h);
            TreeView<String> tree = new TreeView<>(h.gui, "files");
            h.gui.root().children(tree.node());

            assertEquals(List.of(), asked.wanted, "not asked while built: whoever answers may not exist yet");
            h.frame();
            assertEquals(List.of(new TreeView.ChildrenWanted(h.gui.address("files"), null)), asked.wanted,
                    "asked in its first frame");
            assertNull(tree.rowNode("src/"), "and shows nothing it has not been told");

            asked.answer("files", null, "src/", "README");
            h.frame();
            assertNotNull(tree.rowNode("src/"));
            assertNotNull(tree.rowNode("README"));
            tree.close();
        }
    }

    @Test
    void openingAFolderAsksOnceAndTheLevelLandsInAFrame() {
        try (HeadlessGui h = new HeadlessGui()) {
            Asked asked = new Asked(h);
            TreeView<String> tree = new TreeView<>(h.gui, "files");
            h.gui.root().children(tree.node());
            asked.answer("files", null, "src/");
            h.frame();

            tree.expand("src/");
            tree.collapse("src/");
            tree.expand("src/");
            assertEquals(List.of("<roots>", "src/"), asked.parents(), "one request however often it is opened");

            asked.answer("files", "src/", "Main.java");
            h.frame();
            assertNotNull(tree.rowNode("Main.java"), "built in the frame the answer arrived");
            tree.close();
        }
    }

    @Test
    void anAnswerNobodyAskedForBuildsNothing() {
        try (HeadlessGui h = new HeadlessGui()) {
            Asked asked = new Asked(h);
            TreeView<String> tree = new TreeView<>(h.gui, "files");
            asked.answer("files", null, "src/");
            h.frame();

            asked.answer("files", "src/", "Main.java");   // src/ was never opened, so never asked about
            asked.answer("files", null, "other");         // and the roots were answered already
            h.frame();

            assertNull(tree.rowNode("Main.java"));
            assertNull(tree.rowNode("other"));
            assertNotNull(tree.rowNode("src/"));
            tree.close();
        }
    }

    @Test
    void twoTreesWithOneNameAreRefused() {
        try (HeadlessGui h = new HeadlessGui()) {
            new TreeView<String>(h.gui, "files");
            assertThrows(IllegalStateException.class, () -> new TreeView<String>(h.gui, "files"));
        }
    }

    /**
     * A refresh asks for every level it has and applies them together, so a row moved between two open folders keeps
     * what is open under it whichever answer lands first. Applied a level at a time, the folder it left would drop its
     * row before the folder it went to could reuse it.
     */
    @Test
    void aRefreshAppliesEveryLevelAtOnceSoAMovedRowKeepsWhatIsOpenUnderIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            Asked asked = new Asked(h);
            TreeView<String> tree = new TreeView<>(h.gui, "files");
            h.gui.root().children(tree.node());
            asked.answer("files", null, "a/", "b/");
            h.frame();
            tree.expand("a/");
            tree.expand("b/");
            asked.answer("files", "a/", "moved/");
            asked.answer("files", "b/");
            h.frame();
            tree.expand("moved/");
            asked.answer("files", "moved/", "inside");
            h.frame();
            assertNotNull(tree.rowNode("inside"));
            asked.wanted.clear();

            tree.refresh();
            assertEquals(4, asked.wanted.size(), "the roots and every level already listed: " + asked.parents());

            // The folder it left answers first, and nothing changes until the last answer is in.
            asked.answer("files", "a/");
            asked.answer("files", null, "a/", "b/");
            h.frame();
            assertNotNull(tree.rowNode("moved/"), "not applied while levels are still owed");

            asked.answer("files", "b/", "moved/");
            asked.answer("files", "moved/", "inside");
            h.frame();
            assertNotNull(tree.rowNode("inside"), "the moved row was reused, still open, not rebuilt shut");
            tree.close();
        }
    }

    /**
     * Expand-all goes down a level at a time, as each answer lands, and a walk superseded while it waits asks for
     * nothing more when its answer does land.
     */
    @Test
    void expandAllWaitsForEachLevelAndStopsAskingOnceSuperseded() {
        try (HeadlessGui h = new HeadlessGui()) {
            Asked asked = new Asked(h);
            TreeView<String> tree = new TreeView<>(h.gui, "files");
            h.gui.root().children(tree.node());
            asked.answer("files", null, "a/", "z/");
            h.frame();

            tree.expandAll("a/");
            assertEquals(List.of("<roots>", "a/"), asked.parents());
            asked.answer("files", "a/", "b/");
            h.frame();
            assertEquals(List.of("<roots>", "a/", "b/"), asked.parents(), "on from the level that landed");

            tree.focus();
            h.tap(Key.DOWN);   // moving the selection is not an action, so the walk carries on
            tree.expand("z/");  // an expand is: it supersedes the walk
            asked.answer("files", "b/", "c/");
            h.frame();

            assertNotNull(tree.rowNode("c/"), "the answer still builds its level, which is never wrong to have");
            assertFalse(asked.parents().contains("c/"), "but the superseded walk asks nothing more: " + asked.parents());
            tree.close();
        }
    }
}
