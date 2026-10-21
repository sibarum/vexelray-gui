package dev.vexelray.gui.nfd;

import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.WindowInstrument;
import dev.vexelray.gui.core.WindowRecorder;
import dev.vexelray.gui.core.WindowRecording;
import dev.vexelray.gui.core.input.MenuSink;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The camera as a menu, where a module records video: <i>Take screenshot…</i>, and <i>Record video to file…</i>,
 * which asks where the file goes in a native save dialog parented to the window whose camera was clicked, or, while a
 * recording runs, <i>Stop recording</i>. The recording itself is {@link WindowRecording}'s, which this only starts and
 * stops; the automation socket's {@code record} verb starts and stops the same one, told rather than asked where.
 *
 * <p><b>Threads.</b> The menu runs on the GUI thread and returns. The dialog runs where the OS lets it, as the
 * screenshot's does ({@link SaveScreenshot}); the encoder is opened on the pool thread the answer lands on, never the
 * window's.
 */
public final class SaveRecording {

    private static final sibarum.probe.Log LOG = sibarum.probe.Log.of("gui.nfd");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static volatile Path lastFolder;

    private SaveRecording() {
    }

    /** The camera with a menu that screenshots and records, through {@code recorder}. */
    public static WindowInstrument instrument(WindowRecorder recorder) {
        return WindowInstrument.camera("Screenshot or record this window",
                (controls, sink) -> menu(recorder, controls, sink), WindowRecording::recording, WindowRecording.LIVE);
    }

    static void menu(WindowRecorder recorder, WindowControls controls, MenuSink sink) {
        boolean recording = WindowRecording.recording();
        if (recording) {
            sink.item("Stop recording", SaveRecording::stop);
        }
        sink.item("Take screenshot…", () -> SaveScreenshot.ask(controls));
        if (!recording) {
            sink.item("Record video to file…", !WindowRecording.starting(),
                    () -> start(recorder, controls, (guiThread, parent, folder, name) -> FileDialog.saveAsync(
                            guiThread, parent, List.of(FileDialog.Filter.of(recorder.fileType(), recorder.extension())),
                            folder, name)));
        }
    }

    /** The save dialog, as {@link #start} asks it: a seam, so a test needs no native one. */
    @FunctionalInterface
    interface SaveDialog {
        CompletableFuture<Optional<Path>> ask(Executor guiThread, long parent, Path folder, String name);
    }

    static void start(WindowRecorder recorder, WindowControls controls, SaveDialog dialog) {
        long parent = controls.osHandle();
        if (parent == 0L) {
            return;     // no window: nothing to record, and nothing to be modal to
        }
        String name = "vexelray-recording-" + LocalDateTime.now().format(STAMP) + "." + recorder.extension();
        dialog.ask(controls::post, parent, lastFolder, name).whenComplete((chosen, failure) -> {
            if (failure != null) {
                LOG.error("could not open the save dialog for a recording", failure);
                return;
            }
            chosen.ifPresent(path -> {
                Path file = withExtension(path, recorder.extension()).toAbsolutePath();
                lastFolder = file.getParent();
                try {
                    WindowRecording.start(controls, file);
                } catch (IOException e) {
                    LOG.error("could not record to " + file + ": " + e.getMessage());
                }
            });
        });
    }

    private static void stop() {
        WindowRecording.stop().ifPresent(done -> done.whenComplete((file, failure) -> {
            if (failure != null) {
                LOG.error("the recording did not finish whole: " + failure.getMessage());
            }
        }));
    }

    /** The path with {@code .ext} on the end if it has no extension at all, as {@link SaveScreenshot#asPng} does. */
    static Path withExtension(Path path, String ext) {
        String name = path.getFileName().toString();
        return name.indexOf('.') >= 0 ? path : path.resolveSibling(name + "." + ext);
    }
}
