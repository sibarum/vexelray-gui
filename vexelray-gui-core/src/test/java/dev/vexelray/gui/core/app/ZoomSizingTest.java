package dev.vexelray.gui.core.app;

import dev.vexelray.os.Key;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WorkArea;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A window follows its tree's zoom — unless it is at a bound — and comes back to the exact pixel. */
class ZoomSizingTest {

    /** A 1920 x 1040 work area at the origin. */
    private static final WindowMemory.Desktop DESK = (x, y) -> Optional.of(new WorkArea(0, 0, 1920, 1040));

    @Test
    void theClientAreaScalesWithTheZoomAndTheFrameDoesNot() {
        Fake w = new Fake(100, 100, 800, 600, 8, 31);
        ZoomSizing z = new ZoomSizing(DESK);
        z.follow(w, 1f);
        z.follow(w, 1.25f);
        assertEquals(792 * 5 / 4 + 8, w.width, "client 792 grows to 990; the 8px frame does not grow");
        assertEquals(Math.round(569 * 1.25f) + 31, w.height);
        assertEquals(100, w.x, "the top-left stays where it was");
    }

    @Test
    void theFirstZoomSeenIsNotAChange() {
        Fake w = new Fake(100, 100, 800, 600, 0, 0);
        ZoomSizing z = new ZoomSizing(DESK);
        z.follow(w, 1.5f);   // a zoom restored before the first frame: the saved size already accounts for it
        assertEquals(800, w.width);
    }

    @Test
    void tenStepsInAndTenOutReturnToTheExactPixel() {
        Fake w = new Fake(0, 0, 333, 217, 0, 0);
        ZoomSizing z = new ZoomSizing((x, y) -> Optional.empty());
        float zoom = 0.5f;
        z.follow(w, zoom);
        for (int i = 0; i < 10; i++) {
            z.follow(w, zoom *= 1.1f);
        }
        for (int i = 0; i < 10; i++) {
            z.follow(w, zoom /= 1.1f);
        }
        z.follow(w, 0.5f);
        assertEquals(333, w.width);
        assertEquals(217, w.height);
    }

    @Test
    void aMaximizedWindowIsLeftAlone() {
        Fake w = new Fake(0, 0, 1920, 1040, 0, 0);
        w.maximized = true;
        ZoomSizing z = new ZoomSizing(DESK);
        z.follow(w, 1f);
        z.follow(w, 2f);
        assertEquals(1920, w.width);
    }

    @Test
    void anAxisAlreadySpanningTheWorkAreaKeepsItsSpanAndTheOtherFollows() {
        Fake w = new Fake(0, 0, 600, 1040, 0, 0);
        ZoomSizing z = new ZoomSizing(DESK);
        z.follow(w, 1f);
        z.follow(w, 0.8f);
        assertEquals(480, w.width);
        assertEquals(1040, w.height, "a window dragged to the full height stays the full height");
    }

    @Test
    void aWindowThatWouldOutgrowTheScreenIsFittedIntoIt() {
        Fake w = new Fake(1000, 500, 800, 400, 0, 0);
        ZoomSizing z = new ZoomSizing(DESK);
        z.follow(w, 1f);
        z.follow(w, 2f);
        assertEquals(1600, w.width);
        assertEquals(800, w.height);
        assertEquals(320, w.x, "nudged left so its right edge is on screen");
        assertEquals(240, w.y);
    }

    @Test
    void aResizeByHandIsTheNewBase() {
        Fake w = new Fake(0, 0, 800, 600, 0, 0);
        ZoomSizing z = new ZoomSizing(DESK);
        z.follow(w, 1f);
        z.follow(w, 1.25f);   // 1000 x 750
        w.setBounds(0, 0, 900, 700);   // the person drags it
        z.follow(w, 1f);
        assertEquals(720, w.width);
        assertEquals(560, w.height);
    }

    /** A window with a frame of {@code frameW x frameH} around its client area. */
    private static final class Fake implements NativeWindow {
        final int frameW;
        final int frameH;
        int x;
        int y;
        int width;
        int height;
        boolean maximized;

        Fake(int x, int y, int width, int height, int frameW, int frameH) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.frameW = frameW;
            this.frameH = frameH;
        }

        @Override
        public int width() {
            return width - frameW;
        }

        @Override
        public int height() {
            return height - frameH;
        }

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
        public void setBounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        @Override
        public boolean isMaximized() {
            return maximized;
        }

        @Override
        public boolean pumpEvents() {
            return true;
        }

        @Override
        public void show() {
        }

        @Override
        public boolean isKeyDown(Key key) {
            return false;
        }

        @Override
        public long createVulkanSurface(long vkInstance, MemorySegment vkGetInstanceProcAddr) {
            return 0;
        }

        @Override
        public long osHandle() {
            return 0;
        }

        @Override
        public void close() {
        }
    }
}
