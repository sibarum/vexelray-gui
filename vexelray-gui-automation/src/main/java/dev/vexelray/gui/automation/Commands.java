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
}
