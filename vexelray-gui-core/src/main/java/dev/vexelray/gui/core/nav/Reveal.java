package dev.vexelray.gui.core.nav;

/**
 * How a container makes a descendant of it reachable — select the page it is on, expand the branch it is in,
 * open the drawer it is behind. Registered on the node that does the <b>concealing</b>
 * ({@code Gui.reveals(node, reveal)}) and asked by {@code Gui.navigate} on its way down to a landmark.
 *
 * <p><b>Why this is the container's declaration and not the navigator's knowledge.</b> Navigation that knew
 * about tabs, trees, drawers and wizards would be an enumeration, and an enumeration is never finished: the
 * next container that can hide something is a change to the navigator, in a file that has nothing to do with
 * it. Inverted, the fact lives where it is true. The invariant is one line — <i>every node that can conceal a
 * descendant declares how to un-conceal it</i> — and it is checkable today, which is what makes a landmark
 * behind a container written next year still reachable.
 *
 * <p><b>What is not concealment.</b> A container that merely scrolls declares nothing: {@code scrollIntoView}
 * already walks every scrolling ancestor, and navigation ends with one. Nor does a container whose hidden
 * children have no nodes at all — a tree branch never expanded has no rows to address, so there is nothing
 * there to be a landmark. Concealment here means precisely: the node exists, and this container is why it
 * cannot be seen.
 *
 * <p>Called on the GUI thread, once per navigation, outermost container first — an inner reveal that ran before
 * its outer one would be acting inside something still hidden.
 */
@FunctionalInterface
public interface Reveal {

    /**
     * Make {@code descendant} — a node somewhere under the node this is registered on — reachable, and report
     * whether that changed anything.
     *
     * <p>Returning {@code true} says the container acted and the tree is now different, so navigation waits a
     * frame for the reveal to be applied and laid out before going on. Returning {@code false} says there was
     * nothing to do (the right page was already showing), and navigation continues in the same frame. Both are
     * ordinary answers; neither is an error.
     */
    boolean reveal(long descendant);
}
