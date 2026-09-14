package dev.vexelray.gui.architecture;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The detector behind the second half of {@link DispatchGuardTest}: which switches answer an unhandled case by
 * throwing.
 *
 * <p>Separated from the test for the same reason {@link DepthSources} is — so the test can point it at a
 * synthesized violation and prove it detects one.
 *
 * <p><b>Why this is real work and an attribute read was not.</b> "No sealed type" is a single attribute in the
 * class file. "No throwing default" is a statement about control flow: the switch instruction carries a label,
 * and whether the block at that label throws is a question about the instructions that follow it. So this walks
 * them — from the default label to the first instruction that ends the block — and reports the ones that end at
 * {@code athrow}.
 *
 * <p><b>What it deliberately lets through.</b> An exhaustive switch <em>expression</em> over an enum or a sealed
 * hierarchy has no {@code default:} in the source, and javac still emits one: {@code MatchException} if the
 * patterns were exhaustive when compiled, {@code IncompatibleClassChangeError} if an enum has gained a constant
 * since. Those are the compiler saying the class file and the world have diverged, not an author saying a case is
 * impossible, and reporting them would make the rule unsatisfiable — every exhaustive switch would violate it. So
 * the thrown type decides: {@link #COMPILER_FALLBACKS} is the compiler's vocabulary, and anything else is the
 * author's.
 *
 * <p><b>Where the walk stops.</b> At the first branch, return or new label after the default's. A default that
 * tests something and returns is not a throwing default even if a {@code throw} appears further down the method,
 * and stopping early is what keeps that from being reported. The cost is that a default which throws only after
 * branching is missed; that shape is not a case-is-impossible claim, which is what the rule is about.
 */
final class ThrowingDefaults {

    /**
     * The exceptions javac itself throws from a default it wrote. Neither is reachable from source: they say the
     * bytecode was compiled against a different version of a type than the one now loaded.
     */
    static final Set<String> COMPILER_FALLBACKS = Set.of(
            "java/lang/MatchException",
            "java/lang/IncompatibleClassChangeError");

    private ThrowingDefaults() {
    }

    /**
     * Every switch in {@code classFile} whose default throws. Returns
     * {@code "owner.method() answers an unhandled case by throwing: java/lang/IllegalStateException"} per
     * violation.
     */
    static List<String> thrownFrom(byte[] classFile) {
        List<String> violations = new ArrayList<>();
        String[] self = {"?"};

        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String sig, String superName, String[] ifs) {
                self[0] = name;
            }

            @Override
            public MethodVisitor visitMethod(int access, String method, String desc, String sig, String[] ex) {
                String where = self[0] + "." + method;
                return new MethodVisitor(Opcodes.ASM9) {
                    private final Set<Label> defaults = new HashSet<>();
                    private boolean watching;
                    private String pendingNew;

                    @Override
                    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
                        defaults.add(dflt);
                        stop();
                    }

                    @Override
                    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                        defaults.add(dflt);
                        stop();
                    }

                    @Override
                    public void visitLabel(Label label) {
                        // A new label ends whatever block was being watched, and may begin a default's own.
                        watching = defaults.contains(label);
                        pendingNew = null;
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (watching && opcode == Opcodes.NEW) {
                            pendingNew = type;
                        }
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (!watching) {
                            return;
                        }
                        if (opcode == Opcodes.ATHROW) {
                            if (pendingNew != null && !COMPILER_FALLBACKS.contains(pendingNew)) {
                                violations.add(where + " answers an unhandled case by throwing: " + pendingNew);
                            }
                            stop();
                        } else if (endsTheBlock(opcode)) {
                            stop();
                        }
                    }

                    @Override
                    public void visitJumpInsn(int opcode, Label label) {
                        stop();
                    }

                    private void stop() {
                        watching = false;
                        pendingNew = null;
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return violations;
    }

    /** The returns. A default that gives an answer is not a default that refuses to. */
    private static boolean endsTheBlock(int opcode) {
        return opcode == Opcodes.RETURN || opcode == Opcodes.ARETURN || opcode == Opcodes.IRETURN
                || opcode == Opcodes.LRETURN || opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN;
    }
}
