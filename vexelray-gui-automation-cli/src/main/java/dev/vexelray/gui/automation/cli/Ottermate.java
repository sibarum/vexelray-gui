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
            "  ottermate list                       the running applications, from their discovery files",
            "",
            "options:",
            "  --app <name>          attach to the running application of this name (see list)",
            "  --pid <n>             attach to the running application with this process id",
            "  --port <n>            attach to this port. Without --port, --app, --pid or --launch: the one",
            "                        application running, or " + AutomationClient.DEFAULT_PORT + " if none is; several is an error",
            "  --diagnostics         attach to the application's diagnostics socket (recorder, rows, frames, log,",
            "                        stream) rather than its control one, when the two are on different ports",
            "  --script <file|->     read commands from a file, or - for stdin",
            "  --launch <command...> start the application, drive it, shut it down; takes the rest of the",
            "                        line, and reads the port from its 'automation: localhost:<port>' line,",
            "                        so give the application --automation=0 for a free one",
            "  --launch-timeout <s>  how long it may take to announce that (default "
                    + Options.DEFAULT_LAUNCH_TIMEOUT_SECONDS + ")",
            "  --timeout <s>         how long one reply may take (default "
                    + AutomationClient.DEFAULT_REPLY_TIMEOUT_SECONDS + ")",
            "  --window <name|n>     act on this window (default: the main one); 'ottermate windows' lists them",
            "  --dpi <factor>        set the display density first",
            "  --zoom <factor>       set the zoom first; clamped to the application's range, and the reply says so",
            "  --size <w>x<h>        size the window first: pixels, or em with a unit, e.g. 1024x768 or 46emx30em",
            "                        (view options go before --launch, which takes the rest of the line)",
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
        if (options.listing()) {
            // No socket: what is running is in the discovery folder, and reading it clears what is stale.
            out.println(Discovery.table(Discovery.live(), Discovery.folder()));
            return Session.OK;
        }
        Target target = options.launching() ? new Target(-1, Session.OK) : target(options, Discovery.live(), err);
        if (target.status() != Session.OK) {
            return target.status();
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
            int port = target.port();
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
                if (!options.view().isEmpty()) {
                    // What is being photographed is settled before anything is asked of it, and a view that
                    // could not be set stops the run: a picture at the wrong zoom reported as the right one is
                    // the failure this tool exists to rule out.
                    BufferedReader view = new BufferedReader(new java.io.StringReader(
                            String.join("\n", options.view())));
                    int status = Session.run(client, view, options, false, false, out, err);
                    if (status != Session.OK) {
                        return status;
                    }
                }
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

    /** The port to attach to, or the status to stop with instead. */
    record Target(int port, int status) {
    }

    /**
     * Which application to attach to (vexelray-gui {@code docs/plans/discovery.md} §5): the {@code --port} given; the
     * one running application {@code --app} or {@code --pid} names; or, with none of those, the only application
     * running, or {@link AutomationClient#DEFAULT_PORT} when none is. Two that match is a refusal listing them, never
     * a guess — the same rule a target inside the application follows. An application speaking a protocol major
     * this client does not know is refused before anything is sent to it.
     */
    static Target target(Options options, List<Discovery.Entry> live, PrintStream err) {
        if (options.portGiven()) {
            for (Discovery.Entry e : live) {
                if (e.port() == options.port() && !speaks(e, err)) {
                    return new Target(-1, Session.UNREACHABLE);
                }
            }
            return new Target(options.port(), Session.OK);
        }
        List<Discovery.Entry> matches = new java.util.ArrayList<>();
        for (Discovery.Entry e : live) {
            if ((options.app() == null || e.app().equalsIgnoreCase(options.app()))
                    && (options.pid() < 0 || e.pid() == options.pid())) {
                matches.add(e);
            }
        }
        if (options.choosing()) {
            if (matches.isEmpty()) {
                err.println("ottermate: no running application matches "
                        + (options.app() != null ? "--app " + options.app() : "")
                        + (options.app() != null && options.pid() >= 0 ? " " : "")
                        + (options.pid() >= 0 ? "--pid " + options.pid() : "")
                        + "; 'ottermate list' shows what is running (" + Discovery.folder() + ")");
                return new Target(-1, Session.UNREACHABLE);
            }
            if (matches.size() > 1) {
                err.println("ottermate: " + matches.size() + " running applications match --app " + options.app()
                        + "; choose one:");
                for (Discovery.Entry e : matches) {
                    err.println("  ottermate --pid " + e.pid() + "    " + e.describe() + ", started " + e.get("started"));
                }
                return new Target(-1, Session.USAGE);
            }
            Discovery.Entry chosen = matches.getFirst();
            return speaks(chosen, err) ? socket(chosen, options, err) : new Target(-1, Session.UNREACHABLE);
        }
        if (live.size() > 1) {
            err.println("ottermate: " + live.size() + " applications are running; choose one with --app or --pid:");
            for (Discovery.Entry e : live) {
                err.println("  ottermate --pid " + e.pid() + "    " + e.describe() + ", started " + e.get("started"));
            }
            return new Target(-1, Session.USAGE);
        }
        if (live.size() == 1) {
            Discovery.Entry only = live.getFirst();
            if (!speaks(only, err)) {
                return new Target(-1, Session.UNREACHABLE);
            }
            Target t = socket(only, options, err);
            if (t.status() == Session.OK && !options.quiet()) {
                err.println("ottermate: " + (options.diagnostics() ? "reading " + only.app() + "'s diagnostics on port "
                        + t.port() : "driving " + only.describe()));
            }
            return t;
        }
        if (options.diagnostics()) {
            err.println("ottermate: --diagnostics reads the port from a running application's discovery file, and "
                    + "none is running (" + Discovery.folder() + ")");
            return new Target(-1, Session.UNREACHABLE);
        }
        return new Target(AutomationClient.DEFAULT_PORT, Session.OK);
    }

    /** {@code e}'s control socket, or its diagnostics one under {@code --diagnostics}; refused when that is off. */
    private static Target socket(Discovery.Entry e, Options options, PrintStream err) {
        int port = e.port(options.diagnostics());
        if (port < 0) {
            err.println("ottermate: " + e.describe() + " has diagnostics off; switch it on from the automation button "
                    + "in its title bar, or launch it with --automation.diagnostics=0");
            return new Target(-1, Session.UNREACHABLE);
        }
        return new Target(port, Session.OK);
    }

    /** Whether this client can talk to {@code e}; says why not on {@code err} when it cannot. */
    private static boolean speaks(Discovery.Entry e, PrintStream err) {
        if (e.major().equals(Discovery.PROTOCOL_MAJOR)) {
            return true;
        }
        err.println("ottermate: " + e.describe() + " speaks protocol " + e.get("protocol") + "; this ottermate speaks "
                + Discovery.PROTOCOL_MAJOR + ".x, so it sends nothing rather than guess");
        return false;
    }

    private static void close(BufferedReader commands, PrintStream err) {
        try {
            commands.close();
        } catch (IOException e) {
            err.println("ottermate: " + e.getMessage());
        }
    }
}
