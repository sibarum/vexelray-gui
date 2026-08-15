package dev.vexelray.gui.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

/**
 * Locating and reading the compiled output of a reactor module, for the architecture guards.
 *
 * <p>The guards deliberately read <b>bytecode</b>, not source: a source scan is defeated by an alias, a
 * fully-qualified name or a wildcard import, whereas the constant pool records what the compiler actually
 * emitted.
 *
 * <p>Modules are resolved by <b>path from the reactor root</b>, not from the test classpath. A classpath lookup
 * would happily resolve an inspected module to an installed jar from the last {@code mvn install} — so the guard
 * could pass against stale bytecode while the working tree violates the rule. Reading {@code target/classes}
 * directly means the guard always inspects what was just compiled, or says plainly that it cannot.
 */
final class Bytecode {

    static final String GUI_CORE = "vexelray-gui-core";
    static final String GUI_WIDGET = "vexelray-gui-widget";

    private Bytecode() {
    }

    /**
     * The {@code target/classes} directory of a sibling reactor module, resolved from this module's own compiled
     * test output — so it holds regardless of how Maven resolved the dependency.
     */
    static Path classesOf(String module) {
        Path classes = reactorRoot().resolve(module).resolve("target").resolve("classes");
        if (!Files.isDirectory(classes)) {
            throw new IllegalStateException("no compiled output at " + classes + " — the architecture guard reads "
                    + "freshly compiled sibling modules, so build them in the same reactor invocation "
                    + "(`mvn test`, or `mvn -pl vexelray-gui-architecture -am test`)");
        }
        return classes;
    }

    /** Every {@code .class} file under {@code root}, in a stable order so failures list identically each run. */
    static List<Path> classFiles(Path root) {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(p -> p.toString().endsWith(".class")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (files.isEmpty()) {
            // A guard that scans nothing passes vacuously, which is worse than no guard at all.
            throw new IllegalStateException("no classes found under " + root + " — the guard would pass vacuously");
        }
        return files;
    }

    static byte[] read(Path classFile) {
        try {
            return Files.readAllBytes(classFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Whether {@code needle} occurs anywhere in {@code haystack} (used to scan a constant pool for a name). */
    static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** The enclosing top-level class of an internal-form name — {@code a/b/Gui$1} becomes {@code a/b/Gui}. */
    static String outer(String internalName) {
        int nested = internalName.indexOf('$');
        return nested < 0 ? internalName : internalName.substring(0, nested);
    }

    /** The reactor root, from this module's own {@code target/test-classes} directory. */
    private static Path reactorRoot() {
        Path testClasses;
        try {
            testClasses = Paths.get(Bytecode.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("cannot locate the architecture module's own output", e);
        }
        // .../vexelray-gui-architecture/target/test-classes -> target -> vexelray-gui-architecture -> reactor root
        return testClasses.getParent().getParent().getParent();
    }
}
