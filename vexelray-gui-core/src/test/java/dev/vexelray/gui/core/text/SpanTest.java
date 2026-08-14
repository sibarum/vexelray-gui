package dev.vexelray.gui.core.text;

import dev.vexelray.canvas.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Auto-diff remap of a span through an edit (§4.4). */
class SpanTest {

    private static final Color C = Color.rgb(0xff0000);

    @Test
    void insertionBeforeSpanShiftsIt() {
        Span sp = Span.foreground(5, 8, C);
        Span r = sp.remap(TextEdit.insert(2, "abc")); // +3 before the span
        assertEquals(8, r.start());
        assertEquals(11, r.end());
    }

    @Test
    void insertionInsideSpanGrowsIt() {
        Span sp = Span.background(5, 8, C);
        Span r = sp.remap(TextEdit.insert(6, "XY")); // +2 inside
        assertEquals(5, r.start());
        assertEquals(10, r.end());
    }

    @Test
    void insertionAfterSpanLeavesItAlone() {
        Span sp = Span.underline(5, 8);
        Span r = sp.remap(TextEdit.insert(20, "z"));
        assertEquals(5, r.start());
        assertEquals(8, r.end());
    }

    @Test
    void deletingTheWholeSpanCollapsesToNull() {
        Span sp = Span.foreground(5, 8, C);
        assertNull(sp.remap(TextEdit.delete(4, "xxxxx")), "a span fully inside the deletion drops out");
    }

    @Test
    void deletionInsideShrinksSpan() {
        Span sp = Span.foreground(5, 10, C);
        Span r = sp.remap(TextEdit.delete(6, "ab")); // remove 2 inside
        assertEquals(5, r.start());
        assertEquals(8, r.end());
    }
}
