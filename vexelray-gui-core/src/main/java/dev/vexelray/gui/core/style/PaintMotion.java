package dev.vexelray.gui.core.style;

import dev.vexelray.canvas.Color;

/**
 * What colour a node is drawn in, as distinct from the colour it was last given.
 *
 * <p>A widget repaints by writing colours: a button's hover is {@code background(theme.color(fill, HOVER))}, and the
 * write is the whole truth about the state it is in. What a write cannot state is the journey, so without this a
 * hover is a jump. {@link dev.vexelray.gui.core.layout.LayoutMotion} is the same idea for position, and this is the
 * same shape for paint: the framework says what changed, and asks what to draw.
 *
 * <h2>Why here and not in every widget</h2>
 *
 * <p>Every widget that reacts to the pointer already rewrites its colours from the theme, so the one place that sees
 * every one of those writes, from every widget there is and every one an application writes, is where they land.
 * A fade added there covers the whole set with no widget learning a clock, which is the rule widgets live by: they
 * describe a state, and something else times the way between states.
 *
 * <h2>Being told what changed</h2>
 *
 * <p>{@link #repainted} is called when a node's background, border colour or text colour is changed from one colour
 * to another. Nothing is reported for a colour's first appearance (there was no colour to come from), nor for a
 * colour cleared to none (a part that is not drawn has nothing to fade in). A widget whose part comes and goes uses a
 * transparent colour, as {@code Role.NONE} is, and that fades like any other.
 *
 * <p>{@link #repainted} is called on the GUI thread inside the frame, before the frame is drawn, so a fade started
 * there is in effect for the first frame that would have shown the new colour: a node never shows its destination
 * early, not even for one frame. {@link #drawn} is called by the renderer, which may be on another thread; an
 * implementation publishes what it reads there safely.
 */
public interface PaintMotion {

    /** Which of a node's colours. */
    enum Channel {
        /** Its fill. */
        BACKGROUND,
        /** Its border's colour. */
        BORDER,
        /** Its text's colour, where it states one. Spans that state their own are their own. */
        TEXT
    }

    /** Nothing fades: every node is drawn in the colour it was given. The default, and reduced motion. */
    PaintMotion NONE = new PaintMotion() {

        @Override
        public void repainted(long node, Channel channel, Color from, Color to) {
            // nothing fades, so nothing needs telling
        }

        @Override
        public Color drawn(long node, Channel channel, Color settled) {
            return settled;
        }

        @Override
        public boolean moving() {
            return false;
        }

        @Override
        public String toString() {
            return "PaintMotion.NONE";
        }
    };

    /**
     * Node {@code node}'s {@code channel} has just changed from {@code from} to {@code to}, both colours. An
     * implementation that fades starts from the colour the node is drawn in now (which is {@code from} only when the
     * node was at rest) and ends at {@code to}.
     */
    void repainted(long node, Channel channel, Color from, Color to);

    /**
     * The colour to draw node {@code node}'s {@code channel} in, given that {@code settled} is the colour it was
     * given. {@code settled} itself whenever nothing is fading there, and when what is fading there is on its way to
     * some other colour: a fade is never allowed to draw a colour the node was not given on its way to one it was.
     */
    Color drawn(long node, Channel channel, Color settled);

    /** Whether anything is still on its way, so the frame after this one has something different to draw. */
    boolean moving();
}
