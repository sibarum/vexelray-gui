package dev.vexelray.gui.nfd;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;

/**
 * One long-lived thread that native file dialogs run on, one at a time, where the OS lets a dialog run somewhere
 * other than the thread that owns the window — Windows, today ({@link Os#dialogLane}).
 *
 * <p><b>Why a thread of its own.</b> A dialog is modal and synchronous: the call does not return until the person
 * does. Run on the frame loop, that is a window that stops drawing and taking input for as long as somebody reads
 * a folder listing, and a stall report that blames whoever asked. Run here, the dialog's owner window is disabled
 * by the dialog — which is what modal means — while the owner's own thread keeps pumping and drawing.
 *
 * <p><b>Why one, and long-lived.</b> On Windows {@code NFD_Init} enters a COM single-threaded apartment, and an
 * apartment belongs to the thread that entered it: the init, every dialog and the {@code NFD_Quit} that pairs the
 * init all have to be on one thread. A pool would scatter them; a thread per dialog would pay an apartment per
 * dialog. So this thread does all three, and does the quit itself on its way out ({@link #onExit}), which is why
 * an application no longer has to.
 *
 * <p><b>On its way out</b> means one of two things. The JVM's shutdown runs a hook that {@linkplain #shutdown
 * asks} this thread to stop and waits briefly for it; the thread finishes the dialog it is in, if any, and
 * unwinds through {@code NFD_Quit} on itself. A dialog still open when that wait runs out is left to the
 * process's exit — the thread is a daemon precisely so that a forgotten dialog cannot keep a process alive.
 *
 * <p>Idle, it waits on a Java queue rather than pumping messages. That is fine for this apartment: it owns no
 * window between dialogs and exports no object, so there is nothing to deliver to it. {@code IFileDialog::Show}
 * runs its own modal loop while a dialog is up.
 */
final class DialogThread implements Executor {

    private static final sibarum.probe.Log LOG = sibarum.probe.Log.of("gui.nfd");

    /** How long the shutdown hook waits for the thread to unwind through {@code NFD_Quit}. */
    private static final Duration EXIT_WAIT = Duration.ofSeconds(1);

    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final Thread thread;
    private final Runnable onExit;

    /** Whether any task ever ran — so a thread that never showed a dialog never loads the native library to quit it. */
    private volatile boolean used;
    private volatile boolean closed;

    /**
     * @param name   the thread's name, which is what a stack dump and a stall report show
     * @param onExit run on this thread as it stops, if any task ran on it first: the pair of whatever the tasks
     *               set up for themselves on this thread — {@code Nfd::quit}, in production
     */
    DialogThread(String name, Runnable onExit) {
        this.onExit = onExit;
        this.thread = new Thread(this::loop, name);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private static final class Shared {
        static final DialogThread INSTANCE = start();

        private static DialogThread start() {
            DialogThread lane = new DialogThread("vexelray-file-dialogs", Nfd::quit);
            Runtime.getRuntime().addShutdownHook(
                    new Thread(() -> lane.shutdown(EXIT_WAIT), "vexelray-file-dialogs-exit"));
            return lane;
        }
    }

    /** The process's dialog thread, started on first use. */
    static DialogThread shared() {
        return Shared.INSTANCE;
    }

    /**
     * Queue {@code task} behind any dialog already open or waiting. Safe from any thread.
     *
     * @throws RejectedExecutionException once the thread has been asked to stop
     */
    @Override
    public void execute(Runnable task) {
        if (closed) {
            throw new RejectedExecutionException(thread.getName() + " has stopped: the JVM is shutting down");
        }
        queue.add(task);
    }

    /** Whether the caller is this thread. */
    boolean isCurrent() {
        return Thread.currentThread() == thread;
    }

    /**
     * Stop taking work and wait up to {@code wait} for the thread to finish the dialog it is in, if any, and run
     * {@link #onExit} on itself. A request queued after this is rejected; one already queued and not yet started
     * is dropped, which is only ever a dialog the process is exiting too soon to show.
     *
     * @return whether the thread had stopped by the time the wait ended
     */
    boolean shutdown(Duration wait) {
        closed = true;
        thread.interrupt();
        try {
            thread.join(Math.max(1L, wait.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !thread.isAlive();
    }

    private void loop() {
        try {
            while (!closed) {
                Runnable task = queue.take();
                used = true;
                try {
                    task.run();
                } catch (RuntimeException | LinkageError e) {
                    // Tasks report their own failures; this is the backstop that keeps one bad request from
                    // ending the thread every later dialog would have run on.
                    LOG.error("a file dialog task failed on " + thread.getName(), e);
                }
            }
        } catch (InterruptedException e) {
            // Asked to stop: unwind.
        } finally {
            if (used) {
                try {
                    onExit.run();
                } catch (RuntimeException | LinkageError e) {
                    LOG.warn("could not release the file dialogs on " + thread.getName(), e);
                }
            }
        }
    }
}
