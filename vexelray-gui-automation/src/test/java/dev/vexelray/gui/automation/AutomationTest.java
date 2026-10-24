package dev.vexelray.gui.automation;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command surface an agent drives (docs/reference/automation.md §5).
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

    /**
     * A scroller is listed, named or not, and says which way it scrolls: an unnamed body scrolling inside a scrolling
     * root was two scrollbars that the tree had no line for (docs/reference/scrolling.md §3).
     */
    @Test
    void theTreeListsEveryScrollerAndItsAxes() {
        try (Gui gui = deterministic()) {
            Node body = gui.column().size(Length.FILL, Length.dp(300))
                    .children(gui.box().size(Length.dp(100), Length.dp(1000)));
            Node plain = gui.box().size(Length.FILL, Length.dp(50));
            gui.root().children(body, plain);
            gui.frame(W, H, NO_TEXT);

            String tree = new Automation(gui).command("tree");
            assertTrue(tree.contains("\n" + body.id() + " box [0,0 "), "an unnamed scroller has a line: " + tree);
            assertTrue(tree.lines().anyMatch(l -> l.startsWith(body.id() + " box") && l.endsWith(" scrolls y")), tree);
            assertFalse(tree.contains("\n" + plain.id() + " "), "a plain unnamed box still has none: " + tree);
        }
    }

    /** Two scrollers along one axis, one filling the other, are two lines that both say so. */
    @Test
    void aScrollerInsideAScrollingRootIsTwoScrollingLines() {
        try (Gui gui = deterministic()) {
            Node body = gui.column().width(Length.FILL).height(Length.grow(1f))
                    .children(gui.box().size(Length.dp(700), Length.dp(1500)));
            gui.root().direction(dev.vexelray.gui.core.layout.LayoutEnums.Direction.COLUMN)
                    .children(gui.box().size(Length.FILL, Length.dp(32)), body);
            gui.minSize(Length.rem(40), Length.rem(50));   // 800 tall, under a 600 px window
            gui.frame(W, H, NO_TEXT);

            String tree = new Automation(gui).command("tree");
            assertTrue(tree.lines().anyMatch(l -> l.startsWith(gui.root().id() + " box") && l.endsWith(" scrolls y")), tree);
            assertTrue(tree.lines().anyMatch(l -> l.strip().startsWith(body.id() + " box") && l.endsWith(" scrolls y")), tree);
        }
    }

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
            // of that spot and report success (docs/reference/semantic-read-model.md §6).
            assertTrue(out.startsWith("err"), out);
            assertTrue(out.contains("not visible") && out.contains("landmark"),
                    "and it has to say what would have made it reachable: " + out);
        }
    }

    /** The middle of a gesture is what most often needs a photograph, and {@code drag} is over before one. */
    @Test
    void aDragHeldDownStaysOpenUntilLetGo() {
        try (Gui gui = deterministic()) {
            dev.vexelray.gui.core.drop.PayloadType<String> row = dev.vexelray.gui.core.drop.PayloadType.of("row");
            Node body = gui.box().size(Length.dp(200), Length.dp(200));
            gui.onDragSource(body, (x, y) -> dev.vexelray.gui.core.drop.Payload.of(row, "r"));
            gui.root().children(body);
            gui.frame(W, H, NO_TEXT);
            Automation driver = new Automation(gui);

            assertTrue(driver.command("down 20,20").startsWith("ok"));
            driver.command("move 20,90");
            // The press is stamped with the real clock, so the recogniser's hold is wall-clock time: frames are
            // stepped until it has passed, as a loop would step them while a hand held still.
            for (int i = 0; i < 100 && gui.dragSession() == null; i++) {
                gui.frame(W, H, NO_TEXT);
                java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
            }
            assertTrue(gui.dragSession() != null, "pressed and travelled: a drag, still in the hand");
            gui.frame(W, H, NO_TEXT);
            assertTrue(gui.dragSession() != null, "and still, a frame later — nothing let go of it");

            assertTrue(driver.command("up").startsWith("ok 20,90"));
            gui.frame(W, H, NO_TEXT);
            assertTrue(gui.dragSession() != null && gui.dragSession().latched(),
                    "let go: and a drag that is let go stays with the pointer, latched, until a click");
            driver.command("click 20,90");
            gui.frame(W, H, NO_TEXT);
            assertTrue(gui.dragSession() == null, "put down");
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

    // --- settle and the clock (automation-cli.md §5, V3) ----------------------------------------------------

    /**
     * The defect as it was found: nothing owed, a transition in flight, and settle answering at once. Here the
     * "transition" is a timeline that goes quiet 150 ms from now — which settle used to answer before, and must
     * now answer after.
     */
    @Test
    void settleWaitsForATransitionInFlightWhenNoFrameIsOwed() {
        try (Gui gui = deterministic()) {
            gui.root().children(gui.box().size(Length.dp(10), Length.dp(10)));
            gui.frame(W, H, NO_TEXT);
            assertFalse(gui.frameOwed(), "the precondition: the loop has nothing outstanding");

            long quietAt = System.nanoTime() + 150_000_000L;
            Automation driver = new Automation(gui, null, () -> System.nanoTime() >= quietAt);
            String out = driver.command("settle");

            assertTrue(out.startsWith("ok"), out);
            assertTrue(System.nanoTime() >= quietAt, "settle answered before the transition finished");
        }
    }

    /**
     * The flake as it was reported: {@code click} then {@code settle} answered {@code ok}, and the next read saw
     * the tree from before the click. The wake flag was all settle asked, and the frame clears it at its top —
     * so a settle landing while that frame dispatched the click, or while the click's handler ran on its worker,
     * was told nothing was owed. The handler here takes long enough that the old settle lost every time.
     */
    @Test
    void settleWaitsForTheClicksOwnHandlerAndTheFrameAfterIt() {
        try (Gui gui = new Gui(sibarum.atchung.Atchung.create())) {
            Node label = gui.text("before");
            Node button = gui.box().role("button").size(Length.dp(100), Length.dp(40));
            gui.landmark("status", label);
            gui.landmark("go", button);
            gui.onClick(button, () -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                label.text("after");
            });
            gui.root().children(gui.column().children(button, label));
            gui.frame(W, H, NO_TEXT);

            String out = drivenWithFramesRunning(gui, driver -> {
                String clicked = driver.command("click go");
                String settled = driver.command("settle");
                return clicked + " / " + settled + " / " + driver.command("find status");
            });

            assertTrue(out.contains("\"after\""), "settle answered before the click's handler had landed: " + out);
        }
    }

    @Test
    void aTreeThatHasNeverBeenFramedIsNotSettled() {
        try (Gui gui = new Gui(sibarum.atchung.Atchung.create())) {
            gui.root().children(gui.box().size(Length.dp(10), Length.dp(10)));
            // Before frame #1 there is no tree to read, and `ok v0` followed by `(no tree yet)` is the answer an
            // agent connecting at startup used to get (docs/plans/background-runs.md).
            assertTrue(gui.frameOwed(), "a tree is owed its first frame");
            gui.frame(W, H, NO_TEXT);
            assertFalse(gui.frameOwed());
        }
    }

    @Test
    void aTimelineThatNeverGoesQuietIsReportedRatherThanWaitedOut() {
        try (Gui gui = deterministic()) {
            gui.root().children(gui.box().size(Length.dp(10), Length.dp(10)));
            gui.frame(W, H, NO_TEXT);

            String out = new Automation(gui, null, () -> false, 100L).command("settle");

            assertTrue(out.startsWith("err the timeline did not go quiet within 100ms"), out);
            assertTrue(out.contains("await"), "the refusal names what to do instead: " + out);
        }
    }

    @Test
    void theRefusalNamesWhatTheClockSaysIsRunning() {
        try (Gui gui = deterministic()) {
            gui.root().children(gui.box().size(Length.dp(10), Length.dp(10)));
            gui.frame(W, H, NO_TEXT);
            // A clock answers from the tick after it was asked, so an answer read only at the start of the wait is
            // the moment the wait began. This one changes its answer once asked; the refusal has to carry the later.
            AtomicInteger asked = new AtomicInteger();
            Timeline clock = Timeline.of(() -> false, () -> asked.getAndIncrement() < 1
                    ? java.util.List.of("effect@frames: a crossfade still in flight")
                    : java.util.List.of("timeline: next entry at @3s (sprite sheet)"));

            String out = new Automation(gui, null, clock, 100L).command("settle");

            assertTrue(out.contains("(sprite sheet)"), "the loop is named, not the moment: " + out);
            assertFalse(out.contains("crossfade"), "a reason from the start of the wait is stale by its end: " + out);
        }
    }

    @Test
    void withNoClockSettleIsAboutTheFrameLoopAlone() {
        try (Gui gui = deterministic()) {
            gui.root().children(gui.box().size(Length.dp(10), Length.dp(10)));
            gui.frame(W, H, NO_TEXT);

            assertTrue(new Automation(gui).command("settle").startsWith("ok"));
        }
    }

    /**
     * A chord has to arrive as one: a shortcut claimed for Ctrl+Shift+W fires, and the bare W does not, which is
     * what a driver that sent the key without its modifiers would have produced instead — the bug this verb had.
     */
    @Test
    void aChordReachesTheShortcutClaimedForIt() {
        try (Gui gui = deterministic()) {
            Node field = gui.box().size(Length.dp(200), Length.dp(40));
            gui.root().children(field);
            gui.frame(W, H, NO_TEXT);
            gui.focus(field);
            AtomicInteger chord = new AtomicInteger();
            AtomicInteger bare = new AtomicInteger();
            AtomicInteger enter = new AtomicInteger();
            gui.claim(field, Shortcut.of(Key.W, Modifier.CONTROL, Modifier.SHIFT), ClaimScope.FOCUSED,
                    chord::incrementAndGet);
            gui.claim(field, Shortcut.of(Key.W), ClaimScope.FOCUSED, bare::incrementAndGet);
            gui.claim(field, Shortcut.of(Key.ENTER), ClaimScope.FOCUSED, enter::incrementAndGet);
            Automation driver = new Automation(gui);

            assertEquals("ok LEFT_CONTROL+LEFT_SHIFT+W", driver.command("key ctrl+Shift+w"));
            gui.frame(W, H, NO_TEXT);
            assertEquals(1, chord.get(), "the chord fires its claim");
            assertEquals(0, bare.get(), "and not the bare key's");

            assertEquals("ok W", driver.command("key W"));
            assertEquals("ok ENTER", driver.command("key ENTER"));
            gui.frame(W, H, NO_TEXT);
            assertEquals(1, bare.get(), "the modifiers came back up: a plain key afterwards is plain");
            assertEquals(1, enter.get());
            assertTrue(gui.modifiers().value().isEmpty(), "nothing is left held");

            assertEquals("ok LEFT_CONTROL+T", driver.command("key CONTROL+T"), "the Modifier enum's own name");
            assertTrue(driver.command("key HYPER+T").startsWith("err no modifier named 'HYPER'"));
            assertTrue(driver.command("key CTRL+").startsWith("err no key named ''"));
            assertTrue(driver.command("key CTRL+NOPE").startsWith("err no key named 'NOPE'"));
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

    @Test
    void aPointerRideCarriesTheDriversPointerAndLeavesTheRealOneAlone() {
        try (Gui gui = deterministic()) {
            java.util.List<String> osMoves = new java.util.ArrayList<>();
            dev.vexelray.gui.core.input.PointerWarp host = (x, y) -> osMoves.add(x + "," + y);
            gui.onPointerWarp(host);
            gui.root().children(gui.box().size(Length.dp(W), Length.dp(H)));
            gui.frame(W, H, NO_TEXT);

            Automation driver = new Automation(gui);
            assertTrue(driver.command("move 100,100").startsWith("ok"));
            gui.frame(W, H, NO_TEXT);

            // A cancelled drag flying home: the application carries the pointer it sees, which is the driver's.
            dev.vexelray.gui.core.input.PointerRide ride = gui.ridePointer();
            assertTrue(ride.to(104, 102), "the pointer is where the ride started it, so the step is accepted");
            ride.end();
            gui.frame(W, H, NO_TEXT);

            assertEquals(java.util.List.of(), osMoves, "the desk's mouse must not move under a scripted run");
            assertEquals("ok 104,102", driver.command("where"), "the driver's pointer is where the ride put it");

            // Letting go of the hand gives the host its rides back, for whoever takes over.
            driver.letGo();
            assertTrue(gui.pointerWarp() == host, "the host's warp is restored");
        }
    }

    /**
     * {@code await <landmark>} with no text answers once the landmark exists, and not before: the first-frame wait
     * every witness used to write as a loop over {@code find}.
     */
    @Test
    void awaitWithNoTextWaitsForTheLandmarkToExist() throws Exception {
        try (Gui gui = deterministic()) {
            gui.root().children(gui.box().size(Length.dp(10), Length.dp(10)));
            gui.frame(W, H, NO_TEXT);
            Automation driver = new Automation(gui);

            java.util.concurrent.CompletableFuture<String> waiting =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> driver.command("await later"));
            Thread.sleep(100);
            assertFalse(waiting.isDone(), "nothing is registered under 'later' yet: " + waiting.getNow(""));

            Node later = gui.box().role("button").size(Length.dp(100), Length.dp(40)).children(gui.text("Go"));
            gui.landmark("later", later);
            gui.root().children(later);
            gui.frame(W, H, NO_TEXT);

            String out = waiting.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(out.startsWith("ok "), out);
            assertTrue(out.endsWith(" @later"), "the reply names the landmark: " + out);
            String ref = out.substring(3, out.indexOf(' ', 3));
            assertTrue(driver.command("find later").contains(ref + " "), "and the ref is the one find gives: " + out);

            // Already there: answered at once, with no frame to wait for.
            assertEquals(out, driver.command("await later"));
            // The text form is unchanged beside it.
            assertTrue(driver.command("await later Go").startsWith("ok Go"));
            assertTrue(driver.command("await").startsWith("err await needs a landmark"));
        }
    }

    /**
     * A held modifier is held across commands: {@code keydown SHIFT}, then a plain {@code key A}, reaches the claim
     * for Shift+A — the shift-click case, done with a key so it needs no layout — and {@code keyup} ends it.
     */
    @Test
    void keydownHoldsAKeyAcrossCommandsUntilKeyup() {
        try (Gui gui = deterministic()) {
            Node field = gui.box().size(Length.dp(200), Length.dp(40));
            gui.root().children(field);
            gui.frame(W, H, NO_TEXT);
            gui.focus(field);
            AtomicInteger shifted = new AtomicInteger();
            AtomicInteger bare = new AtomicInteger();
            gui.claim(field, Shortcut.of(Key.A, Modifier.SHIFT), ClaimScope.FOCUSED, shifted::incrementAndGet);
            gui.claim(field, Shortcut.of(Key.A), ClaimScope.FOCUSED, bare::incrementAndGet);
            Automation driver = new Automation(gui);
            assertTrue(driver.drives("keydown shift") && driver.drives("keyup"), "both are the hand's");

            assertEquals("ok LEFT_SHIFT held: LEFT_SHIFT", driver.command("keydown shift"));
            gui.frame(W, H, NO_TEXT);
            assertEquals(java.util.Set.of(Modifier.SHIFT), gui.modifiers().value(), "the application sees it held");

            assertEquals("ok A", driver.command("key A"));
            gui.frame(W, H, NO_TEXT);
            assertEquals(1, shifted.get(), "a key pressed while shift is held is Shift+A");
            assertEquals(0, bare.get());
            assertEquals(java.util.Set.of(Modifier.SHIFT), gui.modifiers().value(),
                    "and key did not let go of what keydown holds");

            assertTrue(driver.command("keydown SHIFT").startsWith("err LEFT_SHIFT is already held"));
            assertTrue(driver.command("key SHIFT").startsWith("err LEFT_SHIFT is held by keydown"));
            assertTrue(driver.command("keyup CTRL").startsWith("err LEFT_CONTROL is not held; held: LEFT_SHIFT"));

            assertEquals("ok LEFT_SHIFT held: (none)", driver.command("keyup Shift"));
            gui.frame(W, H, NO_TEXT);
            assertTrue(gui.modifiers().value().isEmpty());
            assertEquals("ok A", driver.command("key A"));
            gui.frame(W, H, NO_TEXT);
            assertEquals(1, bare.get(), "released: a plain key afterwards is plain");

            assertEquals("ok nothing was held", driver.command("keyup"));
            assertTrue(driver.command("keydown HYPER+A").startsWith("err no modifier named 'HYPER'"));
        }
    }

    /**
     * Whatever keydown holds comes up when the hand is let go — {@code quit}, a dropped connection, {@code hand
     * release} — last pressed first, so a session that ends never leaves a key down for whoever sits at the desk.
     */
    @Test
    void lettingGoReleasesHeldKeys() {
        try (Gui gui = deterministic()) {
            gui.root().children(gui.box().size(Length.dp(10), Length.dp(10)));
            gui.frame(W, H, NO_TEXT);
            Automation driver = new Automation(gui);

            assertEquals("ok LEFT_CONTROL+LEFT_ALT held: LEFT_CONTROL+LEFT_ALT", driver.command("keydown ctrl+alt"));
            gui.frame(W, H, NO_TEXT);
            assertEquals(java.util.Set.of(Modifier.CONTROL, Modifier.ALT), gui.modifiers().value());

            driver.letGo();
            gui.frame(W, H, NO_TEXT);
            assertTrue(gui.modifiers().value().isEmpty(), "letting go released them");
            assertEquals("ok nothing was held", driver.command("keyup"));

            assertEquals("ok LEFT_SHIFT held: LEFT_SHIFT", driver.command("keydown SHIFT"));
            assertEquals("ok released LEFT_SHIFT", driver.command("keyup"), "keyup alone releases everything");
        }
    }

    /** A picture with nowhere to go is refused at once, naming the folder, rather than waited for and missed. */
    @Test
    void aShotIntoAFolderThatIsNotThereIsRefusedAtOnceAndSaysWhere(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        java.io.File into = tmp.resolve("absent/shot.png").toFile();
        String said = Automation.noFolderFor(into);
        assertTrue(said != null && said.startsWith("err no folder ") && said.contains(into.getParentFile().getAbsolutePath()),
                said);
        assertEquals(null, Automation.noFolderFor(tmp.resolve("shot.png").toFile()), "a folder that is there is fine");
        if (java.io.File.separatorChar == '\\') {
            String bash = Automation.noFolderFor(new java.io.File("/tmp/nowhere-" + System.nanoTime() + "/shot.png"));
            assertTrue(bash != null && bash.contains("Git Bash path"), "a Git Bash path is named as one: " + bash);
        }
    }
}
