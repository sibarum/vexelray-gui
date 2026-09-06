package dev.vexelray.gui.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic of naming part of a texture — a value with no GPU behind it, which is the point: a sheet's
 * layout is decided where the sheet is packed, and a test can check it without a device.
 */
class ImageRegionTest {

    private static void assertRegion(float u0, float v0, float u1, float v1, ImageRegion actual) {
        assertEquals(u0, actual.u0(), 1e-6f, "u0");
        assertEquals(v0, actual.v0(), 1e-6f, "v0");
        assertEquals(u1, actual.u1(), 1e-6f, "u1");
        assertEquals(v1, actual.v1(), 1e-6f, "v1");
    }

    @Test
    void theWholeTextureIsTheUnitSquare() {
        assertRegion(0f, 0f, 1f, 1f, ImageRegion.WHOLE);
        assertTrue(ImageRegion.WHOLE.whole());
        assertFalse(ImageRegion.cell(0, 2, 1).whole(), "half a texture is not the whole of it");
    }

    @Test
    void cellsAreCountedRowMajorFromTheTopLeft() {
        // A 2x2 sheet: 0 1
        //              2 3
        assertAllCells();
    }

    private void assertAllCells() {
        assertRegion(0f, 0f, 0.5f, 0.5f, ImageRegion.cell(0, 2, 2));
        assertRegion(0.5f, 0f, 1f, 0.5f, ImageRegion.cell(1, 2, 2));
        assertRegion(0f, 0.5f, 0.5f, 1f, ImageRegion.cell(2, 2, 2));
        assertRegion(0.5f, 0.5f, 1f, 1f, ImageRegion.cell(3, 2, 2));
    }

    @Test
    void aStripOfFramesDividesOnlyTheOneAxis() {
        assertRegion(0.25f, 0f, 0.5f, 1f, ImageRegion.cell(1, 4, 1));
        assertRegion(0f, 0.75f, 1f, 1f, ImageRegion.cell(3, 1, 4));
    }

    @Test
    void aOneCellSheetIsTheWholeTexture() {
        assertTrue(ImageRegion.cell(0, 1, 1).whole(),
                "the degenerate sheet must not become a special case anywhere downstream");
    }

    @Test
    void aCellOutsideTheSheetIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ImageRegion.cell(4, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> ImageRegion.cell(-1, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> ImageRegion.cell(0, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> ImageRegion.cell(0, 2, 0));
    }

    @Test
    void pixelRectanglesNormaliseAgainstTheTextureTheyCameFrom() {
        // The vexelray-icons sheet: 640x256, ten 128px cells, the mark in the middle 96.
        assertRegion(0.2f, 0f, 0.4f, 0.5f, ImageRegion.pixels(128, 0, 128, 128, 640, 256));
        assertRegion(0f, 0f, 1f, 1f, ImageRegion.pixels(0, 0, 640, 256, 640, 256));
    }

    @Test
    void theSameRegionsSurviveTheSheetBeingRebakedAtTwiceTheSize() {
        // Normalised rather than pixels, so a 2x sheet is the same regions over larger texels — the reason the
        // type does not store pixel rects.
        assertEquals(ImageRegion.pixels(128, 0, 128, 128, 640, 256),
                ImageRegion.pixels(256, 0, 256, 256, 1280, 512));
    }

    @Test
    void aTextureWithNoSizeCannotBeDivided() {
        assertThrows(IllegalArgumentException.class, () -> ImageRegion.pixels(0, 0, 1, 1, 0, 16));
        assertThrows(IllegalArgumentException.class, () -> ImageRegion.pixels(0, 0, 1, 1, 16, 0));
    }
}
