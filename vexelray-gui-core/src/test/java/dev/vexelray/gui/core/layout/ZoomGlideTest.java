package dev.vexelray.gui.core.layout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A zoom step as motion: where it heads, how it gets there, and who it gives way to. */
class ZoomGlideTest {

    private static final long MS = 1_000_000L;

    @Test
    void withNoGlideAStepIsAtOnce() {
        Metrics m = new Metrics();
        m.zoomIn();
        assertEquals(1.25f, m.zoom().value());
        assertFalse(m.zoomGliding());
    }

    @Test
    void aGlidedStepClosesOnItsTargetFrameByFrameAndLandsExactly() {
        Metrics m = new Metrics();
        m.zoomGlide(40 * MS);
        m.zoomIn();
        assertEquals(1f, m.zoom().value(), "a step moves the target, not the zoom");

        long now = 1_000 * MS;
        float last = 1f;
        int frames = 0;
        while (m.zoomGliding() && frames < 200) {
            assertTrue(m.stepZoomGlide(now));
            float z = m.zoom().value();
            assertTrue(z > last || z == 1.25f, "always on its way, never back: " + last + " -> " + z);
            assertTrue(z <= 1.25f, "never past the target");
            last = z;
            now += 16 * MS;
            frames++;
        }
        assertEquals(1.25f, m.zoom().value(), "arrives on the target, not near it");
        assertTrue(frames > 3, "and took more than a frame or two: " + frames);
    }

    @Test
    void stepsMidGlideAddUp() {
        Metrics m = new Metrics();
        m.zoomGlide(40 * MS);
        m.zoomIn();
        m.stepZoomGlide(1_000 * MS);
        m.zoomIn();
        long now = 1_016 * MS;
        while (m.zoomGliding()) {
            m.stepZoomGlide(now);
            now += 16 * MS;
        }
        assertEquals(1.5625f, m.zoom().value(), 1e-5f, "two presses are two steps from where it was heading");
    }

    @Test
    void settingTheZoomDirectlyStopsAGlide() {
        Metrics m = new Metrics();
        m.zoomGlide(40 * MS);
        m.zoomIn();
        m.stepZoomGlide(1_000 * MS);
        m.zoom(2f);
        assertFalse(m.zoomGliding(), "a restore or a slider owns the zoom now");
        assertFalse(m.stepZoomGlide(1_016 * MS));
        assertEquals(2f, m.zoom().value());
    }

    @Test
    void turningTheGlideOffMidwayLandsAtOnce() {
        Metrics m = new Metrics();
        m.zoomGlide(40 * MS);
        m.zoomOut();
        m.zoomGlide(0L);   // reduced motion arrived
        assertEquals(0.8f, m.zoom().value());
        assertFalse(m.zoomGliding());
    }
}
