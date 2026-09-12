package dev.vexelray.gui.automation.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The reader of the automation wire format, beside the writer that defines it.
 *
 * <p>Twenty lines, which is the argument for writing them here once rather than at every point of use. The
 * protocol is small enough to reimplement in an afternoon and has been, in PowerShell, by an agent that needed
 * a picture and found no client on the stack — see {@code docs/automation-cli.md} §1. An instrument that has
 * to be rebuilt where it is used is not shipped.
 *
 * <p><b>Loopback, and there is no option for anything else.</b> {@code AutomationServer} binds the loopback
 * address and says the address is not configurable because a routable one would make every window on the
 * machine a remote-control target. A client offering {@code --host} would document a capability the server
 * refuses, so it does not offer one.
 *
 * <p><b>The frame.</b> One command line out; lines back until one containing only {@code .}. A reply body line
 * that were itself a lone {@code .} would end the reply early — the format has no escape for it, and nothing
 * the application can print produces one, so this reads it the way the server writes it rather than inventing
 * a quoting rule only one side would know.
 *
 * <p><b>Bounded reads.</b> The socket carries a read timeout, because the failure this whole tool exists to
 * prevent is a script that reports confidently on something that never happened — and a client blocked forever
 * on a hung application is that failure with the verdict withheld instead of wrong.
 */
public final class AutomationClient implements AutoCloseable {

    /**
     * The port when nobody says otherwise.
     *
     * <p>The same number as {@code AutomationServer.DEFAULT_PORT}, restated rather than imported: this module
     * has an empty dependency block on purpose ({@code docs/automation-cli.md} §3, R3) so the tool runs with
     * none of the stack on its classpath. That is the one duplicated literal the zero-dependency rule costs,
     * and it is why the client lives in this repo — a change to the server's default is one commit that
     * touches both sides ({@code docs/automation-cli.md} §4).
     */
    public static final int DEFAULT_PORT = 7654;

    /**
     * How long to wait for a reply before giving up on the application.
     *
     * <p>Comfortably above the longest bound on the other side — {@code Automation.await} waits 30s, and every
     * other verb less — so a timeout here means the application stopped answering rather than that it is
     * legitimately still waiting.
     */
    public static final int DEFAULT_REPLY_TIMEOUT_SECONDS = 120;

    /** Long enough for a loopback connect to a listening socket, short enough to fail fast when nothing is. */
    private static final int CONNECT_TIMEOUT_MS = 3_000;

    /** The line that ends a reply. */
    private static final String TERMINATOR = ".";

    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;
    private final int port;

    /** Attach to a running application on the loopback address, with the default reply timeout. */
    public static AutomationClient attach(int port) throws IOException {
        return attach(port, DEFAULT_REPLY_TIMEOUT_SECONDS);
    }

    /** Attach to a running application on the loopback address. */
    public static AutomationClient attach(int port, int replyTimeoutSeconds) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(Math.max(1, replyTimeoutSeconds) * 1_000);
            socket.setTcpNoDelay(true);   // one small line at a time, each awaited: Nagle has nothing to batch
        } catch (IOException e) {
            socket.close();
            throw new IOException("nothing is listening on localhost:" + port
                    + "; start the application with --automation=" + port
                    + " (or --automation=0 and let --launch read the port)", e);
        }
        return new AutomationClient(socket, port);
    }

    private AutomationClient(Socket socket, int port) throws IOException {
        this.socket = socket;
        this.port = port;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    /** The port this is attached to. */
    public int port() {
        return port;
    }

    /**
     * Send one command and read its whole reply.
     *
     * <p>Blocking, one at a time, which is what the protocol is: the server flushes per reply because the
     * other end is waiting on this one before it sends the next.
     *
     * @throws IOException if the connection dropped, or the application stopped answering within the timeout
     */
    public Reply send(String command) throws IOException {
        out.println(command);
        out.flush();
        List<String> lines = new ArrayList<>();
        while (true) {
            String line = readLine(command);
            if (line == null) {
                throw new IOException("the application closed the connection part-way through the reply to '"
                        + command + "'" + (lines.isEmpty() ? "" : "; got: " + String.join(" / ", lines)));
            }
            if (line.equals(TERMINATOR)) {
                return new Reply(command, lines);
            }
            lines.add(line);
        }
    }

    private String readLine(String command) throws IOException {
        try {
            return in.readLine();
        } catch (SocketTimeoutException e) {
            throw new IOException("the application did not finish answering '" + command + "' within "
                    + socket.getSoTimeout() / 1000 + "s", e);
        }
    }

    /**
     * End the conversation the way the server expects.
     *
     * <p>{@code quit} gets no reply — the server returns from the exchange on seeing it — so this sends and
     * does not read. Closing the socket alone would work; saying goodbye lets the server's accept loop come
     * back round cleanly rather than through an exception on a half-closed stream.
     */
    @Override
    public void close() {
        try {
            out.println("quit");
            out.flush();
        } catch (RuntimeException e) {
            // Already gone. There is nothing a goodbye can fix, and nothing worth reporting from a close().
        }
        try {
            socket.close();
        } catch (IOException e) {
            // Same.
        }
    }
}
