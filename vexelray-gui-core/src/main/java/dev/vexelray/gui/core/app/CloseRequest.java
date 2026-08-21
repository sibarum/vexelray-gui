package dev.vexelray.gui.core.app;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A window asking to close, handed to the application so it can answer. The user pressed the close button, or
 * Alt+F4, or the system asked — and the window is <b>still open</b> while this request is unanswered: it draws,
 * it takes input, and it can put a dialog up asking whether to save.
 *
 * <p><b>Why a request rather than a returned boolean.</b> Handlers here run on worker threads, so there is no
 * answer to return: by the time an application had asked the user anything, the frame that posed the question
 * would be long over. The request is therefore an object with a deadline of its own — hold it as long as the
 * conversation with the user takes, then call {@link #proceed()} or {@link #cancel()}. That is exactly the shape
 * a modal dialog needs, and it is the reason close interception and dialogs landed together.
 *
 * <p>Both answers are idempotent and only the first one counts: a request that has been answered stays answered.
 * An unanswered request leaves the window open indefinitely — the application's own bug, and a visible one.
 * Either method may be called from any thread.
 */
public final class CloseRequest {

    private final Runnable onProceed;
    private final Runnable onCancel;
    private final AtomicBoolean answered = new AtomicBoolean();

    CloseRequest(Runnable onProceed, Runnable onCancel) {
        this.onProceed = onProceed;
        this.onCancel = onCancel;
    }

    /** Let the window close, now — the answer "nothing to save, go ahead". */
    public void proceed() {
        if (answered.compareAndSet(false, true)) {
            onProceed.run();
        }
    }

    /** Keep the window open. A later close attempt raises a fresh request; this one is spent. */
    public void cancel() {
        if (answered.compareAndSet(false, true)) {
            onCancel.run();
        }
    }

    /** Whether {@link #proceed()} or {@link #cancel()} has already been called. */
    public boolean answered() {
        return answered.get();
    }
}
