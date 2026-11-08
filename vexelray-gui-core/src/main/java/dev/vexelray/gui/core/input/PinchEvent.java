package dev.vexelray.gui.core.input;

/**
 * The touchpad's fingers spread or pinched over a node that asked for it ({@code NodeEvent.PINCH}): a
 * {@link Phase#START}, a {@link Phase#MOVE} for each change, and one {@link Phase#END}. {@code scale} is the change in
 * the fingers' spread since the previous event, as a factor, so the moves multiply into a zoom: above one spreading
 * apart, below one pinching together, exactly one on a start and an end.
 *
 * <p>Only the spread. The fingers' twist and drift are on the tree's gesture topic ({@code Gui.gestures()}) for a view
 * that wants them; a view that zooms about the pointer has the pointer already, since the OS holds it still while two
 * fingers are down.
 */
public record PinchEvent(Phase phase, float scale) {

    public enum Phase { START, MOVE, END }
}
