package dev.vexelray.gui.core.style;

import dev.vexelray.gui.core.input.FocusCause;
import dev.vexelray.gui.core.layout.Length;

/**
 * How the node holding keyboard focus says so: one ring, drawn by the renderer around whichever node has focus,
 * so no widget draws its own and a control nobody remembered to mark is marked anyway. The theme states it
 * ({@link Theme#focusRing()}); a node may override it ({@code Node.focusRing}).
 *
 * <p>The ring is a visual transform (architecture.md §7), like opacity and overlay: nothing is laid out, measured
 * or hit-tested, so focusing moves no geometry and changes no pointer target.
 *
 * @param color  a {@link Role}, not a colour, so a palette swap reaches the ring
 * @param width  how thick the ring is
 * @param offset how far the ring stands clear of the border box. Signed: positive is outside, zero sits just on
 *               the outer edge, and {@code -width} lies just inside. One axis covers outline, border overlay and
 *               inset ring. The corner radius follows the node's own radius plus the offset, so the ring runs
 *               parallel to the shape it surrounds
 * @param when   which arrivals of focus show the ring
 */
public record FocusRing(Role color, Length width, Length offset, Visibility when) {

    /** The framework's ring: accent, 0.125rem wide, standing 0.125rem clear of the box, shown however focus came. */
    public static final FocusRing STANDARD = new FocusRing(Role.ACCENT, Length.rem(0.125f), Length.rem(0.125f),
            Visibility.ALWAYS);

    /** No ring anywhere. For an application, or a node, that draws focus itself. */
    public static final FocusRing NONE = new FocusRing(Role.ACCENT, Length.ZERO, Length.ZERO, Visibility.NEVER);

    public FocusRing {
        color = color == null ? Role.ACCENT : color;
        width = width == null ? Length.ZERO : width;
        offset = offset == null ? Length.ZERO : offset;
        when = when == null ? Visibility.ALWAYS : when;
    }

    public FocusRing color(Role c) {
        return new FocusRing(c, width, offset, when);
    }

    public FocusRing width(Length w) {
        return new FocusRing(color, w, offset, when);
    }

    public FocusRing offset(Length o) {
        return new FocusRing(color, width, o, when);
    }

    public FocusRing when(Visibility v) {
        return new FocusRing(color, width, offset, v);
    }

    /** Whether a node that took focus by {@code cause}, and does or does not take typed text, shows this ring. */
    public boolean shows(FocusCause cause, boolean editable) {
        return when.shows(cause, editable);
    }

    /**
     * Should a node that took focus this way show the ring? Open, so an application with its own rule writes it as
     * a lambda; the framework's two answers are constants.
     */
    @FunctionalInterface
    public interface Visibility {

        /**
         * @param cause    how focus arrived
         * @param editable whether the focused node takes typed text — which needs to say where typing goes
         *                 however focus got there
         */
        boolean shows(FocusCause cause, boolean editable);

        /** Every focused node shows the ring. */
        Visibility ALWAYS = (cause, editable) -> true;

        /**
         * CSS's {@code :focus-visible}: shown when focus came by keyboard or by program, and on a node that takes
         * typed text however it got focus. A clicked button doesn't light up; a clicked field still says where
         * typing will land.
         */
        Visibility KEYBOARD = (cause, editable) -> cause != FocusCause.POINTER || editable;

        /** Never shown. */
        Visibility NEVER = (cause, editable) -> false;
    }
}
