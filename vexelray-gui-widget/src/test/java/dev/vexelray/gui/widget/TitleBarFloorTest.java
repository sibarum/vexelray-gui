package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.WindowFloor;
import dev.vexelray.gui.core.spi.Seams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The window floor a title bar sets (docs/plans/window-grips.md §3.6): a window may not be dragged narrower than its
 * bar's window controls, so the close button is never laid out past the window's right edge.
 *
 * <p>The bar reports nothing. It is docked, so the layout reads its least size off how it is built: the identity
 * zone is clipped and gives way, the caption is slack, and the three caption buttons are what is left.
 */
class TitleBarFloorTest {

    /** Three caption buttons of {@code TitleBar.BUTTON_W}, plus the identity zone's own padding (4dp each side). */
    private static final int BAR_FLOOR_W = 3 * 46 + 2 * 4;
    /** {@code TitleBar.BAR_H}. */
    private static final int BAR_H = 32;

    private static WindowFloor floor(HeadlessGui h) {
        return Seams.of(h.gui).windowFloor();
    }

    @Test
    void aTreeWithNoChromeAsksForNoFloor() {
        try (HeadlessGui h = new HeadlessGui()) {
            h.gui.root().children(h.gui.text("content"));
            h.frame();
            assertEquals(WindowFloor.NONE, floor(h));
        }
    }

    @Test
    void theFloorIsTheWindowControls() {
        try (HeadlessGui h = new HeadlessGui()) {
            h.gui.root().children(new TitleBar(h.gui, WindowControls.NONE, "Test").node());
            h.frame();
            assertEquals(new WindowFloor(BAR_FLOOR_W, BAR_H), floor(h));
        }
    }

    @Test
    void aLongTitleGivesWayRatherThanRaisingIt() {
        try (HeadlessGui h = new HeadlessGui()) {
            h.gui.root().children(new TitleBar(h.gui, WindowControls.NONE, "x".repeat(200)).node());
            h.frame();
            assertEquals(BAR_FLOOR_W, floor(h).width(), "the title is clipped, so it is no part of the floor");
        }
    }

    @Test
    void theFloorFollowsDensity() {
        try (HeadlessGui h = new HeadlessGui()) {
            h.gui.root().children(new TitleBar(h.gui, WindowControls.NONE, "Test").node());
            h.gui.dpi(2f);
            h.frame().frame();
            assertEquals(new WindowFloor(2 * BAR_FLOOR_W, 2 * BAR_H), floor(h), "the bar is in dp, so is its floor");
        }
    }

    @Test
    void aLargerCanvasMinimumWinsOnItsAxis() {
        try (HeadlessGui h = new HeadlessGui()) {
            h.gui.root().children(new TitleBar(h.gui, WindowControls.NONE, "Test").node());
            h.gui.minSize(Length.dp(400), Length.dp(10));
            h.frame();
            assertEquals(new WindowFloor(400, BAR_H), floor(h), "minSize wins across, the bar down");
        }
    }
}
