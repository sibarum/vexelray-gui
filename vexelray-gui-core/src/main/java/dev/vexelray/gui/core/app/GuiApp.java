package dev.vexelray.gui.core.app;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.canvas.CanvasVertex;
import dev.vexelray.gui.core.Gui;
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
    private final TextLayout[] text;
    private final TextMeasurer measurer;

    // The main window, plus any open popups. All of them live on the main thread and are pumped/presented by the
    // one loop in run(); a popup closing removes only its own bundle, the main window closing ends the loop.
    private final GuiWindow main;
    private final List<PopupEntry> popups = new ArrayList<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<PopupRequest> popupRequests =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private record PopupRequest(String title, int width, int height, Gui gui,
                                java.util.function.LongConsumer onCreated, Runnable onClosed) {
    }

    private record PopupEntry(GuiWindow window, Runnable onClosed) {
    }

    public GuiApp(String title, int width, int height) {
        this.platform = NativePlatform.current();
        this.instance = new VulkanInstance(title, platform.requiredVulkanInstanceExtensions());

        // Device selection needs a surface to prove present support, so the main window is created first and its
        // surface probed; popups then reuse the same device (scaffold caveat: same-queue present support for
        // sibling surfaces holds on every real platform, but is asserted per-surface only for this first one).
        NativeWindow probe = platform.createWindow(new WindowConfig(title, width, height, true));
        long probeSurface = probe.createVulkanSurface(instance.handleAddress(),
                VkLoader.getInstanceProcAddrPointer());
        VulkanInstance.DeviceSelection selection = instance.selectGraphicsPresentDevice(probeSurface)
                .orElseThrow(() -> new IllegalStateException("no graphics+present device"));
        this.device = new VulkanDevice(instance.handle(), selection);

        int[] atlasSize = new int[2];
        byte[] atlasRgba = loadAtlasRgba(atlasSize);
        this.atlas = new AtlasTexture(device, atlasSize[0], atlasSize[1], atlasRgba);
        this.text = faces(AtlasData.loadFromResource(ATLAS_JSON));
        this.measurer = measurer(text);

        this.main = new GuiWindow(platform, instance, device, atlas, text, measurer, null,
                probe, probeSurface);
    }

    /** The OS window handle (an {@code HWND} on Windows) — used to attach input (tactroller) for client-space
     *  coordinates and focus gating at the application edge. */
    public long windowHandle() {
        return main.osHandle();
    }

    /**
     * Request an OS-level popup window showing {@code popupGui}'s tree. <b>Callable from any thread</b> — this
     * only enqueues; the main thread creates the actual window at the top of its next frame, which is the portable
     * contract (macOS requires window creation and event pumping on the main thread; Win32 binds a window to its
     * creating thread). The popup joins the existing frame loop — it never gets a loop or thread of its own — and
     * closes via its OS close button, releasing only its own resources.
     *
     * <p>Scaffold: the popup renders, resizes, and closes; routing <i>input</i> to it (tactroller attaches to one
     * window today) and programmatic close/parenting (always-on-top, modal) are follow-ups.
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
        popupRequests.add(new PopupRequest(title, width, height, popupGui, onCreated, onClosed));
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
        // Map the GUI's desired cursor shape onto the OS window (I-beam over editable text, §8.3).
        gui.onCursorChange(shape -> main.window.setCursor(osCursor(shape)));
        int frame = 0;
        boolean open = true;
        while (open && (maxFrames <= 0 || frame < maxFrames)) {
            open = main.frame(beforeFrame);
            for (PopupRequest r; (r = popupRequests.poll()) != null; ) {
                GuiWindow w = new GuiWindow(platform, instance, device, atlas, text, measurer, r.gui(),
                        new WindowConfig(r.title(), r.width(), r.height(), true));
                popups.add(new PopupEntry(w, r.onClosed()));
                r.onCreated().accept(w.osHandle());
            }
            popups.removeIf(p -> {
                if (p.window().frame(() -> { })) {
                    return false;
                }
                p.window().close();   // the popup's own resources only; the device and loop keep running
                p.onClosed().run();
                return true;
            });
            frame++;
        }
        device.waitIdle();
        // Main window gone (or frame cap hit): the popups' loop is gone with it, so close them too.
        for (PopupEntry p : popups) {
            p.window().close();
            p.onClosed().run();
        }
        popups.clear();
    }

    public void run(Gui gui) {
        run(gui, 0);
    }

    @Override
    public void close() {
        device.waitIdle();
        for (PopupEntry p : popups) {
            p.window().close();
            p.onClosed().run();
        }
        popups.clear();
        main.close();
        atlas.close();
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
        RetainedNode root = gui.frame(width, height, measurer(text));
        Canvas canvas = new Canvas(width, height);
        canvas.begin();
        if (root != null) {
            TreeRenderer.emit(root, canvas, text);
        }
        float[] vertices = canvas.toVertexArray();
        int vertexCount = canvas.vertexCount();

        NativePlatform platform = NativePlatform.current();
        try (VulkanInstance instance = new VulkanInstance("vexelray-gui",
                platform.requiredVulkanInstanceExtensions())) {
            VulkanInstance.DeviceSelection sel = instance.selectGraphicsDevice()
                    .orElseThrow(() -> new IllegalStateException("no graphics device"));
            try (VulkanDevice device = new VulkanDevice(instance.handle(), sel);
                 VulkanRenderPass rp = new VulkanRenderPass(device, Vk.FORMAT_R8G8B8A8_UNORM,
                         Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                 AtlasTexture atlas = new AtlasTexture(device, atlasSize[0], atlasSize[1], atlasRgba);
                 VertexBuffer vb = new VertexBuffer(device, vertices);
                 GraphicsPipeline pipeline = new GraphicsPipeline(device, rp.handle(), width, height,
                         CanvasShader.vertex().spirv(), "main", CanvasShader.fragment().spirv(), "main",
                         canvasConfig(atlas, false))) { // fixed viewport: offscreen, no resize
                byte[] rgba = OffscreenDraw.toRgba(device, rp.handle(), pipeline, width, height, vb.handle(),
                        atlas.descriptorSet(), vertexCount, bgR, bgG, bgB, 1f);
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

    static GraphicsPipeline.Config canvasConfig(AtlasTexture atlas, boolean dynamicViewport) {
        List<GraphicsPipeline.VertexAttribute> attrs = new ArrayList<>();
        for (CanvasVertex.Attr a : CanvasVertex.ATTRIBUTES) {
            attrs.add(new GraphicsPipeline.VertexAttribute(a.location(), vkFormat(a.components()), a.offset()));
        }
        return new GraphicsPipeline.Config(CanvasVertex.STRIDE_BYTES, attrs,
                new long[]{atlas.descriptorSetLayout()}, true, Vk.SHADER_STAGE_FRAGMENT_BIT, 0, dynamicViewport);
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
