package dev.vexelray.gui.core.app;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.os.WorkArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Window placement across restarts. The interesting half of this is not "the numbers come back" — it is what
 * happens when they should <em>not</em> come back unchanged, and those are the cases here: a desk that has
 * changed shape since, a maximized window whose reported bounds are the screen's, and a size that would restore
 * to a sliver.
 *
 * <p>The desktop is a fixture rather than the platform. That is the whole reason {@link WindowMemory.Desktop}
 * exists: the clamp is the rule that stops a window stranding where no monitor is, so it is the rule most worth
 * testing, and it cannot be tested against whatever monitors happen to be plugged into the build machine.
 */
class WindowMemoryTest {

    /**
     * A single 1024×768 monitor at the origin. It answers every point, including points nowhere near it, which
     * is what the platform does: {@code GuiApp.workArea} finds the monitor a position was on <em>or the
     * nearest surviving one</em>, so a lone screen is the answer to everything.
     */
    private static final WindowMemory.Desktop ONE_SCREEN = (x, y) -> Optional.of(new WorkArea(0, 0, 1024, 768));

    /** A desk nobody can describe — what a platform with no window API answers. */
    private static final WindowMemory.Desktop UNKNOWN = (x, y) -> Optional.empty();

    @Test
    void withNothingSavedAWindowIsItsDefaultSizeAndNowhereInParticular(@TempDir Path dir) {
        WindowConfig config = memory(dir, ONE_SCREEN).config("main", "Calculator", 420, 632);
        assertEquals(420, config.width());
        assertEquals(632, config.height());
        assertEquals(WindowConfig.UNPOSITIONED, config.x(),
                "with nothing saved, where to put it is the window manager's business");
    }

    @Test
    void aWindowComesBackWhereItWasLeft(@TempDir Path dir) {
        Path file = dir.resolve("settings.properties");
        WindowMemory first = new WindowMemory(Settings.at(file), ONE_SCREEN);
        first.watch("main", window(120, 90, 500, 400));
        first.save();

        WindowConfig config = new WindowMemory(Settings.at(file), ONE_SCREEN)
                .config("main", "Calculator", 420, 632);
        assertEquals(120, config.x());
        assertEquals(90, config.y());
        assertEquals(500, config.width());
        assertEquals(400, config.height());
    }

    /**
     * The case the whole clamp exists for: bounds saved on a desk with a second monitor, restored onto one
     * without it. Nothing about the saved rectangle is wrong — it is just no longer a place.
     */
    @Test
    void aWindowSavedOnAMonitorThatIsGoneIsBroughtBackOnScreen(@TempDir Path dir) {
        Path file = dir.resolve("settings.properties");
        WindowMemory before = new WindowMemory(Settings.at(file), (x, y) ->
                Optional.of(new WorkArea(0, 0, 3000, 768)));      // two monitors side by side
        before.watch("main", window(2100, 40, 500, 400));
        before.save();

        WindowConfig config = new WindowMemory(Settings.at(file), ONE_SCREEN)
                .config("main", "Calculator", 420, 632);
        assertTrue(config.x() != WindowConfig.UNPOSITIONED);
        assertTrue(config.x() + config.width() <= 1024,
                "the whole window must be on the screen it lands on, got x=" + config.x() + " w=" + config.width());
        assertEquals(40, config.y(), "and it should move no further than it has to");
    }

    /**
     * A desktop nobody can describe is not a licence to place a window anywhere. The size is honoured, the
     * position is not — an unclamped position is the one way this feature can strand a window off-screen.
     */
    @Test
    void anUndescribableDesktopKeepsTheSizeAndDropsThePlace(@TempDir Path dir) {
        Path file = dir.resolve("settings.properties");
        WindowMemory before = new WindowMemory(Settings.at(file), ONE_SCREEN);
        before.watch("main", window(120, 90, 500, 400));
        before.save();

        WindowConfig config = new WindowMemory(Settings.at(file), UNKNOWN)
                .config("main", "Calculator", 420, 632);
        assertEquals(500, config.width());
        assertEquals(400, config.height());
        assertEquals(WindowConfig.UNPOSITIONED, config.x());
    }

    @Test
    void aSavedSliverIsNotRestoredAsASliver(@TempDir Path dir) {
        Path file = dir.resolve("settings.properties");
        Settings.at(file).putInt("window.main.width", 12).putInt("window.main.height", 8).save();

        WindowConfig config = new WindowMemory(Settings.at(file), UNKNOWN)
                .config("main", "Calculator", 420, 632);
        assertTrue(config.width() >= 320 && config.height() >= 240,
                "a window too small to use is not a window: " + config.width() + "x" + config.height());
    }

    /**
     * Maximized is a state, not a rectangle. While the window is maximized its bounds are the screen's, so
     * recording them would destroy the size to restore down to — which is the whole of what un-maximizing after
     * a restart needs.
     */
    @Test
    void maximizingDoesNotEatTheBoundsToRestoreDownTo(@TempDir Path dir) {
        Path file = dir.resolve("settings.properties");
        WindowMemory memory = new WindowMemory(Settings.at(file), ONE_SCREEN);
        Stub window = window(120, 90, 500, 400);
        memory.watch("main", window);

        window.x = 0;
        window.y = 0;
        window.width = 1024;
        window.height = 768;
        window.maximized = true;
        memory.poll();
        memory.save();

        WindowMemory back = new WindowMemory(Settings.at(file), ONE_SCREEN);
        assertTrue(back.maximized("main"), "the state is remembered");
        WindowConfig config = back.config("main", "Calculator", 420, 632);
        assertEquals(500, config.width(), "and the rectangle under it survives untouched");
        assertEquals(400, config.height());
        assertEquals(120, config.x());
    }

    /** A minimized window reports nothing worth having, and must not be recorded as being nowhere. */
    @Test
    void aMinimizedWindowIsSkippedRatherThanRecorded(@TempDir Path dir) {
        Path file = dir.resolve("settings.properties");
        WindowMemory memory = new WindowMemory(Settings.at(file), ONE_SCREEN);
        Stub window = window(120, 90, 500, 400);
        memory.watch("main", window);

        window.minimized = true;
        window.x = -32000;
        window.y = -32000;
        memory.poll();
        memory.save();

        assertEquals(120, new WindowMemory(Settings.at(file), ONE_SCREEN)
                .config("main", "Calculator", 420, 632).x());
    }

    /**
     * Zoom is remembered for the same reason a size is: a user who scaled a window up did it to read the thing,
     * and losing that on quit is the same loss. Restored before the first frame, because {@code em} resolves
     * against it.
     */
    @Test
    void zoomIsRestoredBeforeTheFirstFrameAndFollowedAfterIt(@TempDir Path dir) {
        Path file = dir.resolve("settings.properties");
        try (Gui gui = new Gui()) {
            WindowMemory memory = new WindowMemory(Settings.at(file), ONE_SCREEN);
            memory.watch("main", window(0, 0, 500, 400), gui);
            gui.zoom(1.75f);
            memory.poll();
            memory.save();
        }
        try (Gui fresh = new Gui()) {
            assertEquals(1f, fresh.zoom().value(), 1e-4f, "a new tree starts unscaled");
            new WindowMemory(Settings.at(file), ONE_SCREEN).watch("main", window(0, 0, 500, 400), fresh);
            assertEquals(1.75f, fresh.zoom().value(), 1e-4f, "and watching it puts the zoom back");
        }
    }

    /** A window with no tree passed is a window whose zoom is nobody's business. */
    @Test
    void aWindowWatchedWithoutATreeRecordsNoZoom(@TempDir Path dir) {
        Path file = dir.resolve("settings.properties");
        WindowMemory memory = new WindowMemory(Settings.at(file), ONE_SCREEN);
        memory.watch("main", window(0, 0, 500, 400));
        memory.save();
        assertFalse(Settings.at(file).has("window.main.zoom"));
    }

    /**
     * Open-ness is polled from the live window rather than written when it opens and closes, so that quitting
     * with a tool window up is distinguishable from closing that window by hand. See {@link WindowMemory#open}.
     */
    @Test
    void whetherAWindowWasOpenSurvivesTheRestart(@TempDir Path dir) {
        Path file = dir.resolve("settings.properties");
        WindowMemory memory = new WindowMemory(Settings.at(file), ONE_SCREEN);
        assertFalse(memory.wasOpen("history"), "nothing was open before there was a settings file");
        memory.open("history", true);
        memory.save();
        assertTrue(new WindowMemory(Settings.at(file), ONE_SCREEN).wasOpen("history"));
    }

    /** A drag is one write, not four hundred: nothing reaches the disk until it is asked for. */
    @Test
    void movingAWindowDoesNotTouchTheDiskUntilItSettles(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("settings.properties");
        WindowMemory memory = new WindowMemory(Settings.at(file), ONE_SCREEN);
        Stub window = window(120, 90, 500, 400);
        memory.watch("main", window);
        for (int i = 0; i < 200; i++) {
            window.x = 120 + i;
            memory.poll();
        }
        assertFalse(Files.exists(file), "the debounce has not expired, so nothing has been written");
        memory.save();
        assertEquals(319, new WindowMemory(Settings.at(file), ONE_SCREEN)
                .config("main", "Calculator", 420, 632).x(), "and the last position is the one that lands");
    }

    private static WindowMemory memory(Path dir, WindowMemory.Desktop desktop) {
        return new WindowMemory(Settings.at(dir.resolve("settings.properties")), desktop);
    }

    @Test
    void aPendingWriteIsADeadlineTheFrameLoopCanBeToldAbout(@TempDir Path dir) {
        WindowMemory memory = new WindowMemory(Settings.at(dir.resolve("settings.properties")), ONE_SCREEN);
        Stub w = window(120, 90, 500, 400);
        memory.watch("main", w);
        memory.save();

        assertEquals(Long.MAX_VALUE, memory.nanosUntilSettle(),
                "nothing pending: a loop with nothing else to do may park indefinitely");

        // Move it, the way a drag does, and poll the way the frame hook does.
        w.x = 300;
        memory.poll();

        long due = memory.nanosUntilSettle();
        assertTrue(due > 0 && due <= 700_000_000L,
                "a debounced write is a frame owed at a stated time, not whenever something else "
                        + "happens to wake the loop: " + due);
    }

    private static Stub window(int x, int y, int width, int height) {
        Stub s = new Stub();
        s.x = x;
        s.y = y;
        s.width = width;
        s.height = height;
        return s;
    }

    /**
     * A window that is only its placement. {@link NativeWindow} defaults everything this needs to override, so
     * the eight methods below are the whole of what a non-window has to answer.
     */
    private static final class Stub implements NativeWindow {
        int x;
        int y;
        int width;
        int height;
        boolean maximized;
        boolean minimized;

        @Override
        public int screenX() {
            return x;
        }

        @Override
        public int screenY() {
            return y;
        }

        @Override
        public int outerWidth() {
            return width;
        }

        @Override
        public int outerHeight() {
            return height;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public boolean isMaximized() {
            return maximized;
        }

        @Override
        public boolean isMinimized() {
            return minimized;
        }

        @Override
        public void setBounds(int nx, int ny, int nw, int nh) {
            x = nx;
            y = ny;
            width = nw;
            height = nh;
        }

        @Override
        public boolean pumpEvents() {
            return true;
        }

        @Override
        public void show() {
        }

        @Override
        public boolean isKeyDown(dev.vexelray.os.Key key) {
            return false;
        }

        @Override
        public long createVulkanSurface(long vkInstance, MemorySegment vkGetInstanceProcAddr) {
            return 0L;
        }

        @Override
        public long osHandle() {
            return 0L;
        }

        @Override
        public void close() {
        }
    }
}
