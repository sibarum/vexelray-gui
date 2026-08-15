package dev.vexelray.gui.architecture;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rules 1 and 3 of docs/architecture-proof-plan.md §2: the GUI speaks only {@code atchung-core}. Transport is
 * chosen at the application edge, so no class in {@code gui-core} or {@code gui-widget} may reference elektro-Q
 * or the bridge. That is what makes "the fast in-VM path never pays for the network" (claim C2) structural rather
 * than a matter of discipline, and it is what lets the same widget code run in-VM or over a wire (C1).
 *
 * <p>This is the guard that must go <b>red</b> the moment someone reaches for a transport inside the GUI.
 */
class LayeringGuardTest {

    /** Internal-form package prefixes that must never appear in a GUI class's constant pool. */
    private static final List<String> FORBIDDEN = List.of(
            "sibarum/elektro",           // the wire stack itself
            "sibarum/atchung/elektroq"); // the bridge — application-edge wiring, not GUI code

    @Test
    void guiCoreAndWidgetNeverReferenceTheWireStack() {
        List<String> violations = new ArrayList<>();
        for (String module : List.of(Bytecode.GUI_CORE, Bytecode.GUI_WIDGET)) {
            Path classes = Bytecode.classesOf(module);
            for (Path classFile : Bytecode.classFiles(classes)) {
                byte[] bytes = Bytecode.read(classFile);
                for (String forbidden : FORBIDDEN) {
                    if (Bytecode.contains(bytes, forbidden.getBytes(StandardCharsets.UTF_8))) {
                        violations.add(classes.relativize(classFile) + " references " + forbidden);
                    }
                }
            }
        }
        assertEquals(List.of(), violations,
                "gui-core and gui-widget must depend on atchung-core only (architecture-proof-plan.md §2 rules 1 "
                        + "and 3). Choose the transport at the application edge and map to wire DTOs there; the "
                        + "GUI publishes and subscribes to plain Topics and never names a transport.");
    }

    /**
     * The guard's own proof of life: a reference to a forbidden type lands in the constant pool where the scan
     * looks, and an unrelated class does not trip it. Without both halves, a scan that never matched anything
     * would be indistinguishable from a clean codebase.
     */
    @Test
    void theGuardReportsAWireStackReference() {
        assertTrue(Bytecode.contains(classWithFieldOfType("sibarum/elektro/queue/Conduit"),
                        "sibarum/elektro".getBytes(StandardCharsets.UTF_8)),
                "a field typed as a transport must be visible to the constant-pool scan");
        assertTrue(Bytecode.contains(classWithFieldOfType("sibarum/atchung/elektroq/ElektroBridge"),
                        "sibarum/atchung/elektroq".getBytes(StandardCharsets.UTF_8)),
                "so must a reference to the bridge");

        assertFalse(Bytecode.contains(classWithFieldOfType("sibarum/atchung/Topic"),
                        "sibarum/elektro".getBytes(StandardCharsets.UTF_8)),
                "but plain atchung-core traffic is exactly what the GUI is supposed to use");
    }

    /** Bytecode for {@code class Probe { <type> field; }} — enough to put {@code type} in the constant pool. */
    private static byte[] classWithFieldOfType(String internalTypeName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "dev/vexelray/gui/widget/Probe", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, "field", "L" + internalTypeName + ";", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
