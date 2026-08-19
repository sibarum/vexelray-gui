package dev.vexelray.gui.nfd;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Native OS file dialogs (open, save, pick folder) via NFDe.
 *
 * Dialogs are synchronous and modal — the call blocks until the user
 * confirms or cancels. Must be invoked from the GUI thread (the thread
 * that owns the window and drives the frame loop); an app typically
 * services dialog requests from its {@code beforeFrame} hook.
 *
 * The parent handle is the raw OS window handle as returned by
 * {@code GuiApp.windowHandle()} — an HWND on Windows, an NSWindow* on
 * macOS. Pass 0 for no parent.
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

    private static Optional<Path> result(String picked) {
        return picked == null ? Optional.empty() : Optional.of(Path.of(picked));
    }

    private static String pathString(Path p) {
        return p == null ? null : p.toAbsolutePath().toString();
    }

    private static String[][] toNfdFilters(List<Filter> filters) {
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
        if (parentWindow == 0L) return Nfd.NFD_WINDOW_HANDLE_TYPE_UNSET;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win"))                          return Nfd.NFD_WINDOW_HANDLE_TYPE_WINDOWS;
        if (os.contains("mac") || os.contains("darwin")) return Nfd.NFD_WINDOW_HANDLE_TYPE_COCOA;
        return Nfd.NFD_WINDOW_HANDLE_TYPE_UNSET;
    }
}
