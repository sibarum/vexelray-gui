package dev.vexelray.gui.core.spi;

import dev.vexelray.gui.core.Drawn;
import dev.vexelray.gui.core.Frame;

/**
 * A part's asked half, as the host runs it: the frame contract's declared draw (docs/plans/frame-contract.md A, B).
 *
 * <p>Registered on a tree by the host or a widget library ({@link Seams#ask}), never by an application, as every seam
 * is: vexelray-framework's generated wiring registers a {@code @View}'s {@code @Draw} function here, closed over the
 * view's state, after the processor has checked it; and a widget whose picture moves (vexelray-codec's {@code ImageView}
 * and {@code VideoView}, playing a feed) registers its own while it moves, and lets it go when it stops. A widget's is
 * library code, held by the library's tests to the same rules, as its input handlers are. How a widget writes and tests
 * one: {@code docs/reference/seams.md}, <i>A widget's asked half</i>. It runs on the GUI thread on every frame the tree runs, in the motion stage, after layout and the told
 * halves, and before publish, so hit-testing reads what it set.
 *
 * <p>It chooses and sets numbers; it builds nothing, sends nothing and waits on nothing. What it would report, its told
 * half derives from a message instead.
 */
@FunctionalInterface
public interface Asked {

    /**
     * Set what this part's nodes show for {@code frame}'s present time through {@code out}, and answer when it next
     * needs asking: {@link Frame#NEVER} at rest, a time at or before {@link Frame#presentNanos} for the next frame.
     */
    long draw(Frame frame, Drawn out);
}
