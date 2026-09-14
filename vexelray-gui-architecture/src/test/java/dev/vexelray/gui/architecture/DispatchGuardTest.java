package dev.vexelray.gui.architecture;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
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
 * citizen (docs/reference/typeset.md §3). The sealed switch was not a detail of that design; it <em>was</em> the design
 * error.
 *
 * <p>Where a consumer genuinely must stay closed (a wire format, a renderer), the answer is a <b>sink</b>: the
 * value gets {@code emitTo(Sink)} and the consumer implements {@code Sink}. The set of operations stays closed,
 * the set of value types stays open, and neither side switches. {@code Placed.Draw} is the worked example.
 *
 * <p><b>Scope, and why the two halves differ.</b> <em>No throwing default</em> is enforced on every module in
 * {@link Bytecode#INSPECTED}: nothing anywhere has one, so there is no conversion owed and no reason to hold the
 * rule narrowly. <em>No sealed type</em> is enforced on {@link #RULED} — {@code -typeset}, {@code -plot} and
 * {@code -draw}, the modules designed or ported under the rule, which is why {@code -plot}'s enclosure algebra is
 * three types carrying the operations rather than the three-case switch it came from. {@code -core} still has
 * four sealed types predating it — {@code Length}, {@code Mutation}, {@code Edit} and {@code InputEvent},
 * dispatched from {@code Length} itself, {@code Reconciler}, {@code Document} and {@code InputDispatcher} — and
 * widening {@link #RULED} to include them is the conversion's definition of done, not a box to tick early
 * (docs/plans/todo.md §1).
 */
class DispatchGuardTest {

    /** The modules the <em>sealed-type</em> half is enforced on. Widening this list is what converting one means. */
    private static final List<String> RULED = List.of(Bytecode.GUI_TYPESET, Bytecode.GUI_PLOT, Bytecode.GUI_DRAW);

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
                        + "(Placed.Draw is the worked example). See docs/reference/typeset.md §3.1.");
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

    /**
     * An enum whose constants carry bodies compiles to a sealed class permitting its anonymous constant
     * subclasses. That shape is the rule's <em>cure</em>, not a violation — it is how an enum puts behaviour on
     * the type instead of in a switch — so the guard must let it through, and must be seen to do so on purpose
     * rather than by an accident of what the scan happens to look at.
     */
    @Test
    void theGuardLetsAnEnumWithConstantBodiesThrough() {
        assertFalse(Sealing.isSealed(enumWithConstantBodies()),
                "penalising a constant-specific body would push authors back toward the switch it replaced");
    }

    /**
     * <b>Every inspected module, not just {@link #RULED}.</b> The two halves of the rule carry different debts.
     * Converting a module away from sealed types is real work and {@code -core} has four still waiting, so that
     * half is widened a module at a time. No module anywhere has a throwing default — that was checked before
     * this was written, not assumed — so there is nothing to convert and no reason to enforce it narrowly. A
     * property the whole codebase already has is one the build should be holding.
     */
    @Test
    void noThrowingDefaults() {
        List<String> violations = new ArrayList<>();
        for (String module : Bytecode.INSPECTED) {
            Path classes = Bytecode.classesOf(module);
            for (Path classFile : Bytecode.classFiles(classes)) {
                violations.addAll(ThrowingDefaults.thrownFrom(Bytecode.read(classFile)));
            }
        }
        assertEquals(List.of(), violations,
                "a default that throws says the type permits a state the code cannot handle, which means the type "
                        + "is wrong and not that the case is impossible. Give the type the method, or invert to a "
                        + "sink. See docs/reference/typeset.md §3.1.");
    }

    /** The guard's proof of life: an author's default that throws must be seen. */
    @Test
    void theGuardReportsAThrowingDefault() {
        assertEquals(List.of("dev/vexelray/gui/typeset/Probe.pick answers an unhandled case by throwing: "
                        + "java/lang/IllegalStateException"),
                ThrowingDefaults.thrownFrom(classWhoseDefault(Opcodes.ATHROW, "java/lang/IllegalStateException")),
                "the detector must catch the shape the rule is about");
    }

    /**
     * And the case that decides whether the rule is satisfiable at all. An exhaustive switch <em>expression</em>
     * has no {@code default:} in its source and javac emits one anyway — verified against real output, which for
     * an enum on this JDK is {@code new MatchException; athrow} on the default target. Reporting that would make
     * every exhaustive switch a violation, which would push authors toward the hand-written dispatch the rule
     * exists to remove.
     */
    @Test
    void theGuardLetsTheCompilersOwnFallbackThrough() {
        for (String fallback : ThrowingDefaults.COMPILER_FALLBACKS) {
            assertEquals(List.of(), ThrowingDefaults.thrownFrom(classWhoseDefault(Opcodes.ATHROW, fallback)),
                    fallback + " is the compiler saying the class file and the world diverged, not an author "
                            + "saying a case is impossible");
        }
    }

    /** And a default that answers is the ordinary shape, which must stay green. */
    @Test
    void theGuardPermitsADefaultThatAnswers() {
        assertEquals(List.of(), ThrowingDefaults.thrownFrom(classWhoseDefault(Opcodes.IRETURN, null)));
    }

    /**
     * Bytecode for {@code static int pick(int k) { switch (k) { case 0: return 1; default: <ending> } }} — where
     * the default block either throws {@code thrown} or returns.
     */
    private static byte[] classWhoseDefault(int ending, String thrown) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "dev/vexelray/gui/typeset/Probe", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "pick", "(I)I", null, null);
        Label caseZero = new Label();
        Label dflt = new Label();
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitTableSwitchInsn(0, 0, dflt, caseZero);

        mv.visitLabel(caseZero);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(dflt);
        if (ending == Opcodes.ATHROW) {
            mv.visitTypeInsn(Opcodes.NEW, thrown);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, thrown, "<init>", "()V", false);
            mv.visitInsn(Opcodes.ATHROW);
        } else {
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.IRETURN);
        }
        mv.visitMaxs(2, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
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

    /** Bytecode for {@code enum Probe { A { … } }} — sealed by the compiler, not by its author. */
    private static byte[] enumWithConstantBodies() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ENUM | Opcodes.ACC_ABSTRACT,
                "dev/vexelray/gui/typeset/Probe", null, "java/lang/Enum", null);
        cw.visitPermittedSubclass("dev/vexelray/gui/typeset/Probe$1");
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
