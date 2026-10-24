package dev.vexelray.gui.core;

import dev.vexelray.vulkan.present.FrameTap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * What records a window to a video file, when a module on the class path offers one: the camera's <i>Record video to
 * file…</i> (docs/reference/automation.md §7). {@code vexelray-codec-record} is the one that does, in AV1.
 *
 * <p><b>A seam, so the GUI never links an encoder.</b> {@code vexelray-codec} depends on this stack and never the
 * other way, so a binary that records nothing carries no encoder. The GUI finds a recorder with {@link #find}, through
 * {@link ServiceLoader}, and offers recording only where there is one; an application that wants it puts the codec
 * module on its class path and does nothing else.
 *
 * <p>A recorder takes frames as a {@link FrameTap} takes them, from a window's presenter ({@link WindowControls#tap}),
 * so it never causes a frame and never makes one wait.
 */
public interface WindowRecorder {

    /** What the file type is called in a save dialog: {@code "Matroska video"}. */
    String fileType();

    /** The file name's extension, without the dot: {@code "mkv"}. */
    String extension();

    /**
     * Start recording into {@code file}, replacing one already there, on a canvas of {@code width x height}: the
     * largest a frame will be (the window's screen), every frame in the file that size, a smaller frame at its top
     * left. Frames are timed from {@code startNanos}, on {@link System#nanoTime}'s clock. Blocking: it opens an
     * encoder and creates the file. Throws, with a reason fit to show, when it cannot.
     */
    Session start(Path file, int width, int height, long startNanos) throws IOException;

    /** One recording: the tap a window's frames go to, and its end. */
    interface Session extends FrameTap {

        /**
         * Stop, the last frame shown lasting until {@code endNanos}, and finish the file. Blocking, briefly: the frames
         * still queued are encoded. Throws, with a reason fit to show, if the file could not be finished whole.
         */
        void finish(long endNanos) throws IOException;

        /** The file being written. */
        Path file();

        /** Why the recording stopped early, or null while it is fine: a full disk, a lost encoder. */
        String failure();
    }

    /** The recorder on the class path, or empty when no module offers one. The first, if several do. */
    static Optional<WindowRecorder> find() {
        try {
            return ServiceLoader.load(WindowRecorder.class).findFirst();
        } catch (java.util.ServiceConfigurationError e) {
            return Optional.empty();
        }
    }
}
