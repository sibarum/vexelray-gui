package dev.vexelray.gui.core;

/**
 * What a node is, as far as the <em>window manager</em> is concerned — the one declaration a GUI that draws its
 * own window chrome has to make.
 *
 * <p>An application-drawn title bar is otherwise ordinary UI: boxes, text, buttons, laid out and hit-tested like
 * everything else. What the OS cannot infer is which of those pixels it should treat as a title bar, so that
 * dragging them moves the window, double-clicking maximizes it and right-clicking opens the system menu. Marking
 * a node {@link #DRAG} says exactly that, and the host pushes the resulting rectangles to the window each frame.
 *
 * <p>Nothing here is an event or a behaviour: it is a fact about a node, derived from the same layout everything
 * else is derived from, and re-published whenever the layout changes. Moving, resizing and snapping stay the
 * window manager's, and input stays the input stack's.
 */
public enum WindowRegion {

    /**
     * A title bar: dragging it moves the window, double-clicking maximizes or restores it, right-clicking opens
     * the system menu. The window manager takes the pointer here, so a node inside a drag region that the user is
     * meant to click must declare itself {@link #INTERACTIVE}.
     */
    DRAG,

    /**
     * Ordinary content, even inside a {@link #DRAG} region — the hole a title bar is punched with. Every control
     * drawn on the chrome (buttons, menu items, tabs) needs this, or the window manager will start a window drag
     * from it instead of letting the click through.
     */
    INTERACTIVE,

    /**
     * The application's maximize button: interactive like {@link #INTERACTIVE}, and additionally reported to the
     * window manager as the maximize affordance, which is what lets a platform offer its own window-arrangement
     * UI on hover (Windows 11's Snap Layouts flyout). The click is still the application's to handle — declaring
     * this does not make the button do anything.
     */
    MAXIMIZE_BUTTON
}
