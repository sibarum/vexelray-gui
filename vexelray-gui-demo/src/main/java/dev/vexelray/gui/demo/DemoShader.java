package dev.vexelray.gui.demo;

import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.canvas.shadelet.CoreShadelets;
import dev.vexelray.shadelets.Effects;

/**
 * The demo's canvas shader: the core set and every effect in {@code vexelray-shadelets}, so a look that names one
 * (an icon's weight or outline, an icon button thickening under the pointer) is drawn rather than dropped.
 *
 * <p>An application opts in to effects here and nowhere else: by composing them into the one shader every window and
 * every capture is drawn with, handed to {@code GuiApp} at construction. The class is public with a public no-arg
 * constructor because the supirvast plugin in this module's pom finds it at build time and compiles it to SPIR-V into
 * the jar, which takes lowering it off the startup path.
 */
public final class DemoShader extends CanvasShader.Composed {

    public DemoShader() {
        super(CoreShadelets.composition().with(Effects.ALL));
    }
}
