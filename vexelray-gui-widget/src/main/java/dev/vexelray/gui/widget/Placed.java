package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Addressed;
import dev.vexelray.gui.core.drop.DropEffect;

import java.util.Objects;

/**
 * The user put {@code item} at {@code where} in the widget at {@code at}, meaning {@code effect}: a drop, a paste,
 * or a card moved from the keyboard. Published on the widget's own topic ({@link TreeView#PLACED},
 * {@link Deck#PLACED}) and addressed to it.
 *
 * <p><b>An announcement, not a request the widget waits on.</b> The hierarchy is the application's, so the
 * application makes the move in its own model, records it in its own history so it undoes like any other edit, and
 * then says so: a tree is told to look again ({@link TreeView#REFRESH}), a deck is handed its new list. It may also
 * refuse, for a reason only its model knows — a file of that name already in the folder — and say why; nothing on
 * screen moved meanwhile but what the widget predicts itself.
 *
 * <p>Which placements are offered at all is declared rather than asked (vexelray-framework's threading.md, T7.5):
 * the effects a widget was made {@code reorderable} with, what each entry {@link TreeView.Entry#acceptsChildren
 * accepts}, and what the widget can see for itself, such as an item dropped into itself. That is decided in the
 * frame, for every move of the pointer, with nothing of the application's run.
 *
 * @param at     the widget's address
 * @param item   what was placed
 * @param where  where, relative to an item already there
 * @param effect {@code MOVE} for a drag or a cut, {@code COPY} for a copy
 */
public record Placed(String at, Object item, Placement<?> where, DropEffect effect) implements Addressed {

    public Placed {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(where, "where");
        Objects.requireNonNull(effect, "effect");
    }

    @Override
    public Object address() {
        return at;
    }
}
