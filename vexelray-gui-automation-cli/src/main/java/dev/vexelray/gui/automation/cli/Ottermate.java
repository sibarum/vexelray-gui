package dev.vexelray.gui.automation.cli;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Drive a VexelRay application from a command line.
 *
 * <pre>
 * ottermate shot after.png                          attach to :7654, take one picture
 * ottermate --port 7655 tree                        somewhere else
 * ottermate --script panels.txt                     a ladder of scenes, one command per line
 * ottermate                                         read commands from stdin; a prompt, at a terminal
 * ottermate --launch mvn -pl app exec:exec -Dautomation=0
 *                                                   start it, drive it, take it down
 * </pre>
 *
 * <p>Commands are the application's, not this tool's: {@code tree}, {@code find}, {@code go}, {@code click},
 * {@code type}, {@code settle}, {@code shot} and the rest, listed by {@code help} over the socket. Nothing
 * here knows what any of them mean, which is why a verb added to {@code Automation} needs no change on this
 * side.
 *
 * <p><b>stdout is replies and nothing else.</b> This tool's own complaints and a launched application's
 * output both go to stderr, so {@code ottermate tree > tree.txt} gets a tree.
 *
 * <p><b>The status is the verdict.</b> {@code 0} everything answered {@code ok}; {@code 1} something answered
 * {@code err} — including a {@code settle} that timed out, which {@code Automation.settle} reports rather
 * than swallows; {@code 2} the command line; {@code 3} nothing to drive; {@code 4} it went away mid-run.
 */
public final class Ottermate {

    private static final String USAGE = String.join("\n", List.of(
            "ottermate: drive a running VexelRay application over its automation socket",
            "",
            "  ottermate [options] [command...]     one command from the remaining arguments",
            "  ottermate [options] --script <file>  one command per line; - is stdin",
            "  ottermate [options]                  commands from stdin (a prompt, at a terminal)",
            "",
            "options:",
            "  --port <n>            attach to this port (default " + AutomationClient.DEFAULT_PORT + ")",
            "  --script <file|->     read commands from a file, or - for stdin",
            "  --launch <command...> start the application, drive it, shut it down; takes the rest of the",
            "                        line, and reads the port from its 'automation: localhost:<port>' line,",
            "                        so give the application --automation=0 for a free one",
            "  --launch-timeout <s>  how long it may take to announce that (default "
                    + Options.DEFAULT_LAUNCH_TIMEOUT_SECONDS + ")",
            "  --timeout <s>         how long one reply may take (default "
                    + AutomationClient.DEFAULT_REPLY_TIMEOUT_SECONDS + ")",
            "  --keep-going          run the rest after a reply that begins err (still exits non-zero)",
            "  --quiet, -q           print only err replies",
            "  --help, -h            this",
            "",
            "The commands themselves are the application's; ask it, with: ottermate help",
            "Loopback only, and there is no option for anything else: the socket is a debugging",
            "instrument and hands whoever reaches it full control of the application's input.",
            "",
            "exit status: 0 all ok  1 something answered err  2 usage  3 nothing to drive  4 it went away"));

    private Ottermate() {
    }

    public static void main(String[] args) {
        // UTF-8 out, not the console's default. The protocol is UTF-8 and an accessible name is whatever the
        // application called something: the calculator's status line contains a middot and a superscript two,
        // and printing those through a legacy console encoding turns them into question marks. That is not
        // only ugly - a `find` typed back from a mangled `tree` matches nothing, so the instrument would be
        // quietly wrong about the one thing it is for. This tool's own messages are ASCII (see USAGE); the
        // application's replies are its own text and are relayed as they arrived.
        System.exit(run(args, utf8(FileDescriptor.out), utf8(FileDescriptor.err)));
    }

    private static PrintStream utf8(FileDescriptor fd) {
        return new PrintStream(new FileOutputStream(fd), true, StandardCharsets.UTF_8);
    }

    /** The whole tool, with its streams handed in, so a test can read what a run printed. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (UsageException e) {
            err.println("ottermate: " + e.getMessage());
            err.println();
            err.println(USAGE);
            return Session.USAGE;
        }
        if (options.help()) {
            out.println(USAGE);
            return Session.OK;
        }

        BufferedReader commands;
        try {
            commands = options.commands();
        } catch (IOException e) {
            err.println("ottermate: cannot read " + options.script() + ": " + e.getMessage());
            return Session.USAGE;
        }

        Launch launch = null;
        try {
            int port = options.port();
            if (options.launching()) {
                launch = Launch.start(options.launch(), options.launchTimeoutSeconds(), err);
                port = launch.port();
                err.println("ottermate: driving localhost:" + port);
            }
            try (AutomationClient client = AutomationClient.attach(port, options.replyTimeoutSeconds())) {
                // A prompt only where there is somebody to read it. An echo only for a run of several
                // commands nobody watched go by: a script's transcript is unreadable without the command
                // above its reply, where a terminal and a one-off both already have it on screen.
                boolean interactive = options.readingStdin() && System.console() != null;
                boolean echo = options.command() == null && !interactive && !options.quiet();
                return Session.run(client, commands, options, echo, interactive, out, err);
            }
        } catch (IOException e) {
            err.println("ottermate: " + e.getMessage());
            return Session.UNREACHABLE;
        } finally {
            close(commands, err);
            if (launch != null) {
                // Reported before the shutdown, because after it every application has died and only one of
                // them chose to: an application that fell over mid-script is the finding, not the cleanup.
                if (!launch.alive()) {
                    err.println("ottermate: the application exited on its own, status " + launch.exitValue());
                }
                launch.close();
            }
        }
    }

    private static void close(BufferedReader commands, PrintStream err) {
        try {
            commands.close();
        } catch (IOException e) {
            err.println("ottermate: " + e.getMessage());
        }
    }
}
