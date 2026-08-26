package dev.vexelray.gui.plot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraTest {

    private static final double EPS = 1e-12;

    @Test
    @DisplayName("pitch is clamped away from both degenerate ends")
    void pitchIsClamped() {
        assertEquals(Camera.MIN_PITCH, new Camera(0, -5).pitch(), EPS);
        assertEquals(Camera.MAX_PITCH, new Camera(0, 5).pitch(), EPS);
        // A gesture may be applied without checking where it lands, which is what the constructor promises.
        assertEquals(Camera.MAX_PITCH, Camera.DEFAULT.turned(0, 100).pitch(), EPS);
    }

    @Test
    @DisplayName("an eye stands opposite the direction it looks along")
    void eyeIsBackAlongTheViewDirection() {
        // The property two renderers of one surface depend on. A projecting renderer folds the viewpoint into
        // Camera.project and never needs a position; a ray-marcher has no projection and needs nothing else.
        // If these two disagreed, swapping between such renderers would silently change the viewpoint -- the
        // picture would turn as you switched, which reads as a bug in whichever one you switched to.
        for (int i = 0; i < 32; i++) {
            Camera camera = new Camera(i * Math.PI / 7, Math.toRadians(4 + i * 2.5));
            double[] forward = camera.viewDirection();
            double[] eye = camera.eye(3.5);
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(-3.5 * forward[axis], eye[axis], EPS,
                        "axis " + axis + " of " + camera);
            }
        }
    }

    @Test
    @DisplayName("looking from the eye along the view direction arrives at the origin")
    void eyeLooksAtTheOrigin() {
        // The same fact stated the way a marcher uses it: fire a ray from eye(d) along viewDirection() and it
        // reaches the centre of the scene after exactly d.
        Camera camera = Camera.DEFAULT;
        double distance = 4.6;
        double[] eye = camera.eye(distance);
        double[] forward = camera.viewDirection();
        for (int axis = 0; axis < 3; axis++) {
            assertEquals(0.0, eye[axis] + distance * forward[axis], EPS);
        }
    }

    @Test
    @DisplayName("the view direction is a unit vector, so a distance means a distance")
    void viewDirectionIsNormalised() {
        for (int i = 0; i < 16; i++) {
            Camera camera = new Camera(i * 0.4, Math.toRadians(5 + i * 5));
            double[] d = camera.viewDirection();
            assertEquals(1.0, Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]), 1e-12, camera.toString());
        }
    }

    @Test
    @DisplayName("an elevated eye is above the floor it looks down at")
    void anElevatedEyeIsAboveTheFloor() {
        // Pitch is a tilt above the floor, so the eye's height -- the z of the plot's own space -- has to be
        // positive for any legal pitch. A sign error here would put the camera underneath the surface, which
        // renders as a plausible-looking picture of the wrong side.
        for (double degrees = 4; degrees <= 86; degrees += 2) {
            Camera camera = new Camera(1.1, Math.toRadians(degrees));
            assertTrue(camera.eye(2.0)[2] > 0, "eye below the floor at " + degrees + " degrees");
        }
    }

    @Test
    @DisplayName("an eye needs a finite distance")
    void eyeRejectsNonFiniteDistance() {
        assertThrows(IllegalArgumentException.class, () -> Camera.DEFAULT.eye(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Camera.DEFAULT.eye(Double.POSITIVE_INFINITY));
    }
}
