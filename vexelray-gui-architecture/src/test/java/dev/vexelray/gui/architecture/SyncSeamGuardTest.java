package dev.vexelray.gui.architecture;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A sync is not an edit: the setter a panel calls when its model moved must tell nobody.
 *
 * <p>A control has two setters and they differ in who is said to have moved it. The one named for what the
 * control <em>is</em> -- {@code Toggle.on}, {@code Slider.value}, {@code Segment.select} -- acts as the user
 * would and fires the callback. {@code show} displays the value and stays quiet. A panel re-reading its model
 * calls {@code show}; only something genuinely standing in for the user calls the other.
 *
 * <p>This is a guard rather than a convention because getting it wrong is not a cosmetic slip, and because the
 * failure is invisible at the call site that causes it. Reporting a sync as an edit is a <b>loop</b>: the row
 * tells the application it was edited, the application writes to the model, the model republishes the panel, the
 * panel syncs the row again -- without bound, and whatever the value is, because the notification never depended
 * on anything having moved. That is what {@code Inspector} and {@code Rows} did until the seam was split, and
 * nothing in either file read as wrong.
 *
 * <p>What this checks is the seam's <b>definition</b>: that {@code show} is honest wherever one is declared. It
 * does not check every use -- "this refresh should not have called the notifying setter" is a fact about a
 * caller's intent, and a guard that tried to read intent would either miss the case that started this or fail the
 * legitimate ones (a preset button really is standing in for the user, and really should notify). The definition
 * is the half that is mechanical, so it is the half the build owns.
 */
class SyncSeamGuardTest {

    private static final String SELF = "dev/vexelray/gui/widget/PretendControl";
    private static final String DESC = "(Z)L" + SELF + ";";
    private static final String CONSUMER = "java/util/function/Consumer";
    private static final String REPORTED = SELF + ".show" + DESC + " tells the application about a change nobody made";

    @Test
    void noControlTellsTheApplicationAboutAChangeNobodyMade() {
        List<String> violations = new ArrayList<>();
        for (String module : Bytecode.INSPECTED) {
            Path classes = Bytecode.classesOf(module);
            for (Path classFile : Bytecode.classFiles(classes)) {
                violations.addAll(SyncSeams.notifyingShows(Bytecode.read(classFile)));
            }
        }
        assertEquals(List.of(), violations,
                "a control's show(...) fired its own callback. show displays a value and tells nobody; the setter "
                        + "named for what the control is -- on, value, select -- is the one that speaks for the "
                        + "user. A panel that syncs through the notifying setter writes back to the model it just "
                        + "read, which republishes the panel, which syncs again, without bound.");
    }

    /** The guard's proof of life: a control whose {@code show} fires its own callback must be reported. */
    @Test
    void theGuardReportsAShowThatNotifies() {
        assertEquals(List.of(REPORTED), SyncSeams.notifyingShows(control(true, false)),
                "the detector must catch the call the loop was made of");
    }

    /**
     * And through a helper, which is the shape that actually occurs: {@code Toggle.show} and the notifying
     * {@code Toggle.on} deliberately share {@code move}, so only one of the two may go on to {@code accept}.
     */
    @Test
    void theGuardFollowsTheControlsOwnHelpers() {
        assertEquals(List.of(REPORTED), SyncSeams.notifyingShows(control(false, true)),
                "a show that reaches accept one call away is the same defect written one line further out");
    }

    /** The converse: a show that only moves the knob is the whole point, and must not be reported. */
    @Test
    void theGuardPermitsAShowThatStaysQuiet() {
        assertEquals(List.of(), SyncSeams.notifyingShows(control(false, false)));
    }

    /**
     * The other senses of the word are not the seam and are not examined: {@code Modals.show(Modal)} is static and
     * {@code AppWindow.show()} takes nothing. Either would otherwise be dragged in by name alone.
     */
    @Test
    void theGuardIgnoresTheOtherSensesOfTheWord() {
        assertEquals(List.of(), SyncSeams.notifyingShows(otherSenses()));
    }

    /**
     * {@code class PretendControl { Consumer onChange; PretendControl show(boolean v) { ... } }} -- where the body
     * either accepts directly, delegates to a helper that does, or does neither.
     */
    private static byte[] control(boolean tellsDirectly, boolean tellsViaHelper) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, SELF, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, "onChange", "L" + CONSUMER + ";", null, null).visitEnd();

        MethodVisitor show = cw.visitMethod(Opcodes.ACC_PUBLIC, SyncSeams.SHOW, DESC, null, null);
        show.visitCode();
        if (tellsDirectly) {
            accept(show);
        } else if (tellsViaHelper) {
            show.visitVarInsn(Opcodes.ALOAD, 0);
            show.visitMethodInsn(Opcodes.INVOKESPECIAL, SELF, "settle", "()V", false);
        }
        show.visitVarInsn(Opcodes.ALOAD, 0);
        show.visitInsn(Opcodes.ARETURN);
        show.visitMaxs(3, 2);
        show.visitEnd();

        MethodVisitor settle = cw.visitMethod(Opcodes.ACC_PRIVATE, "settle", "()V", null, null);
        settle.visitCode();
        if (tellsViaHelper) {
            accept(settle);
        }
        settle.visitInsn(Opcodes.RETURN);
        settle.visitMaxs(3, 1);
        settle.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code onChange.accept(null)} -- the call that makes a sync look like an edit. */
    private static void accept(MethodVisitor mv) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, SELF, "onChange", "L" + CONSUMER + ";");
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, CONSUMER, "accept", "(Ljava/lang/Object;)V", true);
    }

    /** {@code static void show(Object)} and {@code PretendControl show()} -- same word, neither the seam. */
    private static byte[] otherSenses() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, SELF, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, "onChange", "L" + CONSUMER + ";", null, null).visitEnd();

        MethodVisitor statik = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, SyncSeams.SHOW, "(Ljava/lang/Object;)V", null, null);
        statik.visitCode();
        statik.visitInsn(Opcodes.RETURN);
        statik.visitMaxs(0, 1);
        statik.visitEnd();

        MethodVisitor nullary = cw.visitMethod(Opcodes.ACC_PUBLIC, SyncSeams.SHOW, "()L" + SELF + ";", null, null);
        nullary.visitCode();
        accept(nullary);
        nullary.visitVarInsn(Opcodes.ALOAD, 0);
        nullary.visitInsn(Opcodes.ARETURN);
        nullary.visitMaxs(3, 1);
        nullary.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
