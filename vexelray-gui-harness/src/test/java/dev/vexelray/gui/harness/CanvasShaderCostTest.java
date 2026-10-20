package dev.vexelray.gui.harness;

import dev.supirvast.vastir.lower.CoreToSpirv;
import dev.supirvast.vulkan.Vk;
import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.canvas.CanvasVertex;
import dev.vexelray.canvas.shadelet.Composition;
import dev.vexelray.canvas.shadelet.CoreShadelets;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.shadelets.Effects;
import dev.vexelray.vulkan.present.AtlasTexture;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.SampledColorTarget;
import dev.vexelray.vulkan.present.StorageBuffer;
import dev.vexelray.vulkan.present.VulkanRenderPass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a theme that changes the canvas composition costs, measured: the two numbers R0 of
 * {@code docs/plans/runtime-themes.md} asks for.
 *
 * <ul>
 *   <li><b>Lowering</b> the composition in-process, as R4's worker would for a theme the jar did not know: the
 *       composition built into IR, then {@code CoreToSpirv}. The first lowering in a process is the one a swap pays,
 *       because an application with a precompiled shader has never run the lowerer, so it is reported apart.</li>
 *   <li><b>The pipeline</b> for it, built as {@code GuiWindow} builds the canvas pipeline (the layout of
 *       {@code GuiApp.canvasConfig}, which is package-private and so restated here), on the loop thread, which is this
 *       harness's main thread. Each build is given SPIR-V the driver has never seen (a nonce in an
 *       {@code OpModuleProcessed}), so its shader cache cannot answer for it, as it could not for a new theme's.</li>
 * </ul>
 *
 * <p>A measurement, not a test: it asserts only that it ran, takes several seconds, and is skipped unless asked for:
 * {@code mvn -o -pl vexelray-gui-harness test -Dtest=CanvasShaderCostTest -Dmeasure=true}.
 */
@EnabledIfSystemProperty(named = "measure", matches = "true")
class CanvasShaderCostTest {

    private static final int RUNS = 5;

    /** The demo's composition: the core set and every effect, as {@code DemoShader} composes it. */
    private static final class Everything extends CanvasShader.Composed {
        Everything() {
            super(CoreShadelets.composition().with(Effects.ALL));
        }
    }

    /** The core set alone, for scale. */
    private static final class Core extends CanvasShader.Composed {
        Core() {
            super(CoreShadelets.composition());
        }
    }

    @Test
    void lowering() {
        report("lower core+effects", lower(Everything::new));
        report("lower core", lower(Core::new));
    }

    @Test
    void pipeline() {
        byte[] vertex = CanvasShader.vertex().spirv();
        byte[] everything = new CoreToSpirv().lower(new Everything().module(), new Everything().target()).toByteArray();
        byte[] core = new CoreToSpirv().lower(new Core().module(), new Core().target()).toByteArray();

        AtomicBoolean ran = new AtomicBoolean();
        AtomicReference<GuiApp> app = new AtomicReference<>();
        List<long[]> results = new ArrayList<>();
        Runnable measure = () -> {
            if (ran.getAndSet(true) || app.get() == null) {
                return;
            }
            GuiApp a = app.get();
            var device = a.gpu().device();
            AtlasTexture atlas = a.texture(new byte[] {-1, -1, -1, -1}, 1, 1);
            SampledColorTarget image = a.viewport(4, 4);
            StorageBuffer tables = a.storage(Composition.TABLES_FLOATS, Composition.TABLES_BINDING);
            List<GraphicsPipeline.VertexAttribute> attrs = new ArrayList<>();
            for (CanvasVertex.Attr at : CanvasVertex.ATTRIBUTES) {
                attrs.add(GraphicsPipeline.VertexAttribute.floats(at.location(), at.components(), at.offset()));
            }
            GraphicsPipeline.Config config = new GraphicsPipeline.Config(CanvasVertex.STRIDE_BYTES, attrs,
                    new long[] {atlas.descriptorSetLayout(), image.descriptorSetLayout(), tables.descriptorSetLayout()},
                    true, Vk.SHADER_STAGE_FRAGMENT_BIT, 0, true);
            try (VulkanRenderPass pass = new VulkanRenderPass(device, Vk.FORMAT_B8G8R8A8_UNORM,
                    Vk.IMAGE_LAYOUT_PRESENT_SRC_KHR)) {
                for (byte[] fragment : List.of(everything, core)) {
                    long[] cold = new long[RUNS];
                    long[] warm = new long[RUNS];
                    for (int i = 0; i < RUNS; i++) {
                        byte[] fresh = withNonce(fragment, System.nanoTime() + ":" + i);
                        cold[i] = build(device, pass, vertex, fresh, config);
                        warm[i] = build(device, pass, vertex, fresh, config);   // the same bytes again: cached
                    }
                    results.add(cold);
                    results.add(warm);
                }
            }
            a.release(atlas);
        };

        try (HarnessApp harness = HarnessApp.start(new Gui(), WindowConfig.of("cost", 200, 100),
                GuiApp.Compute.SHARED, () -> measure.run())) {
            app.set(harness.app());
            harness.window().postWake();
            assertTrue(harness.await(() -> results.size() == 4, 120_000), "the pipelines were never built");
        }
        report("pipeline core+effects, new SPIR-V", results.get(0));
        report("pipeline core+effects, same SPIR-V again", results.get(1));
        report("pipeline core, new SPIR-V", results.get(2));
        report("pipeline core, same SPIR-V again", results.get(3));
    }

    private static long build(dev.supirvast.vulkan.VulkanDevice device, VulkanRenderPass pass, byte[] vertex,
                              byte[] fragment, GraphicsPipeline.Config config) {
        long t0 = System.nanoTime();
        GraphicsPipeline p = new GraphicsPipeline(device, pass.handle(), 640, 360, vertex, "main", fragment, "main",
                config);
        long t = System.nanoTime() - t0;
        p.close();
        return t;
    }

    private static long[] lower(java.util.function.Supplier<CanvasShader.Composed> source) {
        long[] t = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long t0 = System.nanoTime();
            CanvasShader.Composed s = source.get();
            new CoreToSpirv().lower(s.module(), s.target()).toByteArray();
            t[i] = System.nanoTime() - t0;
        }
        return t;
    }

    /** First run, then the median of the rest, in milliseconds. */
    private static void report(String what, long[] nanos) {
        long[] rest = Arrays.copyOfRange(nanos, 1, nanos.length);
        Arrays.sort(rest);
        System.out.printf("%-42s first %7.1f ms   median of next %d %7.1f ms   all %s%n", what, nanos[0] / 1e6,
                rest.length, rest[rest.length / 2] / 1e6,
                Arrays.toString(Arrays.stream(nanos).mapToObj(n -> String.format("%.0f", n / 1e6)).toArray()));
    }

    /**
     * {@code spirv} with an {@code OpModuleProcessed} carrying {@code nonce}, before the first annotation: a debug
     * instruction, so the shader is the same program and different bytes.
     */
    private static byte[] withNonce(byte[] spirv, String nonce) {
        ByteBuffer in = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
        int words = spirv.length / 4;
        int at = 5;
        while (at < words) {
            int op = in.getInt(at * 4) & 0xFFFF;
            if ((op >= 71 && op <= 75) || op == 332 || (op >= 19 && op <= 39)) {
                break;
            }
            at += in.getInt(at * 4) >>> 16;
        }
        byte[] text = nonce.getBytes(StandardCharsets.UTF_8);
        int textWords = text.length / 4 + 1;   // null-terminated, padded to a word
        ByteBuffer out = ByteBuffer.allocate(spirv.length + (1 + textWords) * 4).order(ByteOrder.LITTLE_ENDIAN);
        out.put(spirv, 0, at * 4);
        out.putInt(((1 + textWords) << 16) | 330);
        byte[] padded = Arrays.copyOf(text, textWords * 4);
        out.put(padded);
        out.put(spirv, at * 4, spirv.length - at * 4);
        return out.array();
    }
}
