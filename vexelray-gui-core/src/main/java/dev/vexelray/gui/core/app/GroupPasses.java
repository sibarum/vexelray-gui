package dev.vexelray.gui.core.app;

import dev.supirvast.vulkan.VulkanDevice;
import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.vulkan.present.AtlasTexture;
import dev.vexelray.vulkan.present.DrawCommands;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.Recorder;
import dev.vexelray.vulkan.present.RunRecorder;
import dev.vexelray.vulkan.present.SampledColorTarget;
import dev.vexelray.vulkan.present.SampledImage;
import dev.vexelray.vulkan.present.WindowedPresenter;

import java.lang.foreign.Arena;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The passes a frame records before its own, one for each SDG group the canvas drew apart (vexelray-fields
 * {@code docs/sdg-groups.md}, *The GPU pass*): each group's runs drawn into a target of its size, cleared to
 * transparent, which the frame's own pass then reads through the group's quad. The window and the headless capture
 * both use one, so the two draw a group alike.
 *
 * <p><b>One record space.</b> A group's elements are in the canvas's one vertex stream and its one table buffer, so
 * its pass binds the frame's own vertex buffer and set 2, and only the pipeline differs: one built on a target's render
 * pass, the format a target has, with the canvas's shaders and layout and a dynamic viewport, built once.
 *
 * <p><b>Targets are pooled</b> by size (the canvas sizes them in powers of two), and a target drawn in one frame is
 * not drawn into again until {@value #IN_FLIGHT} frames later, by when the frame that read it has finished with it.
 */
final class GroupPasses implements AutoCloseable {

    /** Frames a target rests after it is drawn, before it is drawn into again: more than a presenter has in flight. */
    static final int IN_FLIGHT = 3;

    private final VulkanDevice device;
    private final DeviceImages images;
    private final AtlasTexture atlas;
    private final List<? extends SampledImage> atlases;
    private final ShadeletTables tables;
    private final Arena arena = Arena.ofShared();
    private final DrawCommands commands;
    private final RunRecorder runs;
    private GraphicsPipeline pipeline;
    private final Map<Long, ArrayDeque<SampledColorTarget>> free = new HashMap<>();
    private final List<List<SampledColorTarget>> resting = new ArrayList<>();
    private int frame;

    GroupPasses(VulkanDevice device, DeviceImages images, AtlasTexture atlas, List<? extends SampledImage> atlases,
                ShadeletTables tables) {
        this.device = device;
        this.images = images;
        this.atlas = atlas;
        this.atlases = atlases;
        this.tables = tables;
        this.commands = new DrawCommands(device);
        this.runs = new RunRecorder(device, arena);
        for (int i = 0; i < IN_FLIGHT; i++) {
            resting.add(new ArrayList<>());
        }
    }

    /**
     * The passes for {@code canvas}'s groups this frame, its {@code vertexCount} vertices in {@code vertexBuffer}: each
     * group given its target first ({@link Canvas.GroupTarget#bind}), so the frame's own runs, bound after this, read
     * them. Null when the canvas drew no group apart.
     */
    Recorder prepare(Canvas canvas, int vertexCount, long vertexBuffer) {
        frame++;
        List<SampledColorTarget> due = resting.get(frame % IN_FLIGHT);
        for (SampledColorTarget t : due) {
            free.computeIfAbsent(key(t.width(), t.height()), k -> new ArrayDeque<>()).push(t);
        }
        due.clear();
        List<Canvas.GroupTarget> groups = canvas.groups();
        if (groups.isEmpty()) {
            return null;
        }
        List<SampledColorTarget> targets = new ArrayList<>(groups.size());
        List<List<WindowedPresenter.Run>> bound = new ArrayList<>(groups.size());
        for (Canvas.GroupTarget g : groups) {
            SampledColorTarget t = take(g.width(), g.height());
            g.bind(t);
            due.add(t);
            targets.add(t);
        }
        // bound after every target is given, since a group's runs may read an inner group's target
        for (Canvas.GroupTarget g : groups) {
            bound.add(GuiApp.bind(g.runs(), vertexCount, images, atlases));
        }
        long set0 = atlas.descriptorSet();
        long set2 = tables.buffer().descriptorSet();
        return (cmd, w, h) -> {
            for (int i = 0; i < targets.size(); i++) {
                SampledColorTarget t = targets.get(i);
                List<WindowedPresenter.Run> r = bound.get(i);
                GraphicsPipeline p = pipeline(t);
                t.record(cmd, (c, tw, th) -> {
                    commands.bindPipeline(c, p);
                    commands.bindVertexBuffer(c, vertexBuffer);
                    runs.record(c, p.pipelineLayout(), set0, set2, r, 0);
                }, 0f, 0f, 0f, 0f);
            }
        };
    }

    private SampledColorTarget take(int w, int h) {
        ArrayDeque<SampledColorTarget> q = free.get(key(w, h));
        return q != null && !q.isEmpty() ? q.pop() : new SampledColorTarget(device, w, h);
    }

    private static long key(int w, int h) {
        return (long) w << 32 | h;
    }

    /** The canvas pipeline on a target's render pass: every target is RGBA8 without depth, so one serves them all. */
    private GraphicsPipeline pipeline(SampledColorTarget t) {
        if (pipeline == null) {
            pipeline = new GraphicsPipeline(device, t.renderPass(), t.width(), t.height(),
                    CanvasShader.vertex().spirv(), "main", images.fragment().spirv(), "main",
                    GuiApp.canvasConfig(atlas, images.placeholder(), tables.buffer(), true));
        }
        return pipeline;
    }

    @Override
    public void close() {
        if (pipeline != null) {
            pipeline.close();
        }
        for (ArrayDeque<SampledColorTarget> q : free.values()) {
            q.forEach(SampledColorTarget::close);
        }
        for (List<SampledColorTarget> r : resting) {
            r.forEach(SampledColorTarget::close);
        }
        commands.close();
        arena.close();
    }
}
