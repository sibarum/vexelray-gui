package dev.vexelray.gui.automation;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command surface an agent drives (docs/automation.md §5).
 *
 * <p>Two things are worth pinning beyond "the verb runs". First, a ref resolves to a place <em>at the moment of
 * acting</em>, never to a coordinate the agent read a frame earlier — that stale-coordinate click is the classic
 * automation flake and it cannot be retried, because it has already landed somewhere. Second, a target that is
 * concealed is <em>navigated to</em> rather than refused: the framework already knows how to reveal a node,
 * and an automation driver that re-solved that problem would be enumerating the containers that can hide
 * something, which is an enumeration nobody ever finishes.
 */
class AutomationTest {

    private static final float W = 800f;
    private static final float H = 600f;

    private static Gui deterministic() {
        return new Gui(sibarum.atchung.Atchung.create(), Runnable::run);
    }

    private static final TextMeasurer NO_TEXT = new TextMeasurer() {
        @Override
        public float intrinsic(RetainedNode node, Axis axis, float px) {
            return 0f;
        }

        @Override
        public int offsetAt(String text, float localX, float px) {
            return 0;
        }

        @Override
        public float[] caretAdvances(String text, float px) {
            int n = text == null ? 0 : text.length();
            return new float[n + 1];    // zero-width glyphs, but the right number of caret boundaries
        }
    };

    @Test
    void findReportsRoleNameAndTheDurableLandmark() {
        try (Gui gui = deterministic()) {
            Node save = gui.box().role("button").size(Length.dp(100), Length.dp(40))
                    .children(gui.text("Save"));
            gui.landmark("save", save);
            gui.root().children(save);
            gui.frame(W, H, NO_TEXT);

            // Two matches, and correctly so: the button is named "Save" because its label is, and the label is
            // itself a node named "Save". A search is not a disambiguator — the landmark is.
            String out = new Automation(gui).command("find Save");
            assertTrue(out.startsWith("ok 2"), out);
            assertTrue(out.contains("button"), out);
            assertTrue(out.contains("\"Save\""), out);
            // The landmark is shown so an agent reaches for it first: an id is minted per run and means
            // nothing in the next one, which is what makes it the wrong thing to write down.
            assertTrue(out.contains("@save"), "the durable name has to be visible in the listing: " + out);
        }
    }

    @Test
    void aRefIsResolvedWhereTheNodeIsNowRatherThanWhereItWas() {
        try (Gui gui = deterministic()) {
            Node spacer = gui.box().size(Length.dp(10), Length.dp(10));
            Node target = gui.box().role("button").size(Length.dp(100), Length.dp(40));
            AtomicInteger clicks = new AtomicInteger();
            gui.onClick(target, clicks::incrementAndGet);
            gui.root().children(gui.column().width(Length.FILL).children(spacer, target));
            gui.frame(W, H, NO_TEXT);

            long ref = target.id();
            // The node moves after the agent has learned its ref, exactly as a real UI moves between the read
            // and the act. Resolving at act time is the whole reason a ref is not a coordinate.
            spacer.size(Length.dp(10), Length.dp(200));
            gui.frame(W, H, NO_TEXT);

            Automation driver = new Automation(gui);
            assertTrue(driver.command("click " + ref).startsWith("ok"));
            gui.frame(W, H, NO_TEXT);
            assertEquals(1, clicks.get(), "the click has to land on the node, not on where it used to be");
        }
    }

    @Test
    void aConcealedTargetIsNavigatedToRatherThanRefused() {
        // The default executor, not the same-thread one the other tests use. This test needs a driver thread
        // and a loop thread, and with Runnable::run the driver's own publish would run handlers on the driver
        // while the loop laid out the same tree — two threads inside the GUI, which is the one thing the
        // framework's threading model does not allow. That is a property of this arrangement, not of the
        // driver: a real application has a loop thread already.
        try (Gui gui = new Gui(sibarum.atchung.Atchung.create())) {
            Node hidden = gui.box().role("button").size(Length.dp(100), Length.dp(40)).visible(false);
            Node drawer = gui.box().width(Length.FILL).height(Length.dp(200)).children(hidden);
            gui.landmark("buried", hidden);
            // The container declares how to un-conceal what is inside it — the framework's own inversion, so
            // navigation never has to know what a drawer is.
            gui.reveals(drawer, descendant -> {
                hidden.visible(true);
                return true;
            });
            gui.root().children(drawer);
            gui.frame(W, H, NO_TEXT);
            assertFalse(gui.semanticSnapshot().node(hidden.id()).visible());

            AtomicInteger clicks = new AtomicInteger();
            gui.onClick(hidden, clicks::incrementAndGet);

            // Navigation takes frames rather than returning done — a reveal has to be laid out before the next
            // step can see it — so this test has to be a loop and a driver, the two threads a real application
            // already has. Driving it from the thread that also owns frames would wait for a frame it is itself
            // preventing, which is not a property of the driver but of asking one thread to do both.
            String out = drivenWithFramesRunning(gui, driver -> driver.command("click buried"));
            assertTrue(out.startsWith("ok"), out);
            // The handler runs on a worker, so the count arrives after the frame that dispatched the click —
            // the same asynchrony settle() cannot see through, and the reason this waits rather than asserts.
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (clicks.get() == 0 && System.nanoTime() < deadline) {
                gui.frame(W, H, NO_TEXT);
                Thread.onSpinWait();
            }
            assertEquals(1, clicks.get(), "the concealed target was revealed and then clicked");
        }
    }

    /**
     * Run {@code work} on a driver thread while this thread keeps laying out frames — the arrangement a real
     * application has anyway, and the only one in which a command that waits for the loop can be satisfied.
     */
    private static String drivenWithFramesRunning(Gui gui, java.util.function.Function<Automation, String> work) {
        java.util.concurrent.CompletableFuture<String> answer = new java.util.concurrent.CompletableFuture<>();
        Thread driver = new Thread(() -> answer.complete(work.apply(new Automation(gui))), "test-driver");
        driver.setDaemon(true);
        driver.start();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!answer.isDone() && System.nanoTime() < deadline) {
            gui.frame(W, H, NO_TEXT);
            Thread.onSpinWait();
        }
        return answer.getNow("err the driver never answered");
    }

    @Test
    void aConcealedTargetWithNoLandmarkSaysWhyItCannotBeReached() {
        try (Gui gui = deterministic()) {
            Node hidden = gui.box().role("button").size(Length.dp(100), Length.dp(40)).visible(false);
            gui.root().children(gui.box().width(Length.FILL).children(hidden));
            gui.frame(W, H, NO_TEXT);

            String out = new Automation(gui).command("click " + hidden.id());
            // Not a silent click at the rect a hidden node still publishes — that would hit whatever is on top
            // of that spot and report success (docs/semantic-read-model.md §6).
            assertTrue(out.startsWith("err"), out);
            assertTrue(out.contains("not visible") && out.contains("landmark"),
                    "and it has to say what would have made it reachable: " + out);
        }
    }

    @Test
    void typingGoesThroughTheOrdinaryCharacterChannel() {
        try (Gui gui = deterministic()) {
            StringBuilder typed = new StringBuilder();
            Node field = gui.box().size(Length.dp(200), Length.dp(40));
            gui.onChar(field, cp -> typed.appendCodePoint(cp));
            gui.root().children(field);
            gui.frame(W, H, NO_TEXT);
            gui.focus(field);

            new Automation(gui).command("type hey");
            gui.frame(W, H, NO_TEXT);
            // Never a set-the-text shortcut: what is under test is the field, and a field written to rather
            // than typed into has skipped every claim, caret move and edit the typing would have caused.
            assertEquals("hey", typed.toString());
        }
    }

    /**
     * The failure this exists to prevent, found by driving a real table: a virtualised list keeps rows it has
     * scrolled past, those rows are laid out where their index says rather than where they can be seen, and
     * the centre of that rect belongs to whatever is drawn there — a sticky header, the next panel down. The
     * old resolution aimed at it, clicked something else, and answered {@code ok}, which is the one outcome an
     * instrument may never produce: a confident wrong answer about a UI that did something else entirely.
     */
    @Test
    void aRowClippedOutOfItsListIsRefusedRatherThanClickedThroughTo() {
        try (Gui gui = deterministic()) {
            AtomicInteger footerClicks = new AtomicInteger();
            AtomicInteger rowClicks = new AtomicInteger();
            // Four 40px rows in a 100px list: the last is laid out at y=120, which is inside the footer.
            Node[] rows = new Node[4];
            for (int i = 0; i < rows.length; i++) {
                rows[i] = gui.box().width(Length.FILL).height(Length.dp(40));
            }
            Node list = gui.column().width(Length.dp(200)).height(Length.dp(100)).scroll(false, true)
                    .children(rows);
            Node footer = gui.box().width(Length.dp(200)).height(Length.dp(100));
            gui.root().children(gui.column().width(Length.FILL).children(list, footer));
            gui.onClick(footer, footerClicks::incrementAndGet);
            gui.onClick(rows[3], rowClicks::incrementAndGet);
            gui.frame(W, H, NO_TEXT);

            NodeLayout scrolledOut = rows[3].layout();
            assertTrue(scrolledOut.clippedAway(), "the premise: it has a rect, and none of it is on screen");
            assertTrue(footer.layout().rect().contains(scrolledOut.rect().centreX(), scrolledOut.rect().centreY()),
                    "and the point the old resolution would have aimed at belongs to the footer");

            String out = new Automation(gui).command("click " + rows[3].id());
            gui.frame(W, H, NO_TEXT);

            assertTrue(out.startsWith("err"), out);
            assertTrue(out.contains("scrolled out of view"), "and it says which kind of unreachable: " + out);
            assertEquals(0, rowClicks.get(), "nothing was clicked");
            assertEquals(0, footerClicks.get(), "least of all the node that happens to be drawn there");
        }
    }

    /**
     * The other half of the same answer: scrolled out is a state the framework can do something about, so a
     * target that has a landmark is <em>scrolled to</em> and then clicked. Refusing every clipped node would
     * have made this class the thing that decides what a scroller is, which is the enumeration
     * {@link Automation#go} exists so that nobody here ever starts.
     */
    @Test
    void aClippedTargetWithALandmarkIsScrolledToRatherThanRefused() {
        try (Gui gui = new Gui(sibarum.atchung.Atchung.create())) {
            AtomicInteger clicks = new AtomicInteger();
            Node[] rows = new Node[6];
            for (int i = 0; i < rows.length; i++) {
                rows[i] = gui.box().width(Length.FILL).height(Length.dp(40));
            }
            Node list = gui.column().width(Length.dp(200)).height(Length.dp(100)).scroll(false, true)
                    .children(rows);
            gui.landmark("last", rows[5]);
            gui.root().children(list);
            gui.onClick(rows[5], clicks::incrementAndGet);
            gui.frame(W, H, NO_TEXT);
            assertTrue(rows[5].layout().clippedAway(), "the premise: the last row is nowhere on screen");

            String out = drivenWithFramesRunning(gui, driver -> driver.command("click last"));
            assertTrue(out.startsWith("ok"), out);
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (clicks.get() == 0 && System.nanoTime() < deadline) {
                gui.frame(W, H, NO_TEXT);
                Thread.onSpinWait();
            }
            assertEquals(1, clicks.get(), "the row was scrolled into view and then clicked");
        }
    }

    /** A row half out of the list is still clickable — on the half of it that is there, not on its centre. */
    @Test
    void aPartlyClippedRowIsClickedWhereItCanStillBeSeen() {
        try (Gui gui = deterministic()) {
            AtomicInteger clicks = new AtomicInteger();
            Node[] rows = new Node[4];
            for (int i = 0; i < rows.length; i++) {
                rows[i] = gui.box().width(Length.FILL).height(Length.dp(40));
            }
            Node list = gui.column().width(Length.dp(200)).height(Length.dp(100)).scroll(false, true)
                    .children(rows);
            gui.root().children(list);
            gui.onClick(rows[2], clicks::incrementAndGet);      // laid out at y=80..120, viewport ends at 100
            gui.frame(W, H, NO_TEXT);

            assertTrue(rows[2].layout().clipped(), "the premise: half of this row is past the bottom edge");
            String out = new Automation(gui).command("click " + rows[2].id());
            gui.frame(W, H, NO_TEXT);

            assertTrue(out.startsWith("ok"), out);
            assertEquals(1, clicks.get(), "the click landed on the part of the row that is on screen");
        }
    }

    @Test
    void anUnknownCommandAnswersInsteadOfDying() {
        try (Gui gui = deterministic()) {
            Automation driver = new Automation(gui);
            assertTrue(driver.command("wibble").startsWith("err"));
            assertTrue(driver.command("click 999999").startsWith("err"), "a ref that is not there is an answer");
            assertTrue(driver.command("").isEmpty());
            // A driver that dies on a bad command loses the session it was in the middle of investigating.
            assertTrue(driver.command("help").startsWith("ok"));
        }
    }
}
