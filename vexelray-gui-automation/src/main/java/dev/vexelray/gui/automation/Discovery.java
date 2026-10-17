package dev.vexelray.gui.automation;

import dev.vexelray.gui.core.app.AppHome;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The file a running application writes so it can be found (docs/plans/discovery.md): one per process, in one
 * folder per user, saying which port its automation socket listens on and the facts that never change while it
 * runs.
 *
 * <pre>
 * ~/.vexelray/automation/calculator-18244.auto     protocol=1.0, app=, title=, pid=, started=, port=, cwd=, ...
 * ~/.vexelray/automation/calculator-18244.lock     held for the life of the process
 * </pre>
 *
 * <p><b>Honest without a heartbeat.</b> The application holds an OS lock on the {@code .lock} file for as long as it
 * runs, and the operating system lets go of it when the process ends, however it ends. A reader that can take the
 * lock knows the writer is gone and may delete both files. The lock is on a companion file rather than the one
 * being read because a Windows lock is mandatory: a locked range cannot be read by anyone else.
 *
 * <p><b>Only facts that hold for the whole run.</b> What changes while the application runs — windows, the hand,
 * the recorder — is {@code hello}'s to report, so this file is written once and never caught half-rewritten. It is
 * written to a temporary name and moved into place, so a reader never sees a part of it either.
 *
 * <p><b>The folder</b> is {@code AppHome.of("vexelray").folder("automation")}: {@code ~/.vexelray/automation}, which
 * {@code -Dvexelray.home=<dir>} moves the way it moves any {@link AppHome}. A client is another process and cannot
 * see that property, so the environment variable {@code VEXELRAY_HOME} moves it too, for both sides, and wins.
 * {@code ottermate} keeps its own copy of this rule, because it has no dependencies; the shared contract is
 * discovery.md, and a test on each side reads what the other wrote.
 */
public final class Discovery implements AutoCloseable {

    private static final sibarum.probe.Log LOG = sibarum.probe.Log.of("automation.discovery");

    /** The environment variable that moves the folder, for every process that honours it. */
    public static final String HOME_ENV = "VEXELRAY_HOME";

    /** The info file's extension. */
    public static final String INFO = ".auto";

    /** The lock file's extension. */
    public static final String LOCK = ".lock";

    private final Path info;
    private final Path lockFile;
    private final FileChannel channel;
    private final FileLock lock;
    private final Thread hook;
    private final Map<String, String> facts;
    private volatile boolean closed;

    private Discovery(Path info, Path lockFile, FileChannel channel, FileLock lock, Map<String, String> facts) {
        this.info = info;
        this.lockFile = lockFile;
        this.channel = channel;
        this.lock = lock;
        this.facts = new LinkedHashMap<>(facts);
        // A process that exits without closing the server still tidies up after itself; one that crashes leaves
        // the files, and the lock says they are stale.
        this.hook = new Thread(this::remove, "vexelray-automation-discovery");
        Runtime.getRuntime().addShutdownHook(hook);
    }

    /** The folder every running application writes into, and every client reads. */
    public static Path folder() {
        String env = System.getenv(HOME_ENV);
        if (env != null && !env.isBlank()) {
            return Path.of(env.trim()).toAbsolutePath().normalize().resolve("automation");
        }
        return AppHome.of("vexelray").dir().resolve("automation");
    }

    /**
     * An application's name as it appears in a file name: lower case, letters, digits and {@code -}, so it is the
     * same on every file system and needs no quoting in a shell.
     */
    static String fileName(String app) {
        String s = (app == null ? "" : app).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return s.isEmpty() ? "app" : s;
    }

    /**
     * The facts this file holds, in the order written. Keys and values are one line each; a value keeps any
     * {@code =} it contains, because a reader splits on the first one only.
     */
    static Map<String, String> facts(String app, String title, int port) {
        ProcessHandle self = ProcessHandle.current();
        ProcessHandle.Info p = self.info();
        Map<String, String> f = new LinkedHashMap<>();
        f.put("protocol", AutomationServer.PROTOCOL);
        f.put("app", app);
        f.put("title", title == null || title.isBlank() ? app : title);
        f.put("pid", Long.toString(self.pid()));
        f.put("started", p.startInstant().map(Instant::toString).orElse("-"));
        f.put("port", Integer.toString(port));
        f.put("cwd", Path.of("").toAbsolutePath().toString());
        f.put("command", p.commandLine().orElse(p.command().orElse("-")));
        String version = AutomationServer.class.getPackage().getImplementationVersion();
        f.put("build", "vexelray-gui-automation " + (version == null ? "-" : version));
        return f;
    }

    /**
     * Write this process's file into {@link #folder()}, holding its lock, or return {@code null} when that cannot
     * be done — an application that cannot be found is still one that can be driven by its port, so a failure here
     * is reported and never fatal.
     */
    static Discovery publish(String app, String title, int port) {
        return publish(folder(), app, title, port);
    }

    static Discovery publish(Path folder, String app, String title, int port) {
        String base = fileName(app) + "-" + ProcessHandle.current().pid();
        Path info = folder.resolve(base + INFO);
        Path lockFile = folder.resolve(base + LOCK);
        FileChannel channel = null;
        try {
            Files.createDirectories(folder);
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new IOException("another process holds " + lockFile);
            }
            Map<String, String> facts = facts(app, title, port);
            write(info, facts);
            return new Discovery(info, lockFile, channel, lock, facts);
        } catch (IOException | OverlappingFileLockException | UnsupportedOperationException e) {
            LOG.warn("not discoverable: could not write " + info + " (" + e.getMessage()
                    + "); the socket still listens on port " + port);
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // As above: the point is the warning, not this.
                }
            }
            return null;
        }
    }

    /** Write {@code facts} to {@code info} by way of a temporary file moved into place: never seen half-written. */
    private static void write(Path info, Map<String, String> facts) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> e : facts.entrySet()) {
            text.append(e.getKey()).append('=').append(oneLine(e.getValue())).append('\n');
        }
        Path tmp = info.resolveSibling(info.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, info, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, info, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Add one more fact for the rest of the run — a further endpoint, such as the Inspector link's
     * {@code stream=uds:<path>} — and write the file again, moved into place so a reader sees the old file or the
     * new one and never a part of either. Every fact is still one that holds from here to the end of the run.
     */
    public synchronized void advertise(String key, String value) {
        if (closed) {
            return;
        }
        facts.put(key, value);
        try {
            write(info, facts);
        } catch (IOException e) {
            LOG.warn("could not add " + key + " to " + info + ": " + e.getMessage());
        }
    }

    /** The base name every file of this process shares: {@code <app>-<pid>}. */
    public String base() {
        String name = info.getFileName().toString();
        return name.substring(0, name.length() - INFO.length());
    }

    private static String oneLine(String v) {
        return v == null ? "-" : v.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }

    /** The info file this process wrote. */
    public Path file() {
        return info;
    }

    private synchronized void remove() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            Files.deleteIfExists(info);
        } catch (IOException e) {
            LOG.warn("could not remove " + info + ": " + e.getMessage());
        }
        try {
            lock.release();
            channel.close();
            Files.deleteIfExists(lockFile);
        } catch (IOException e) {
            // A lock file left behind with no lock on it is what a crash leaves too, and readers clear it.
        }
    }

    @Override
    public void close() {
        remove();
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // Already shutting down: the hook is running, or has run, and found it closed.
        }
    }
}
