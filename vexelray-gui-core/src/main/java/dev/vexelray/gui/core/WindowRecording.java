package dev.vexelray.gui.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The one recording a process can be making: a window's presented frames going to a {@link WindowRecorder}, started
 * and stopped from the camera's menu ({@code vexelray-gui-nfd}'s {@code SaveRecording}, which asks where) or from the
 * automation socket's {@code record} verb (which is told).
 *
 * <p><b>One at a time, and the process's.</b> While a recording runs, every window's camera shows it
 * ({@link WindowInstrument#camera}, re-authored through {@link #LIVE}) and every one can stop it. A second start is
 * refused until the first has stopped, so two windows never race for one encoder's attention.
 *
 * <p><b>The canvas is the screen.</b> Every frame in the file is the size of the usable area of the monitor the window
 * is on when the recording starts, or of the window if that is larger, so the window can be resized while it records
 * and keep its own pixels ({@link WindowRecorder#start}).
 *
 * <p><b>Finished whatever happens.</b> {@link #stop} hands the window's last frames over before finishing the file,
 * holding the last frame until the moment of the stop. A window closed while it records finishes its file as it
 * closes ({@link #windowClosed}), and an application that exits some other way finishes it from a shutdown hook.
 * One killed outright finishes nothing, and its file still plays up to about its last second, because the writer
 * puts every frame on the disk as it goes.
 *
 * <p>Every method may be called from any thread. {@link #start} and the futures' completions block on the encoder,
 * so they are called off the GUI thread.
 */
public final class WindowRecording {

    private static final sibarum.probe.Log LOG = sibarum.probe.Log.of("gui.record");

    private static final Object LOCK = new Object();
    private static final List<Runnable> WATCHERS = new CopyOnWriteArrayList<>();

    /** The recording in progress, or null. Guarded by LOCK. */
    private static Active active;
    /** An encoder is being opened: a second start waits for it to settle rather than racing it. Guarded by LOCK. */
    private static boolean starting;

    private record Active(WindowControls controls, WindowRecorder.Session session, int width, int height) {
    }

    /**
     * What a camera watches so its mark and tooltip follow the recording: changed on every start and stop, in every
     * window.
     */
    public static final WindowInstrument.Live LIVE = new WindowInstrument.Live() {

        @Override
        public Runnable watch(Runnable changed) {
            WATCHERS.add(changed);
            return () -> WATCHERS.remove(changed);
        }

        @Override
        public String tooltip() {
            synchronized (LOCK) {
                return active == null ? null
                        : "Recording to " + active.session().file().getFileName() + ": click to stop";
            }
        }
    };

    static {
        // An application closed mid-recording still leaves a whole file: what was encoded, finished.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Active a;
            synchronized (LOCK) {
                a = active;
                active = null;
            }
            if (a != null) {
                finish(a.session(), System.nanoTime());
            }
        }, "recording-shutdown"));
    }

    private WindowRecording() {
    }

    /** Whether a recording can be made at all: a {@link WindowRecorder} is on the class path. */
    public static boolean available() {
        return WindowRecorder.find().isPresent();
    }

    /** Whether a recording is in progress, in any window. */
    public static boolean recording() {
        synchronized (LOCK) {
            return active != null;
        }
    }

    /** Whether a recording is being started: the encoder opening. */
    public static boolean starting() {
        synchronized (LOCK) {
            return starting;
        }
    }

    /** The file being recorded to, while there is a recording. */
    public static Optional<Path> file() {
        synchronized (LOCK) {
            return active == null ? Optional.empty() : Optional.of(active.session().file());
        }
    }

    /**
     * Start recording the window {@code controls} command into {@code file}, replacing one already there. Blocking: it
     * opens the encoder and creates the file, then waits (briefly) for the window to start handing over its frames.
     * Returns the canvas, {@code {width, height}}. Throws, with a reason fit to show, when no recorder is on the class
     * path, a recording is already in progress, the file cannot be made, or the window cannot be read back.
     */
    public static int[] start(WindowControls controls, Path file) throws IOException {
        WindowRecorder recorder = WindowRecorder.find()
                .orElseThrow(() -> new IOException("nothing here records video: no WindowRecorder on the class path"
                        + " (vexelray-codec-video is the module that offers one)"));
        if (controls.osHandle() == 0L) {
            throw new IOException("there is no window to record");
        }
        synchronized (LOCK) {
            if (active != null) {
                throw new IOException("already recording to " + active.session().file());
            }
            if (starting) {
                throw new IOException("a recording is already being started");
            }
            starting = true;
        }
        Active a = null;
        try {
            int[] canvas = canvas(controls);
            WindowRecorder.Session session = recorder.start(file.toAbsolutePath(), canvas[0], canvas[1],
                    System.nanoTime());
            a = new Active(controls, session, canvas[0], canvas[1]);
            Boolean tapped;
            try {
                tapped = controls.tap(session).get(10, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                tapped = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                tapped = false;
            }
            if (!Boolean.TRUE.equals(tapped)) {
                controls.tap(null);
                finish(session, System.nanoTime());
                a = null;
                throw new IOException("this window cannot be recorded: its presenter does not read its frames back");
            }
            LOG.info("recording {} at {}x{}", session.file(), canvas[0], canvas[1]);
            return canvas;
        } finally {
            synchronized (LOCK) {
                starting = false;
                active = a;
            }
            changed();
        }
    }

    /**
     * Stop the recording in progress, if there is one: the window's last frames handed over, the last frame held until
     * now, and the file finished. Returns at once; the future completes with the file once it is whole, or
     * exceptionally with why it is not. Empty when nothing was recording.
     */
    public static Optional<CompletableFuture<Path>> stop() {
        Active a;
        synchronized (LOCK) {
            a = active;
            active = null;
        }
        if (a == null) {
            return Optional.empty();
        }
        changed();
        long end = System.nanoTime();
        CompletableFuture<Path> done = new CompletableFuture<>();
        a.controls().tap(null).whenCompleteAsync((ignored, why) -> {
            try {
                finish(a.session(), end);
                String failed = a.session().failure();
                if (failed != null) {
                    done.completeExceptionally(new IOException(failed));
                } else {
                    done.complete(a.session().file());
                }
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            }
        });
        return Optional.of(done);
    }

    /**
     * The window with OS handle {@code osHandle} has closed, its presenter with it, after handing every frame it had
     * copied to the recording. If that window was being recorded, the recording ends here: the last frame held until
     * now and the file finished, on this thread, before the application goes on to exit. Called by the GUI's window
     * teardown; a window that was not being recorded changes nothing.
     */
    public static void windowClosed(long osHandle) {
        Active a;
        synchronized (LOCK) {
            a = active;
            if (a == null || osHandle == 0L || a.controls().osHandle() != osHandle) {
                return;
            }
            active = null;
        }
        changed();
        finish(a.session(), System.nanoTime());
    }

    /** Finish {@code session}, saying in the log what became of it. */
    private static void finish(WindowRecorder.Session session, long end) {
        try {
            session.finish(end);
            LOG.info("recorded {}", session.file());
        } catch (IOException | RuntimeException e) {
            LOG.error("the recording to {} is not whole: {}", session.file(), e.getMessage());
        }
    }

    private static void changed() {
        for (Runnable w : WATCHERS) {
            w.run();
        }
    }

    /** The monitor's usable area, or the window where that is larger or unknown: what every frame is drawn on. */
    static int[] canvas(WindowControls controls) {
        int w = controls.width();
        int h = controls.height();
        var area = controls.workArea();
        if (area.isPresent()) {
            w = Math.max(w, area.get().width());
            h = Math.max(h, area.get().height());
        }
        return new int[] {Math.max(w, 2), Math.max(h, 2)};
    }
}
