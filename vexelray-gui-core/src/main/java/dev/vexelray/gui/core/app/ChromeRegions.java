package dev.vexelray.gui.core.app;

import dev.vexelray.gui.core.WindowRegion;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.os.HitRegions;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a laid-out tree's {@link WindowRegion} declarations into the {@link HitRegions} the OS window is told
 * about — the whole of the host's part in application-drawn chrome.
 *
 * <p>It is a <b>derivation, not a protocol</b>: the answer is a pure function of the tree as it currently stands,
 * recomputed each frame and pushed. Nothing registers, nothing subscribes, and a title bar that moves because a
 * toolbar above it grew needs no notification — its rectangle is simply different the next time this runs.
 *
 * <p>Coordinates come straight off the laid-out nodes, which are already in the window's client pixel space, and
 * are rounded outward-consistently (each edge rounded independently) so that adjacent regions tile without
 * leaving a one-pixel seam the window manager would read as content.
 */
final class ChromeRegions {

    private ChromeRegions() {
    }

    /** The regions declared by {@code root}'s subtree, or {@link HitRegions#NONE} if it declares none. */
    static HitRegions of(RetainedNode root) {
        if (root == null) {
            return HitRegions.NONE;
        }
        Collector collector = new Collector();
        collector.walk(root);
        if (collector.caption.isEmpty() && collector.interactive.isEmpty() && collector.maximize == null) {
            return HitRegions.NONE;
        }
        return new HitRegions(collector.caption, collector.interactive, collector.maximize, 0);
    }

    private static final class Collector {

        private final List<HitRegions.Rect> caption = new ArrayList<>();
        private final List<HitRegions.Rect> interactive = new ArrayList<>();
        private HitRegions.Rect maximize;

        void walk(RetainedNode n) {
            if (!n.visible()) {
                // A hidden node was not laid out, so its rect describes a place it no longer occupies. Publishing
                // it would hand the window manager a title bar that is not on screen.
                return;
            }
            WindowRegion region = n.windowRegion();
            if (region != null) {
                HitRegions.Rect rect = rectOf(n);
                if (rect != null) {
                    switch (region) {
                        case DRAG -> caption.add(rect);
                        case INTERACTIVE -> interactive.add(rect);
                        // One maximize button per window: the last one declared wins, which is the one drawn on
                        // top of the others and so the one the pointer would reach.
                        case MAXIMIZE_BUTTON -> {
                            interactive.add(rect);
                            maximize = rect;
                        }
                    }
                }
            }
            for (RetainedNode c : n.children) {
                walk(c);
            }
        }

        private static HitRegions.Rect rectOf(RetainedNode n) {
            int x = Math.round(n.x);
            int y = Math.round(n.y);
            int w = Math.round(n.x + n.w) - x;
            int h = Math.round(n.y + n.h) - y;
            return w > 0 && h > 0 ? new HitRegions.Rect(x, y, w, h) : null;
        }
    }
}
