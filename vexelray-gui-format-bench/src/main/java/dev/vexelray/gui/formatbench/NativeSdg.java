package dev.vexelray.gui.formatbench;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The C rasterizer of sdg-signed-distance-graphics ({@code c/}), called through the foreign-function API: the same
 * pixels as {@code SdgRender}, byte for byte (its conformance set), compiled native. Found as the shared
 * library in {@code -Dsdg.native.dir} (by default that repository's {@code c/build}); without it, the comparison
 * leaves it out and says so.
 */
final class NativeSdg {

    static final String DIR_PROPERTY = "sdg.native.dir";

    private static final MethodHandle OPEN, CLOSE, RENDER;
    private static final String WHY;

    static {
        MethodHandle open = null, close = null, render = null;
        String why = null;
        try {
            Path lib = Path.of(System.getProperty(DIR_PROPERTY, "sdg-native-not-set"))
                    .resolve(System.mapLibraryName("sdg"));
            if (!Files.isRegularFile(lib)) {
                why = "no " + lib + " (build sdg-signed-distance-graphics/c)";
            } else {
                SymbolLookup sdg = SymbolLookup.libraryLookup(lib, Arena.global());
                Linker linker = Linker.nativeLinker();
                open = linker.downcallHandle(sdg.find("sdg_open").orElseThrow(),
                        FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG));
                close = linker.downcallHandle(sdg.find("sdg_close").orElseThrow(),
                        FunctionDescriptor.ofVoid(ADDRESS));
                render = linker.downcallHandle(sdg.find("sdg_render_threaded").orElseThrow(),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE,
                                JAVA_DOUBLE, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
            }
        } catch (RuntimeException e) {
            why = e.toString();
        }
        OPEN = open;
        CLOSE = close;
        RENDER = render;
        WHY = why;
    }

    private static final Map<String, MemorySegment> OPENED = new HashMap<>();

    private NativeSdg() {
    }

    static boolean available() {
        return WHY == null;
    }

    /** Why it is not available; null when it is. */
    static String why() {
        return WHY;
    }

    /** {@code art}'s drawing, opened by the C library once and kept. */
    static synchronized MemorySegment drawing(Corpus.Art art) {
        return OPENED.computeIfAbsent(art.name(), n -> {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment bytes = a.allocate(art.sdgb().length);
                bytes.copyFrom(MemorySegment.ofArray(art.sdgb()));
                MemorySegment err = a.allocate(256);
                MemorySegment d = (MemorySegment) OPEN.invokeExact(bytes, (long) art.sdgb().length, err, 256L);
                if (d.address() == 0) {
                    throw new IllegalStateException("the C library refused " + n + ": "
                            + err.getString(0, StandardCharsets.UTF_8));
                }
                return d;
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        });
    }

    /**
     * {@code art} drawn by the C library, fitted into {@code side} square, as SdgRender fits it, on {@code threads}
     * threads (0: one a processor): straight RGBA.
     */
    static byte[] render(Corpus.Art art, int side, int threads) {
        MemorySegment d = drawing(art);
        double w = art.sdg().width(), h = art.sdg().height();
        double upp = Math.max(w / side, h / side);
        double ox = (w - side * upp) / 2, oy = (h - side * upp) / 2;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate((long) side * side * 4);
            int rc = (int) RENDER.invokeExact(d, side, side, ox, oy, upp, MemorySegment.NULL, MemorySegment.NULL,
                    threads, out);
            if (rc != 0) {
                throw new IllegalStateException("the C library ran out of memory");
            }
            return out.toArray(JAVA_BYTE);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
