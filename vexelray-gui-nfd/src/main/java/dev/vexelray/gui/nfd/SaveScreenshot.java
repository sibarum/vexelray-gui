package dev.vexelray.gui.nfd;

import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.WindowInstrument;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The screenshot instrument that asks where the picture goes: a native save dialog, parented to the window whose
 * button was clicked, and no picture at all if it is cancelled.
 *
 * <p><b>Why it asks.</b> {@link WindowInstrument#screenshot()} files a timestamped PNG in the working directory,
 * which is right for a script and wrong for a person: the working directory of a desktop application is wherever
 * it happened to be launched from, and nothing on screen says where that is. A person who clicks a camera expects
 * to be asked, and to find the file where they said.
 *
 * <p><b>Here and not in core</b> because core has no native dialog to open; this module is the GUI's one native
 * binding. The button itself — role, mark — is core's, so an agent finds it by the same name either way.
 *
 * <p><b>Threads.</b> The click runs on a worker, which asks {@link FileDialog#saveAsync} and returns. The dialog
 * runs where the OS lets it: on Windows the module's dialog thread, so the window goes on drawing behind it; on
 * macOS the window's own thread, reached through {@link WindowControls#post}. The answer lands on a pool thread,
 * and the capture is posted from there to the window's thread — so the picture is taken after the dialog has
 * gone, on a frame that does not have it in it.
 */
public final class SaveScreenshot {

    private static final sibarum.probe.Log LOG = sibarum.probe.Log.of("gui.nfd");

    private static final FileDialog.Filter PNG = FileDialog.Filter.of("PNG image", "png");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * The folder the last picture went to, so a run of screenshots does not each start from the OS's idea of a
     * folder. Process-wide on purpose: a screenshot of a popup and one of the main window are the same errand.
     */
    private static volatile Path lastFolder;

    private SaveScreenshot() {
    }

    /** The screenshot button, asking for a path on every click. */
    public static WindowInstrument instrument() {
        return WindowInstrument.screenshot("Save a screenshot of this window", SaveScreenshot::ask);
    }

    private static void ask(WindowControls controls) {
        ask(controls, (guiThread, parent, folder, name) ->
                FileDialog.saveAsync(guiThread, parent, List.of(PNG), folder, name));
    }

    /** The save dialog, as {@link #ask(WindowControls, SaveDialog)} asks it — a seam, so a test needs no native one. */
    @FunctionalInterface
    interface SaveDialog {
        CompletableFuture<Optional<Path>> ask(Executor guiThread, long parent, Path folder, String name);
    }

    static void ask(WindowControls controls, SaveDialog dialog) {
        long parent = controls.osHandle();
        if (parent == 0L) {
            // No window: nothing to photograph, and nothing to be modal to.
            return;
        }
        dialog.ask(controls::post, parent, lastFolder, defaultName()).whenComplete((chosen, failure) -> {
            if (failure != null) {
                // A missing native library or a dialog the OS refused is not a reason to take the application
                // down. Saying so is: an instrument that fails silently is exactly the thing someone would then
                // be troubleshooting.
                LOG.error("could not open the save dialog for a screenshot", failure);
                return;
            }
            chosen.ifPresent(path -> {
                Path png = asPng(path).toAbsolutePath();
                lastFolder = png.getParent();
                // Posted, because this is a pool thread and the controls are the window thread's to call.
                controls.post(() -> controls.capture(png.toString()));
            });
        });
    }

    private static String defaultName() {
        return "vexelray-shot-" + LocalDateTime.now().format(STAMP) + ".png";
    }

    /**
     * The path with {@code .png} on the end if it has no extension at all. The picture is a PNG whatever it is
     * called; a name typed bare would otherwise be a file no viewer knows how to open. A name with some other
     * extension is left as typed, because someone typed it.
     */
    static Path asPng(Path path) {
        String name = path.getFileName().toString();
        return name.indexOf('.') >= 0 ? path : path.resolveSibling(name + ".png");
    }
}
