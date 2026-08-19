package dev.vexelray.gui.nfd;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Raw Panama bindings for Native File Dialog Extended (NFDe).
 *
 * Use {@link FileDialog} for the typed Java API; this class is the FFM layer.
 *
 * Threading: dialogs are modal and must be invoked from the thread that owns
 * the window / pumps OS events (the main thread on macOS). Calls block until
 * the dialog is dismissed.
 *
 * String encoding: NFDe's {@code nfdnchar_t} is {@code wchar_t} (UTF-16LE)
 * on Windows and {@code char} (UTF-8) on POSIX. The helpers in this class
 * select the right encoding per platform.
 */
@SuppressWarnings("restricted")
public final class Nfd {

    public static final int NFD_ERROR  = 0;
    public static final int NFD_OKAY   = 1;
    public static final int NFD_CANCEL = 2;

    public static final long NFD_WINDOW_HANDLE_TYPE_UNSET   = 0;
    public static final long NFD_WINDOW_HANDLE_TYPE_WINDOWS = 1;
    public static final long NFD_WINDOW_HANDLE_TYPE_COCOA   = 2;

    /** Matches NFD_INTERFACE_VERSION in nfd.h; required first arg to every {@code _With_Impl}. */
    private static final long NFD_INTERFACE_VERSION = 1L;

    private static final boolean WINDOWS;
    public  static final Charset NATIVE_CHARSET;
    private static final int CHAR_SIZE;

    static {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        WINDOWS = os.contains("win");
        NATIVE_CHARSET = WINDOWS ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8;
        CHAR_SIZE = WINDOWS ? 2 : 1;
    }

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIB;

    static {
        Path resolved = NativeLibLoader.load("nfd");
        LIB = (resolved != null)
            ? SymbolLookup.libraryLookup(resolved, Arena.global())
            : SymbolLookup.libraryLookup("nfd", Arena.global());
    }

    private static MethodHandle dc(String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
            LIB.find(name).orElseThrow(() -> new UnsatisfiedLinkError("nfd: " + name)),
            desc
        );
    }

    private static final MethodHandle NFD_INIT             = dc("NFD_Init",         FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle NFD_QUIT             = dc("NFD_Quit",         FunctionDescriptor.ofVoid());
    private static final MethodHandle NFD_FREE_PATH_N      = dc("NFD_FreePathN",    FunctionDescriptor.ofVoid(ADDRESS));
    // Real exported symbols are the *_With_Impl variants — the *_With names in nfd.h
    // are header-only inline shims that prepend NFD_INTERFACE_VERSION as a size_t arg.
    private static final MethodHandle NFD_OPEN_DIALOG_N    = dc("NFD_OpenDialogN_With_Impl",  FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle NFD_SAVE_DIALOG_N    = dc("NFD_SaveDialogN_With_Impl",  FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle NFD_PICK_FOLDER_N    = dc("NFD_PickFolderN_With_Impl",  FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle NFD_GET_ERROR        = dc("NFD_GetError",     FunctionDescriptor.of(ADDRESS));

    private static boolean initialized = false;

    private Nfd() {}

    /** Idempotent. NFDe requires NFD_Init before any dialog call. */
    public static synchronized void ensureInit() {
        if (initialized) return;
        try {
            int r = (int) NFD_INIT.invokeExact();
            if (r != NFD_OKAY) {
                throw new IllegalStateException("NFD_Init failed: " + lastError());
            }
            initialized = true;
        } catch (Throwable t) { throw rethrow(t); }
    }

    public static String lastError() {
        try {
            MemorySegment ptr = (MemorySegment) NFD_GET_ERROR.invokeExact();
            return readUtf8(ptr);
        } catch (Throwable t) { throw rethrow(t); }
    }

    /**
     * @param parentHandle  raw native window handle (HWND on Windows, NSWindow* on macOS), or null
     * @param parentType    one of NFD_WINDOW_HANDLE_TYPE_*  (UNSET if parentHandle is null)
     * @param filters       caller-formatted {name,spec} pairs ({@code spec} is comma-separated extensions, no dots)
     * @param defaultPath   starting directory, or null
     * @return picked path on OKAY, null on CANCEL
     * @throws RuntimeException on ERROR
     */
    // Struct layouts (Windows x64; nfdnchar_t = wchar_t*; sizes match nfd.h):
    //
    //   nfdopendialognargs_t (40 bytes):
    //     @0  filterList   void*       8
    //     @8  filterCount  uint32      4 (+4 pad to 8-byte align next pointer)
    //     @16 defaultPath  void*       8
    //     @24 parentWindow {size_t, void*}  16
    //
    //   nfdsavedialognargs_t (48 bytes):
    //     @0  filterList   void*       8
    //     @8  filterCount  uint32      4 (+4 pad)
    //     @16 defaultPath  void*       8
    //     @24 defaultName  void*       8
    //     @32 parentWindow {size_t, void*}  16
    //
    //   nfdpickfoldernargs_t (24 bytes):
    //     @0  defaultPath  void*       8
    //     @8  parentWindow {size_t, void*}  16

    public static String openDialog(MemorySegment parentHandle, long parentType,
                                    String[][] filters, String defaultPath) {
        ensureInit();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outPtr = arena.allocate(ADDRESS);
            MemorySegment args = arena.allocate(40);
            args.set(ADDRESS, 0,  allocateFilterList(arena, filters));
            args.set(JAVA_INT, 8, filters == null ? 0 : filters.length);
            args.set(ADDRESS, 16, allocateNString(arena, defaultPath));
            writeWindowHandle(args, 24, parentType, parentHandle);

            int result = (int) NFD_OPEN_DIALOG_N.invokeExact(NFD_INTERFACE_VERSION, outPtr, args);
            return interpretResult(result, outPtr);
        } catch (Throwable t) { throw rethrow(t); }
    }

    public static String saveDialog(MemorySegment parentHandle, long parentType,
                                    String[][] filters, String defaultPath, String defaultName) {
        ensureInit();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outPtr = arena.allocate(ADDRESS);
            MemorySegment args = arena.allocate(48);
            args.set(ADDRESS, 0,  allocateFilterList(arena, filters));
            args.set(JAVA_INT, 8, filters == null ? 0 : filters.length);
            args.set(ADDRESS, 16, allocateNString(arena, defaultPath));
            args.set(ADDRESS, 24, allocateNString(arena, defaultName));
            writeWindowHandle(args, 32, parentType, parentHandle);

            int result = (int) NFD_SAVE_DIALOG_N.invokeExact(NFD_INTERFACE_VERSION, outPtr, args);
            return interpretResult(result, outPtr);
        } catch (Throwable t) { throw rethrow(t); }
    }

    public static String pickFolder(MemorySegment parentHandle, long parentType, String defaultPath) {
        ensureInit();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outPtr = arena.allocate(ADDRESS);
            MemorySegment args = arena.allocate(24);
            args.set(ADDRESS, 0, allocateNString(arena, defaultPath));
            writeWindowHandle(args, 8, parentType, parentHandle);

            int result = (int) NFD_PICK_FOLDER_N.invokeExact(NFD_INTERFACE_VERSION, outPtr, args);
            return interpretResult(result, outPtr);
        } catch (Throwable t) { throw rethrow(t); }
    }

    private static String interpretResult(int result, MemorySegment outPtr) throws Throwable {
        if (result == NFD_CANCEL) return null;
        if (result == NFD_OKAY) {
            MemorySegment pathPtr = outPtr.get(ADDRESS, 0);
            try {
                return readNString(pathPtr);
            } finally {
                NFD_FREE_PATH_N.invokeExact(pathPtr);
            }
        }
        throw new RuntimeException("NFD error: " + lastError());
    }

    private static void writeWindowHandle(MemorySegment seg, long offset, long type, MemorySegment handle) {
        seg.set(JAVA_LONG, offset, type);
        seg.set(ADDRESS, offset + 8, handle == null ? MemorySegment.NULL : handle);
    }

    private static MemorySegment allocateFilterList(Arena arena, String[][] filters) {
        if (filters == null || filters.length == 0) return MemorySegment.NULL;
        // Each filter item is { const nfdnchar_t* name; const nfdnchar_t* spec; } = 16 bytes
        MemorySegment list = arena.allocate(16L * filters.length);
        for (int i = 0; i < filters.length; i++) {
            String[] pair = filters[i];
            list.set(ADDRESS, i * 16L,     allocateNString(arena, pair[0]));
            list.set(ADDRESS, i * 16L + 8, allocateNString(arena, pair[1]));
        }
        return list;
    }

    private static MemorySegment allocateNString(Arena arena, String s) {
        if (s == null) return MemorySegment.NULL;
        byte[] encoded = s.getBytes(NATIVE_CHARSET);
        MemorySegment seg = arena.allocate(encoded.length + CHAR_SIZE);
        MemorySegment.copy(encoded, 0, seg, JAVA_BYTE, 0, encoded.length);
        // trailing null terminator is implied — arena.allocate zero-fills.
        return seg;
    }

    private static String readNString(MemorySegment ptr) {
        if (ptr == null || ptr.address() == 0L) return null;
        MemorySegment reint = ptr.reinterpret(Long.MAX_VALUE);
        long byteLen = 0;
        if (WINDOWS) {
            while (reint.get(JAVA_SHORT, byteLen) != 0) byteLen += 2;
        } else {
            while (reint.get(JAVA_BYTE, byteLen) != 0) byteLen += 1;
        }
        byte[] bytes = new byte[(int) byteLen];
        MemorySegment.copy(reint, JAVA_BYTE, 0, bytes, 0, (int) byteLen);
        return new String(bytes, NATIVE_CHARSET);
    }

    private static String readUtf8(MemorySegment ptr) {
        if (ptr == null || ptr.address() == 0L) return null;
        MemorySegment reint = ptr.reinterpret(Long.MAX_VALUE);
        long len = 0;
        while (reint.get(JAVA_BYTE, len) != 0) len++;
        byte[] bytes = new byte[(int) len];
        MemorySegment.copy(reint, JAVA_BYTE, 0, bytes, 0, (int) len);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException re) return re;
        if (t instanceof Error err) throw err;
        return new RuntimeException(t);
    }
}
