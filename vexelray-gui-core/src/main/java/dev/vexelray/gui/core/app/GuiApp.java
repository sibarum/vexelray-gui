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
import dev.vexelray.gui.core.text.TextFaces;
import dev.vexelray.text.FontSet;
import dev.vexelray.vulkan.present.AtlasTexture;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.OffscreenDraw;
import dev.vexelray.vulkan.present.SampledColorTarget;
import dev.vexelray.diag.Diagnostics;
import dev.vexelray.vulkan.present.SampledImage;
import dev.vexelray.vulkan.present.StorageBuffer;
import dev.vexelray.vulkan.present.VertexBuffer;
import dev.vexelray.vulkan.present.VulkanRenderPass;
import dev.vexelray.vulkan.present.VulkanSwapchain;
import dev.vexelray.vulkan.present.WindowedPresenter;
import dev.supirvast.vulkan.ComputeSupport;
import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VkLoader;
import dev.supirvast.vulkan.VulkanDevice;
import dev.supirvast.vulkan.VulkanInstance;

import java.io.IOException;
import java.nio.file.Path;
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

    private static final sibarum.probe.Log LOG = sibarum.probe.Log.of("gui.app");

    /**
     * How far compute's own queue ({@link Compute#OWN_QUEUE}) defers to drawing's, which is at 1: a hint to the
     * driver of whose work to favour when both have some. Drivers may ignore it.
     */
    private static final float COMPUTE_PRIORITY = 0.5f;

    // Shared engine context — one GPU bring-up serves every window.
    private final NativePlatform platform;
    /**
     * What makes every OS window this application opens — see {@link #GuiApp(WindowConfig,
     * java.util.function.Function)}. The platform itself for an ordinary application; something else for a
     * host that needs its windows to be other than exactly what the platform would have made.
     */
    private final java.util.function.Function<WindowConfig, NativeWindow> windows;
    private final VulkanInstance instance;
    private final VulkanDevice device;
    /** The queue family compute submits to: a compute-only family of its own, or the family that draws. */
    private final int computeFamily;
    /**
     * What presents every window when not a Vulkan swapchain — DXGI on Windows, chosen by
     * {@code -Dvexelray.present} — or null for the swapchain path. Shared by all windows; closed after them.
     */
    private final dev.vexelray.vulkan.present.PresenterProvider.Backend presentBackend;
    /** Every face's glyph atlas, uploaded at startup, and the faces that measure them. Shared by every window. */
    private final FontAtlases fonts;
    private final AtlasTexture noImage;
    /** Render targets minted by {@link #viewport}, closed with the application. */
    private final List<SampledColorTarget> viewports = new ArrayList<>();
    /** Uploaded images minted by {@link #texture}, closed with the application. */
    private final List<AtlasTexture> textures = new ArrayList<>();
    /** Textures handed to {@link #release}, waiting for a frame that no longer names them. Main thread. */
    private final List<Retiring> retiring = new ArrayList<>();
    /** Loop iterations begun, so a released texture can be held for a whole one after it. Main thread. */
    private long iteration;
    private final List<StorageBuffer> buffers = new ArrayList<>();

    // The main window, plus every other window this application has open. All of them live on the main thread
    // and are pumped/presented by the one loop in run(); another window closing removes only its own bundle, the
    // main window closing ends the loop.
    private final GuiWindow main;
    private final WindowControls controls;
    private final List<OpenWindow> open = new ArrayList<>();

    /** The non-main windows as {@link #windows} reports them: {@code open} seen from threads that may not read it. */
    private final java.util.concurrent.CopyOnWriteArrayList<WindowView> listed =
            new java.util.concurrent.CopyOnWriteArrayList<>();

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
    private volatile Gui mainGui;

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
    /** Whether {@link #minFrameNanos} follows the display. See {@link #maxFrameRateToDisplay}. */
    private boolean ceilingFollowsDisplay;
    /** When the display's interval was last read, so it is read about once a second and never per frame. */
    private long displayReadAt;
    /** Set by {@link #postWake}, consumed by the park: how a wake is told apart from OS input. */
    private final java.util.concurrent.atomic.AtomicBoolean wakePosted = new java.util.concurrent.atomic.AtomicBoolean();

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
        this(config, null, null, TextFaces.standard());
    }

    /**
     * As {@link #GuiApp(WindowConfig)}, drawing text in {@code fonts} — the set the application's own build baked
     * with vexelray-msdf-maven-plugin's {@code <families>}, read with {@link FontSet#fromClassPath}, and given its
     * fallbacks with {@link FontSet#withFallback}. Every face in it is uploaded now, before the first window is
     * shown (docs/plans/font-families.md §1); the other constructors use vexelray-text's {@code sans} and
     * {@code mono}, each falling back to the other.
     */
    public GuiApp(WindowConfig config, FontSet fonts) {
        this(config, null, null, new TextFaces(fonts));
    }

    /**
     * As {@link #GuiApp(WindowConfig)}, but around a window somebody else made.
     *
     * <p><b>Covers the main window only</b>, which is why {@link #GuiApp(WindowConfig,
     * java.util.function.Function)} stands beside it. An application opens more windows than the one it was
     * constructed with — every popup, named window and modal dialog is created <em>here</em>, from a
     * {@link WindowConfig} the host never sees — and a host that hands over one window has said nothing about
     * those. They are created straight from the platform and shown, which for a test harness means a window
     * appearing mid-run and taking real focus and real input. Hand over the factory instead of the window and
     * every window this application opens comes from it.
     *
     * <p>{@code config} is still read for size, decorations and the rest; only the creation is skipped.
     * A {@code null} window means create one — though say that as {@link #GuiApp(WindowConfig)} rather than
     * as a bare {@code null} here, which no longer picks an overload on its own.
     */
    public GuiApp(WindowConfig config, NativeWindow window) {
        this(config, window, null, TextFaces.standard());
    }

    /**
     * As {@link #GuiApp(WindowConfig)}, with <b>every</b> window this application opens made by
     * {@code windows} — the main window at construction, and every popup, named window and dialog for the life
     * of the application.
     *
     * <p>For a host that needs the loop to be real and the windows not to be: a harness driving a whole
     * application under test, where everything the GPU touches has to behave exactly as it does in production
     * and only the four methods the frame loop uses — pump, wait, wake, focus — are the test's to control.
     * Such a host answers with a wrapper around a genuinely created window, asked for off screen
     * ({@link WindowConfig#hidden}), which keeps the swapchain, the presenter and every pixel honest where a
     * substitute renderer would not.
     *
     * <p><b>Why the whole creation and not a wrap around the result.</b> Two reasons, and each one alone is
     * enough. A window is on screen from the moment the platform makes it, so anything handed the finished
     * window is already too late to stop it being mapped — that is a property of the request, and the request
     * is what arrives here. And the windows that matter do not exist yet: the menu, the dialog, the tool window
     * opened by the interaction under test are all created from configs the host never wrote, so a seam that
     * only covers the window the host happened to make is one every later window walks straight past —
     * silently, and in exactly the tests that ask where the focus and the keyboard went.
     *
     * <p>Called on the main thread, in creation order, with the main window first. It is handed the fully
     * resolved config — ownership and placement already settled ({@link Standing}) — and must return a window
     * realising it; returning {@code null} is a bug. {@code NativePlatform.current()::createWindow} is exactly
     * what the other constructors pass, so a factory that adds nothing is an ordinary application.
     */
    public GuiApp(WindowConfig config, java.util.function.Function<WindowConfig, NativeWindow> windows) {
        this(config, null, windows, TextFaces.standard(), Compute.SHARED);
    }

    /**
     * As {@link #GuiApp(WindowConfig, java.util.function.Function)}, with the device made for {@code compute}:
     * {@link Compute#OWN_QUEUE} for an application whose simulation should run beside its drawing rather than in
     * line with it. {@code windows} may be null for the platform's own.
     */
    public GuiApp(WindowConfig config, java.util.function.Function<WindowConfig, NativeWindow> windows,
                  Compute compute) {
        this(config, null, windows, TextFaces.standard(), compute);
    }

    /** As the one that builds it, below, with compute sharing the queue that draws. */
    private GuiApp(WindowConfig config, NativeWindow adopted,
                   java.util.function.Function<WindowConfig, NativeWindow> windows, TextFaces faces) {
        this(config, adopted, windows, faces, Compute.SHARED);
    }

    /**
     * What an application's compute gets of its device's queues.
     *
     * <p>Asked for before the device exists, because a queue is taken when the device is made and never after.
     * {@link GuiApp.Gpu#computeFamily()} says what was got.
     */
    public enum Compute {
        /**
         * The queue that draws, which is the device every application had before there was a choice: compute is
         * ordered with drawing by the order of submission, and both submit from the main thread.
         */
        SHARED,
        /**
         * A queue of a compute-only family, where the device has one (most discrete GPUs do, for asynchronous
         * compute, and so does this machine's Intel GPU), at a lower priority than the queue that draws; and
         * timeline semaphores, so that drawing can wait for compute inside the GPU. Its work runs beside
         * drawing's, and is ordered against it only by what the two signal and wait for. On a device with no such
         * family, the same as {@link #SHARED}.
         */
        OWN_QUEUE
    }

    /**
     * The one that builds it: {@code adopted} is a main window the host already made (or null to make one), and
     * {@code windows} makes every window from here on (or null for the platform's own), {@code faces} is the
     * application's fonts, and {@code compute} what its compute gets of the device's queues.
     */
    private GuiApp(WindowConfig config, NativeWindow adopted,
                   java.util.function.Function<WindowConfig, NativeWindow> windows, TextFaces faces,
                   Compute compute) {
        this.platform = NativePlatform.current();
        this.windows = windows == null ? platform::createWindow : windows;
        this.instance = new VulkanInstance(config.title(), platform.requiredVulkanInstanceExtensions());

        // Device selection needs a surface to prove present support, so the main window is created first and its
        // surface probed; popups then reuse the same device (scaffold caveat: same-queue present support for
        // sibling surfaces holds on every real platform, but is asserted per-surface only for this first one).
        // A window the host handed over whole is already whatever it wanted it to be, so it is not remade.
        NativeWindow probe = adopted != null ? adopted : create(config);
        long probeSurface = probe.createVulkanSurface(instance.handleAddress(),
                VkLoader.getInstanceProcAddrPointer());
        VulkanInstance.DeviceSelection selection = instance.selectGraphicsPresentDevice(probeSurface)
                .orElseThrow(() -> new IllegalStateException("no graphics+present device"));
        // Made able to compute as well as draw, so that whatever the application simulates can live on this
        // device and be read by its pictures where it already is — a buffer cannot cross from one device to
        // another, and a second device for compute would make every frame a copy. The features are enabled only
        // where the driver supports them, so on a device that supports none this is the device it always was.
        ComputeSupport support = ComputeSupport.query(instance, selection.physicalDevice());
        VulkanDevice.Request request = VulkanDevice.Request.presentAndCompute(support);
        int ownFamily = compute == Compute.OWN_QUEUE
                ? instance.computeOnlyQueueFamily(selection.physicalDevice()) : -1;
        if (ownFamily >= 0) {
            request = request.withQueues(ownFamily, 1, COMPUTE_PRIORITY).withTimelineSemaphore();
            LOG.info("compute has a queue of its own, of family {}", ownFamily);
        } else if (compute == Compute.OWN_QUEUE) {
            LOG.info("{} has no compute-only queue family, so compute shares the queue that draws",
                    selection.deviceName());
        }

        // A presentation path other than the swapchain, if one was asked for and is here. It has to be chosen
        // before the device exists, because it needs device extensions; and anything failing on the way falls
        // back to the swapchain path, which works everywhere, rather than leaving the application without a window.
        dev.vexelray.vulkan.present.PresenterProvider provider = presenterProvider();
        VulkanDevice made = null;
        dev.vexelray.vulkan.present.PresenterProvider.Backend backend = null;
        if (provider != null) {
            try {
                VulkanDevice.Request withProvider = request.withExtensions(provider.deviceExtensions());
                if (provider.timelineSemaphore()) {
                    withProvider = withProvider.withTimelineSemaphore();
                }
                made = new VulkanDevice(instance.handle(), selection, withProvider);
                backend = provider.open(instance, made);
                LOG.info("presenting through {}", provider.name());
            } catch (RuntimeException e) {
                LOG.warn("presenting through {} is not possible here, so windows use a Vulkan swapchain: {}",
                        provider.name(), e.toString());
                backend = null;   // a device made with the extra extensions is still a good device: kept
            }
        }
        this.device = made != null ? made : new VulkanDevice(instance.handle(), selection, request);
        this.presentBackend = backend;
        this.computeFamily = ownFamily >= 0 ? ownFamily : device.queueFamilyIndex();
        if (backend != null) {
            // The provider presents to the window itself; a Vulkan surface left on it would only be in the way.
            instance.destroySurface(probeSurface);
            probeSurface = 0L;
        }

        // Every face's atlas, now: what the application holds on the GPU for text is fixed by what its build baked,
        // not by what it goes on to show (docs/plans/font-families.md §1).
        this.fonts = FontAtlases.upload(device, faces);
        // One placeholder for the device, not one per window: every window's canvas pipeline is built against the
        // same layout, and every span that draws no image binds this same 1x1 white.
        this.noImage = AtlasTexture.placeholder(device);

        this.main = new GuiWindow(platform, instance, device, fonts, noImage, null,
                probe, probeSurface, config.decorations(), presentBackend);
        this.controls = controlsFor(main);
    }

    /**
     * The presentation path {@code -Dvexelray.present} names, or null for the Vulkan swapchain. {@code vulkan}
     * (the default) asks for the swapchain; any other name is looked up among the {@code PresenterProvider}s on
     * the class path, and one that is absent or unsupported here is said and ignored.
     */
    private static dev.vexelray.vulkan.present.PresenterProvider presenterProvider() {
        String name = System.getProperty("vexelray.present", "vulkan");
        if (name.isBlank() || name.equals("vulkan")) {
            return null;
        }
        java.util.Optional<dev.vexelray.vulkan.present.PresenterProvider> found =
                dev.vexelray.vulkan.present.PresenterProvider.find(name);
        if (found.isEmpty()) {
            LOG.warn("-Dvexelray.present={} names no presentation path available here; using a Vulkan swapchain",
                    name);
            return null;
        }
        return found.get();
    }

    /**
     * Make one window through the host's factory. Every window this application opens comes from here and
     * nowhere else, which is the whole of the guarantee: there is one place to look to know that no window can
     * escape whatever the host decided windows are.
     */
    private NativeWindow create(WindowConfig config) {
        NativeWindow window = windows.apply(config);
        if (window == null) {
            throw new IllegalStateException("the window factory returned null; it must return a window");
        }
        return window;
    }

    /**
     * A render target of this size, on this application's device — what a <b>viewport</b> node shows.
     *
     * <p>Hand the result to {@code Node.image(...)} and render a scene into it (
     * {@code SampledColorTarget.renderInto}) before the frame that shows it. It is a {@code SampledImage}, so the
     * canvas draws it as a box that samples: it rounds, clips, fades and lays out like every other node.
     *
     * <p>This exists so that a target is always made on <em>this</em> application's device: one allocated on a
     * different device produces a descriptor set this application's pipeline cannot bind, which is a validation
     * error rather than a blank box. Asking the application for its size and nothing else keeps that impossible.
     * (The device itself is reachable through {@link #gpu()}, for the one thing that has to be on it — compute
     * that shares its buffers with what is drawn.) Targets made here are closed with the application, so an app that keeps
     * one per viewport for the session need not track them; one that resizes a viewport should
     * {@link SampledColorTarget#close()} the old target itself, after a frame that no longer names it.
     *
     * <p><b>Not resized for you.</b> A target has fixed pixels, and the node it lands in is laid out by flex — so
     * a viewport whose box has changed shape is being upscaled by the sampler until the application makes a new
     * target at the new size. Read the box from {@code Node.layout()} and decide; the framework will not guess,
     * because re-marching a scene is far too expensive to trigger from a resize it merely noticed.
     */
    public SampledColorTarget viewport(int width, int height) {
        return viewport(width, height, false);
    }

    /**
     * The same, for a viewport that more than one thing draws into.
     *
     * <p>A target with no depth composites what is drawn into it <b>in submission order</b>: whatever records
     * last wins every pixel it touches. That is right for a scene and then chrome over it, and wrong the moment
     * two things in the same scene are supposed to occlude each other — a marched curve and a grid drawn under
     * and around it, say, where the answer differs per pixel and depends on where the camera is.
     *
     * <p>With {@code depth}, a technique that declares it tests and writes a shared depth attachment, and the
     * picture is the same one a single renderer drawing everything at once would have produced. It costs a
     * second full-size image and a clear per pass, which is why it is asked for rather than assumed: a viewport
     * showing one fullscreen effect has nothing to interleave with and should not pay for either.
     */
    public SampledColorTarget viewport(int width, int height, boolean depth) {
        SampledColorTarget target =
                new SampledColorTarget(device, Math.max(1, width), Math.max(1, height), depth);
        // Pruned here rather than nowhere: an application that re-mints its viewport on every resize closes the
        // old one itself, and a list that kept it would grow by one per resize for the life of the window.
        viewports.removeIf(SampledColorTarget::isClosed);
        viewports.add(target);
        // A viewport's pixels change with no edit to any tree that shows it, and a loop that draws only on change
        // would go on showing the old ones. So every render into it asks the trees for a frame, and an application
        // drawing a scene never has to know the loop works this way (docs/plans/frame-loop.md, decided 1).
        target.onRendered(this::requestFrames);
        return target;
    }

    /** Ask every tree this application shows to draw its next frame. Main thread, like everything that renders. */
    private void requestFrames() {
        Gui main = mainGui;
        if (main != null) {
            main.requestFrame();
        }
        for (OpenWindow w : open) {
            w.spec.gui().requestFrame();
        }
    }

    /**
     * The fonts every window of this application measures and draws text in — for anything that sizes text itself
     * rather than letting the layout measure it, such as a typeset block, which must use the same faces or be the
     * wrong size.
     */
    public TextFaces faces() {
        return fonts.faces();
    }

    /**
     * A sampled texture of {@code width} x {@code height} straight (non-premultiplied) RGBA8 texels, on this
     * application's device — a decoded photograph, an icon sheet, an animation's frames — and the other thing a
     * {@code Node.image(...)} can be handed.
     *
     * <p>Here for exactly the reason {@link #viewport} is: the device is deliberately not public, so without this
     * an application could show a scene it marched but not a picture it loaded. Nothing about decoding belongs in
     * this framework — pixels arrive as bytes from wherever the application got them, and the only thing asked of
     * it is the one layout every sampler agrees on.
     *
     * <p><b>Uploaded once.</b> The texels are staged and copied at construction and there is no way to rewrite
     * them, which is a deliberate limit rather than a missing method: a texture that changed under a frame in
     * flight would tear. An animation is therefore <em>one</em> texture holding every frame, shown a cell at a
     * time with {@code Node.image(image, ImageRegion.cell(...))} — no per-frame upload, and no run boundary in the
     * vertex buffer because the bound handle never changes. Content that genuinely must change makes a new
     * texture, points the node at it, and hands the old one to {@link #release}.
     *
     * <p>Textures made here are closed with the application, so an app that loads its icons at startup need not
     * track them. One that browses — a new picture per selection — releases each as it stops showing it, or holds
     * every one it ever showed until exit.
     */
    public AtlasTexture texture(byte[] rgba, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("a texture needs a positive size: " + width + "x" + height);
        }
        long expected = (long) width * height * 4L;
        if (rgba == null || rgba.length != expected) {
            throw new IllegalArgumentException("a " + width + "x" + height + " RGBA8 texture is " + expected
                    + " bytes; was given " + (rgba == null ? "null" : rgba.length + " bytes"));
        }
        AtlasTexture texture = new AtlasTexture(device, width, height, rgba);
        textures.removeIf(AtlasTexture::isClosed);   // see viewport: replaced ones are closed by the application
        textures.add(texture);
        return texture;
    }

    /**
     * Give back a texture made by {@link #texture}, once no node needs it: it is closed when no frame can still
     * sample it. Main thread, like {@link #texture}.
     *
     * <p><b>Why the application cannot simply close it.</b> Closing is safe once the GPU has finished every frame
     * that drew the texture, and the frames in flight are behind the device this class keeps private on purpose —
     * so the one party that knows a texture is finished with is the one that cannot see when that becomes true.
     * Too early is a use-after-free on the GPU; never is a leak until exit. This is the half it can see.
     *
     * <p><b>The half that stays the application's</b> is the tree: call this after the last node showing the
     * texture has been given another image (or none). The texture is held for one whole loop iteration after the
     * call, so an edit posted just before it has been drained by every window, then closed after one device wait —
     * which on a loop with one frame in flight costs what the next frame's own fence wait would have. A texture
     * some window's tree still names is not closed: it is kept, said once in the log, and looked for again each
     * frame, because a dangling descriptor is the one outcome worse than a leak.
     *
     * <p>Releasing a texture twice, or one already closed, does nothing. One this application did not make is a
     * bug, and is refused here rather than at the frame that would have closed somebody else's.
     */
    public void release(AtlasTexture texture) {
        if (texture == null) {
            throw new IllegalArgumentException("release needs a texture; was given null");
        }
        if (texture.isClosed()) {
            return;
        }
        if (!textures.contains(texture)) {
            throw new IllegalArgumentException("only a texture made by this application's texture() can be "
                    + "released here; " + texture + " was not");
        }
        for (Retiring r : retiring) {
            if (r.texture == texture) {
                return;
            }
        }
        retiring.add(new Retiring(texture, iteration));
    }

    /** A texture handed to {@link #release}, and when. */
    private static final class Retiring {
        final AtlasTexture texture;
        final long releasedAt;
        /** Whether the log has already been told it is still shown, so a forgotten node is one line, not one a frame. */
        boolean warned;

        Retiring(AtlasTexture texture, long releasedAt) {
            this.texture = texture;
            this.releasedAt = releasedAt;
        }
    }

    /** Whether some released texture is still waiting out its iteration — the loop must not park on it. */
    private boolean retireDue() {
        for (int i = 0; i < retiring.size(); i++) {
            if (!aged(retiring.get(i))) {
                return true;
            }
        }
        return false;
    }

    /** Whether a whole loop iteration has begun and ended since {@code r} was released. */
    private boolean aged(Retiring r) {
        return r.releasedAt <= iteration - 2;
    }

    /**
     * Close every released texture that no frame can still sample. Top of a loop iteration, before any window
     * records, so nothing is drawing while the device waits.
     */
    private void retire() {
        boolean waited = false;
        for (int i = 0; i < retiring.size(); ) {
            Retiring r = retiring.get(i);
            if (!aged(r)) {
                i++;
                continue;
            }
            if (shown(r.texture)) {
                if (!r.warned) {
                    r.warned = true;
                    LOG.warn("a released texture is still shown by a node, so it stays open until no tree names "
                            + "it; give the node another image before releasing: " + r.texture);
                }
                i++;
                continue;
            }
            if (!waited) {
                device.waitIdle();
                waited = true;
            }
            r.texture.close();
            retiring.remove(i);
        }
    }

    /** Whether any window's tree, as its last update left it, still names {@code image}. */
    private boolean shown(SampledImage image) {
        if (main.shows(image)) {
            return true;
        }
        for (OpenWindow w : open) {
            if (w.window.shows(image)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code image} is on {@code node} or anywhere under it. */
    static boolean names(RetainedNode node, dev.vexelray.target.ImageHandle image) {
        if (node.image() == image) {
            return true;
        }
        List<RetainedNode> children = node.children;
        for (int i = 0; i < children.size(); i++) {
            if (names(children.get(i), image)) {
                return true;
            }
        }
        return false;
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
        buffers.removeIf(StorageBuffer::isClosed);   // see viewport: replaced ones are closed by the application
        buffers.add(buffer);
        return buffer;
    }

    /**
     * This application's Vulkan instance and device, for work that must run <b>on</b> them: a simulation that
     * computes on the device the application draws on, so that a picture of its state reads the buffers the
     * kernels wrote instead of a copy of them.
     *
     * <p>Everything made from these must be made on the main thread, which is where Vulkan stays, and closed
     * before the application is: the device is closed with it. Nothing made from a different device can be bound
     * by anything drawn here, which is why a compute context belongs on this one — {@code GpuContext.on} in
     * {@code vastir-tools} takes exactly this pair. The device was made with the compute features its GPU
     * supports. Unless the application asked for {@link Compute#OWN_QUEUE} and got it, its one queue is shared:
     * whatever submits to it from here does so from the main thread.
     */
    public Gpu gpu() {
        return new Gpu(instance, device, computeFamily);
    }

    /**
     * The instance and device behind an application; see {@link #gpu()}. A picture of a buffer somebody else owns
     * binds it with a {@code BoundStorageBuffer} on this device, and closes that itself, as it does its pipeline.
     *
     * @param computeFamily the queue family compute submits to ({@code GpuContext.on(instance, device, family)}):
     *                      a compute-only family the device was made with for {@link Compute#OWN_QUEUE}, or the
     *                      family that draws
     */
    public record Gpu(VulkanInstance instance, VulkanDevice device, int computeFamily) {

        /**
         * Whether compute has a queue of its own. If it has, nothing orders its work against drawing's but what
         * the two signal and wait for: a buffer compute writes while a frame still reads it is torn.
         */
        public boolean ownQueue() {
            return computeFamily != device.queueFamilyIndex();
        }
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
     * Do {@code work} on the offload lane and hand what it produced back here, on the GUI thread.
     *
     * <h2>Public for the same reason {@link #post} is</h2>
     *
     * That method was made public because applications were each growing a queue of their own. They have
     * since moved up a level and are growing the <em>round trip</em>: submit to a pool, catch what it throws,
     * post the result back, post the failure back. It is four lines that are wrong in a quiet way when they
     * are wrong — a result applied on the pool thread works until the day it does not — and there is no
     * reason for an application to write it.
     *
     * <p><b>This is the shortest correct way to do slow work, and that is deliberate.</b> Reading a file
     * straight from a command handler is shorter still and stops the frame loop for as long as the disk takes;
     * {@link Gui#async} is shorter and gives back no result, so a caller who needs one hand-rolls the way
     * home. Making the correct round trip the least typing is the point of it being here.
     *
     * <p><b>What runs on the lane must touch nothing.</b> No node, no tab, no model — it computes a value and
     * returns it. Everything that acts on that value runs in {@code landed}, on this thread, in order with
     * every other request in the queue. That is the whole discipline, and it is checkable by reading one
     * lambda.
     *
     * @param work   the slow part: I/O, a decode, a parse. Runs on the offload lane
     * @param landed what to do with the result, on the GUI thread
     * @param failed what to do when {@code work} threw, on the GUI thread. A report, usually — this is not a
     *               path that should take the application down, because the thing that failed was one request
     */
    /**
     * How long a posted task may hold this thread before {@link #reportStall} says something, in nanoseconds;
     * zero switches the check off.
     *
     * <p>This queue is where an application's own structural work runs, on the thread that also draws — so it
     * is the easiest place on the stack to write an ordinary blocking call and have nothing object. The text
     * editor read and wrote files here, and the only reason anybody found out was a port that went looking.
     *
     * <p>Two hundred and fifty milliseconds is fifteen frames: long enough that the window has visibly stopped
     * and nobody would call it jitter, and long enough that a heavy first frame does not cry wolf. Turn it
     * down with {@code -Dvexelray.stall.ms=16} to find out what blocks rather than to be told that something
     * did; {@code 0} switches it off for a run that does not care, such as a batch capture.
     */
    private static final long stallNanos = stallThreshold();

    private static long stallThreshold() {
        long ms = Long.getLong("vexelray.stall.ms", 250L);
        return ms <= 0 ? 0L : java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(ms);
    }

    /**
     * Name what held the loop, as precisely as a {@code Runnable} allows.
     *
     * <p>A posted task is almost always a lambda or a method reference, and such a class is named for the
     * class that <em>created</em> it — {@code dev.vexelray.demo.editor.FileActions$$Lambda/0x...}. Trimming at
     * the marker leaves the one fact worth printing: which class posted the thing that stopped the window.
     * That is not the method, but it is a file to open, and the alternative is a duration with no address.
     *
     * <p>Warned once per source, because a task that blocks once usually blocks every time, and a warning
     * repeated every frame is one that gets filtered out along with the first useful copy of it.
     */
    static void reportStall(Runnable task, long nanos) {
        String source = task.getClass().getName();
        int lambda = source.indexOf("$$Lambda");
        if (lambda > 0) {
            source = source.substring(0, lambda);
        }
        dev.vexelray.diag.Diagnostics.dropped("GuiApp.post/" + source,
                "every frame owed while a task posted by " + source + " ran",
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos) + " ms on the GUI thread, so the"
                        + " window did not draw and took no input for that long. This queue is for structural"
                        + " work — opening a window, changing a tab — and not for work that blocks; put that"
                        + " on the offload lane with offload(work, landed, failed), which runs it off this"
                        + " thread and hands the result back here");
    }

    public <T> void offload(java.util.concurrent.Callable<T> work, java.util.function.Consumer<T> landed,
                            java.util.function.Consumer<Exception> failed) {
        Gui gui = mainGui;
        if (gui == null) {
            throw new IllegalStateException(
                    "no tree yet: offload needs the lane the Gui was built on, which run() binds");
        }
        gui.offload().execute(() -> {
            T value;
            try {
                value = work.call();
            } catch (Exception e) {
                post(() -> failed.accept(e));
                return;
            }
            post(() -> landed.accept(value));
        });
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
                LOG.error("could not write capture to " + path, e);
            }
        }), this::post);
    }

    /**
     * Every window this application has open, the main one first — a snapshot, safe to take from any thread.
     *
     * <p>For an instrument that has to tell windows apart: the automation socket selects among them by name, and
     * each has its own tree and its own controls. The list is rebuilt from what is open at the call, so a window
     * opened or closed since the last one shows up or goes, and a caller asks again rather than caching.
     * Empty of a main entry until {@link #run} has bound the main tree.
     */
    public List<WindowView> windows() {
        List<WindowView> all = new ArrayList<>();
        Gui gui = mainGui;
        if (gui != null) {
            all.add(new WindowView(mainKey, gui, controls, true));
        }
        all.addAll(listed);
        return List.copyOf(all);
    }

    OpenWindow openWindow(WindowSpec spec, AppWindow owner) {
        // The spec's Standing decides whether this window is a satellite of the main window — above it always,
        // sharing its taskbar button, minimized and destroyed with it — or a peer with its own place in the
        // stack, which the main window can be brought in front of. Nothing after creation can change it: the
        // OS settles a window's standing from the owner it was created with. A satellite that goes away with
        // its owner arrives back here as its own pump reporting closed, the same path as its close button.
        wireWake(spec.gui());
        // From the same factory the main window came from: this is the path every popup, named window and
        // dialog takes, and a window that skipped it is one the host was never given a say over.
        GuiWindow w = new GuiWindow(platform, instance, device, fonts, noImage, spec.gui(),
                spec.standing().place(spec.config(), anchorHandle(spec)), this::create, presentBackend);
        WindowInput input = inputs.attach(w.window, spec.gui());
        OpenWindow entry = new OpenWindow(w, input, spec, owner);
        LOG.info("window opened: \"{}\" {}x{}{}", spec.config().title(), spec.config().width(), spec.config().height(),
                owner != null ? " (named " + owner.key() + ")" : "");
        open.add(entry);
        spec.onCreated().accept(w.window);
        // The controls this window can actually be commanded by, capture included. After onCreated, so a bar
        // that also listens there is already built by the time it is pointed at the window.
        WindowControls windowControls = controlsFor(w);
        spec.onControls().accept(windowControls);
        entry.view = new WindowView(owner != null ? owner.key() : spec.config().title(), spec.gui(),
                windowControls, false);
        listed.add(entry.view);
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
     * <p>It bounds the frames a {@link #postWake} earns: a wake is remembered, not dropped, and draws no
     * sooner than this gap after the previous frame <em>began</em>. A wake that lands together with OS input
     * is held to the same gap.
     *
     * <p>Costs nothing in input latency while the loop is parked: the wait ends early on OS input. The
     * exception is the remainder of a gap after a wake, which is a timed sleep and so is not ended by input
     * ({@code waitEvents} cannot be repeated: it returns at once while the wake's message is unread). Input that
     * lands there waits for it, at most this gap.
     */
    public GuiApp maxFrameRate(long minFrameNanos) {
        this.minFrameNanos = Math.max(0L, minFrameNanos);
        return this;
    }

    /**
     * {@link #maxFrameRate} set to one refresh of the display the main window is on, read from the window and read
     * again about once a second, so a window dragged to a display of another rate follows it.
     *
     * <p>The value to hold the loop to: a ceiling above the display draws frames that are not shown, and one below it
     * (a constant 60 Hz on a 144 Hz panel) caps the animation for nothing. {@code fallbackNanos} stands until the
     * window can say, and for good where it cannot (a platform whose window reports no interval).
     */
    public GuiApp maxFrameRateToDisplay(long fallbackNanos) {
        this.minFrameNanos = Math.max(0L, fallbackNanos);
        this.ceilingFollowsDisplay = true;
        return this;
    }

    /** Re-read the display's refresh interval if it is time to, and make it the ceiling. Main thread, outside the frame. */
    private void followDisplay() {
        long now = System.nanoTime();
        if (displayReadAt != 0L && now - displayReadAt < 1_000_000_000L) {
            return;
        }
        displayReadAt = now;
        long interval = main.window.refreshIntervalNanos();
        if (interval > 0L && interval != minFrameNanos) {
            LOG.debug("frame ceiling follows the display: {} us a frame ({} Hz)", interval / 1_000L,
                    Math.round(1e9 / interval));
            minFrameNanos = interval;
        }
    }

    public void postWake() {
        if (Probe.ON) {
            Probe.mark(Lane.FRAME, "wake.post", "nudging the OS message queue");
        }
        wakePosted.set(true);
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
                long taskStarted = stallNanos > 0 ? System.nanoTime() : 0L;
                task.run();
                if (stallNanos > 0) {
                    long took = System.nanoTime() - taskStarted;
                    if (took > stallNanos) {
                        reportStall(task, took);
                    }
                }
            }
            // After the tasks, because opening a window is one of them: a tree that arrived this
            // iteration is presented this iteration, so it must be able to ask for the next one.
            wireAllWakes();
            iteration++;
            if (!retiring.isEmpty()) {
                retire();
            }
            // One span per loop iteration, and it is the root of the whole report: every other span in every
            // other lane nests inside this one, so its self time is the loop's own overhead and its children
            // are where a frame actually went. The wait below is deliberately outside it - a loop parked on an
            // empty event queue is idle, not slow, and counting the park as frame time would make a perfectly
            // healthy render-on-demand application look like the worst offender in the table.
            if (ceilingFollowsDisplay) {
                followDisplay();
            }
            wakePosted.set(false);   // a wake from here on was posted during this frame, so it is pending for the next
            long frameStarted = System.nanoTime();   // the gap the ceiling holds is frame start to frame start
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
                listed.remove(w.view);
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
                // A texture waiting out its iteration (see release) is the same: parked, it is never closed.
                long budget = tasks.isEmpty() && !retireDue() ? pacing.getAsLong() : 0L;
                // Then the two bounds. The floor applies only while someone is looking: an unfocused
                // window parks on whatever the application asked for, up to forever.
                if (isFocused()) {
                    budget = Math.min(budget, maxIdleNanos);
                }
                // The ceiling applies always. A zero budget means "immediately", which on a presenter
                // that does not block is as fast as the machine goes; this is what stops that.
                budget = Math.max(budget, minFrameNanos - (System.nanoTime() - frameStarted));
                // The heartbeat, and the thing that makes a gap in the log readable (docs/reference/automation.md §4).
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
                        // A wake ends the park like OS input does, so the ceiling would not hold for it.
                        // Sleep out what is left of the gap when a wake was posted; it stays pending, so the
                        // next iteration draws it. A plain timed sleep and not waitEvents, which returns at
                        // once while the wake's message is unread, so OS input does not end this one: it waits,
                        // at most one gap.
                        long gapEnds = frameStarted + minFrameNanos;
                        long left;
                        while (minFrameNanos > 0 && wakePosted.getAndSet(false)
                                && (left = gapEnds - System.nanoTime()) > 0) {
                            java.util.concurrent.locks.LockSupport.parkNanos(left);
                        }
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
        listed.clear();
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
        listed.clear();
        main.close();
        if (presentBackend != null) {
            presentBackend.close();   // after every window it presented, before the device it was made on
        }
        for (SampledColorTarget v : viewports) {
            v.close();
        }
        viewports.clear();
        for (AtlasTexture t : textures) {
            t.close();
        }
        textures.clear();
        retiring.clear();   // every one of them was in textures, so closed just above
        for (StorageBuffer b : buffers) {
            b.close();
        }
        buffers.clear();
        fonts.close();
        noImage.close();
        device.close();
        instance.close();
    }

    // --- headless capture ---

    /**
     * Reconcile + lay out {@code gui} at {@code width}×{@code height}, render one frame, and write a PNG — in
     * vexelray-text's standard fonts.
     */
    public static void capture(Gui gui, int width, int height, float bgR, float bgG, float bgB, String path)
            throws IOException {
        capture(gui, TextFaces.standard(), width, height, bgR, bgG, bgB, path);
    }

    /** As {@link #capture(Gui, int, int, float, float, float, String)}, in an application's own fonts. */
    public static void capture(Gui gui, FontSet fonts, int width, int height, float bgR, float bgG, float bgB,
                               String path) throws IOException {
        capture(gui, new TextFaces(fonts), width, height, bgR, bgG, bgB, path);
    }

    private static void capture(Gui gui, TextFaces faces, int width, int height, float bgR, float bgG, float bgB,
                                String path) throws IOException {
        // Two frames, not one. Anything an application derives from its own measured box — a picture authored in
        // pixels is the clearest case — cannot exist during the first layout that produces that box: the observer
        // fires inside it, and the mutation it posts is applied by the next drain. A still image wants the settled
        // state rather than the instant before it, so the first frame is a warm-up and the second is the picture.
        gui.frame(width, height, faces);
        RetainedNode root = gui.frame(width, height, faces);
        Canvas canvas = new Canvas(width, height);
        canvas.begin();
        if (root != null) {
            TreeRenderer.emit(root, canvas, faces, gui.theme());
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
                 FontAtlases fonts = FontAtlases.upload(device, faces);
                 AtlasTexture noImage = AtlasTexture.placeholder(device);
                 VertexBuffer vb = new VertexBuffer(device, vertices);
                 GraphicsPipeline pipeline = new GraphicsPipeline(device, rp.handle(), width, height,
                         CanvasShader.vertex().spirv(), "main", CanvasShader.fragment().spirv(), "main",
                         canvasConfig(fonts.first(), noImage, false))) { // fixed viewport: offscreen, no resize
                // The same run list the windowed path walks, so a tree holding images captures to PNG exactly as
                // it presents — which is what makes the image kind checkable without a window.
                byte[] rgba = OffscreenDraw.toRgba(device, rp.handle(), pipeline, width, height, vb.handle(),
                        fonts.first().descriptorSet(), bind(runs, vertexCount, noImage, fonts.textures()),
                        bgR, bgG, bgB, 1f);
                PngWriter.write(rgba, width, height, Path.of(path));
            }
        }
    }

    // --- native-API glue ---

    /**
     * Map a requested {@link dev.vexelray.gui.core.input.CursorShape} onto the window API.
     *
     * <p><b>The engine offers the arrow, the I-beam and the two resize arrows</b> — the shapes every desktop has a
     * system cursor for. The hand shapes — pointer over anything clickable, open and closed hands over anything
     * grabbable — fall back to the arrow: Windows has a pointing hand but no open or closed one, so a grab hand
     * would mean shipping cursor images. The rule that decides them is framework-side and fully exercised
     * ({@code CursorRuleTest}). Degrading to the arrow is the right fallback: a wrong-looking cursor is a cosmetic
     * loss, where guessing at a shape the platform has no standard for would not be.
     */
    private static NativeWindow.Cursor osCursor(dev.vexelray.gui.core.input.CursorShape shape) {
        return OS_CURSORS.getOrDefault(shape, NativeWindow.Cursor.ARROW);
    }

    /** The shapes the engine can show; anything absent is the arrow. */
    private static final java.util.Map<dev.vexelray.gui.core.input.CursorShape, NativeWindow.Cursor> OS_CURSORS =
            java.util.Map.of(
                    dev.vexelray.gui.core.input.CursorShape.TEXT, NativeWindow.Cursor.TEXT,
                    dev.vexelray.gui.core.input.CursorShape.RESIZE_HORIZONTAL, NativeWindow.Cursor.RESIZE_HORIZONTAL,
                    dev.vexelray.gui.core.input.CursorShape.RESIZE_VERTICAL, NativeWindow.Cursor.RESIZE_VERTICAL);

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
    static List<WindowedPresenter.Run> bind(List<Canvas.Run> runs, int vertexCount, SampledImage noImage,
                                            List<? extends SampledImage> atlases) {
        List<WindowedPresenter.Run> out = new ArrayList<>(runs.size());
        for (Canvas.Run r : runs) {
            if (r.firstVertex() >= vertexCount) {
                break;   // runs are in submission order, so the first one past the end ends the frame
            }
            int count = Math.min(r.vertexCount(), vertexCount - r.firstVertex());
            long set = r.image() instanceof SampledImage img && bindable(img.device(), noImage.device())
                    ? img.descriptorSet()
                    : noImage.descriptorSet();
            // Set 0 is the atlas of the face the run's glyphs came from. Every face id a canvas can name is one this
            // application's FontSet gave out, and every face of that set was uploaded, so it is always in range.
            out.add(new WindowedPresenter.Run(atlases.get(r.face()).descriptorSet(), set, r.firstVertex(), count));
        }
        return out;
    }

    /**
     * Whether an image owned by {@code imageDevice} can be bound while drawing on {@code frameDevice} — and if
     * it cannot, say so before falling back to the placeholder.
     *
     * <p>A descriptor set means nothing to a device other than the one that allocated it, and the case that
     * arises in practice is {@link #capture}: it builds its own device and then walks a tree the application
     * built against a window's. Anything in that tree with an {@code IMAGE} prop bound to a target from the
     * other device is unbindable here.
     *
     * <p>The substitution is right and stays. What was missing is that it happened <b>silently</b>: a capture
     * of a tree holding a marched viewport came out correct about every panel, label and border and blank
     * exactly where the content was. That is a screenshot which looks like a screenshot — the one failure a
     * reader has no reason to investigate, because nothing about it suggests a question was asked and declined.
     * One line of stderr is the whole difference between a puzzling afternoon and a known limitation.
     *
     * <p>Keyed per call site rather than per image: this runs once per run per frame, and a capture holding
     * three viewports has one thing wrong with it, not three.
     *
     * <p>The parameters are {@code Object} rather than {@code VulkanDevice} because the rule genuinely is
     * reference identity, and because a device cannot be constructed without a GPU — typed, this decision could
     * only be exercised by a test that needs one, which is how a warning ends up never having been watched to
     * fire. The call site passes two devices and nothing else can reach it.
     */
    static boolean bindable(Object imageDevice, Object frameDevice) {
        if (imageDevice == frameDevice) {
            return true;
        }
        Diagnostics.dropped("GuiApp.bind/foreignDevice",
                "an image belonging to another Vulkan device",
                "its descriptor set cannot be bound here, so the placeholder is drawn instead — a headless"
                        + " capture builds its own device, so a marched viewport in the tree comes out blank"
                        + " while the rest of the frame is correct");
        return false;
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
            attrs.add(GraphicsPipeline.VertexAttribute.floats(a.location(), a.components(), a.offset()));
        }
        return new GraphicsPipeline.Config(CanvasVertex.STRIDE_BYTES, attrs,
                new long[]{atlas.descriptorSetLayout(), image.descriptorSetLayout()}, true,
                Vk.SHADER_STAGE_FRAGMENT_BIT, 0, dynamicViewport);
    }

}
