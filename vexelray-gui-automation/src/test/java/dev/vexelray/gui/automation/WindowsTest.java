package dev.vexelray.gui.automation;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.app.WindowView;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One socket drives an application, not a window (docs/plans/todo.md §6.9).
 *
 * <p>Two windows that both have a landmark called {@code home}, because that is the case an address exists for: the
 * name alone does not say which, and {@code viewport/home} does. What is pinned is that a command naming its window
 * reaches that window's tree and no other, that the verbs without a target reach the chosen window — tree and shot
 * both — and that a window reopened under the same tree is photographed through its new controls, not its old ones.
 */
class WindowsTest {

    private static final float W = 800f;
    private static final float H = 600f;

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
            return new float[text == null ? 1 : text.length() + 1];
        }
    };

    /** A window whose photograph is a file saying which window took it. */
    private record Camera(String who) implements WindowControls {
        @Override
        public void minimize() {
        }

        @Override
        public void toggleMaximize() {
        }

        @Override
        public boolean maximized() {
            return false;
        }

        @Override
        public void close() {
        }

        @Override
        public void capture(String path) {
            try {
                Files.writeString(Path.of(path), who);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public int width() {
            return (int) W;
        }

        @Override
        public int height() {
            return (int) H;
        }
    }

    /** A tree registered under {@code key}, as {@code GuiApp} registers it, with one button landmarked home. */
    private static Gui window(String key, String label, AtomicInteger clicks) {
        Gui gui = new Gui(sibarum.atchung.Atchung.create(), Runnable::run).windowKey(key);
        Node home = gui.box().role("button").size(Length.dp(100), Length.dp(40)).children(gui.text(label));
        gui.landmark("home", home);
        gui.onClick(home, clicks::incrementAndGet);
        gui.root().children(home);
        gui.frame(W, H, NO_TEXT);
        return gui;
    }

    @Test
    void oneSocketTreesShootsAndClicksInANamedSecondWindow(@TempDir Path dir) throws IOException {
        AtomicInteger mainClicks = new AtomicInteger();
        AtomicInteger viewClicks = new AtomicInteger();
        try (Gui main = window("main", "Main home", mainClicks);
             Gui view = window("viewport", "Viewport home", viewClicks)) {
            Windows windows = new Windows(() -> List.of(
                    new WindowView("main", main, new Camera("main"), true),
                    new WindowView("viewport", view, new Camera("viewport"), false)));

            // The address carries the window: the click lands in the viewport, though the main window is chosen.
            assertTrue(windows.command("click viewport/home").startsWith("ok"));
            view.frame(W, H, NO_TEXT);
            main.frame(W, H, NO_TEXT);
            assertEquals(1, viewClicks.get(), "the click went to the window the address names");
            assertEquals(0, mainClicks.get(), "and not to the chosen window's landmark of the same name");
            assertEquals("ok main", windows.command("window"), "a command naming its window does not move the choice");

            // The verbs with no target go to the chosen window.
            assertEquals("ok viewport", windows.command("window viewport"));
            String tree = windows.command("tree");
            assertTrue(tree.contains("\"Viewport home\"") && !tree.contains("\"Main home\""), tree);
            Path shot = dir.resolve("shot.png");
            assertTrue(windows.command("shot " + shot).startsWith("ok"));
            assertEquals("viewport", Files.readString(shot), "shot photographs the chosen window");

            // And a bare landmark means the chosen window's.
            assertTrue(windows.command("click home").startsWith("ok"));
            view.frame(W, H, NO_TEXT);
            assertEquals(2, viewClicks.get());
            assertEquals(0, mainClicks.get());
        }
    }

    @Test
    void aWindowReopenedUnderTheSameTreeIsPhotographedThroughItsNewControls(@TempDir Path dir) throws IOException {
        try (Gui main = window("main", "Main home", new AtomicInteger());
             Gui view = window("viewport", "Viewport home", new AtomicInteger())) {
            List<WindowView> open = new CopyOnWriteArrayList<>(List.of(
                    new WindowView("main", main, new Camera("main"), true),
                    new WindowView("viewport", view, new Camera("first"), false)));
            Windows windows = new Windows(() -> List.copyOf(open));
            windows.command("window viewport");
            Path shot = dir.resolve("shot.png");
            assertTrue(windows.command("shot " + shot).startsWith("ok"));
            assertEquals("first", Files.readString(shot));

            // Closed and reopened between two commands: the same tree, a fresh set of controls. The driver made for
            // the tree is kept, and has to ask again rather than hold the set it was first handed.
            open.set(1, new WindowView("viewport", view, new Camera("reopened"), false));
            assertTrue(windows.command("shot " + shot).startsWith("ok"));
            assertEquals("reopened", Files.readString(shot));
        }
    }

    @Test
    void aGestureCannotStartInOneWindowAndEndInAnother() {
        try (Gui main = window("main", "Main home", new AtomicInteger());
             Gui view = window("viewport", "Viewport home", new AtomicInteger())) {
            Windows windows = new Windows(() -> List.of(
                    new WindowView("main", main, WindowControls.NONE, true),
                    new WindowView("viewport", view, WindowControls.NONE, false)));
            String out = windows.command("drag main/home viewport/home");
            assertTrue(out.startsWith("err") && out.contains("another"), out);
        }
    }

    @Test
    void aWindowVerbWaitsForTheFirstWindowButNotForOneThatClosed() throws Exception {
        try (Gui main = window("main", "Main home", new AtomicInteger())) {
            List<WindowView> open = new CopyOnWriteArrayList<>();
            Windows windows = new Windows(() -> List.copyOf(open));
            // The socket is up before the window, as it is in every application: the port line comes first.
            Thread opener = new Thread(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    return;
                }
                open.add(new WindowView("main", main, WindowControls.NONE, true));
            });
            opener.start();
            String first = windows.session().command("windows");
            opener.join();
            assertTrue(first.startsWith("ok 1"), first);

            // Once a window has been seen, none open is the application's answer, given at once.
            open.clear();
            long started = System.nanoTime();
            String none = windows.session().command("windows");
            assertTrue(none.startsWith("err"), none);
            assertTrue(System.nanoTime() - started < Windows.FIRST_WINDOW_TIMEOUT_MS * 1_000_000L / 10, "waited");
        }
    }

    @Test
    void aDriverRefusesAnAddressInAnotherWindowRatherThanLookingItUpHere() {
        AtomicInteger clicks = new AtomicInteger();
        try (Gui main = window("main", "Main home", clicks)) {
            String out = new Automation(main).command("click viewport/home");
            assertTrue(out.startsWith("err") && out.contains("'viewport'"), out);
            main.frame(W, H, NO_TEXT);
            assertEquals(0, clicks.get(), "the same landmark here is not the one the address meant");
            assertFalse(new Automation(main).command("click main/home").startsWith("err"),
                    "its own window's name is just a longer way to say the landmark");
        }
    }
}
