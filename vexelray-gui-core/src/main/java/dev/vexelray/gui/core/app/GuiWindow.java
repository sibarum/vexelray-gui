package dev.vexelray.gui.core.app;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.canvas.CanvasVertex;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.os.Decorations;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import sibarum.probe.Lane;
import sibarum.probe.Probe;
import sibarum.probe.Zone;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.text.TextLayout;
import dev.vexelray.vulkan.present.AtlasTexture;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.OffscreenDraw;
import dev.vexelray.vulkan.present.SampledImage;
import dev.vexelray.vulkan.present.VertexBuffer;
import dev.vexelray.vulkan.present.VulkanRenderPass;
import dev.vexelray.vulkan.present.VulkanSwapchain;
import dev.vexelray.vulkan.present.WindowedPresenter;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VkLoader;
import dev.vexelray.vulkan.vk.VulkanDevice;
import dev.vexelray.vulkan.vk.VulkanInstance;

import javax.imageio.ImageIO;
import java.io.File;

/**
 * One OS window and everything owned per-window: the {@link NativeWindow}, its Vulkan surface + swapchain +
 * render pass + pipeline + presenter, a {@link Canvas}, a dynamic vertex buffer, and the {@link Gui} tree shown
 * in it. The heavyweight engine objects — instance, device, queue, font atlas — are <b>shared</b> across windows
 * and passed in; a popup costs a window, a surface, a swapchain, and a vertex buffer, not a second GPU bring-up.
 *
 * <p>All windows live on the <b>same thread</b> (the main thread): each is pumped and presented by one
 * {@link #frame} call per host-loop iteration ({@link WindowedPresenter#frame}). That single-thread rule is not a
 * simplification but the portable contract — macOS requires window creation and event pumping on the main thread,
 * and Win32 binds a window to the thread that created it. Nothing here may be called off the main thread; thread-
 * safe popup <i>requests</i> are the host's job ({@link GuiApp#requestPopup}).
 *
 * <p><b>Application-drawn chrome.</b> A window created with {@link Decorations#CLIENT} gets two extra things per
 * frame, and only then: its tree's {@link dev.vexelray.gui.core.WindowRegion} declarations are pushed to the OS
 * ({@link ChromeRegions}) along with {@link Gui#resizeBorder} — how far into the GUI's own dead space the resize
 * grip reaches — so the window manager knows which of the GUI's own pixels are title bar and which are edge; and
 * the window is given a frame sink, so the frames Windows asks for while it runs a modal move or resize are
 * drawn instead of the window freezing for the length of the drag.
 *
 * <p><b>Input is per-window</b>, and the host attaches it: each window gets its own backend through
 * {@link WindowInput}, pumped by {@link #frame} before that window's tree is laid out, so two windows hear their
 * own pointers without either learning about the other.
 *
 * <p><b>Scaffold caveat:</b> the shared device was selected for present support against the <i>first</i> window's
 * surface; every real platform presents to sibling windows of the same display stack from the same queue family,
 * but a per-surface support check at creation is a correctness follow-up.
 */
final class GuiWindow implements AutoCloseable {

    /**
     * The per-window vertex buffer, in floats. Host-visible and rewritten once a frame, so it is the ceiling on
     * how complicated one window's picture may be.
     *
     * <p>Worth doing the arithmetic rather than picking a round number. At 23 floats a vertex and six vertices a
     * quad, a quad is 138 floats: this is about thirty thousand of them. An ordinary dense UI is a few hundred;
     * a plot that draws a surface out of boxes is tens of thousands, which is how the previous figure of 512K —
     * under four thousand quads — was found, by a window dying on it mid-frame.
     *
     * <p>Sixteen megabytes of host-visible memory is not a meaningful cost on any device that can run this. The
     * real fix is a buffer that <b>grows</b>; until it does, {@link #draw} degrades rather than throws.
     */
    private static final int CAPACITY_FLOATS = 4 * 1024 * 1024;

    /** Said once, not once a frame: a window over budget is over budget for as long as it is on screen. */
    private boolean warnedOverCapacity;

    /**
     * Teardown happens once. Every object below is a GPU handle whose second destruction is undefined behaviour,
     * and the two paths that close a window — a popup releasing itself, and the application closing every window
     * it still holds — are not mutually exclusive by construction. Guarding here is what keeps that harmless.
     */
    private boolean closed;

    final NativeWindow window;
    /** The tree shown in this window. Set at creation for popups; bound by {@code run} for the main window. */
    Gui gui;
    private final VulkanInstance instance;
    /** Held for {@link #capture}: the offscreen pass and pipeline it needs are built on this window's device. */
    private final VulkanDevice device;
    /** Held for {@link #capture}, which binds the same descriptor set the presenter draws with. */
    private final AtlasTexture atlas;
    /**
     * The offscreen render pass and pipeline {@link #capture} draws through, built on first use and rebuilt when
     * the window's extent changes. Lazy because most windows are never captured, and a capture path that costs
     * every window a second pipeline whether or not anyone asks for a picture is a cost with no consumer.
     */
    private VulkanRenderPass capturePass;
    private GraphicsPipeline capturePipeline;
    private int captureW;
    private int captureH;
    private final long surface;
    private final VulkanSwapchain swapchain;
    private final VulkanRenderPass renderPass;
    private final VertexBuffer vertexBuffer;
    private final GraphicsPipeline pipeline;
    private final WindowedPresenter presenter;
    private final Canvas canvas;
    /** Bound at the image set for every span that draws no image -- see {@link AtlasTexture#placeholder}. */
    private final SampledImage noImage;
    private final TextLayout[] text;
    private final TextMeasurer measurer;
    private final boolean clientChrome;
    /** The host's per-frame hook, held so a platform-pulled frame runs the same step the host loop would. */
    private Runnable beforeFrame = () -> { };

    /**
     * Create a fresh OS window (popups). Must run on the main thread.
     *
     * <p>{@code windows} is the host's window factory — {@link GuiApp#GuiApp(WindowConfig,
     * java.util.function.Function)} — and it is <b>not optional</b>, because this constructor is the only
     * place in the framework a window is born after start-up. Going to the platform directly from here is what
     * made every window but the first invisible to a host that had said what its windows should be: under a
     * test harness, a popup appearing on screen mid-run and taking the keyboard from whatever the test was
     * actually measuring.
     */
    GuiWindow(NativePlatform platform, VulkanInstance instance, VulkanDevice device, AtlasTexture atlas,
              SampledImage noImage, TextLayout[] text, TextMeasurer measurer, Gui gui, WindowConfig config,
              java.util.function.Function<WindowConfig, NativeWindow> windows) {
        this(platform, instance, device, atlas, noImage, text, measurer, gui,
                windows.apply(config), 0L, config.decorations());
    }

    /**
     * Adopt an already-created window (the main window is created before the device exists, because device
     * selection needs its surface to prove present support). {@code existingSurface} of 0 creates one here.
     * {@code decorations} is the mode that window was created with — it cannot be read back off the window, and
     * this bundle needs it to know whether the tree's chrome declarations are worth publishing.
     */
    GuiWindow(NativePlatform platform, VulkanInstance instance, VulkanDevice device, AtlasTexture atlas,
              SampledImage noImage, TextLayout[] text, TextMeasurer measurer, Gui gui, NativeWindow window,
              long existingSurface, Decorations decorations) {
        this.instance = instance;
        this.device = device;
        this.atlas = atlas;
        this.noImage = noImage;
        this.gui = gui;
        this.text = text;
        this.measurer = measurer;
        this.window = window;
        this.clientChrome = decorations == Decorations.CLIENT;
        this.surface = existingSurface != 0L ? existingSurface
                : window.createVulkanSurface(instance.handleAddress(), VkLoader.getInstanceProcAddrPointer());
        this.swapchain = new VulkanSwapchain(instance.handle(), device, surface, window.width(), window.height());
        this.renderPass = new VulkanRenderPass(device, swapchain.format(), Vk.IMAGE_LAYOUT_PRESENT_SRC_KHR);
        this.canvas = new Canvas(swapchain.width(), swapchain.height());
        this.vertexBuffer = new VertexBuffer(device, CAPACITY_FLOATS);
        ComposedShader vs = CanvasShader.vertex();
        ComposedShader fs = CanvasShader.fragment();
        this.pipeline = new GraphicsPipeline(device, renderPass.handle(), swapchain.width(), swapchain.height(),
                vs.spirv(), "main", fs.spirv(), "main", GuiApp.canvasConfig(atlas, noImage, true));
        this.presenter = new WindowedPresenter(device, swapchain, renderPass.handle(), pipeline, window);
        presenter.configureDraw(vertexBuffer.handle(), atlas.descriptorSet(), 0);
        // Windows drags and resizes a window inside a message loop of its own, which suspends the host's loop for
        // as long as the gesture lasts. Handing the window a sink lets it pull the frames that loop would have
        // drawn — the difference between a window that resizes live and one that freezes until the mouse is let
        // go. It renders without pumping, because the pump is what called it.
        window.setFrameSink(() -> presenter.render(0, this::draw));
    }

    /**
     * Pump this window's events and present one frame of its {@link Gui}. Returns {@code false} once the window
     * has been asked to close — the host then {@link #close()}es this bundle (the shared device stays up).
     */
    boolean frame(Runnable beforeFrame) {
        this.beforeFrame = beforeFrame == null ? () -> { } : beforeFrame;
        if (!window.pumpEvents()) {
            return false;
        }
        if (window.isMinimized()) {
            // A minimized window is 0x0: there is nothing to present to, and a swapchain acquired against a
            // surface with no extent can block forever rather than fail — the loop stops, with the window not
            // even on screen to show for it. The rest of the frame still runs, so mutations from worker threads
            // keep draining and input keeps being consumed instead of piling up behind the taskbar button.
            this.beforeFrame.run();
            update();
            idle();
            return true;
        }
        return presenter.render(0, this::draw);
    }

    /**
     * Render this window's current tree offscreen and write it to {@code path} as a PNG.
     *
     * <p><b>Per window, and on the main thread.</b> Per window because a screenshot is of a window — the one
     * whose title bar the instrument sits in (docs/automation.md §7) — and an application-level capture would
     * only ever have had to be widened into this. On the main thread because everything Vulkan here is, so this
     * is called from the frame loop's task queue and never directly.
     *
     * <p>It draws the tree <em>as it stands</em>: the same drain-and-lay-out step {@link #draw} runs, then the
     * same {@link TreeRenderer} emit into the same {@link Canvas}, so a capture and the frame beside it cannot
     * disagree. Unlike the static {@link GuiApp#capture} there is no warm-up frame — this window has been
     * running, so whatever an observer wanted to settle has settled.
     *
     * <p>The background is the theme's page colour rather than transparent: a PNG of a translucent UI over
     * nothing is unreadable, and every consumer of this wants to see what was on screen.
     */
    void capture(String path) throws java.io.IOException {
        int w = swapchain.width();
        int h = swapchain.height();
        if (w <= 0 || h <= 0) {
            return;                     // minimized: there is no picture, and asking for one is not an error
        }
        if (capturePass == null || captureW != w || captureH != h) {
            closeCapture();
            capturePass = new VulkanRenderPass(device, Vk.FORMAT_R8G8B8A8_UNORM,
                    Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            capturePipeline = new GraphicsPipeline(device, capturePass.handle(), w, h,
                    CanvasShader.vertex().spirv(), "main", CanvasShader.fragment().spirv(), "main",
                    GuiApp.canvasConfig(atlas, noImage, false)); // fixed viewport: offscreen never resizes mid-draw
            captureW = w;
            captureH = h;
        }

        RetainedNode root = update();
        canvas.begin();
        if (root != null) {
            TreeRenderer.emit(root, canvas, text, gui.theme());
        }
        int vertexCount = Math.min(canvas.vertexCount(),
                (int) (vertexBuffer.capacityFloats() / CanvasVertex.FLOATS_PER_VERTEX));
        vertexBuffer.update(canvas.toVertexArray(), vertexCount * CanvasVertex.FLOATS_PER_VERTEX);

        Color page = gui.theme().color(Role.PAGE);
        byte[] rgba = OffscreenDraw.toRgba(device, capturePass.handle(), capturePipeline, w, h,
                vertexBuffer.handle(), atlas.descriptorSet(),
                GuiApp.bind(canvas.runs(), vertexCount, noImage), page.r(), page.g(), page.b(), 1f);
        // Written beside the target and moved into place, never written at it. A capture is asynchronous, so
        // every consumer of one waits for the file to appear — and a PNG written in place is *visible* from its
        // first byte, so the natural way to wait produces a truncated read on a timing this test hit first try.
        // Moving atomically makes "the file is there" mean "the file is complete", for every consumer, forever.
        File target = new File(path);
        File tmp = new File(target.getAbsolutePath() + ".part");
        ImageIO.write(GuiApp.toImage(rgba, w, h), "PNG", tmp);
        try {
            java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Some filesystems cannot; a plain replace is still far narrower than writing in place.
            java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        // The vertex buffer now holds the capture's geometry and the presenter's run list still describes the
        // last presented frame. Nothing reads either until the next frame rewrites both, but leaving the two
        // disagreeing is the kind of state that is only ever fine until something else is added here.
        presenter.setRuns(GuiApp.bind(canvas.runs(), vertexCount, noImage));
    }

    /** Release the offscreen pass and pipeline, if this window ever built them. */
    private void closeCapture() {
        if (capturePipeline != null) {
            capturePipeline.close();
            capturePipeline = null;
        }
        if (capturePass != null) {
            capturePass.close();
            capturePass = null;
        }
    }

    /** Yield while there is nothing to draw, so a minimized window costs a poll rather than a core. */
    private static void idle() {
        try {
            Thread.sleep(8L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One frame's worth of work: host hook, layout, emit, and (for client chrome) republish the OS regions. */
    private void draw(double dt, java.lang.foreign.MemorySegment pushConstants) {
        // The application edge - on a real app this is the input pump, so its cost is input's, not drawing's.
        try (Zone z = Probe.zone(Lane.FRAME, "before frame")) {
            beforeFrame.run();
        }
        RetainedNode root = update();
        try (Zone z = Probe.zone(Lane.DRAW, "emit canvas")) {
            canvas.begin();
            if (root != null) {
                TreeRenderer.emit(root, canvas, text, gui.theme());
            }
            // The number the truncation warning below is about, recorded every frame rather than only when it
            // overflows: a buffer that is nearly full is the frame before the one that draws wrong.
            Probe.count(Lane.DRAW, "vertices", canvas.vertexCount());
            Probe.count(Lane.DRAW, "runs", canvas.runs().size());
            emit(canvas.toVertexArray(), canvas.vertexCount(), canvas.runs());
        }
    }

    /**
     * Hand one frame's vertices to the GPU, dropping the tail if there are more than the buffer holds.
     *
     * <p>A picture too complicated to draw is a bad frame; it is not a reason for the application to stop. The
     * old behaviour threw out of the render loop and took the process with it, which turns "this plot is denser
     * than the buffer expected" into a crash with a stack trace about Vulkan — from which nothing about the
     * actual cause is apparent. Truncating loses the primitives emitted last, which are the nearest ones, so a
     * frame over budget is visibly wrong rather than quietly wrong. It says so once, with the numbers, because a
     * cap nobody is told about reads as a rendering bug.
     */
    private void emit(float[] vertices, int vertexCount, java.util.List<Canvas.Run> runs) {
        int room = (int) (vertexBuffer.capacityFloats() / CanvasVertex.FLOATS_PER_VERTEX);
        if (vertexCount > room) {
            if (!warnedOverCapacity) {
                warnedOverCapacity = true;
                System.err.println("vexelray-gui: window needs " + vertexCount + " vertices and the buffer holds "
                        + room + "; drawing what fits. Raise GuiWindow.CAPACITY_FLOATS or draw less.");
            }
            vertexCount = room;
        }
        vertexBuffer.update(vertices, vertexCount * CanvasVertex.FLOATS_PER_VERTEX);
        presenter.setRuns(GuiApp.bind(runs, vertexCount, noImage));
    }

    /**
     * Everything a frame does that is not drawing: follow the window's size, drain and lay out the tree, and —
     * for a client-decorated window — republish the regions the OS hit-tests against, derived from the very tree
     * that is about to be drawn, so the window manager and the user are never looking at different frames.
     *
     * <p>Separate from {@link #draw} because a minimized window still has to do all of it. The mutations a worker
     * posts do not stop arriving because the user pressed minimize.
     */
    private RetainedNode update() {
        // On resize, rebuild the Canvas at the new size so its pixel→NDC mapping matches the (dynamic) viewport,
        // and feed the live size to the GUI, which relays out.
        int ww = window.width();
        int wh = window.height();
        if (ww > 0 && wh > 0 && (ww != canvas.width() || wh != canvas.height())) {
            canvas.resize(ww, wh);
        }
        RetainedNode root;
        // Everything between a mutation and a drawable node: dispatch, drain, reconcile, layout, publish. The
        // LAYOUT lane names the pieces; this is their sum, and the one number to compare against DRAW and GPU.
        try (Zone z = Probe.zone(Lane.LAYOUT, "tree update")) {
            root = gui == null ? null : gui.frame(canvas.width(), canvas.height(), measurer);
        }
        if (clientChrome) {
            // The tree's own declarations, plus how far into its dead space it hands over a resize grip — both
            // derived from the frame just laid out, so the band scales with the gutter it was set from.
            window.setHitRegions(ChromeRegions.of(root, gui == null ? 0 : gui.resizeBorderPx()));
        }
        return root;
    }

    /** The raw OS window handle — for attaching input at the application edge. */
    long osHandle() {
        return window.osHandle();
    }

    /** Release everything this window owns. Shared objects (device, atlas, instance) are not touched. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        window.setFrameSink(null);   // nothing may pull a frame through objects that are being torn down
        presenter.close();   // waits the device idle before tearing down per-window GPU objects
        closeCapture();
        pipeline.close();
        vertexBuffer.close();
        renderPass.close();
        swapchain.close();
        instance.destroySurface(surface);
        window.close();
    }
}
