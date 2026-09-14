package dev.vexelray.gui.automation.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * An application, as far as the wire is concerned: the same framing {@code AutomationServer} writes, and
 * nothing else.
 *
 * <p>Hand-written rather than the real server, and deliberately. Depending on {@code vexelray-gui-automation}
 * to test this would put {@code -core} on the test classpath of a module whose whole claim is that it needs
 * none of the stack, and it would test the two halves against each other instead of testing this half against
 * the format. What the client must get right is the <em>frame</em> — a line per command, lines back, a lone
 * {@code .} to end, {@code quit} answered by hanging up — so that is what this speaks.
 */
final class FakeApplication implements AutoCloseable {

    private final ServerSocket socket;
    private final Thread thread;
    private final List<String> received = new ArrayList<>();
    private volatile boolean running = true;

    /**
     * @param answer what to reply to a command, as the whole multi-line body; {@code null} to hang up on it,
     *               which is how an application that fell over mid-session is played
     */
    static FakeApplication answering(Function<String, String> answer) throws IOException {
        return new FakeApplication(answer);
    }

    private FakeApplication(Function<String, String> answer) throws IOException {
        this.socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.thread = new Thread(() -> serve(answer), "fake-application");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    int port() {
        return socket.getLocalPort();
    }

    /**
     * Every command line this was sent, in order — including the goodbye — once there are at least {@code count}
     * of them.
     *
     * <p><b>There is deliberately no unconditional accessor.</b> Closing the client writes {@code quit} and
     * returns; it does not wait for this thread to read it. So a test that closed a client and then asked what
     * had arrived was asking whether a line had crossed a socket yet, and got a different answer under load than
     * it did alone — four tests here did exactly that, passing on their own and failing in a full reactor run.
     * Taking the count makes the question "have these arrived", which has an answer worth waiting for.
     */
    synchronized List<String> awaitReceived(int count) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (received.size() < count) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw new AssertionError("waited 5s for " + count + " lines, saw " + received);
            }
            try {
                wait(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for " + count + " lines, saw " + received, e);
            }
        }
        return List.copyOf(received);
    }

    private void serve(Function<String, String> answer) {
        while (running) {
            try (Socket client = socket.accept()) {
                converse(client, answer);
            } catch (IOException e) {
                if (running) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    private void converse(Socket client, Function<String, String> answer) throws IOException {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(
                client.getOutputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            synchronized (this) {
                received.add(line);
                notifyAll();
            }
            if (line.trim().toLowerCase(Locale.ROOT).equals("quit")) {
                return;
            }
            String reply = answer.apply(line);
            if (reply == null) {
                return;                       // hung up part-way through: the application went away
            }
            out.println(reply);
            out.println(".");
            out.flush();
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            socket.close();
        } catch (IOException e) {
            // Nothing a close can do about a socket already gone.
        }
        thread.interrupt();
    }
}
