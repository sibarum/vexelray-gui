package dev.vexelray.gui.core.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The per-user directory an application owns: {@code $HOME/.{name}/} — and everything it wants back on its next
 * launch. {@link Settings} is the first file in it; a recent-file list, a window layout, a cache or a log are the
 * next ones, and they belong beside it rather than each inventing a location of their own.
 *
 * <p><b>One layout on every platform.</b> A dot-directory under the user's home, named for the application, is a
 * convention a toolchain can state in one line and a user can find without documentation. VexelRay does not
 * branch on {@code AppData} / {@code Library} / {@code XDG_CONFIG_HOME}: an application whose files live in a
 * different place per platform has three support stories instead of one, and this directory is the user's to
 * inspect, back up, copy between machines, or delete. Where a deployment genuinely needs somewhere else — a
 * portable install, a test rig, a machine whose home is a network share — the location is configurable two ways:
 * {@link #at(Path)} names the directory outright, and the system property {@code {name}.home} redirects
 * {@link #of(String)} without the application changing a line.
 *
 * <p><b>Reading leaves no mark.</b> Neither {@code of} nor {@code at} touches the filesystem, and neither does
 * {@link #file}; the directory appears at the first write that needs it ({@link Settings#save()},
 * {@link #folder}, or an explicit {@link #create()}). An application launched and closed without changing
 * anything leaves the home directory as it found it — absent, if it was never there.
 *
 * <p>Paths resolve <i>inside</i> the home directory and nowhere else: a segment that is absolute, blank, or
 * climbs out with {@code ..} is rejected rather than quietly escaping, so a name computed from a document title
 * or a preference key cannot address the rest of the disk.
 */
public final class AppHome {

    /** Extension for the properties-backed stores {@link #settings(String)} opens. */
    private static final String STORE_SUFFIX = ".properties";

    /** Name of the default store, so {@code settings()} and {@code settings("settings")} are the same file. */
    private static final String DEFAULT_STORE = "settings";

    private final String name;
    private final Path dir;

    private AppHome(String name, Path dir) {
        this.name = name;
        this.dir = dir;
    }

    /**
     * The home directory for {@code appName}: {@code $HOME/.{appName}}, or the path in the {@code {appName}.home}
     * system property when one is set — that property names the directory itself, not a parent to nest under.
     * A leading dot on {@code appName} is optional ({@code of("editor")} and {@code of(".editor")} are the same
     * directory), and the name must be a single path segment: no separators, no {@code .} or {@code ..}.
     */
    public static AppHome of(String appName) {
        String name = normalizeName(appName);
        String override = System.getProperty(name + ".home");
        Path dir = override != null && !override.isBlank()
                ? Path.of(override.trim()).toAbsolutePath().normalize()
                : Path.of(System.getProperty("user.home"), "." + name);
        return new AppHome(name, dir);
    }

    /**
     * A home directory at an explicit path — a portable install, a test's temporary directory, or any deployment
     * that places its files itself. The last path element names it, so {@code at(tmp.resolve(".editor"))} reports
     * {@code "editor"} as its {@link #name()}.
     */
    public static AppHome at(Path dir) {
        Path abs = dir.toAbsolutePath().normalize();
        Path last = abs.getFileName();
        String name = last == null ? "" : last.toString();
        return new AppHome(name.startsWith(".") ? name.substring(1) : name, abs);
    }

    /** The application name this home is named for, without its leading dot. */
    public String name() {
        return name;
    }

    /** The directory itself. It may not exist yet — nothing here creates it until something writes. */
    public Path dir() {
        return dir;
    }

    /** Whether the directory exists on disk: false on a first launch, and after the user deletes it. */
    public boolean exists() {
        return Files.isDirectory(dir);
    }

    /** Create the directory, and any missing parents, if it is not already there; returns {@link #dir()}. */
    public Path create() {
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("could not create " + dir, e);
        }
    }

    /**
     * A path inside this home — {@code home.file("recent", "documents.txt")} is
     * {@code ~/.{name}/recent/documents.txt}. Nothing is created; the caller writes when it has something to
     * write. Segments must be relative, non-blank, and must not climb out of the home directory.
     */
    public Path file(String first, String... more) {
        Path resolved = resolve(first, more);
        if (!resolved.startsWith(dir)) {
            throw new IllegalArgumentException("path escapes " + dir + ": " + resolved);
        }
        return resolved;
    }

    /** Like {@link #file}, for a directory: the folder, and its parents, exist when this returns. */
    public Path folder(String first, String... more) {
        Path resolved = file(first, more);
        try {
            Files.createDirectories(resolved);
            return resolved;
        } catch (IOException e) {
            throw new UncheckedIOException("could not create " + resolved, e);
        }
    }

    /** This application's settings: {@code ~/.{name}/settings.properties}. */
    public Settings settings() {
        return settings(DEFAULT_STORE);
    }

    /**
     * A named store beside the default one: {@code ~/.{name}/{store}.properties}. Splitting by lifetime is what
     * keeps these files readable — {@code settings} for preferences the user chose, {@code session} for window
     * placement and open files, {@code recent} for a list that churns — and it means one store can be deleted,
     * or saved on a different cadence, without disturbing the others. A {@code .properties} suffix already on
     * {@code store} is not doubled.
     */
    public Settings settings(String store) {
        return Settings.at(file(store.endsWith(STORE_SUFFIX) ? store : store + STORE_SUFFIX));
    }

    private Path resolve(String first, String... more) {
        Path resolved = dir.resolve(segment(first));
        for (String s : more) {
            resolved = resolved.resolve(segment(s));
        }
        return resolved.normalize();
    }

    /** One caller-supplied path element: relative, non-blank, and never climbing out of the home directory. */
    private static String segment(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("path segment must not be blank");
        }
        Path p = Path.of(s);
        if (p.isAbsolute() || p.getRoot() != null) {
            throw new IllegalArgumentException("path segment must be relative: " + s);
        }
        for (Path part : p) {
            if (part.toString().equals("..")) {
                throw new IllegalArgumentException("path segment must not climb out of the home directory: " + s);
            }
        }
        return s;
    }

    /** The application name as a directory name: a leading dot is optional, everything else must be a segment. */
    private static String normalizeName(String appName) {
        if (appName == null || appName.isBlank()) {
            throw new IllegalArgumentException("appName must not be blank");
        }
        String name = appName.trim();
        if (name.startsWith(".")) {
            name = name.substring(1);
        }
        if (name.isBlank() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("appName must be a single path segment: " + appName);
        }
        return name;
    }

    @Override
    public String toString() {
        return "AppHome[" + dir + "]";
    }
}
