package dev.vexelray.gui.architecture;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Detects a sealed type in compiled bytecode, for {@link DispatchGuardTest}.
 *
 * <p>Reads the {@code PermittedSubclasses} attribute rather than scanning source or the constant pool. The
 * attribute is present exactly when the type is sealed, so this neither misses a seal written with a
 * fully-qualified permits clause nor false-positives on a class that merely mentions a subtype's name.
 */
final class Sealing {

    private Sealing() {
    }

    /** Whether {@code classBytes} declares a sealed type. */
    static boolean isSealed(byte[] classBytes) {
        boolean[] sealed = {false};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visitPermittedSubclass(String permittedSubclass) {
                sealed[0] = true;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return sealed[0];
    }
}
