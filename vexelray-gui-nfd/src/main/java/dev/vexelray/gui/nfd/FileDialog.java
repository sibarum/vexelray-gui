package dev.vexelray.gui.nfd;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Native OS file dialogs (open, save, pick folder) via NFDe. Every dialog is modal to its parent window.
 *
 * <h2>Ask with the {@code *Async} methods</h2>
 *
 * {@link #openAsync}, {@link #saveAsync} and {@link #pickFolderAsync} return at once, from any thread, and hand
 * back a future that completes with the answer. They take {@code guiThread} — the way onto the thread that owns
 * the window, {@code app::post} or {@code controls::post} — and the module decides where the dialog runs:
 * <ul>
 *   <li><b>Windows:</b> on the module's dialog thread, one at a time. The parent window is disabled while the
 *       dialog is up and <b>goes on drawing</b>, because its thread is not the one waiting. That thread did the
 *       {@code NFD_Init} and does the {@code NFD_Quit} itself at JVM shutdown; the application quits nothing.</li>
 *   <li><b>macOS:</b> on {@code guiThread}, because AppKit's panels are main-thread only. The frame loop stops
 *       for as long as the dialog is up, as it always has; that is the OS's rule, not this module's.</li>
 *   <li><b>Anywhere else:</b> no NFDe build ships, and the future fails with {@link UnsatisfiedLinkError}.</li>
 * </ul>
 * The future completes on a pool thread — never on the GUI thread nor the dialog thread — so what a caller
 * chains onto it runs off both: hand anything that touches the tree back with {@code guiThread}. (A dependent
 * attached after the future has already completed runs on the attaching thread, as with any
 * {@link CompletableFuture}.) A cancel is an empty {@code Optional}; a dialog the OS refused, or a native library
 * that would not load, completes it exceptionally.
 *
 * <h2>The synchronous methods</h2>
 *
 * {@link #open}, {@link #save} and {@link #pickFolder} run the dialog on the calling thread and block it until the
 * person answers. They are for a caller already on the right thread that can afford to wait — on Windows that is
 * not the GUI thread of a window that should keep drawing. A thread that opens a dialog this way has
 * initialised NFDe for itself and calls {@link Nfd#quit()} from that same thread when done. Do not block the
 * GUI thread waiting on an {@code *Async} future either: on Windows the dialog disables its owner with a message
 * the owner's thread has to receive, so a GUI thread that waits without pumping waits forever.
 *
 * <p>The parent handle is the raw OS window handle as returned by {@code GuiApp.windowHandle()} — an HWND on
 * Windows, an NSWindow* on macOS. Pass 0 for no parent.
 */
public final class FileDialog {

    /**
     * One row in a dialog's filter dropdown.
     *
     * @param description  human-readable name shown in the filter dropdown ("Images")
     * @param extensions   extensions without leading dot ("png", "jpg"); at least one required
     */
    public record Filter(String description, List<String> extensions) {
        public Filter {
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(extensions, "extensions");
            if (extensions.isEmpty()) {
                throw new IllegalArgumentException("filter must have at least one extension");
            }
        }

        public static Filter of(String description, String... extensions) {
            return new Filter(description, List.of(extensions));
        }
    }

    private FileDialog() {}

    /** Pick a single existing file. */
    public static Optional<Path> open(long parentWindow, List<Filter> filters, Path defaultPath) {
        return result(Nfd.openDialog(
            handleOf(parentWindow), handleTypeOf(parentWindow),
            toNfdFilters(filters), pathString(defaultPath)
        ));
    }

    /** Pick a destination file. NFDe handles overwrite confirmation per OS. */
    public static Optional<Path> save(long parentWindow, List<Filter> filters,
                                      Path defaultPath, String defaultName) {
        return result(Nfd.saveDialog(
            handleOf(parentWindow), handleTypeOf(parentWindow),
            toNfdFilters(filters), pathString(defaultPath), defaultName
        ));
    }

    /** Pick a directory. */
    public static Optional<Path> pickFolder(long parentWindow, Path defaultPath) {
        return result(Nfd.pickFolder(
            handleOf(parentWindow), handleTypeOf(parentWindow), pathString(defaultPath)
        ));
    }

    /** {@link #open}, run where this OS lets dialogs run; see the class note. Safe from any thread. */
    public static CompletableFuture<Optional<Path>> openAsync(Executor guiThread, long parentWindow,
                                                              List<Filter> filters, Path defaultPath) {
        return ask(guiThread, () -> open(parentWindow, filters, defaultPath));
    }

    /** {@link #save}, run where this OS lets dialogs run; see the class note. Safe from any thread. */
    public static CompletableFuture<Optional<Path>> saveAsync(Executor guiThread, long parentWindow,
                                                              List<Filter> filters, Path defaultPath,
                                                              String defaultName) {
        return ask(guiThread, () -> save(parentWindow, filters, defaultPath, defaultName));
    }

    /** {@link #pickFolder}, run where this OS lets dialogs run; see the class note. Safe from any thread. */
    public static CompletableFuture<Optional<Path>> pickFolderAsync(Executor guiThread, long parentWindow,
                                                                    Path defaultPath) {
        return ask(guiThread, () -> pickFolder(parentWindow, defaultPath));
    }

    private static <T> CompletableFuture<T> ask(Executor guiThread, Supplier<T> dialog) {
        Objects.requireNonNull(guiThread, "guiThread");
        return ask(() -> Os.current().dialogLane(guiThread), dialog);
    }

    /**
     * Run {@code dialog} on the executor {@code lane} names, and complete the answer on the future's default async
     * pool rather than on the lane — so a slow dependent neither stalls the frame loop (macOS) nor holds up the
     * next dialog (Windows). Package-private, with the lane as a parameter, so that routing can be tested with no
     * dialog to open.
     */
    static <T> CompletableFuture<T> ask(Supplier<Executor> lane, Supplier<T> dialog) {
        CompletableFuture<T> answer = new CompletableFuture<>();
        Executor handOff = answer.defaultExecutor();
        try {
            lane.get().execute(() -> {
                T value;
                try {
                    value = dialog.get();
                } catch (RuntimeException | LinkageError e) {
                    handOff.execute(() -> answer.completeExceptionally(e));
                    return;
                }
                handOff.execute(() -> answer.complete(value));
            });
        } catch (RuntimeException | LinkageError e) {
            // No lane on this platform, or one that has stopped taking work.
            answer.completeExceptionally(e);
        }
        return answer;
    }

    private static Optional<Path> result(String picked) {
        return picked == null ? Optional.empty() : Optional.of(Path.of(picked));
    }

    private static String pathString(Path p) {
        return p == null ? null : p.toAbsolutePath().toString();
    }

    /** Package-private so the spec formatting can be tested without a native dialog to open. */
    static String[][] toNfdFilters(List<Filter> filters) {
        if (filters == null || filters.isEmpty()) return null;
        String[][] out = new String[filters.size()][2];
        for (int i = 0; i < filters.size(); i++) {
            Filter f = filters.get(i);
            out[i][0] = f.description();
            StringBuilder spec = new StringBuilder();
            for (int j = 0; j < f.extensions().size(); j++) {
                String ext = f.extensions().get(j);
                if (ext.startsWith(".")) ext = ext.substring(1);
                if (j > 0) spec.append(',');
                spec.append(ext);
            }
            out[i][1] = spec.toString();
        }
        return out;
    }

    private static MemorySegment handleOf(long parentWindow) {
        return parentWindow == 0L ? MemorySegment.NULL : MemorySegment.ofAddress(parentWindow);
    }

    private static long handleTypeOf(long parentWindow) {
        return parentWindow == 0L ? Nfd.NFD_WINDOW_HANDLE_TYPE_UNSET : Os.current().windowHandleType();
    }
}
