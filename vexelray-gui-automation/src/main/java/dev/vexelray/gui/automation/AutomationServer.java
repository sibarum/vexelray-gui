package dev.vexelray.gui.automation;

import sibarum.probe.Lane;
import sibarum.probe.Probe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A line protocol on a loopback socket, so an agent outside the process can drive the application inside it.
 *
 * <p>One command per line in, one reply out, terminated by a line containing only {@code .} — a terminator
 * rather than a length, because the thing on the other end is often a person or a shell one-liner and a length
 * prefix makes {@code nc} useless. A reply starts with {@code ok} or {@code err}, so a script can branch on the
 * first two characters without parsing anything.
 *
 * <pre>
 * $ printf 'find Save\nclick 41\nsettle\n' | nc localhost 7654
 * </pre>
 *
 * <p><b>Loopback only, and deliberately.</b> This hands anyone who can reach it full control of the
 * application's input — it is a debugging instrument, not a service. Binding it to a routable address would
 * make every window on the machine a remote-control target, so the address is not configurable.
 *
 * <p><b>One connection at a time.</b> A pointer is one hand: two agents interleaving paths would produce a
 * gesture neither of them asked for, and the log would show a run that nobody performed.
 *
 * <p>Commands run on this server's own thread, never on the GUI thread. Everything they touch is either a
 * lock-free snapshot or the bus, and the one thing that is not — a screenshot — is posted to the frame loop by
 * {@code WindowControls} rather than performed here.
 */
public final class AutomationServer implements AutoCloseable {

    /** The port, when nobody says otherwise. Arbitrary, high, and unregistered. */
    public static final int DEFAULT_PORT = 7654;

    private final Automation automation;
    private final ServerSocket socket;
    private final Thread thread;
    private volatile boolean running = true;

    /** Start listening on {@link #DEFAULT_PORT}. */
    public static AutomationServer start(Automation automation) throws IOException {
        return start(automation, DEFAULT_PORT);
    }

    /** Start listening on {@code port}; pass 0 to be given a free one, then ask {@link #port()}. */
    public static AutomationServer start(Automation automation, int port) throws IOException {
        return new AutomationServer(automation, port);
    }

    private AutomationServer(Automation automation, int port) throws IOException {
        this.automation = java.util.Objects.requireNonNull(automation, "automation");
        this.socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress());
        this.thread = new Thread(this::serve, "vexelray-automation");
        this.thread.setDaemon(true);      // an instrument must never be the reason a process will not exit
        this.thread.start();
    }

    /** The port actually bound — worth asking when 0 was requested. */
    public int port() {
        return socket.getLocalPort();
    }

    private void serve() {
        while (running) {
            try (Socket client = socket.accept()) {
                converse(client);
            } catch (IOException e) {
                if (running) {
                    // One client going away is not the end of the session; the next one is still welcome.
                    System.err.println("vexelray-automation: " + e.getMessage());
                }
            }
        }
    }

    private void converse(Socket client) throws IOException {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(
                client.getOutputStream(), StandardCharsets.UTF_8));
        String line;
        while (running && (line = in.readLine()) != null) {
            if (line.trim().equalsIgnoreCase("quit")) {
                return;
            }
            if (Probe.ON) {
                // The agent's own commands, in the same file and the same order as their effects — which is
                // what lets a run be read back without the conversation that produced it.
                Probe.mark(Lane.APP, "agent.command", line);
            }
            out.println(automation.command(line));
            out.println(".");
            out.flush();     // per reply: the other end is waiting on this one before it sends the next
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            socket.close();
        } catch (IOException e) {
            // Closing a socket that is already gone is not a failure worth reporting from a close().
        }
        thread.interrupt();
    }
}
