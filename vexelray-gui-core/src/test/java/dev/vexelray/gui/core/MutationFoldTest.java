package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.model.Mutation;
import dev.vexelray.gui.core.model.NodeKind;
import dev.vexelray.gui.core.model.PropKey;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which edits are writes to a cell, and which are occurrences — the declaration the folding mailbox drains this
 * channel through ({@code Mutation.cell}).
 *
 * <p>The distinction is the whole of it. Setting a property twice before a frame is two writes to one cell and
 * only the second was ever observable, so the queue keeps one. Inserting a node twice is two moves, and a queue
 * that kept one would be a tree in the wrong shape.
 */
class MutationFoldTest {

    private static float noText(dev.vexelray.gui.core.model.RetainedNode n,
                                dev.vexelray.gui.core.layout.LayoutEnums.Axis axis, float px) {
        return 0f;
    }

    @Test
    void twoWritesToOnePropertyOfOneNodeAreOneCell() {
        assertEquals(new Mutation.SetProp(7, PropKey.WIDTH, Length.rem(1)).cell(),
                new Mutation.SetProp(7, PropKey.WIDTH, Length.rem(9)).cell(),
                "same node, same property: the second write supersedes the first");
    }

    @Test
    void differentPropertiesAndDifferentNodesAreDifferentCells() {
        assertNotEquals(new Mutation.SetProp(7, PropKey.WIDTH, Length.rem(1)).cell(),
                new Mutation.SetProp(7, PropKey.HEIGHT, Length.rem(1)).cell(),
                "a node's width is not its height");
        assertNotEquals(new Mutation.SetProp(7, PropKey.WIDTH, Length.rem(1)).cell(),
                new Mutation.SetProp(8, PropKey.WIDTH, Length.rem(1)).cell(),
                "and one node's width is not another's");
    }

    @Test
    void settingTextWritesTheSameCellAsAnyOtherWriteToText() {
        assertEquals(new Mutation.SetProp(7, PropKey.TEXT, "a").cell(),
                new Mutation.SetText(7, "b").cell(),
                "SetText is a write to PropKey.TEXT, so it must supersede and be superseded by one");
    }

    @Test
    void structureIsNeverACell() {
        assertNull(new Mutation.Create(7, NodeKind.BOX, Map.of()).cell());
        assertNull(new Mutation.Insert(1, 7, 0).cell(), "the same node inserted twice is two moves");
        assertNull(new Mutation.Remove(7).cell());
        assertNull(new Mutation.Batch(List.of()).cell(), "a batch was already coalesced by whoever grouped it");
    }

    @Test
    void askingTwiceForTheSameScrollIsAskingOnce() {
        assertEquals(new Mutation.Reveal(7).cell(), new Mutation.Reveal(7).cell());
        assertEquals(new Mutation.ScrollToEdge(7).cell(), new Mutation.ScrollToEdge(7).cell());
        assertNotEquals(new Mutation.Reveal(7).cell(), new Mutation.Reveal(8).cell(),
                "but not for a different node");
        assertNotEquals(new Mutation.Reveal(7).cell(), new Mutation.ScrollToEdge(7).cell(),
                "and going to a node is not going to an edge");
    }

    @Test
    void aBurstOfWritesLandsAsItsLastValue() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node box = gui.box().width(Length.rem(1)).height(Length.rem(1));
            gui.root().append(box);
            gui.frame(400f, 100f, MutationFoldTest::noText);

            for (int i = 1; i <= 10_000; i++) {
                box.width(Length.rem(i));
            }
            gui.frame(400f, 100f, MutationFoldTest::noText);

            assertEquals(160_000f, box.layout().rect().w(), 0.5f,
                    "ten thousand writes to one property are the last of them, and cost one slot to say so");
        }
    }

    @Test
    void aWriteToANodeThatWasRemovedAfterItStaysAfterTheRemoval() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node keep = gui.box().width(Length.rem(1)).height(Length.rem(1));
            Node doomed = gui.box().width(Length.rem(1)).height(Length.rem(1));
            gui.root().direction(dev.vexelray.gui.core.layout.LayoutEnums.Direction.ROW).children(keep, doomed);
            gui.frame(400f, 100f, MutationFoldTest::noText);

            // Write, destroy, write again. Folding moves the surviving write to the position of the *latest*
            // write, which is after the removal — so it lands on a node that is gone and does nothing, exactly
            // as the unfolded sequence did. Carrying it back in front of the removal would have applied it.
            doomed.width(Length.rem(2));
            doomed.remove();
            doomed.width(Length.rem(3));
            gui.frame(400f, 100f, MutationFoldTest::noText);

            assertTrue(gui.layoutSnapshot().node(keep.id()).present(), "the survivor is still there");
            assertFalse(gui.layoutSnapshot().node(doomed.id()).present(),
                    "and the removed node is still removed, however many writes chased it");
        }
    }
}
