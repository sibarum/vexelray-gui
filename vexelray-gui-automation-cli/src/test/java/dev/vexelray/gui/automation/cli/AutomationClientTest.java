package dev.vexelray.gui.automation.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The frame: what the client must get right about the format it reads. */
class AutomationClientTest {

    @Test
    void readsAOneLineReplyWithoutItsTerminator() throws IOException {
        try (FakeApplication app = FakeApplication.answering(c -> "ok 12,340");
             AutomationClient client = AutomationClient.attach(app.port())) {
            Reply reply = client.send("where");
            assertEquals(List.of("ok 12,340"), reply.lines());
            assertEquals("where", reply.command());
            assertFalse(reply.err());
        }
    }

    @Test
    void readsEveryLineOfAMultiLineReply() throws IOException {
        // A tree is the reason the terminator exists: the reply is as long as the application is deep, and a
        // client that stopped at the first line would report a one-node window.
        String tree = String.join("\n", "ok", "  41 button Save [10,10 80x24]", "  42 button Quit [10,40 80x24]");
        try (FakeApplication app = FakeApplication.answering(c -> tree);
             AutomationClient client = AutomationClient.attach(app.port())) {
            Reply reply = client.send("tree");
            assertEquals(3, reply.lines().size());
            assertEquals(tree, reply.text());
            assertFalse(reply.err(), "err is the first line only, never a line further down");
        }
    }

    @Test
    void aReplyMentioningErrorFurtherDownIsStillOk() throws IOException {
        try (FakeApplication app = FakeApplication.answering(c -> "ok\n  41 label err: disk full [0,0 90x12]");
             AutomationClient client = AutomationClient.attach(app.port())) {
            assertFalse(client.send("find err").err(),
                    "a listing of a node whose name says 'err' is a successful find");
        }
    }

    @Test
    void errIsReadOffTheFirstLine() throws IOException {
        try (FakeApplication app = FakeApplication.answering(c -> "err did not settle within 2000ms");
             AutomationClient client = AutomationClient.attach(app.port())) {
            assertTrue(client.send("settle").err());
        }
    }

    @Test
    void aBlankReplyIsNotAFailure() throws IOException {
        // The server prints an empty body for an empty command, then the terminator. Nothing was asked, so
        // nothing failed.
        try (FakeApplication app = FakeApplication.answering(c -> "");
             AutomationClient client = AutomationClient.attach(app.port())) {
            Reply reply = client.send("  ");
            assertFalse(reply.err());
            assertEquals("", reply.text());
        }
    }

    @Test
    void commandsArriveOneToALineAndInOrder() throws IOException {
        try (FakeApplication app = FakeApplication.answering(c -> "ok")) {
            try (AutomationClient client = AutomationClient.attach(app.port())) {
                client.send("click 41");
                client.send("type hello world");
            }
            assertEquals(List.of("click 41", "type hello world", "quit"), app.received(),
                    "close says goodbye, so the server's accept loop comes back round cleanly");
        }
    }

    @Test
    void anApplicationThatHangsUpMidReplyIsReported() throws IOException {
        try (FakeApplication app = FakeApplication.answering(c -> null);
             AutomationClient client = AutomationClient.attach(app.port())) {
            IOException e = assertThrows(IOException.class, () -> client.send("shot after.png"));
            assertTrue(e.getMessage().contains("shot after.png"),
                    "the message has to name the command, or a script's last line is a guess: " + e.getMessage());
        }
    }

    @Test
    void anApplicationThatStopsAnsweringIsBoundedRatherThanWaitedOnForever() throws IOException {
        try (FakeApplication app = FakeApplication.answering(c -> {
            try {
                Thread.sleep(30_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "ok too late";
        }); AutomationClient client = AutomationClient.attach(app.port(), 1)) {
            IOException e = assertThrows(IOException.class, () -> client.send("settle"));
            assertTrue(e.getMessage().contains("within 1s"), e.getMessage());
        }
    }

    @Test
    void attachingToNothingSaysWhatToStart() {
        // Port 1 on loopback: privileged, and nothing on this stack listens there.
        IOException e = assertThrows(IOException.class, () -> AutomationClient.attach(1, 1));
        assertTrue(e.getMessage().contains("--automation="),
                "a refused connection is nearly always a missing flag, so the message names it: "
                        + e.getMessage());
    }
}
