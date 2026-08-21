package dev.vexelray.gui.core.app;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;

/**
 * The dim over a window that a dialog has taken priority from: one translucent sheet across the whole viewport,
 * drawn last so nothing behind it — not a menu, not a tooltip, not a floating panel — looks available while it
 * is up.
 *
 * <p>Three declarations carry the whole behaviour, and each is deliberate:
 * <ul>
 *   <li><b>Floating at the origin, {@code vw(100)} × {@code vh(100)}</b> — out of the flow, so appearing dims the
 *       window without moving a single pixel of it. A dim that reflowed the page would be worse than no dim.</li>
 *   <li><b>Last child of the root</b> — paint order, which is the only thing "over everything" can mean in a
 *       tree that has no z-index.</li>
 *   <li><b>{@link Node#hitInert}</b> — drawn, never a pointer target. The block itself is the window manager's
 *       ({@link dev.vexelray.os.NativeWindow#setEnabled}); this sheet only says so. A scrim that swallowed
 *       clicks would be a second, weaker implementation of modality that could disagree with the real one.</li>
 * </ul>
 *
 * <p>Created once per tree and kept, hidden, between dialogs — the same "hide, never remove" rule the rest of
 * this framework's overlays follow, so nothing is rebuilt to ask a second question.
 */
final class ModalScrim {

    private final Node node;

    private ModalScrim(Node node) {
        this.node = node;
    }

    /** Attach a hidden scrim to {@code gui}'s root. */
    static ModalScrim install(Gui gui) {
        Node node = gui.box()
                .width(Length.vw(100))
                .height(Length.vh(100))
                .floatAt(Length.ZERO, Length.ZERO)
                .hitInert(true)
                .scroll(false, false)
                .visible(false);
        gui.root().append(node);
        return new ModalScrim(node);
    }

    /** Show the scrim in {@code color}, or hide it. A null colour is "do not dim at all". */
    void dim(boolean dimmed, Color color) {
        if (color != null) {
            node.background(color);
        }
        node.visible(dimmed && color != null);
    }

    /** The scrim's node — for tests, and for an application that wants to style the dim itself. */
    Node node() {
        return node;
    }
}
