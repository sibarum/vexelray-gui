package dev.vexelray.gui.automation.cli;

/**
 * The command line said something this tool cannot act on.
 *
 * <p>Separate from every other failure because the answer is different: a usage error is fixed by typing
 * something else, so it prints the usage and exits {@code 2}, where a refused connection prints what to start
 * and exits {@code 3}. A tool that returns the same status for "you typed it wrong" and "the application is
 * not running" makes a script guess between them.
 */
final class UsageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    UsageException(String message) {
        super(message);
    }
}
