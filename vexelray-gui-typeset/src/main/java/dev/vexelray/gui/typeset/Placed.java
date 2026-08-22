package dev.vexelray.gui.typeset;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of laying a {@link Box} out: its geometry plus a flat list of things to paint.
 *
 * <p><b>The declared box is a promise.</b> Every draw must fit inside {@code width × (ascent + descent)}. That is
 * the one rule the framework holds an implementation to, and it is what lets a parent place a child it knows
 * nothing about. Violating it is a bug, not a rendering mode: it is asserted in tests, and at runtime the enclosing
 * block's clip contains the damage to visual overlap rather than corruption.
 *
 * <p>Coordinates are in <b>pixels</b>: {@code x} grows right, the reference baseline is {@code y = 0}, and
 * {@code y} grows <b>down</b> — so material above the baseline has negative y. {@link #ascent} is the extent above
 * the baseline as a positive number and {@link #descent} the extent below.
 *
 * <p>Pixels, and not the em this module otherwise insists on, because this is the stage where geometry is
 * resolved: the tone map has already run, and its floor and ceiling are physical. The projection converts once,
 * and it must use {@code Length.dp} rather than {@code Length.em} — the layout basis, zoom and DPI included, is
 * already baked into these numbers, so resolving them as em would apply both a second time.
 *
 * <h2>Why there is a sink and not a sealed draw type</h2>
 * A consumer of a draw list — the node projection, a renderer, a remote client holding no atlas — needs a
 * <b>closed set of operations</b> it can implement completely. The obvious way to get that is a sealed {@link Draw}
 * the consumer switches over, and that is exactly the shape this module does not use: a switch over a type
 * hierarchy is hand-written dispatch, and it puts the behaviour somewhere other than the type.
 *
 * <p>Inverted instead: {@link Draw} is <b>open</b> and knows how to express itself as calls on a {@link Sink},
 * which is <b>closed</b>. A consumer implements two methods and is done. A draw kind nobody has written yet still
 * works, because it can only express itself in operations every consumer already handles. The closed thing is the
 * alphabet a consumer must understand; the open thing is what can be authored against it — and neither side ever
 * switches.
 *
 * <p>Widening {@link Sink} is therefore a real, visible decision with consequences in every consumer, which is
 * what it should be. (The first thing that will ask for it is a diagonal line — a commutative-diagram arrow —
 * which neither {@link Bar} nor the axis-aligned node projection can draw.)
 *
 * @param width   horizontal advance
 * @param ascent  extent above the baseline, positive
 * @param descent extent below the baseline, positive
 * @param draws   what to paint, in this box's own frame
 */
public record Placed(double width, double ascent, double descent, List<Draw> draws) {

    public Placed {
        draws = List.copyOf(draws);
    }

    /**
     * Something paintable. Open: a {@link Box} implementation may define its own, so long as it can say what it is
     * in terms of a {@link Sink}.
     */
    public interface Draw {

        /** This draw translated by {@code (dx, dy)} — how a parent places a laid-out child's contents. */
        Draw shifted(double dx, double dy);

        /** Express this draw as calls on {@code sink}. The one method a new draw kind has to write. */
        void emitTo(Sink sink);
    }

    /**
     * What a consumer of a draw list implements — the closed alphabet every draw reduces to. Two operations, and a
     * consumer that implements both can render any draw kind, including ones written after it.
     */
    public interface Sink {

        /** A run of glyphs with its baseline at {@code y}, drawn at {@code size} pixels, in the face named by
         *  {@code face} (a profile key — the consumer resolves it, since only the consumer knows its atlas). */
        void glyphs(String text, String face, double x, double y, double size, Object sourceRef);

        /** A filled rectangle with its top-left at {@code (x, y)}. */
        void bar(double x, double y, double width, double height);
    }

    /**
     * A run of glyphs. {@code sourceRef} rides through from the {@link Box.Run} that produced it, so a draw list
     * can be mapped back to the application's own source without the framework knowing what that source is.
     */
    public record Glyphs(String text, String face, double x, double y, double size,
                         Object sourceRef) implements Draw {

        @Override
        public Draw shifted(double dx, double dy) {
            return new Glyphs(text, face, x + dx, y + dy, size, sourceRef);
        }

        @Override
        public void emitTo(Sink sink) {
            sink.glyphs(text, face, x, y, size, sourceRef);
        }
    }

    /** A filled rectangle with its top-left at {@code (x, y)} — a fraction bar, a vinculum, an underline. */
    public record Bar(double x, double y, double width, double height) implements Draw {

        @Override
        public Draw shifted(double dx, double dy) {
            return new Bar(x + dx, y + dy, width, height);
        }

        @Override
        public void emitTo(Sink sink) {
            sink.bar(x, y, width, height);
        }
    }

    /** An empty box of no extent — what a construct with nothing in it lays out to. */
    public static Placed empty() {
        return new Placed(0, 0, 0, List.of());
    }

    /** Total vertical extent. */
    public double height() {
        return ascent + descent;
    }

    /** Emit every draw to {@code sink}, in order. The whole of how a consumer reads a draw list. */
    public void emitTo(Sink sink) {
        for (Draw d : draws) {
            d.emitTo(sink);
        }
    }

    /** This box's draws translated by {@code (dx, dy)}, for a parent assembling its children. */
    public List<Draw> shifted(double dx, double dy) {
        List<Draw> out = new ArrayList<>(draws.size());
        for (Draw d : draws) {
            out.add(d.shifted(dx, dy));
        }
        return out;
    }
}
