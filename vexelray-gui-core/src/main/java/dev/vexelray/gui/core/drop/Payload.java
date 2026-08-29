package dev.vexelray.gui.core.drop;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * What is being dragged, offered in as many forms as its source can supply.
 *
 * <pre>{@code
 * Payload dragged = Payload.of(ROW, row).andLazily(TEXT, row::label);
 *
 * // ...and at a drop target, which understands one of those and not the other:
 * return dragged.as(ROW).map(r -> insertAfter(r, here)).orElse(Drop.NONE);
 * }</pre>
 *
 * <h2>Inspectable before the drop, which is the whole requirement</h2>
 *
 * <p>A payload is complete the moment the drag starts, not at the moment it lands. That is what lets a target
 * decide whether it will accept — and show that it will — while the pointer is still moving, rather than taking
 * the drop and then failing. It is also forced on us by the platform: an OS file drag hands over its paths at
 * {@code DragEnter}, and a drop target that could not answer until {@code Drop} would have to accept everything
 * and apologise afterwards.
 *
 * <h2>Several forms, one drag</h2>
 *
 * <p>The same drag can be a tree row to the tree, a path to the shell and a line of text to an editor, and which
 * one is used is the <em>target's</em> decision rather than the source's. A source that offered only its richest
 * form would be deciding, on behalf of targets it has never heard of, that they are not allowed to participate.
 *
 * <p>Forms are supplied lazily and computed at most once, because the expensive one is usually the one nothing
 * asks for — reading a file's contents to offer them as text, when every target under the pointer wanted the path.
 *
 * <h2>Immutable</h2>
 *
 * <p>{@link #and} returns a new payload. A drag in flight is read by the frame loop while the source may still be
 * adding forms to a builder it kept, and a payload that could grow underneath a target would let two targets
 * disagree about what was on offer in the same frame.
 */
public final class Payload {

    private static final Payload EMPTY = new Payload(Map.of());

    /** Suppliers by type, in declaration order so {@link #toString} reads the way the source wrote it. */
    private final Map<PayloadType<?>, Supplier<?>> forms;

    /** Memoised results, so a lazily-supplied form is computed once however many targets ask. */
    private final Map<PayloadType<?>, Object> resolved = new LinkedHashMap<>();

    private Payload(Map<PayloadType<?>, Supplier<?>> forms) {
        this.forms = forms;
    }

    /** A payload offering nothing. A drag with no form at all is legal and every target will decline it, which
     * is a better answer than refusing to start the drag. */
    public static Payload empty() {
        return EMPTY;
    }

    /** A payload offering {@code value} as {@code type}. */
    public static <T> Payload of(PayloadType<T> type, T value) {
        Objects.requireNonNull(value, "value");
        return EMPTY.andLazily(type, () -> value);
    }

    /** A payload offering a {@code type} that is computed only if something asks for it. */
    public static <T> Payload lazily(PayloadType<T> type, Supplier<? extends T> value) {
        return EMPTY.andLazily(type, value);
    }

    /** This payload, also offered as {@code type}. Re-offering a type already present replaces it. */
    public <T> Payload and(PayloadType<T> type, T value) {
        Objects.requireNonNull(value, "value");
        return andLazily(type, () -> value);
    }

    /** This payload, also offered as a {@code type} computed only if something asks for it. Named apart from
     * {@link #and} rather than overloading it: a payload whose own value is a lambda would match both, and the
     * call would fail to compile for a reason that has nothing to do with what the caller meant. */
    public <T> Payload andLazily(PayloadType<T> type, Supplier<? extends T> value) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
        Map<PayloadType<?>, Supplier<?>> next = new LinkedHashMap<>(forms);
        next.put(type, value);
        return new Payload(Map.copyOf(next));
    }

    /** Whether this payload can be read as {@code type}. Cheap: it never computes the form. */
    public boolean offers(PayloadType<?> type) {
        return forms.containsKey(type);
    }

    /** Every form on offer. For a target choosing among several, and for diagnostics. */
    public Set<PayloadType<?>> forms() {
        return forms.keySet();
    }

    /**
     * Read this payload as {@code type}, or empty if it was never offered as one.
     *
     * <p>A supplier that returns null is treated as "not offered after all" rather than propagated: a source that
     * cannot produce the form it promised has, in the only sense that matters to a target, not promised it.
     */
    @SuppressWarnings("unchecked")   // a type's parameter is fixed at construction and only its own supplier is
                                     // ever stored against it, so the cast holds by the same argument as a
                                     // heterogeneous container's
    public <T> Optional<T> as(PayloadType<T> type) {
        Objects.requireNonNull(type, "type");
        Supplier<?> supplier = forms.get(type);
        if (supplier == null) {
            return Optional.empty();
        }
        synchronized (resolved) {
            // computeIfAbsent would be neater but cannot store the absence of a value, and a supplier that
            // returns null must not be re-run on every frame of a drag that asks sixty times a second.
            if (!resolved.containsKey(type)) {
                resolved.put(type, supplier.get());
            }
            return Optional.ofNullable((T) resolved.get(type));
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Payload[");
        String sep = "";
        for (PayloadType<?> type : forms.keySet()) {
            sb.append(sep).append(type.name());
            sep = ", ";
        }
        return sb.append(']').toString();
    }
}
