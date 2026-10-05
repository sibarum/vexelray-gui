package dev.vexelray.gui.nfd;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What the typed API hands the FFM layer, checked without a dialog to open.
 *
 * <p>Only the pure half is reachable here — the formatting of a filter into NFDe's {name, spec} pair. Opening a
 * dialog needs the native library, a window and a person, so it is not a thing a suite can assert; the value of
 * testing this half is that the spec is a string format with rules, and a string format with rules is exactly
 * what drifts silently.
 */
class FileDialogTest {

    @Test
    void aFilterBecomesANameAndACommaSeparatedSpec() {
        String[][] out = FileDialog.toNfdFilters(List.of(
                FileDialog.Filter.of("Images", "png", "jpg", "webp"),
                FileDialog.Filter.of("Vector", "svg")));

        assertEquals(2, out.length);
        assertArrayEquals(new String[]{"Images", "png,jpg,webp"}, out[0]);
        assertArrayEquals(new String[]{"Vector", "svg"}, out[1]);
    }

    /** NFDe wants extensions without dots, and a caller who writes one is being helpful rather than wrong. */
    @Test
    void aLeadingDotIsTakenOff() {
        String[][] out = FileDialog.toNfdFilters(List.of(FileDialog.Filter.of("Images", ".png", "jpg", ".webp")));
        assertArrayEquals(new String[]{"Images", "png,jpg,webp"}, out[0]);
    }

    /**
     * Null rather than an empty array, because they mean different things at the boundary: the dialog reads a
     * count beside the pointer, and a zero count with a non-null list is a shape NFDe is not promised.
     */
    @Test
    void noFiltersIsNoFilterList() {
        assertNull(FileDialog.toNfdFilters(null));
        assertNull(FileDialog.toNfdFilters(List.of()));
        assertNull(FileDialog.toNfdFilters(new ArrayList<>()));
    }

    /**
     * The asynchronous shape, with a plain thread standing in for the dialog's lane: the dialog runs on the lane,
     * and the answer is completed on neither the caller nor the lane — so a dependent cannot stall the frame loop
     * (macOS, where the lane is the GUI thread) or hold up the next dialog (Windows).
     */
    @Test
    void anAnswerArrivesOffTheLaneAndOffTheCaller() throws Exception {
        ExecutorService lane = Executors.newSingleThreadExecutor();
        try {
            Thread laneThread = lane.submit(Thread::currentThread).get();
            CompletableFuture<Thread> askedOn = new CompletableFuture<>();
            CompletableFuture<Thread> answeredOn = new CompletableFuture<>();
            // The person is still looking until the dependent is attached; one attached to a future that has
            // already completed runs on the attaching thread, as any CompletableFuture's does.
            java.util.concurrent.CountDownLatch looking = new java.util.concurrent.CountDownLatch(1);

            FileDialog.ask(() -> lane, () -> {
                askedOn.complete(Thread.currentThread());
                try {
                    looking.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "picked";
            }).whenComplete((picked, failure) -> answeredOn.complete(Thread.currentThread()));
            looking.countDown();

            Thread answered = answeredOn.get(5, TimeUnit.SECONDS);
            assertSame(laneThread, askedOn.get());
            assertNotSame(laneThread, answered);
            assertNotSame(Thread.currentThread(), answered);
        } finally {
            lane.shutdownNow();
        }
    }

    /** A dialog the OS refused, a library that would not load, a platform with no lane: all failures, none thrown. */
    @Test
    void aFailureIsAnExceptionalAnswerAndNotAThrow() {
        CompletableFuture<String> refused = FileDialog.ask(() -> Runnable::run, () -> {
            throw new RuntimeException("NFD error: the OS said no");
        });
        assertEquals("NFD error: the OS said no", failureOf(refused).getMessage());

        CompletableFuture<String> unlinked = FileDialog.ask(() -> Runnable::run, () -> {
            throw new UnsatisfiedLinkError("nfd");
        });
        assertInstanceOf(UnsatisfiedLinkError.class, failureOf(unlinked));

        CompletableFuture<String> nowhere = FileDialog.ask(() -> Os.of("Linux").dialogLane(Runnable::run),
                () -> "never asked");
        assertInstanceOf(UnsatisfiedLinkError.class, failureOf(nowhere));
    }

    private static Throwable failureOf(CompletableFuture<?> future) {
        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        return e.getCause();
    }

    /** A filter row with no extension would format to an empty spec, which NFDe shows as a dead dropdown entry. */
    @Test
    void aFilterMustOfferSomething() {
        assertThrows(IllegalArgumentException.class, () -> FileDialog.Filter.of("Images"));
        assertThrows(IllegalArgumentException.class, () -> new FileDialog.Filter("Images", List.of()));
        assertThrows(NullPointerException.class, () -> new FileDialog.Filter(null, List.of("png")));
        assertThrows(NullPointerException.class, () -> new FileDialog.Filter("Images", null));
    }
}
