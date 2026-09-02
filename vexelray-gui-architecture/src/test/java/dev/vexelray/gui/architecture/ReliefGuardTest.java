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
 * One ladder, in one place: no class in the framework may size a shadow of its own.
 *
 * <p>The same argument as {@link PaletteGuardTest}, one property later. Depth was seven hand-picked lengths spread
 * across the widgets — {@code 0.25f}, {@code 0.35f}, {@code 0.375f}, {@code 0.5f}, {@code 0.75f}, {@code 1f},
 * {@code 1.25f} — with the hover-and-press response written out as a {@code switch} over {@code InteractionState}
 * in three separate files, each agreeing with the others by memory. A theme could restyle every colour in the
 * interface and not move a single shadow.
 *
 * <p>Now a widget names a rung ({@code Relief.CONTROL}) and the theme answers with a length, so the ladder's base
 * and ratio are one decision and hover-lifts-one-rung is stated once. This guard is what stops the next widget
 * from starting an eighth depth — which matters more here than it did for colour, because every component still
 * on the backlog has a pressed state.
 */
class ReliefGuardTest {

    /**
     * Nothing may size its own shadow — not even the core. The theme is reachable from anywhere a node is built,
     * and a rung the ladder cannot express is a change to {@code Relief}, not a literal at a call site.
     */
    private static final Set<String> ALLOWED = Set.of();

    @Test
    void noFrameworkClassSizesItsOwnShadow() {
        List<String> violations = new ArrayList<>();
        for (String module : Bytecode.INSPECTED) {
            Path classes = Bytecode.classesOf(module);
            for (Path classFile : Bytecode.classFiles(classes)) {
                violations.addAll(DepthSources.sizedOutside(ALLOWED, Bytecode.read(classFile)));
            }
        }
        assertEquals(List.of(), violations,
                "a framework class chose a shadow size instead of a rung. Depth comes from Gui.theme(): "
                        + "gui.theme().elevation(Relief.CONTROL), or elevation(Relief.CONTROL, state) for hover and "
                        + "press. A depth the ladder is missing is a change to Relief's base or ratio, not a length "
                        + "at the call site — otherwise the response stops being one step and starts being seven.");
    }

    /** The guard's proof of life: a synthesized widget that mints its own depth must be reported. */
    @Test
    void theGuardReportsAWidgetThatSizesItsOwnShadow() {
        assertEquals(List.of("dev/vexelray/gui/widget/PretendWidget.style() sizes its own shadow: Length.rem"),
                DepthSources.sizedOutside(ALLOWED, classElevating("dev/vexelray/gui/widget/PretendWidget", "rem")),
                "the detector must catch exactly the call every widget's depth used to be written as");
    }

    /** And {@code Length.ZERO}, which is the same decision spelled as a constant. The rung for it is FLUSH. */
    @Test
    void theGuardReportsAFlushConstantToo() {
        assertEquals(List.of("dev/vexelray/gui/widget/PretendWidget.style() sizes its own shadow: Length.ZERO"),
                DepthSources.sizedOutside(ALLOWED, classElevating("dev/vexelray/gui/widget/PretendWidget", "ZERO")));
    }

    /**
     * The converse, and the reason the peephole is exact rather than approximate: a length that reaches
     * {@code elevation} from somewhere else — here a local, which is what a theme lookup compiles to once its
     * result is held — is not a minted depth and must not be reported.
     */
    @Test
    void theGuardPermitsADepthThatCameFromElsewhere() {
        assertEquals(List.of(),
                DepthSources.sizedOutside(ALLOWED, classElevatingFromALocal("dev/vexelray/gui/widget/PretendWidget")));
    }

    /**
     * Bytecode for {@code class <name> { static void style(Node n) { n.elevation(Length.<member>); } }} — a depth
     * minted straight into the setter, which is what the detector walks for.
     */
    private static byte[] classElevating(String internalName, String member) {
        return widget(internalName, mv -> {
            String length = "L" + DepthSources.LENGTH + ";";
            if (member.equals(member.toUpperCase(java.util.Locale.ROOT))) {
                mv.visitFieldInsn(Opcodes.GETSTATIC, DepthSources.LENGTH, member, length);
            } else {
                mv.visitLdcInsn(0.375f);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, DepthSources.LENGTH, member, "(F)" + length, false);
            }
        });
    }

    /** And the legitimate shape: the length is already in a local when the setter is called. */
    private static byte[] classElevatingFromALocal(String internalName) {
        return widget(internalName, mv -> mv.visitVarInsn(Opcodes.ALOAD, 1));
    }

    /** {@code class <name> { static void style(Node n, Length d) { n.elevation(<argument>); } }}. */
    private static byte[] widget(String internalName, java.util.function.Consumer<MethodVisitor> argument) {
        String node = "L" + DepthSources.NODE + ";";
        String length = "L" + DepthSources.LENGTH + ";";
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "style", "(" + node + length + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        argument.accept(mv);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DepthSources.NODE, DepthSources.ELEVATION, "(" + length + ")" + node,
                false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
