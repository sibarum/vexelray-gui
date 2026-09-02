package dev.vexelray.gui.architecture;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The detector behind {@link ReliefGuardTest}: which classes size a shadow themselves rather than naming a rung of
 * the theme's ladder.
 *
 * <p>Separated from the test for the same reason {@link ColourSources} is — so the test can point it at a
 * synthesized violation and prove it detects one.
 *
 * <p>The check is a peephole rather than a dataflow analysis, and that is exact here rather than approximate: a
 * length minted for an elevation is compiled immediately before the call that consumes it, because the receiver is
 * already on the stack by then. A depth that came from anywhere else — a theme, a field, a parameter — has some
 * other instruction in that slot and is not reported.
 */
final class DepthSources {

    /** The node's depth setter, and the length type it takes, in internal form. */
    static final String NODE = "dev/vexelray/gui/core/Node";
    static final String LENGTH = "dev/vexelray/gui/core/layout/Length";
    static final String ELEVATION = "elevation";

    /** The factories that turn a literal into a length. */
    private static final Set<String> FACTORIES = Set.of("em", "rem", "dp", "percent", "vw", "vh");

    /** And the named constants — {@code ZERO} is a depth decision too; the rung for it is {@code Relief.FLUSH}. */
    private static final Set<String> CONSTANTS = Set.of("ZERO", "AUTO", "FILL");

    private DepthSources() {
    }

    /**
     * Every shadow {@code classFile} sizes itself, unless its top-level class is in {@code allowed}. Returns
     * {@code "owner.method() sizes its own shadow: Length.rem"} per violation.
     */
    static List<String> sizedOutside(Set<String> allowed, byte[] classFile) {
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
                    return null;
                }
                String where = self + "." + method;
                return new MethodVisitor(Opcodes.ASM9) {

                    /** The length minted by the instruction just executed, or null if that was something else. */
                    private String pending;

                    @Override
                    public void visitMethodInsn(int op, String owner, String name, String d, boolean itf) {
                        if (NODE.equals(owner) && ELEVATION.equals(name) && pending != null) {
                            violations.add(where + "() sizes its own shadow: Length." + pending);
                        }
                        pending = op == Opcodes.INVOKESTATIC && LENGTH.equals(owner) && FACTORIES.contains(name)
                                ? name : null;
                    }

                    @Override
                    public void visitFieldInsn(int op, String owner, String name, String descriptor) {
                        pending = op == Opcodes.GETSTATIC && LENGTH.equals(owner) && CONSTANTS.contains(name)
                                ? name : null;
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return violations;
    }
}
