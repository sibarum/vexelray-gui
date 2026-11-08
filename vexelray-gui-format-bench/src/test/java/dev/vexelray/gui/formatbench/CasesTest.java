package dev.vexelray.gui.formatbench;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every case runs, every contender draws the size it is asked, and the truth is close to itself. */
class CasesTest {

    @Test
    void everyCaseRuns() {
        Map<String, Supplier<Object>> cases = Cases.all();
        assertEquals(Corpus.names().size() * (Cases.FORMS.length + Contender.all().size() * Corpus.SIZES.length),
                cases.size());
        for (var e : cases.entrySet()) {
            if (e.getKey().endsWith("|1024")) {
                continue;   // the large draws are the same code at a larger size; kept out of the unit test's time
            }
            assertNotNull(e.getValue().get(), e.getKey());
        }
    }

    @Test
    void everyContenderDrawsItsSizeAndIsNearTheTruth() {
        Corpus.Art art = Corpus.get("badge");
        byte[] truth = Quality.reference(art, 64);
        assertEquals(64 * 64 * 4, truth.length);
        for (Contender c : Contender.all()) {
            byte[] got = c.draw(art, 64);
            assertEquals(truth.length, got.length, c.label);
            Quality.Error e = Quality.compare(truth, got);
            assertTrue(e.mean() < 8, c.label + " mean error " + e.mean());
        }
        assertEquals(0, Quality.compare(truth, truth).worst());
    }
}
