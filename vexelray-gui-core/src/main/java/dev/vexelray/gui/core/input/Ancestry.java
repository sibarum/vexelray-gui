package dev.vexelray.gui.core.input;

import dev.vexelray.gui.core.model.RetainedNode;

import java.util.Arrays;

/**
 * The ids on a node's ancestor-or-self chain, gathered in one walk and then asked by membership in constant time —
 * so "is this node the hit or one of its containers?" costs one walk per refresh rather than one per question.
 *
 * <p>Reused across walks without allocating once it has grown to the tree's depth, and it remembers the chain it
 * last held, so {@link #walk} can say whether anything changed. GUI-thread only.
 */
final class Ancestry {

    private long[] ids = new long[16];
    private int size;
    // Open addressing over the ids, sized to a power of two at least twice the chain; `used` marks the occupied
    // slots, since every long, -1 and 0 included, is a possible node id.
    private long[] slots = new long[32];
    private boolean[] used = new boolean[32];

    /**
     * Hold {@code hit}'s ancestor-or-self chain ({@code null} holds the empty chain), and say whether it differs
     * from the chain held before, compared id by id from the hit upward.
     */
    boolean walk(RetainedNode hit) {
        boolean changed = false;
        int n = 0;
        for (RetainedNode node = hit; node != null; node = node.parent) {
            if (n == ids.length) {
                ids = Arrays.copyOf(ids, n * 2);
            }
            if (n >= size || ids[n] != node.id) {
                changed = true;
                ids[n] = node.id;
            }
            n++;
        }
        if (n != size) {
            changed = true;
        }
        size = n;
        if (changed) {
            index();
        }
        return changed;
    }

    /** Whether {@code id} is on the chain last walked. */
    boolean contains(long id) {
        if (size == 0) {
            return false;
        }
        int mask = slots.length - 1;
        for (int i = mix(id) & mask; used[i]; i = (i + 1) & mask) {
            if (slots[i] == id) {
                return true;
            }
        }
        return false;
    }

    private void index() {
        int capacity = slots.length;
        while (capacity < size * 2) {
            capacity *= 2;
        }
        if (capacity != slots.length) {
            slots = new long[capacity];
            used = new boolean[capacity];
        } else {
            Arrays.fill(used, false);
        }
        int mask = capacity - 1;
        for (int k = 0; k < size; k++) {
            long id = ids[k];
            int i = mix(id) & mask;
            while (used[i] && slots[i] != id) {
                i = (i + 1) & mask;
            }
            used[i] = true;
            slots[i] = id;
        }
    }

    private static int mix(long id) {
        long h = id * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }
}
