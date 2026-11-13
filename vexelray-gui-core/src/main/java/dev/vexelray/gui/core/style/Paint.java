package dev.vexelray.gui.core.style;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.input.InteractionState;

/**
 * A colour as a node holds it: not the colour, but how to get it from whichever {@link Theme} the tree is drawn in.
 * The renderer asks every frame, so a theme swapped after the tree is built repaints it whole, with no prop written.
 *
 * <p>The same move {@code Length} makes against zoom. A node that holds {@code 1.5em} rather than {@code 24px} keeps
 * its proportions when the zoom changes; a node that holds {@link Role#ACTION} rather than the blue it resolved to
 * keeps its meaning when the look changes. A {@link Role} is a paint already — the common case is
 * {@code background(Role.ACTION)} — and a role under the pointer is {@code Role.ACTION.at(state)}, so a state handler
 * still decides <em>which</em> state and the theme still decides what that state looks like, only later.
 *
 * <p>A literal colour is the constant paint ({@link #of(Color)}): the same in every theme, which is right for a
 * swatch, an image's tint or anything the user chose, and wrong for anything the look decides.
 *
 * <p>Equality matters: the reconciler skips a write that changes nothing, and a state handler rewrites the same paint
 * whenever it fires. Every paint here is a record or a constant, so equal paints are equal. A paint made by a lambda
 * at every write is not, and costs a frame per write; hold it in a field.
 *
 * <p><b>A paint is an {@link Ink}</b>, so everything that takes a colour takes a paint as well: a span, a picture's
 * marks, a cue. Each is drawn in the theme the tree is in, and a theme swap fades it with the nodes around it.
 */
@FunctionalInterface
public interface Paint extends dev.vexelray.gui.draw.Ink {

    /** The colour this paint is in {@code theme}. */
    Color in(Theme theme);

    /** {@code color} in every theme. Null stays null: no paint is not a paint. */
    static Paint of(Color color) {
        return color == null ? null : new Fixed(color);
    }

    /**
     * {@code ink} as a paint: itself when it is one, its colour in every theme when it is a colour, and null for no ink
     * or for a name this module does not know.
     */
    static Paint from(dev.vexelray.gui.draw.Ink ink) {
        if (ink instanceof Paint p) {
            return p;
        }
        Color fixed = dev.vexelray.gui.draw.Ink.fixedOf(ink);
        return fixed == null ? null : new Fixed(fixed);
    }

    /**
     * Every ink decided in {@code theme}: what a picture with named colours is drawn or exported through where there is
     * no tree to draw it ({@code Svg.document(picture, w, h, font, Paint.resolver(theme))}). A name this module does not
     * know is not drawn.
     */
    static dev.vexelray.gui.draw.Ink.Resolver resolver(Theme theme) {
        Theme in = theme == null ? Theme.DARK : theme;
        return ink -> {
            Paint p = from(ink);
            return p == null ? null : p.in(in);
        };
    }

    /** {@code role} as it looks while the pointer is in {@code state}, in whichever theme is drawing. */
    static Paint of(Role role, InteractionState state) {
        return new Shaded(role, state);
    }

    /** This paint at {@code alpha} times its own opacity, in whichever theme is drawing. */
    default Paint alpha(float alpha) {
        return new Faded(this, alpha);
    }

    /**
     * {@code t} of the way from this paint to {@code toward}, mixed in Oklab, in whichever theme is drawing. From a
     * transparent paint there is no hue to mix from, so the far one fades in over the gap instead.
     */
    default Paint mix(Paint toward, float t) {
        return new Mixed(this, toward, t);
    }

    /** A colour the look does not decide. */
    record Fixed(Color color) implements Paint {
        @Override
        public Color in(Theme theme) {
            return color;
        }

        @Override
        public Color fixed() {
            return color;
        }
    }

    /** A role under the pointer: the theme's shading of it, decided at draw time. */
    record Shaded(Role role, InteractionState state) implements Paint {
        @Override
        public Color in(Theme theme) {
            return theme.color(role, state);
        }
    }

    /** Another paint, thinned. */
    record Faded(Paint paint, float alpha) implements Paint {
        @Override
        public Color in(Theme theme) {
            Color c = paint.in(theme);
            return c == null ? null : Color.withAlpha(c, c.a() * alpha);
        }
    }

    /** Two paints, part of the way from one to the other. */
    record Mixed(Paint from, Paint to, float t) implements Paint {
        @Override
        public Color in(Theme theme) {
            return blend(from.in(theme), to.in(theme), t);
        }
    }

    /**
     * {@code t} of the way from {@code a} to {@code b}, mixed in Oklab as {@link #mix} mixes: from a transparent colour
     * there is no hue to mix from, so {@code b} fades in over the gap. A null end gives the other; equal ends, or a
     * {@code t} at either end, give that end with nothing made.
     */
    static Color blend(Color a, Color b, float t) {
        if (a == null || b == null) {
            return a == null ? b : a;
        }
        if (t >= 1f) {
            return b;
        }
        if (t <= 0f || a.equals(b)) {
            return a;
        }
        if (a.a() <= 0f) {
            return Oklab.of(b).toColor(b.a() * t);
        }
        return Oklab.of(a).mix(Oklab.of(b), t).toColor(a.a() + (b.a() - a.a()) * t);
    }
}
