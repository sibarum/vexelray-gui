package dev.vexelray.gui.automation.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What the command line asked for, parsed once and then only read.
 *
 * <p>A record rather than a bag of fields on the entry point, so the parse is a pure function of {@code argv}
 * and can be tested without a socket, a child process or a temporary file. Every validation that can be done
 * before anything is opened is done here — the two settings that contradict each other are rejected before
 * the application is launched, not after.
 *
 * @param port         the port to attach to; rejected when launching, because the child announces its own
 * @param script       a file of commands, or {@code null}
 * @param command      a single command from the trailing arguments, or {@code null}
 * @param launch       the command line to start, or empty for attach
 * @param launchTimeoutSeconds how long to wait for the launched application to announce its port
 * @param replyTimeoutSeconds  how long to wait for any one reply
 * @param keepGoing    run the rest of the script after a reply that begins {@code err}
 * @param quiet        print only {@code err} replies
 * @param help         print the usage and stop
 */
record Options(int port,
               Path script,
               String command,
               List<String> launch,
               int launchTimeoutSeconds,
               int replyTimeoutSeconds,
               boolean keepGoing,
               boolean quiet,
               boolean help) {

    /** How long an application may take to say it is listening. Vulkan, a window, and a first frame. */
    static final int DEFAULT_LAUNCH_TIMEOUT_SECONDS = 60;

    /** The name {@code --script} takes for standard input, by the usual convention. */
    private static final String STDIN = "-";

    Options {
        launch = List.copyOf(launch);
    }

    boolean launching() {
        return !launch.isEmpty();
    }

    /** Whether commands come from standard input — neither a file nor a trailing command line. */
    boolean readingStdin() {
        return script == null && command == null;
    }

    /**
     * Where the commands come from, opened.
     *
     * <p>One type for all three sources, so the session that runs them does not care which it got. Standard
     * input is a reader rather than a slurped list because that is what makes an attached terminal a prompt
     * loop for free: a line is read, sent and answered before the next one is asked for.
     */
    BufferedReader commands() throws IOException {
        if (command != null) {
            return new BufferedReader(new StringReader(command));
        }
        if (script != null) {
            return Files.newBufferedReader(script, StandardCharsets.UTF_8);
        }
        return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }

    /**
     * Read {@code argv}.
     *
     * <p>An if/else chain rather than a switch, because half of these consume the next argument and one of
     * them consumes all of the rest; the lookahead reads better than the jump table would.
     *
     * @throws UsageException if the line cannot be acted on
     */
    static Options parse(String[] args) {
        int port = -1;
        Path script = null;
        List<String> launch = List.of();
        int launchTimeout = DEFAULT_LAUNCH_TIMEOUT_SECONDS;
        int replyTimeout = AutomationClient.DEFAULT_REPLY_TIMEOUT_SECONDS;
        boolean keepGoing = false;
        boolean quiet = false;
        boolean help = false;
        List<String> trailing = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--help") || arg.equals("-h")) {
                help = true;
            } else if (arg.equals("--port")) {
                port = number(value(args, ++i, "--port"), "--port");
            } else if (arg.equals("--script")) {
                String where = value(args, ++i, "--script");
                // Explicit stdin is the same source as the default one, so it is recorded by leaving the
                // script null rather than by a third flag that could come to disagree with them.
                script = where.equals(STDIN) ? null : Path.of(where);
            } else if (arg.equals("--launch")) {
                // Everything after it, verbatim: the child's own flags are not this tool's to interpret, and
                // an application that takes a --port of its own must not have it stolen here.
                launch = List.of(args).subList(i + 1, args.length);
                if (launch.isEmpty()) {
                    throw new UsageException("--launch needs a command to run");
                }
                i = args.length;
            } else if (arg.equals("--launch-timeout")) {
                launchTimeout = number(value(args, ++i, "--launch-timeout"), "--launch-timeout");
            } else if (arg.equals("--timeout")) {
                replyTimeout = number(value(args, ++i, "--timeout"), "--timeout");
            } else if (arg.equals("--keep-going")) {
                keepGoing = true;
            } else if (arg.equals("--quiet") || arg.equals("-q")) {
                quiet = true;
            } else if (arg.startsWith("--")) {
                throw new UsageException("no option '" + arg + "'");
            } else {
                trailing.add(arg);
            }
        }

        String command = trailing.isEmpty() ? null : String.join(" ", trailing);

        if (help) {
            return new Options(AutomationClient.DEFAULT_PORT, null, null, List.of(),
                    launchTimeout, replyTimeout, keepGoing, quiet, true);
        }
        if (!launch.isEmpty() && port >= 0) {
            // Not merely redundant. A launched application is told its port by whoever wrote the --launch
            // line, and it may have been told 0 — a free one, which is what makes concurrent runs safe. The
            // port that matters is the one it announces on stdout, so a --port here is either the same number
            // said twice or a wrong answer that would attach to somebody else's window.
            throw new UsageException("--port and --launch disagree by construction: a launched application's "
                    + "port is read from its own 'automation: localhost:<port>' line, so pass the port to the "
                    + "application (--automation=<port>, or --automation=0 for a free one) and not to this tool");
        }
        if (script != null && command != null) {
            throw new UsageException("--script " + script + " and the command '" + command
                    + "' are two sources of commands; give one");
        }
        return new Options(port < 0 ? AutomationClient.DEFAULT_PORT : port, script, command, launch,
                launchTimeout, replyTimeout, keepGoing, quiet, false);
    }

    private static String value(String[] args, int at, String option) {
        if (at >= args.length) {
            throw new UsageException(option + " needs a value");
        }
        return args[at];
    }

    private static int number(String text, String option) {
        int n;
        try {
            n = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new UsageException(option + " wants a number, and got '" + text + "'");
        }
        if (n < 0) {
            throw new UsageException(option + " cannot be negative, and got " + n);
        }
        return n;
    }
}
