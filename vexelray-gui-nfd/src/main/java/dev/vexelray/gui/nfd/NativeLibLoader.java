package dev.vexelray.gui.nfd;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Locates a native library either from a bundled classpath resource
 * ({@code /natives/<os>-<arch>/<file>}) extracted to a temp directory, or
 * via the system loader on PATH / {@code java.library.path}. Loading is
 * idempotent per library name.
 *
 * Resolution order (first match wins):
 * <ol>
 *   <li>System property {@code vexelray.natives.<libName>} or env var
 *       {@code VEXELRAY_NATIVES_<LIBNAME>} — absolute path to a specific file.</li>
 *   <li>System property {@code vexelray.natives.dir} or env var
 *       {@code VEXELRAY_NATIVES_DIR} — directory containing the platform-correct
 *       filename (e.g. {@code nfd.dll} on Windows, {@code libnfd.dylib} on macOS).</li>
 *   <li>Classpath resource at {@code /natives/<os>-<arch>/<file>}, extracted
 *       to a temp directory and loaded.</li>
 *   <li>{@code System.loadLibrary(libName)} fallback.</li>
 * </ol>
 *
 * The returned Path is the resolved file path when the library came from a
 * known location, or {@code null} when {@code System.loadLibrary} was used
 * (in which case callers must look up symbols by library name, not path).
 */
@SuppressWarnings("restricted")
final class NativeLibLoader {

    private static final Map<String, Path> LOADED = new HashMap<>();

    private NativeLibLoader() {}

    static synchronized Path load(String libName) {
        if (LOADED.containsKey(libName)) return LOADED.get(libName);

        String explicit = lookup("vexelray.natives." + libName, "VEXELRAY_NATIVES_" + envKey(libName));
        if (explicit != null) {
            Path p = Path.of(explicit).toAbsolutePath();
            System.load(p.toString());
            LOADED.put(libName, p);
            return p;
        }

        String fileName = libFileName(libName);

        String dirOverride = lookup("vexelray.natives.dir", "VEXELRAY_NATIVES_DIR");
        if (dirOverride != null) {
            Path p = Path.of(dirOverride).resolve(fileName).toAbsolutePath();
            if (Files.exists(p)) {
                System.load(p.toString());
                LOADED.put(libName, p);
                return p;
            }
        }

        Path resolved = null;
        String resource = "/natives/" + osArchDir() + "/" + fileName;
        try (InputStream in = NativeLibLoader.class.getResourceAsStream(resource)) {
            if (in != null) {
                Path tmpDir = Files.createTempDirectory("vexelray-natives-");
                tmpDir.toFile().deleteOnExit();
                Path libPath = tmpDir.resolve(fileName);
                Files.copy(in, libPath, StandardCopyOption.REPLACE_EXISTING);
                libPath.toFile().deleteOnExit();
                System.load(libPath.toAbsolutePath().toString());
                resolved = libPath.toAbsolutePath();
            } else {
                System.loadLibrary(libName);
            }
        } catch (IOException e) {
            throw new UnsatisfiedLinkError("Failed to extract " + libName + ": " + e.getMessage());
        }

        LOADED.put(libName, resolved);
        return resolved;
    }

    private static String lookup(String propKey, String envKey) {
        String v = System.getProperty(propKey);
        if (v != null && !v.isEmpty()) return v;
        v = System.getenv(envKey);
        if (v != null && !v.isEmpty()) return v;
        return null;
    }

    private static String envKey(String libName) {
        return libName.toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_');
    }

    private static String libFileName(String libName) {
        return Os.current().libraryFileName(libName);
    }

    private static String osArchDir() {
        return Os.current().resourceDirectory() + "-" + currentArch();
    }

    private static String currentArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "aarch64";
        }
        if (arch.contains("64")) {
            return "x64";
        }
        throw new UnsatisfiedLinkError("Unsupported arch: " + arch);
    }
}
