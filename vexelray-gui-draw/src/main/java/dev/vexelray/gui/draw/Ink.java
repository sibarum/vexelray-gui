package dev.vexelray.gui.draw;

import dev.vexelray.canvas.Color;

/**
 * What a mark is painted in: a colour, or a <b>name</b> for one that the look a picture is drawn in decides.
 *
 * <p>A picture is authored once and drawn every frame, and the person can change the look between frames. A mark that
 * holds a {@link Color} is drawn in that colour in every look, which is right for a colour that means itself (a plot's
 * series, a swatch) and wrong for one that means "the text colour" or "the accent": that one has to be a name, decided
 * when it is drawn. {@code vexelray-gui-core}'s {@code Paint}, and so every {@code Role}, is an ink, and the screen draws
 * it in the theme the tree is in, fading with everything else when the theme is swapped.
 *
 * <p>This module sits under the one that knows what a theme is, so an ink here is only what every consumer needs: the
 * colour, when it does not depend on the look ({@link #fixed}). Deciding the rest is the sink's
 * ({@link Picture.Sink#color}).
 */
public interface Ink {

    /** This ink's colour when it is the same in every look; null for one the look decides. */
    default Color fixed() {
        return null;
    }

    /** {@code color} in every look. Null stays null: no ink is not an ink, and a mark with none is not drawn. */
    static Ink of(Color color) {
        return color == null ? null : new Fixed(color);
    }

    /** {@code ink}'s colour where it is the same in every look, else null; null for no ink. */
    static Color fixedOf(Ink ink) {
        return ink == null ? null : ink.fixed();
    }

    /** A colour the look does not decide. */
    record Fixed(Color color) implements Ink {
        @Override
        public Color fixed() {
            return color;
        }
    }

    /**
     * The colour a sink draws an ink in: the look's answer for a name. {@link #FIXED} answers only for a colour, and
     * refuses a name, naming what to pass instead.
     */
    @FunctionalInterface
    interface Resolver {

        /** {@code ink}'s colour where this draws; null for no ink. */
        Color color(Ink ink);

        /** Colours only: a named ink has no look to be decided in here, which is said rather than drawn wrong. */
        Resolver FIXED = ink -> {
            if (ink == null) {
                return null;
            }
            Color c = ink.fixed();
            if (c == null) {
                throw new IllegalStateException("a mark named its colour (" + ink + "), and this sink was given no look "
                        + "to decide it in: pass a Resolver, such as vexelray-gui-core's Paint.resolver(theme), when the picture is "
                        + "drawn or exported");
            }
            return c;
        };
    }
}
