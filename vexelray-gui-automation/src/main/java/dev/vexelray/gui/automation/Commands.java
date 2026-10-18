package dev.vexelray.gui.automation;

/**
 * Something that answers one command line with one reply — what {@link AutomationServer} serves. {@link Automation}
 * is one (a single window); {@link Windows} is another (an application's windows, one chosen at a time). The
 * server asks neither which, so a host with one window and a host with five wire it the same way.
 *
 * <p>The server has several connections and one hand (docs/reference/automation.md §5), and the three defaults
 * below are what it needs to know to run them: which lines need the hand, what one connection may choose for
 * itself, and how to leave the pointer at rest when the hand changes. Each answers for its own verbs, so the
 * server holds no list of them.
 */
public interface Commands {

    /** Run one command line and return what to print. Never throws; a failure is a reply that begins {@code err}. */
    String command(String line);

    /**
     * Whether {@code line} acts on the application — moves the pointer, presses, types, navigates, or changes the
     * view — rather than reading it. The server runs such a line only for the connection holding the hand.
     */
    default boolean drives(String line) {
        return false;
    }

    /**
     * Which {@link Capability} a socket must serve to run {@code line}: {@link Capability#CONTROL} for the hand and
     * the reads it aims through, {@link Capability#DIAGNOSTICS} for what the process recorded about itself. A
     * socket that does not serve it refuses the line and runs nothing of it. Control unless a surface says otherwise.
     */
    default Capability needs(String line) {
        return Capability.CONTROL;
    }

    /**
     * These commands as one connection sees them. What a connection chooses for itself (which window its verbs
     * act on) lives in the session; what the application has only one of (each window's pointer) is shared.
     */
    default Commands session() {
        return this;
    }

    /**
     * The hand is being let go: release any button a {@code down} left held, where the pointer is, so whoever
     * takes the hand next starts from a pointer at rest rather than inside someone else's drag.
     */
    default void letGo() {
    }

    /**
     * If {@code line} is a command whose reply is written while it happens rather than returned whole — the
     * flight recorder's {@code stream} — the thing that writes it; otherwise {@code null}, and the server asks
     * {@link #command} as usual. A streaming command always reads: it never needs the hand.
     */
    default Streaming streaming(String line) {
        return null;
    }

    /**
     * What this host offers right now, for {@code hello} (docs/plans/discovery.md §4): one line per subject, a
     * word and then {@code key=value} pairs — the windows, the probe, the recorder, the domains. The server adds
     * the first line (protocol, process, connections, hand) itself. Nothing by default.
     */
    default java.util.List<String> describe() {
        return java.util.List.of();
    }

    /**
     * A reply written as it happens, on the connection's own thread. It writes every line of its reply, starting
     * with {@code ok} or {@code err}; the server writes the terminator after it returns.
     */
    interface Streaming {

        /**
         * Write the reply to {@code out}, flushing as it goes. {@code in} is the connection's input, for a stream
         * that ends when the client sends a line: such a stream leaves that line unread, so the server answers it
         * next as an ordinary command.
         */
        void run(java.io.BufferedReader in, java.io.PrintWriter out) throws java.io.IOException;
    }
}
