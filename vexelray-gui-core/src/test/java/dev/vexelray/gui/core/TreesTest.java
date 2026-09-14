package dev.vexelray.gui.core;

import dev.vexelray.gui.core.layout.LayoutSnapshot;
import dev.vexelray.gui.core.model.Mutation;
import dev.vexelray.gui.core.model.PropKey;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where nodes come from, with no {@code Gui} and no frame loop — the mutations are read straight off the topic
 * they are published to (docs/plans/gui-decomposition.md §4).
 *
 * <p>That is the only way to see what a handle actually does. Building a tree through {@code Gui} and asserting
 * on the laid-out result cannot distinguish "published one batch" from "published eleven mutations", and the
 * difference is the whole reason {@code batch} exists.
 */
class TreesTest {

    private static final Topic<Mutation> TOPIC = Topic.of("trees.test.mutations", Mutation.class);

    /** Collects everything published, so a test can read the mutations rather than their eventual effect. */
    private static final class Seen {
        private final List<Mutation> all = new ArrayList<>();

        synchronized void add(Mutation m) {
            all.add(m);
        }

        synchronized List<Mutation> all() {
            return List.copyOf(all);
        }
    }

    private record Rig(Trees trees, Seen seen, AtomicInteger wakes) {
    }

    private static Rig rig() {
        Atchung bus = Atchung.create();
        Seen seen = new Seen();
        bus.subscribe(TOPIC, seen::add);
        AtomicInteger wakes = new AtomicInteger();
        Trees trees = new Trees(bus, TOPIC, why -> wakes.incrementAndGet(), () -> LayoutSnapshot.EMPTY);
        return new Rig(trees, seen, wakes);
    }

    /** Every mutation reaches the bus; a handful of them may arrive on the publishing thread or just after. */
    private static void settle(Seen seen, int expected) {
        long deadline = System.currentTimeMillis() + 2_000;
        while (seen.all().size() < expected && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
    }

    @Test
    void everyNodeGetsAnIdOfItsOwn() {
        Rig r = rig();

        Node a = r.trees().box();
        Node b = r.trees().row();
        Node c = r.trees().column();

        assertNotEquals(a.id(), b.id());
        assertNotEquals(b.id(), c.id());
        assertNotEquals(a.id(), r.trees().rootId(), "and none of them is the root");
    }

    /**
     * The root is minted in the constructor but <b>created</b> only when asked, because its id is needed before
     * whatever drains the topic is subscribed and its {@code Create} must be published after. Publishing it early
     * puts it on the bus with nobody listening, and the tree starts life without a root — which is exactly what
     * happened when this component was first extracted.
     */
    @Test
    void theRootIsNotCreatedUntilItIsAskedFor() {
        Rig r = rig();
        settle(r.seen(), 0);

        assertEquals(List.of(), r.seen().all(), "nothing published yet, so a late subscriber misses nothing");

        Node root = r.trees().createRoot();
        settle(r.seen(), 1);

        assertEquals(1, r.seen().all().size());
        Mutation.Create created = assertInstanceOf(Mutation.Create.class, r.seen().all().get(0));
        assertEquals(root.id(), created.id());
        assertEquals(r.trees().rootId(), created.id());
    }

    @Test
    void aTextNodeIsBornHoldingItsText() {
        Rig r = rig();

        Node label = r.trees().text("Save");
        settle(r.seen(), 1);

        Mutation.Create created = assertInstanceOf(Mutation.Create.class, r.seen().all().get(0));
        assertEquals(label.id(), created.id());
        assertEquals("Save", created.initial().get(PropKey.TEXT));
    }

    // --- batching -----------------------------------------------------------------------------------------

    @Test
    void aBatchIsOnePublishRatherThanOnePerEdit() {
        Rig r = rig();

        r.trees().batch(() -> {
            r.trees().box();
            r.trees().box();
            r.trees().text("three");
        });
        settle(r.seen(), 1);

        assertEquals(1, r.seen().all().size(), "one publish");
        Mutation.Batch batch = assertInstanceOf(Mutation.Batch.class, r.seen().all().get(0));
        assertEquals(3, batch.ops().size(), "carrying all three edits");
    }

    /** A helper that batches internally must compose into a caller's larger group, not publish early. */
    @Test
    void aNestedBatchJoinsTheOuterOne() {
        Rig r = rig();

        r.trees().batch(() -> {
            r.trees().box();
            r.trees().batch(() -> {
                r.trees().box();
                r.trees().box();
            });
        });
        settle(r.seen(), 1);

        assertEquals(1, r.seen().all().size());
        assertEquals(3, assertInstanceOf(Mutation.Batch.class, r.seen().all().get(0)).ops().size());
    }

    @Test
    void anEmptyBatchPublishesNothingAtAll() {
        Rig r = rig();

        r.trees().batch(() -> { });

        assertEquals(List.of(), r.seen().all(), "a group with nothing in it is not a frame's worth of work");
    }

    /**
     * A batch is one thread's group of edits. Two workers batching at once must not braid their ops into each
     * other's group — which is what the thread-local is for, and what a shared buffer would have got wrong.
     */
    @Test
    void twoThreadsBatchingAtOnceDoNotBraid() throws Exception {
        Rig r = rig();
        CountDownLatch bothInside = new CountDownLatch(2);
        CountDownLatch done = new CountDownLatch(2);

        for (int t = 0; t < 2; t++) {
            final int mine = t;
            Thread worker = new Thread(() -> r.trees().batch(() -> {
                r.trees().text("a" + mine);
                bothInside.countDown();
                try {
                    bothInside.await(2, TimeUnit.SECONDS);   // hold both groups open at the same time
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                r.trees().text("b" + mine);
                done.countDown();
            }));
            worker.setDaemon(true);
            worker.start();
        }

        assertTrue(done.await(5, TimeUnit.SECONDS), "both batches finished");
        settle(r.seen(), 2);

        assertEquals(2, r.seen().all().size(), "one batch each, not one shared or three");
        for (Mutation m : r.seen().all()) {
            assertEquals(2, assertInstanceOf(Mutation.Batch.class, m).ops().size(),
                    "and each carries its own two edits, not the other thread's");
        }
    }

    // --- waking -------------------------------------------------------------------------------------------

    /**
     * Every publish wakes the loop. The wake is easy to write once and easy to forget the second time, and
     * forgetting it produces a window that stops updating rather than one that updates slowly.
     */
    @Test
    void everyPublishWakesTheLoopExactlyOnce() {
        Rig r = rig();

        r.trees().box();
        assertEquals(1, r.wakes().get());

        r.trees().batch(() -> {
            r.trees().box();
            r.trees().box();
        });
        assertEquals(2, r.wakes().get(), "a batch is one publish, so it is one wake");
    }
}
