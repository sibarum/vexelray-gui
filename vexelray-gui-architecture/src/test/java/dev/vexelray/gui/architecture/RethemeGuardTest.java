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
 * A theme can be swapped at runtime, and this is what keeps it true: no framework class may resolve a theme decision
 * early and write the answer onto a node.
 *
 * <p>A node holds a {@code Paint} — a role, a role in a state, a role thinned — and the renderer resolves it against
 * the tree's theme every frame. A node handed {@code theme.color(Role.PANEL)} instead holds a dark-grey constant,
 * and after a swap to the light theme it is a dark-grey panel on a white page. Nothing fails; it just stays wrong,
 * which is why this has to be a guard rather than something a reviewer notices.
 */
class RethemeGuardTest {

    /**
     * The only classes allowed to write a resolved colour, and why. Never the way to fix a failing build: a widget
     * writing a colour the theme decides writes the role ({@code background(Role.PANEL)}), the role in its state
     * ({@code Role.ACTION.at(state)}), or a paint built from them ({@code .alpha(...)}, {@code .mix(...)}).
     */
    private static final Set<String> ALLOWED = Set.of(
            // The theme itself: its withers (focusRing, icons) answer lit() and letterpress() by asking the theme
            // they wrap, which is a theme delegating, not a node being written.
            "dev/vexelray/gui/core/style/Theme",
            // The one widget whose subject is colour: a swatch shows the colour the user chose, which is the same in
            // every theme by definition. Its own chrome writes roles like everything else.
            "dev/vexelray/gui/widget/ColorPicker");

    @Test
    void noFrameworkClassWritesAThemeDecisionDown() {
        List<String> violations = new ArrayList<>();
        for (String module : Bytecode.INSPECTED) {
            Path classes = Bytecode.classesOf(module);
            for (Path classFile : Bytecode.classFiles(classes)) {
                violations.addAll(ThemeSources.settledOutside(ALLOWED, Bytecode.read(classFile)));
            }
        }
        assertEquals(List.of(), violations,
                "a framework class resolved a theme decision and wrote the answer onto a node, so a theme swapped "
                        + "later cannot reach it. Write the Paint: background(Role.PANEL), "
                        + "background(Role.ACTION.at(state)), Role.ACTION.alpha(0.9f); and Finish.LIT / "
                        + "Finish.LETTERPRESS rather than theme.lit() / theme.letterpress().");
    }

    /** Proof of life: a synthesized widget writing a resolved colour must be reported. */
    @Test
    void theGuardReportsAResolvedColourWritten() {
        assertEquals(List.of("dev/vexelray/gui/widget/PretendWidget.style() writes a resolved colour: Node.background"),
                ThemeSources.settledOutside(ALLOWED, classCalling("dev/vexelray/gui/widget/PretendWidget",
                        ThemeSources.NODE, "background", "(L" + ColourSources.COLOR + ";)L" + ThemeSources.NODE + ";",
                        false)));
    }

    /** And the theme's flags asked for, which can only be to write them down. */
    @Test
    void theGuardReportsAFinishAskedFor() {
        assertEquals(List.of("dev/vexelray/gui/widget/PretendWidget.style() asks Theme.lit"),
                ThemeSources.settledOutside(ALLOWED, classCalling("dev/vexelray/gui/widget/PretendWidget",
                        ThemeSources.THEME, "lit", "()Z", true)));
    }

    /** And the same mistake with the right type: a theme colour wrapped as the constant paint. */
    @Test
    void theGuardReportsAThemeColourWrappedAsAConstant() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "dev/vexelray/gui/widget/PretendWidget", null, "java/lang/Object",
                null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "style", "()V", null, null);
        mv.visitCode();
        String role = "Ldev/vexelray/gui/core/style/Role;";
        String colour = "L" + ColourSources.COLOR + ";";
        mv.visitInsn(Opcodes.ACONST_NULL);   // the theme
        mv.visitFieldInsn(Opcodes.GETSTATIC, "dev/vexelray/gui/core/style/Role", "PANEL", role);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, ThemeSources.THEME, "color", "(" + role + ")" + colour, true);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, ThemeSources.PAINT, "of",
                "(" + colour + ")L" + ThemeSources.PAINT + ";", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        assertEquals(List.of("dev/vexelray/gui/widget/PretendWidget.style() wraps a theme colour as a constant: "
                        + "Paint.of(theme.color(...))"),
                ThemeSources.settledOutside(ALLOWED, cw.toByteArray()));
    }

    /** The converse: the paint overload is the right call and must not be reported. */
    @Test
    void theGuardPermitsAPaint() {
        assertEquals(List.of(),
                ThemeSources.settledOutside(ALLOWED, classCalling("dev/vexelray/gui/widget/PretendWidget",
                        ThemeSources.NODE, "background",
                        "(Ldev/vexelray/gui/core/style/Paint;)L" + ThemeSources.NODE + ";", false)));
    }

    /** Bytecode for {@code class <name> { static void style() { <owner>.<method><desc> on nulls; } }}. */
    private static byte[] classCalling(String internalName, String owner, String method, String desc, boolean itf) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "style", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);   // the receiver
        int args = org.objectweb.asm.Type.getArgumentTypes(desc).length;
        for (int i = 0; i < args; i++) {
            mv.visitInsn(Opcodes.ACONST_NULL);
        }
        mv.visitMethodInsn(itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, owner, method, desc, itf);
        mv.visitInsn(Opcodes.POP);   // a Node or a boolean: one slot either way
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
