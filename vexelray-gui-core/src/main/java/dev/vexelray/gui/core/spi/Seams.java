package dev.vexelray.gui.core.spi;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.Viewport;
import dev.vexelray.gui.core.drop.DragSource;
import dev.vexelray.gui.core.drop.DragState;
import dev.vexelray.gui.core.drop.DropTarget;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.ClickEvent;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.KeyEvent;
import dev.vexelray.gui.core.input.MenuPresenter;
import dev.vexelray.gui.core.input.MenuSink;
import dev.vexelray.gui.core.input.PointerEvent;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.input.Trigger;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutSnapshot;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.layout.SemanticSnapshot;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.nav.Reveal;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.Objects;
import java.util.function.Function;

/**
 * The seams the GUI runs code through on its own thread, in the frame: the <b>widget library's</b> and the
 * <b>host's</b>, and nobody else's (vexelray-framework's {@code docs/threading.md}, T7.2).
 *
 * <p>A widget is the GUI answering its own input, so it registers code the dispatcher calls in order with the input
 * around it: a button's press, a field's keys and caret, a tree's drop resolution. A host (the application shell, the
 * automation driver) installs the sinks the GUI reports to: undo feedback, the cursor, the pointer lock, the wake. An
 * application does none of this. Its logic hears a widget by message on its own thread, and its view hears a node by
 * {@link Gui#hear} and declares what the dispatcher must know ahead of time ({@link Gui#menu}, a shortcut that sends
 * a message), so no application code is ever handed to the GUI to run in the frame.
 *
 * <p><b>Why a type of its own rather than methods on {@link Gui}:</b> a view is handed the {@code Gui}, and a seam on it
 * is a side channel a view could do logic through. Here, nothing an application is handed reaches it: the
 * framework's API walk holds that, and its processor refuses a {@code @View} that imports this package.
 *
 * <p>{@code Seams.of(gui)} is the tree's own; it is cheap to ask for, so a widget asks where it registers.
 */
public abstract class Seams {

    private static volatile Function<Gui, Seams> lookup;

    /** For {@link Gui}'s own implementation; there is no other. */
    protected Seams() {
    }

    /**
     * Installed once, by {@link Gui} as the class initialises: how a tree's seams are found. A second install is
     * refused, so nothing can put itself between a widget and the tree it registers on.
     */
    public static void install(Function<Gui, Seams> seams) {
        Objects.requireNonNull(seams, "seams");
        synchronized (Seams.class) {
            if (lookup != null) {
                throw new IllegalStateException("the seams are installed once, by Gui");
            }
            lookup = seams;
        }
    }

    /** {@code gui}'s seams. */
    public static Seams of(Gui gui) {
        Objects.requireNonNull(gui, "gui");
        return lookup.apply(gui);
    }

    /**
     * Register a click handler for {@code node} — fired when a left press and release land on it (bubbling to the
     * nearest ancestor with a handler). Runs on the GUI thread, in the dispatch, so it must be short.
     */
    public abstract Seams onClick(Node node, Runnable handler);

    /**
     * Register a click handler that is told <b>what the click meant</b>: the {@link ClickEvent} carries the
     * modifiers held when the button was released, so Ctrl-click and Shift-click can be told apart from a click.
     *
     * <p>Without this a widget driving a selection would have to read keyboard state from somewhere else at the
     * moment a pointer handler runs — a second channel alongside the event, and one that is already wrong if the
     * key came up while the handler was queued. The modifiers were always part of the click (see
     * {@link ClickEvent}); this hands them to the left-button handler as {@link #onContextClick} already does to
     * the right-button one. Runs in the dispatch, like the plain form.
     */
    public abstract Seams onClick(Node node, java.util.function.Consumer<ClickEvent> handler);

    /**
     * Register a double-click handler for {@code node} — fired when the second of two quick left clicks in one
     * place lands on it (bubbling to the nearest ancestor with a double-click handler).
     *
     * {@snippet :
     * Seams.of(gui).onClick(row, () -> select(path))
     *    .onDoubleClick(row, () -> open(path));
     * }
     *
     * <p>The clicks still arrive as clicks: the second one reaches {@link #onClick} and then this. That is what
     * keeps single clicks immediate — telling a lone click from the first half of a double would mean holding
     * every click for the whole double-click window. For triple clicks and beyond, read
     * {@link ClickEvent#clicks()} in an {@link #onClick} handler. Runs in the dispatch, like a click.
     */
    public abstract Seams onDoubleClick(Node node, Runnable handler);

    /** {@link #onDoubleClick(Node, Runnable)}, handed the {@link ClickEvent}: where it landed, what was held. */
    public abstract Seams onDoubleClick(Node node, java.util.function.Consumer<ClickEvent> handler);

    /**
     * Register a context-click handler for {@code node} — fired when a <em>right</em> press and release land on it
     * (bubbling to the nearest ancestor with a handler). The handler receives the {@link ClickEvent} because a
     * context action almost always needs the pointer position, to anchor a menu at it. A right click changes
     * nothing else — no focus move, no PRESSED state, no drag capture; what it means is the widget's decision.
     * Runs on the GUI thread, in the dispatch, so it must be short.
     */
    public abstract Seams onContextClick(Node node, java.util.function.Consumer<ClickEvent> handler);

    /**
     * Give {@code node} a context menu: {@code source} is asked what should be on it at the moment of each right
     * click, and the answer is shown by the installed {@link MenuPresenter}.
     *
     * {@snippet :
     * Seams.of(gui).onContextMenu(row, menu -> menu
     *         .item("Open", () -> open(path))
     *         .item("Paste", clipboard.hasText(), () -> paste(path)));   // shown, greyed when it cannot apply
     * }
     *
     * <p><b>The nearest node with a menu owns the click</b> — leaf→root, the same rule a click handler follows. So
     * a row inside a panel inside a page may each declare one and the innermost thing the user pointed at answers,
     * with none of them knowing the others exist.
     *
     * <p><b>Sources accumulate</b>, like {@link #onState} observers: a widget declares its own defaults (a text
     * field's Copy/Cut/Paste) and the application adds what only it knows, in registration order, into one menu.
     * That is why the menu is built per click rather than held: its contents are a fact about the current state,
     * and only the source knows what that is. A source that adds nothing means no menu opens, so "sometimes there
     * is nothing to offer here" needs no special case.
     *
     * <p>Sources run on the GUI thread, in the dispatch, so they must be short. An application declares its menu
     * instead ({@link Gui#menu}, T7.5).
     *
     * <p><b>The first menu brings its presenter.</b> A tree with no {@link #menus() presenter} gets the
     * {@link MenuPresenter.Default} on the class path here ({@code vexelray-gui-widget}'s {@code ContextMenu}), so
     * a tree whose only menus are the application's own draws them as a text field's would be.
     */
    public abstract Seams onContextMenu(Node node, java.util.function.Consumer<MenuSink> source);

    /**
     * Show the menu {@code source} builds at {@code at}, now: for a control whose plain click opens a menu, as a
     * title-bar instrument's does. Built and shown as a right click's would be, by the installed presenter (the
     * default one, if this tree has none yet).
     */
    public abstract Seams openMenu(ClickEvent at, java.util.function.Consumer<MenuSink> source);

    /**
     * Install what draws context menus. {@code vexelray-gui-widget}'s {@code ContextMenu} is installed the first
     * time a node is given a menu, so this is only for replacing it with something else — before the UI is built,
     * since a presenter already there is kept rather than replaced.
     */
    public abstract Seams menus(MenuPresenter presenter);

    /** The installed context-menu presenter, or {@code null} if nothing shows menus on this tree yet. */
    public abstract MenuPresenter menus();

    /**
     * React to {@code node}'s pointer-interaction state (NORMAL/HOVER/PRESSED) — e.g. to restyle a button on hover
     * and press. The handler is invoked in the dispatch on each state change, so it must be short. Hovering a descendant (a button's label) counts as hovering {@code node}.
     */
    public abstract Seams onState(Node node, java.util.function.Consumer<InteractionState> handler);

    /**
     * Follow where the pointer is over {@code node}: told on every move while it or a descendant is under the
     * pointer, and once, not over, when it leaves. Observers accumulate, as {@link #onState}'s do, and run in the
     * dispatch, so they must be short. See {@link PointerEvent}.
     */
    public abstract Seams onPointer(Node node, java.util.function.Consumer<PointerEvent> handler);

    /**
     * Drag {@code node}: a left press on it (or a descendant) captures the pointer and delivers START/MOVE/END
     * {@link DragEvent}s — MOVE continues while held even when the pointer leaves the node. The handler runs in the
     * dispatch; use {@link DragEvent#fractionX()}/{@code fractionY()} to map the pointer onto the node.
     */
    public abstract Seams onDrag(Node node, java.util.function.Consumer<DragEvent> handler);

    /**
     * Take the wheel over {@code node} (or a descendant): it arrives as a {@link dev.vexelray.gui.core.input.WheelEvent}
     * in the dispatch, instead of scrolling whatever {@code node} is inside. For something that is not inside what
     * it moves — a scrollbar beside its {@link Scroller}, a 3D view panning its camera. {@code null} gives the wheel
     * back.
     */
    public abstract Seams onWheel(Node node, java.util.function.Consumer<dev.vexelray.gui.core.input.WheelEvent> handler);

    /**
     * Say what would happen if a drag were released on {@code node} — see {@link DropTarget}.
     *
     * <p>Not the same thing as {@link #onDrag}, and a node may have both without them interacting. That one is a
     * manipulation the pointer steers: captured on press, no threshold, ends on release, and always about a
     * gesture that started on the node itself. This one answers about a drag the node did not start and may know
     * nothing about — one begun elsewhere in this window, in another window of this process, or in another
     * process entirely, since a file dragged from the desktop arrives with no press for anything to have
     * captured.
     *
     * <p>The target is asked once per frame while a drag is over it, on the GUI thread, and returns a
     * description. Nothing it describes happens until the user releases, and what happens then is exactly the
     * {@link dev.vexelray.gui.core.drop.Drop} that was last shown.
     */
    public abstract Seams onDrop(Node node, DropTarget target);

    /**
     * The drop targets under a point, innermost first: the {@code DragSession.Lookup} for this tree, for a drag source
     * driving a session against it.
     */
    public abstract java.util.List<DropTarget> dropTargetsAt(float x, float y);

    /**
     * Make {@code node} something a drag can begin on — see {@link DragSource}.
     *
     * <p>The gesture is recognised for you: a press here becomes a drag only once the pointer has travelled far
     * enough and held long enough, so an ordinary click on the node stays an ordinary click. The source is asked
     * for a payload at that moment, not at the press, and answering null leaves the gesture as a click.
     */
    public abstract Seams onDragSource(Node node, DragSource source);

    /**
     * Hear about every drag in this tree on the GUI thread, in the frame it moves, and hear how it ended — see
     * {@link dev.vexelray.gui.core.drop.DragWatch}.
     *
     * <p>For whatever draws the thing being carried. {@link Gui#drag()} is the published view and is right for
     * drawing <em>about</em> a drag, but a handle written from it lands a frame late, and its ending does not say
     * whether the drag dropped or was cancelled. {@code node} only bounds the registration: the watch is dropped
     * with it, and is told about drags anywhere in the tree, not only over it.
     */
    public abstract Seams watchDrag(Node node, dev.vexelray.gui.core.drop.DragWatch watch);

    /**
     * Observe {@code node}'s computed geometry: the handler is called with its {@link NodeLayout} whenever the
     * node's box changes, <b>including the first time it has one</b>. Runs on the GUI thread, in the publish after the
     * layout that changed it, so it must be short: measure, mutate a handle, return.
     *
     * <p><b>This is the seam for "my geometry is real now".</b> Everything else a node can be told about is
     * input; geometry was observable only by polling {@link Node#layout} or by subscribing to {@link Gui#layout()}
     * and diffing, and an application that did neither had no way to know when its box arrived. The failure that
     * produces is silent — code that runs too early reads {@link NodeLayout#ABSENT}, does nothing, reports
     * nothing, and is never asked again — which is why it belongs in the framework rather than in each
     * application's notes.
     *
     * <p><b>Lifecycle callbacks are not a substitute, and neither is the window.</b> {@code WindowSpec.onCreated}
     * means the OS window exists; nothing has been measured yet, so a node's box is still absent there. The first
     * call to this handler is the moment after that — and because it is an observer rather than a one-shot, the
     * same registration also covers every later window resize, zoom change and DPI change, which a
     * "first frame" callback would not.
     *
     * <p>Only a change of <em>box</em> fires it: the border rect and the content rect are compared, so scrolling
     * a container does not (its viewport did not move, its contents did). A node laid out to zero — inside a
     * hidden parent, say — is reported honestly as zero rather than withheld; a consumer that cannot draw at
     * that size checks and waits for the next call.
     */
    public abstract Seams onResize(Node node, java.util.function.Consumer<NodeLayout> handler);

    /**
     * Register a key handler for {@code node} (which becomes focusable). Fires with each key press while the node
     * holds focus, after shortcuts and Tab traversal have had first refusal. Runs on the GUI thread, in the dispatch.
     */
    public abstract Seams onKey(Node node, java.util.function.Consumer<KeyEvent> handler);

    /**
     * Register a typed-text handler for {@code node} (which becomes focusable and, by convention, editable).
     * Fires once per Unicode code point typed while the node holds focus — the text channel, delivered from
     * {@code CharTyped}, kept separate from {@link #onKey} (caret motion, backspace, shortcuts). Runs in the
     * dispatch.
     */
    public abstract Seams onChar(Node node, java.util.function.IntConsumer handler);

    /**
     * Declare how {@code node} makes a descendant of it reachable — the seam that lets navigation cross a tab
     * panel, a collapsed branch or a drawer without knowing what any of those are. See {@link Reveal}, which is
     * where the reasoning lives; the widgets that conceal things register their own, so an application composing
     * them needs this only for a container it wrote itself.
     */
    public abstract Seams reveals(Node node, Reveal reveal);

    /** Register a global keyboard shortcut. The command runs on the GUI thread, in the dispatch. */
    public abstract Seams shortcut(Shortcut shortcut, Runnable command);

    /** Convenience: {@code seams.shortcut(Key.S, save, Modifier.CONTROL)}. */
    public abstract Seams shortcut(Key key, Runnable command, Modifier... mods);

    /**
     * Claim {@code chord} for {@code node} at {@code scope} — preemption declared in advance. While the claim
     * applies, that chord runs {@code command} and reaches nothing else: not the focused node's key handler, not
     * another element's claim at a broader scope, not the framework's own defaults (which are themselves
     * {@link ClaimScope#GLOBAL} claims, so a focused element outranks them).
     *
     * <p>This is what the framework offers in place of {@code preventDefault}, which it cannot: cancelling
     * requires a handler to answer before the dispatch moves on, and a handler that may refuse is a callback with
     * a veto. Declaring the preemption up front lets the dispatcher decide without asking anyone.
     *
     * <p>The chord may as well be a touchpad {@link Gesture} — a three-finger swipe, a two-finger double tap —
     * which is claimed, outranked and released exactly as a key is.
     */
    public abstract Seams claim(Node node, Trigger chord, ClaimScope scope, Runnable command);

    /** {@link #claim(Node, Trigger, ClaimScope, Runnable)} for a chord. */
    public abstract Seams claim(Node node, Shortcut chord, ClaimScope scope, Runnable command);

    /**
     * Claim <em>every</em> chord for {@code node} at {@code scope}, the command hearing which one was pressed —
     * for a control whose subject is the chord itself, a key-binding recorder above all. While it is armed at
     * {@link ClaimScope#FOCUSED}, Ctrl+S records Ctrl+S rather than saving, and Tab records Tab rather than
     * moving focus: the framework's defaults are claims, and this one outranks them while it applies.
     *
     * <p>A claim naming its chord at the same scope still wins, so the wildcard is the floor of its scope and
     * never shadows a key somebody asked for by name. A modifier pressed alone is not a chord and reaches nobody,
     * here as everywhere; read {@link Gui#modifiers()} to show the ones being held. A touchpad {@link Gesture} that
     * is a command (a swipe, a tap) is heard too, so a recorder records the gesture made on the pad. The command
     * runs in the dispatch.
     */
    public abstract Seams claimAny(Node node, ClaimScope scope, java.util.function.Consumer<Trigger> command);

    /**
     * Hear what every {@link #undo}/{@link Gui#redo} in this tree came to, on a handler thread — the seam a
     * presenter hangs on to turn a failure into a prompt or a cue. One sink; installing another replaces it.
     */
    public abstract Seams onUndo(java.util.function.Consumer<dev.vexelray.gui.core.edit.UndoEvent> sink);

    /**
     * Install the sink notified when the desired pointer cursor changes (§8.3) — e.g. the I-beam over editable
     * text. The application maps {@link CursorShape} onto its window's cursor. Called on the GUI thread.
     */
    public abstract Seams onCursorChange(java.util.function.Consumer<CursorShape> sink);

    /**
     * Install the sink notified when the pointer should be locked ({@code true}) or released ({@code false}).
     * The application maps it onto its input backend's pointer lock. Called on the GUI thread, and only on a
     * change, so an application need not de-duplicate.
     *
     * <p>The same seam as {@link #onCursorChange} and for the same reason: the framework can say what it wants
     * of a pointer and cannot reach an OS to get it.
     */
    public abstract Seams onPointerLock(java.util.function.Consumer<Boolean> sink);

    /**
     * How a host loop that parks between frames is told a mutation is waiting for it.
     *
     * <p><b>Required by any loop that sleeps</b>, and its absence is a window that stops updating rather
     * than a slow one. An application's logic publishes from its own thread, between frames, after the
     * frame that showed the click has already drained, reconciled and presented. A loop that then asks only its
     * animation clock is told there is nothing to do, parks, and leaves the mutation queued: the button was
     * clicked, the state changed, and the
     * screen keeps showing what it showed before. It comes right the instant any OS event arrives, so it
     * presents as "the UI only updates when I move the mouse" — which points at input handling rather
     * than at the loop, and it is worst on a touchpad where a click carries no movement with it.
     *
     * <pre>{@code
     * Seams.of(gui).onWork(app::postWake);          // alongside kron.onWork(app::postWake)
     * }</pre>
     *
     * <p>Both are needed and they are not redundant: this one covers changes the application makes, the
     * clock's covers time passing. Either alone leaves half the reasons a frame is due unaccounted for.
     *
     * <p>Fires on <em>every</em> publish, including from the GUI thread mid-frame, which costs at most
     * one extra pass that finds nothing to do. Filtering by thread would be an optimisation paid for in
     * exactly the currency this is trying to save.
     *
     * <p>One listener, last call wins — so one call site per {@code Gui}. A second, from a profiler or
     * an adapter installing its own, silently replaces the first and the loop stops waking.
     */
    public abstract void onWork(Runnable listener);

    /**
     * Install where the pointer is put when this tree asks to carry it — the host's half of
     * {@link Gui#ridePointer}. The same seam as {@link #onPointerLock}: core can say where it wants the pointer and
     * cannot reach an OS to put it there. Until one is installed, a ride moves nothing.
     */
    public abstract Seams onPointerWarp(dev.vexelray.gui.core.input.PointerWarp warp);

    /**
     * The warp installed now, {@link dev.vexelray.gui.core.input.PointerWarp#NONE} if none. For a second pointer that
     * borrows the seam — an automation driver while it holds the hand — and has to give it back.
     */
    public abstract dev.vexelray.gui.core.input.PointerWarp pointerWarp();

    /**
     * Install the tree's clock, which runs every widget's {@link dev.vexelray.gui.core.Timing}: the host's, once, as a
     * window's motion is set up. Replaces any before it. Until one is installed a timing lands at once.
     */
    public abstract Seams clock(Clock clock);

    /** The tree's clock, or null before the host installs one. */
    public abstract Clock clock();

    /**
     * Ask {@code part} what its nodes show on every frame from the next one on, and park the loop only until the
     * earliest time it says it is due ({@link Asked}). The host's: vexelray-framework registers a view's declared draw
     * here. GUI thread.
     */
    public abstract Seams ask(Asked part);

    /** Stop asking {@code part}; what it last set on its nodes stays set. GUI thread. */
    public abstract Seams unask(Asked part);

    /** One axis of {@code scroller} as a scrollbar's source: vertical when {@code vertical}, else horizontal. */
    public abstract ScrollSource scrollSource(dev.vexelray.gui.core.Scroller scroller, boolean vertical);

    // --- the GUI's own read models, as bus States: what a widget restyles or re-solves from (T7.2) ---

    /**
     * The live drag as a bus {@code State} — subscribe with {@code Seams.of(gui).drag().onCommit(...)} to draw a ghost or a
     * drop indicator. {@link DragState#NONE} whenever nothing is being dragged.
     *
     * <p>Published once per frame, on change only, so a still pointer over a settled indicator costs nothing.
     */
    public abstract sibarum.atchung.State<DragState> drag();

    /**
     * The modifier keys held right now, as a bus {@code State} — subscribe with
     * {@code Seams.of(gui).modifiers().onCommit(...)} to restyle while one is held, or read {@code .value()} inside a
     * handler that needs to know what a click meant.
     *
     * <p><b>Why this exists at all.</b> A modifier press is not a command and is routed nowhere: it arms the
     * chord the next key makes, and until now that was the whole of its life. But a modifier is also a
     * <em>mode</em> the user can see — hold Ctrl and the links in a document underline themselves — and a mode
     * with no observable state is one every widget would have to reconstruct from key edges of its own, which
     * is precisely the side channel this framework does not permit. It comes from the same tactroller edges as
     * everything else; this is where the framework stops keeping it to itself.
     *
     * <p>Committed on change only, and cleared when the window loses focus — a key released over another window
     * is never seen here, and a modifier that stays held for ever is a UI stuck in a mode with no way out.
     */
    public abstract sibarum.atchung.State<java.util.Set<Modifier>> modifiers();

    /**
     * The live window size as a bus {@code State} — subscribe with {@code Seams.of(gui).viewport().onCommit(...)}. The
     * drawable area in layout px, the space {@link #layout()} rects and input events are in: always the real
     * window, never the canvas {@link Gui#minSize} may clamp it to, and not the OS outer rect a window is created at.
     */
    public abstract sibarum.atchung.State<Viewport> viewport();

    /**
     * The user zoom factor as a bus {@code State} (1.0 = unscaled). Read it with {@code Seams.of(gui).zoom().value()} or
     * subscribe to observe changes.
     *
     * <p>Zoom is not a property of any node: it is one of the ambient factors every {@link Length} resolves
     * against ({@code em = v · rootEmPx · zoom · dpi}, §6), so changing it rescales the entire UI — box sizes,
     * padding, border, corner radius, text size, the scrollbar thickness, the text insets and the wheel step —
     * in one step and in proportion. That is the whole point of §6's "no pixel unit", and zooming is the cheapest
     * way to see whether it actually holds: anything that fails to move with the rest is still pinned to device
     * pixels. The one deliberate exception is {@link Length#dp}: chrome that honours density and opts out of zoom
     * (§6), so a gap or a hairline in dp is meant to stay put.
     *
     * <p><b>The window follows.</b> In a {@code GuiApp}, a window that is not maximized, minimized or fullscreen
     * scales its client area by the same ratio, so a zoom keeps the composition rather than crowding or emptying
     * the window; an axis already spanning the screen's work area keeps its span, and a window that would outgrow
     * the screen is fitted into it. A resize by hand is the new base. A zoom set before the first frame (a restored
     * one) is not a change: the window was already sized for it.
     */
    public abstract sibarum.atchung.State<Float> zoom();

    /**
     * The display density as a bus {@code State} — the points-to-pixels ratio of the surface being drawn to
     * (1.0 on a conventional display, 2.0 on a Retina-class one).
     *
     * <p>Distinct from {@link #zoom} on purpose, even though both currently multiply into the same em. Density
     * keeps a UI the same <em>physical</em> size on a denser screen; zoom is the user asking for bigger. A length
     * can sensibly honour one and not the other, and that is only expressible while the two stay separate
     * factors.
     */
    public abstract sibarum.atchung.State<Float> dpi();

    /**
     * The computed-layout read-model as a coalesced {@code State} (docs/reference/layout-read-model.md): every node's
     * position/size/scroll after layout, republished per changed frame. Subscribe to react to layout changes, or
     * read a node's own via {@link Node#layout()}. The value is one frame stale (the framework's input latency).
     */
    public abstract sibarum.atchung.State<LayoutSnapshot> layout();

    /**
     * The semantic read-model as a coalesced {@code State} (docs/reference/automation.md §3): what every node <b>is</b> —
     * role, name, structure, focus — published on the same frames and at the same {@code version} as
     * {@link #layout()}, so the two join by node id and by frame.
     *
     * <p>Exists because {@link dev.vexelray.gui.core.model.NodeKind} is {@code BOX} or {@code TEXT}: enough for
     * the renderer, and an anonymous tree to everyone else. A consumer that must address a node by meaning — an
     * automation agent, a thin client — reads this.
     */
    public abstract sibarum.atchung.State<SemanticSnapshot> semantics();

    /**
     * Drain + reconcile + (re)layout for one frame; returns the retained root to render (may be null on the very
     * first call before the root's Create is drained — it won't be, since we drain first). {@code tm} supplies
     * text intrinsic sizes.
     */
    public abstract RetainedNode frame(float viewportW, float viewportH, TextMeasurer tm);
}
