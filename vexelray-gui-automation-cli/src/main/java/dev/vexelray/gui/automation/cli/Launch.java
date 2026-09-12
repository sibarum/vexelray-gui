package dev.vexelray.gui.automation.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Start an application, learn where its socket is, and take it down again afterwards.
 *
 * <p><b>The port comes from the child's own stdout.</b> {@code Driver.open} already prints
 * {@code automation: localhost:<port>} when it binds, so this needs no new framework API and — the point of
 * doing it this way — it works with {@code --automation=0}, a free port, which is what makes two runs at once
 * safe. A tool that instead told the application which port to use would be picking numbers on behalf of
 * every concurrent run on the machine.
 *
 * <p><b>It does not know how to start anything.</b> It runs a command line it was given.
 * {@code docs/automation-cli.md} §8 puts application lifecycle out of scope on purpose: knowing that this
 * application is a Maven exec and that one is a native binary is {@code mainframe}'s territory if it is
 * anyone's, and the zero-dependency rule exists so this tool never grows that knowledge.
 *
 * <p><b>The child's output goes to stderr</b> so that this tool's stdout stays nothing but protocol replies.
 * A launched run that mixed the application's log into the replies would be unpipeable, and the reason to
 * relay it at all is the case where no port line ever arrives and the answer is in what the application said
 * instead.
 */
final class Launch implements AutoCloseable {

    /**
     * What {@code Driver.open} prints on binding. Matched anywhere in the line rather than anchored, because
     * a launch through Maven or a wrapper script may have prefixed it by the time it is read.
     */
    private static final Pattern PORT_LINE = Pattern.compile("automation:\\s*localhost:(\\d+)");

    /** How long a destroyed application is given to go quietly before it is made to. */
    private static final int GRACE_SECONDS = 5;

    private final Process process;
    private final int port;
    private final Thread shutdownHook;

    /**
     * Run {@code command}, and return once it has announced its port.
     *
     * @throws IOException if it cannot be started, exits first, or never announces one
     */
    static Launch start(List<String> command, int timeoutSeconds, PrintStream relay) throws IOException {
        Process process = new ProcessBuilder(command)
                // Merged, because the two streams interleaved in the order they were written is what a person
                // reads a failed launch out of; splitting them is how a stack trace arrives shuffled.
                .redirectErrorStream(true)
                .start();
        CompletableFuture<Integer> announced = new CompletableFuture<>();
        Thread pump = new Thread(() -> relay(process, relay, announced), "ottermate-launch-output");
        pump.setDaemon(true);
        pump.start();
        try {
            int port = announced.get(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            return new Launch(process, port);
        } catch (TimeoutException e) {
            throw failed(process, command, "it did not announce a port within " + timeoutSeconds + "s");
        } catch (ExecutionException e) {
            throw failed(process, command, e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed(process, command, "interrupted while waiting for it to announce a port");
        }
    }

    private Launch(Process process, int port) {
        this.process = process;
        this.port = port;
        // A launched application must not outlive the tool that launched it, including when the tool is
        // interrupted at the keyboard part-way through a script. Without this a Ctrl-C leaves a window on
        // screen that nothing is driving and nobody remembers starting.
        this.shutdownHook = new Thread(() -> destroy(process), "ottermate-launch-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    int port() {
        return port;
    }

    /** Whether it is still running — false means it fell over on its own, which is worth reporting. */
    boolean alive() {
        return process.isAlive();
    }

    /** Its exit status, or {@code -1} while it is still running. */
    int exitValue() {
        return process.isAlive() ? -1 : process.exitValue();
    }

    @Override
    public void close() {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // Already shutting down, so the hook is running or has run: it will do the destroying.
            return;
        }
        destroy(process);
    }

    private static void destroy(Process process) {
        if (!process.isAlive()) {
            return;
        }
        // Descendants first, and snapshotted before the parent goes: a launch through `mvn exec:exec` is a
        // shell running Maven running the JVM that owns the window, and destroying only the top of that
        // leaves the window on screen with its parent gone.
        List<ProcessHandle> children = process.descendants().toList();
        children.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(GRACE_SECONDS, TimeUnit.SECONDS)) {
                children.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void relay(Process process, PrintStream relay, CompletableFuture<Integer> announced) {
        // UTF-8 regardless of what the console would have used. The line this is actually reading for is
        // ASCII, so discovery cannot be affected either way, and the rest is a relay: guessing an encoding
        // per platform would trade a rare mojibake in an application's log for a rule nobody can predict.
        try (BufferedReader out = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null) {
                relay.println(line);
                Matcher match = PORT_LINE.matcher(line);
                if (!announced.isDone() && match.find()) {
                    announced.complete(Integer.parseInt(match.group(1)));
                }
            }
        } catch (IOException e) {
            announced.completeExceptionally(new IOException("its output stopped: " + e.getMessage(), e));
        }
        // End of output. If nothing was announced by now nothing will be, and saying so at once beats
        // waiting out a sixty-second timeout for an application that has already gone.
        announced.completeExceptionally(new IOException(
                "it exited without announcing one (status " + waitQuietly(process) + ")"));
    }

    private static String waitQuietly(Process process) {
        try {
            return String.valueOf(process.waitFor());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }

    private static IOException failed(Process process, List<String> command, String why) {
        destroy(process);
        return new IOException("launched '" + String.join(" ", command) + "' and " + why
                + ". The application prints 'automation: localhost:<port>' only when it was asked for a "
                + "socket, so the command needs --automation=0 (a free port) or --automation=<port>");
    }
}
