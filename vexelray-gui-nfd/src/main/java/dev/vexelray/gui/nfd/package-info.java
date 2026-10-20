/**
 * The GUI's one native binding: a Panama (FFM) facade over nativefiledialog-extended (open / save / pick-folder).
 *
 * <p><b>Threads.</b> Ask through {@link dev.vexelray.gui.nfd.FileDialog}'s {@code *Async} methods, from any
 * thread, passing the way onto the window's thread ({@code app::post}). Where the dialog runs is decided in one
 * place, per OS: on Windows and Linux a long-lived daemon dialog thread of this module's, which owns {@code NFD_Init}, runs
 * the dialogs one at a time and calls {@code NFD_Quit} itself at JVM shutdown — so the window keeps drawing behind
 * a dialog and the application has nothing to quit; on macOS the window's own thread, because AppKit requires it,
 * and the frame loop waits for the dialog there. The answer is a {@code CompletableFuture} completed on a pool
 * thread, never the GUI thread.
 *
 * <p><b>Platforms.</b> This module holds no platform knowledge: the NFDe library and the few facts that differ per
 * OS come from the one {@code vexelray-gui-nfd-<os>} module this module's OS-activated build profile adds
 * ({@link dev.vexelray.gui.nfd.spi.NfdPlatform}). Linux uses NFDe's xdg-desktop-portal backend.
 *
 * <p>The synchronous {@code FileDialog} methods remain, and run on (and block, and initialise NFDe on) the
 * calling thread; such a thread pairs them with {@link dev.vexelray.gui.nfd.Nfd#quit()} on that same thread,
 * which is a no-op on any thread that never opened a dialog.
 */
package dev.vexelray.gui.nfd;
