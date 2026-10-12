package dev.vexelray.gui.core.style;

/**
 * What a theme does to an icon that is the whole of a control: how it answers the pointer, and how it shows a control
 * that is on. Shares of the icon's size, as an icon's own effects are, so they read alike at every size.
 *
 * <p>A theme's decision rather than the widget's, as a hover colour is: one look's buttons thicken under the pointer,
 * another's could stay still. Each is an effect the application's canvas shader draws only if it was composed with it
 * (the weight is {@code Effects.WEIGHT}); the glow is the core set's, which every shader has.
 *
 * @param hoverWeight how much bolder the icon is under the pointer: 0 for not at all
 * @param onGlow      how far a halo of the accent reaches round the icon of a control that is on: 0 for none
 */
public record IconEffects(float hoverWeight, float onGlow) {

    /** The framework's: a clear thickening under the pointer, and a glow for on. */
    public static final IconEffects STANDARD = new IconEffects(0.04f, 0.18f);

    /** Nothing but colour: the icon neither thickens nor glows. */
    public static final IconEffects STILL = new IconEffects(0f, 0f);

    public IconEffects {
        onGlow = Math.max(0f, onGlow);
    }
}
