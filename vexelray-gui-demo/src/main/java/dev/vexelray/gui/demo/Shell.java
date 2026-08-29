package dev.vexelray.gui.demo;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.Cues;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.text.TextLayout;
import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The frame the gallery lives in: a title bar the GUI draws itself, a navigation rail of chapters, the page,
 * the activity rail, and a status line.
 *
 * <p><b>The rail and the pages are the same widget.</b> {@link Tabs} already owns everything a page host needs —
 * pages hidden rather than removed so a chapter keeps its caret and its state while another is up, a crossfade
 * driven by a ramp the application supplies, keyboard selection — and the only thing that made it look like the
 * wrong widget for a vertical rail was its own header bar. So the header bar is hidden and the rail drives
 * {@link Tabs#select}. Writing a second page host to get a different-shaped list of buttons would have been
 * writing the interesting half again to avoid reusing it.
 */
public final class Shell {

    /** Width of the navigation rail. Wide enough for the longest chapter title at 1x, and it is a rem so it
     *  grows with zoom like the type in it rather than staying a fixed gutter. */
    private static final Length NAV_W = Length.rem(11.5f);

    /** Width of the activity rail. Narrow enough to be a margin, wide enough for a path to be readable. */
    private static final Length LOG_W = Length.rem(19);

    private final Gui gui;
    private final Stage stage;
    private final Console console;
    private final TitleBar titleBar;
    private final Tabs pages;
    private final Tabs.TabTransition crossfade;
    private final Node status;
    private final Node pageTitle;
    private final Node pageBlurb;
    private final List<Chapter> chapters = new ArrayList<>();
    private final List<Node> navItems = new ArrayList<>();
    private final List<AtomicReference<InteractionState>> navStates = new ArrayList<>();

    public Shell(Gui gui, KronoGui krono, List<Chapter> content) {
        this.gui = gui;
        Theme theme = gui.theme();
        this.console = new Console(gui);
        // One cue player for the gallery. 420ms rather than something snappier: a cue competes with whatever the
        // user is actually looking at, so under about a third of a second it reads as a rendering glitch if it
        // registers at all. Linear, because a cue does not arrive anywhere — it travels through, or rises and
        // falls, and easing a thing with no destination only produces a stall.
        Cues cues = new Cues((progress, done) -> krono.ramp(Dur.ms(420), Ease.LINEAR, progress, done));
        this.stage = new Stage(gui, krono, cues, console);

        this.pages = new Tabs(gui);
        // The dissolve, and LINEAR again for the same reason: OUT_CUBIC is 87% faded by the halfway point, so
        // the visible part of the transition finishes in the first third and the rest of the duration is a stall
        // — a delay followed by a change, rather than a change.
        this.crossfade = Tabs.crossfade(
                (progress, done) -> krono.ramp(Dur.ms(180), Ease.LINEAR, progress, done));
        pages.transition(crossfade);
        pages.bar().visible(false);   // the rail is the tab bar; see the class note

        this.pageTitle = gui.text("").width(Length.FILL).height(Length.rem(2.25f))
                .textSize(Length.rem(1.5f)).textColor(theme.color(Role.INK))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
        this.pageBlurb = gui.text("").width(Length.FILL)
                .textSize(Length.rem(0.9375f)).textColor(theme.color(Role.DIM))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
        this.status = gui.text("").height(Length.FILL).width(Length.grow(1))
                .textSize(Length.rem(0.875f)).textColor(theme.color(Role.FAINT))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
        this.titleBar = new TitleBar(gui, dev.vexelray.gui.core.WindowControls.NONE, "VexelRay GUI");

        Node nav = gui.column().width(NAV_W).height(Length.FILL)
                .background(theme.color(Role.CHROME))
                .padding(Length.dp(10)).gap(Length.rem(0.125f))
                .scroll(false, true);
        for (Chapter chapter : content) {
            add(chapter, nav);
        }

        Node page = gui.column().width(Length.grow(1)).height(Length.FILL)
                .padding(Length.dp(20)).gap(Length.rem(0.75f))
                .children(
                        gui.column().width(Length.FILL).height(Length.AUTO).gap(Length.rem(0.125f))
                                .children(pageTitle, pageBlurb),
                        pages.node());

        Node activity = gui.column().width(LOG_W).height(Length.FILL)
                .background(theme.color(Role.CHROME))
                .padding(Length.dp(14)).gap(Length.rem(0.5f))
                .children(
                        gui.text("Activity").width(Length.FILL).height(Length.rem(1.5f))
                                .textSize(Length.rem(0.9375f)).textColor(theme.color(Role.ACCENT))
                                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE),
                        console.node());

        Node body = gui.row().width(Length.FILL).height(Length.FILL)
                .children(nav, page, activity);

        gui.root().background(theme.color(Role.PAGE))
                .children(titleBar.node(), body, statusBar());

        // Ctrl+1..9 jumps to a chapter. An ordinary GLOBAL claim, so a focused text field that wants one of
        // these chords could outrank it — which none of them do, and that is the point of the ordering existing
        // rather than of this line.
        Key[] digits = {Key.DIGIT_1, Key.DIGIT_2, Key.DIGIT_3, Key.DIGIT_4, Key.DIGIT_5,
                Key.DIGIT_6, Key.DIGIT_7, Key.DIGIT_8, Key.DIGIT_9};
        for (int i = 0; i < chapters.size() && i < digits.length; i++) {
            int index = i;
            gui.shortcut(digits[i], () -> select(index), Modifier.CONTROL);
        }
        pages.onSelect(this::showChapter);
        showChapter(0);
        // The zoom readout, live. Every length in this window resolves through zoom, so the status line saying
        // what it is is the one place the number itself appears.
        gui.zoom().onCommit(v -> restatus());
    }

    /** The tree's root is already built; this is the handle {@code Demo} needs to bind the window chrome. */
    public TitleBar titleBar() {
        return titleBar;
    }

    /** The rail every chapter reports into. */
    public Console console() {
        return console;
    }

    /** Handed to {@code Demo} so the window can be announced to the chapters that asked for it. */
    public Stage stage() {
        return stage;
    }

    /** Show a chapter by index — what the rail and the shortcuts go through. */
    public void select(int index) {
        pages.select(index);
    }

    /**
     * Show a chapter with no transition, for a run that will not present enough frames to finish one.
     *
     * <p>A capture renders a single frame. A crossfade is a change spread over many, so a capture that merely
     * {@link #select}ed would photograph the moment before the new page had appeared — the rail and the heading
     * updated, the body still showing the chapter before it, which is a screenshot that lies. The panel's
     * instant transition is not a special case for captures: it is the default, and the reduced-motion path.
     */
    public void selectNow(int index) {
        pages.transition(Tabs.TabTransition.NONE);
        pages.select(index);
        pages.transition(crossfade);
    }

    private void add(Chapter chapter, Node nav) {
        int index = chapters.size();
        chapters.add(chapter);
        pages.add(chapter.title(), chapter.build(stage));

        AtomicReference<InteractionState> state = new AtomicReference<>(InteractionState.NORMAL);
        Node item = gui.text(chapter.title()).width(Length.FILL).height(Length.rem(2.25f))
                .corner(Length.rem(0.5f)).padding(Length.ZERO, Length.em(0.625f))
                .textSize(Length.rem(0.9375f))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
        navStates.add(state);
        navItems.add(item);
        gui.onState(item, s -> {
            state.set(s);
            paintNav();
        });
        gui.onClick(item, () -> select(index));
        nav.append(item);
    }

    /**
     * Paint the rail: the current chapter is a selection, the rest are quiet until pointed at.
     *
     * <p>All of them, on every change, rather than only the two that differ. Nine writes of a colour a node
     * already holds cost a comparison each; tracking which two changed costs a bug the first time a third thing
     * can change one of them — and something already can, since the theme is switchable underneath this.
     */
    private void paintNav() {
        Theme theme = gui.theme();
        int current = pages.selected();
        for (int i = 0; i < navItems.size(); i++) {
            boolean on = i == current;
            InteractionState s = navStates.get(i).get();
            navItems.get(i)
                    .textColor(theme.color(on ? Role.INK : Role.DIM))
                    .background(on ? theme.color(Role.SELECTION, s)
                            : s == InteractionState.NORMAL ? dev.vexelray.canvas.Color.TRANSPARENT
                                    : theme.color(Role.PANEL, s));
        }
    }

    private void showChapter(int index) {
        Chapter chapter = chapters.get(index);
        pageTitle.text(chapter.title());
        pageBlurb.text(chapter.blurb());
        paintNav();
        restatus();
    }

    private void restatus() {
        int index = pages.selected();
        status.text(String.format("Chapter %d of %d  ·  %s  ·  zoom %.0f%%  ·  Ctrl+1..9 chapters, "
                        + "Ctrl+= / Ctrl+- zoom",
                index + 1, chapters.size(), chapters.get(index).title(), gui.zoom().value() * 100f));
    }

    private Node statusBar() {
        Theme theme = gui.theme();
        return gui.row().width(Length.FILL).height(Length.rem(1.875f))
                .background(theme.color(Role.CHROME))
                .padding(Length.ZERO, Length.dp(14)).gap(Length.rem(0.75f))
                .alignItems(AlignItems.CENTER).scroll(false, false)
                .children(status);
    }
}
