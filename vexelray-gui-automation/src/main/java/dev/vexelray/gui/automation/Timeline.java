package dev.vexelray.gui.automation;

/**
 * What {@link Automation#settle} asks besides the frame loop: whether a clock this module cannot see is still
 * moving things.
 *
 * <p><b>Why settle needs it.</b> A frame loop knows what has been published and not yet drawn; it does not know
 * that a fade is a third of the way through. With nothing owed, settle used to answer at once — and
 * {@code click} → {@code settle} → {@code shot} photographed a panel part-way through its transition and said
 * {@code ok}, three seconds before the transition finished (docs/reference/automation-cli.md §5, V3).
 *
 * <p><b>Why a seam and not the clock.</b> This module depends on {@code gui-core} alone, so a driver can be linked
 * into anything with a {@code Gui}; naming {@code KronoGui} here would put the timing framework on that path for
 * every host, including the ones with no clock. A host with one hands it over as
 * {@code krono::quiescentAtLastTick}.
 *
 * <p><b>Called from the driver's thread</b>, which is neither the timeline nor the frame loop. So an
 * implementation must be safe from any thread, and an answer a frame old is fine — {@code KronoGui}'s
 * {@code quiescentAtLastTick} is exactly that, and says why the live question is not.
 */
@FunctionalInterface
public interface Timeline {

    /** No clock: settle is about the frame loop alone, which is what it was before this existed. */
    Timeline NONE = () -> true;

    /** Whether nothing is scheduled and nothing is animating. */
    boolean quiet();
}
