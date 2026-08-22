package dev.vexelray.gui.architecture;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Rule 4 of docs/architecture-proof-plan.md §2: only the declared stages write the retained model.
 *
 * <p>{@code RetainedNode}'s fields are public, because the stages that legitimately write them span four packages
 * and Java has no friend mechanism. That openness is exactly why this guard exists: it enumerates the stages
 * allowed to write and fails on anything else.
 *
 * <p>It is not hypothetical. {@code TreeRenderer} used to resolve caret-follow scroll by assigning
 * {@code n.scrollX} mid-draw, and because {@code TreeRenderer.emit} runs only from {@code GuiApp}, that made a
 * text field behave one way on screen and another way headless or over a wire — silently falsifying claim C1 for
 * as long as it went unnoticed. The behaviour now lives in the compute phase (layout-read-model.md §2.1); this
 * guard is what stops it, or anything like it, drifting back into a read path.
 */
class ModelWriterGuardTest {

    /**
     * The stages permitted to write the retained model, by top-level class (internal form). Each corresponds to a
     * phase of {@code Gui.frame}. Adding an entry here is a deliberate architectural decision — it widens who may
     * mutate the model — and is never the right way to fix a failing build.
     */
    private static final Set<String> STAGES = Set.of(
            "dev/vexelray/gui/core/model/RetainedNode",     // the model's own typed accessors
            "dev/vexelray/gui/core/model/Reconciler",       // command stage: applies Mutations (the single writer)
            "dev/vexelray/gui/core/input/InputDispatcher",  // dispatch stage: proposes scroll offsets, focus state
            "dev/vexelray/gui/core/layout/FlexLayout",      // compute stage: boxes, viewport, overflow, scroll clamp
            "dev/vexelray/gui/core/Gui");                   // compute stage: caret-follow scroll + text metrics

    @Test
    void onlyTheDeclaredStagesWriteTheRetainedModel() {
        List<String> violations = new ArrayList<>();
        for (String module : Bytecode.INSPECTED) {
            Path classes = Bytecode.classesOf(module);
            for (Path classFile : Bytecode.classFiles(classes)) {
                violations.addAll(ModelWriters.writesOutside(STAGES, Bytecode.read(classFile)));
            }
        }
        assertEquals(List.of(), violations,
                "only a declared stage of Gui.frame may write RetainedNode (architecture-proof-plan.md §2 rule 4, "
                        + "layout-read-model.md §2.1). A renderer, a widget, or the publish step writing model "
                        + "state means behaviour that exists only in that host — the class of bug CaretScrollTest "
                        + "was written for. Computed values belong in the compute phase; publish only copies.");
    }

    /**
     * The guard's own proof of life: a synthesized class that writes {@code RetainedNode.scrollX} from a
     * disallowed package must be reported. Without this, a detector that silently matched nothing would look
     * exactly like a clean codebase.
     */
    @Test
    void theGuardReportsAWriteFromADisallowedStage() {
        byte[] renderer = classWritingScrollX("dev/vexelray/gui/core/app/PretendRenderer");

        assertEquals(List.of("dev/vexelray/gui/core/app/PretendRenderer.draw() writes RetainedNode.scrollX"),
                ModelWriters.writesOutside(STAGES, renderer),
                "the detector must catch exactly the write TreeRenderer.updateHScroll used to make");
    }

    /** And the converse: the same write from a declared stage is legitimate and must not be reported. */
    @Test
    void theGuardPermitsAWriteFromADeclaredStage() {
        byte[] computeStage = classWritingScrollX("dev/vexelray/gui/core/Gui");

        assertEquals(List.of(), ModelWriters.writesOutside(STAGES, computeStage));
    }

    /** Bytecode for {@code class <name> { static void draw(RetainedNode n) { n.scrollX = 0f; } }}. */
    private static byte[] classWritingScrollX(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "draw",
                "(L" + ModelWriters.MODEL + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, ModelWriters.MODEL, "scrollX", "F");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
