package dev.vexelray.gui.widget;

import dev.vexelray.gui.core.spi.Seams;
import sibarum.atchung.Sender;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.CaptionGrid;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.IconSource;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.WindowInstrument;
import dev.vexelray.gui.core.WindowRegion;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.Menu;
import dev.vexelray.gui.core.input.MenuItem;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Paint;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.draw.Picture;
import dev.vexelray.gui.icons.IconView;
import dev.vexelray.os.Icon;
import dev.vexelray.text.TextLayout;

/**
 * The application's own window title bar: a draggable strip carrying a title and the minimize, maximize and
 * close buttons, for a window created with client decorations.
 *
 * <p><b>Almost none of this is special.</b> The strip is a row, the buttons are clickable boxes that paint a
 * rounded plate under the pointer, every glyph is a picture on one shared grid ({@link CaptionGrid}) — ordinary
 * framework machinery, laid out and hit-tested like
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

    static {
        Hosting.install();
    }

    /** Caption button footprint — the Windows caption-button metrics, in density-independent pixels. */
    private static final Length BUTTON_W = Length.dp(46);
    private static final Length BAR_H = Length.dp(32);
    /**
     * The bar's glyphs are chrome too, so they are {@code dp} like the strip they sit in — the native caption does
     * not grow with an application's zoom either. In {@code rem} they zoomed and the bar did not, so a few steps of
     * Ctrl+= pushed the title and the close glyph out of a 32dp strip. (Sizes are today's 0.85rem and 1.4rem at
     * the 16px root, so nothing moves at 100%.)
     */
    private static final Length TITLE_SIZE = Length.dp(13.6f);
    /** An application's own window-control glyph ({@link #glyphs}), drawn at this size. */
    private static final Length GLYPH = Length.dp(16);
    /**
     * The window controls' own drawings, on the caption grid with the instruments' marks ({@link CaptionGrid}): what
     * the bar shows until {@link #glyphs} says otherwise, and what a {@code null} there puts back. A 10-unit square,
     * the native caption's glyph size, in the strip's one stroke.
     */
    private static final Glyph MINIMIZE = (g, ink, under) -> Picture.of(g.hline(-5, 5, 0, ink));
    private static final Glyph MAXIMIZE = (g, ink, under) -> Picture.of(g.outline(-5, -5, 10, 10, 1.5, ink));
    /** The window behind, then the face of the one in front painted over it in what is under the glyph. */
    private static final Glyph RESTORE = (g, ink, under) -> Picture.of(
            g.outline(-3, -5, 8, 8, 1.5, ink),
            g.fill(-5, -3, 8, 8, 1.5, under),
            g.outline(-5, -3, 8, 8, 1.5, ink));
    private static final Glyph CLOSE = (g, ink, under) -> Picture.of(
            g.line(-4.75, -4.75, 4.75, 4.75, ink),
            g.line(-4.75, 4.75, 4.75, -4.75, ink));
    /** The mark's side: the native caption's small icon, 16px at 100%. */
    private static final int ICON_DP = 16;
    /** The landmark the mark is reached by, so a script can {@code click titlebar-mark}. */
    public static final String MARK = "titlebar-mark";

    private final Gui gui;
    /**
     * Rebindable, because the window a title bar commands need not exist when the tree showing it is built — an
     * application assembles its UI, creates the window, and only then can bind the two. Until it does, the bar is
     * a working bar against {@link WindowControls#NONE}, which is also how it renders headless.
     */
    private volatile WindowControls controls;
    private final Node root;
    /** The flexing dead space between identity and the tools: caption, and nothing but caption. */
    private final Node caption;
    /** The framework's own tools, between the caption and the window controls. Empty until asked for. */
    private final Node instruments;
    /** The instrument buttons currently mounted, so a later call can take them down again. */
    private final java.util.List<Node> mounted = new java.util.ArrayList<>();
    /** What stops each live instrument's watch ({@link WindowInstrument#live()}), run when the strip is replaced. */
    private final java.util.List<Runnable> unwatch = new java.util.ArrayList<>();
    /** The tools those buttons are, in order: what {@link #instruments()} answers. */
    private volatile java.util.List<WindowInstrument> tools = java.util.List.of();
    /** Built on first use: a bar with no instruments needs no tooltip machinery. */
    private Tooltip instrumentTips;
    private final Node leading;
    /**
     * The window's lockup, first in {@link #leading}: its mark and its wordmark, one target, opening the app menu. A
     * plate behind them lifts under the pointer as a caption button's does, and the wordmark takes the accent.
     */
    private final Node lockup;
    /** The application's mark, first in {@link #lockup}; null until given one. */
    private Node icon;
    /** The application's name as a logo, after the mark in {@link #lockup}; null until given one. */
    private volatile Wordmark wordmark;
    /** What the pointer is doing to the lockup, so a wordmark given later is inked for it. */
    private volatile InteractionState lockupState = InteractionState.NORMAL;
    /** The About line at the top of the app menu ({@link #onMark}), read at click time; null for none. */
    private volatile Runnable markAction;
    /** What the About line says. */
    private volatile String markTip;
    private final Node titleText;
    /** The hairline between the instruments and the window controls; shown while there are instruments. */
    private final Node divider;
    /** The window controls, each painting its own plate and glyph. */
    private final Caption minimize;
    private final Caption maximize;
    private final Caption close;
    /**
     * An application's own glyphs ({@link #glyphs}), shown in place of the drawn ones; hidden while the drawn one is
     * in use. Kept as views so they can be redrawn in place.
     */
    private final IconView minimizeGlyph;
    private final IconView maximizeGlyph;
    private final IconView restoreGlyph;
    private final IconView closeGlyph;
    private volatile boolean minimizeOwn;
    private volatile boolean maximizeOwn;
    private volatile boolean restoreOwn;
    private volatile boolean closeOwn;

    /** Build a title bar showing {@code title}, driving {@code controls}. */
    public TitleBar(Gui gui, WindowControls controls, String title) {
        this.gui = gui;
        this.controls = controls == null ? WindowControls.NONE : controls;

        this.titleText = gui.text(title == null ? "" : title)
                .textSize(TITLE_SIZE)
                .textColor(Role.DIM)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE)
                // The title is the first thing to give way when the window is narrow: clipped, it can be narrowed,
                // and narrowed it ends in an ellipsis rather than half a glyph.
                .wordWrap(false)
                .ellipsis(dev.vexelray.gui.core.text.Ellipsis.END)
                .clip(true);
        // Clipped too, so the whole identity zone gives way — mark and menus after the title — and the window
        // controls never do. Unclipped, a long title or a menu bar had no smaller size than its content, and a
        // narrow window laid the close button out past its own right edge.
        // The lockup is one target however many nodes it wears: what is put in it takes no pointer of its own, so
        // the plate lifts, the menu opens and the landmark answers wherever on it the pointer is. It is caption until
        // its menu has something on it (lockupRegion), and hidden until it has something to show.
        this.lockup = gui.row()
                .role("titlebar-lockup")
                .height(Length.dp(24))
                .alignItems(AlignItems.CENTER)
                .gap(Length.dp(7))
                .padding(Length.ZERO, Length.dp(8))
                .corner(Length.dp(5))
                .background(Role.NONE)
                .scroll(false, false)
                .visible(false);
        Seams.of(gui).onClick(lockup, (dev.vexelray.gui.core.input.ClickEvent e) -> appMenu(e));
        Seams.of(gui).onState(lockup, s -> {
            lockupState = s;
            paintLockup();
        });
        gui.landmark(MARK, lockup);
        this.leading = gui.row()
                .height(Length.FILL)
                .alignItems(AlignItems.CENTER)
                .gap(Length.dp(6))
                .padding(Length.ZERO, Length.dp(4))
                .scroll(false, false)
                .clip(true)
                .children(lockup, titleText);

        // An application's own glyphs, if it gives any (glyphs()); until then the buttons draw their own and these are
        // hidden. Maximize and restore share a button and swap with the window's state, which syncMaximized()
        // follows; both are built so the swap is a repaint and a visibility change, never a rebuild.
        this.minimizeGlyph = IconView.of(gui, dev.vexelray.gui.icons.Icon.TB_MINIMIZE).size(GLYPH);
        this.maximizeGlyph = IconView.of(gui, dev.vexelray.gui.icons.Icon.TB_MAXIMIZE).size(GLYPH);
        this.restoreGlyph = IconView.of(gui, dev.vexelray.gui.icons.Icon.TB_WINDOWED).size(GLYPH);
        this.closeGlyph = IconView.of(gui, dev.vexelray.gui.icons.Icon.TB_EXIT).size(GLYPH);
        minimizeGlyph.node().visible(false);
        maximizeGlyph.node().visible(false);
        restoreGlyph.node().visible(false);
        closeGlyph.node().visible(false);

        // The handlers read the field, not the constructor argument, so they resolve the controls at click
        // time and rebinding later rewires every button. Closing over the argument would capture what the
        // window did not exist yet to be -- WindowControls.NONE, for every application, permanently.
        this.minimize = new Caption(gui.box(), false,
                (g, ink, under) -> minimizeOwn ? Picture.EMPTY : MINIMIZE.draw(g, ink, under));
        this.maximize = new Caption(gui.box(), false, (g, ink, under) -> {
            boolean max = this.controls.maximized();
            return (max ? restoreOwn : maximizeOwn) ? Picture.EMPTY : (max ? RESTORE : MAXIMIZE).draw(g, ink, under);
        });
        this.close = new Caption(gui.box(), true,
                (g, ink, under) -> closeOwn ? Picture.EMPTY : CLOSE.draw(g, ink, under));
        Node minimizeButton = button(minimize, minimizeGlyph.node(),
                WindowRegion.INTERACTIVE, () -> this.controls.minimize());
        Node maximizeButton = button(maximize, gui.box().size(GLYPH, GLYPH).scroll(false, false)
                        .children(maximizeGlyph.node().floatAt(Length.ZERO, Length.ZERO),
                                restoreGlyph.node().floatAt(Length.ZERO, Length.ZERO)),
                WindowRegion.MAXIMIZE_BUTTON, () -> this.controls.toggleMaximize());
        Node closeButton = button(close, closeGlyph.node(),
                WindowRegion.INTERACTIVE, () -> this.controls.close());

        Node buttons = gui.row().height(Length.FILL).alignItems(AlignItems.STRETCH).scroll(false, false)
                .children(minimizeButton, maximizeButton, closeButton);
        // Instruments are tools and the controls are the window's: a hairline says they are two groups without a
        // gap wide enough to read as caption. A bar with no instruments has one group, and no hairline.
        this.divider = gui.box().width(Length.dp(1)).height(Length.dp(14)).margin(Length.dp(6))
                .background(Role.LINE).scroll(false, false).visible(false);

        // Four zones, left to right: identity, caption, instruments, window controls (docs/reference/automation.md §7).
        // The caption is a spacer that takes the slack rather than SPACE_BETWEEN spreading three groups, because
        // instruments have to sit *against* the caption buttons and not float in the middle of the bar. It stays
        // ordinary dead space, so it is still all draggable.
        this.caption = gui.box().width(Length.FILL).height(Length.FILL).scroll(false, false);
        // The bar's own height, not FILL. `buttons` can say FILL because it is given its children here and so has
        // a content height to resolve against; this strip is filled later, and a row that is empty when the first
        // layout runs resolves FILL to nothing and stays a zero-height strip that swallows its buttons.
        this.instruments = gui.row().height(BAR_H)
                .alignItems(AlignItems.STRETCH).scroll(false, false);

        this.root = gui.row()
                .role("titlebar")
                .width(Length.FILL)
                .height(BAR_H)
                .background(Role.CHROME)
                .alignItems(AlignItems.CENTER)
                .scroll(false, false)
                // Everything the buttons do not claim is caption: drag to move, double-click to maximize,
                // right-click for the system menu — all of it the window manager's, none of it re-implemented.
                .windowRegion(WindowRegion.DRAG)
                // Docked at the top of its parent's frame, not placed in its flow: the root's frame is the
                // window, while its flow may be a larger canvas (Gui.minSize) that scrolls. In the flow, a zoom
                // past the minimum laid the bar out on that canvas and the window controls off the window.
                .floatAt(Length.ZERO, Length.ZERO)
                .dock(true)
                .children(leading, caption, instruments, divider, buttons);

        // The window can be maximized or restored without this widget being the cause, and every one of those
        // routes changes the viewport. Re-deriving from the window on that signal is what keeps the icon honest.
        Seams.of(gui).viewport().onCommit(v -> syncMaximized());
        syncMaximized();
    }

    /**
     * The node to place in a layout — a child of the root. It docks at the top of the root's frame, so its place
     * among the root's children decides only what draws over it, and the root's flow starts below it.
     */
    public Node node() {
        return root;
    }

    /**
     * Put the framework's own tools in this bar — a screenshot, later a macro recorder
     * ({@link WindowInstrument#standard()}, or any list an application assembles).
     *
     * <p><b>Opt-in, and replacing rather than appending.</b> A bar has none until asked, so nothing ships a
     * tool it never wanted; calling this again replaces them, so "which tools does this window have" has
     * one answer rather than a history.
     *
     * <p><b>Each is a section of the app menu</b>, the one the lockup opens ({@link WindowInstrument#section}), in this
     * order, after the About line. One with a {@link WindowInstrument#badge() badge} also stands at the bar's right
     * while its badge says so: a caption-sized button between the draggable caption and the window controls, declaring
     * {@link WindowRegion#INTERACTIVE} so its click reaches the button rather than the window manager — the gaps
     * around it stay caption, as they do for minimize and close. Its mark is re-authored whenever the button's box
     * changes, because a {@link dev.vexelray.gui.draw.Picture} is in pixels and a window can be zoomed.
     */
    public TitleBar instruments(java.util.List<WindowInstrument> tools) {
        // Replacing, not appending: the bar is a statement about this window, not a log of what was asked for.
        for (Node old : mounted) {
            old.remove();
        }
        mounted.clear();
        java.util.Map<Node, WindowInstrument> standing = new java.util.LinkedHashMap<>();
        unwatch.forEach(Runnable::run);
        unwatch.clear();
        this.tools = tools == null ? java.util.List.of() : java.util.List.copyOf(tools);
        lockupRegion();
        for (WindowInstrument tool : this.tools) {
            if (tool.badge() == null) {
                continue;   // a line in the app menu, and nothing more
            }
            if (instrumentTips == null) {
                instrumentTips = new Tooltip(gui);
            }
            Node b = instrument(tool);
            mounted.add(b);
            standing.put(b, tool);
            instruments.append(b);
        }
        badges = java.util.Map.copyOf(standing);
        showBadges();
        return this;
    }

    /** The instruments that stand at the right while their badge says so, by their buttons. */
    private volatile java.util.Map<Node, WindowInstrument> badges = java.util.Map.of();

    /**
     * Stand each badge's button at the right while its badge says so, and the hairline before the window controls
     * while any does. Any thread: a node's setters are thread-safe, and a live instrument calls this from its own.
     */
    private void showBadges() {
        boolean any = false;
        for (java.util.Map.Entry<Node, WindowInstrument> b : badges.entrySet()) {
            boolean shown = b.getValue().badged();
            b.getKey().visible(shown);
            any |= shown;
        }
        divider.visible(any);
    }

    /**
     * The tools this bar shows now, in order — so a contributor that arrives later (the key bindings, the automation
     * switches) states the whole strip again with itself in it, rather than replacing what the framework put there.
     */
    public java.util.List<WindowInstrument> instruments() {
        return tools;
    }

    /** One instrument as a caption-sized button: its mark, its tooltip, and its command. */
    private Node instrument(WindowInstrument tool) {
        Node b = gui.box()
                .role(tool.role())
                .width(BUTTON_W)
                .height(Length.FILL)
                .background(Role.NONE)
                .scroll(false, false)
                .windowRegion(WindowRegion.INTERACTIVE);
        // Authored against the box the layout settled on, and re-authored when that box changes or the pointer
        // changes what the button is doing. A picture resolves no units of its own, so this is the only way a mark
        // survives a zoom. The ink is the bar's, never the instrument's (WindowInstrument's mark).
        Caption caption = new Caption(b, false, (g, ink, under) -> tool.mark().apply(g.box(), ink));
        // The instrument asks the window, not the application: a bar in a popup photographs the popup. Resolved
        // at click time like every other button here, so a bar built before its window still works. One with a
        // menu opens it at the click instead, built then, against what is true then.
        if (tool.menu() != null) {
            // Told which window it is in, as an action is: a recording started from a popup records the popup.
            Seams.of(gui).onClick(b, (dev.vexelray.gui.core.input.ClickEvent e) ->
                    Seams.of(gui).openMenu(e, sink -> tool.menu().accept(this.controls, sink)));
        } else {
            Seams.of(gui).onClick(b, () -> tool.action().accept(this.controls));
        }
        // Its role as a landmark, so a script reaches it by the name the tree shows (click instrument-automation).
        if (!tool.role().isEmpty()) {
            gui.landmark(tool.role(), b);
        }
        WindowInstrument.Live live = tool.live();
        if (live != null) {
            // A live one is re-authored against the last box when what it shows changes, too.
            unwatch.add(live.watch(() -> {
                caption.paint();
                showBadges();
            }));
            instrumentTips.attach(b, () -> {
                String now = live.tooltip();
                return now != null ? now : tool.tooltip();
            });
        } else if (!tool.tooltip().isEmpty()) {
            instrumentTips.attach(b, tool.tooltip());
        }
        return b;
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

    /**
     * Have this bar command whatever window {@code spec} opens, for as long as it is open — the binding above,
     * performed by the framework rather than by hand.
     *
     * <p>The two lines this replaces were written identically at every window of every application, because a
     * bar is necessarily built before the window it belongs to: the tree outlives the window, so the buttons
     * command {@link WindowControls#NONE} until something points them at the real one, and must go back to
     * commanding nothing when it closes or they are left holding a destroyed handle. Forgetting either is
     * silent — a caption that draws perfectly and does nothing — and every application that got it right got it
     * right in the same way.
     *
     * <p>It composes, so it can be applied to a spec that already has lifecycle callbacks of its own, in any
     * order:
     * {@snippet :
     * app.window("plot", () -> titleBar.commands(WindowSpec.of(config, gui)
     *         .onCreated(this::restorePlacement)));
     * }
     *
     * <p>Not for the <b>main</b> window: that one exists before its tree is handed over, so its bar takes
     * {@code GuiApp.controls()} directly and there is no moment to wait for.
     */
    public WindowSpec commands(WindowSpec spec) {
        // Taken from the host rather than minted from the native window. A NativeWindow cannot photograph
        // itself — the pixels are in the GUI's per-window render bundle, which only GuiApp owns — so a bar that
        // built its own controls got a working minimize, maximize and close and a screenshot that quietly did
        // nothing. That is the failure mode this whole strip exists to avoid, and it was in the strip.
        return spec.onControls(this::controls)
                   .onClosed(() -> controls(WindowControls.NONE));
    }

    /** Change the title shown. */
    public TitleBar title(String title) {
        titleText.text(title == null ? "" : title);
        return this;
    }

    /**
     * Put the application's mark at the far left, first in the lockup, where the native caption puts a window's
     * icon. {@link #addLeading} cannot: it appends, so a mark added there lands <em>after</em> the title.
     *
     * <p>Replacing rather than appending, like {@link #instruments}: a window wears one mark, and {@code null}
     * takes it off. The node is the caller's to build — a drawn {@link dev.vexelray.gui.draw.Picture}, an image,
     * anything — and takes no pointer of its own: the lockup it stands in is the target, and opens the app menu.
     * {@link #icon(GuiApp, Icon)} builds it from the {@link Icon} the window itself wears.
     */
    public TitleBar icon(Node node) {
        if (icon != null) {
            icon.remove();
        }
        icon = node;
        if (node != null) {
            lockup.insert(node.hitInert(true), 0);
        }
        lockupShown();
        return this;
    }

    /**
     * The application's name as a logo, after the mark in the lockup: a typeface the application owns, set as it
     * would set it anywhere. The framework gives every bar the name in the theme's type ({@link Wordmark#of}) unless
     * the application gave its own first. Replacing, like {@link #icon(Node)}; {@code null} takes it off.
     */
    public TitleBar wordmark(Wordmark mark) {
        Wordmark old = wordmark;
        if (old != null) {
            old.node().remove();
        }
        wordmark = mark;
        if (mark != null) {
            lockup.insert(mark.node().hitInert(true), icon == null ? 0 : 1);
            paintLockup();
        }
        lockupShown();
        return this;
    }

    /** {@code name} as the wordmark, in the bar's type ({@link Wordmark#of}). */
    public TitleBar wordmark(String name) {
        return wordmark(Wordmark.of(gui, name));
    }

    /** The wordmark the lockup wears, or null for none yet. */
    public Wordmark wordmark() {
        return wordmark;
    }

    /**
     * An application's name drawn as its logo. The node is the application's; the ink is the bar's, handed over as the
     * pointer comes and goes, so the logo lifts into the accent with the plate behind it as a caption glyph does.
     *
     * <p>The ink is a {@link Paint}, not a colour: the theme decides what it looks like when the frame is drawn, so a
     * theme swapped later reaches the logo as it reaches everything else.
     */
    public interface Wordmark {

        /** The logo, as a node of its own size. */
        Node node();

        /**
         * Draw the logo in {@code ink} from now on: {@link Role#INK} at rest, {@link Role#ACCENT} in the pointer's state
         * under it. A text node holds it as it is ({@code textColor(ink)}); a logo that must bake a colour asks
         * {@code ink.in(theme)} and is then the application's to bake again when the theme changes.
         */
        void ink(Paint ink);

        /**
         * The lockup is in {@code pointer}'s state, and its app menu is {@code open} or not: called whenever either
         * changes. By default the logo is inked as the bar inks a menu's label: {@link Role#INK} at rest, the accent
         * shaded for the pointer, and the accent while the menu is open, wherever the pointer has gone ({@link #ink}).
         * A logo that wants its own look for each, glowing under the pointer and lit only once open, say, overrides
         * this rather than guessing from the paint.
         */
        default void state(InteractionState pointer, boolean open) {
            ink(lockupInk(pointer, open));
        }

        /** {@code name} in the bar's type, weighted as a name: the wordmark of a bar whose application gave none. */
        static Wordmark of(Gui gui, String name) {
            Node text = gui.text(name == null ? "" : name)
                    .textSize(TITLE_SIZE)
                    .fontWeight(600)
                    .wordWrap(false)
                    .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
            return new Wordmark() {
                @Override
                public Node node() {
                    return text;
                }

                @Override
                public void ink(Paint ink) {
                    text.textColor(ink);
                }
            };
        }
    }

    /**
     * Put an About line at the top of the app menu, saying {@code tooltip} and doing {@code action} — the About window,
     * as the framework uses it. {@code null} takes the line off.
     *
     * <p>Replacing rather than accumulating, like {@link #instruments}, and any time, before the mark or after.
     *
     * <p>The host's form (the framework opening About), reached through {@code WidgetSeams}; an application's is
     * {@link #onMark(String, Sender, Object)}.
     */
    TitleBar onMark(String tooltip, Runnable action) {
        this.markTip = tooltip;
        this.markAction = action;
        lockupRegion();
        return this;
    }

    /**
     * Have the About line send {@code message} through {@code to}, saying {@code tooltip}: the form for an application,
     * as a menu item's is (vexelray-framework's {@code docs/threading.md}, T7.5).
     */
    public <T> TitleBar onMark(String tooltip, Sender<T> to, T message) {
        java.util.Objects.requireNonNull(to, "to");
        java.util.Objects.requireNonNull(message, "message");
        return onMark(tooltip, () -> to.send(message));
    }

    /**
     * The lockup's region for whether its menu has anything on it. While it has, it stops being caption: it declares
     * {@link WindowRegion#INTERACTIVE}, as a caption button does, so the click reaches it rather than starting a drag.
     * That is the trade the native caption makes too, where a click on a window's icon opens its system menu rather
     * than moving it. The rest of the strip still drags.
     */
    private void lockupRegion() {
        lockup.windowRegion(hasAppMenu() ? WindowRegion.INTERACTIVE : null);
    }

    /** Whether the app menu would have a line on it. */
    private boolean hasAppMenu() {
        return markAction != null || !tools.isEmpty();
    }

    /** Show the lockup once it has a mark or a wordmark to show. */
    private void lockupShown() {
        lockup.visible(icon != null || wordmark != null);
    }

    /**
     * The plate and the wordmark's ink for what the pointer is doing: nothing and the theme's ink at rest; the raised
     * surface and the accent under the pointer; deeper while pressed. A colour change inside the lockup's own box,
     * which the window's motion fades, as a caption button's plate is.
     */
    private void paintLockup() {
        boolean menu = hasAppMenu();
        InteractionState s = menu ? lockupState : InteractionState.NORMAL;
        boolean open = menu && lockupOpen;
        lockup.background(plate(s, open));
        Wordmark mark = wordmark;
        if (mark != null) {
            mark.state(s, open);
        }
    }

    /** Whether the app menu the lockup opened is open now: the lockup stays lit until it closes. */
    private volatile boolean lockupOpen;

    /** The plate under a target that opens a menu: none at rest, raised under the pointer and while open, deeper pressed. */
    private static Paint plate(InteractionState s, boolean open) {
        return s == InteractionState.PRESSED ? Role.LINE
                : s == InteractionState.HOVER || open ? Role.RAISED : Role.NONE;
    }

    /** A menu target's ink: {@link Role#INK} at rest, the accent for the pointer, and the accent's hover shade while open. */
    static Paint lockupInk(InteractionState s, boolean open) {
        return s == InteractionState.NORMAL && !open ? Role.INK
                : Paint.of(Role.ACCENT, s == InteractionState.NORMAL ? InteractionState.HOVER : s);
    }

    /** The app menu has opened or closed. */
    private void lockupOpen(boolean open) {
        lockupOpen = open;
        paintLockup();
    }

    /**
     * Open the app menu under the lockup, or close it if it is open: the About line, then each instrument's section,
     * a rule between, built now against what is true now, for the window this bar commands.
     */
    private void appMenu(dev.vexelray.gui.core.input.ClickEvent at) {
        if (!hasAppMenu()) {
            return;
        }
        if (Seams.of(gui).menus() instanceof ContextMenu open && open.isOpen()) {
            open.hide();
            return;
        }
        // Hung from the lockup's lower left rather than from the pointer, as a menu bar's menus are: the same place
        // wherever on the lockup it was clicked. The lockup's rect is in pixels, as a click's place is.
        dev.vexelray.gui.core.layout.NodeLayout l = lockup.layout();
        float dpi = Math.max(0.0001f, Seams.of(gui).dpi().value());
        dev.vexelray.gui.core.input.ClickEvent under = !l.present() ? at
                : new dev.vexelray.gui.core.input.ClickEvent(at.nodeId(), at.button(), l.rect().x(),
                        l.rect().y() + l.rect().h() + 6 * dpi, at.clicks(), at.modifiers());
        WindowControls window = this.controls;
        Seams.of(gui).openMenu(under, sink -> {
            Runnable about = markAction;
            if (about != null) {
                sink.item(dev.vexelray.gui.icons.Icon.INFO, markTip, about);
            }
            for (WindowInstrument tool : tools) {
                sink.separator();
                tool.section(window, sink);
            }
        });
        ContextMenu.whileOpen(gui, this::lockupOpen);
    }

    /**
     * The mark the window wears in the taskbar, worn in this bar too: {@code mark} shown {@value #ICON_DP}dp
     * square, as the native caption shows a window's small icon. {@code null} takes the mark off.
     *
     * <p><b>Each size the icon was drawn at stays that size.</b> An {@link Icon} is a set of sizes, and a
     * 16-pixel mark is not a 256-pixel one reduced, so the bar shows whichever image is nearest the pixels its box
     * has at the window's current scale, and changes image when the window moves to a display of another scale.
     * The sizes it can choose between are uploaded here, once — which is why this takes the {@code GuiApp}, is
     * called on the main thread once the window exists, and leaves the textures to close with the application.
     */
    public TitleBar icon(GuiApp app, Icon mark) {
        if (mark == null) {
            return icon((Node) null);
        }
        // Up to 4x the caption size: a 400% display wants 64px. Larger sizes are for Alt-Tab and Explorer, and
        // would only be sampled down here -- unless they are all the mark has, so the one nearest 64 always goes.
        java.util.Map<Icon.Image, dev.vexelray.vulkan.present.SampledImage> sizes = new java.util.LinkedHashMap<>();
        for (Icon.Image image : mark.images()) {
            if (Math.max(image.width(), image.height()) <= ICON_DP * 4) {
                sizes.put(image, app.texture(rgba(image), image.width(), image.height()));
            }
        }
        sizes.computeIfAbsent(mark.bestFor(ICON_DP * 4),
                image -> app.texture(rgba(image), image.width(), image.height()));
        Icon uploaded = new Icon(java.util.List.copyOf(sizes.keySet()));

        Node node = gui.box().size(Length.dp(ICON_DP), Length.dp(ICON_DP)).scroll(false, false);
        // Chosen against the box the layout settled on, which is in pixels and so already carries the scale.
        Seams.of(gui).onResize(node, computed -> node.image(sizes.get(uploaded.bestFor(
                Math.round(Math.max(computed.rect().w(), computed.rect().h()))))));
        return icon(node);
    }

    /** {@code image}'s straight {@code 0xAARRGGBB} pixels as the straight RGBA8 bytes a texture takes. */
    static byte[] rgba(Icon.Image image) {
        int[] argb = image.argb();
        byte[] out = new byte[argb.length * 4];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            out[i * 4] = (byte) (p >>> 16);
            out[i * 4 + 1] = (byte) (p >>> 8);
            out[i * 4 + 2] = (byte) p;
            out[i * 4 + 3] = (byte) (p >>> 24);
        }
        return out;
    }

    /**
     * Draw the window controls with {@code minimize}, {@code maximize}, {@code restore} and {@code close} instead of
     * the bar's own. A {@code null} is the bar's own drawing for that button, so {@code glyphs(null, null, null,
     * mine)} changes only close, and four nulls put the defaults back.
     *
     * <p><b>Only the drawing changes.</b> The buttons, their window regions (maximize still earns Snap Layouts),
     * their hover plates and their size stay the bar's, and each glyph is drawn {@code GLYPH} square in the theme's
     * ink, so any {@link IconSource} will do: a {@link dev.vexelray.gui.icons.Icon}, or a field of the application's
     * own. Maximize and restore are separate because they are two drawings the bar swaps between as the window
     * changes, whoever changed it; giving one without the other would show the default half the time.
     *
     * <p>Replacing rather than accumulating, like {@link #instruments}: the call says what the controls look like
     * now. Redrawn in place, so it can be called at any time, before the window exists or after.
     */
    public TitleBar glyphs(IconSource minimize, IconSource maximize, IconSource restore, IconSource close) {
        minimizeOwn = own(minimizeGlyph, minimize);
        maximizeOwn = own(maximizeGlyph, maximize);
        restoreOwn = own(restoreGlyph, restore);
        closeOwn = own(closeGlyph, close);
        syncMaximized();
        this.minimize.paint();
        this.close.paint();
        return this;
    }

    /** Point {@code view} at {@code source}, if there is one; whether the application's glyph is now in use. */
    private static boolean own(IconView view, IconSource source) {
        if (source != null) {
            view.icon(source);
        }
        view.node().visible(source != null);
        return source != null;
    }

    /**
     * Add a node to the left-hand slot, after the title — a menu bar, a tab strip; the mark goes in {@link #icon}.
     * Anything clickable placed here must declare {@link WindowRegion#INTERACTIVE} itself (directly, or on each of
     * its own clickable parts), or the window manager will start a window drag from it instead of letting the click
     * land.
     */
    public TitleBar addLeading(Node node) {
        leading.append(node);
        return this;
    }

    // -------------------------------------------------------------------- the application's menus

    /** The landmark of the application's menu named {@code name}: {@code titlebar-menu.<name>}. */
    public static String menuLandmark(String name) {
        return "titlebar-menu." + name;
    }

    /** One of the application's menus: its target after the lockup, its label, and what it holds now. */
    private final class AppMenu {
        final String name;
        final Node target;
        volatile Menu menu = Menu.NONE;
        volatile InteractionState state = InteractionState.NORMAL;
        /** Whether the menu this target dropped is open now: it stays lit until it closes. */
        volatile boolean open;

        AppMenu(String name) {
            this.name = name;
            // The lockup's shape and the lockup's behaviour: one target, a plate that lifts under the pointer, the
            // label taking the accent, and the menu hung from its lower left. Its siblings, so they read as one bar.
            this.target = gui.text("")
                    .role("titlebar-menu")
                    .height(Length.dp(24))
                    .padding(Length.ZERO, Length.dp(8))
                    .corner(Length.dp(5))
                    .textSize(TITLE_SIZE)
                    .fontWeight(500)
                    .wordWrap(false)
                    .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE)
                    .background(Role.NONE)
                    .scroll(false, false);
            Seams.of(gui).onClick(target, (dev.vexelray.gui.core.input.ClickEvent e) -> show(e));
            Seams.of(gui).onState(target, s -> {
                state = s;
                paint();
            });
            gui.landmark(menuLandmark(name), target);
            paint();
        }

        /** Hold {@code next}: caption while it is empty, as the lockup is while its menu is, and a target once it is not. */
        void declare(String label, Menu next) {
            menu = next == null ? Menu.NONE : next;
            target.text(label == null ? name : label);
            target.windowRegion(menu.isEmpty() ? null : WindowRegion.INTERACTIVE);
            paint();
        }

        /**
         * The plate and the label's ink for what the pointer is doing and whether its menu is open, as
         * {@link #paintLockup} paints the lockup's.
         */
        void paint() {
            boolean live = !menu.isEmpty();
            InteractionState s = live ? state : InteractionState.NORMAL;
            boolean lit = live && open;
            target.background(plate(s, lit));
            target.textColor(!live ? Role.FAINT
                    : s == InteractionState.NORMAL && !lit ? Role.DIM
                    : lockupInk(s, lit));
        }

        /** Open the menu under the target, or close whichever menu is open; {@code at} is the click, or null for a key. */
        void show(dev.vexelray.gui.core.input.ClickEvent at) {
            ContextMenu.drop(gui, target, at, menu, now -> {
                open = now;
                paint();
            });
        }
    }

    /** The application's menus by name, in the order first declared, which is their order in the bar. */
    private final java.util.Map<String, AppMenu> menus = new java.util.LinkedHashMap<>();

    /**
     * Declare the application's menu {@code name}: a target labelled {@code label} after the lockup, opening
     * {@code menu} hung from its lower left, as the lockup opens the app menu. The first declaration of a name places
     * it, after the menus declared before it; a later one replaces its label and what it holds, in place.
     *
     * <p><b>Application menus, beside the framework's.</b> The app menu is the window's: About and the framework's
     * instruments ({@link WindowInstrument}), the same in every window. These are the application's own commands,
     * the menu bar a desktop application has always had, and they sit where a menu bar sits, after the window's
     * identity. They never reach the caption's slack or the instruments and window controls at the right, so those
     * still mean the same thing in every window.
     *
     * <p><b>A menu is data</b> ({@link Menu}): each item sends a message when chosen, and nothing runs at the click.
     * When the facts behind it change, declare it again. One that is {@link Menu#NONE} stays, greyed and inert, and
     * is caption: a menu that comes and goes teaches nothing about where it is.
     *
     * <p>Reached by a script as {@link #menuLandmark}{@code (name)}. Any thread.
     */
    public synchronized TitleBar menu(String name, String label, Menu menu) {
        java.util.Objects.requireNonNull(name, "name");
        AppMenu m = menus.get(name);
        if (m == null) {
            m = new AppMenu(name);
            menus.put(name, m);
            leading.insert(m.target, menus.size());   // after the lockup and the menus before it, before the title
        }
        m.declare(label, menu);
        return this;
    }

    /**
     * Open the application's menu {@code name} under its target, as a click on it would, or close the menu that is
     * open: for a key that drops it (F10, a function key of the application's). Opened so, its first line is under the
     * keyboard, and the arrows and Enter go on from there ({@link ContextMenu#markFirst}). Nothing for a name not
     * declared, an empty menu, or a target not laid out yet. Called where the GUI's own handlers run.
     */
    public void open(String name) {
        AppMenu m;
        synchronized (this) {
            m = menus.get(name);
        }
        if (m != null) {
            m.show(null);
        }
    }

    /** Point the maximize button's glyph at what the window actually is right now. */
    private void syncMaximized() {
        boolean max = controls.maximized();
        maximizeGlyph.node().visible(maximizeOwn && !max);
        restoreGlyph.node().visible(restoreOwn && max);
        maximize.paint();
    }

    /**
     * One window control: a fixed-width, full-height box that paints itself ({@code caption}) and holds the
     * application's own glyph if it gave one, clickable, and declared to the window manager so the caption it sits
     * on does not swallow the click.
     */
    private Node button(Caption caption, Node own, WindowRegion region, Runnable action) {
        Node b = caption.node
                .width(BUTTON_W)
                .height(Length.FILL)
                .justify(Justify.CENTER)
                .alignItems(AlignItems.CENTER)
                .background(Role.NONE)
                .scroll(false, false)
                .windowRegion(region);
        b.append(own);   // over the plate and the drawn glyphs the caption put there
        Seams.of(gui).onClick(b, action);
        return b;
    }

    /** A caption glyph: drawn on the button's grid, in {@code ink}, over {@code under} (what is behind it). */
    @FunctionalInterface
    private interface Glyph {
        Picture draw(CaptionGrid grid, Color ink, Color under);
    }

    /**
     * How every button in the strip paints — instruments and window controls alike, so they cannot drift apart.
     *
     * <p><b>Quiet until approached.</b> At rest a glyph is drawn in the theme's secondary ink on nothing, so the strip
     * recedes behind the title and the content; under the pointer it takes the full ink on a rounded plate inset in
     * the button, and pressed the plate deepens. Close is the one that warns: its plate is the danger colour and its
     * glyph the colour that reads on it.
     *
     * <p><b>Nothing appears or grows, and nothing changes in a frame.</b> The plate is a colour change inside a box that
     * was always the button's — the hit target is the full button at every state, plate or no plate, so a pointer that
     * lands on a button keeps landing on it. The plate is a node with a background, so the framework fades it as it
     * fades any colour. The glyph is a drawing on the caption grid, which keeps its strokes on the device's pixels,
     * drawn once for each state in that state's ink over that state's plate; the state shows one and the framework
     * fades the opacities between them. Drawn per state rather than recoloured because the restore glyph cuts its front
     * window out of the one behind in the colour of what is under it.
     */
    private final class Caption {
        /** The plate's inset in the button, and its size: a 40 x 24 plate in a 46 x 32 button, rounded at five. */
        private static final Length INSET_X = Length.dp(3);
        private static final Length INSET_Y = Length.dp(4);

        final Node node;
        private final boolean danger;
        private final Glyph glyph;
        /** The plate, and the glyph drawn for each state, in {@link InteractionState}'s order. */
        private final Node plate;
        private final Node[] glyphs = new Node[3];
        private volatile dev.vexelray.gui.core.layout.Rect box;
        private volatile InteractionState state = InteractionState.NORMAL;

        Caption(Node node, boolean danger, Glyph glyph) {
            this.node = node;
            this.danger = danger;
            this.glyph = glyph;
            // Neither takes the pointer: the button is what is clicked and hovered, all of it.
            this.plate = gui.box().floatAt(INSET_X, INSET_Y).size(Length.dp(40), Length.dp(24)).corner(Length.dp(5))
                    .background(Role.NONE).hitInert(true).scroll(false, false);
            node.append(plate);
            for (int i = 0; i < glyphs.length; i++) {
                glyphs[i] = gui.box().floatAt(Length.ZERO, Length.ZERO).width(Length.FILL).height(Length.FILL)
                        .hitInert(true).scroll(false, false).opacity(i == 0 ? 1f : 0f);
                node.append(glyphs[i]);
            }
            Seams.of(gui).onResize(node, computed -> {
                box = computed.rect();
                paint();
            });
            Seams.of(gui).onState(node, s -> {
                state = s;
                show();
            });
        }

        /** The plate and the glyph for the state the pointer left the button in; the framework fades between them. */
        private void show() {
            InteractionState s = state;
            plate.background(plateIn(s));
            for (int i = 0; i < glyphs.length; i++) {
                glyphs[i].opacity(i == s.ordinal() ? 1f : 0f);
            }
        }

        /** What the plate is painted in, in {@code s}: nothing at rest, raised hovered, deeper pressed. */
        private dev.vexelray.gui.core.style.Paint plateIn(InteractionState s) {
            if (s == InteractionState.NORMAL) {
                return Role.NONE;
            }
            if (danger) {
                return s == InteractionState.PRESSED ? Role.DANGER.mix(Role.CHROME, 0.25f) : Role.DANGER;
            }
            return s == InteractionState.PRESSED ? Role.LINE : Role.RAISED;
        }

        /** Author each state's glyph for the box the layout settled on. Any thread. */
        void paint() {
            dev.vexelray.gui.core.layout.Rect b = box;
            if (b == null) {
                return;
            }
            Theme theme = gui.theme();
            CaptionGrid g = CaptionGrid.of(b);
            for (InteractionState s : InteractionState.values()) {
                Color under = s == InteractionState.NORMAL ? theme.color(Role.CHROME) : plateIn(s).in(theme);
                Color ink = s == InteractionState.NORMAL ? theme.color(Role.DIM)
                        : theme.color(danger ? Role.ON_DANGER : Role.INK);
                glyphs[s.ordinal()].picture(glyph.draw(g, ink, under));
            }
            show();
        }
    }
}
