package dev.vexelray.gui.core.style;

import dev.vexelray.canvas.Color;

/**
 * What colour a node is drawn in, as distinct from the colour it was last given.
 *
 * <p>A widget repaints by writing paints: a button's hover is {@code background(fill.at(HOVER))}, and the write is the
 * whole truth about the state it is in. What a write cannot state is the journey, so without this a
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
 * <p>A node holds a {@link Paint}, so what is reported is the colours it resolves to in the theme being drawn. A theme
 * swap is reported the same way, for every node whose colours it changes — including text in the theme's ink that
 * never stated a colour — so a source that fades a hover fades the whole tree from one look to the other.
 *
 * <p>{@link #repainted} is called on the GUI thread inside the frame, before the frame is drawn, so a fade started
 * there is in effect for the first frame that would have shown the new colour: a node never shows its destination
 * early, not even for one frame. {@link #drawn} is called by the renderer, which may be on another thread; an
 * implementation publishes what it reads there safely.
 *
 * <h2>Told and asked</h2>
 *
 * <p>This is a part under the frame contract (vexelray-gui's docs/plans/frame-contract.md): {@link #repainted} is its
 * told half, and {@link #draw} its asked half, run once per frame for the frame's present time before anything is
 * emitted. {@link #drawn} reads what {@code draw} chose, and computes nothing of its own.
 */
public interface PaintMotion {

    /** Which of a node's colours. */
    enum Channel {
        /** Its fill. */
        BACKGROUND,
        /** Its border's colour. */
        BORDER,
        /** Its text's colour, where it states one. Spans that state their own are their own. */
        TEXT,
        /**
         * Its own opacity ({@code Node.opacity}), reported as white at that alpha, so a source that blends colours
         * blends it with no case of its own: white to white is white, and the alpha goes linearly. Only for a node
         * on screen; one given an opacity before it is laid out is drawn at it from its first frame.
         */
        OPACITY,
        /**
         * How far what a node shows has come up over what it replaced, its words or its image, reported as white from
         * no alpha to full when they are replaced; the renderer draws what was there before at the rest. Only for a
         * node on screen.
         */
        REPLACED
    }

    /**
     * An amount, not a colour, as {@link Channel#OPACITY} and {@link Channel#REPLACED} carry one: white at alpha
     * {@code a}, so a source that blends colours blends it, and what is read back is its alpha. Never drawn.
     */
    static Color amount(float a) {
        return new Color(1f, 1f, 1f, a);
    }

    /**
     * Whether {@code a} and {@code b} look the same on screen: equal, or both at no alpha, which leaves no hue to tell
     * them apart. {@code Role.NONE} is transparent black, and a colour faded out is that colour at no alpha; the two
     * are the same nothing, so neither a fade nor a watch for cuts may treat the step from one to the other as a change.
     */
    static boolean same(Color a, Color b) {
        return a.equals(b) || (a.a() <= 0f && b.a() <= 0f);
    }

    /** {@link #amount} at its two ends. */
    Color NONE_OF_IT = amount(0f);
    Color ALL_OF_IT = amount(1f);

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
        public long draw(dev.vexelray.gui.core.Frame frame) {
            return dev.vexelray.gui.core.Frame.NEVER;
        }

        @Override
        public String toString() {
            return "PaintMotion.NONE";
        }
    };

    /**
     * Node {@code node}'s {@code channel} has just changed from {@code from} to {@code to}, both colours. An
     * implementation that fades starts from the colour the node is drawn in now (which is {@code from} only when the
     * node was at rest) and ends at {@code to}. Told {@code from} equal to {@code to}, the channel is at rest there:
     * whatever was on its way is let go, so the next change starts from {@code from} itself.
     */
    void repainted(long node, Channel channel, Color from, Color to);

    /**
     * The colour to draw node {@code node}'s {@code channel} in, given that {@code settled} is the colour it was
     * given. {@code settled} itself whenever nothing is fading there, and when what is fading there is on its way to
     * some other colour: a fade is never allowed to draw a colour the node was not given on its way to one it was.
     */
    Color drawn(long node, Channel channel, Color settled);

    /**
     * Choose every colour on its way for {@code frame}'s present time, and answer when next to be asked:
     * {@link dev.vexelray.gui.core.Frame#NEVER} once nothing is on its way, the present time while something is. Called
     * on the GUI thread once per frame, after this frame's {@link #repainted} calls and before any {@link #drawn}.
     */
    long draw(dev.vexelray.gui.core.Frame frame);
}
