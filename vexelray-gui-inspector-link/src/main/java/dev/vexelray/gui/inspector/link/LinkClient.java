package dev.vexelray.gui.inspector.link;

import sibarum.elektro.queue.Conduit;
import sibarum.elektro.queue.DefaultConduit;
import sibarum.elektro.queue.ElektroException;
import sibarum.elektro.queue.message.PeerId;
import sibarum.elektro.queue.transport.tcp.TcpTransport;
import sibarum.elektro.queue.transport.uds.UdsTransport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The Inspector's end of the link: dial an application's {@code stream} endpoint, open the stream, ask read-only
 * queries. Handlers run on elektro-Q's receive thread for this connection, in the order the application sent, and
 * must not block.
 */
public final class LinkClient implements AutoCloseable {

    /** How long a request (opening the stream, a query) waits for its answer. */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final Conduit conduit;
    private final PeerId app;

    private LinkClient(Conduit conduit, PeerId app) {
        this.conduit = conduit;
        this.app = app;
    }

    /** Dial {@code endpoint}: {@code uds:<path>} or {@code tcp:<port>}, as a discovery file's {@code stream} line has it. */
    public static LinkClient connect(String endpoint) {
        DefaultConduit conduit;
        if (endpoint.startsWith("uds:")) {
            conduit = new DefaultConduit("inspector", UdsTransport.connecting(Path.of(endpoint.substring(4))),
                    Wire.registry(), REQUEST_TIMEOUT);
        } else if (endpoint.startsWith("tcp:")) {
            conduit = new DefaultConduit("inspector",
                    TcpTransport.connecting("127.0.0.1", Integer.parseInt(endpoint.substring(4))), Wire.registry(),
                    REQUEST_TIMEOUT);
        } else {
            throw new IllegalArgumentException("not a link endpoint: '" + endpoint + "' (uds:<path> or tcp:<port>)");
        }
        try {
            conduit.start().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            conduit.close();
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ElektroException("could not reach the inspector link at " + endpoint + ": " + cause.getMessage(),
                    cause);
        }
        PeerId app = conduit.peers().stream().findFirst()
                .orElseThrow(() -> new ElektroException("connected to " + endpoint + " but no peer is there"));
        return new LinkClient(conduit, app);
    }

    /** Dial the endpoint an application's discovery file advertises in its {@code stream} line. */
    public static LinkClient connect(Path discoveryFile) throws IOException {
        for (String line : Files.readAllLines(discoveryFile, StandardCharsets.UTF_8)) {
            if (line.startsWith("stream=")) {
                return connect(line.substring("stream=".length()));
            }
        }
        throw new IOException(discoveryFile + " advertises no stream: the application has no inspector link");
    }

    /**
     * Open the stream: batches go to {@code onRows} and snapshots to {@code onSnap} from here on. The answer says where
     * it really starts, or, in {@code err}, why it was refused.
     */
    public StreamOpened open(StreamOpen open, Consumer<RowBatch> onRows, Consumer<Snap> onSnap) {
        conduit.subscribe(Wire.ROW_BATCH, (batch, ctx) -> onRows.accept(batch));
        conduit.subscribe(Wire.SNAP, (snap, ctx) -> onSnap.accept(snap));
        return join(conduit.action(Wire.STREAM_OPEN).request(open, app, Wire.STREAM_OPENED));
    }

    /** Stop the stream; the connection stays open for queries. */
    public void closeStream(String reason) {
        conduit.action(Wire.STREAM_CLOSE).emit(new StreamClose(reason), app);
    }

    /** Ask any read verb, as its line; the reply is the automation socket's text. */
    public QueryReply query(String line) {
        return join(conduit.action(Wire.QUERY).request(new Query(line), app, Wire.QUERY_REPLY));
    }

    private static <R> R join(java.util.concurrent.CompletionStage<R> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw cause instanceof ElektroException ee ? ee : new ElektroException(cause.getMessage(), cause);
        }
    }

    @Override
    public void close() {
        conduit.close();
    }
}
