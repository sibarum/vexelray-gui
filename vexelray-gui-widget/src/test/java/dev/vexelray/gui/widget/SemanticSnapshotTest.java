package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.SemanticSnapshot;
import dev.vexelray.gui.core.model.SemanticNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The semantic read-model must describe the tree a reader is actually looking at (docs/automation.md §3).
 *
 * <p>Like {@code LabelGeometryTest}, these are compliance conditions rather than features. The snapshot exists
 * so that something which is <em>not</em> the renderer — an automation agent, a thin client, a screen reader —
 * can address a node by what it means. Every claim that makes it usable for that is checkable now, with no such
 * consumer present, and the ones that are not checked are the ones that quietly stop being true.
 *
 * <p>The join with {@code LayoutSnapshot} is the load-bearing one: an agent asks the semantic snapshot which
 * node is the Save button and the layout snapshot where it is. If the two can ever describe different frames,
 * it clicks where the button used to be, and does so intermittently.
 */
class SemanticSnapshotTest {

    @Test
    void theTwoSnapshotsAlwaysDescribeTheSameFrame() {
        try (HeadlessGui h = new HeadlessGui()) {
            h.gui.root().children(h.gui.text("hello"));
            h.frame();

            assertEquals(h.gui.layoutSnapshot().version(), h.gui.semanticSnapshot().version(),
                    "the join key: same version, or a reader pairs meaning from one frame with geometry from another");

            long before = h.gui.semanticSnapshot().version();
            h.gui.root().children(h.gui.text("hello"), h.gui.text("world"));
            h.frame();
            assertTrue(h.gui.semanticSnapshot().version() > before, "a changed tree republishes both");
            assertEquals(h.gui.layoutSnapshot().version(), h.gui.semanticSnapshot().version(),
                    "and they stay in step across frames, not just on the first one");
        }
    }

    @Test
    void aDeclaredRoleSurvivesToTheSnapshot() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node box = h.gui.box().role("button");
            h.gui.root().children(box);
            h.frame();

            SemanticNode n = h.gui.semanticSnapshot().node(box.id());
            assertTrue(n.present());
            assertEquals("button", n.role(), "core stores the role verbatim and branches on nothing");
        }
    }

    @Test
    void aCompositionsNameComesFromItsLabel() {
        try (HeadlessGui h = new HeadlessGui()) {
            // What a Button is: a box the user points at, with the text they read inside it. Asking the box for
            // its own text gets nothing, which is exactly why the name has to be derived.
            Node button = h.gui.box().role("button");
            button.children(h.gui.text("Save"));
            h.gui.root().children(button);
            h.frame();

            assertEquals("Save", h.gui.semanticSnapshot().node(button.id()).name(),
                    "the name a person would call it by, read off the composition");
        }
    }

    @Test
    void aNameStopsAtTheNextDeclaredRole() {
        try (HeadlessGui h = new HeadlessGui()) {
            // A toolbar containing two buttons is not named "Save Cancel". Without the stop rule, every
            // container's name degenerates into the full text of its subtree as the tree gets deeper.
            Node save = h.gui.box().role("button").children(h.gui.text("Save"));
            Node cancel = h.gui.box().role("button").children(h.gui.text("Cancel"));
            Node toolbar = h.gui.box().role("toolbar").children(h.gui.text("File"), save, cancel);
            h.gui.root().children(toolbar);
            h.frame();

            SemanticSnapshot snap = h.gui.semanticSnapshot();
            assertEquals("File", snap.node(toolbar.id()).name(), "its own label, not its children's");
            assertEquals("Save", snap.node(save.id()).name());
            assertEquals("Cancel", snap.node(cancel.id()).name());
        }
    }

    @Test
    void structureIsWalkableFromTheRoot() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node child = h.gui.box().role("panel");
            h.gui.root().children(child);
            h.frame();

            SemanticSnapshot snap = h.gui.semanticSnapshot();
            SemanticNode root = snap.root();
            assertTrue(root.present(), "a reader with only the snapshot has to be able to start somewhere");
            assertEquals(-1L, root.parentId());
            assertTrue(root.children().contains(child.id()), "children in tree order");
            assertEquals(root.id(), snap.node(child.id()).parentId(), "and the link goes both ways");
        }
    }

    @Test
    void aHiddenNodeIsDescribedRatherThanOmitted() {
        try (HeadlessGui h = new HeadlessGui()) {
            Node hidden = h.gui.box().role("panel").visible(false);
            h.gui.root().children(hidden);
            h.frame();

            SemanticNode n = h.gui.semanticSnapshot().node(hidden.id());
            assertTrue(n.present(), "\"it exists but you cannot see it\" is an answer; \"no such node\" is not");
            assertFalse(n.visible());

            // And `visible` is the flag that answers it — not the node's absence from the geometry snapshot.
            // A hidden node *is* published there (collectLayout walks the whole tree), carrying whatever rect it
            // last had; only its derived text metrics are cleared. So a consumer deciding whether it can click
            // something must read this flag, and must not infer visibility from having found a rect.
            assertTrue(h.gui.layoutSnapshot().node(hidden.id()).present(),
                    "pinning the asymmetry rather than assuming it away");
        }
    }

    @Test
    void focusIsReportedWhereItIs() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField field = new TextField(h.gui, "abc");
            h.gui.root().children(field.node());
            h.frame();

            long id = field.node().id();
            assertTrue(h.gui.semanticSnapshot().node(id).focusable(),
                    "an editable field takes focus, and a reader must be able to tell that from decoration");
            assertFalse(h.gui.semanticSnapshot().node(id).focused());

            dev.vexelray.gui.core.layout.Rect box = field.node().layout().rect();
            h.click(box.x() + box.w() * 0.5f, box.y() + box.h() * 0.5f);
            assertTrue(h.gui.semanticSnapshot().node(id).focused(), "and focus moving is visible in the snapshot");
        }
    }

    @Test
    void aNameDoesNotReachIntoContent() {
        try (HeadlessGui h = new HeadlessGui()) {
            // The case declared roles alone do not cover: a container's *content* is ordinary application boxes
            // that declare nothing, so without a depth bound this node would be named after everything in it.
            Node page = h.gui.box().children(
                    h.gui.box().children(
                            h.gui.box().children(h.gui.text("a paragraph buried in the content"))));
            Node panel = h.gui.box().role("panel").children(h.gui.text("Settings"), page);
            h.gui.root().children(panel);
            h.frame();

            assertEquals("Settings", h.gui.semanticSnapshot().node(panel.id()).name(),
                    "its own label, not a transcript of what it contains");
        }
    }

    @Test
    void widgetsSayWhatTheyAre() {
        try (HeadlessGui h = new HeadlessGui()) {
            TextField field = new TextField(h.gui, "abc");
            h.gui.root().children(field.node());
            h.frame();

            // Without this an agent has a correctly-structured but anonymous tree: it can see a box at 12,40 and
            // cannot tell that it is the thing you type into.
            assertEquals("textfield", h.gui.semanticSnapshot().node(field.node().id()).role());
        }
    }

    /**
     * A table is the component whose entire content is structure, so it is the one an anonymous box tree fails
     * hardest: "row 5 of the table" and "the Name column's header" are the two things anybody addressing one
     * ever says, and neither is sayable about a box. Every part declares what it is, and the rows keep being
     * named after their cells — the reason the role is on what the builder built and not on the box around it.
     */
    @Test
    void aTableSaysWhatItsPartsAre() {
        try (HeadlessGui h = new HeadlessGui()) {
            Table<String> table = new Table<>(h.gui, 2f, java.util.List.of(
                    Table.Column.of("Name", Length.grow(1), (g, s) -> g.text(s)),
                    Table.Column.of("Kind", Length.rem(6), (g, s) -> g.text("person"))));
            table.items(java.util.List.of("alice", "bob"));
            table.node().width(Length.FILL).height(Length.FILL);
            h.gui.root().children(table.node());
            h.frame().frame();      // one frame builds the rows, the next lays them out

            SemanticSnapshot sem = h.gui.semanticSnapshot();
            assertEquals("table", sem.node(table.node().id()).role());
            assertEquals("rowgroup", sem.node(table.rows().node().id()).role(),
                    "the body is a ListView, and what it holds are this table's rows rather than list items");

            assertTrue(sem.nodes().values().stream()
                            .anyMatch(n -> n.role().equals("columnheader") && n.name().equals("Name")),
                    "the header a click sorts by is addressable by what it says");
            assertTrue(sem.nodes().values().stream().anyMatch(n -> n.role().equals("columngrip")),
                    "and the boundary a drag resizes by is addressable at all");

            SemanticNode row = sem.nodes().values().stream()
                    .filter(n -> n.role().equals("row") && n.name().contains("alice"))
                    .findFirst().orElse(SemanticNode.ABSENT);
            assertTrue(row.present(), "a row is named after the cells in it, or it cannot be found by them");
        }
    }

    @Test
    void anAbsentNodeAnswersRatherThanFailing() {
        try (HeadlessGui h = new HeadlessGui()) {
            h.frame();
            assertSame(SemanticNode.ABSENT, h.gui.semanticSnapshot().node(9_999L));
            assertFalse(SemanticNode.ABSENT.present());
        }
    }
}
