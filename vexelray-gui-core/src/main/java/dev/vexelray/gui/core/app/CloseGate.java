package dev.vexelray.gui.core.app;

import dev.vexelray.os.NativeWindow;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * One window's close policy: the small state machine between "the window reported a close" and "the host tears
 * it down". With no handler installed it is not in the way at all — a close closes, exactly as it did before.
 * With one, the close becomes a {@link CloseRequest} the application answers whenever it can, and the window
 * keeps running (drawing, taking input, able to show a dialog) until it does.
 *
 * <p>Three states, and the transitions are the whole design:
 * <ul>
 *   <li><b>open</b> — nothing pending. A reported close withdraws the OS-level request
 *       ({@link NativeWindow#cancelClose()}) and asks the application.</li>
 *   <li><b>asking</b> — a request is outstanding. Further reported closes change nothing: the user clicking the
 *       X twice must not queue two conversations, and must not close a window whose first question is still on
 *       screen.</li>
 *   <li><b>allowed</b> — the application said proceed. The next reported close is let through, and so is every
 *       one after it; an application that has agreed to close does not get asked again.</li>
 * </ul>
 *
 * <p>The one case that is not the application's to decide: a window that was <em>destroyed</em> rather than
 * asked to close (the owner went away, the session ended). {@link NativeWindow#cancelClose()} reports that by
 * returning false, and this gate lets it go without asking anyone — a veto over a window that no longer exists
 * would be a hang, not a feature. A platform that cannot tell the two apart says false to both, so close
 * interception degrades to no interception rather than to a stuck window.
 */
final class CloseGate {

    private final AtomicReference<Consumer<CloseRequest>> handler = new AtomicReference<>();
    private final AtomicReference<CloseRequest> pending = new AtomicReference<>();
    private volatile boolean allowed;

    /** Install (or with {@code null}, remove) the handler asked before this window closes. */
    void handler(Consumer<CloseRequest> handler) {
        this.handler.set(handler);
    }

    /** Whether an application handler is installed — i.e. whether a close is worth intercepting at all. */
    boolean intercepts() {
        return handler.get() != null && !allowed;
    }

    /**
     * A close was reported for {@code window}. Returns whether the window should <b>keep running</b>: true means
     * the host carries on pumping and presenting it, false means tear it down.
     *
     * <p>Runs on the main thread, inside the frame loop. The handler itself runs on {@code handlers} — an
     * application's answer is application logic, and belongs off the GUI thread with the rest of it.
     */
    boolean keepAlive(NativeWindow window, Executor handlers) {
        Consumer<CloseRequest> h = handler.get();
        if (h == null || allowed) {
            return false;
        }
        if (pending.get() != null) {
            return true;                    // still asking; one question at a time
        }
        if (!window.cancelClose()) {
            return false;                   // destroyed, not asked — nothing to veto
        }
        CloseRequest request = new CloseRequest(
                () -> {
                    allowed = true;
                    pending.set(null);
                    window.requestClose();  // travels the ordinary route, so the loop closes it as it always does
                },
                () -> pending.set(null));
        pending.set(request);
        handlers.execute(() -> h.accept(request));
        return true;
    }

    /** The outstanding request, if the application has not answered yet — for tests and for diagnostics. */
    CloseRequest pending() {
        return pending.get();
    }
}
