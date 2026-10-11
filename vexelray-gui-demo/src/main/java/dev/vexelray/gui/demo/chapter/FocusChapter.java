package dev.vexelray.gui.demo.chapter;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.FocusCause;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.SemanticSnapshot;
import dev.vexelray.gui.core.model.SemanticNode;
import dev.vexelray.gui.core.style.FocusRing;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.demo.Chapter;
import dev.vexelray.gui.demo.Console;
import dev.vexelray.gui.demo.Stage;
import dev.vexelray.gui.demo.Ui;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.ListView;
import dev.vexelray.gui.widget.NumberField;
import dev.vexelray.gui.widget.Segment;
import dev.vexelray.gui.widget.Select;
import dev.vexelray.gui.widget.Slider;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.Toggle;
import dev.vexelray.gui.widget.Tooltip;
import dev.vexelray.text.TextLayout;

import java.util.List;
import java.util.Locale;

/**
 * Focus: one ring, from the theme, around whichever node has keyboard focus, and no widget draws its own.
 *
 * <p>The top card is every focusable control the framework ships, in Tab order. Before the ring was the
 * renderer's, two of them showed focus and the rest took it invisibly; the card is that claim made visible, and
 * the test that the mechanism is in the right place is that none of these widgets has a line of focus code.
 *
 * <p>The middle card tunes the ring, with the same controls the top card shows, so changing the ring restyles the
 * controls changing it. Each control writes a new {@code FocusRing} into the theme; the ring is chrome the
 * renderer reads every frame, so a theme swap reaches it without a restyle pass.
 *
 * <p>The bottom card is the cases the drawing rule exists for: a control that fills its container (the ring comes
 * inside), one half scrolled out of a viewport (the viewport trims it), one between touching neighbours (it is
 * over both), one under a popup (the popup covers it), and a list that marks focus itself and opts out.
 */
public final class FocusChapter implements Chapter {

    // The ring the middle card has dialled in. Written from the controls' handlers; apply() turns it into a theme.
    private volatile Role color = Role.ACCENT;
    private volatile float widthRem = 0.125f;
    private volatile boolean hairline;
    /** Where the offset slider is, 0..1 across "-width" to "+0.375rem". */
    private volatile float offsetAt = 0.5f;
    private volatile FocusRing.Visibility when = FocusRing.Visibility.ALWAYS;

    private static final float MIN_WIDTH = 0.0625f;
    private static final float MAX_WIDTH = 0.375f;
    private static final float MAX_OFFSET = 0.375f;

    @Override
    public String title() {
        return "Focus";
    }

    @Override
    public String blurb() {
        return "Tab through every control the framework ships. One ring, from the theme, and no widget draws its "
                + "own.";
    }

    @Override
    public Node build(Stage stage) {
        Gui gui = stage.gui();
        Console console = stage.console();

        // ---- every focusable widget, in Tab order --------------------------------------------------------------

        TextField text = new TextField(gui, "Type here");
        NumberField number = new NumberField(gui, 42, 1);
        Select<String> select = new Select<>(gui, s -> s);
        select.options(List.of("Red", "Green", "Blue")).show("Green");
        Segment<String> segment = new Segment<>(gui);
        segment.option("One", "one").option("Two", "two").option("Three", "three").show("two");
        Toggle toggle = new Toggle(gui, true);
        Slider slider = new Slider(gui, 0.4f);
        Button primary = new Button(gui, "Primary").kind(Button.Kind.PRIMARY);
        Button secondary = new Button(gui, "Secondary").kind(Button.Kind.SECONDARY);
        Button ghost = new Button(gui, "Ghost").kind(Button.Kind.GHOST);
        Tabs tabs = new Tabs(gui).closable(false);
        tabs.add("Alpha", page(gui, "The first page."));
        tabs.add("Beta", page(gui, "The second page."));
        tabs.add("Gamma", page(gui, "The third page."));

        Node band = gui.column().width(Length.FILL).height(Length.AUTO).gap(Ui.GAP).scroll(false, false)
                .children(
                        line(gui,
                                field(gui, "Text", Length.grow(2), text.node()),
                                field(gui, "Number", Length.grow(1), number.node()),
                                field(gui, "Select", Length.grow(1), select.node()),
                                field(gui, "Segment", Length.grow(2), segment.node())),
                        line(gui,
                                field(gui, "Toggle", Length.rem(4), toggle.node()),
                                field(gui, "Slider", Length.grow(1), slider.node()),
                                field(gui, "Buttons", Length.AUTO, gui.row().width(Length.AUTO)
                                        .height(Length.AUTO).gap(Length.rem(0.5f)).scroll(false, false)
                                        .children(primary.node(), secondary.node(), ghost.node()))),
                        gui.box().width(Length.FILL).height(Length.rem(7)).scroll(false, false)
                                .children(tabs.node()));

        // ---- the ring's own controls ---------------------------------------------------------------------------

        Segment<Role> colour = new Segment<>(gui);
        colour.option("Accent", Role.ACCENT).option("Ink", Role.INK).option("Danger", Role.DANGER)
                .option("Action", Role.ACTION).show(Role.ACCENT);
        colour.onChange(r -> {
            color = r;
            apply(gui, console);
        });

        Slider width = new Slider(gui, (widthRem - MIN_WIDTH) / (MAX_WIDTH - MIN_WIDTH));
        width.onChange(f -> {
            widthRem = MIN_WIDTH + (float) f * (MAX_WIDTH - MIN_WIDTH);
            apply(gui, console);
        });
        Toggle hair = new Toggle(gui, false);
        hair.onChange(on -> {
            hairline = on;
            apply(gui, console);
        });

        Slider offset = new Slider(gui, offsetAt);
        offset.onChange(f -> {
            offsetAt = (float) f;
            apply(gui, console);
        });

        Segment<FocusRing.Visibility> visibility = new Segment<>(gui);
        visibility.option("Always", FocusRing.Visibility.ALWAYS)
                .option("Keyboard only", FocusRing.Visibility.KEYBOARD).show(FocusRing.Visibility.ALWAYS);
        visibility.onChange(v -> {
            when = v;
            apply(gui, console);
        });

        Button none = new Button(gui, "No ring").kind(Button.Kind.SECONDARY).onPress(() -> {
            gui.theme(gui.theme().focusRing(FocusRing.NONE));
            console.note("no ring: Tab now moves focus somewhere you can't see, as it did for ten of these");
        });
        Button back = new Button(gui, "Dialled ring").kind(Button.Kind.SECONDARY)
                .onPress(() -> apply(gui, console));

        Node tuning = gui.column().width(Length.FILL).height(Length.AUTO).gap(Ui.GAP).scroll(false, false)
                .children(
                        line(gui,
                                field(gui, "Colour (a role, so it follows the palette)", Length.grow(1),
                                        colour.node()),
                                field(gui, "When", Length.grow(1), visibility.node())),
                        line(gui,
                                field(gui, "Width", Length.grow(1), slider(gui, width.node(), "thin", "thick")),
                                field(gui, "Hairline (dp: no zoom)", Length.rem(9), hair.node())),
                        field(gui, "Offset", Length.FILL,
                                slider(gui, offset.node(), "inset", "outset")),
                        Ui.controls(gui, none.node(), back.node()));

        // ---- the cases the drawing rule is for -----------------------------------------------------------------

        // Single-line on purpose: a multiline field takes Tab as text, which would end the walk here.
        TextField fills = new TextField(gui, "Fills its panel: the ring comes inside");
        Node fillsPanel = gui.box().width(Length.FILL).height(Length.rem(2.75f)).clip(true)
                .border(Length.rem(0.1f), gui.theme().color(Role.LINE)).corner(Length.rem(0.5f))
                .scroll(false, false)
                .children(fills.node().width(Length.FILL).height(Length.FILL));

        Node scroller = gui.column().width(Length.FILL).height(Length.rem(6)).gap(Length.rem(0.375f))
                .padding(Length.rem(0.375f)).scroll(false, true)
                .border(Length.rem(0.1f), gui.theme().color(Role.LINE)).corner(Length.rem(0.5f));
        for (int i = 1; i <= 6; i++) {
            scroller.append(new TextField(gui, "Row " + i).node().width(Length.FILL));
        }

        Node touching = gui.row().width(Length.FILL).height(Length.AUTO).scroll(false, false)
                .children(new TextField(gui, "Left").node().width(Length.grow(1)),
                        new TextField(gui, "Middle").node().width(Length.grow(1)),
                        new TextField(gui, "Right").node().width(Length.grow(1)));

        Select<String> popup = new Select<>(gui, s -> s);
        popup.options(List.of("Open me", "and Tab here", "the list covers the ring")).show("Open me");
        TextField tipped = new TextField(gui, "Hover me");
        new Tooltip(gui).attach(tipped.node(), "A tooltip covers the ring too: it floats at the root");
        Node underPopup = line(gui, popup.node().width(Length.grow(1)), tipped.node().width(Length.grow(1)));

        ListView<String> list = new ListView<>(gui, 1.75f, (g, item) -> g.text(item).width(Length.FILL)
                .padding(Length.ZERO, Length.rem(0.5f))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE));
        list.items(List.of("This list marks focus", "with its own lead row,", "so it opts out of the ring:",
                "FocusRing.NONE on its node."));
        list.node().width(Length.FILL).height(Length.rem(7)).focusRing(FocusRing.NONE)
                .border(Length.rem(0.1f), gui.theme().color(Role.LINE)).corner(Length.rem(0.5f));

        Node cases = gui.column().width(Length.FILL).height(Length.AUTO).gap(Ui.GAP).scroll(false, false)
                .children(
                        line(gui,
                                field(gui, "Fills its container: the ring falls inward", Length.grow(1),
                                        fillsPanel),
                                field(gui, "In a scroll viewport: trimmed by it", Length.grow(1), scroller)),
                        field(gui, "Next to its siblings: over both neighbours", Length.FILL, touching),
                        field(gui, "Under a popup: the popup covers it", Length.FILL, underPopup),
                        field(gui, "Opted out", Length.FILL, list.node()));

        Node root = gui.column().width(Length.FILL).height(Length.FILL).gap(Ui.GAP)
                .children(
                        Ui.strip(gui, Ui.heading(gui, "Every focusable control, in Tab order"), band,
                                Ui.prose(gui, "Press Tab and Shift+Tab. Before the ring was the renderer's, two "
                                        + "of these showed focus — the text field and the number field, which "
                                        + "recoloured their own border — and the rest took it invisibly. None "
                                        + "of them has focus code now; the framework draws one ring around "
                                        + "whichever node holds focus.")),
                        Ui.strip(gui, Ui.heading(gui, "The ring"), tuning,
                                Ui.prose(gui, "Each control writes a new ring into the theme. With Keyboard only, "
                                        + "clicking a button shows nothing, tabbing to it does, and clicking a "
                                        + "text field still does: a field has to say where your typing goes. "
                                        + "Width and offset are lengths, so the ring scales with zoom — unless "
                                        + "it is a hairline.")),
                        Ui.strip(gui, Ui.heading(gui, "Where it is drawn"), cases));

        // The console says where focus went and how, which is what the When rule is decided from.
        gui.bus().subscribe(gui.focusEvents(), e -> {
            if (!e.gained()) {
                return;
            }
            SemanticSnapshot sem = gui.semanticSnapshot();
            if (!within(sem, e.nodeId(), root.id())) {
                return;
            }
            SemanticNode n = sem.node(e.nodeId());
            String name = n.name().length() > 24 ? n.name().substring(0, 24) + "…" : n.name();
            console.note("focus → " + (n.role().isEmpty() ? "node" : n.role())
                    + (name.isEmpty() ? "" : " \"" + name + "\"") + " by " + cause(e.cause()));
        });
        return root;
    }

    /** The ring the middle card describes, installed as the theme's. */
    private void apply(Gui gui, Console console) {
        float w = widthRem;
        Length width = hairline ? Length.dp(1) : Length.rem(w);
        float min = hairline ? 0f : -w;
        float off = min + offsetAt * (MAX_OFFSET - min);
        FocusRing ring = new FocusRing(color, width, Length.rem(off), when);
        gui.theme(gui.theme().focusRing(ring));
        console.say(String.format(Locale.ROOT, "ring: %s, offset %.3frem%s",
                hairline ? "hairline" : String.format(Locale.ROOT, "%.3frem", w), off,
                when == FocusRing.Visibility.KEYBOARD ? ", keyboard only" : ""));
    }

    /** Whether {@code id} is {@code ancestor} or under it. */
    private static boolean within(SemanticSnapshot sem, long id, long ancestor) {
        for (long at = id; at >= 0; at = sem.node(at).parentId()) {
            if (at == ancestor) {
                return true;
            }
            if (!sem.node(at).present()) {
                return false;
            }
        }
        return false;
    }

    private static String cause(FocusCause cause) {
        return cause == null ? "?" : cause.name().toLowerCase(Locale.ROOT);
    }

    private static Node page(Gui gui, String text) {
        return gui.text(text).padding(Length.rem(0.75f))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP);
    }

    /** Controls side by side, lined up on their captions. */
    private static Node line(Gui gui, Node... children) {
        return gui.row().width(Length.FILL).height(Length.AUTO).gap(Ui.GAP).alignItems(AlignItems.START)
                .scroll(false, false).children(children);
    }

    /** A slider between two words saying what its ends mean. */
    private static Node slider(Gui gui, Node slider, String low, String high) {
        return gui.row().width(Length.FILL).height(Length.AUTO).gap(Length.rem(0.5f))
                .alignItems(AlignItems.CENTER).scroll(false, false)
                .children(end(gui, low, TextLayout.HAlign.RIGHT), slider.width(Length.grow(1)),
                        end(gui, high, TextLayout.HAlign.LEFT));
    }

    private static Node end(Gui gui, String word, TextLayout.HAlign align) {
        return gui.text(word).width(Length.rem(3.5f)).height(Length.rem(1.5f)).textSize(Length.rem(0.8125f))
                .textColor(gui.theme().color(Role.FAINT)).align(align, TextLayout.VAlign.MIDDLE);
    }

    /** A captioned control, as on the Choosers page. */
    private static Node field(Gui gui, String caption, Length width, Node control) {
        return gui.column().width(width).height(Length.AUTO).gap(Length.rem(0.25f)).scroll(false, false)
                .children(
                        gui.text(caption).width(Length.FILL).height(Length.rem(1.25f))
                                .textSize(Length.rem(0.8125f))
                                .textColor(gui.theme().color(Role.FAINT))
                                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE),
                        control);
    }
}
