package dev.vexelray.gui.inspector.link;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.util.Map;

/**
 * The dials, every {@code snapEveryMs}: the frame loop's always-on counters ({@code LoopCounters}) and the ring's
 * own. Totals since the application started, never reset, so the Inspector takes differences. Domains whose hook has
 * not landed (mailboxes, kronometer, heap) are added as fields at the end when they do, and are absent until then,
 * never zero.
 *
 * @param frame          the frame in progress; -1 when the host handed no loop counters, and every loop field is
 *                       then -1 too
 * @param parks          parks by what bounded them ({@code forever}, {@code deadline}, {@code floor},
 *                       {@code ceiling}, {@code zero})
 * @param focus          what the loop takes for focus: {@code on}, {@code off}, with {@code (simulated)} while
 *                       overridden
 * @param ringHeld       rows the recorder holds now
 * @param ringLost       rows lost since the recorder started
 */
@IntrabufMessage(name = "inspector.Snap", id = 902, compat = Compat.BACKWARD)
public record Snap(long frame, long drawn, long presented, long wakes, long wakesCoalesced,
                   @Field(nonNull = true) String focus, @Field(nonNull = true) Map<String, Long> parks,
                   long ringHeld, long ringLost) {
}
