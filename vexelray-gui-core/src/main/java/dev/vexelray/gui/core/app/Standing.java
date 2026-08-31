package dev.vexelray.gui.core.app;

import dev.vexelray.os.WindowConfig;

/**
 * Where a second window stands relative to the window it belongs to — the application's main window unless the
 * spec named another ({@link WindowSpec#belongingTo}). This is the one thing about a window
 * that cannot be decided after it exists: every platform fixes it at creation, from whether the window was given
 * an owner (Win32 owned windows, X11 {@code WM_TRANSIENT_FOR}, Wayland {@code xdg_toplevel.set_parent}, macOS
 * child windows), and a window's standing is what it was created with for as long as it lives.
 *
 * <p><b>Why it has to be declared.</b> Ownership is not one property but a bundle the platform hands over
 * whole — no taskbar button of its own, <em>always above the owner</em>, raised with the group, minimized and
 * destroyed with the owner — and the middle term is not optional. An owned window sits above the window that
 * owns it whatever the user does; clicking the main window activates it and leaves it underneath. That is right
 * for a thing that belongs <em>to</em> the main window and wrong for a thing that sits <em>beside</em> it, and
 * nothing about a {@link WindowSpec} lets the framework tell those apart. So the application says which.
 *
 * <p>Expressed as a function from the requested config to the config the window is actually created from, rather
 * than as a flag the host reads: the standing <em>is</em> the difference it makes, and a standing added later
 * (a palette pinned above every window of the application, say) is a new constant here and no change anywhere
 * else.
 */
@FunctionalInterface
public interface Standing {

    /**
     * The config this window is created from, given the OS handle of the window it stands relative to — its
     * anchor, which is the application's main window unless the spec named another
     * ({@link WindowSpec#belongingTo}). Called on the main thread, once, at creation.
     */
    WindowConfig place(WindowConfig config, long anchor);

    /**
     * An ordinary top-level window that happens to belong to this application: its own place in the stack, so
     * the main window comes forward in front of it when the user focuses the main window, and its own taskbar
     * button. It is not minimized or destroyed by the OS along with the main window — the frame loop closes it
     * when the loop ends, which is the only part of that the application actually depended on.
     *
     * <p>The requested config passes through untouched, so an application that named an owner itself gets the
     * one it named. The default for {@link WindowSpec}: a window is a window unless it is said to be less.
     */
    Standing PEER = (config, anchor) -> config;

    /**
     * A window that belongs to the main window and shows it: always above it, no taskbar button of its own,
     * minimized and destroyed with it. What a dialog, a palette, or a tool window pinned to its document wants,
     * and what a modal must have — a modal the user can put behind the window it is blocking is a trap.
     *
     * <p>Ownership is not modality: the main window stays fully interactive. Blocking it is
     * {@link GuiApp#modalWindow} and is a separate decision.
     */
    Standing SATELLITE = WindowConfig::ownedBy;
}
