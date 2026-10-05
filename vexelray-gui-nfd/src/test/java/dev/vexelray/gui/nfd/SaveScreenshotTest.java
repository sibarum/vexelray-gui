package dev.vexelray.gui.nfd;

import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.WindowInstrument;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of the asking screenshot that needs no dialog. The dialog itself needs the native library, a window
 * and a person, as {@link FileDialogTest} says.
 */
class SaveScreenshotTest {

    /** The same button as core's, so an agent that finds the screenshot by role finds this one. */
    @Test
    void itIsTheScreenshotButton() {
        assertEquals(WindowInstrument.screenshot().role(), SaveScreenshot.instrument().role());
    }

    @Test
    void aBareNameBecomesAPng() {
        assertEquals(Path.of("shots", "window.png"), SaveScreenshot.asPng(Path.of("shots", "window")));
    }

    @Test
    void aNameWithAnExtensionIsLeftAsTyped() {
        assertEquals(Path.of("window.png"), SaveScreenshot.asPng(Path.of("window.png")));
        assertEquals(Path.of("window.PNG"), SaveScreenshot.asPng(Path.of("window.PNG")));
    }

    /** No window, no dialog: nothing is posted and nothing is captured, and the native library is never loaded. */
    @Test
    void withNoWindowNothingIsAsked() {
        List<String> calls = new ArrayList<>();
        WindowControls headless = new WindowControls() {
            @Override public void minimize() { }
            @Override public void toggleMaximize() { }
            @Override public boolean maximized() { return false; }
            @Override public void close() { }
            @Override public void capture(String path) { calls.add("capture " + path); }
            @Override public void post(Runnable task) { calls.add("post"); }
        };

        SaveScreenshot.instrument().action().accept(headless);

        assertTrue(calls.isEmpty(), calls.toString());
    }

    /**
     * The click asks and returns; nothing is posted to the window's thread for the dialog itself (that is the
     * dialog lane's to decide), and the capture the answer ends in is posted there, not taken on the answering
     * thread.
     */
    @Test
    void anAnswerIsCapturedOnTheWindowsThread() {
        List<String> calls = new ArrayList<>();
        List<Runnable> posted = new ArrayList<>();
        WindowControls window = windowAt(42L, calls, posted);
        List<Long> parents = new ArrayList<>();

        SaveScreenshot.ask(window, (guiThread, parent, folder, name) -> {
            parents.add(parent);
            assertTrue(name.endsWith(".png"), name);
            return CompletableFuture.completedFuture(Optional.of(Path.of("shots", "window")));
        });

        assertEquals(List.of(42L), parents, "parented to the window whose button was clicked");
        assertTrue(calls.isEmpty(), "not captured off the window's thread: " + calls);
        assertEquals(1, posted.size());
        posted.get(0).run();
        assertEquals(List.of("capture " + Path.of("shots", "window.png").toAbsolutePath()), calls);
    }

    @Test
    void aCancelOrAFailureTakesNoPicture() {
        List<String> calls = new ArrayList<>();
        List<Runnable> posted = new ArrayList<>();
        WindowControls window = windowAt(42L, calls, posted);

        SaveScreenshot.ask(window, (guiThread, parent, folder, name) ->
                CompletableFuture.completedFuture(Optional.empty()));
        SaveScreenshot.ask(window, (guiThread, parent, folder, name) ->
                CompletableFuture.failedFuture(new UnsatisfiedLinkError("nfd")));

        assertTrue(posted.isEmpty(), posted.toString());
        assertTrue(calls.isEmpty(), calls.toString());
    }

    private static WindowControls windowAt(long handle, List<String> calls, List<Runnable> posted) {
        return new WindowControls() {
            @Override public void minimize() { }
            @Override public void toggleMaximize() { }
            @Override public boolean maximized() { return false; }
            @Override public void close() { }
            @Override public void capture(String path) { calls.add("capture " + path); }
            @Override public long osHandle() { return handle; }
            @Override public void post(Runnable task) { posted.add(task); }
        };
    }
}
