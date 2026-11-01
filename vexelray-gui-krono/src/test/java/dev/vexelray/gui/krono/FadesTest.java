package dev.vexelray.gui.krono;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Frame;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.style.PaintMotion.Channel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Kron;
import sibarum.kronometer.anim.Ease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.kronometer.Dur.ms;

/**
 * Colour fades, driven headlessly: {@link Fades#repainted} is told what the reconciler would have told it, and
 * {@link Fades#drawn} is read back as the renderer would read it. Which writes reach {@code repainted} at all is
 * {@code gui-core}'s, and covered there.
 */
class FadesTest {

    private static final Dur OVER = ms(200);
    private static final Color GREY = Color.rgb(0.2f, 0.2f, 0.2f);
    private static final Color LIGHT = Color.rgb(0.8f, 0.8f, 0.8f);

    private KronoGui headless() {
        return KronoGui.attach(new Gui(Atchung.create()), Kron.driven());
    }

    /** Step the clock to {@code at} and draw a frame for it, as the tree would: the fades are asked there. */
    private static void tick(KronoGui krono, Fades fades, Dur at) {
        krono.tick(at);
        fades.draw(new Frame(krono.nanos(), krono.nanos(), false));
    }

    /** Whether the fades, asked for the present moment, still want the next frame. */
    private static boolean moving(KronoGui krono, Fades fades) {
        return fades.draw(new Frame(krono.nanos(), krono.nanos(), false)) != Frame.NEVER;
    }

    @Test
    @DisplayName("a colour change starts where it was and arrives exactly")
    void aFadeRunsFromTheOldColourToTheNew() {
        try (KronoGui krono = headless()) {
            Fades fades = Fades.on(krono, OVER, Ease.LINEAR);
            fades.repainted(7L, Channel.BACKGROUND, GREY, LIGHT);

            assertEquals(GREY, fades.drawn(7L, Channel.BACKGROUND, LIGHT), "nothing has elapsed: the old colour");
            assertTrue(moving(krono, fades));

            tick(krono, fades, ms(100));
            Color half = fades.drawn(7L, Channel.BACKGROUND, LIGHT);
            assertTrue(half.r() > GREY.r() && half.r() < LIGHT.r(), "half way, between the two: " + half);

            tick(krono, fades, ms(200));
            assertEquals(LIGHT, fades.drawn(7L, Channel.BACKGROUND, LIGHT), "at the end, the colour it was given");
            tick(krono, fades, ms(216));   // past the end, it has let go
            assertFalse(moving(krono, fades), "and then it stops asking for frames");
        }
    }

    @Test
    @DisplayName("an opacity, told as white at that alpha, fades its alpha linearly and stays white")
    void anOpacityFadesAsAnAlpha() {
        try (KronoGui krono = headless()) {
            Fades fades = Fades.on(krono, OVER, Ease.LINEAR);
            Color half = new Color(1f, 1f, 1f, 0.5f);
            Color none = new Color(1f, 1f, 1f, 0f);
            fades.repainted(7L, Channel.OPACITY, half, none);

            tick(krono, fades, ms(100));
            Color at = fades.drawn(7L, Channel.OPACITY, none);
            assertEquals(0.25f, at.a(), 1e-3f, "half way, half the alpha");
            assertEquals(1f, at.r(), 1e-3f, "and still white");
            tick(krono, fades, ms(200));
            assertEquals(0f, fades.drawn(7L, Channel.OPACITY, none).a(), "at the end, the opacity it was given");
        }
    }

    @Test
    @DisplayName("another node, and another channel of the same node, are untouched")
    void aFadeIsItsOwnNodesAndChannels() {
        try (KronoGui krono = headless()) {
            Fades fades = Fades.on(krono, OVER, Ease.LINEAR);
            fades.repainted(7L, Channel.BACKGROUND, GREY, LIGHT);

            assertEquals(LIGHT, fades.drawn(8L, Channel.BACKGROUND, LIGHT));
            assertEquals(LIGHT, fades.drawn(7L, Channel.TEXT, LIGHT));
        }
    }

    @Test
    @DisplayName("interrupted, it turns round from the colour it is drawn in")
    void anInterruptedFadeStartsFromWhereItIs() {
        try (KronoGui krono = headless()) {
            Fades fades = Fades.on(krono, OVER, Ease.LINEAR);
            fades.repainted(7L, Channel.BACKGROUND, GREY, LIGHT);
            tick(krono, fades, ms(100));
            Color midway = fades.drawn(7L, Channel.BACKGROUND, LIGHT);

            // The pointer left before the hover finished: back to grey, from where it got to, not from light.
            fades.repainted(7L, Channel.BACKGROUND, LIGHT, GREY);
            Color turned = fades.drawn(7L, Channel.BACKGROUND, GREY);
            assertEquals(midway.r(), turned.r(), 1e-5f, "no jump at the turn");
            assertEquals(midway.g(), turned.g(), 1e-5f);
            assertEquals(midway.b(), turned.b(), 1e-5f);
        }
    }

    @Test
    @DisplayName("a fade on its way to another colour is not drawn over the one the node now has")
    void aStaleFadeIsIgnored() {
        try (KronoGui krono = headless()) {
            Fades fades = Fades.on(krono, OVER, Ease.LINEAR);
            fades.repainted(7L, Channel.BACKGROUND, GREY, LIGHT);
            Color other = Color.rgb(1f, 0f, 0f);
            assertEquals(other, fades.drawn(7L, Channel.BACKGROUND, other));
        }
    }

    @Test
    @DisplayName("from transparent, the colour comes up rather than passing through black")
    void transparentHasNoHue() {
        try (KronoGui krono = headless()) {
            Fades fades = Fades.on(krono, OVER, Ease.LINEAR);
            fades.repainted(7L, Channel.BACKGROUND, Color.TRANSPARENT, LIGHT);
            tick(krono, fades, ms(100));
            Color half = fades.drawn(7L, Channel.BACKGROUND, LIGHT);
            assertEquals(LIGHT.r(), half.r(), 1e-3f, "the hue is the destination's all the way");
            assertEquals(0.5f, half.a(), 0.02f, "and only the alpha is on its way");
        }
    }

    @Test
    @DisplayName("a zero duration is reduced motion: nothing fades")
    void zeroDurationSnaps() {
        try (KronoGui krono = headless()) {
            Fades fades = Fades.on(krono, Dur.ZERO, Ease.LINEAR);
            fades.repainted(7L, Channel.BACKGROUND, GREY, LIGHT);
            assertEquals(LIGHT, fades.drawn(7L, Channel.BACKGROUND, LIGHT));
            assertFalse(moving(krono, fades));
        }
    }

    @Test
    @DisplayName("a colour written on every frame by someone else's ramp is followed, not faded behind")
    void aDrivenColourIsDrawnAtOnce() {
        try (KronoGui krono = headless()) {
            Fades fades = Fades.on(krono, OVER, Ease.LINEAR);
            Color[] steps = {GREY, Color.rgb(0.4f, 0.4f, 0.4f), Color.rgb(0.6f, 0.6f, 0.6f), LIGHT};
            for (int i = 1; i < steps.length; i++) {
                fades.repainted(7L, Channel.BACKGROUND, steps[i - 1], steps[i]);   // the app's ramp, frame by frame
            }
            assertEquals(LIGHT, fades.drawn(7L, Channel.BACKGROUND, LIGHT),
                    "the third write running is drawn where the ramp put it");
            assertFalse(moving(krono, fades));
        }
    }
}
