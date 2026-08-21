package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.WindowRegion;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.text.TextLayout;

/**
 * The application's own window title bar: a draggable strip carrying a title and the minimize, maximize and
 * close buttons, for a window created with client decorations.
 *
 * <p><b>Almost none of this is special.</b> The strip is a row, the buttons are clickable boxes with hover
 * shading, the icons are rectangles and one glyph — ordinary framework machinery, laid out and hit-tested like
 * any other widget. Two declarations are all that separate it from a toolbar: the strip is
 * {@link WindowRegion#DRAG}, so the window manager moves the window when it is dragged (and maximizes on
 * double-click, and opens the system menu on right-click, and offers snap when it is dragged to an edge), and
 * each button is {@link WindowRegion#INTERACTIVE}, punching a hole in that caption so the click reaches the
 * button instead. The gaps <em>between</em> the buttons stay draggable, which is what a title bar should do.
 *
 * <p>The maximize button additionally declares {@link WindowRegion#MAXIMIZE_BUTTON}, which is what earns the
 * platform's own window-arrangement affordance on hover (Windows 11's Snap Layouts flyout). The click itself is
 * still this widget's: declaring the region tells the window manager what the button <em>is</em>, not what it
 * does.
 *
 * <p><b>The maximize icon follows the window, not the button.</b> A window can be maximized without this widget
 * hearing about it — a double-click on the caption, Win+Up, a drag to the top edge — so the icon is re-derived
 * from {@link WindowControls#maximized()} whenever the viewport changes, which is precisely when a maximize,
 * restore or snap has happened. Setting it in the click handler would be right until the first time the user
 * used the OS's own gesture, and then permanently wrong.
 */
public final class TitleBar {

    private static final Color CHROME = Color.rgb(0x121722);
    private static final Color HOVER = Color.rgb(0x232a3d);
    private static final Color CLOSE_HOVER = Color.rgb(0xc4353b);
    private static final Color INK = Color.rgb(0xeef2f8);
    private static final Color DIM = Color.rgb(0x93a0b4);
    private static final Color NONE = Color.rgba(0f, 0f, 0f, 0f);

    /** Caption button footprint — the Windows caption-button metrics, in density-independent pixels. */
    private static final Length BUTTON_W = Length.dp(46);
    private static final Length BAR_H = Length.dp(32);

    private final Gui gui;
    /**
     * Rebindable, because the window a title bar commands need not exist when the tree showing it is built — an
     * application assembles its UI, creates the window, and only then can bind the two. Until it does, the bar is
     * a working bar against {@link WindowControls#NONE}, which is also how it renders headless.
     */
    private volatile WindowControls controls;
    private final Node root;
    private final Node leading;
    private final Node titleText;
    private final Node maximizeIcon;
    private final Node restoreIcon;

    /** Build a title bar showing {@code title}, driving {@code controls}. */
    public TitleBar(Gui gui, WindowControls controls, String title) {
        this.gui = gui;
        this.controls = controls == null ? WindowControls.NONE : controls;

        this.titleText = gui.text(title == null ? "" : title)
                .textSize(Length.rem(0.85f))
                .textColor(DIM)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
        this.leading = gui.row()
                .height(Length.FILL)
                .alignItems(AlignItems.CENTER)
                .gap(Length.dp(8))
                .padding(Length.ZERO, Length.dp(12))
                .scroll(false, false)
                .children(titleText);

        // The maximize glyph is two overlapping outlines, of which the back one is shown only when the window is
        // maximized — the ordinary restore icon, built from boxes because the atlas font carries no square glyph.
        Node maxBox = gui.box().size(Length.dp(10), Length.dp(10)).border(Length.dp(1), INK);
        this.maximizeIcon = gui.box().size(Length.dp(14), Length.dp(14)).scroll(false, false)
                .children(maxBox.floatAt(Length.dp(2), Length.dp(2)));
        Node restoreBack = gui.box().size(Length.dp(9), Length.dp(9)).border(Length.dp(1), INK);
        Node restoreFront = gui.box().size(Length.dp(9), Length.dp(9)).border(Length.dp(1), INK);
        this.restoreIcon = gui.box().size(Length.dp(14), Length.dp(14)).scroll(false, false)
                .children(restoreBack.floatAt(Length.dp(4), Length.dp(1)),
                        restoreFront.floatAt(Length.dp(1), Length.dp(4)))
                .visible(false);

        // The handlers read the field, not the constructor argument, so they resolve the controls at click
        // time and rebinding later rewires every button. Closing over the argument would capture what the
        // window did not exist yet to be -- WindowControls.NONE, for every application, permanently.
        Node minimize = button(gui.box().size(Length.dp(10), Length.dp(1)).background(INK),
                WindowRegion.INTERACTIVE, HOVER, () -> this.controls.minimize());
        Node maximize = button(gui.box().size(Length.dp(14), Length.dp(14)).scroll(false, false)
                        .children(maximizeIcon, restoreIcon),
                WindowRegion.MAXIMIZE_BUTTON, HOVER, () -> this.controls.toggleMaximize());
        Node close = button(gui.text("×").textSize(Length.rem(1.4f)).textColor(INK)
                        .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE),
                WindowRegion.INTERACTIVE, CLOSE_HOVER, () -> this.controls.close());

        Node buttons = gui.row().height(Length.FILL).alignItems(AlignItems.STRETCH).scroll(false, false)
                .children(minimize, maximize, close);

        this.root = gui.row()
                .width(Length.FILL)
                .height(BAR_H)
                .background(CHROME)
                .alignItems(AlignItems.CENTER)
                .justify(Justify.SPACE_BETWEEN)
                .scroll(false, false)
                // Everything the buttons do not claim is caption: drag to move, double-click to maximize,
                // right-click for the system menu — all of it the window manager's, none of it re-implemented.
                .windowRegion(WindowRegion.DRAG)
                .children(leading, buttons);

        // The window can be maximized or restored without this widget being the cause, and every one of those
        // routes changes the viewport. Re-deriving from the window on that signal is what keeps the icon honest.
        gui.viewport().onCommit(v -> syncMaximized());
        syncMaximized();
    }

    /** The node to place in a layout — put it at the top of the root column. */
    public Node node() {
        return root;
    }

    /**
     * Bind (or rebind) the window this bar commands — call it once the OS window exists. Every button resolves
     * the controls at click time, so a bar built before its window is not a bar wired to nothing.
     */
    public TitleBar controls(WindowControls controls) {
        this.controls = controls == null ? WindowControls.NONE : controls;
        syncMaximized();
        return this;
    }

    /** Change the title shown. */
    public TitleBar title(String title) {
        titleText.text(title == null ? "" : title);
        return this;
    }

    /**
     * Add a node to the left-hand slot, after the title — an application icon, a menu bar, a tab strip. Anything
     * clickable placed here must declare {@link WindowRegion#INTERACTIVE} itself (directly, or on each of its own
     * clickable parts), or the window manager will start a window drag from it instead of letting the click land.
     */
    public TitleBar addLeading(Node node) {
        leading.append(node);
        return this;
    }

    /** Point the maximize button's icon at what the window actually is right now. */
    private void syncMaximized() {
        boolean max = controls.maximized();
        maximizeIcon.visible(!max);
        restoreIcon.visible(max);
    }

    /**
     * One caption button: a fixed-width, full-height box wrapping {@code icon}, hover-shaded, clickable, and
     * declared to the window manager so the caption it sits on does not swallow the click.
     */
    private Node button(Node icon, WindowRegion region, Color hover, Runnable action) {
        Node b = gui.box()
                .width(BUTTON_W)
                .height(Length.FILL)
                .justify(Justify.CENTER)
                .alignItems(AlignItems.CENTER)
                .background(NONE)
                .scroll(false, false)
                .windowRegion(region)
                .children(icon);
        gui.onClick(b, action);
        gui.onState(b, state -> b.background(state == InteractionState.NORMAL ? NONE : hover));
        return b;
    }
}
