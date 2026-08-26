package dev.vexelray.gui.plot;

/**
 * Where a surface is looked at from: an <b>axonometric</b> camera — a yaw, a pitch, and a magnification, with no
 * perspective at all.
 *
 * <p>The absence of perspective is the design decision, not a simplification. A reliable surface is drawn as a
 * field of axis-aligned boxes standing over a grid, and under an orthographic projection the order those boxes
 * must be painted in depends only on where the grid squares are — {@link #depthKey} is that order, and it is a
 * function of {@code (x, y)} alone. Add perspective and the ordering starts depending on height as well, for a
 * gain (a sense of distance) that a plot of a function does not want in the first place: a surface is read by
 * comparing heights, and a projection that makes the far side of the window smaller than the near side makes
 * that comparison a lie.
 *
 * <h2>The space it projects</h2>
 * Coordinates arrive <b>normalised</b> — the volume on show mapped to {@code [-0.5, 0.5]} on each axis — so this
 * class knows nothing about {@link Volume} and a volume knows nothing about being looked at. What comes back is
 * in the same units: {@code u} to the right, {@code v} upward, both around zero.
 *
 * <p>There is deliberately <b>no magnification here</b>. A camera that carried one would be carrying half a
 * decision, since how large the picture should be depends on the viewport and this class has never heard of one;
 * {@link #reach} hands over the half-extent instead, and whoever owns the pixels divides by it. Two angles is
 * the whole of what a viewpoint is.
 *
 * @param yaw   rotation about the vertical axis, in radians — which way the floor is turned
 * @param pitch tilt above the floor, in radians, clamped away from both degenerate ends
 */
public record Camera(double yaw, double pitch) {

    /** Never edge-on: at zero pitch the floor collapses to a line and the surface has no ground to stand on. */
    public static final double MIN_PITCH = Math.toRadians(4);

    /** Never overhead either: at a right angle the heights vanish and a surface becomes a contour map. */
    public static final double MAX_PITCH = Math.toRadians(86);

    /** Three-quarter view: turned enough that both floor axes read, tilted enough to see height as height. */
    public static final Camera DEFAULT = new Camera(Math.toRadians(38), Math.toRadians(26));

    public Camera {
        if (!Double.isFinite(yaw) || !Double.isFinite(pitch)) {
            throw new IllegalArgumentException("a camera needs finite angles");
        }
        pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch));
    }

    /**
     * Turned by {@code dYaw} and tilted by {@code dPitch}, both in radians. The drag, and the arrow keys. The
     * pitch is clamped by the constructor, so a gesture may be applied without checking where it lands.
     */
    public Camera turned(double dYaw, double dPitch) {
        return new Camera(yaw + dYaw, pitch + dPitch);
    }

    /**
     * Project a normalised point. The two rotations, in the order they are named: the floor turns about the
     * vertical axis, then the whole thing tilts back.
     */
    public Point project(double x, double y, double z) {
        double cy = Math.cos(yaw);
        double sy = Math.sin(yaw);
        double across = x * cy - y * sy;              // rightward on screen, unaffected by pitch
        double away = x * sy + y * cy;                // into the picture, before the tilt
        double cp = Math.cos(pitch);
        double sp = Math.sin(pitch);
        // Screen-up is world-up tilted back by the pitch, so distance carries a point upward as well as height
        // does -- which is what makes a floor read as a floor. Depth runs the other way for the same reason: an
        // elevated eye is nearer to a tall point than to a short one directly beneath it.
        return new Point(across, away * sp + z * cp, away * cp - z * sp);
    }

    /**
     * How far a cell at {@code (x, y)} on the floor is from the eye, ignoring height — <b>the painting order</b>.
     * Cells drawn in descending order of this are drawn far to near.
     *
     * <p>Height is deliberately left out. Including it would be more precise about two boxes and wrong about the
     * grid: a surface is a set of prisms standing over disjoint floor squares, and it is the floor squares that
     * decide which prism is behind which. This is the ordering every heightmap renderer uses, and it is a
     * statement about surfaces over a grid rather than a theorem about boxes in general — which is why the
     * surface plot's soundness claim is made per cell and not per pixel.
     */
    public double depthKey(double x, double y) {
        return x * Math.sin(yaw) + y * Math.cos(yaw);
    }

    /**
     * The unit vector pointing from the eye into the scene, in the same normalised space {@link #project} takes
     * its arguments in. {@code {x, y, z}}.
     *
     * <p><b>One vector for the whole picture</b>, which is the useful consequence of there being no perspective:
     * under an orthographic projection every point is viewed along the same direction, so anything that depends
     * on the angle between a surface and the eye — a Fresnel term, a reflection — needs this once rather than
     * per point. It is also, by construction, the direction {@link #depthKey} measures along.
     */
    public double[] viewDirection() {
        double cp = Math.cos(pitch);
        return new double[]{cp * Math.sin(yaw), cp * Math.cos(yaw), -Math.sin(pitch)};
    }

    /**
     * Where an eye {@code distance} away stands to look at the origin from here — {@code {x, y, z}}, in the same
     * normalised space {@link #project} takes its arguments in.
     *
     * <p>Simply {@code -distance} times {@link #viewDirection}, and it exists so that a renderer needing an
     * actual <em>position</em> rather than a projection does not re-derive one from the angles. A ray-marcher
     * does need one: it has no projection to fold the viewpoint into, only an eye and a direction to fire rays
     * along. Deriving it here means a marched picture and a projected one look from the same place <em>because
     * they are computed from the same vector</em>, rather than because two pieces of trigonometry were written
     * to agree and have not yet drifted — which matters the moment an application offers both and lets someone
     * swap between them.
     *
     * <p>No magnification in it, for the reason there is none anywhere else here: how far back an eye should
     * stand depends on how large the picture is meant to be, and this class has never heard of a viewport. The
     * caller supplies the distance it wants.
     */
    public double[] eye(double distance) {
        if (!Double.isFinite(distance)) {
            throw new IllegalArgumentException("an eye needs a finite distance, was " + distance);
        }
        double[] forward = viewDirection();
        return new double[]{-distance * forward[0], -distance * forward[1], -distance * forward[2]};
    }

    /**
     * The half-extent, in projected units, of the normalised unit box seen from here — {@code {u, v}}. What a
     * viewport divides by to make the whole volume fit however it happens to be turned.
     */
    public double[] reach() {
        double maxU = 0;
        double maxV = 0;
        for (int corner = 0; corner < 8; corner++) {
            Point p = project((corner & 1) == 0 ? -0.5 : 0.5,
                              (corner & 2) == 0 ? -0.5 : 0.5,
                              (corner & 4) == 0 ? -0.5 : 0.5);
            maxU = Math.max(maxU, Math.abs(p.u()));
            maxV = Math.max(maxV, Math.abs(p.v()));
        }
        return new double[]{maxU, maxV};
    }

    /**
     * A projected point: {@code u} right, {@code v} up, and how far away it was. The depth is carried because a
     * consumer that wants to shade by distance should not have to project twice to find out.
     */
    public record Point(double u, double v, double depth) {
    }
}
