package dev.vexelray.gui.core.app;

import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WorkArea;

import java.util.Optional;

/**
 * A window that grows and shrinks with its tree's zoom, so Ctrl+= makes the content bigger and not the window
 * more crowded. One per window, asked once a frame on the main thread ({@link #follow}).
 *
 * <p><b>Why the window and not only the content.</b> Zoom rescales every {@code em} in the tree in proportion, so a
 * window sized to its content before a zoom is a window that no longer fits it after one: zoomed in, the layout
 * wraps and scrolls; zoomed out, it floats in empty space. Every zoom was followed by a resize by hand. Scaling the
 * client area by the same ratio keeps the window the same <em>composition</em> at the new size, which is what the
 * person zooming asked for.
 *
 * <p><b>A window at a bound is left at it.</b> Maximized, minimized and fullscreen windows are the screen's size,
 * not the content's, and do not move. An axis that already spans its monitor's work area (a window dragged to fill
 * the height, a snapped half) keeps that span; the other axis still follows. A window that would outgrow the work
 * area is fitted into it — shrunk to it, then nudged back on screen — never pushed past it.
 *
 * <p><b>No drift.</b> The size is computed from a base — the client size the window had at some zoom — rather than
 * by multiplying the last result, so ten steps in and ten out return to the exact pixel. The base is taken again
 * whenever the window is not the size this last asked for: the person resized it, the OS refused the request, or it
 * came back from maximized. A pinned axis keeps its base, so zooming back out restores the size it had before.
 */
final class ZoomSizing {

    /** A zoom factor closer than this to the last one is the same zoom. */
    private static final float EPSILON = 1e-4f;
    /** The smallest outer size a zoom-out shrinks a window to: still a window, never a sliver. */
    private static final int MIN_WIDTH = 160;
    private static final int MIN_HEIGHT = 120;

    private final WindowMemory.Desktop desktop;
    /** The zoom last seen; NaN until the first frame, so a zoom restored before it is not a change. */
    private float zoom = Float.NaN;
    /** The client size the base holds, at {@link #baseZoom}. */
    private int baseW;
    private int baseH;
    private float baseZoom;
    /** The client size this last asked for, or -1 when there is no request to compare against. */
    private int askedW = -1;
    private int askedH = -1;

    ZoomSizing(WindowMemory.Desktop desktop) {
        this.desktop = desktop;
    }

    /** Resize {@code window} if {@code now} is a zoom it has not been sized for yet. Main thread. */
    void follow(NativeWindow window, float now) {
        if (Float.isNaN(zoom)) {
            zoom = now;
            return;
        }
        if (Math.abs(now - zoom) < EPSILON) {
            return;
        }
        float was = zoom;
        zoom = now;
        if (window.isMinimized() || window.isMaximized() || window.isFullscreen()) {
            askedW = -1;
            return;
        }
        int cw = window.width();
        int ch = window.height();
        if (cw <= 0 || ch <= 0) {
            return;
        }
        if (cw != askedW || ch != askedH) {
            baseW = cw;
            baseH = ch;
            baseZoom = was;
        }
        int ox = window.screenX();
        int oy = window.screenY();
        int ow = window.outerWidth();
        int oh = window.outerHeight();
        WorkArea.Bounds to = fit(ox, oy, ow, oh, ow - cw, oh - ch,
                Math.round(baseW * now / baseZoom), Math.round(baseH * now / baseZoom),
                desktop.covering(ox + ow / 2, oy + oh / 2));
        askedW = to.width() - (ow - cw);
        askedH = to.height() - (oh - ch);
        if (to.x() != ox || to.y() != oy || to.width() != ow || to.height() != oh) {
            window.setBounds(to.x(), to.y(), to.width(), to.height());
        }
    }

    /**
     * Where a window at outer {@code (x, y, w, h)}, whose frame adds {@code frameW × frameH} to its client area, goes
     * when its client area becomes {@code clientW × clientH}: top-left kept, an axis already spanning {@code area}
     * left as it is, and the result fitted into {@code area} when there is one.
     */
    static WorkArea.Bounds fit(int x, int y, int w, int h, int frameW, int frameH, int clientW, int clientH,
                               Optional<WorkArea> area) {
        int tw = Math.max(MIN_WIDTH, clientW + frameW);
        int th = Math.max(MIN_HEIGHT, clientH + frameH);
        if (area.isEmpty()) {
            return new WorkArea.Bounds(x, y, tw, th);
        }
        WorkArea a = area.get();
        if (w >= a.width()) {
            tw = w;
        }
        if (h >= a.height()) {
            th = h;
        }
        return a.fit(x, y, tw, th, MIN_WIDTH, MIN_HEIGHT);
    }
}
