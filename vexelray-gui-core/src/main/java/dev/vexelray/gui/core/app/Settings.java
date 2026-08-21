package dev.vexelray.gui.core.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Per-user application settings, persisted as {@code $HOME/.{appName}/settings.properties} — window placement,
 * open files, user preferences: the small facts an application wants back on its next launch.
 *
 * <p><b>Format and location are deliberately boring.</b> Java properties: zero dependencies, line-diffable,
 * hand-editable, and forgiving of unknown keys — an older build reading a newer file skips what it doesn't know
 * instead of failing. A dot-directory under the user's home is the convention every toolchain already follows,
 * and it gives the app a natural place for more than one file later (caches, logs) without inventing anything.
 *
 * <p><b>Explicit save, atomic write.</b> Mutations touch memory; {@link #save()} writes everything, via a
 * temporary file moved into place, so a crash mid-write leaves the previous settings intact rather than a
 * truncated file. Call it at the natural checkpoints — shutdown, or after a change worth surviving a crash.
 * A missing or unreadable file is an empty store, never an exception at startup: settings are a convenience,
 * and an app must not refuse to launch over a corrupt preferences file.
 *
 * <p>Thread-safe: all access is serialized on the instance. Values are typed at the accessor, not the store —
 * a malformed value falls back to the caller's default, same policy as a missing one.
 */
public final class Settings {

    /** Separates list elements inside one property value (paths may contain anything printable; this cannot). */
    private static final String LIST_SEPARATOR = "\u001F";

    private final Path file;
    private final Properties props = new Properties();

    private Settings(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                // A corrupt or unreadable settings file is an empty store, not a launch failure.
            }
        }
    }

    /**
     * Open (or start empty) the settings for {@code appName}: {@code $HOME/.{appName}/settings.properties}.
     * The directory is created on the first {@link #save()}, not here — merely reading settings leaves no mark.
     */
    public static Settings open(String appName) {
        if (appName == null || appName.isBlank()) {
            throw new IllegalArgumentException("appName must not be blank");
        }
        return new Settings(Path.of(System.getProperty("user.home"), "." + appName, "settings.properties"));
    }

    /** Open (or start empty) a settings file at an explicit path — for tests, or a non-standard location. */
    public static Settings at(Path file) {
        return new Settings(file);
    }

    /** Where this store persists. */
    public Path path() {
        return file;
    }

    /**
     * Write every setting to disk atomically: to a sibling temporary file first, then moved into place, so a
     * crash mid-write can lose this save but never the file. Creates the parent directory on first use.
     */
    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                props.store(out, null);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);   // best effort where FS can't atomic
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not save settings to " + file, e);
        }
    }

    // --- typed accessors: the type lives at the call site, and a malformed value is a missing one ---

    public synchronized String getString(String key, String def) {
        String v = props.getProperty(key);
        return v != null ? v : def;
    }

    public synchronized Settings putString(String key, String value) {
        if (value == null) {
            props.remove(key);
        } else {
            props.setProperty(key, value);
        }
        return this;
    }

    public synchronized int getInt(String key, int def) {
        try {
            String v = props.getProperty(key);
            return v != null ? Integer.parseInt(v.trim()) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public synchronized Settings putInt(String key, int value) {
        props.setProperty(key, Integer.toString(value));
        return this;
    }

    public synchronized long getLong(String key, long def) {
        try {
            String v = props.getProperty(key);
            return v != null ? Long.parseLong(v.trim()) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public synchronized Settings putLong(String key, long value) {
        props.setProperty(key, Long.toString(value));
        return this;
    }

    public synchronized float getFloat(String key, float def) {
        try {
            String v = props.getProperty(key);
            return v != null ? Float.parseFloat(v.trim()) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public synchronized Settings putFloat(String key, float value) {
        props.setProperty(key, Float.toString(value));
        return this;
    }

    public synchronized boolean getBoolean(String key, boolean def) {
        String v = props.getProperty(key);
        return v != null ? Boolean.parseBoolean(v.trim()) : def;
    }

    public synchronized Settings putBoolean(String key, boolean value) {
        props.setProperty(key, Boolean.toString(value));
        return this;
    }

    /** An ordered list of strings (e.g. the open files) — stored as one property, order preserved. */
    public synchronized List<String> getList(String key) {
        String v = props.getProperty(key);
        if (v == null || v.isEmpty()) {
            return List.of();
        }
        return List.of(v.split(LIST_SEPARATOR, -1));
    }

    /** Store an ordered list under {@code key}. Null or empty removes it. Elements must not contain U+001F. */
    public synchronized Settings putList(String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            props.remove(key);
            return this;
        }
        List<String> checked = new ArrayList<>(values.size());
        for (String v : values) {
            if (v.contains(LIST_SEPARATOR)) {
                throw new IllegalArgumentException("list elements must not contain U+001F: " + v);
            }
            checked.add(v);
        }
        props.setProperty(key, String.join(LIST_SEPARATOR, checked));
        return this;
    }

    /** Remove {@code key} entirely, so the caller's default applies again. */
    public synchronized Settings remove(String key) {
        props.remove(key);
        return this;
    }

    /** Whether {@code key} is present. */
    public synchronized boolean has(String key) {
        return props.containsKey(key);
    }
}
