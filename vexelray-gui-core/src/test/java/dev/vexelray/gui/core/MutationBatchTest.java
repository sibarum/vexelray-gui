package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.Length;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Gui#batch} posts a group of edits as one {@code Mutation.Batch}, so no frame boundary can split it.
 *
 * <p>This used to be a no-op that ran the edits inline, which mostly looked correct: a drain applies everything
 * queued, so one producer's edits usually landed together. "Usually" was the whole problem — the drain runs
 * concurrently with the producer, so a frame firing midway through the group rendered the half of it that had
 * been published, and a UI that reads two properties as a pair could observe them disagreeing.
 */
class MutationBatchTest {

    private static float noText(dev.vexelray.gui.core.model.RetainedNode n,
                                dev.vexelray.gui.core.layout.LayoutEnums.Axis axis, float px) {
        return 0f;
    }

    @Test
    void aFrameDuringABatchSeesNoneOfIt() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node a = gui.box().width(Length.rem(1)).height(Length.rem(1));
            Node b = gui.box().width(Length.rem(1)).height(Length.rem(1));
            gui.root().direction(dev.vexelray.gui.core.layout.LayoutEnums.Direction.ROW).children(a, b);
            gui.frame(400f, 100f, MutationBatchTest::noText);
            assertEquals(16f, a.layout().rect().w(), 0.5f, "1rem to start");

            gui.batch(() -> {
                a.width(Length.rem(5));
                b.width(Length.rem(3));
                // A frame taken from inside the group: the edits are buffered, so it can see neither of them.
                gui.frame(400f, 100f, MutationBatchTest::noText);
                assertEquals(16f, a.layout().rect().w(), 0.5f, "buffered, not published");
                assertEquals(16f, b.layout().rect().w(), 0.5f);
            });

            gui.frame(400f, 100f, MutationBatchTest::noText);
            assertEquals(80f, a.layout().rect().w(), 0.5f, "the whole group lands together");
            assertEquals(48f, b.layout().rect().w(), 0.5f);
        }
    }

    /** A helper that batches internally must compose into a caller's group rather than publishing early. */
    @Test
    void nestedBatchesJoinTheOuterGroup() {
        try (Gui gui = new Gui(Atchung.create())) {
            Node a = gui.box().width(Length.rem(1)).height(Length.rem(1));
            gui.root().children(a);
            gui.frame(400f, 100f, MutationBatchTest::noText);

            gui.batch(() -> {
                gui.batch(() -> a.width(Length.rem(5)));
                gui.frame(400f, 100f, MutationBatchTest::noText);
                assertEquals(16f, a.layout().rect().w(), 0.5f,
                        "the inner batch joined the outer one instead of publishing on its own");
            });

            gui.frame(400f, 100f, MutationBatchTest::noText);
            assertEquals(80f, a.layout().rect().w(), 0.5f);
        }
    }

    /** An empty batch publishes nothing at all — no group, no wasted drain. */
    @Test
    void anEmptyBatchIsSilent() {
        try (Gui gui = new Gui(Atchung.create())) {
            gui.frame(400f, 100f, MutationBatchTest::noText);
            long before = gui.layoutSnapshot().version();
            gui.batch(() -> { });
            gui.frame(400f, 100f, MutationBatchTest::noText);
            assertEquals(before, gui.layoutSnapshot().version(), "nothing changed, so nothing republished");
        }
    }
}
