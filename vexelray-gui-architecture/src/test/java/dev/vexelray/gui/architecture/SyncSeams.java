package dev.vexelray.gui.architecture;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The detector behind {@link SyncSeamGuardTest}: which controls tell their application about a change nobody made.
 *
 * <p>Separated from the test for the same reason {@link DepthSources} is — so the test can point it at a
 * synthesized violation and prove it detects one.
 *
 * <p><b>What counts as the seam.</b> The word {@code show} carries more than one sense in this framework:
 * {@code Modals.show(Modal)} puts a dialog up, {@code AppWindow.show()} maps a window, {@code Node.showing}
 * asks about the tree. The one this guard is about is the <em>value</em> sense — the fluent setter a panel calls
 * when its model moved underneath it — and that sense has an exact shape: an instance method named {@code show},
 * taking one argument, returning the class it is declared on. The other senses are static, take nothing, or
 * return something else, and are not examined.
 *
 * <p><b>What counts as telling.</b> A direct call to {@code accept} on one of the {@code java.util.function}
 * consumer interfaces — which is how every control here holds its {@code onChange} or {@code onCommit} — reached
 * either straight from {@code show} or through the class's own methods, since {@code Toggle.show} deliberately
 * shares {@code move} with the notifying {@code set} and only one of the two may end at {@code accept}. The walk
 * is over {@code invoke*} instructions and so does not follow a lambda, which is an {@code invokedynamic}: a
 * {@code show} that notified from inside a closure would not be reported. That shape has never existed here, and
 * the guard is stated for what it does catch rather than implied to catch everything.
 */
final class SyncSeams {

    /** The value-sense setter's name. */
    static final String SHOW = "show";

    /** The functional interfaces a control tells its application through. */
    private static final Set<String> NOTIFIERS = Set.of(
            "java/util/function/Consumer",
            "java/util/function/BiConsumer",
            "java/util/function/IntConsumer",
            "java/util/function/LongConsumer",
            "java/util/function/DoubleConsumer");

    private static final String ACCEPT = "accept";

    private SyncSeams() {
    }

    /**
     * Every {@code show} in {@code classFile} that notifies. Returns {@code "owner.show(desc) tells the
     * application about a change nobody made"} per violation.
     */
    static List<String> notifyingShows(byte[] classFile) {
        Map<String, Boolean> tells = new LinkedHashMap<>();
        Map<String, Set<String>> callsOwn = new LinkedHashMap<>();
        List<String> seams = new ArrayList<>();
        String[] self = {"?"};

        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String sig, String superName, String[] ifs) {
                self[0] = name;
            }

            @Override
            public MethodVisitor visitMethod(int access, String method, String desc, String sig, String[] ex) {
                String key = method + desc;
                tells.putIfAbsent(key, false);
                callsOwn.putIfAbsent(key, new LinkedHashSet<>());
                if (isValueSeam(access, method, desc, self[0])) {
                    seams.add(key);
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String name, String d, boolean itf) {
                        if (NOTIFIERS.contains(owner) && ACCEPT.equals(name)) {
                            tells.put(key, true);
                        } else if (owner.equals(self[0])) {
                            callsOwn.get(key).add(name + d);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);

        Set<String> reaches = reaching(tells, callsOwn);
        List<String> violations = new ArrayList<>();
        for (String seam : seams) {
            if (reaches.contains(seam)) {
                violations.add(self[0] + "." + seam + " tells the application about a change nobody made");
            }
        }
        return violations;
    }

    /** The value-sense {@code show}: an instance method, one argument, returning the class it is declared on. */
    private static boolean isValueSeam(int access, String method, String desc, String self) {
        if (!SHOW.equals(method) || (access & Opcodes.ACC_STATIC) != 0) {
            return false;
        }
        if (Type.getArgumentTypes(desc).length != 1) {
            return false;
        }
        Type returned = Type.getReturnType(desc);
        return returned.getSort() == Type.OBJECT && returned.getInternalName().equals(self);
    }

    /** Methods that reach {@code accept}, directly or through the class's own methods. A fixed point. */
    private static Set<String> reaching(Map<String, Boolean> direct, Map<String, Set<String>> callsOwn) {
        Set<String> reaches = new LinkedHashSet<>();
        direct.forEach((method, tells) -> {
            if (tells) {
                reaches.add(method);
            }
        });
        for (boolean grew = true; grew; ) {
            grew = false;
            for (Map.Entry<String, Set<String>> caller : callsOwn.entrySet()) {
                if (reaches.contains(caller.getKey())) {
                    continue;
                }
                for (String callee : caller.getValue()) {
                    if (reaches.contains(callee)) {
                        reaches.add(caller.getKey());
                        grew = true;
                        break;
                    }
                }
            }
        }
        return reaches;
    }
}
