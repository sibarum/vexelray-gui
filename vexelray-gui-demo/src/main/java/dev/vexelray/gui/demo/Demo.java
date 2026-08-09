package dev.vexelray.gui.demo;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.text.TextLayout;

import static dev.vexelray.gui.core.model.RetainedNode.node;

/**
 * vexelray-gui showcase — step 1 (architecture.md §12 step 4). Builds a hard-coded {@link RetainedNode} tree and
 * renders it through {@link GuiApp}, which walks it into VexelRay's native Canvas and presents. No mutation queue,
 * layout engine, or input yet — this proves the "GUI draws only through VexelRay" thesis end to end.
 *
 * <p>Run: {@code Demo} (windowed until closed), {@code Demo <frames>} (capped), or
 * {@code Demo --capture <out.png>} (headless). Needs {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class Demo {

    private static final int W = 900;
    private static final int H = 560;

    public static void main(String[] args) throws Exception {
        RetainedNode ui = buildUi();

        if (args.length >= 1 && args[0].equals("--capture")) {
            String path = args.length >= 2 ? args[1] : "gui.png";
            GuiApp.capture(ui, W, H, 0.06f, 0.07f, 0.09f, path);
            System.out.println("captured " + path);
            return;
        }
        int maxFrames = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        try (GuiApp app = new GuiApp("VexelRay GUI", W, H)) {
            app.run(ui, maxFrames);
        }
        System.out.println("clean shutdown");
    }

    /** A hard-coded dashboard: window background, a title bar, two bordered cards with text, and a button. */
    private static RetainedNode buildUi() {
        Color bg = Color.rgb(0x11141b);
        Color panel = Color.rgb(0x1b2130);
        Color line = Color.rgb(0x2b3346);
        Color accent = Color.rgb(0x3aa0ff);
        Color ink = Color.rgb(0xeef2f8);
        Color dim = Color.rgb(0x93a0b4);

        RetainedNode root = node().bounds(0, 0, W, H).background(bg);

        root.add(node().bounds(0, 0, W, 64).background(panel)
                .text("VexelRay GUI", 30f, ink).align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE));

        root.add(card(24, 92, 410, 200, panel, line,
                "Retained tree", accent,
                "A hard-coded RetainedNode tree, walked by the emitter into VexelRay's native Canvas.", dim));
        root.add(card(466, 92, 410, 200, panel, line,
                "Native rendering", accent,
                "Rounded rects and MSDF text, one batched draw. The GUI writes no Vulkan or shaders.", dim));

        root.add(node().bounds(24, 320, 180, 52).background(accent).corner(12)
                .text("Get started", 20f, Color.WHITE).align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE));
        root.add(node().bounds(216, 320, 180, 52).background(panel).corner(12).border(1.5f, line)
                .text("Docs", 20f, dim).align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE));

        root.add(node().bounds(24, H - 44, W - 48, 28)
                .text("step 1: tree - Canvas - present   (no engine changes)", 15f, dim)
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE));
        return root;
    }

    /** A bordered card with a coloured title row and a wrapped body paragraph. */
    private static RetainedNode card(float x, float y, float w, float h, Color bg, Color border,
                                     String title, Color titleColor, String body, Color bodyColor) {
        RetainedNode c = node().bounds(x, y, w, h).background(bg).corner(16).border(1.5f, border);
        c.add(node().bounds(x + 20, y + 16, w - 40, 34)
                .text(title, 22f, titleColor).align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE));
        c.add(node().bounds(x + 20, y + 60, w - 40, h - 76)
                .text(body, 17f, bodyColor).align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP));
        return c;
    }

    private Demo() {
    }
}
