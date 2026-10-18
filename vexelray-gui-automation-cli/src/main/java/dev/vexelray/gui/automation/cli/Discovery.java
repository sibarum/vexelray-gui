package dev.vexelray.gui.automation.cli;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The running applications, read from the files they write (vexelray-gui {@code docs/plans/discovery.md}): one
 * {@code <app>-<pid>.auto} per process, beside a {@code .lock} the process holds for as long as it runs.
 *
 * <p><b>A copy of the server's rule, on purpose.</b> This module has an empty dependency block
 * ({@code docs/reference/automation-cli.md} §3, R3), so the folder and the liveness check are restated here rather
 * than imported from {@code dev.vexelray.gui.automation.Discovery}. The contract they share is discovery.md, as
 * the default port is shared with {@code AutomationServer} (see {@link AutomationClient#DEFAULT_PORT}).
 *
 * <p><b>Live means the lock is held.</b> A file whose lock this process can take belongs to a process that has
 * ended, however it ended; it is deleted, with its lock, and not reported. Process ids are not consulted, because
 * Windows reuses them quickly enough that "is pid 18244 alive" can be answered yes by somebody else.
 */
final class Discovery {

    /** The environment variable that moves the folder, for every process that honours it. */
    static final String HOME_ENV = "VEXELRAY_HOME";

    /** The protocol major this client speaks; a server reporting another is refused, never guessed at. */
    static final String PROTOCOL_MAJOR = "1";

    private static final String INFO = ".auto";
    private static final String LOCK = ".lock";

    /** One running application, as its file describes it. */
    record Entry(Path file, Map<String, String> facts) {

        Entry {
            facts = Map.copyOf(facts);
        }

        String get(String key) {
            return facts.getOrDefault(key, "-");
        }

        String app() {
            return get("app");
        }

        long pid() {
            try {
                return Long.parseLong(get("pid"));
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        int port() {
            try {
                return Integer.parseInt(get("port"));
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        /**
         * The port to attach to for diagnostics ({@code diagnostics=}) or control ({@code port=}); {@code -1} when
         * diagnostics is {@code off}. A file from before the two were split has no {@code diagnostics=} line, and its
         * one socket serves both.
         */
        int port(boolean diagnostics) {
            if (!diagnostics) {
                return port();
            }
            String d = facts.get("diagnostics");
            if (d == null) {
                return port();
            }
            try {
                return Integer.parseInt(d);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        /** The protocol's major, the part before the dot. */
        String major() {
            String p = get("protocol");
            int dot = p.indexOf('.');
            return dot < 0 ? p : p.substring(0, dot);
        }

        /** One line saying which application this is, for a message. */
        String describe() {
            return app() + " (pid " + pid() + ", port " + port() + ")";
        }
    }

    private Discovery() {
    }

    /**
     * The folder: {@code $VEXELRAY_HOME/automation}; else {@code -Dvexelray.home}, the same property that moves the
     * server's; else {@code ~/.vexelray/automation}.
     */
    static Path folder() {
        String env = System.getenv(HOME_ENV);
        if (env != null && !env.isBlank()) {
            return Path.of(env.trim()).toAbsolutePath().normalize().resolve("automation");
        }
        String property = System.getProperty("vexelray.home");
        if (property != null && !property.isBlank()) {
            return Path.of(property.trim()).toAbsolutePath().normalize().resolve("automation");
        }
        return Path.of(System.getProperty("user.home"), ".vexelray", "automation");
    }

    /** The live applications in {@link #folder()}, oldest first; stale files are cleared on the way. */
    static List<Entry> live() {
        return live(folder());
    }

    static List<Entry> live(Path folder) {
        List<Entry> found = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return found;
        }
        List<Path> infos = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*" + INFO)) {
            files.forEach(infos::add);
        } catch (IOException e) {
            return found;
        }
        for (Path info : infos) {
            String name = info.getFileName().toString();
            Path lock = info.resolveSibling(name.substring(0, name.length() - INFO.length()) + LOCK);
            if (!held(lock)) {
                delete(info);
                delete(lock);
                // And the Inspector link's socket, if it had one: a crash leaves that behind too.
                delete(info.resolveSibling(name.substring(0, name.length() - INFO.length()) + ".sock"));
                continue;
            }
            Map<String, String> facts = read(info);
            if (facts != null) {
                found.add(new Entry(info, facts));
            }
        }
        found.sort(Comparator.comparing((Entry e) -> e.get("started")).thenComparingLong(Entry::pid));
        return found;
    }

    /**
     * Whether some process holds {@code lock}. A missing lock file is not held: the writer creates it before the
     * info file and removes it after, so an info file without one was left by a process that is gone.
     */
    private static boolean held(Path lock) {
        if (!Files.exists(lock)) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            FileLock taken = channel.tryLock();
            if (taken == null) {
                return true;
            }
            taken.release();
            return false;
        } catch (OverlappingFileLockException e) {
            return true;     // this very JVM holds it: an application reading its own file
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            return true;     // cannot tell, so do not delete what may be somebody's
        }
    }

    /** {@code key=value} lines, split on the first {@code =}; {@code null} when the file vanished meanwhile. */
    static Map<String, String> read(Path info) {
        try {
            Map<String, String> facts = new LinkedHashMap<>();
            for (String line : Files.readAllLines(info, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    facts.put(line.substring(0, eq).trim(), line.substring(eq + 1));
                }
            }
            return facts;
        } catch (IOException e) {
            return null;
        }
    }

    private static void delete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            // Somebody else's to clear, then; it is reported as stale either way by not being listed.
        }
    }

    /** {@code list}'s reply: a first line of facts, then one row per live application. */
    static String table(List<Entry> live, Path folder) {
        StringBuilder sb = new StringBuilder("ok apps=").append(live.size()).append(" folder=").append(folder);
        if (live.isEmpty()) {
            return sb.toString();
        }
        String[] header = {"app", "pid", "port", "diagnostics", "protocol", "started", "cwd"};
        List<String[]> rows = new ArrayList<>();
        rows.add(header);
        for (Entry e : live) {
            int d = e.port(true);
            rows.add(new String[]{e.app(), Long.toString(e.pid()), Integer.toString(e.port()),
                    d < 0 ? "off" : Integer.toString(d), e.get("protocol"), e.get("started"), e.get("cwd")});
        }
        int[] width = new int[header.length];
        for (String[] r : rows) {
            for (int i = 0; i < r.length; i++) {
                width[i] = Math.max(width[i], r[i].length());
            }
        }
        for (String[] r : rows) {
            sb.append('\n');
            for (int i = 0; i < r.length; i++) {
                sb.append(i == r.length - 1 ? r[i] : String.format("%-" + (width[i] + 2) + "s", r[i]));
            }
        }
        return sb.toString();
    }
}
