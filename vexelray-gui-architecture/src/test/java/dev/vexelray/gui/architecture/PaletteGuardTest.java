package dev.vexelray.gui.architecture;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One palette, in one place: no class in the framework may mint a colour of its own.
 *
 * <p>This is not hypothetical either. The look used to live as fifteen {@code Color.rgb(0x…)} constants copied
 * across eight files — {@code 0x2b3346} appeared ten times, {@code 0xeef2f8} seven — so "the border colour" was
 * whatever each widget remembered it to be, a second theme was impossible without forking the widgets, and the
 * light-theme question could not even be asked. Every one of those values is now a level of
 * {@code Palette}'s ladders, named through a {@code Role}. This guard is what stops the next widget from quietly
 * starting a sixteenth.
 */
class PaletteGuardTest {

    /**
     * The only classes allowed to name a colour outright, and why. Adding an entry here re-opens the seam the
     * theme exists to close, and is never the right way to fix a failing build: a widget that needs a colour the
     * palette cannot express needs a {@code Role} (which is a one-line lambda), not a literal.
     */
    private static final Set<String> ALLOWED = Set.of(
            // The palette's own vocabulary: NONE is "paint nothing", which is not a palette decision.
            "dev/vexelray/gui/core/style/Role",
            // The model's last-resort default for a text node that never declared a colour. The renderer passes
            // the theme's ink in its place (RetainedNode.textColor(Color)), so this is a floor, not a look.
            "dev/vexelray/gui/core/model/RetainedNode",
            // The one widget whose *subject* is colour. Everything it wears -- panel, borders, wells, the
            // accent on a hovered swatch -- still comes from a Role, and must. What is minted here is the
            // opposite category: the red at 0 degrees on the hue ramp, the black whose coverage *is* the value
            // axis, the alpha checkerboard, and the white-inside-black marker rings. None of those is a look
            // decision, and a Role is exactly the wrong home for them -- a Role is a function of the palette, so
            // a light theme would repaint the ramp and the ramp would then be lying about what it depicts. The
            // marker is the same argument in physics rather than in semantics: it has to read against every
            // colour the square can show, which is a constraint no palette can satisfy and none should try to.
            "dev/vexelray/gui/widget/ColorPicker");

    @Test
    void noFrameworkClassMintsItsOwnColour() {
        List<String> violations = new ArrayList<>();
        for (String module : Bytecode.INSPECTED) {
            Path classes = Bytecode.classesOf(module);
            for (Path classFile : Bytecode.classFiles(classes)) {
                violations.addAll(ColourSources.mintedOutside(ALLOWED, Bytecode.read(classFile)));
            }
        }
        assertEquals(List.of(), violations,
                "a framework class named a colour instead of a role. Colour comes from Gui.theme(): "
                        + "gui.theme().color(Role.PANEL), or color(Role.PANEL, state) for hover and press. A shade "
                        + "the vocabulary is missing is a new Role (a lambda over Palette), not a new literal — "
                        + "otherwise a second theme has to fork the widget to exist.");
    }

    /**
     * The guard's own proof of life: a synthesized widget that calls {@code Color.rgb} must be reported. Without
     * this, a detector that silently matched nothing would look exactly like a clean codebase.
     */
    @Test
    void theGuardReportsAWidgetThatMintsAColour() {
        assertEquals(List.of("dev/vexelray/gui/widget/PretendWidget.style() mints Color.rgb"),
                ColourSources.mintedOutside(ALLOWED, classMinting("dev/vexelray/gui/widget/PretendWidget", "rgb")),
                "the detector must catch exactly the call every widget's palette used to be built from");
    }

    /** And the named constants, which are the same decision with the hex spelled out in English. */
    @Test
    void theGuardReportsANamedConstantToo() {
        assertEquals(List.of("dev/vexelray/gui/widget/PretendWidget.style() mints Color.WHITE"),
                ColourSources.mintedOutside(ALLOWED, classMinting("dev/vexelray/gui/widget/PretendWidget", "WHITE")));
    }

    /** The converse: the same call from a declared source is legitimate and must not be reported. */
    @Test
    void theGuardPermitsThePaletteItself() {
        assertEquals(List.of(),
                ColourSources.mintedOutside(ALLOWED, classMinting("dev/vexelray/gui/core/style/Role", "rgb")));
    }

    /**
     * Bytecode for {@code class <name> { static void style() { Color.<member>; } }} — a literal colour minted in a
     * method body, which is what the detector walks for. {@code member} is either a factory or a named constant.
     */
    private static byte[] classMinting(String internalName, String member) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "style", "()V", null, null);
        mv.visitCode();
        String colour = "L" + ColourSources.COLOR + ";";
        if (member.equals(member.toUpperCase(java.util.Locale.ROOT))) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, ColourSources.COLOR, member, colour);
        } else {
            mv.visitLdcInsn(0x2b3346);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, ColourSources.COLOR, member, "(I)" + colour, false);
        }
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
