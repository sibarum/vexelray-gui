package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.model.PropKey;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.model.SemanticNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both read-models, published from hand-built trees with no {@code Gui}, no dispatcher and no navigator — see
 * {@code TextGeometryTest} for why the absence is the assertion (docs/plans/gui-decomposition.md §4).
 *
 * <p>This is the component the plan called {@code LayoutPublisher} and that turned out to have to be both
 * halves, so the case that matters most here is the one the name is about: the two snapshots carry the same
 * version, because they are built from one walk of one tree.
 */
class ReadModelsTest {

    private static long ids = 1L;

    /** Everything runs inline, so a resize observer has landed by the time the assertion reads it. */
    private static ReadModels plain() {
        return new ReadModels(ReadModels.Meanings.NONE, Runnable::run);
    }

    private static RetainedNode box(float x, float y, float w, float h) {
        RetainedNode n = new RetainedNode(ids++);
        n.x = x;
        n.y = y;
        n.w = w;
        n.h = h;
        return n;
    }

    private static RetainedNode child(RetainedNode parent, RetainedNode n) {
        n.parent = parent;
        parent.children.add(n);
        return n;
    }

    // --- the reason the two halves live together -----------------------------------------------------------

    /**
     * The join between geometry and meaning is by node id <b>and</b> by version. Publishing them from two walks,
     * or at two versions, would let a reader join a box from one frame to a role from another and see nothing
     * wrong. This is the whole argument for one component owning both.
     */
    @Test
    void bothSnapshotsCarryTheSameVersionFromTheSameWalk() {
        ReadModels rm = plain();
        RetainedNode root = box(0, 0, 100, 100);
        child(root, box(0, 0, 50, 20));

        rm.publish(root);

        assertEquals(rm.layoutSnapshot().version(), rm.semanticSnapshot().version(),
                "one walk, one version, or the join describes two frames");
        assertEquals(root.id, rm.semanticSnapshot().rootId());
    }

    @Test
    void everyPublishIsANewVersion() {
        ReadModels rm = plain();
        RetainedNode root = box(0, 0, 100, 100);

        rm.publish(root);
        long first = rm.layoutSnapshot().version();
        rm.publish(root);

        assertNotEquals(first, rm.layoutSnapshot().version());
        assertEquals(first + 1, rm.layoutSnapshot().version());
    }

    // --- what each half says -------------------------------------------------------------------------------

    @Test
    void geometryIsCopiedRatherThanWorkedOut() {
        ReadModels rm = plain();
        RetainedNode root = box(10, 20, 100, 50);
        root.scrollY = 7f;
        root.contentH = 500f;
        root.overflowY = true;

        rm.publish(root);
        NodeLayout published = rm.layoutSnapshot().node(root.id);

        assertTrue(published.present());
        assertEquals(new Rect(10, 20, 100, 50), published.rect());
        assertEquals(7f, published.scrollY(), 0.0001f);
        assertTrue(published.overflowY());
    }

    /**
     * Hidden subtrees are described rather than skipped, unlike geometry. A reader asking "why can I not see the
     * Save button" is asking about a node that exists and is not visible, and a snapshot that omitted it could
     * only answer "no such node".
     */
    @Test
    void aHiddenNodeIsStillDescribedEvenThoughItIsNotPlaced() {
        ReadModels rm = plain();
        RetainedNode root = box(0, 0, 100, 100);
        RetainedNode hidden = child(root, box(0, 0, 40, 10));
        hidden.set(PropKey.TEXT, "Save");
        hidden.set(PropKey.VISIBLE, false);

        rm.publish(root);

        SemanticNode described = rm.semanticSnapshot().node(hidden.id);
        assertEquals("Save", described.name(), "it exists, and a reader can ask about it");
        assertFalse(described.visible(), "and the snapshot says which case it is");
    }

    /**
     * A name identifies a node among its siblings; it is not a transcript. A button is a box with a label
     * inside, so the name has to come from the composition — but it stops at a declared role, because a
     * toolbar's name is not every button on it.
     */
    @Test
    void aNameIsGatheredFromTheCompositionButStopsAtADeclaredRole() {
        ReadModels rm = plain();
        RetainedNode root = box(0, 0, 200, 100);

        RetainedNode button = child(root, box(0, 0, 80, 20));
        child(button, box(0, 0, 80, 20)).set(PropKey.TEXT, "Save");

        RetainedNode toolbar = child(root, box(0, 40, 200, 20));
        RetainedNode inner = child(toolbar, box(0, 40, 80, 20));
        inner.set(PropKey.ROLE, "button");
        child(inner, box(0, 40, 80, 20)).set(PropKey.TEXT, "Open");

        rm.publish(root);

        assertEquals("Save", rm.semanticSnapshot().node(button.id).name(),
                "a box with a label inside is named by the label");
        assertEquals("", rm.semanticSnapshot().node(toolbar.id).name(),
                "but a container of named things is not named after them");
    }

    /** What the tree cannot say about itself arrives as a value, and is asked for rather than assumed. */
    @Test
    void focusAndLandmarkNamesComeFromTheMeanings() {
        RetainedNode root = box(0, 0, 100, 100);
        RetainedNode target = child(root, box(0, 0, 50, 20));
        long wanted = target.id;

        ReadModels rm = new ReadModels(new ReadModels.Meanings() {
            @Override
            public boolean focusable(long id) {
                return id == wanted;
            }

            @Override
            public long focusedId() {
                return wanted;
            }

            @Override
            public String landmarkName(long id) {
                return id == wanted ? "save-button" : "";
            }
        }, Runnable::run);

        rm.publish(root);

        SemanticNode described = rm.semanticSnapshot().node(wanted);
        assertTrue(described.focusable());
        assertTrue(described.focused());
        assertEquals("save-button", described.landmark());
        assertFalse(rm.semanticSnapshot().node(root.id).focused(), "and the root is not");
    }

    // --- geometry observers --------------------------------------------------------------------------------

    @Test
    void anObserverHearsAboutABoxThatMoved() {
        ReadModels rm = plain();
        RetainedNode root = box(0, 0, 100, 100);
        List<Rect> seen = new ArrayList<>();
        rm.onResize(root.id, computed -> seen.add(computed.rect()), true);

        rm.publish(root);
        root.w = 200f;
        rm.publish(root);

        assertEquals(List.of(new Rect(0, 0, 100, 100), new Rect(0, 0, 200, 100)), seen);
    }

    /** A scroll moves contents inside an unchanged viewport, which is not a resize. */
    @Test
    void anObserverIsNotToldAboutAScroll() {
        ReadModels rm = plain();
        RetainedNode root = box(0, 0, 100, 100);
        AtomicInteger told = new AtomicInteger();
        rm.onResize(root.id, computed -> told.incrementAndGet(), true);

        rm.publish(root);
        assertEquals(1, told.get(), "the first box is news");

        root.scrollY = 40f;
        rm.publish(root);
        assertEquals(1, told.get(), "and moving the contents inside it is not");
    }

    @Test
    void aForgottenObserverHearsNothingMore() {
        ReadModels rm = plain();
        RetainedNode root = box(0, 0, 100, 100);
        AtomicInteger told = new AtomicInteger();
        rm.onResize(root.id, computed -> told.incrementAndGet(), true);
        rm.publish(root);

        rm.forget(root.id);
        root.w = 300f;
        rm.publish(root);

        assertEquals(1, told.get(), "the node left the tree, and everything keyed by its id went with it");
    }
}
