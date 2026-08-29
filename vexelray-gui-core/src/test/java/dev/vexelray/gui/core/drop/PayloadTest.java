package dev.vexelray.gui.core.drop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadTest {

    private static final PayloadType<String> TEXT = PayloadType.of("text");
    private static final PayloadType<List<String>> PATHS = PayloadType.of("paths");
    private static final PayloadType<Integer> COUNT = PayloadType.of("count");

    @Test
    @DisplayName("a payload yields the form it was given, typed")
    void aFormComesBackAsItself() {
        Payload dragged = Payload.of(TEXT, "hello");

        assertEquals(Optional.of("hello"), dragged.as(TEXT));
        assertTrue(dragged.offers(TEXT));
    }

    @Test
    @DisplayName("a form nobody offered is absent rather than null")
    void anUnofferedFormIsEmpty() {
        Payload dragged = Payload.of(TEXT, "hello");

        assertEquals(Optional.empty(), dragged.as(PATHS));
        assertFalse(dragged.offers(PATHS));
    }

    @Test
    @DisplayName("one drag can be several things, and the target picks")
    void severalFormsCoexist() {
        Payload dragged = Payload.of(TEXT, "x.txt").and(PATHS, List.of("/tmp/x.txt"));

        assertEquals(Optional.of("x.txt"), dragged.as(TEXT));
        assertEquals(1, dragged.as(PATHS).orElseThrow().size());
        assertEquals(2, dragged.forms().size());
    }

    /** Two types with the same name are still two types — matching is identity, so a name cannot collide. */
    @Test
    @DisplayName("types match by identity, not by name")
    void sameNameIsNotSameType() {
        PayloadType<String> mine = PayloadType.of("text");
        Payload dragged = Payload.of(TEXT, "hello");

        assertFalse(dragged.offers(mine));
        assertEquals(Optional.empty(), dragged.as(mine));
    }

    @Test
    @DisplayName("a lazy form is computed at most once, however many targets ask")
    void lazyFormsAreMemoised() {
        AtomicInteger computed = new AtomicInteger();
        Payload dragged = Payload.lazily(COUNT, computed::incrementAndGet);

        assertEquals(Optional.of(1), dragged.as(COUNT));
        assertEquals(Optional.of(1), dragged.as(COUNT));
        assertEquals(1, computed.get(), "sixty frames of drag must not be sixty computations");
    }

    @Test
    @DisplayName("a lazy form nobody asks for is never computed")
    void lazyFormsAreNotComputedEagerly() {
        AtomicInteger computed = new AtomicInteger();
        Payload dragged = Payload.of(TEXT, "cheap").andLazily(COUNT, computed::incrementAndGet);

        dragged.as(TEXT);

        assertEquals(0, computed.get(), "the expensive form is usually the one nothing wanted");
    }

    @Test
    @DisplayName("a source that cannot produce what it promised has not promised it")
    void aNullFormIsAbsentAndNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        Payload dragged = Payload.lazily(TEXT, () -> {
            attempts.incrementAndGet();
            return null;
        });

        assertEquals(Optional.empty(), dragged.as(TEXT));
        assertEquals(Optional.empty(), dragged.as(TEXT));
        assertEquals(1, attempts.get(), "a failed form must not be retried on every frame");
    }

    @Test
    @DisplayName("adding a form leaves the original alone")
    void payloadsAreImmutable() {
        Payload first = Payload.of(TEXT, "a");
        Payload second = first.and(PATHS, List.of("/tmp"));

        assertFalse(first.offers(PATHS), "a payload in flight must not grow under the targets reading it");
        assertTrue(second.offers(PATHS));
        assertTrue(second.offers(TEXT));
    }

    @Test
    @DisplayName("re-offering a type replaces it")
    void reofferingReplaces() {
        Payload dragged = Payload.of(TEXT, "a").and(TEXT, "b");

        assertEquals(Optional.of("b"), dragged.as(TEXT));
        assertEquals(1, dragged.forms().size());
    }

    @Test
    @DisplayName("an empty payload is legal and simply accepted by nothing")
    void anEmptyPayloadIsLegal() {
        assertEquals(Optional.empty(), Payload.empty().as(TEXT));
        assertTrue(Payload.empty().forms().isEmpty());
    }
}
