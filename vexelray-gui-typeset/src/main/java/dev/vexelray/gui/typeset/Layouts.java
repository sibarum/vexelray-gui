package dev.vexelray.gui.typeset;

/**
 * Where the built-in seven arrange themselves. <b>Not implemented yet — this is P2</b> (docs/typeset.md §10);
 * P0 fixed the vocabulary and the SPI, and this is the file P2 fills in.
 *
 * <p>The bodies live here rather than inline in {@link Box} for one reason: it keeps {@code Box} readable as the
 * <em>contract</em>. A reader deciding whether to implement their own box kind should be able to read the
 * interface and the seven declarations without wading through geometry.
 *
 * <p>These are called through {@link Box#arrange} like any other implementation, and they get the same
 * {@link Arrangement} an application's box would. Nothing here is package-private state or a shortcut past the
 * interface, which is what makes "an application can write a box kind" a true statement rather than an
 * aspiration — {@code VocabularyTest} asserts it.
 */
final class Layouts {

    private Layouts() {
    }

    static Placed run(Box.Run box, Arrangement a) {
        throw notYet("Run");
    }

    static Placed row(Box.Row box, Arrangement a) {
        throw notYet("Row");
    }

    static Placed stack(Box.Stack box, Arrangement a) {
        throw notYet("Stack");
    }

    static Placed attach(Box.Attach box, Arrangement a) {
        throw notYet("Attach");
    }

    static Placed rule(Box.Rule box, Arrangement a) {
        throw notYet("Rule");
    }

    static Placed stretch(Box.Stretch box, Arrangement a) {
        throw notYet("Stretch");
    }

    static Placed grid(Box.Grid box, Arrangement a) {
        throw notYet("Grid");
    }

    private static UnsupportedOperationException notYet(String kind) {
        return new UnsupportedOperationException(
                kind + ".arrange lands in P2 (docs/typeset.md §10). P0 fixed the vocabulary and the SPI; the "
                        + "geometry comes with the engine, against a real atlas.");
    }
}
