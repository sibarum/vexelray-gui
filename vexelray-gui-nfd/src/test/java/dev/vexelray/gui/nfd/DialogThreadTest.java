package dev.vexelray.gui.nfd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dialog thread's promises, checked with plain tasks standing in for dialogs — and, on Windows, with NFDe's
 * real init and quit, which need no window and no person.
 */
class DialogThreadTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    /** One thread, not the caller's, taking requests in the order they came. */
    @Test
    void everyTaskRunsOnTheOneThreadInOrder() throws Exception {
        DialogThread lane = new DialogThread("test-dialogs", () -> { });
        List<String> ran = new CopyOnWriteArrayList<>();
        List<Thread> on = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(3);
        for (String name : List.of("a", "b", "c")) {
            lane.execute(() -> {
                ran.add(name);
                on.add(Thread.currentThread());
                done.countDown();
            });
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        lane.shutdown(WAIT);

        assertEquals(List.of("a", "b", "c"), ran);
        assertEquals(1, on.stream().distinct().count(), "one thread, so one COM apartment");
        assertNotSame(Thread.currentThread(), on.get(0));
        assertTrue(on.get(0).isDaemon(), "a forgotten dialog must not keep the process alive");
    }

    /** One dialog the OS refused is not a reason for every later one to have nowhere to run. */
    @Test
    void aFailingTaskDoesNotEndTheThread() throws Exception {
        DialogThread lane = new DialogThread("test-dialogs", () -> { });
        CompletableFuture<String> after = new CompletableFuture<>();
        lane.execute(() -> {
            throw new IllegalStateException("the OS said no");
        });
        lane.execute(() -> after.complete("still here"));

        assertEquals("still here", after.get(5, TimeUnit.SECONDS));
        lane.shutdown(WAIT);
    }

    /** The quit is the thread's own, so it runs on the thread the init ran on — and only if a dialog ever did. */
    @Test
    void theExitRunsOnTheDialogThreadOnlyIfItWasUsed() throws Exception {
        CompletableFuture<Thread> exitedOn = new CompletableFuture<>();
        CompletableFuture<Thread> ranOn = new CompletableFuture<>();
        DialogThread used = new DialogThread("test-dialogs", () -> exitedOn.complete(Thread.currentThread()));
        used.execute(() -> ranOn.complete(Thread.currentThread()));
        ranOn.get(5, TimeUnit.SECONDS);

        assertTrue(used.shutdown(WAIT));
        assertEquals(ranOn.get(), exitedOn.getNow(null));

        List<String> exits = new CopyOnWriteArrayList<>();
        DialogThread idle = new DialogThread("test-dialogs", () -> exits.add("quit"));
        assertTrue(idle.shutdown(WAIT));
        assertTrue(exits.isEmpty(), "a thread that never showed a dialog has nothing to release");
    }

    /** Shutdown finishes the dialog that is up, then unwinds; it never pulls one out from under the person. */
    @Test
    void shutdownWaitsForTheDialogInFlight() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        DialogThread lane = new DialogThread("test-dialogs", () -> order.add("quit"));
        CountDownLatch started = new CountDownLatch(1);
        lane.execute(() -> {
            started.countDown();
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
            while (System.nanoTime() < until) {
                Thread.onSpinWait();   // a native modal loop does not notice an interrupt either
            }
            order.add("dialog answered");
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertTrue(lane.shutdown(WAIT));
        assertEquals(List.of("dialog answered", "quit"), order);
        assertThrows(RejectedExecutionException.class, () -> lane.execute(() -> { }));
    }

    /**
     * The real thing, short of a dialog: NFDe initialised on the dialog thread, a late {@link Nfd#quit()} from
     * another thread doing nothing rather than throwing, and the dialog thread's own exit releasing it.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void nfdIsInitialisedAndQuitOnTheDialogThreadAlone() throws Exception {
        CompletableFuture<Boolean> afterQuit = new CompletableFuture<>();
        DialogThread lane = new DialogThread("test-dialogs", () -> {
            Nfd.quit();
            afterQuit.complete(Nfd.initialisedHere());
        });
        CompletableFuture<Boolean> initialised = new CompletableFuture<>();
        lane.execute(() -> {
            Nfd.ensureInit();
            Nfd.ensureInit();   // idempotent per thread
            initialised.complete(Nfd.initialisedHere());
        });
        assertTrue(initialised.get(5, TimeUnit.SECONDS));

        assertFalse(Nfd.initialisedHere(), "the init belongs to the dialog thread, not to this one");
        Nfd.quit();   // what an application written before the dialog thread still does at shutdown: harmless

        assertTrue(lane.shutdown(WAIT));
        assertFalse(afterQuit.getNow(true), "the dialog thread released its own init");
    }
}
