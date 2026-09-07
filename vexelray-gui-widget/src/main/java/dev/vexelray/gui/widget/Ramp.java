package dev.vexelray.gui.widget;

import java.util.function.DoubleConsumer;

/**
 * Where a widget's motion gets its time: drive {@code progress} from 0 to 1, then call {@code done}.
 *
 * <p>Sole purpose is to keep this module clock-free. A widget that animates has to be told <em>when</em>, and the
 * clock enters at the application edge — {@code vexelray-gui-krono} is downstream of here, so naming it would
 * invert the layering. Both types crossing this seam are JDK types, so {@code KronoGui.ramp} satisfies it without
 * either module knowing the other exists:
 *
 * <pre>{@code
 * tabs.transition(Tabs.slide((p, done) -> krono.ramp(ms(200), Ease.LINEAR, p, done)));
 * }</pre>
 *
 * <p>A widget with no ramp installed does whatever it did before there was motion — which is what makes every
 * animation here opt-in, and makes the reduced-motion path simply not calling it.
 *
 * <h2>The name is taken twice over, so import this one carefully</h2>
 *
 * A <em>colour</em> ramp is also a ramp, and that meaning is the commoner one in a drawing framework — it is
 * even the one {@link ColorPicker} uses, in this very package, for the strips it paints. So an application that
 * has its own {@code Ramp} — a colour map, typically — must not import this one: a single-type import
 * <b>shadows a type of the same name from the importing file's own package</b>, silently and with no complaint
 * at the import itself, and every {@code Ramp} in that file then means this interface instead. Write this one
 * out in full at the use site, which for an application installing motion is usually a single field:
 *
 * <pre>{@code
 * private final dev.vexelray.gui.widget.Ramp knob;   // this file's own Ramp is the colour map
 * }</pre>
 *
 * <p>Not hypothetical: it cost {@code calculator-vexel-demo} a working colour picker, whose {@code Ramp} is an
 * enum of four colour maps.
 */
@FunctionalInterface
public interface Ramp {

    /**
     * Drive {@code progress} over [0,1] and call {@code done} once at the end.
     *
     * <p>Deliver both endpoints exactly, and deliver the last one on a frame that is actually presented. A ramp
     * sampled only at frame boundaries is already a frame into its duration by the first of them, so a consumer
     * that never sees 0 begins by jumping to wherever that first sample landed. And a consumer that tears down on
     * arrival — hides a node, drops an overlay — runs in the same batch as the final sample unless something
     * separates them, so the end value gets written and overwritten before anything reaches the screen, and the
     * last thing seen is the second-to-last sample.
     */
    void run(DoubleConsumer progress, Runnable done);
}
