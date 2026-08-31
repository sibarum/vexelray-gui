package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.WindowRegion;
import dev.vexelray.gui.core.app.AppWindow;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.Standing;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.os.Decorations;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.text.TextLayout;

/**
 * A panel docked against one edge of a window, which the user can collapse to a rail, pop out into a window of
 * its own, and dock back again — an outline, a console, a properties inspector, a tool palette.
 *
 * <p><b>Popping out is not a reparent, and cannot be.</b> A {@link Node} is a handle onto the tree that minted
 * it: it carries that tree's mutation sink, and its registrations — handlers, focusability, widget state — are
 * keyed by node id inside that tree and released when the node leaves it. There is no operation that moves a
 * subtree from one {@link Gui} to another, and there should not be: the thing that would have to survive such a
 * move is precisely the state that lives in the tree it is moving out of. So the content is declared as a
 * {@link Content} <b>builder</b> rather than handed over as a node, and it is built <b>once per host</b> — once
 * into the docked panel, and once into the popped-out window's tree the first time it is popped.
 *
 * <p><b>Both instances then stay alive.</b> Neither is ever removed; popping out hides the docked body and shows
 * the window, docking back does the reverse — the same choice {@link Tabs} makes for pages and for the same
 * reason. Removing and rebuilding would look equivalent and is not: it would hand back a panel that draws
 * correctly and cannot take a keystroke. The honest cost, stated once here so it is not discovered later: the
 * two instances are two trees, so per-node state <em>diverges</em> — a caret, a scroll offset, a selection left
 * in the docked copy is not the one the popped-out copy shows. State that must be the same in both belongs in
 * the application's model, which both builds read; that is also the only arrangement in which the two copies
 * could ever agree.
 *
 * <p><b>The docked panel is an ordinary child of the host tree.</b> It takes space in the layout, so the host's
 * content shrinks to make room, and it clips, scrolls and collapses with the window like anything else. Append
 * {@link #node()} into a row (for {@link Edge#LEFT}/{@link Edge#RIGHT}) or a column
 * ({@link Edge#TOP}/{@link Edge#BOTTOM}) on the side the edge names — the panel sizes itself across that axis
 * and fills the other, and puts its own rule on the side facing the host's content.
 *
 * <p><b>Collapsed and popped-out look the same from the host's side</b>, deliberately: in both, the docked body
 * is hidden and what is left is the header rail, which carries the control that undoes it. A container that can
 * hide something says how to show it again — including to {@link dev.vexelray.gui.core.nav.Navigation}, which
 * this registers a {@link dev.vexelray.gui.core.nav.Reveal} for: navigating to a landmark inside the docked
 * content expands the panel, and docks it back if it was popped out, rather than arriving at something the user
 * cannot see.
 *
 * <p>The window is a named {@link AppWindow}, so it is the framework's ordinary one-window-however-many-times
 * machinery: {@link #hostedBy} claims the name, the tree behind it outlives every open/close cycle, and the
 * window closing by any route at all — the dock button, its own close box, Alt+F4, the owner going away — is
 * heard in one place and docks the panel back. Until an application calls {@link #hostedBy}, there is no window
 * to pop into and the pop-out control is not shown: a button that does nothing is worse than no button.
 *
 * <p><b>The popped-out window wears the application's own chrome</b>, not the desktop's: it is created with
 * {@link Decorations#CLIENT} and carries a {@link TitleBar}, with the dock control in the bar's leading slot.
 * A panel that popped out of the application is still the application, and a floating panel wearing the system
 * caption while every other window of the same program draws its own is the one arrangement that looks like a
 * mistake. Nothing is re-implemented to get it: the strip declares {@link WindowRegion#DRAG} and the window
 * manager still moves, snaps and maximizes the window, while the caption buttons and the dock control punch
 * {@link WindowRegion#INTERACTIVE} holes so their clicks land. The bar is bound to the window it ends up in by
 * {@link TitleBar#commands}, so it commands the live window while there is one and nothing once it is gone.
 *
 * {@snippet :
 * Popout console = new Popout(gui, "Console", Popout.Edge.BOTTOM, (g, into) -> into.append(consoleView(g)))
 *         .size(Length.rem(12))
 *         .hostedBy(app, "console");
 * gui.root().column().children(editor, console.node());
 * }
 */
public final class Popout {

    /** Which edge of the host window the panel docks against, and therefore which axis it sizes across. */
    public enum Edge {
        LEFT, RIGHT, TOP, BOTTOM;

        /** Whether this edge docks along the vertical sides, so the panel sizes across the horizontal axis. */
        boolean vertical() {
            return this == LEFT || this == RIGHT;
        }

        /** Whether the panel comes before its rule in layout order — the rule always faces the host content. */
        boolean leading() {
            return this == LEFT || this == TOP;
        }
    }

    /**
     * How the panel's content is built. Called once per tree it appears in — the host tree, and the popped-out
     * window's tree — with the {@link Gui} that owns that tree and the container to append into. Build against
     * the {@code gui} handed in and never against one captured from elsewhere: a node minted by the wrong tree
     * is a mutation posted to a model that has never heard of it.
     */
    @FunctionalInterface
    public interface Content {
        void build(Gui gui, Node into);
    }

    /** Header strip height, matching {@link TitleBar}'s so a panel against the top lines up with the caption. */
    private static final Length HEADER_H = Length.dp(28);

    /** Header control footprint — square, so the rail a collapsed side panel leaves is as narrow as one. */
    private static final Length BUTTON = Length.dp(24);

    /** Cross-axis extent when the panel is open, if the application does not say otherwise. */
    private static final Length DEFAULT_SIZE = Length.rem(14);

    private final Gui host;
    private final Edge edge;
    private final Content content;

    private final Node root;
    private final Node panel;
    private final Node header;
    private final Node headerTitle;
    private final Node body;
    private final Node collapseIcon;
    private final Node popButton;

    /**
     * The popped-out window's tree. Minted in the constructor so there is exactly one place two trees are
     * created, and left <b>empty</b> until the first pop: an application that assembles a dozen panels and pops
     * none of them pays for a dozen empty {@link Gui}s and no nodes. Shares the host's bus — every panel, in
     * whichever window it currently is, is on the one bus the application's interactivity already rides.
     */
    private final Gui away;

    /** The away tree's content container and its chrome, built together at the first pop, or null before it. */
    private Node awayBody;
    private TitleBar awayBar;

    /** The window this panel pops into, or null until {@link #hostedBy}. */
    private volatile AppWindow window;
    private volatile Standing standing = Standing.SATELLITE;
    private volatile AppWindow anchor;
    private volatile String title;
    private volatile Length size = DEFAULT_SIZE;
    private volatile boolean collapsed;
    private volatile boolean popped;

    /**
     * A panel titled {@code title}, docked against {@code edge} of the window showing {@code host}, whose
     * content is built by {@code content}. The docked instance is built now; the popped-out one is built the
     * first time the panel is popped.
     */
    public Popout(Gui host, String title, Edge edge, Content content) {
        if (host == null || edge == null || content == null) {
            throw new IllegalArgumentException("host, edge and content must not be null");
        }
        this.host = host;
        this.edge = edge;
        this.content = content;
        this.title = title == null ? "" : title;
        Theme theme = host.theme();

        this.headerTitle = host.text(this.title)
                .textSize(Length.rem(0.8f))
                .textColor(theme.color(Role.DIM))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
        this.collapseIcon = host.text("−")   // − ; the atlas carries no chevron, and TreeView reads the same
                .textSize(Length.rem(1.1f))
                .textColor(theme.color(Role.INK))
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE);
        Node collapse = button(host, collapseIcon, Role.RAISED, null, this::toggleCollapsed);
        this.popButton = button(host, host.text("»")   // »
                        .textSize(Length.rem(1.0f))
                        .textColor(theme.color(Role.INK))
                        .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE),
                Role.RAISED, null, this::toggle)
                // Nothing to pop into until an application says where. Shown by hostedBy, once.
                .visible(false);

        Node controls = host.row().alignItems(AlignItems.CENTER).scroll(false, false)
                .children(popButton, collapse);
        this.header = host.row()
                .width(Length.FILL)
                .height(HEADER_H)
                .background(theme.color(Role.CHROME))
                .alignItems(AlignItems.CENTER)
                .justify(Justify.SPACE_BETWEEN)
                .padding(Length.ZERO, Length.dp(6))
                .scroll(false, false)
                .children(headerTitle, controls);

        this.body = host.column()
                .width(Length.FILL)
                .height(Length.FILL)
                .background(theme.color(Role.PANEL))
                .scroll(false, false);
        content.build(host, body);

        this.panel = host.column()
                .background(theme.color(Role.PANEL))
                .scroll(false, false)
                .children(header, body);

        // A one-pixel rule on the side facing the host's content, so the seam is a declared part of the panel
        // rather than something every application redraws beside it.
        Node rule = host.box()
                .background(theme.color(Role.LINE))
                .scroll(false, false);
        if (edge.vertical()) {
            rule.size(Length.dp(1), Length.FILL);
        } else {
            rule.size(Length.FILL, Length.dp(1));
        }

        this.root = (edge.vertical() ? host.row() : host.column()).scroll(false, false);
        if (edge.vertical()) {
            root.width(Length.AUTO).height(Length.FILL);
        } else {
            root.width(Length.FILL).height(Length.AUTO);
        }
        if (edge.leading()) {
            root.children(panel, rule);
        } else {
            root.children(rule, panel);
        }

        this.away = new Gui(host.bus());

        // The one thing a container that conceals has to declare. Popped out, the docked copy is hidden and the
        // live one is in another tree that this landmark is not in — so the reveal brings the panel home, which
        // is the only way the node being navigated to becomes something the user can actually look at.
        host.reveals(body, descendant -> {
            if (popped) {
                dock();
                return true;
            }
            if (collapsed) {
                collapse(false);
                return true;
            }
            return false;
        });

        applyState();
    }

    /** The node to place in the host's layout, on the side {@link Edge} names. Includes the panel's own rule. */
    public Node node() {
        return root;
    }

    /**
     * The container the docked content was built into — for an application that wants to address it, name a
     * landmark under it, or read its layout. The popped-out copy is a different node in a different tree; there
     * is deliberately no accessor that hands back "the" body, because while the panel is popped out there are
     * two and they are not interchangeable.
     */
    public Node body() {
        return body;
    }

    /** The header strip, for adding controls of the panel's own beside the title. */
    public Node header() {
        return header;
    }

    /**
     * How far the panel extends across its edge's axis when it is open — width for a side panel, height for one
     * against the top or bottom. Takes effect immediately, and is also the popped-out window's opening size.
     */
    public Popout size(Length size) {
        this.size = size == null ? DEFAULT_SIZE : size;
        applyState();
        return this;
    }

    /**
     * Change the title: in the docked header, on the popped-out window's own title bar, and on the OS window
     * the next time it opens. All three, because a panel renamed while it is out would otherwise be one name in
     * the host and another in the window that came out of it.
     */
    public Popout title(String title) {
        this.title = title == null ? "" : title;
        headerTitle.text(this.title);
        if (awayBar != null) {
            awayBar.title(this.title);
        }
        return this;
    }

    /**
     * Give this panel a window to pop into, known by {@code key} — after which the pop-out control appears in
     * the header. The window is registered with the application the ordinary way, so it answers to that name
     * from a shortcut, a menu or an {@link dev.vexelray.gui.core.nav.Address} like any other named window, and
     * asking for it twice raises the one that exists.
     *
     * <p>Called once. A second call with a different name would leave the panel able to be in two windows, which
     * is not a state this component has, so it is refused rather than quietly rebound.
     */
    public Popout hostedBy(GuiApp app, String key) {
        if (app == null) {
            throw new IllegalArgumentException("app must not be null");
        }
        if (window != null) {
            throw new IllegalStateException("popout '" + title + "' already has a window");
        }
        this.window = app.window(key, this::spec);
        popButton.visible(true);
        return this;
    }

    /**
     * Where the popped-out window stands relative to the window it came out of — {@link Standing#SATELLITE} by
     * default: a panel that popped out of a document still belongs to it, so it stays above it, keeps out of the
     * taskbar, and goes away with it. {@link Standing#PEER} for a panel meant to become a window in its own
     * right. Set before the first pop; the standing of a window is fixed when it is created.
     */
    public Popout standing(Standing standing) {
        this.standing = standing == null ? Standing.SATELLITE : standing;
        return this;
    }

    /**
     * The window the popped-out one belongs to, when the panel is docked in something other than the
     * application's main window — a panel in a second document window pops out above <em>that</em> window, not
     * above whichever window happens to be hosting the application.
     */
    public Popout belongingTo(AppWindow anchor) {
        this.anchor = anchor;
        return this;
    }

    /** Whether the panel is currently in a window of its own. */
    public boolean popped() {
        return popped;
    }

    /** Whether the docked panel is collapsed to its header rail. */
    public boolean collapsed() {
        return collapsed;
    }

    /**
     * Pop the panel out into its own window, building its content there the first time. Does nothing if it is
     * already out, or if no window has been named ({@link #hostedBy}). Safe from any thread — the command is an
     * ordinary {@link AppWindow#show()}, and the state follows the window rather than leading it.
     */
    public Popout pop() {
        AppWindow w = window;
        if (w != null) {
            w.show();
        }
        return this;
    }

    /**
     * Bring the panel back into the host window: the window closes, and the docked body is shown again. The
     * popped-out tree survives, with everything in it, for the next pop — closing a window here is closing a
     * window, not discarding the panel.
     */
    public Popout dock() {
        AppWindow w = window;
        if (w != null) {
            w.close();
        }
        return this;
    }

    /** Out if it is in, in if it is out — what the header's pop control does. */
    public Popout toggle() {
        return popped ? dock() : pop();
    }

    /**
     * Collapse the docked panel to its header rail, or open it again. Independent of popping: a panel collapsed
     * while it is out is collapsed when it comes back, which is what makes the rail's control mean one thing.
     */
    public Popout collapse(boolean collapsed) {
        this.collapsed = collapsed;
        applyState();
        return this;
    }

    /** Collapsed if it is open, open if it is collapsed. */
    public Popout toggleCollapsed() {
        return collapse(!collapsed);
    }

    /**
     * The window this panel pops into, built once when its name is first claimed. The content is built into the
     * away tree here rather than in the constructor, so a panel that is never popped never pays for it.
     */
    private WindowSpec spec() {
        buildAway();
        WindowConfig config = WindowConfig.of(title, 480, 360).decorations(Decorations.CLIENT);
        // The bar exists before the window does and must stop commanding it when it is gone; commands() is that
        // pair of bindings, composed onto the spec rather than written out at every window that has chrome.
        WindowSpec built = awayBar.commands(WindowSpec.of(config, away))
                .standing(standing)
                // Both edges of the pop, heard from the window rather than from the button that asked for it.
                // Every route out is a window closing — the dock control, the window's own close box, Alt+F4,
                // the owner being destroyed — and a docked rail that says "popped out" because the last thing
                // the user did was not go through this class is exactly the lie this avoids.
                .onCreated(w -> {
                    popped = true;
                    applyState();
                })
                .onClosed(() -> {
                    popped = false;
                    applyState();
                });
        AppWindow a = anchor;
        return a == null ? built : built.belongingTo(a);
    }

    /**
     * Build the away tree, once: the window's own chrome, a dock control, and the content.
     *
     * <p><b>Why it wears a {@link TitleBar} rather than the system caption.</b> A panel that popped out of the
     * application is still the application — a second window is not a second opinion about what the application
     * looks like, and the alternative is a floating panel that carries the desktop's caption while every other
     * window of the same program carries its own. The window is created with {@link Decorations#CLIENT}, which
     * hands the frame to the GUI and keeps everything the window manager was already doing with it: the strip is
     * {@link WindowRegion#DRAG}, so dragging still moves and snaps the window, double-click still maximizes, and
     * right-click still opens the system menu.
     *
     * <p>The dock control sits in the bar's leading slot, and declares {@link WindowRegion#INTERACTIVE} because
     * anything clickable on a caption must: without it the window manager starts a window drag from the button
     * and the click never lands.
     *
     * <p>Built here rather than in the constructor for two reasons that point the same way. A panel never popped
     * costs no nodes; and the theme this reads is the one in force when the panel is first popped, not the one
     * that happened to be installed while the application was still assembling itself.
     */
    void buildAway() {
        if (awayBody != null) {
            return;   // idempotent: the spec is built once per name, but nothing here depends on that
        }
        away.theme(host.theme());
        Theme theme = away.theme();
        this.awayBar = new TitleBar(away, WindowControls.NONE, title);
        awayBar.addLeading(button(away, away.text("«")   // «
                        .textSize(Length.rem(1.0f))
                        .textColor(theme.color(Role.INK))
                        .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE),
                Role.RAISED, WindowRegion.INTERACTIVE, this::dock));
        this.awayBody = away.column()
                .width(Length.FILL)
                .height(Length.FILL)
                .background(theme.color(Role.PANEL))
                .scroll(false, false);
        away.root().direction(Direction.COLUMN)
                .background(theme.color(Role.PAGE))
                .children(awayBar.node(), awayBody);
        content.build(away, awayBody);
    }

    /**
     * The popped-out tree and what is in it — for the test, which has no {@code GuiApp} to open a window with
     * and so must drive the away tree directly. Package-private rather than public on purpose: what the away
     * tree contains is this class's business, and an application reaching into it is describing a component
     * this one is not.
     */
    Gui awayGui() {
        return away;
    }

    Node awayBody() {
        return awayBody;
    }

    TitleBar awayBar() {
        return awayBar;
    }

    /**
     * Put the docked panel into the shape its state says it should have. One place, derived from
     * {@code popped} and {@code collapsed} together, because the two produce the same footprint — the body gone
     * and the rail left — and computing that at each of the four call sites is how they come to disagree.
     */
    private void applyState() {
        boolean railed = popped || collapsed;
        body.visible(!railed);
        collapseIcon.text(collapsed ? "+" : "−");
        if (edge.vertical()) {
            // A side rail is as narrow as its controls, so the header turns to a column and the title goes: the
            // atlas has no rotated text, and a title laid on its side is not something this can draw honestly.
            header.direction(railed ? Direction.COLUMN : Direction.ROW)
                    .width(railed ? Length.AUTO : Length.FILL)
                    .height(railed ? Length.FILL : HEADER_H)
                    .justify(railed ? Justify.START : Justify.SPACE_BETWEEN);
            headerTitle.visible(!railed);
            panel.width(railed ? Length.AUTO : size).height(Length.FILL);
        } else {
            panel.width(Length.FILL).height(railed ? Length.AUTO : size);
        }
    }

    /**
     * One control: a square, hover-shaded, clickable box around {@code icon}, built in {@code gui} — which is
     * the host's tree for the header's controls and the away tree for the one on its caption. The role is what
     * it paints on hover; the {@link WindowRegion} is {@code null} — nothing declared — except on a caption,
     * where a control that does not punch its own hole is a control the window manager drags the window from.
     */
    private static Node button(Gui gui, Node icon, Role hover, WindowRegion region, Runnable action) {
        Node b = gui.box()
                .size(BUTTON, BUTTON)
                .justify(Justify.CENTER)
                .alignItems(AlignItems.CENTER)
                .background(gui.theme().color(Role.NONE))
                .scroll(false, false)
                .windowRegion(region)
                .children(icon);
        gui.onClick(b, action);
        gui.onState(b, state -> b.background(
                gui.theme().color(state == InteractionState.NORMAL ? Role.NONE : hover)));
        return b;
    }
}
