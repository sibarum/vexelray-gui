package dev.vexelray.gui.architecture;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The detector behind {@link ModelWriterGuardTest}: which classes write fields of the retained model.
 *
 * <p>Separated from the test so the test can point it at a synthesized violation and prove it actually detects
 * one. A guard nobody has seen fail is a guard nobody knows works.
 */
final class ModelWriters {

    /** The retained model, in internal form — the type whose fields only declared stages may assign. */
    static final String MODEL = "dev/vexelray/gui/core/model/RetainedNode";

    private ModelWriters() {
    }

    /**
     * Every write to a {@link #MODEL} field made by {@code classFile}, unless its top-level class is in
     * {@code allowedStages}. Returns {@code "owner.method() writes RetainedNode.field"} per violation.
     */
    static List<String> writesOutside(Set<String> allowedStages, byte[] classFile) {
        List<String> violations = new ArrayList<>();
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
            private String self = "?";

            @Override
            public void visit(int version, int access, String name, String sig, String superName, String[] ifs) {
                self = name;
            }

            @Override
            public MethodVisitor visitMethod(int access, String method, String desc, String sig, String[] ex) {
                if (allowedStages.contains(Bytecode.outer(self))) {
                    return null;   // a declared stage — no need to walk its body
                }
                String where = self + "." + method;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String field, String fieldDesc) {
                        boolean write = opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC;
                        if (write && MODEL.equals(owner)) {
                            violations.add(where + "() writes RetainedNode." + field);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return violations;
    }
}
