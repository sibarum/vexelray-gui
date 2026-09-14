package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.Viewport;
import sibarum.atchung.Committer;
import sibarum.atchung.State;

/**
 * The ambient factors every {@link Length} resolves against — zoom, display density, the window size — and the
 * limits and minimums that shape them.
 *
 * <p>All of it was on {@code Gui}, where three bus {@code State}s, five volatiles and six remembered values sat
 * among everything else the composition root holds. They belong together because they answer one question
 * between them: <b>what size and scale is this frame</b>. Nothing here consults a node, a mutation or the tree.
 *
 * <p><b>The frame asks once.</b> {@link #measure} is the only thing {@code Gui.frame} needs: it resolves both
 * layout contexts, clamps the canvas to the configured minimum, re-resolves the resize grip, and says what has
 * changed since the last layout ran. The remembered values behind that comparison are this component's and
 * nobody else's — {@link #layoutRan} is how a frame reports that a pass actually happened, which is the only
 * moment they may move.
 *
 * <p>Constructible with nothing: the {@code State}s are built here rather than handed in, so this can be
 * exercised without a window, a bus or a {@code Gui}.
 */
public final class Metrics {

    /** The flat root em, before zoom and density. A constant today; a {@code State} if it ever becomes settable. */
    public static final float ROOT_EM_PX = 16f;

    /**
     * Default zoom bounds and step; override with {@link #zoomRange}.
     *
     * <p>These are the numbers every application that has ever set a range wrote for itself — the demo in this
     * repo, and the four the framework counted before taking them as its own {@code Appearance.ZoomRange.DEFAULT}.
     * They are the default because they are what everyone who cared chose, not because they are a limit:
     * {@link #zoomRange} accepts anything down to 0.01, so an application that wants to go further still can.
     * A default nobody has taken is worth less than a default five callers independently agreed on.
     */
    private static final float DEFAULT_MIN_ZOOM = 0.5f;
    private static final float DEFAULT_MAX_ZOOM = 3f;
    private static final float DEFAULT_ZOOM_STEP = 1.25f;

    /** Density bounds: 1.0 conventional, 2.0 Retina-class, 3.0 exists; below 1 is not a real display. */
    private static final float MIN_DPI = 1f;
    private static final float MAX_DPI = 4f;

    private final State<Viewport> viewport;
    private final Committer<Viewport, Viewport> setViewport;
    private final State<Float> zoom;
    private final Committer<Float, Float> setZoom;
    private final State<Float> dpi;
    private final Committer<Float, Float> setDpi;

    // Zoom limits and step, configurable (see zoomRange). Read on the GUI thread and by zoomIn/zoomOut, which
    // may be called from a worker — volatile is enough, since a stale bound only misses by one step.
    private volatile float minZoom = DEFAULT_MIN_ZOOM;
    private volatile float maxZoom = DEFAULT_MAX_ZOOM;
    private volatile float zoomStep = DEFAULT_ZOOM_STEP;

    // The smallest canvas the UI is laid out on, whatever the window does (see minSize).
    private volatile Length minWidth = Length.ZERO;
    private volatile Length minHeight = Length.ZERO;

    // How far into its own dead space the GUI hands the window manager a resize grip, and that Length resolved
    // against this frame's zoom and density — read by the host right after frame().
    private volatile Length resizeBorder = Length.ZERO;
    private int resizeBorderPx;

    // What the last layout pass ran at. GUI thread only: measure() reads them, layoutRan() writes them, and
    // both happen inside one frame.
    private float lastZoom = -1f;
    private float lastDpi = -1f;
    private float lastViewportW = -1f;
    private float lastViewportH = -1f;
    private float lastLayoutW = -1f;
    private float lastLayoutH = -1f;

    public Metrics() {
        State.Builder<Viewport> vb = State.of(new Viewport(0, 0));
        this.setViewport = vb.mutation("set", (current, next) -> next);
        this.viewport = vb.build();

        State.Builder<Float> zb = State.of(1f);
        this.setZoom = zb.mutation("set", (current, next) -> next);
        this.zoom = zb.build();

        State.Builder<Float> db = State.of(1f);
        this.setDpi = db.mutation("set", (current, next) -> next);
        this.dpi = db.build();
    }

    // --- what one frame needs to know ---------------------------------------------------------------------

    /**
     * The size and scale of one frame, and what about it has moved since the last layout pass.
     *
     * @param windowCtx      resolved against the <em>window</em>, for lengths that must not consult the clamped
     *                       canvas — a minimum in {@code vw} against the clamped size would define itself
     * @param layoutCtx      resolved against the canvas actually laid out, which is what {@code vw}/{@code vh} mean
     * @param layoutW        the clamped canvas width, never smaller than the configured minimum
     * @param layoutH        the clamped canvas height
     * @param viewportMoved  the window changed size
     * @param scaleMoved     zoom or density changed
     * @param clampMoved     the clamped canvas changed, which a minimum in a zoom-dependent unit can do alone
     */
    public record Frame(LayoutContext windowCtx, LayoutContext layoutCtx, float layoutW, float layoutH,
                        boolean viewportMoved, boolean scaleMoved, boolean clampMoved) {

        /** Whether anything about size or scale asks for a relayout. */
        public boolean moved() {
            return viewportMoved || scaleMoved || clampMoved;
        }
    }

    /**
     * Resolve this frame's size and scale, and publish the window size if it changed.
     *
     * <p>Zoom and density are <b>read</b> here rather than pushed at us: a worker's shortcut commits to the
     * {@code State} from its own thread and the frame notices, so nothing outside the GUI thread ever writes a
     * dirty flag.
     */
    public Frame measure(float viewportW, float viewportH) {
        float z = zoom.value();
        float d = dpi.value();
        boolean scaleMoved = z != lastZoom || d != lastDpi;
        boolean viewportMoved = viewportW != lastViewportW || viewportH != lastViewportH;

        // The canvas the layout actually runs on: never smaller than the configured minimum. Resolved against a
        // context built from the *window* size, because a minimum in vw/vh against the clamped size would define
        // itself; em/dp — the units this is for — do not consult the viewport at all.
        LayoutContext windowCtx = new LayoutContext(ROOT_EM_PX, z, d, viewportW, viewportH);
        float layoutW = Math.max(viewportW, minWidth.scalarPx(windowCtx, viewportW));
        float layoutH = Math.max(viewportH, minHeight.scalarPx(windowCtx, viewportH));

        // Resolved every frame like everything else: a zoomed UI whose gutter grew has a grip that grew with it.
        resizeBorderPx = Math.round(resizeBorder.scalarPx(windowCtx, viewportW));
        boolean clampMoved = layoutW != lastLayoutW || layoutH != lastLayoutH;

        if (viewportMoved) {
            // Published before the relayout, so observers and the layout see the same value this frame.
            viewport.commit(setViewport, new Viewport(Math.round(viewportW), Math.round(viewportH)));
        }

        // vw/vh resolve against the laid-out canvas, which is the area that actually exists to fill.
        LayoutContext layoutCtx = new LayoutContext(ROOT_EM_PX, z, d, layoutW, layoutH);
        return new Frame(windowCtx, layoutCtx, layoutW, layoutH, viewportMoved, scaleMoved, clampMoved);
    }

    /**
     * Record that a layout pass ran at {@code frame}, so the next {@link #measure} compares against it.
     *
     * <p>Separate from {@code measure} because a frame can decide not to lay out — there may be no tree yet — and
     * remembering a pass that did not happen would make the next frame think it was up to date.
     */
    public void layoutRan(Frame frame) {
        lastViewportW = frame.windowCtx().viewportW();
        lastViewportH = frame.windowCtx().viewportH();
        lastLayoutW = frame.layoutW();
        lastLayoutH = frame.layoutH();
        lastZoom = frame.layoutCtx().zoom();
        lastDpi = frame.layoutCtx().dpi();
    }

    // --- the ambient factors ------------------------------------------------------------------------------

    /** The window size, as a coalesced bus {@code State}. Always the real window, never the clamped canvas. */
    public State<Viewport> viewport() {
        return viewport;
    }

    /** The user zoom factor as a bus {@code State} (1.0 = unscaled). */
    public State<Float> zoom() {
        return zoom;
    }

    /** Set the zoom factor, clamped to the configured range. Safe from any thread. */
    public void zoom(float factor) {
        zoom.commit(setZoom, Math.max(minZoom, Math.min(maxZoom, factor)));
    }

    /**
     * Set the zoom limits and the step {@link #zoomIn}/{@link #zoomOut} move by. The step is a <b>factor</b>,
     * not an increment: zoom is perceived as a ratio, so a fixed increment is a huge jump at the bottom of the
     * range and an imperceptible one at the top. Re-clamps the current factor, so narrowing the range cannot
     * leave the UI outside it.
     */
    public void zoomRange(float min, float max, float step) {
        this.minZoom = Math.max(0.01f, min);
        this.maxZoom = Math.max(this.minZoom, max);
        this.zoomStep = Math.max(1.0001f, step);
        zoom(zoom.value());
    }

    /** Zoom in one step, clamped to the configured maximum. */
    public void zoomIn() {
        zoom(zoom.value() * zoomStep);
    }

    /** Zoom out one step, clamped to the configured minimum. */
    public void zoomOut() {
        zoom(zoom.value() / zoomStep);
    }

    /** Back to 1.0 — unscaled. */
    public void resetZoom() {
        zoom(1f);
    }

    /** The display density as a bus {@code State} — the points-to-pixels ratio of the surface drawn to. */
    public State<Float> dpi() {
        return dpi;
    }

    /** Set the display density, clamped to [{@value #MIN_DPI}, {@value #MAX_DPI}]. */
    public void dpi(float scale) {
        dpi.commit(setDpi, Math.max(MIN_DPI, Math.min(MAX_DPI, scale)));
    }

    /** The root em in pixels before zoom and DPI. */
    public float rootEmPx() {
        return ROOT_EM_PX;
    }

    /** The smallest canvas the UI is laid out on, whatever the window does. */
    public void minSize(Length width, Length height) {
        this.minWidth = width == null ? Length.ZERO : width;
        this.minHeight = height == null ? Length.ZERO : height;
    }

    /** How thick a resize grip the window manager gets inside each edge, over this GUI's own dead space. */
    public void resizeBorder(Length thickness) {
        this.resizeBorder = thickness == null ? Length.ZERO : thickness;
    }

    /** {@link #resizeBorder} in pixels as of the last {@link #measure}. {@code 0} means "the platform's own". */
    public int resizeBorderPx() {
        return resizeBorderPx;
    }
}
