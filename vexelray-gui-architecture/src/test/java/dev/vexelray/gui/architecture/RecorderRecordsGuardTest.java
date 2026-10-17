package dev.vexelray.gui.architecture;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The flight recorder's answers are plain records (docs/plans/flight-recorder.md §5, records first): the text reply
 * is one rendering of them, and another transport is meant to carry the same records as they are. That holds only
 * while the recorder module names no GUI type, no bus type and no transport or serialization library — so a record
 * can be annotated as a wire message later without dragging the GUI along. This guard is what keeps it so.
 */
class RecorderRecordsGuardTest {

    /** Internal-form package prefixes the recorder module must never reference. */
    private static final List<String> FORBIDDEN = List.of(
            "dev/vexelray/gui/core",      // the GUI: rows are probe rows, not nodes or windows
            "sibarum/atchung/",           // the bus (atchung-core); atchung-probe is sibarum/probe and is allowed
            "sibarum/tactroller",         // input types
            "sibarum/elektro",            // a transport
            "sibarum/intrabuf");          // a serialization library

    @Test
    void theRecorderNamesNoGuiBusOrTransportType() {
        Path classes = Bytecode.classesOf(Bytecode.GUI_RECORDER);
        List<String> violations = new ArrayList<>();
        for (Path classFile : Bytecode.classFiles(classes)) {
            byte[] bytes = Bytecode.read(classFile);
            for (String forbidden : FORBIDDEN) {
                if (Bytecode.contains(bytes, forbidden.getBytes(StandardCharsets.UTF_8))) {
                    violations.add(classes.relativize(classFile) + " references " + forbidden);
                }
            }
        }
        assertEquals(List.of(), violations,
                "vexelray-gui-recorder answers in plain records that any transport can carry; keep GUI, bus and "
                        + "wire types at the edge that uses it (vexelray-gui-automation), not in the records.");
    }
}
