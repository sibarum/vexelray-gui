package dev.vexelray.gui.architecture;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Detects an author-declared sealed type in compiled bytecode, for {@link DispatchGuardTest}.
 *
 * <p>Reads the {@code PermittedSubclasses} attribute rather than scanning source or the constant pool. The
 * attribute is present exactly when the type is sealed, so this neither misses a seal written with a
 * fully-qualified permits clause nor false-positives on a class that merely mentions a subtype's name.
 *
 * <p><b>Enums are excluded, deliberately.</b> An enum whose constants carry bodies — {@code A { double f() {…} }}
 * — compiles to a sealed class permitting its anonymous constant subclasses, so the attribute appears without
 * anyone having written {@code sealed}. That shape is not the violation; it is the <em>cure</em>. The rule asks
 * that behaviour live on the type rather than in a switch over it, and a constant-specific body is precisely how
 * an enum does that. Flagging it would penalise the fix and push authors back toward the switch.
 *
 * <p>The cost of the exclusion is that this guard cannot see an enum being switched over. That belongs to the
 * unmechanised half of the rule (docs/todo.md §1.6) and is hand-reviewed, so nothing is lost that was covered
 * before.
 */
final class Sealing {

    private Sealing() {
    }

    /** Whether {@code classBytes} declares a sealed type that its author wrote as sealed. */
    static boolean isSealed(byte[] classBytes) {
        boolean[] sealed = {false};
        boolean[] isEnum = {false};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                isEnum[0] = (access & Opcodes.ACC_ENUM) != 0;
            }

            @Override
            public void visitPermittedSubclass(String permittedSubclass) {
                sealed[0] = true;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return sealed[0] && !isEnum[0];
    }
}
