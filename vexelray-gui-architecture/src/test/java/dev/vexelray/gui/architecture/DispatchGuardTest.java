package dev.vexelray.gui.architecture;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>No sealed type whose cases are read from outside it, and no default case that throws.</b>
 *
 * <p>Both are the same failure — behaviour living somewhere other than the type it belongs to. A switch over a
 * type hierarchy is dispatch written by hand; put the method on the type. A {@code default:} that throws is an
 * admission that the type permits a state the code cannot handle, which means the type is wrong, not that the
 * case is impossible.
 *
 * <p>This guard exists because the rule was learned the expensive way. {@code vexelray-gui-typeset}'s IR began as
 * a sealed interface of seven records with a seven-case engine switch, and the switch was what made the
 * vocabulary closed: an application could not add a composition without editing the framework. Inverting it — an
 * open interface where each box arranges itself — is what let an application-defined box become a first-class
 * citizen (docs/typeset.md §3). The sealed switch was not a detail of that design; it <em>was</em> the design
 * error.
 *
 * <p>Where a consumer genuinely must stay closed (a wire format, a renderer), the answer is a <b>sink</b>: the
 * value gets {@code emitTo(Sink)} and the consumer implements {@code Sink}. The set of operations stays closed,
 * the set of value types stays open, and neither side switches. {@code Placed.Draw} is the worked example.
 *
 * <p><b>Scope.</b> Enforced today on {@link Bytecode#GUI_TYPESET}, the module designed under the rule.
 * {@code -core} still has three sealed types predating it — {@code Length}, {@code Mutation}, {@code Edit},
 * dispatched from {@code Length} itself, {@code Reconciler}, {@code Document} and {@code InputDispatcher} — and
 * widening {@link #RULED} to include them is the conversion's definition of done, not a box to tick early.
 */
class DispatchGuardTest {

    /** The modules the rule is enforced on. Widening this list is what converting a module means. */
    private static final List<String> RULED = List.of(Bytecode.GUI_TYPESET);

    @Test
    void noSealedTypes() {
        List<String> violations = new ArrayList<>();
        for (String module : RULED) {
            Path classes = Bytecode.classesOf(module);
            for (Path classFile : Bytecode.classFiles(classes)) {
                if (Sealing.isSealed(Bytecode.read(classFile))) {
                    violations.add(classes.relativize(classFile).toString());
                }
            }
        }
        assertEquals(List.of(), violations,
                "a sealed type is a closed vocabulary, and the only thing to do with one is switch on it. Put the "
                        + "behaviour on the type instead; where a consumer must stay closed, invert to a sink "
                        + "(Placed.Draw is the worked example). See docs/typeset.md §3.1.");
    }

    /**
     * The guard's own proof of life. A scan that never matches anything is indistinguishable from a clean
     * codebase, so synthesize both answers: a sealed interface must be seen, an ordinary one must not.
     */
    @Test
    void theGuardReportsASealedType() {
        assertTrue(Sealing.isSealed(sealedInterface()), "a PermittedSubclasses attribute must be detected");
        assertFalse(Sealing.isSealed(openInterface()), "and an ordinary interface must not trip it");
    }

    /** Bytecode for {@code sealed interface Probe permits Probe$A {}}. */
    private static byte[] sealedInterface() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                "dev/vexelray/gui/typeset/Probe", null, "java/lang/Object", null);
        cw.visitPermittedSubclass("dev/vexelray/gui/typeset/Probe$A");
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Bytecode for the same interface without the seal — the case that must stay green. */
    private static byte[] openInterface() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                "dev/vexelray/gui/typeset/Probe", null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
