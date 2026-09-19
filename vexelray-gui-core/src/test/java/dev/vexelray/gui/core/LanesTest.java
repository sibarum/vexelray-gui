package dev.vexelray.gui.core;

import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One application means one set of threads, and that is only true if a {@code Gui} handed a lane actually uses
 * it and does not outlive it by building its own.
 *
 * <p>The thing being held here is not the pool, it is <b>who owns it</b>. The worker pool used to be a field
 * initializer, so a {@code Gui} built a {@code newCachedThreadPool} whatever it was handed: passing an executor
 * redirected input handlers and left the pool standing, and an application's thread count was therefore a
 * property of how many trees it happened to hold rather than of anything it decided. A container that means to
 * place work on a thread cannot do it while that is true, which is why these two assertions come before any of
 * the work that depends on them.
 */
class LanesTest {

    @Test
    void offloadedWorkRunsOnTheLaneItWasGiven() throws Exception {
        ExecutorService lane = Executors.newSingleThreadExecutor(r -> new Thread(r, "the-embedders-lane"));
        AtomicReference<String> ran = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        try (Gui gui = new Gui(Atchung.create(), Runnable::run, lane)) {
            gui.async(() -> {
                ran.set(Thread.currentThread().getName());
                done.countDown();
            });
            assertTrue(done.await(5, TimeUnit.SECONDS), "offloaded work never ran");
        }
        // Not merely "on some thread": on that one. A Gui that quietly kept a pool of its own would pass a
        // liveness check and fail this, which is the distinction the change is about.
        org.junit.jupiter.api.Assertions.assertEquals("the-embedders-lane", ran.get());
        assertFalse(lane.isShutdown(), "close() shut down a lane it was handed rather than one it built");
        lane.shutdownNow();
    }

    @Test
    void aGuiShutsDownOnlyTheLanesItBuiltItself() throws Exception {
        ExecutorService handlers = Executors.newSingleThreadExecutor();
        ExecutorService offload = Executors.newSingleThreadExecutor();

        // The case this is really about: two trees on one application's lanes. Closing a dialog's tree must
        // leave the main window's threads alone, and before the split there was nothing to get that wrong with
        // because every tree brought its own.
        Gui dialogs = new Gui(Atchung.create(), handlers, offload);
        dialogs.close();

        assertFalse(handlers.isShutdown(), "closing one tree shut down the application's handler lane");
        assertFalse(offload.isShutdown(), "closing one tree shut down the application's offload lane");

        CountDownLatch stillAlive = new CountDownLatch(1);
        offload.execute(stillAlive::countDown);
        assertTrue(stillAlive.await(5, TimeUnit.SECONDS), "the lane was left unusable after a tree closed");

        handlers.shutdownNow();
        offload.shutdownNow();
    }

    @Test
    void theTwoDefaultLanesAreDistinctAndNamedApart() throws Exception {
        // "Worker thread" meant both lanes, which is one name for two things with different rules in the
        // documentation applications read. A handler is short application code answering an input event; an
        // offloaded task is a file read that may outlast any number of frames. They are told apart by name here
        // because that is how they are told apart in a thread dump.
        AtomicReference<String> handler = new AtomicReference<>();
        AtomicReference<String> offloaded = new AtomicReference<>();
        CountDownLatch both = new CountDownLatch(2);
        try (Gui gui = new Gui(Atchung.create())) {
            gui.handlers().execute(() -> {
                handler.set(Thread.currentThread().getName());
                both.countDown();
            });
            gui.async(() -> {
                offloaded.set(Thread.currentThread().getName());
                both.countDown();
            });
            assertTrue(both.await(5, TimeUnit.SECONDS), "a default lane never ran its work");
        }
        assertNotEquals(handler.get(), offloaded.get());
        assertTrue(handler.get().startsWith("vexelray-gui-handler"), handler.get());
        assertTrue(offloaded.get().startsWith("vexelray-gui-offload"), offloaded.get());
    }
}
