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
import sibarum.probe.Lane;
import sibarum.probe.Probe;
import sibarum.probe.Zone;
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
import dev.vexelray.vulkan.present.StorageBuffer;
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
    private final List<StorageBuffer> buffers = new ArrayList<>();
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

    // The wake/budget/frame chain traces through Probe on the FRAME lane; see the note in Gui for why the
    // bespoke flag that used to live here had to go.

    /** Trees already given a wake, by identity. Main thread only. See {@link #wireAllWakes}. */
    private final java.util.Set<Gui> wired =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** How long {@link #run} may block after presenting. See {@link #pacing}. Default: never block. */
    private java.util.function.LongSupplier pacing = () -> 0L;

    /** Longest the loop will park while focused. See {@link #idleRefresh}. */
    private long maxIdleNanos = 200_000_000L;        // 5 Hz
    /** Shortest gap between presented frames. See {@link #maxFrameRate}. */
    private long minFrameNanos = 0L;                 // uncapped

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

    /** The name the main window answers to in an {@link dev.vexelray.gui.core.nav.Address}. */
    private volatile String mainKey = "main";

    /** Listening for navigation requests, so a destination in a closed window can still be reached. */
    private sibarum.atchung.Subscription navSub;

    public GuiApp(String title, int width, int height) {
        this(WindowConfig.of(title, width, height));
    }

    /**
     * As {@link #GuiApp(String, int, int)}, but with the full window request — the overload an app restoring
     * persisted bounds uses, so the window is <em>created</em> where it was last left rather than jumping there
     * after appearing (see {@code Settings}).
     */
    public GuiApp(WindowConfig config) {
        this(config, null);
    }

    /**
     * As {@link #GuiApp(WindowConfig)}, but around a window somebody else made.
     *
     * <p>For a host that needs the loop to be real and the window not to be: a harness driving a whole
     * application under test, where everything the GPU touches has to behave exactly as it does in
     * production and only the four methods the frame loop uses — pump, wait, wake, focus — are the
     * test's to control. Passing a wrapper around a genuine window keeps the swapchain, the presenter
     * and every pixel honest, which a substitute renderer would not.
     *
     * <p>{@code config} is still read for size, decorations and the rest; only the creation is skipped.
     * A {@code null} window means create one, which is the ordinary path.
     */
    public GuiApp(WindowConfig config, NativeWindow window) {
        this.platform = NativePlatform.current();
        this.instance = new VulkanInstance(config.title(), platform.requiredVulkanInstanceExtensions());

        // Device selection needs a surface to prove present support, so the main window is created first and its
        // surface probed; popups then reuse the same device (scaffold caveat: same-queue present support for
        // sibling surfaces holds on every real platform, but is asserted per-surface only for this first one).
        NativeWindow probe = window != null ? window : platform.createWindow(config);
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
        this.controls = controlsFor(main);
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

    /**
     * A storage buffer of {@code floats} floats on this application's device, bound at set 0 / {@code binding} —
     * what a shader reads when its data is too big, or too changeable, to be push constants.
     *
     * <p>Here for exactly the reason {@link #viewport} is: the device is deliberately not public, and a buffer
     * allocated on a different one yields a descriptor this application's pipeline cannot bind. Asking the
     * application for a size and a binding keeps that impossible.
     *
     * <p>The motivating case is a ray-marched viewport whose geometry is data rather than code. With the scene
     * compiled into the shader, a new scene is new SPIR-V and a new pipeline — and building one was measured at
     * five seconds, on the frame loop, which every window in the application shares. Reading the geometry from
     * one of these makes the shader the same bytes whatever it draws, so the pipeline is built once.
     *
     * <p>Closed with the application, like a viewport, so an app that keeps one for the session need not track
     * it. Unlike a viewport it is <b>not</b> resized for you and cannot be: the pipeline was built against its
     * descriptor set layout, so a bigger buffer is a new pipeline. Size it for the worst case up front.
     */
    public StorageBuffer storage(int floats, int binding) {
        StorageBuffer buffer = new StorageBuffer(device, Math.max(1, floats), binding);
        buffers.add(buffer);
        return buffer;
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
     * <p>The config's {@code owner} is ignored: a popup is an owned window by definition
     * ({@link Standing#SATELLITE}), and this substitutes the right handle when the window is created. That
     * handle is the main window's. A popup belonging to some <em>other</em> window of this application — a
     * second window's own tool window — has to say so, or it joins the main window's owner group and leaves the
     * window it belongs to underneath: open it through {@link #requestWindow} with
     * {@link WindowSpec#belongingTo}. A second window that should not sit above anything is not a popup at all —
     * {@link #requestWindow} or {@link #window} stand beside. Never call {@link NativeWindow#close()}
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
        return named.computeIfAbsent(key, k -> {
            WindowSpec built = spec.get();
            // The tree learns the name its window is known by, which is what lets an Address that names a window
            // be told apart from one meant for somebody else on the same bus.
            built.gui().windowKey(k);
            return new AppWindow(k, this, built);
        });
    }

    /**
     * The name the main window answers to when an {@link dev.vexelray.gui.core.nav.Address} names a window —
     * {@code "main"} unless this says otherwise. The other windows are named where they are registered
     * ({@link #window(String, java.util.function.Supplier)}); the main one has nowhere else to say it.
     */
    public GuiApp mainWindow(String key) {
        this.mainKey = key == null || key.isBlank() ? "main" : key;
        if (mainGui != null) {
            mainGui.windowKey(mainKey);
        }
        return this;
    }

    /**
     * Carry out the window half of a navigation request: open and raise the window that owns the destination.
     *
     * <p>The split is deliberate and it is the whole reason this is here rather than in {@link Gui}. A tree can
     * reveal, scroll and focus, and does — but it cannot make its own window exist, and a link to a landmark in
     * a preferences window that is currently closed has to open it. So: <b>the application opens windows, the
     * tree navigates itself.</b> Both halves see the same published {@link dev.vexelray.gui.core.nav.Address},
     * neither has to tell the other it is done, and a window already showing is simply raised — {@code show()}
     * being idempotent is what makes that safe to do on every request.
     *
     * <p>An address that names no window asks nothing of this: it means "wherever this reaches", and whichever
     * open tree has the landmark answers it.
     */
    private void route(dev.vexelray.gui.core.nav.Address address) {
        if (!address.windowNamed() || address.window().equals(mainKey)) {
            return;   // the main window is already open; nothing to do but let its tree walk
        }
        AppWindow target = named.get(address.window());
        if (target != null) {
            target.show();
        }
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

    /**
     * Enqueue work for the top of the next frame. The one way anything reaches the main thread from
     * elsewhere — opening a window, showing, hiding, closing one.
     *
     * <p>And therefore a channel of change in its own right, which is the part that was missing. This
     * queue is drained at the <b>top</b> of an iteration, so work posted at any point after that —
     * including from the host's own {@code beforeFrame} hook, which is where an application drains its
     * own queues — is owed the <em>next</em> frame. Under a loop that redrew unconditionally that frame
     * always came. Under one that parks it does not, and the request waits indefinitely for an
     * unrelated event.
     *
     * <p>It is the last channel to show itself because it is the narrowest: only window operations pass
     * through here, so everything else about an application keeps working and one menu item does
     * nothing. {@link #run} independently refuses to park while this queue is non-empty, which covers
     * the same-thread case exactly — a task posted during {@code beforeFrame} is visible by the time the
     * budget is read — and the wake covers every other thread.
     *
     * <h2>Public, because applications were writing it themselves</h2>
     *
     * An application whose handlers run off the GUI thread needs somewhere to put structural work, and
     * with this hidden every one of them grew its own: a {@code ConcurrentLinkedQueue<Runnable>} drained
     * from the {@code beforeFrame} hook. Three of them, in two applications, all the same shape.
     *
     * <p>They are not equivalent, and the difference costs a frame per step. This queue is drained
     * <b>to exhaustion at the top of an iteration</b>, so a task posted <em>by</em> a task — which is
     * what opening a window from a request looks like — runs in the same frame. A queue drained from
     * {@code beforeFrame} runs mid-frame, so the nested post it makes lands here and waits for the next
     * iteration. Chain three such steps and the operation takes three frames, which at 140 fps was
     * invisible and against a loop that parks is something you can watch happen.
     *
     * <p>So: structural work belongs here. A private queue is right only for work that must run at a
     * particular point in the host's own frame hook, and that is rarer than it looks.
     */
    public void post(Runnable task) {
        tasks.add(task);
        postWake();
    }

    /**
     * Create a window for {@code spec} now, on the main thread: the OS window, its input backend, and its place
     * in the frame loop. {@code owner} is the named handle to keep in step, or null for an anonymous popup.
     */
    /**
     * Working controls for one of this application's windows — every command, including a real
     * {@link WindowControls#capture}.
     *
     * <p><b>Made here because only here can it be made.</b> {@code WindowControls.of(NativeWindow)} cannot
     * capture: the pixels come from that window's own render bundle, which the host owns and a native window
     * knows nothing about. So a title bar that minted its own controls from the window handed to
     * {@code onCreated} got a working minimize, maximize and close and a screenshot that silently did nothing —
     * on every window but the main one. Every window this application opens is handed these instead.
     *
     * <p>The capture posts to the frame loop rather than running here: everything {@code GuiWindow.capture}
     * touches is Vulkan, and a caption button is clicked on the GUI thread, not the main one.
     */
    private WindowControls controlsFor(GuiWindow window) {
        return WindowControls.of(window.window, path -> post(() -> {
            try {
                window.capture(path);
            } catch (java.io.IOException e) {
                // A screenshot that cannot be written is not a reason to take the application down mid-frame.
                // Saying so once, with the path, is: the instrument is for troubleshooting, and an instrument
                // that fails silently is the thing being troubleshot.
                System.err.println("vexelray-gui: could not write capture to " + path + ": " + e.getMessage());
            }
        }));
    }

    OpenWindow openWindow(WindowSpec spec, AppWindow owner) {
        // The spec's Standing decides whether this window is a satellite of the main window — above it always,
        // sharing its taskbar button, minimized and destroyed with it — or a peer with its own place in the
        // stack, which the main window can be brought in front of. Nothing after creation can change it: the
        // OS settles a window's standing from the owner it was created with. A satellite that goes away with
        // its owner arrives back here as its own pump reporting closed, the same path as its close button.
        wireWake(spec.gui());
        GuiWindow w = new GuiWindow(platform, instance, device, atlas, noImage, text, measurer, spec.gui(),
                spec.standing().place(spec.config(), anchorHandle(spec)));
        WindowInput input = inputs.attach(w.window, spec.gui());
        OpenWindow entry = new OpenWindow(w, input, spec, owner);
        open.add(entry);
        spec.onCreated().accept(w.window);
        // The controls this window can actually be commanded by, capture included. After onCreated, so a bar
        // that also listens there is already built by the time it is pointed at the window.
        spec.onControls().accept(controlsFor(w));
        if (modal != null) {
            // A window opened while a dialog is up must not be a way around it.
            w.window.setEnabled(w.window == modal);
            applyScrim(spec.gui(), w.window != modal);
        }
        return entry;
    }

    /**
     * The OS handle a new window's {@link Standing} is measured from: the anchor the spec named, or the main
     * window when it named none.
     *
     * <p>The fallback also covers an anchor that is not open. That is not a failure to report: ownership is
     * fixed at creation and there is nothing to be owned by, so the choice is between an unanchored window and
     * no window, and a tool window is normally opened from the window it belongs to anyway.
     */
    private long anchorHandle(WindowSpec spec) {
        AppWindow anchor = spec.anchor();
        NativeWindow window = anchor == null ? null : anchor.window();
        return window == null ? main.osHandle() : window.osHandle();
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

    /**
     * How long the loop may block after presenting a frame, in nanoseconds — {@link Long#MAX_VALUE} to
     * block until an event arrives, {@code 0} (the default) to present again at once.
     *
     * <p><b>Render on demand, without this module knowing what a clock is.</b> A loop that presents
     * unconditionally redraws a completely still window at whatever rate the presenter allows, which is
     * a core spent on nothing. Something has to say when the next frame is actually due, and that
     * something is the application's animation runtime — so the seam is a JDK type rather than a
     * dependency, the same way a widget declares its timing need as a {@code DoubleConsumer} and stays
     * clock-free.
     *
     * <pre>{@code
     * app.pacing(() -> krono.kron().sleepTimeout().nanos());
     * krono.kron().onWork(app::postWake);          // and this, or a sleeping loop never wakes
     * }</pre>
     *
     * <p>Two things the supplier must get right, neither of which this method can check:
     *
     * <ul>
     *   <li><b>Return the minimum over every clock in the process</b>, not the main window's. One loop
     *       serves every window, so a hosted window with an animation of its own is starved by a
     *       supplier that only consults the main one — and it presents as a broken animation rather
     *       than as a wrong loop.</li>
     *   <li><b>Zero while anything is animating.</b> That is what keeps this free: the loop free-runs
     *       during motion exactly as it does now, and blocks only when there is provably nothing to
     *       draw. A supplier that returns a frame period instead is a frame-rate cap, which is a
     *       different feature with different failure modes.</li>
     * </ul>
     *
     * <p>Wired without {@link #postWake} this is safe but pointless on any platform whose
     * {@link NativeWindow#waitEvents} actually blocks; the animation runtime should refuse to report an
     * indefinite budget until a wake exists. Wired on a platform with no {@code waitEvents} it does
     * nothing at all, which is the intended fallback rather than a failure.
     *
     * <p><b>Do not set this on a run with a frame cap.</b> {@code run(gui, maxFrames)} is a scripted
     * run — a capture, a bounded check — and blocking makes N frames of a still window take forever
     * rather than N presents. The two flags live together in the caller, so the caller is what decides;
     * this method deliberately does not second-guess it, because a {@code pacing} that silently stopped
     * applying under some other argument would be worse than one that is documented not to mix.
     */
    public GuiApp pacing(java.util.function.LongSupplier nanosUntilNextFrame) {
        this.pacing = java.util.Objects.requireNonNull(nanosUntilNextFrame, "nanosUntilNextFrame");
        return this;
    }

    /**
     * End a {@link #pacing} block early, from any thread.
     *
     * <p>The other half of {@code pacing}, and the half whose absence is a hang rather than a cost: a
     * loop told it may block indefinitely has only OS input to end that block, and a worker thread's
     * mutation is not OS input. Hand this to whatever knows that work arrived.
     */
    /**
     * The longest this loop will park while its window has focus. {@code 0} to park indefinitely.
     *
     * <p><b>The bound that makes everything else an optimisation.</b> Render on demand is only correct
     * if every source of change wakes the loop, and that is a promise about code nobody has written
     * yet: the next queue someone adds, drained once per frame and announcing nothing, silently brings
     * back a window that ignores a click. There is no way to test for the absence of a wake, and the
     * symptom — occasionally unresponsive, fine again as soon as the pointer moves — points nowhere
     * near its cause.
     *
     * <p>So the loop refuses to park longer than this. A missing wake then costs <em>latency</em>
     * instead of a hang, which is a different kind of defect: bounded, uniform, and survivable. The
     * default of 5 Hz costs five wakes a second that mostly find nothing to do, against the 140 an
     * unconditional loop was spending, and buys the guarantee that the UI always comes back.
     *
     * <p>It is a floor, not a frame rate. Everything that <em>does</em> wake the loop still gets its
     * frame immediately, so the common paths stay at zero latency and this is only ever the worst case.
     *
     * <p>Focus is what keeps it cheap: a window nobody is looking at parks indefinitely, so the floor
     * is paid only where it can be perceived.
     */
    public GuiApp idleRefresh(long maxIdleNanos) {
        this.maxIdleNanos = maxIdleNanos <= 0 ? Long.MAX_VALUE : maxIdleNanos;
        return this;
    }

    /**
     * The shortest gap between presented frames — a ceiling on how fast this loop will draw.
     *
     * <p>The other half of the budget. {@link #pacing} reports zero while anything is animating, which
     * means "as fast as you can", and on a presenter that does not block that is 140 fps to show a
     * 60 Hz display. This bounds it without involving the presenter.
     *
     * <p>Costs nothing in input latency: the wait ends early on OS input regardless, so this limits
     * only how often the loop draws of its own accord.
     */
    public GuiApp maxFrameRate(long minFrameNanos) {
        this.minFrameNanos = Math.max(0L, minFrameNanos);
        return this;
    }

    public void postWake() {
        if (Probe.ON) {
            Probe.mark(Lane.FRAME, "wake.post", "nudging the OS message queue");
        }
        main.window.postWake();
    }

    /**
     * Let one {@link Gui} end a {@link #pacing} block, so a change in it earns a frame.
     *
     * <p>Done here, for every tree this application drives, because <b>an application has more trees
     * than it has main windows</b> and the ones it forgets are exactly the ones that break. A file
     * navigator, a history palette, a preview — each is a {@code Gui} of its own, and each is where a
     * user does the clicking that appears to do nothing. Left to the application this is a line to
     * repeat per window, correct on the window under test and missing on the one being used.
     *
     * <p>All of them wake the <em>main</em> window, which is not a simplification: {@code waitEvents}
     * blocks on the loop thread's message queue, and every window on this loop shares it, so one nudge
     * ends the block whichever tree asked for it.
     */
    private void wireWake(Gui gui) {
        if (gui != null && wired.add(gui)) {
            gui.onWork(this::postWake);
        }
    }

    /**
     * Ensure every tree this loop is about to present can wake it. Called each iteration.
     *
     * <p>Wiring at window creation was not enough, and the reason is worth keeping: a tree is not
     * always handed over at the moment it starts being drawn. A palette built at startup and opened
     * later, a window recreated after a close, a tree adopted by a path that did not exist when this
     * was written — each is a chance to be presenting something that cannot ask for a frame, and the
     * symptom is a window that ignores clicks, which points nowhere near here.
     *
     * <p>So the invariant is checked rather than established once: <b>if it is being presented, it can
     * wake the loop.</b> An identity set makes the steady state a hash lookup per window per frame, and
     * a tree that is not presented needs no frame and is therefore correct to leave alone.
     */
    /**
     * Whether any window of this application has focus — this loop serves all of them, so any one of
     * them being looked at means the loop is being looked at.
     */
    private boolean isFocused() {
        if (main.window.isFocused()) {
            return true;
        }
        for (OpenWindow w : open) {
            if (w.window.window.isFocused()) {
                return true;
            }
        }
        return false;
    }

    private void wireAllWakes() {
        wireWake(mainGui);
        for (OpenWindow w : open) {
            wireWake(w.spec.gui());
        }
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
        gui.windowKey(mainKey);
        // Navigation requests reach the loop here, on the main thread, because the only thing this half of
        // navigation does is open windows — and creating a window belongs to the main thread on every platform.
        this.navSub = gui.bus().subscribeAsync(
                dev.vexelray.gui.core.nav.NavTopics.GO, this::route, this::post);
        wireWake(gui);
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
            // After the tasks, because opening a window is one of them: a tree that arrived this
            // iteration is presented this iteration, so it must be able to ask for the next one.
            wireAllWakes();
            // One span per loop iteration, and it is the root of the whole report: every other span in every
            // other lane nests inside this one, so its self time is the loop's own overhead and its children
            // are where a frame actually went. The wait below is deliberately outside it - a loop parked on an
            // empty event queue is idle, not slow, and counting the park as frame time would make a perfectly
            // healthy render-on-demand application look like the worst offender in the table.
            try (Zone frameZone = Probe.zone(Lane.FRAME, "frame")) {
                running = main.frame(beforeFrame) || mainGate.keepAlive(main.window, gui.handlers());
            }
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
            if (running) {
                // After presenting, never before: the frame the application just asked for is not the
                // one to make it wait for. One call covers every window, because the wait is on this
                // thread's message queue rather than on any one window, and every window on this loop
                // shares that queue.
                // Never park on a queue that is already holding something: a window operation posted
                // after this iteration's drain is owed the next frame, and it is the only one here that
                // can say so.
                long budget = tasks.isEmpty() ? pacing.getAsLong() : 0L;
                // Then the two bounds. The floor applies only while someone is looking: an unfocused
                // window parks on whatever the application asked for, up to forever.
                if (isFocused()) {
                    budget = Math.min(budget, maxIdleNanos);
                }
                // The ceiling applies always. A zero budget means "immediately", which on a presenter
                // that does not block is as fast as the machine goes; this is what stops that.
                budget = Math.max(budget, minFrameNanos);
                // The heartbeat, and the thing that makes a gap in the log readable (docs/automation.md §4).
                //
                // A run is read by sorting on time and looking for long stretches with no frame in them. That
                // only distinguishes a stall from a nap if two things are recorded unconditionally: that a frame
                // happened, and that the loop then parked *and for how long it was allowed to*. This loop parks
                // indefinitely on an unfocused window by design, so without the budget a thirty-second doze and
                // a thirty-second hang are the same silence. With it the rule is mechanical: a gap covered by
                // the preceding park is expected, and a gap that is not is a stall whose suspect is the row
                // above it.
                if (Probe.ON) {
                    Probe.mark(Lane.FRAME, "frame.present", "#" + frame);
                    Probe.mark(Lane.FRAME, "loop.park",
                            budget == Long.MAX_VALUE ? "forever" : budget / 1_000_000 + "ms");
                }
                if (budget > 0) {
                    // Timed separately from the frame, and worth timing: this is where a well-behaved
                    // application spends most of its life, and a wait total that is small next to the run
                    // time is the signature of a loop that is spinning rather than sleeping.
                    try (Zone waitZone = Probe.idleZone(Lane.FRAME, "wait for events")) {
                        main.window.waitEvents(budget);
                    }
                }
            }
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
        if (navSub != null) {
            navSub.close();
            navSub = null;
        }
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
        for (StorageBuffer b : buffers) {
            b.close();
        }
        buffers.clear();
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

    static BufferedImage toImage(byte[] rgba, int w, int h) {
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
