package dev.vexelray.gui.widget;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dialog sequencing: one on screen at a time, the rest in the order they were asked, and nothing lost when
 * several threads ask at once.
 */
class ModalQueueTest {

    private static Modal modal(String title) {
        return Modal.of(title, "?");
    }

    @Test
    void theFirstDialogShowsAndTheRestWait() {
        ModalQueue queue = new ModalQueue();
        Modal first = modal("first");
        Modal second = modal("second");

        assertSame(first, queue.offer(first), "nothing was showing: show it");
        assertNull(queue.offer(second), "one is up: this one waits");
        assertSame(first, queue.showing());
        assertEquals(1, queue.queued());
    }

    @Test
    void closingOneShowsTheNextInOrder() {
        ModalQueue queue = new ModalQueue();
        Modal a = modal("a");
        Modal b = modal("b");
        Modal c = modal("c");
        queue.offer(a);
        queue.offer(b);
        queue.offer(c);

        assertSame(b, queue.next(), "first asked of the waiting ones, first shown");
        assertSame(c, queue.next());
        assertNull(queue.next(), "and then the application is unblocked");
        assertNull(queue.showing());
        assertEquals(0, queue.queued());
    }

    @Test
    void aDialogOfferedAfterTheQueueDrainedShowsImmediately() {
        ModalQueue queue = new ModalQueue();
        Modal a = modal("a");
        queue.offer(a);
        queue.next();

        Modal later = modal("later");
        assertSame(later, queue.offer(later), "an empty queue shows the next question at once");
    }

    @Test
    void clearDropsTheWaitingOnesAndLeavesTheOneOnScreen() {
        ModalQueue queue = new ModalQueue();
        Modal showing = modal("showing");
        queue.offer(showing);
        queue.offer(modal("dropped"));
        queue.offer(modal("dropped too"));

        queue.clear();
        assertSame(showing, queue.showing(), "what is on screen still has a window to close");
        assertEquals(0, queue.queued());
        assertNull(queue.next(), "and nothing follows it");
    }

    @Test
    void concurrentAsksProduceExactlyOnePresentationEach() throws Exception {
        ModalQueue queue = new ModalQueue();
        int askers = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(askers);
        List<Modal> presentedNow = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < askers; i++) {
            Modal m = modal("q" + i);
            new Thread(() -> {
                try {
                    start.await();
                    Modal now = queue.offer(m);
                    if (now != null) {
                        presentedNow.add(now);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS), "the askers finished");

        assertEquals(1, presentedNow.size(), "exactly one thread is told to present — never two dialogs at once");
        assertEquals(askers - 1, queue.queued(), "and every other question is queued, not dropped");

        int drained = 0;
        while (queue.next() != null) {
            drained++;
        }
        assertEquals(askers - 1, drained, "all of them come back out");
    }
}
