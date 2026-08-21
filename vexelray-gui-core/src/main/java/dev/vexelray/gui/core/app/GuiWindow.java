package dev.vexelray.gui.core.app;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.os.Decorations;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.text.TextLayout;
import dev.vexelray.vulkan.present.AtlasTexture;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.VertexBuffer;
import dev.vexelray.vulkan.present.VulkanRenderPass;
import dev.vexelray.vulkan.present.VulkanSwapchain;
import dev.vexelray.vulkan.present.WindowedPresenter;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VkLoader;
import dev.vexelray.vulkan.vk.VulkanDevice;
import dev.vexelray.vulkan.vk.VulkanInstance;

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
 * ({@link ChromeRegions}), so the window manager knows which of the GUI's own pixels are title bar; and the
 * window is given a frame sink, so the frames Windows asks for while it runs a modal move or resize are drawn
 * instead of the window freezing for the length of the drag.
 *
 * <p><b>Scaffold caveat:</b> the shared device was selected for present support against the <i>first</i> window's
 * surface; every real platform presents to sibling windows of the same display stack from the same queue family,
 * but a per-surface support check at creation is a correctness follow-up. Input is also not yet routed per-window
 * (tactroller attaches to one window today), so a popup renders and resizes but does not hear the pointer.
 */
final class GuiWindow implements AutoCloseable {

    private static final int CAPACITY_FLOATS = 512 * 1024;

    final NativeWindow window;
    /** The tree shown in this window. Set at creation for popups; bound by {@code run} for the main window. */
    Gui gui;
    private final VulkanInstance instance;
    private final long surface;
    private final VulkanSwapchain swapchain;
    private final VulkanRenderPass renderPass;
    private final VertexBuffer vertexBuffer;
    private final GraphicsPipeline pipeline;
    private final WindowedPresenter presenter;
    private final Canvas canvas;
    private final TextLayout[] text;
    private final TextMeasurer measurer;
    private final boolean clientChrome;
    /** The host's per-frame hook, held so a platform-pulled frame runs the same step the host loop would. */
    private Runnable beforeFrame = () -> { };

    /** Create a fresh OS window (popups). Must run on the main thread. */
    GuiWindow(NativePlatform platform, VulkanInstance instance, VulkanDevice device, AtlasTexture atlas,
              TextLayout[] text, TextMeasurer measurer, Gui gui, WindowConfig config) {
        this(platform, instance, device, atlas, text, measurer, gui,
                platform.createWindow(config), 0L, config.decorations());
    }

    /**
     * Adopt an already-created window (the main window is created before the device exists, because device
     * selection needs its surface to prove present support). {@code existingSurface} of 0 creates one here.
     * {@code decorations} is the mode that window was created with — it cannot be read back off the window, and
     * this bundle needs it to know whether the tree's chrome declarations are worth publishing.
     */
    GuiWindow(NativePlatform platform, VulkanInstance instance, VulkanDevice device, AtlasTexture atlas,
              TextLayout[] text, TextMeasurer measurer, Gui gui, NativeWindow window, long existingSurface,
              Decorations decorations) {
        this.instance = instance;
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
                vs.spirv(), "main", fs.spirv(), "main", GuiApp.canvasConfig(atlas, true));
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
        beforeFrame.run();
        RetainedNode root = update();
        canvas.begin();
        if (root != null) {
            TreeRenderer.emit(root, canvas, text);
        }
        vertexBuffer.update(canvas.toVertexArray());
        presenter.setVertexCount(canvas.vertexCount());
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
        RetainedNode root = gui == null ? null : gui.frame(canvas.width(), canvas.height(), measurer);
        if (clientChrome) {
            window.setHitRegions(ChromeRegions.of(root));
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
        window.setFrameSink(null);   // nothing may pull a frame through objects that are being torn down
        presenter.close();   // waits the device idle before tearing down per-window GPU objects
        pipeline.close();
        vertexBuffer.close();
        renderPass.close();
        swapchain.close();
        instance.destroySurface(surface);
        window.close();
    }
}
