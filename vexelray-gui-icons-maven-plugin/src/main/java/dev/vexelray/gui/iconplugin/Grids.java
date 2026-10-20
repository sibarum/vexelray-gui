package dev.vexelray.gui.iconplugin;

/**
 * The two cell sizes a set bakes into: large art into {@code largeInner}-texel squares with {@code largeSpread} of
 * margin, small art into {@code smallInner} with {@code smallSpread}. The suite's set uses 64 + 16 and 48 + 12: the
 * margin is a quarter of the shape, as far as a glow or a shadow reaches. A set that extends another takes its grids,
 * so an application's icons sit beside the suite's at the same weight and with the same reach of effect.
 */
record Grids(int largeInner, int largeSpread, int smallInner, int smallSpread) {

    static final Grids SUITE = new Grids(64, 16, 48, 12);

    Grids {
        if (largeInner <= 0 || smallInner <= 0 || largeSpread < 0 || smallSpread < 0) {
            throw new IllegalArgumentException("grids of " + largeInner + " + " + largeSpread + " and " + smallInner
                    + " + " + smallSpread + " texels");
        }
    }

    int largeCell() {
        return largeInner + 2 * largeSpread;
    }

    int smallCell() {
        return smallInner + 2 * smallSpread;
    }

    /** This, with any of the four given replacing its own. */
    Grids with(Integer largeInner, Integer largeSpread, Integer smallInner, Integer smallSpread) {
        return new Grids(largeInner != null ? largeInner : this.largeInner,
                largeSpread != null ? largeSpread : this.largeSpread,
                smallInner != null ? smallInner : this.smallInner,
                smallSpread != null ? smallSpread : this.smallSpread);
    }
}
