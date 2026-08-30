package dev.vexelray.gui.core.drop;

/**
 * Something held, waiting to be put somewhere: a {@link Payload} and what putting it down would mean.
 *
 * <p>This is the clipboard, in the terms drag and drop already uses. Cut, copy and paste are not a second way of
 * moving things — they are the <em>keyboard's</em> way of reaching what a drag reaches, and saying so in the type
 * is what stops them growing a parallel vocabulary. A cut is a {@link DropEffect#MOVE} in hand; a copy is a
 * {@link DropEffect#COPY} in hand; a paste is a placement resolved at the selection instead of under the pointer.
 * Everything downstream — refusal, the change, the undo record — is then the same code.
 *
 * <p><b>In this process only.</b> The OS clipboard here carries text, so a transfer crosses windows within an
 * application and stops at its edge. That is a real boundary rather than a temporary one: carrying typed data
 * between processes is a platform facility this framework does not have, and pretending otherwise would mean a
 * paste that silently did nothing in the one case a user most expects it to work.
 *
 * @param payload what is held, in whatever forms its owner offered it
 * @param effect  what the holder intends: {@code MOVE} for a cut, {@code COPY} for a copy
 */
public record Transfer(Payload payload, DropEffect effect) {

    /** Nothing held — the state a paste has to be prepared for, and the one it starts in. */
    public static final Transfer NONE = new Transfer(Payload.empty(), DropEffect.NONE);

    public Transfer {
        if (payload == null) {
            throw new IllegalArgumentException("payload");
        }
        if (effect == null) {
            throw new IllegalArgumentException("effect");
        }
    }

    /** A cut: the thing is to be moved, and its origin gives it up when it lands. */
    public static Transfer cut(Payload payload) {
        return new Transfer(payload, DropEffect.MOVE);
    }

    /** A copy: the thing stays where it is, and landing makes another. */
    public static Transfer copy(Payload payload) {
        return new Transfer(payload, DropEffect.COPY);
    }

    /** Whether anything is held at all. */
    public boolean isEmpty() {
        return !effect.accepts() || payload.forms().isEmpty();
    }

    /** Whether what is held can be had as {@code type} — what a paste target asks before offering to take it. */
    public boolean offers(PayloadType<?> type) {
        return !isEmpty() && payload.offers(type);
    }
}
