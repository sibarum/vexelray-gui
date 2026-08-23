package dev.vexelray.gui.architecture;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The detector behind {@link PaletteGuardTest}: which classes mint a {@code Color} of their own rather than
 * asking the theme for one.
 *
 * <p>Separated from the test so the test can point it at a synthesized violation and prove it actually detects
 * one. A guard nobody has seen fail is a guard nobody knows works.
 */
final class ColourSources {

    /** The engine's colour type, in internal form. */
    static final String COLOR = "dev/vexelray/canvas/Color";

    /** The factories that turn literal components into a colour. */
    private static final Set<String> FACTORIES = Set.of("rgb", "rgba", "argb");

    /** The named constants — a hard-coded colour that happens to have a name is still a hard-coded colour. */
    private static final Set<String> CONSTANTS = Set.of("WHITE", "BLACK", "TRANSPARENT");

    private ColourSources() {
    }

    /**
     * Every literal colour {@code classFile} mints, unless its top-level class is in {@code allowed}. Returns
     * {@code "owner.method() mints Color.rgb"} per violation.
     */
    static List<String> mintedOutside(Set<String> allowed, byte[] classFile) {
        List<String> violations = new ArrayList<>();
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
            private String self = "?";

            @Override
            public void visit(int version, int access, String name, String sig, String superName, String[] ifs) {
                self = name;
            }

            @Override
            public MethodVisitor visitMethod(int access, String method, String desc, String sig, String[] ex) {
                if (allowed.contains(Bytecode.outer(self))) {
                    return null;   // an allowed source — no need to walk its body
                }
                String where = self + "." + method;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String name, String d, boolean itf) {
                        if (COLOR.equals(owner) && FACTORIES.contains(name)) {
                            violations.add(where + "() mints Color." + name);
                        }
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String field, String fieldDesc) {
                        if (opcode == Opcodes.GETSTATIC && COLOR.equals(owner) && CONSTANTS.contains(field)) {
                            violations.add(where + "() mints Color." + field);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return violations;
    }
}
