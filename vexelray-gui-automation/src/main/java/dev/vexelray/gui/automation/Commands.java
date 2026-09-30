package dev.vexelray.gui.automation;

/**
 * Something that answers one command line with one reply — what {@link AutomationServer} serves. {@link Automation}
 * is one (a single window); {@link Windows} is another (an application's windows, one chosen at a time). The
 * server asks neither which, so a host with one window and a host with five wire it the same way.
 */
public interface Commands {

    /** Run one command line and return what to print. Never throws; a failure is a reply that begins {@code err}. */
    String command(String line);
}
