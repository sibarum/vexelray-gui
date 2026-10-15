package dev.vexelray.gui.krono;

import dev.vexelray.canvas.Color;
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

    @Test
    @DisplayName("a colour change starts where it was and arrives exactly")
    void aFadeRunsFromTheOldColourToTheNew() {
        try (KronoGui krono = headless()) {
            Fades fades = Fades.on(krono, OVER, Ease.LINEAR);
            fades.repainted(7L, Channel.BACKGROUND, GREY, LIGHT);

            assertEquals(GREY, fades.drawn(7L, Channel.BACKGROUND, LIGHT), "nothing has elapsed: the old colour");
            assertTrue(fades.moving());

            krono.tick(ms(100));
            Color half = fades.drawn(7L, Channel.BACKGROUND, LIGHT);
            assertTrue(half.r() > GREY.r() && half.r() < LIGHT.r(), "half way, between the two: " + half);

            krono.tick(ms(200));
            assertEquals(LIGHT, fades.drawn(7L, Channel.BACKGROUND, LIGHT), "at the end, the colour it was given");
            krono.tick(ms(216));   // the ramp lets go a frame after its last value is on screen
            assertFalse(fades.moving(), "and then it stops asking for frames");
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
            krono.tick(ms(100));
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
            krono.tick(ms(100));
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
            assertFalse(fades.moving());
        }
    }
}
