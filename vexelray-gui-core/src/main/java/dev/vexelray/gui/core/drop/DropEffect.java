package dev.vexelray.gui.core.drop;

/**
 * What releasing here would do. The answer a target owes on every pointer move, not only at the drop.
 *
 * <p>It exists as its own value rather than as a boolean because the platform demands it — {@code IDropTarget}
 * wants an effect back from every {@code DragOver}, synchronously — and because the user is owed the same
 * answer: a move and a copy leave the tree in different states, and which one is about to happen has to be
 * legible <em>before</em> the button comes up rather than discoverable afterwards by undoing.
 */
public enum DropEffect {

    /** Nothing would happen. Not a failure — the honest answer for the space between two things that do accept,
     * and what makes a resolution total rather than leaving points unanswered. */
    NONE,

    /** The thing would end up here and remain where it came from. */
    COPY,

    /** The thing would end up here and leave where it came from. */
    MOVE,

    /** A reference would end up here, pointing at where it came from. */
    LINK;

    /**
     * What a drop whose effect would be this does with {@code held} down, as a desktop's file drop reads the keys:
     * Control alone copies, Shift alone moves, and anything else leaves this. Only a copy or a move answers the keys;
     * a link and nothing stay what they are. Read at every resolution, so the indicator shows what the keys chose
     * before the button comes up.
     */
    public DropEffect byKeys(java.util.Set<sibarum.tactroller.api.Modifier> held) {
        if (this != COPY && this != MOVE) {
            return this;
        }
        boolean control = held.contains(sibarum.tactroller.api.Modifier.CONTROL);
        boolean shift = held.contains(sibarum.tactroller.api.Modifier.SHIFT);
        return control && !shift ? COPY : shift && !control ? MOVE : this;
    }

    /** Whether releasing here would do anything at all. */
    public boolean accepts() {
        return this != NONE;
    }
}
