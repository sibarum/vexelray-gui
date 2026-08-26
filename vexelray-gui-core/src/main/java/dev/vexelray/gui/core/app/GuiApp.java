package dev.vexelray.gui.core.app;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.canvas.CanvasVertex;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.text.AtlasData;
import dev.vexelray.text.GlyphLayout;
import dev.vexelray.text.TextLayout;
import dev.vexelray.vulkan.present.AtlasTexture;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.OffscreenDraw;
import dev.vexelray.vulkan.present.SampledColorTarget;
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
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The GUI application host and frame loop — the seam between the framework and VexelRay. It owns the window and
 * the VexelRay objects (device, swapchain, render pass, font atlas, the Canvas pipeline, a dynamic vertex buffer,
 * the presenter). Each frame it asks the {@link Gui} to drain mutations, reconcile, and lay out the tree, then
 * walks the resulting {@link RetainedNode} tree into a {@link Canvas} ({@link TreeRenderer}) and presents. The GUI
 * writes no Vulkan or shader code — this only composes VexelRay's native API.
 */
public final class GuiApp implements AutoCloseable {

    private static final String ATLAS_JSON = "/dev/vexelray/text/atlas/primary.json";
    private static final String ATLAS_PNG = "/dev/vexelray/text/atlas/primary.png";

    // Shared engine context — one GPU bring-up serves every window.
    private final NativePlatform platform;
    private final VulkanInstance instance;
    private final VulkanDevice device;
    private final AtlasTexture atlas;
    private final AtlasTexture noImage;
    /** Render targets minted by {@link #viewport}, closed with the application. */
    private final List<SampledColorTarget> viewports = new ArrayList<>();
    private final TextLayout[] text;
    private final TextMeasurer measurer;

    // The main window, plus every other window this application has open. All of them live on the main thread
    // and are pumped/presented by the one loop in run(); another window closing removes only its own bundle, the
    // main window closing ends the loop.
    private final GuiWindow main;
    private final WindowControls controls;
    private final List<OpenWindow> open = new ArrayList<>();

    // Work that must happen on the main thread at the top of a frame: opening a window, showing, hiding or
    // closing one. Every thread-safe command on this class is one of these — enqueued here, performed there.
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> tasks =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Windows the application refers to by name, so asking twice raises one window instead of making two. */
    private final java.util.concurrent.ConcurrentHashMap<String, AppWindow> named =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** How input is attached to windows the framework opens; NONE until the application edge supplies one. */
    private volatile WindowInput.Factory inputs = WindowInput.Factory.NONE;

    /** The main window's close policy — see {@link #onCloseRequest}. */
    private final CloseGate mainGate = new CloseGate();

    /** The tree in the main window; bound by {@link #run}, and the executor application callbacks run on. */
    private Gui mainGui;

    /** The window a modal dialog is showing in, or null when nothing is modal. Main thread. */
    private NativeWindow modal;

    /**
     * What the rest of the application is dimmed with while a modal is up. Unset means "whatever the blocked
     * tree's theme says" (Role.SCRIM), so a dialog over a light window dims correctly without the application
     * restating it; set it explicitly to override, or to null for no scrim at all.
     */
    private volatile dev.vexelray.canvas.Color modalDim;
    private volatile boolean modalDimSet;

    /** One scrim node per blocked tree, created on first use and hidden between modals. */
    private final java.util.Map<Gui, ModalScrim> scrims = new java.util.IdentityHashMap<>();

    public GuiApp(String title, int width, int height) {
        this(WindowConfig.of(title, width, height));
    }

    /**
     * As {@link #GuiApp(String, int, int)}, but with the full window request — the overload an app restoring
     * persisted bounds uses, so the window is <em>created</em> where it was last left rather than jumping there
     * after appearing (see {@code Settings}).
     */
    public GuiApp(WindowConfig config) {
        this.platform = NativePlatform.current();
        this.instance = new VulkanInstance(config.title(), platform.requiredVulkanInstanceExtensions());

        // Device selection needs a surface to prove present support, so the main window is created first and its
        // surface probed; popups then reuse the same device (scaffold caveat: same-queue present support for
        // sibling surfaces holds on every real platform, but is asserted per-surface only for this first one).
        NativeWindow probe = platform.createWindow(config);
        long probeSurface = probe.createVulkanSurface(instance.handleAddress(),
                VkLoader.getInstanceProcAddrPointer());
        VulkanInstance.DeviceSelection selection = instance.selectGraphicsPresentDevice(probeSurface)
                .orElseThrow(() -> new IllegalStateException("no graphics+present device"));
        this.device = new VulkanDevice(instance.handle(), selection);

        int[] atlasSize = new int[2];
        byte[] atlasRgba = loadAtlasRgba(atlasSize);
        this.atlas = new AtlasTexture(device, atlasSize[0], atlasSize[1], atlasRgba);
        // One placeholder for the device, not one per window: every window's canvas pipeline is built against the
        // same layout, and every span that draws no image binds this same 1x1 white.
        this.noImage = AtlasTexture.placeholder(device);
        this.text = faces(AtlasData.loadFromResource(ATLAS_JSON));
        this.measurer = measurer(text);

        this.main = new GuiWindow(platform, instance, device, atlas, noImage, text, measurer, null,
                probe, probeSurface, config.decorations());
        this.controls = WindowControls.of(main.window);
    }

    /**
     * A render target of this size, on this application's device — what a <b>viewport</b> node shows.
     *
     * <p>Hand the result to {@code Node.image(...)} and render a scene into it (
     * {@code SampledColorTarget.renderInto}) before the frame that shows it. It is a {@code SampledImage}, so the
     * canvas draws it as a box that samples: it rounds, clips, fades and lays out like every other node.
     *
     * <p>This exists because the device is deliberately not public — GPU lifetime is the framework's business, and
     * a target allocated on a <em>different</em> device produces a descriptor set this application's pipeline
     * cannot bind, which is a validation error rather than a blank box. Asking the application for its size and
     * nothing else keeps that impossible. Targets made here are closed with the application, so an app that keeps
     * one per viewport for the session need not track them; one that resizes a viewport should
     * {@link SampledColorTarget#close()} the old target itself, after a frame that no longer names it.
     *
     * <p><b>Not resized for you.</b> A target has fixed pixels, and the node it lands in is laid out by flex — so
     * a viewport whose box has changed shape is being upscaled by the sampler until the application makes a new
     * target at the new size. Read the box from {@code Node.layout()} and decide; the framework will not guess,
     * because re-marching a scene is far too expensive to trigger from a resize it merely noticed.
     */
    public SampledColorTarget viewport(int width, int height) {
        SampledColorTarget target = new SampledColorTarget(device, Math.max(1, width), Math.max(1, height));
        viewports.add(target);
        return target;
    }

    /** The OS window handle (an {@code HWND} on Windows) — used to attach input (tactroller) for client-space
     *  coordinates and focus gating at the application edge. */
    public long windowHandle() {
        return main.osHandle();
    }

    /**
     * The main OS window — for reading and restoring placement ({@code screenX/screenY/outerWidth/outerHeight},
     * {@code setPosition}), typically persisted through {@code Settings}. The window's lifecycle stays this
     * class's business: don't close it through this handle.
     */
    public NativeWindow window() {
        return main.window;
    }

    /**
     * Window commands for an application-drawn title bar — minimize, maximize/restore, close — bound to the main
     * window. Hand this to a chrome widget ({@code TitleBar}); it is the only thing such a widget needs from the
     * host, which is what keeps it a widget rather than a piece of the application edge.
     *
     * <p>Meaningful whatever the window's decorations are: a window with a system title bar simply has two ways
     * to be minimized. What decides whether the GUI's own chrome is <em>drawn</em> is the {@link WindowConfig}
     * this app was constructed with.
     */
    public WindowControls controls() {
        return controls;
    }

    /**
     * Request an OS-level popup window showing {@code popupGui}'s tree. <b>Callable from any thread</b> — this
     * only enqueues; the main thread creates the actual window at the top of its next frame, which is the portable
     * contract (macOS requires window creation and event pumping on the main thread; Win32 binds a window to its
     * creating thread). The popup joins the existing frame loop — it never gets a loop or thread of its own — and
     * closes via its OS close button, releasing only its own resources.
     *
     * <p>Popups are <b>owned</b> by the main window: no taskbar button of their own (one icon for the whole
     * application), always above the main window, raised together with it when any window of the group is
     * activated, and minimized/destroyed with it. Ownership is not modality — the main window stays interactive.
     *
     * <p>Scaffold: modality is a follow-up. Input routing and programmatic close are not — a popup gets its own
     * input backend through {@code onCreated}, and the {@link #requestPopup(WindowConfig, Gui,
     * java.util.function.Consumer, Runnable)} overload hands over the popup's own {@link NativeWindow}.
     */
    public void requestPopup(String title, int width, int height, Gui popupGui) {
        requestPopup(title, width, height, popupGui, h -> { }, () -> { });
    }

    /**
     * As {@link #requestPopup(String, int, int, Gui)}, with the two seams a popup with real input needs:
     * {@code onCreated} receives the new window's OS handle on the main thread once the window exists (attach a
     * per-window input backend to it there), and {@code onClosed} runs on the main thread after the window is
     * torn down (release that backend there). Both windows then pump on the one loop — two OS windows, one GUI.
     */
    public void requestPopup(String title, int width, int height, Gui popupGui,
                             java.util.function.LongConsumer onCreated, Runnable onClosed) {
        requestPopup(WindowConfig.of(title, width, height), popupGui,
                window -> onCreated.accept(window.osHandle()), onClosed);
    }

    /**
     * The full popup request: a {@link WindowConfig} instead of a title and a size, and an {@code onCreated} that
     * receives the popup's own {@link NativeWindow} rather than just its handle.
     *
     * <p>Both halves exist for the same reason — a popup is a window the user moves and sizes, so an application
     * has to be able to put it back. The config carries the saved position ({@link WindowConfig#at}), so the
     * window is <em>created</em> where it was left rather than jumping there after appearing; the window handed
     * to {@code onCreated} is what reads the placement back out ({@code screenX/screenY/outerWidth/outerHeight})
     * to be saved again. It also carries {@link NativeWindow#requestClose()}, which is how a popup closes itself
     * — through the ordinary route, so the loop tears it down on its own terms and {@code onClosed} still runs.
     *
     * <p>The config's {@code owner} is ignored: a popup is owned by the main window by definition
     * ({@link Standing#SATELLITE}), and this substitutes the right handle when the window is created. A second
     * window that should <em>not</em> sit above the main one is not a popup — open it through
     * {@link #requestWindow} or {@link #window}, which stand beside it. Never call {@link NativeWindow#close()}
     * on the window — that destroys OS resources the loop is still presenting to.
     */
    public void requestPopup(WindowConfig config, Gui popupGui,
                             java.util.function.Consumer<NativeWindow> onCreated, Runnable onClosed) {
        requestWindow(WindowSpec.of(config, popupGui).onCreated(onCreated).onClosed(onClosed)
                .standing(Standing.SATELLITE));
    }

    /**
     * Open an anonymous window from a full {@link WindowSpec} — {@code requestPopup} with every seam declared,
     * including whether the window stands above the main one or beside it ({@link WindowSpec#standing}, which
     * defaults to beside).
     */
    public void requestWindow(WindowSpec spec) {
        post(() -> openWindow(spec, null));
    }

    /**
     * How input reaches windows the framework opens. Supply this once, at the application edge, and every window
     * opened from then on — {@link #window named windows}, dialogs, popups requested through
     * {@link #requestWindow} — has a backend attached at creation, pumped every frame, and released with the
     * window. Without it those windows draw and hear nothing.
     *
     * <p>Windows opened through the older {@link #requestPopup} seams keep attaching their own input in
     * {@code onCreated} and pumping it themselves; both work, and an application migrating can do it one window
     * at a time.
     */
    public GuiApp input(WindowInput.Factory factory) {
        this.inputs = factory == null ? WindowInput.Factory.NONE : factory;
        return this;
    }

    /**
     * The window known as {@code key}, registering {@code spec} the first time the name is used. Callable from
     * any thread, and cheap to call repeatedly: the second call returns the same handle and never builds a second
     * spec (which is why the spec arrives as a supplier — the tree behind it is built once, when the name is
     * first claimed).
     *
     * <p>This is the whole of "one window, however many times you ask for it":
     * {@snippet :
     * app.window("terminal", () -> WindowSpec.of(WindowConfig.of("Terminal", 720, 420), terminalGui)).show();
     * }
     * Bind that to a shortcut and it opens the terminal, or focuses the terminal that is already open.
     */
    public AppWindow window(String key, java.util.function.Supplier<WindowSpec> spec) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("window key must not be blank");
        }
        return named.computeIfAbsent(key, k -> new AppWindow(k, this, spec.get()));
    }

    /** The window known as {@code key}, if that name has been registered. */
    public java.util.Optional<AppWindow> window(String key) {
        return java.util.Optional.ofNullable(named.get(key));
    }

    /**
     * Be asked before the main window closes — the "you have unsaved changes" seam. The handler runs on the
     * handler executor with a {@link CloseRequest} it may answer at its leisure, from any thread; until it does,
     * the window stays open and fully live, which is what lets the answer come from a dialog. Passing
     * {@code null} removes the handler, and a close closes again.
     *
     * <p>Closing the main window is closing the application, so this is also how an application refuses to exit.
     * Other windows declare the same thing per window, through {@link WindowSpec#onCloseRequest}.
     */
    public GuiApp onCloseRequest(java.util.function.Consumer<CloseRequest> handler) {
        mainGate.handler(handler);
        return this;
    }

    /**
     * Make {@code window} the application's modal surface: every other window this application owns is disabled
     * at the OS level — it takes no pointer or keyboard input and cannot be activated — and dimmed, so the block
     * is visible as well as real. Passing {@code null} releases it.
     *
     * <p><b>Disabled, not merely ignored.</b> The window manager enforces this, which is what makes it behave
     * the way a user expects a modal dialog to: clicking the dead window flashes the dialog instead of doing
     * nothing, Alt+Tab still works, and the application never has to remember to drop events. The dimming is the
     * GUI's half — a scrim over each blocked tree, so the reason a window stopped responding is on screen.
     *
     * <p>Callable from any thread — like every other window command here, it is performed at the top of the next
     * frame. {@code Modals} is what applications normally use, and it calls this.
     */
    public GuiApp modalWindow(NativeWindow window) {
        post(() -> applyModal(window));
        return this;
    }

    /** Main thread: make {@code window} the only enabled window of this application (or null, none). */
    private void applyModal(NativeWindow window) {
        this.modal = window;
        main.window.setEnabled(window == null || window == main.window);
        applyScrim(mainGui, window != null && window != main.window);
        for (OpenWindow w : open) {
            boolean isModal = w.window.window == window;
            w.window.window.setEnabled(window == null || isModal);
            applyScrim(w.spec.gui(), window != null && !isModal);
        }
    }

    /** The colour the rest of the application is dimmed with while a modal is up; {@code null} draws no scrim. */
    public GuiApp modalDim(dev.vexelray.canvas.Color color) {
        this.modalDim = color;
        this.modalDimSet = true;
        return this;
    }

    /** Show or hide {@code gui}'s dim — installing it the first time a dialog blocks that tree. */
    private void applyScrim(Gui gui, boolean dimmed) {
        if (gui == null || (!dimmed && !scrims.containsKey(gui))) {
            return;   // a tree that has never been dimmed needs no scrim to un-dim
        }
        scrims.computeIfAbsent(gui, ModalScrim::install)
                .dim(dimmed, modalDimSet ? modalDim : gui.theme().color(dev.vexelray.gui.core.style.Role.SCRIM));
    }

    /** Enqueue work for the top of the next frame. The one way anything reaches the main thread from elsewhere. */
    void post(Runnable task) {
        tasks.add(task);
    }

    /**
     * Create a window for {@code spec} now, on the main thread: the OS window, its input backend, and its place
     * in the frame loop. {@code owner} is the named handle to keep in step, or null for an anonymous popup.
     */
    OpenWindow openWindow(WindowSpec spec, AppWindow owner) {
        // The spec's Standing decides whether this window is a satellite of the main window — above it always,
        // sharing its taskbar button, minimized and destroyed with it — or a peer with its own place in the
        // stack, which the main window can be brought in front of. Nothing after creation can change it: the
        // OS settles a window's standing from the owner it was created with. A satellite that goes away with
        // its owner arrives back here as its own pump reporting closed, the same path as its close button.
        GuiWindow w = new GuiWindow(platform, instance, device, atlas, noImage, text, measurer, spec.gui(),
                spec.standing().place(spec.config(), main.osHandle()));
        WindowInput input = inputs.attach(w.window, spec.gui());
        OpenWindow entry = new OpenWindow(w, input, spec, owner);
        open.add(entry);
        spec.onCreated().accept(w.window);
        if (modal != null) {
            // A window opened while a dialog is up must not be a way around it.
            w.window.setEnabled(w.window == modal);
            applyScrim(spec.gui(), w.window != modal);
        }
        return entry;
    }

    /**
     * The usable area of the monitor nearest the screen point {@code (x, y)} — the monitor's rectangle minus the
     * taskbar or dock. Empty where the platform cannot say.
     *
     * <p>What it is for: deciding whether persisted window bounds are still a place. A saved position is a
     * promise about a desktop that may have changed shape since — a monitor unplugged, a resolution lowered, a
     * laptop undocked — so an application restoring bounds asks this first and clamps with
     * {@link dev.vexelray.os.WorkArea#fit}. Passing the saved position finds the monitor that saved position was
     * on, or the nearest surviving one.
     *
     * <p>Static on purpose: the first window's bounds have to be clamped <em>before</em> there is a
     * {@link GuiApp} to ask, because they go into the {@link WindowConfig} it is constructed with.
     */
    public static java.util.Optional<dev.vexelray.os.WorkArea> workArea(int x, int y) {
        return NativePlatform.current().workArea(x, y);
    }

    /** Drive {@code gui} until the window closes (or {@code maxFrames} presented if positive). */
    public void run(Gui gui, int maxFrames) {
        run(gui, maxFrames, () -> { });
    }

    /**
     * Drive {@code gui}, running {@code beforeFrame} at the top of each frame — the app-edge hook for pumping input
     * onto the bus (e.g. {@code TactrollerInputBridge::pump}) before {@link Gui#frame} drains and dispatches it.
     *
     * <p>This loop is the one thread all windows share: each iteration pumps and presents the main window, then
     * materialises any pending {@link #requestPopup} calls, then pumps and presents each open popup. A popup that
     * was closed is torn down here (its window, surface, swapchain — never the shared device).
     */
    public void run(Gui gui, int maxFrames, Runnable beforeFrame) {
        main.gui = gui;
        this.mainGui = gui;
        // Map the GUI's desired cursor shape onto the OS window (I-beam over editable text, §8.3).
        gui.onCursorChange(shape -> main.window.setCursor(osCursor(shape)));
        int frame = 0;
        boolean running = true;
        while (running && (maxFrames <= 0 || frame < maxFrames)) {
            // Window creation, showing, hiding and closing all land here: posted from wherever they were asked
            // for, performed on the thread that is allowed to perform them.
            for (Runnable task; (task = tasks.poll()) != null; ) {
                task.run();
            }
            running = main.frame(beforeFrame) || mainGate.keepAlive(main.window, gui.handlers());
            open.removeIf(w -> {
                // Each window pumps its own input before its own frame: two OS windows, one loop, one GUI.
                if (w.window.frame(w.input::pump) || w.gate.keepAlive(w.window.window, w.spec.gui().handlers())) {
                    return false;
                }
                if (w.window.window == modal) {
                    // Directly, not posted: the application must be usable again in the same frame the dialog
                    // leaves, or it spends one frame with every window disabled and nothing modal to explain it.
                    applyModal(null);
                }
                w.release();   // this window's own resources only; the device and loop keep running
                return true;
            });
            frame++;
        }
        device.waitIdle();
        // Main window gone (or frame cap hit): the other windows' loop is gone with it, so close them too.
        for (OpenWindow w : open) {
            w.release();
        }
        open.clear();
    }

    public void run(Gui gui) {
        run(gui, 0);
    }

    @Override
    public void close() {
        device.waitIdle();
        for (OpenWindow w : open) {
            w.release();
        }
        open.clear();
        main.close();
        for (SampledColorTarget v : viewports) {
            v.close();
        }
        viewports.clear();
        atlas.close();
        noImage.close();
        device.close();
        instance.close();
    }

    // --- headless capture ---

    /** One {@link TextLayout} per face the atlas carries — index-aligned with {@code RetainedNode.font()}. */
    private static TextLayout[] faces(AtlasData data) {
        TextLayout[] faces = new TextLayout[data.faceCount()];
        for (int i = 0; i < faces.length; i++) {
            faces[i] = new TextLayout(data.face(i));
        }
        return faces;
    }

    /** Reconcile + lay out {@code gui} at {@code width}×{@code height}, render one frame, and write a PNG. */
    public static void capture(Gui gui, int width, int height, float bgR, float bgG, float bgB, String path)
            throws IOException {
        int[] atlasSize = new int[2];
        byte[] atlasRgba = loadAtlasRgba(atlasSize);
        TextLayout[] text = faces(AtlasData.loadFromResource(ATLAS_JSON));
        // Two frames, not one. Anything an application derives from its own measured box — a picture authored in
        // pixels is the clearest case — cannot exist during the first layout that produces that box: the observer
        // fires inside it, and the mutation it posts is applied by the next drain. A still image wants the settled
        // state rather than the instant before it, so the first frame is a warm-up and the second is the picture.
        gui.frame(width, height, measurer(text));
        RetainedNode root = gui.frame(width, height, measurer(text));
        Canvas canvas = new Canvas(width, height);
        canvas.begin();
        if (root != null) {
            TreeRenderer.emit(root, canvas, text, gui.theme());
        }
        float[] vertices = canvas.toVertexArray();
        int vertexCount = canvas.vertexCount();
        List<Canvas.Run> runs = canvas.runs();

        NativePlatform platform = NativePlatform.current();
        try (VulkanInstance instance = new VulkanInstance("vexelray-gui",
                platform.requiredVulkanInstanceExtensions())) {
            VulkanInstance.DeviceSelection sel = instance.selectGraphicsDevice()
                    .orElseThrow(() -> new IllegalStateException("no graphics device"));
            try (VulkanDevice device = new VulkanDevice(instance.handle(), sel);
                 VulkanRenderPass rp = new VulkanRenderPass(device, Vk.FORMAT_R8G8B8A8_UNORM,
                         Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                 AtlasTexture atlas = new AtlasTexture(device, atlasSize[0], atlasSize[1], atlasRgba);
                 AtlasTexture noImage = AtlasTexture.placeholder(device);
                 VertexBuffer vb = new VertexBuffer(device, vertices);
                 GraphicsPipeline pipeline = new GraphicsPipeline(device, rp.handle(), width, height,
                         CanvasShader.vertex().spirv(), "main", CanvasShader.fragment().spirv(), "main",
                         canvasConfig(atlas, noImage, false))) { // fixed viewport: offscreen, no resize
                // The same run list the windowed path walks, so a tree holding images captures to PNG exactly as
                // it presents — which is what makes the image kind checkable without a window.
                byte[] rgba = OffscreenDraw.toRgba(device, rp.handle(), pipeline, width, height, vb.handle(),
                        atlas.descriptorSet(), bind(runs, vertexCount, noImage), bgR, bgG, bgB, 1f);
                ImageIO.write(toImage(rgba, width, height), "PNG", new File(path));
            }
        }
    }

    // --- native-API glue ---

    /**
     * Map a requested {@link dev.vexelray.gui.core.input.CursorShape} onto the window API.
     *
     * <p><b>The engine offers only {@code ARROW} and {@code TEXT}</b>, so the hand shapes -- pointer over
     * anything clickable, open and closed hands over anything grabbable -- currently fall back to the arrow. The
     * rule that decides them is framework-side and fully exercised ({@code CursorRuleTest}); what is missing is
     * three cursor constants and their OS handles, which is an engine concern like E2-E4. Degrading to the arrow
     * is the right fallback: a wrong-looking cursor is a cosmetic loss, where guessing at a shape the platform
     * has no standard for would not be.
     */
    private static NativeWindow.Cursor osCursor(dev.vexelray.gui.core.input.CursorShape shape) {
        return shape == dev.vexelray.gui.core.input.CursorShape.TEXT
                ? NativeWindow.Cursor.TEXT
                : NativeWindow.Cursor.ARROW;
    }

    /**
     * Text intrinsic sizing over VexelRay's glyph layout: width = measured advance, height = line height.
     * Measures with the face the node renders with ({@code RetainedNode.font()}); the face-less methods keep
     * working against face 0, and out-of-range face indices degrade to face 0 the same way rendering does.
     */
    private static TextMeasurer measurer(TextLayout[] faces) {
        return new TextMeasurer() {
            private TextLayout tl(int font) {
                return faces[font <= 0 ? 0 : Math.min(font, faces.length - 1)];
            }

            private GlyphLayout gl(int font) {
                return tl(font).glyphLayout();
            }

            @Override
            public float intrinsic(RetainedNode node, Axis axis, float textSizePx) {
                GlyphLayout gl = gl(node.font());
                String s = node.textString() == null ? "" : node.textString();
                return axis == Axis.HORIZONTAL
                        ? gl.measure(s, textSizePx)
                        : gl.ascent(textSizePx) + gl.descent(textSizePx);
            }

            @Override
            public int offsetAt(String s, float localX, float textSizePx) {
                return offsetAt(0, s, localX, textSizePx);
            }

            @Override
            public int offsetAt(int font, String s, float localX, float textSizePx) {
                if (s == null || s.isEmpty() || localX <= 0f) {
                    return 0;
                }
                GlyphLayout gl = gl(font);
                // Walk character boundaries, returning the offset whose caret-x is nearest localX. O(n^2) over the
                // prefix measures, but a single line is short; a prefix-advance scan is a later optimisation.
                float prev = 0f;
                for (int i = 1; i <= s.length(); i++) {
                    float w = gl.measure(s.substring(0, i), textSizePx);
                    if (localX < (prev + w) * 0.5f) {
                        return i - 1;
                    }
                    prev = w;
                }
                return s.length();
            }

            @Override
            public List<TextLayout.LineSpan> lineSpans(String s, float wrapWidth, float textSizePx) {
                return lineSpans(0, s, wrapWidth, textSizePx);
            }

            @Override
            public List<TextLayout.LineSpan> lineSpans(int font, String s, float wrapWidth, float textSizePx) {
                // The engine already owns offset-aware line breaking; wrapWidth <= 0 disables wrapping there,
                // so a single-line field falls through to "split on '\n' only".
                return tl(font).breakLineSpans(s == null ? "" : s, textSizePx, wrapWidth,
                        TextLayout.WrapMode.WORD_CHAR);
            }

            @Override
            public float[] caretAdvances(String s, float textSizePx) {
                return caretAdvances(0, s, textSizePx);
            }

            @Override
            public float[] caretAdvances(int font, String s, float textSizePx) {
                if (s == null) {
                    return new float[] {0f};
                }
                GlyphLayout gl = gl(font);
                // Cumulative advance at each character boundary (xs[0] = 0). Uses the glyph layout's per-codepoint
                // advance so this is O(n), not O(n^2).
                float[] xs = new float[s.length() + 1];
                float x = 0f;
                int i = 0;
                while (i < s.length()) {
                    int cp = s.codePointAt(i);
                    int next = i + Character.charCount(cp);
                    x += gl.advance(cp, textSizePx);
                    // Fill the boundary for each char index the codepoint spans (surrogate pairs share an advance).
                    for (int j = i + 1; j <= next; j++) {
                        xs[j] = x;
                    }
                    i = next;
                }
                return xs;
            }
        };
    }

    /**
     * Resolve each {@link Canvas.Run}'s opaque image handle to the descriptor set to bind, clipped to the vertices
     * that actually reached the buffer. Shared by the windowed and capture paths, so the two cannot disagree about
     * which image a span draws with.
     *
     * <p>The clip is the caller's truncation and exists for the same reason it does: a run pointing past the end of
     * the buffer is a draw of undefined memory, which is a worse answer to "this frame is too big" than a missing
     * tail. A handle that is not a {@link SampledImage} — or a null one, which is every shape and glyph — gets the
     * placeholder, so a tree carrying something unexpected shows a blank box rather than failing the frame.
     */
    static List<WindowedPresenter.Run> bind(List<Canvas.Run> runs, int vertexCount, SampledImage noImage) {
        List<WindowedPresenter.Run> out = new ArrayList<>(runs.size());
        for (Canvas.Run r : runs) {
            if (r.firstVertex() >= vertexCount) {
                break;   // runs are in submission order, so the first one past the end ends the frame
            }
            int count = Math.min(r.vertexCount(), vertexCount - r.firstVertex());
            long set = r.image() instanceof SampledImage img ? img.descriptorSet() : noImage.descriptorSet();
            out.add(new WindowedPresenter.Run(set, r.firstVertex(), count));
        }
        return out;
    }

    /**
     * The canvas pipeline's layout: the glyph atlas at set 0 and an image at set 1.
     *
     * <p>Both sets are declared whether or not this frame draws an image, because a pipeline layout is fixed at
     * build time and a set the pipeline declares must have something bound. {@code image} is only read for its
     * <em>layout</em> here — every image binds against the same one-sampler shape, so the placeholder's layout
     * describes a marched viewport just as well as it describes itself.
     */
    static GraphicsPipeline.Config canvasConfig(AtlasTexture atlas, SampledImage image, boolean dynamicViewport) {
        List<GraphicsPipeline.VertexAttribute> attrs = new ArrayList<>();
        for (CanvasVertex.Attr a : CanvasVertex.ATTRIBUTES) {
            attrs.add(new GraphicsPipeline.VertexAttribute(a.location(), vkFormat(a.components()), a.offset()));
        }
        return new GraphicsPipeline.Config(CanvasVertex.STRIDE_BYTES, attrs,
                new long[]{atlas.descriptorSetLayout(), image.descriptorSetLayout()}, true,
                Vk.SHADER_STAGE_FRAGMENT_BIT, 0, dynamicViewport);
    }

    private static int vkFormat(int components) {
        return switch (components) {
            case 1 -> Vk.FORMAT_R32_SFLOAT;
            case 2 -> Vk.FORMAT_R32G32_SFLOAT;
            case 4 -> Vk.FORMAT_R32G32B32A32_SFLOAT;
            default -> throw new IllegalArgumentException("unsupported component count " + components);
        };
    }

    private static byte[] loadAtlasRgba(int[] sizeOut) {
        try (InputStream in = GuiApp.class.getResourceAsStream(ATLAS_PNG)) {
            if (in == null) {
                throw new IllegalStateException("atlas PNG not found on classpath: " + ATLAS_PNG);
            }
            BufferedImage img = ImageIO.read(in);
            int w = img.getWidth();
            int h = img.getHeight();
            sizeOut[0] = w;
            sizeOut[1] = h;
            byte[] rgba = new byte[w * h * 4];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = img.getRGB(x, y);
                    int i = (y * w + x) * 4;
                    rgba[i] = (byte) ((argb >> 16) & 0xFF);
                    rgba[i + 1] = (byte) ((argb >> 8) & 0xFF);
                    rgba[i + 2] = (byte) (argb & 0xFF);
                    rgba[i + 3] = (byte) ((argb >> 24) & 0xFF);
                }
            }
            return rgba;
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading atlas PNG " + ATLAS_PNG, e);
        }
    }

    private static BufferedImage toImage(byte[] rgba, int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 4;
                image.setRGB(x, y, ((rgba[i + 3] & 0xFF) << 24) | ((rgba[i] & 0xFF) << 16)
                        | ((rgba[i + 1] & 0xFF) << 8) | (rgba[i + 2] & 0xFF));
            }
        }
        return image;
    }

}
