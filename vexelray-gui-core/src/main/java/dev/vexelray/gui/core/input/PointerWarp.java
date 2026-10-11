package dev.vexelray.gui.core.input;

/**
 * Where the GUI asks the platform to put the pointer — the host's half of {@link PointerRide}, installed with
 * {@code Gui.onPointerWarp}. Core never touches the OS; whoever owns the input backend does the moving.
 */
@FunctionalInterface
public interface PointerWarp {

    /** Does nothing: a host that cannot, or will not, move the pointer. */
    PointerWarp NONE = (x, y) -> { };

    /** Put the pointer at {@code (x, y)}, client-space px. Called from whatever thread drives the animation. */
    void to(float x, float y);
}
