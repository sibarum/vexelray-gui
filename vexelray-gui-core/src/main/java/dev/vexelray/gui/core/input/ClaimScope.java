package dev.vexelray.gui.core.input;

/**
 * How widely a claim on a keyboard chord applies. A claim is <b>preemption declared in advance</b>: when one
 * matches, its command runs and nothing else sees the key — not another element, not the framework's own
 * defaults.
 *
 * <p>This exists because the alternative cannot: {@code preventDefault} needs a handler to answer
 * <em>synchronously</em> that it consumed the event, and handlers here run on worker threads through the bus. By
 * the time one could answer, the frame is over. Every framework offering bubbling and cancellation is relying on
 * handlers running inline on the GUI thread. Declaring preemption up front is what replaces it: the dispatcher
 * reads claims on the GUI thread and decides immediately, while the command itself runs wherever it likes.
 *
 * <p>Precedence runs in declaration order below — the most specific context wins — so the framework's own
 * defaults are registered as ordinary {@link #GLOBAL} claims that any focused element can outrank rather than
 * as interception baked into the dispatcher.
 */
public enum ClaimScope {

    /** Only while the claiming node holds keyboard focus. A multiline editor taking Tab for indentation. */
    FOCUSED,

    /** Whenever the claiming node is in the tree. An open dialog taking Esc while it is up. */
    VISIBLE,

    /** Always, whether or not the claiming node still exists. An application-wide Ctrl+S. */
    GLOBAL
}
