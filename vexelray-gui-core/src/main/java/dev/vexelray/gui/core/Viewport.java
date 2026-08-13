package dev.vexelray.gui.core;

/**
 * The drawable size of the window in pixels. Published as an Atchung {@code State} (coalesced, latest-wins) on the
 * GUI's bus whenever it changes, so a window resize is observable to any component — the framework relays out from
 * it, and workers can react too. A "what is true now" signal, exactly like the pointer position.
 *
 * @param width  drawable width in pixels (never negative)
 * @param height drawable height in pixels (never negative)
 */
public record Viewport(int width, int height) {

    public Viewport {
        width = Math.max(0, width);
        height = Math.max(0, height);
    }
}
