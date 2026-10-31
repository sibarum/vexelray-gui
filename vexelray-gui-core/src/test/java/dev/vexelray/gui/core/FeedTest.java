package dev.vexelray.gui.core;

import dev.vexelray.vulkan.present.MipChain;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A feed's ring as the producer and the renderer see it (docs/plans/frame-contract.md D), without a device: which slot
 * is shown, when a slot comes free again, and what a producer is told when none is.
 */
class FeedTest {

    private static byte[][] filled(Feed f, int value) {
        byte[][] chain = new byte[f.levels()][];
        for (int l = 0; l < f.levels(); l++) {
            chain[l] = new byte[f.levelBytes(l)];
            java.util.Arrays.fill(chain[l], (byte) value);
        }
        return chain;
    }

    @Test
    void aStillIsOneSlotHandedOverAndShown() {
        Feed f = Feed.of(4, 2, 1, false);
        Feed still = Feed.still(4, 2, false, filled(f, 7));
        assertEquals(1, still.slots());
        assertEquals(0, still.select());
        assertEquals(7, still.chain(0)[0][0]);
        assertEquals(-1, still.take(), "a still's one slot is shown, and so never free again");
    }

    @Test
    void aPictureCarriesItsWholeMipChain() {
        Feed f = Feed.of(8, 4, 1, true);
        assertEquals(MipChain.count(8, 4), f.levels());
        byte[][] chain = MipChain.of(new byte[8 * 4 * 4], 8, 4);
        for (int l = 0; l < f.levels(); l++) {
            assertEquals(chain[l].length, f.levelBytes(l), "level " + l);
        }
        assertTrue(f.write(chain, 0L));
    }

    @Test
    void nothingIsShownBeforeASlotIsHandedOver() {
        Feed f = Feed.of(2, 2, 3, false);
        assertEquals(-1, f.select());
        int s = f.take();
        assertEquals(-1, f.select(), "a slot being written is not shown");
        f.handOver(s, 10L);
        assertEquals(s, f.select());
    }

    @Test
    void theNewestIsShownWhenNobodyChoosesAndTheOlderComeFree() {
        Feed f = Feed.of(2, 2, 3, false);
        assertTrue(f.write(filled(f, 1), 10L));
        assertTrue(f.write(filled(f, 2), 20L));
        assertTrue(f.write(filled(f, 3), 30L));
        assertEquals(-1, f.take(), "three handed over and none shown: the producer is ahead by the whole ring");
        int shown = f.select();
        assertEquals(30L, f.stamp(shown));
        assertEquals(3, f.chain(shown)[0][0]);
        // The two older will never be shown now, so they are the producer's again.
        assertTrue(f.take() >= 0);
        assertTrue(f.take() >= 0);
        assertEquals(-1, f.take(), "the shown slot stays the renderer's");
    }

    @Test
    void aChoiceByStampHoldsTheRingBackToWhatIsDue() {
        Feed f = Feed.of(2, 2, 3, false);
        f.write(filled(f, 1), 10L);
        f.write(filled(f, 2), 20L);
        f.write(filled(f, 3), 30L);
        assertEquals(-1, f.due(5L), "nothing is due before the first stamp");
        int due = f.due(24L);
        assertEquals(20L, f.stamp(due));
        assertEquals(30L, f.nextAfter(24L));
        assertTrue(f.choose(due));
        assertEquals(due, f.select());
        assertEquals(30L, f.stamp(f.newest()), "the later frame waits, handed over, until it is due");
        assertTrue(f.take() >= 0, "the earlier frame was passed, so it came free");
        assertEquals(-1, f.take());
        assertFalse(f.choose(due), "choosing what is shown changes nothing");
    }

    @Test
    void aChoiceStaysChosenUntilTheNext() {
        Feed f = Feed.of(2, 2, 3, false);
        f.write(filled(f, 1), 10L);
        f.write(filled(f, 2), 20L);
        int first = f.due(10L);
        f.choose(first);
        assertEquals(first, f.select());
        assertEquals(first, f.select(), "asked again in the frame, by a second run or device, the answer is the same");
    }

    @Test
    void aSlotWrittenAgainIsANewGeneration() {
        Feed f = Feed.of(2, 2, 2, false);
        f.write(filled(f, 1), 10L);
        int a = f.select();
        long was = f.generation(a);
        f.write(filled(f, 2), 20L);
        int b = f.select();
        f.write(filled(f, 3), 30L);
        assertEquals(a, f.select(), "two slots alternate");
        assertTrue(f.generation(a) > was, "so the renderer uploads it again");
        assertTrue(b != a);
    }

    @Test
    void aHandOverWakesWhoeverDrawsIt() {
        Feed f = Feed.of(2, 2, 2, false);
        AtomicInteger woken = new AtomicInteger();
        f.wake(woken::incrementAndGet);
        f.wake(() -> { throw new AssertionError("the first renderer's wake is kept"); });
        f.write(filled(f, 1), 0L);
        f.close();
        assertEquals(2, woken.get());
    }

    @Test
    void aClosedFeedGivesNoSlots() {
        Feed f = Feed.of(2, 2, 2, false);
        f.close();
        assertTrue(f.closed());
        assertEquals(-1, f.take());
        assertFalse(f.write(filled(f, 1), 0L));
    }

    @Test
    void aSlotIsWrittenOnlyBetweenTakingAndHandingOver() {
        Feed f = Feed.of(2, 2, 2, false);
        assertThrows(IllegalStateException.class, () -> f.level(0, 0));
        int s = f.take();
        f.level(s, 0)[0] = 9;
        f.handOver(s, 0L);
        assertThrows(IllegalStateException.class, () -> f.level(s, 0));
        assertThrows(IllegalStateException.class, () -> f.handOver(s, 1L));
        int t = f.take();
        f.drop(t);
        assertArrayEquals(new byte[] {9, 0, 0, 0}, java.util.Arrays.copyOf(f.chain(s)[0], 4));
    }

    @Test
    void aFeedHasASizeAndASlot() {
        assertThrows(IllegalArgumentException.class, () -> Feed.of(0, 2, 1, false));
        assertThrows(IllegalArgumentException.class, () -> Feed.of(2, 2, 0, false));
        Feed f = Feed.of(2, 2, 1, false);
        assertThrows(IllegalArgumentException.class, () -> f.write(new byte[2][], 0L));
    }
}
