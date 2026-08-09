package dev.vexelray.gui.core.app;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.canvas.CanvasVertex;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.text.AtlasData;
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
 * The GUI application host and frame loop — the seam where the framework meets VexelRay. It owns the window and
 * the VexelRay objects (device, swapchain, render pass, font atlas, the Canvas pipeline, a dynamic vertex buffer,
 * the presenter) and each frame it walks a {@link RetainedNode} tree into a {@link Canvas} ({@link TreeRenderer}),
 * uploads the geometry, and presents. This is exactly VexelRay's dynamic-Canvas present path — the GUI writes no
 * Vulkan or shader code; it only composes native API.
 *
 * <p>Step 1 (architecture.md §12 step 4): render a hard-coded tree, on demand, through {@code Canvas}. The
 * mutation queue, layout engine, input, and idle-blocking loop are later steps; here the loop simply re-emits the
 * (static) tree each frame via {@link WindowedPresenter}'s per-frame callback.
 */
public final class GuiApp implements AutoCloseable {

    /** Default font atlas shipped by vexelray-text (baked by vexelray-msdf-maven-plugin). */
    private static final String ATLAS_JSON = "/dev/vexelray/text/atlas/primary.json";
    private static final String ATLAS_PNG = "/dev/vexelray/text/atlas/primary.png";
    /** Vertex-buffer capacity in floats — headroom for a frame's worth of Canvas geometry. */
    private static final int CAPACITY_FLOATS = 512 * 1024;

    private final NativeWindow window;
    private final VulkanInstance instance;
    private final long surface;
    private final VulkanDevice device;
    private final VulkanSwapchain swapchain;
    private final VulkanRenderPass renderPass;
    private final AtlasTexture atlas;
    private final VertexBuffer vertexBuffer;
    private final GraphicsPipeline pipeline;
    private final WindowedPresenter presenter;
    private final Canvas canvas;
    private final TextLayout text;

    /** Open a window and wire up the native rendering path. */
    public GuiApp(String title, int width, int height) {
        NativePlatform platform = NativePlatform.current();
        this.window = platform.createWindow(new WindowConfig(title, width, height, true));
        this.instance = new VulkanInstance(title, platform.requiredVulkanInstanceExtensions());
        this.surface = window.createVulkanSurface(instance.handleAddress(), VkLoader.getInstanceProcAddrPointer());
        VulkanInstance.DeviceSelection selection = instance.selectGraphicsPresentDevice(surface)
                .orElseThrow(() -> new IllegalStateException("no graphics+present device"));
        this.device = new VulkanDevice(instance.handle(), selection);
        this.swapchain = new VulkanSwapchain(instance.handle(), device, surface, window.width(), window.height());
        this.renderPass = new VulkanRenderPass(device, swapchain.format(), Vk.IMAGE_LAYOUT_PRESENT_SRC_KHR);

        int[] atlasSize = new int[2];
        byte[] atlasRgba = loadAtlasRgba(atlasSize);
        AtlasData atlasData = AtlasData.loadFromResource(ATLAS_JSON);
        this.atlas = new AtlasTexture(device, atlasSize[0], atlasSize[1], atlasRgba);
        this.text = new TextLayout(atlasData);
        this.canvas = new Canvas(swapchain.width(), swapchain.height());
        this.vertexBuffer = new VertexBuffer(device, CAPACITY_FLOATS);

        ComposedShader vs = CanvasShader.vertex();
        ComposedShader fs = CanvasShader.fragment();
        this.pipeline = new GraphicsPipeline(device, renderPass.handle(), swapchain.width(), swapchain.height(),
                vs.spirv(), "main", fs.spirv(), "main", canvasConfig(atlas));
        this.presenter = new WindowedPresenter(device, swapchain, renderPass.handle(), pipeline, window);
    }

    /** Render {@code root} until the window closes (or {@code maxFrames} presented if positive). */
    public void run(RetainedNode root, int maxFrames) {
        presenter.configureDraw(vertexBuffer.handle(), atlas.descriptorSet(), 0);
        presenter.run(maxFrames, 0, (dt, pushConstants) -> {
            canvas.begin();
            TreeRenderer.emit(root, canvas, text);
            vertexBuffer.update(canvas.toVertexArray());
            presenter.setVertexCount(canvas.vertexCount());
        });
    }

    /** Render {@code root} until the window closes. */
    public void run(RetainedNode root) {
        run(root, 0);
    }

    @Override
    public void close() {
        device.waitIdle();
        presenter.close();
        pipeline.close();
        vertexBuffer.close();
        atlas.close();
        renderPass.close();
        swapchain.close();
        device.close();
        instance.destroySurface(surface);
        instance.close();
        window.close();
    }

    // --- headless capture (verification / thumbnails); no window, its own device ---

    /** Render {@code root} once into a {@code width}×{@code height} image and write it as a PNG. */
    public static void capture(RetainedNode root, int width, int height, float bgR, float bgG, float bgB,
                               String path) throws IOException {
        int[] atlasSize = new int[2];
        byte[] atlasRgba = loadAtlasRgba(atlasSize);
        AtlasData atlasData = AtlasData.loadFromResource(ATLAS_JSON);

        Canvas canvas = new Canvas(width, height);
        TreeRenderer.emit(root, canvas, new TextLayout(atlasData));
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
                         canvasConfig(atlas))) {
                byte[] rgba = OffscreenDraw.toRgba(device, rp.handle(), pipeline, width, height, vb.handle(),
                        atlas.descriptorSet(), vertexCount, bgR, bgG, bgB, 1f);
                ImageIO.write(toImage(rgba, width, height), "PNG", new File(path));
            }
        }
    }

    // --- native-API glue (composition, not re-implementation) ---

    /** The Canvas pipeline config: the fat vertex format, the atlas descriptor, alpha blend, no push constant. */
    private static GraphicsPipeline.Config canvasConfig(AtlasTexture atlas) {
        List<GraphicsPipeline.VertexAttribute> attrs = new ArrayList<>();
        for (CanvasVertex.Attr a : CanvasVertex.ATTRIBUTES) {
            attrs.add(new GraphicsPipeline.VertexAttribute(a.location(), vkFormat(a.components()), a.offset()));
        }
        return new GraphicsPipeline.Config(CanvasVertex.STRIDE_BYTES, attrs,
                new long[]{atlas.descriptorSetLayout()}, true, Vk.SHADER_STAGE_FRAGMENT_BIT, 0);
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
