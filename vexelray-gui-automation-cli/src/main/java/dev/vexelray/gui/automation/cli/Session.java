package dev.vexelray.gui.automation.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Locale;

/**
 * One conversation: commands in from wherever they came, replies out, and a verdict.
 *
 * <p>Separate from the entry point so the verdict can be tested against a fake server rather than against a
 * running application. {@code R5} — a non-zero status when any reply began {@code err} — is the requirement
 * this class exists to satisfy, and it is the one worth a test, because a CLI that returns 0 after a failed
 * {@code shot} recreates exactly the failure the whole tool was written to remove: a script reporting success
 * for a picture nobody took.
 *
 * <p><b>Stops at the first {@code err}, unless told not to.</b> A scene ladder is a sequence in which each
 * step assumes the last one worked; carrying on past a {@code find} that matched nothing produces clicks that
 * land somewhere nobody chose, and pictures of it. {@code --keep-going} is for the case where the steps are
 * genuinely independent, and it still fails the run.
 */
final class Session {

    /** Everything went as asked. */
    static final int OK = 0;

    /** At least one reply began {@code err}. */
    static final int FAILED = 1;

    /** The command line could not be acted on. */
    static final int USAGE = 2;

    /** Nothing was there to drive, or it could not be started. */
    static final int UNREACHABLE = 3;

    /** It was there, and then it was not. */
    static final int DROPPED = 4;

    private Session() {
    }

    /**
     * Run every command in {@code commands} against {@code client}.
     *
     * @param echo whether to print each command above its reply — worth it for a transcript of a script, and
     *             noise at a terminal where the person can already see what they typed
     * @param prompt whether to prompt, which is the whole of being interactive
     * @return one of the statuses above
     */
    static int run(AutomationClient client, BufferedReader commands, Options options,
                   boolean echo, boolean prompt, PrintStream out, PrintStream err) {
        int status = OK;
        try {
            while (true) {
                if (prompt) {
                    out.print("ottermate> ");
                    out.flush();
                }
                String line = commands.readLine();
                if (line == null) {
                    return status;
                }
                String command = line.trim();
                if (command.isEmpty() || command.startsWith("#")) {
                    // Blank lines and comments are the client's own convenience, not the protocol's: a scene
                    // ladder is a file somebody has to read six months later. The application never sees them.
                    continue;
                }
                if (command.toLowerCase(Locale.ROOT).equals("quit")) {
                    // Honoured here rather than sent, because the server answers quit by hanging up and this
                    // says goodbye on close anyway. Sending it and then reading would wait for a reply that
                    // the protocol says will not come.
                    return status;
                }
                if (echo) {
                    out.println("> " + command);
                }
                Reply reply = client.send(command);
                if (reply.err()) {
                    status = FAILED;
                    out.println(reply.text());
                    if (!options.keepGoing()) {
                        err.println("ottermate: stopped at '" + command + "'; --keep-going runs the rest");
                        return status;
                    }
                } else if (!options.quiet()) {
                    out.println(reply.text());
                }
            }
        } catch (IOException e) {
            err.println("ottermate: " + e.getMessage());
            return DROPPED;
        }
    }
}
