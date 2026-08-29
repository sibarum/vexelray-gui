package dev.vexelray.gui.core.drop;

import java.util.Objects;

/**
 * A name for one thing a {@link Payload} can be read as: files, a tree node, plain text, an application's own
 * record. Identity, not a value — two types with the same name are still different types, because the whole
 * point is that a drop target recognises the token it was given rather than a string that might collide.
 *
 * <pre>{@code
 * // Declared once, wherever the thing being dragged is defined.
 * static final PayloadType<List<Path>> FILES = PayloadType.of("files");
 * static final PayloadType<TreeRow>    ROW   = PayloadType.of("tree-row");
 * }</pre>
 *
 * <h2>Why a token rather than a set of cases</h2>
 *
 * <p>The alternative is a sealed payload with a case per kind, and it fails the first time an application wants
 * to drag something the framework has never heard of — which is immediately, since dragging an application's own
 * records is the ordinary use. Enumerating the kinds would make the framework the authority on what can be
 * dragged, and it is not: it is the authority on what a <em>drop</em> is, which is a different and much smaller
 * question. A payload therefore carries whatever its source declared, and a target asks for what it understands.
 *
 * <p>The type parameter is what the payload yields, so {@link Payload#as} needs no cast at the call site and a
 * target that asks for the wrong shape does not compile.
 *
 * @param <T> what a payload offering this type yields
 */
public final class PayloadType<T> {

    private final String name;

    private PayloadType(String name) {
        this.name = name;
    }

    /** A fresh type. Call once and keep the result; calling twice with the same name yields two distinct types
     * that no payload will ever satisfy at once, which is the intended behaviour of an identity. */
    public static <T> PayloadType<T> of(String name) {
        return new PayloadType<>(Objects.requireNonNull(name, "name"));
    }

    /** What this type is called. For diagnostics — never for matching, which is by identity. */
    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "PayloadType[" + name + "]";
    }
}
