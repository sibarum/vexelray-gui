package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.WindowInstrument;
import dev.vexelray.gui.core.input.MenuItem;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.draw.Picture;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The framework's own tools in the title bar (docs/reference/automation.md §7).
 *
 * <p>What is worth pinning here is not that a button draws. It is the properties that make the tools a framework
 * facility rather than a convenience: they are <b>absent</b> unless asked for; each is a <b>section of the app menu</b>
 * the lockup opens, after the About line; one stands at the right only while its <b>badge</b> says so, <b>between</b>
 * the caption and the window controls rather than displacing them; and what it does reaches the <b>window</b>, so a bar
 * in a popup commands the popup.
 */
class InstrumentStripTest {

    private static final float BUTTON_W = 46f;
    private static final float VIEW_W = 800f;

    /** A {@link WindowControls} that records what it was asked to do. */
    private static final class Recorder implements WindowControls {
        private final List<String> calls = new ArrayList<>();

        @Override public void minimize() {
            calls.add("minimize");
        }

        @Override public void toggleMaximize() {
            calls.add("toggleMaximize");
        }

        @Override public boolean maximized() {
            return false;
        }

        @Override public void close() {
            calls.add("close");
        }

        @Override public void capture(String path) {
            calls.add("capture " + path);
        }
    }

    /** A tool whose line says {@code role} and records it when chosen. */
    private static WindowInstrument marker(String role, List<String> fired) {
        return new WindowInstrument(role, role, (box, ink) -> Picture.EMPTY, controls -> fired.add(role));
    }

    /** The centre of the nth 46dp button counting from the right edge of the bar. */
    private static float fromRight(int n) {
        return VIEW_W - (n + 0.5f) * BUTTON_W;
    }

    /** A bar wearing a wordmark, so its lockup is there to click, in the tree and laid out. */
    private static TitleBar bar(HeadlessGui h, WindowControls controls) {
        TitleBar bar = new TitleBar(h.gui, controls, "app").wordmark("App");
        h.gui.root().children(bar.node());
        h.frame();
        return bar;
    }

    /** Click the middle of the node {@code landmark} names, laid out a frame ago. */
    private static void click(HeadlessGui h, String landmark) {
        dev.vexelray.gui.core.Node node = h.gui.landmarkNode(landmark).orElseThrow(() ->
                new AssertionError("no " + landmark));
        Rect r = h.gui.layoutSnapshot().node(h.retained(node).id).rect();
        h.click(r.x() + r.w() / 2f, r.y() + r.h() / 2f);
        h.frame();
    }

    private static List<String> labels(ContextMenu menu) {
        return menu.items().stream().map(i -> i.separator() ? "-" : i.label()).toList();
    }

    @Test
    void aBarHasNoInstrumentsUnlessAsked() {
        try (HeadlessGui h = new HeadlessGui()) {
            TitleBar bar = new TitleBar(h.gui, new Recorder(), "app");
            h.gui.root().children(bar.node());
            h.frame();

            // A shipped application must not find itself carrying tools it never asked for.
            assertFalse(h.gui.semanticSnapshot().nodes().values().stream()
                            .anyMatch(n -> n.role().startsWith("instrument-")),
                    "no instrument is present until the application opts in");
            assertTrue(bar.instruments().isEmpty());
        }
    }

    @Test
    void eachToolIsALineOfTheAppMenuAfterAbout() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<String> fired = new ArrayList<>();
            ContextMenu menu = new ContextMenu(h.gui);
            TitleBar bar = bar(h, new Recorder())
                    .instruments(List.of(marker("One", fired), marker("Two", fired)));
            bar.onMark("About App", () -> fired.add("about"));
            h.frame();

            assertFalse(h.gui.semanticSnapshot().nodes().values().stream()
                            .anyMatch(n -> n.role().equals("One") || n.role().equals("Two")),
                    "a tool with no badge is no button at the right");
            click(h, TitleBar.MARK);
            assertTrue(menu.isOpen(), "the lockup's click opens the app menu, and is not taken for a click away");
            assertEquals(List.of("About App", "-", "One", "-", "Two"), labels(menu));
            assertTrue(fired.isEmpty(), "opening the menu runs nothing");

            click(h, "menu.two");
            assertEquals(List.of("Two"), fired);
            assertFalse(menu.isOpen(), "choosing a line closes the menu");
        }
    }

    /**
     * The menu is in place order, whatever order the hands that assembled the list added in: what is about the
     * application joins About's group, the tools follow, the run's own line is last. The shell adds the key bindings
     * after the application's screenshot, and the automation module adds its line after both.
     */
    @Test
    void theMenuIsInPlaceOrderNotTheOrderAdded() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<String> fired = new ArrayList<>();
            ContextMenu menu = new ContextMenu(h.gui);
            TitleBar bar = bar(h, new Recorder()).instruments(List.of(
                    WindowInstrument.automation("debug", m -> m.item("Restart in debug mode", () -> { })),
                    marker("Screenshot", fired),
                    WindowInstrument.keymap("Key bindings…", c -> fired.add("keys"))));
            bar.onMark("About App", () -> fired.add("about"));
            h.frame();

            click(h, TitleBar.MARK);
            assertEquals(List.of("About App", "Key bindings…", "-", "Screenshot", "-", "Restart in debug mode"),
                    labels(menu));
        }
    }

    @Test
    void theWindowControlsKeepTheCorner() {
        try (HeadlessGui h = new HeadlessGui()) {
            Recorder rec = new Recorder();
            List<String> fired = new ArrayList<>();
            bar(h, rec).instruments(List.of(marker("One", fired).badge(() -> true)));
            h.frame();

            // Close, maximize and minimize keep the right edge: a badge must not push the window controls off the
            // corner the user aims at without looking.
            h.click(fromRight(0), 16f);
            h.click(fromRight(3), 16f);
            assertEquals(List.of("close"), rec.calls);
            assertEquals(List.of("One"), fired, "the fourth slot from the right is the badge");
        }
    }

    @Test
    void aLineCommandsTheWindow() {
        try (HeadlessGui h = new HeadlessGui()) {
            new ContextMenu(h.gui);
            Recorder rec = new Recorder();
            bar(h, rec).instruments(List.of(WindowInstrument.screenshot(() -> "shot.png")));
            h.frame();

            click(h, TitleBar.MARK);
            click(h, "menu.screenshot-this-window");
            assertEquals(List.of("capture shot.png"), rec.calls,
                    "the instrument asks the window it is in, not the application");
        }
    }

    @Test
    void aToolsMenuIsItsSectionAndAScriptCanReachIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<String> fired = new ArrayList<>();
            ContextMenu menu = new ContextMenu(h.gui);
            bar(h, new Recorder()).instruments(List.of(WindowInstrument.automation("debug", m -> {
                m.heading("Debug");
                m.item("Restart in debug mode", () -> fired.add("restart"));
            })));
            h.frame();

            click(h, TitleBar.MARK);
            assertEquals(List.of("Debug", "Restart in debug mode"), labels(menu));
            assertTrue(menu.items().get(0).heading(), "a heading names the group and runs nothing");
            assertFalse(h.gui.landmarkNode("instrument-automation").isPresent(),
                    "a normal run's plug is a line, not a button");
            click(h, "menu.restart-in-debug-mode");
            assertEquals(List.of("restart"), fired);
        }
    }

    @Test
    void aBadgeStandsAtTheRightOnlyWhileItSaysSo() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<String> fired = new ArrayList<>();
            List<Runnable> watchers = new ArrayList<>();
            AtomicBoolean recording = new AtomicBoolean();
            WindowInstrument tool = new WindowInstrument("instrument-camera", "Camera", (box, ink) -> Picture.EMPTY,
                    controls -> fired.add("camera"), changed -> {
                        watchers.add(changed);
                        return () -> watchers.remove(changed);
                    }).badge(recording::get);
            bar(h, new Recorder()).instruments(List.of(tool));
            h.frame();

            h.click(fromRight(3), 16f);
            assertTrue(fired.isEmpty(), "nothing stands there while nothing is going on");

            recording.set(true);
            watchers.forEach(Runnable::run);   // the recording started, say
            h.frame();
            h.click(fromRight(3), 16f);
            assertEquals(List.of("camera"), fired, "and while it is, the badge does what its line does");
            assertTrue(h.gui.landmarkNode("instrument-camera").isPresent(), "reached by its role");
        }
    }

    @Test
    void theToolsAreReplacedRatherThanAppended() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<String> fired = new ArrayList<>();
            ContextMenu menu = new ContextMenu(h.gui);
            TitleBar bar = bar(h, new Recorder())
                    .instruments(List.of(marker("instrument-one", fired).badge(() -> true)));
            bar.instruments(List.of(marker("instrument-two", fired).badge(() -> true)));
            h.frame();

            long count = h.gui.semanticSnapshot().nodes().values().stream()
                    .filter(n -> n.role().startsWith("instrument-"))
                    .count();
            assertEquals(1L, count, "which tools this window has needs one answer, not a history");
            click(h, TitleBar.MARK);
            assertEquals(List.of("instrument-two"), labels(menu));
        }
    }

    @Test
    void aBarInAnyWindowGetsAWorkingScreenshot() {
        try (HeadlessGui h = new HeadlessGui()) {
            new ContextMenu(h.gui);
            // A bar built before its window, as every bar but the main one is.
            TitleBar bar = bar(h, null).instruments(List.of(WindowInstrument.screenshot(() -> "popup.png")));
            h.frame();

            // What the host does when it opens the window. The bar must take these rather than mint its own
            // from the NativeWindow: a native window cannot photograph itself, so a bar that built its own got
            // a working minimize, maximize and close and a screenshot that silently did nothing — on every
            // window except the main one.
            Recorder rec = new Recorder();
            var spec = bar.commands(dev.vexelray.gui.core.app.WindowSpec.of(
                    dev.vexelray.os.WindowConfig.of("popup", 400, 300), h.gui));
            spec.onControls().accept(rec);

            click(h, TitleBar.MARK);
            click(h, "menu.screenshot-this-window");
            assertEquals(List.of("capture popup.png"), rec.calls,
                    "the line has to reach the window the host supplied, not a no-op");
        }
    }

    @Test
    void aBadgesMarkIsReauthoredForTheBoxItGot() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<Rect> authored = new ArrayList<>();
            WindowInstrument tool = new WindowInstrument("instrument-mark", "", (box, ink) -> {
                authored.add(box);
                return Picture.EMPTY;
            }, controls -> { }).badge(() -> true);

            bar(h, new Recorder()).instruments(List.of(tool));
            h.frame();
            h.frame();

            // A Picture resolves no units of its own, so a mark authored against anything but the box the layout
            // settled on is drawn at the wrong size the moment the window is zoomed.
            assertFalse(authored.isEmpty(), "the mark has to be authored against a real box");
            Rect box = authored.get(authored.size() - 1);
            assertTrue(box.w() > 0f && box.h() > 0f, "and a laid-out one: " + box);
        }
    }

    @Test
    void aLiveMarkIsReauthoredWhenWhatItShowsChangesAndLetGoWhenReplaced() {
        try (HeadlessGui h = new HeadlessGui()) {
            List<Rect> authored = new ArrayList<>();
            List<Runnable> watchers = new ArrayList<>();
            WindowInstrument tool = new WindowInstrument("instrument-live", "", (box, ink) -> {
                authored.add(box);
                return Picture.EMPTY;
            }, controls -> { }, changed -> {
                watchers.add(changed);
                return () -> watchers.remove(changed);
            }).badge(() -> true);

            TitleBar bar = bar(h, new Recorder()).instruments(List.of(tool));
            h.frame();
            int laidOut = authored.size();
            assertEquals(1, watchers.size(), "the bar watches a live instrument");

            watchers.get(0).run();      // the sockets' lamps changed, say
            int states = dev.vexelray.gui.core.input.InteractionState.values().length;
            assertEquals(laidOut + states, authored.size(), "and re-authors its mark per state, same box");
            assertEquals(authored.get(laidOut - 1), authored.get(laidOut));

            bar.instruments(List.of());
            assertTrue(watchers.isEmpty(), "tools taken down stop watching");
        }
    }

    @Test
    void aCurrentLineIsAMarkedCopy() {
        MenuItem stop = MenuItem.of("Stop recording", () -> { }).current(true);
        assertTrue(stop.current());
        assertTrue(stop.enabled(), "current is how it is drawn, not whether it can be chosen");
        assertFalse(MenuItem.heading("Capture").enabled(), "a heading is never chosen");
    }
}
