package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.InputEvent;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A rough cost reading for a pointer move over a deep tree with many state observers, printed rather than asserted
 * (timing on a shared machine is no pass/fail). The assertion is only that the deliveries are the ones expected, so
 * the loop measures the real path.
 *
 * <p>The tree: a chain {@value #DEPTH} deep with an observer on every link, and beside it a bank of
 * {@value #BANK} observed nodes the pointer never reaches — the shape of a long list of hover-styled rows next to a
 * deeply nested panel. Before the refresh walked each chain once, a move cost (observers x depth) parent steps.
 */
class StateRefreshCostTest {

    private static final Topic<ClickEvent> CLICKS = Topic.of("test.state.cost.clicks", ClickEvent.class);
    private static final int DEPTH = 40;
    private static final int BANK = 1000;
    private static final int MOVES = 4000;

    private static RetainedNode node(long id, float x, float y, float w, float h, RetainedNode parent) {
        RetainedNode n = new RetainedNode(id);
        n.x = x;
        n.y = y;
        n.w = w;
        n.h = h;
        if (parent != null) {
            n.parent = parent;
            parent.children.add(n);
        }
        return n;
    }

    @Test
    void movesOverADeepObservedTree() {
        Atchung bus = Atchung.create();
        InputDispatcher d = new InputDispatcher(bus, CLICKS, Runnable::run);
        AtomicLong deliveries = new AtomicLong();

        RetainedNode root = node(0, 0, 0, 2000, 1000, null);
        RetainedNode link = root;
        long id = 1;
        for (int i = 0; i < DEPTH; i++) {
            link = node(id, 0, 0, 100, 100, link);
            d.onState(id++, s -> deliveries.incrementAndGet());
        }
        // Two leaves at the bottom of the chain, so a move can cross between them.
        node(id, 0, 0, 50, 100, link);
        d.onState(id++, s -> deliveries.incrementAndGet());
        node(id, 50, 0, 50, 100, link);
        d.onState(id++, s -> deliveries.incrementAndGet());
        RetainedNode bank = node(id++, 1000, 0, 1000, 1000, root);
        for (int i = 0; i < BANK; i++) {
            node(id, 1000 + i, 0, 1, 1, bank);
            d.onState(id++, s -> deliveries.incrementAndGet());
        }

        double within = run(bus, d, root, 10, 20, deliveries, 0);
        double across = run(bus, d, root, 10, 60, deliveries, 2);
        System.out.printf("state refresh: depth %d, %d observers: %.0f ns/move within a leaf, %.0f ns/move across "
                + "leaves%n", DEPTH, DEPTH + 2 + BANK, within, across);
    }

    /** Average ns per move alternating between x={@code a} and x={@code b}; checks deliveries per move. */
    private static double run(Atchung bus, InputDispatcher d, RetainedNode root, int a, int b, AtomicLong deliveries,
                              int perMove) {
        // Settle on the first point, then warm up, then measure.
        bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved(a, 10, 0, 0, 0));
        d.dispatch(root);
        long start = 0;
        for (int round = 0; round < 2; round++) {
            deliveries.set(0);
            if (round == 1) {
                start = System.nanoTime();
            }
            for (int i = 0; i < MOVES; i++) {
                bus.publish(InputTopics.INPUT, new InputEvent.PointerMoved(i % 2 == 0 ? b : a, 10, 0, 0, 0));
                d.dispatch(root);
            }
            assertEquals((long) MOVES * perMove, deliveries.get());
        }
        return (System.nanoTime() - start) / (double) MOVES;
    }
}
