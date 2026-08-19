package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.ClickEvent;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.text.TextLayout;
import sibarum.atchung.Subscription;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.MouseButton;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A context menu: a floating panel of commands, opened at a point (usually the pointer, via
 * {@link Gui#onContextClick}) and dismissed by choosing one, pressing Escape, or clicking anywhere else.
 *
 * <p><b>The menu is a floating last child of the root</b> — the overlay primitive, not a special layer. It floats
 * out of the flow ({@code Node.floatAt}), so opening it reflows nothing; being the last child, it paints over and
 * is hit before the page; and the layout clamps a floating node into its parent, so a menu opened near an edge
 * slides in rather than cropping. It attaches itself on first {@link #show} — after the page is built — and is
 * hidden, never removed, between openings, so its items keep their handlers.
 *
 * <p><b>Dismissal is claims and observation, not modality.</b> While open, the menu claims Escape at
 * {@link ClaimScope#VISIBLE} — released on hide, because a hidden node keeps its claims. Click-away rides the
 * {@code clicks()} topic, which publishes every click on every node: a left click whose target is not one of the
 * menu's own nodes closes it. Right clicks are deliberately ignored there — the owner's context handler is what
 * re-anchors the menu, and racing it with a dismissal would close what it just opened. Nothing is blocked while
 * the menu is up; the click that lands elsewhere still does what it always did.
 *
 * <p>Anchor coordinates are client-space pixels (what {@link ClickEvent} carries); the menu converts them to
 * {@code dp} against the current density, so the position is faithful at any DPI and ignores zoom — a pointer
 * position is a physical fact, not content.
 */
public final class ContextMenu implements AutoCloseable {

    private static final Color MENU_BG = Color.rgb(0x1b2130);
    private static final Color MENU_BORDER = Color.rgb(0x2b3346);
    private static final Color ITEM_HOVER = Color.rgb(0x2b3346);
    private static final Color INK = Color.rgb(0xeef2f8);
    private static final Color SEPARATOR = Color.rgb(0x2b3346);

    private static final Shortcut ESCAPE = Shortcut.of(Key.ESCAPE);

    private final Gui gui;
    private final Node menu;
    private final Subscription clickSub;

    /** Every node this menu owns, so click-away can tell "one of mine" from "somewhere else". */
    private final Set<Long> ownIds = ConcurrentHashMap.newKeySet();

    private volatile boolean attached;
    private volatile boolean shown;

    /** Build an empty menu on {@code gui}; add commands with {@link #item} and open it with {@link #show}. */
    public ContextMenu(Gui gui) {
        this.gui = gui;
        this.menu = gui.column()
                .visible(false)
                .background(MENU_BG)
                .corner(Length.rem(0.5f))
                .border(Length.rem(0.1f), MENU_BORDER)
                .lit(true)
                .elevation(Length.rem(1f))
                .padding(Length.dp(4))
                .scroll(false, false);
        ownIds.add(menu.id());
        this.clickSub = gui.bus().subscribe(gui.clicks(), this::onAnyClick);
    }

    /** Add a command. The action runs on the handler executor, after the menu has closed. */
    public ContextMenu item(String label, Runnable action) {
        Node row = gui.text(label)
                .width(Length.FILL)
                .textSize(Length.rem(1))
                .textColor(INK)
                .corner(Length.rem(0.4f))
                .padding(Length.dp(4), Length.dp(12))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
        ownIds.add(row.id());
        gui.onState(row, state -> row.background(state == InteractionState.NORMAL ? null : ITEM_HOVER));
        gui.onClick(row, () -> {
            hide();
            if (action != null) {
                action.run();   // already on the handler executor — the lane app callbacks run on
            }
        });
        menu.append(row);
        return this;
    }

    /** Add a thin horizontal rule between item groups. */
    public ContextMenu separator() {
        Node rule = gui.box().width(Length.FILL).height(Length.dp(1)).background(SEPARATOR)
                .margin(Length.dp(3)).scroll(false, false);
        ownIds.add(rule.id());
        menu.append(rule);
        return this;
    }

    /** The menu's own node — for tests and for styling beyond the defaults. */
    public Node node() {
        return menu;
    }

    /** Whether the menu is currently open. */
    public boolean shown() {
        return shown;
    }

    /**
     * Open the menu at client-space pixel {@code (x, y)} — pass a {@link ClickEvent}'s coordinates straight in.
     * Reopening while already open just moves it. The first call attaches the menu to the root, above a page
     * that is by then built.
     */
    public ContextMenu show(float x, float y) {
        if (!attached) {
            attached = true;
            gui.root().append(menu);
        }
        float dpi = Math.max(0.0001f, gui.dpi().value());
        menu.floatAt(Length.dp(x / dpi), Length.dp(y / dpi));
        menu.visible(true);
        if (!shown) {
            shown = true;
            // Claimed only while open (and released on hide): a hidden node keeps its claims, and a closed menu
            // owning Escape would shadow whatever the page wants it for.
            gui.claim(menu, ESCAPE, ClaimScope.VISIBLE, this::hide);
        }
        return this;
    }

    /** Close the menu. Hidden, not removed: the items keep their identity and handlers for the next opening. */
    public void hide() {
        if (!shown) {
            return;
        }
        shown = false;
        menu.visible(false);
        gui.releaseClaim(menu, ESCAPE);
    }

    /** Release the menu's subscription, registrations and node. */
    @Override
    public void close() {
        clickSub.close();
        hide();
        gui.releaseNode(menu);
        if (attached) {
            menu.remove();
        }
    }

    /**
     * Click-away: every click on every node is published here, so "the user clicked somewhere else" is an
     * observation, not a capture. Left button only — the right click that would re-anchor this menu publishes
     * too, and must not race the show it triggers.
     */
    private void onAnyClick(ClickEvent e) {
        if (shown && e.button() == MouseButton.LEFT && !ownIds.contains(e.nodeId())) {
            hide();
        }
    }
}
