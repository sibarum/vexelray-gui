package dev.vexelray.gui.nfd;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What the typed API hands the FFM layer, checked without a dialog to open.
 *
 * <p>Only the pure half is reachable here — the formatting of a filter into NFDe's {name, spec} pair. Opening a
 * dialog needs the native library, a window and a person, so it is not a thing a suite can assert; the value of
 * testing this half is that the spec is a string format with rules, and a string format with rules is exactly
 * what drifts silently.
 */
class FileDialogTest {

    @Test
    void aFilterBecomesANameAndACommaSeparatedSpec() {
        String[][] out = FileDialog.toNfdFilters(List.of(
                FileDialog.Filter.of("Images", "png", "jpg", "webp"),
                FileDialog.Filter.of("Vector", "svg")));

        assertEquals(2, out.length);
        assertArrayEquals(new String[]{"Images", "png,jpg,webp"}, out[0]);
        assertArrayEquals(new String[]{"Vector", "svg"}, out[1]);
    }

    /** NFDe wants extensions without dots, and a caller who writes one is being helpful rather than wrong. */
    @Test
    void aLeadingDotIsTakenOff() {
        String[][] out = FileDialog.toNfdFilters(List.of(FileDialog.Filter.of("Images", ".png", "jpg", ".webp")));
        assertArrayEquals(new String[]{"Images", "png,jpg,webp"}, out[0]);
    }

    /**
     * Null rather than an empty array, because they mean different things at the boundary: the dialog reads a
     * count beside the pointer, and a zero count with a non-null list is a shape NFDe is not promised.
     */
    @Test
    void noFiltersIsNoFilterList() {
        assertNull(FileDialog.toNfdFilters(null));
        assertNull(FileDialog.toNfdFilters(List.of()));
        assertNull(FileDialog.toNfdFilters(new ArrayList<>()));
    }

    /** A filter row with no extension would format to an empty spec, which NFDe shows as a dead dropdown entry. */
    @Test
    void aFilterMustOfferSomething() {
        assertThrows(IllegalArgumentException.class, () -> FileDialog.Filter.of("Images"));
        assertThrows(IllegalArgumentException.class, () -> new FileDialog.Filter("Images", List.of()));
        assertThrows(NullPointerException.class, () -> new FileDialog.Filter(null, List.of("png")));
        assertThrows(NullPointerException.class, () -> new FileDialog.Filter("Images", null));
    }
}
