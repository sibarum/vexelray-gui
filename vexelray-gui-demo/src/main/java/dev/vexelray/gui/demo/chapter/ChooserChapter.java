package dev.vexelray.gui.demo.chapter;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.demo.Chapter;
import dev.vexelray.gui.demo.Console;
import dev.vexelray.gui.demo.Stage;
import dev.vexelray.gui.demo.Ui;
import dev.vexelray.gui.widget.Segment;
import dev.vexelray.gui.widget.Select;
import dev.vexelray.gui.widget.SelectionModel;
import dev.vexelray.text.TextLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Choosing: the same question in three shapes, and which one is right is a fact about how many answers there are.
 *
 * <p>Three controls across the top ask <em>which of these?</em> and differ only in that. A {@code Segment} shows
 * all of them, which is right for three words and impossible for three hundred. A {@code Select} hides all but
 * the chosen one, which costs a click to see the alternatives and buys a control that is one line high whatever
 * the list holds. The third is the same {@code Select} over ten thousand options, and the counter under it is the
 * argument: the popup holds a screenful of rows however long the list is, because it <em>is</em> a
 * {@code ListView}.
 *
 * <p>The second card is the claim worth the page. <b>Multi-select is not a fourth control</b> — it is the same
 * {@code Select} handed a {@code SelectionModel.range()} instead of a {@code single()}. Everything that differs
 * follows from that one answer, and the keyboard is where it shows: in the single-choice popups an arrow key
 * selects where it lands, and in this one it moves an outlined cursor and chooses nothing until Space says so.
 * That is one call in the widget, not a branch — {@code lead} degrades to {@code at} wherever only one thing may
 * be held.
 *
 * <p>The console separates the two events every chooser has and most conflate. Moving through the options prints
 * a <em>preview</em>; the popup closing on a choice prints one <em>commit</em>. Escape prints neither and puts
 * back what the popup opened with, which is why a preview can be free with itself.
 */
public final class ChooserChapter implements Chapter {

    /** A shading mode: three words, so all three go on show. */
    private enum Shading { SOLID, ADDITIVE, SSS }

    private static final List<String> SIZES =
            List.of("Extra small", "Small", "Medium", "Large", "Extra large", "Enormous");

    private static final List<String> TAGS = List.of(
            "annotated", "archived", "binary", "compressed", "draft", "encrypted",
            "generated", "indexed", "linked", "pinned", "shared", "signed");

    /** What the counter last said, so a line that has not changed is not written again. */
    private String lastCount = "";

    @Override
    public String title() {
        return "Choosers";
    }

    @Override
    public String blurb() {
        return "One of a handful with all of them on show, one of ten thousand behind a chevron, and several at "
                + "once — which is the same control in a different mode, not a different control.";
    }

    @Override
    public Node build(Stage stage) {
        Gui gui = stage.gui();
        Console console = stage.console();

        // ---- all on show ------------------------------------------------------------------------------------

        Segment<Shading> shading = new Segment<>(gui);
        for (Shading s : Shading.values()) {
            shading.option(word(s.name()), s);
        }
        shading.onChange(s -> console.good("shading: " + word(s.name())));

        // ---- one of a handful, behind a chevron -------------------------------------------------------------

        Select<String> size = new Select<>(gui, s -> s);
        size.options(SIZES).placeholder("Pick a size").value("Medium");
        size.selection().onChange(chosen -> console.note("size preview: " + oneOf(chosen)));
        size.onCommit(chosen -> console.good("size chosen: " + oneOf(chosen)));

        // ---- one of ten thousand, at the same cost ----------------------------------------------------------

        List<String> many = new ArrayList<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            many.add("swatch-" + String.format("%05d", i));
        }
        Select<String> deep = new Select<>(gui, s -> s);
        deep.options(many).placeholder("Pick a swatch").value("swatch-06000");
        deep.onCommit(chosen -> console.good("swatch chosen: " + oneOf(chosen)));

        Node counter = gui.text("").width(Length.FILL).height(Length.rem(1.5f))
                .textSize(Length.rem(0.9375f)).textColor(gui.theme().color(Role.DIM))
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
        // Read on every published layout, written only when it changed — a text write invalidates the layout that
        // produced it, so a counter that wrote unconditionally would relayout for ever.
        gui.layout().onCommit(snapshot -> {
            String now = many.size() + " options  ·  " + deep.list().realizedRows() + " rows exist  ·  "
                    + (deep.shown() ? "open" : "shut");
            if (!now.equals(lastCount)) {
                lastCount = now;
                counter.text(now);
            }
        });

        // ---- several at once, which is the same control ------------------------------------------------------

        Select<String> tags = new Select<>(gui, s -> s, SelectionModel.range());
        tags.options(TAGS).placeholder("No tags");
        // A framework cannot write "3 selected" — that is an English sentence, and minting one is the same
        // category of mistake as minting a colour. An application knows what its things are called, so it says.
        tags.summary(chosen -> chosen.size() <= 2
                ? String.join(", ", chosen)
                : chosen.size() + " tags");
        tags.selection().onChange(chosen -> console.note("tags now: " + (chosen.isEmpty() ? "none"
                : String.join(", ", chosen))));
        tags.onCommit(chosen -> console.good("tags committed: " + chosen.size()));

        Node choosers = gui.row().width(Length.FILL).height(Length.AUTO).gap(Ui.GAP)
                .alignItems(AlignItems.START).scroll(false, false)
                .children(
                        field(gui, "Shading", Length.grow(1), shading.node()),
                        field(gui, "Size", Length.grow(1), size.node()),
                        field(gui, "Swatch", Length.grow(1), deep.node()));

        Node notes = Ui.strip(gui,
                Ui.heading(gui, "Why there are two shapes and not one styled two ways"),
                Ui.prose(gui, "A menu hides every option but the chosen one, so it costs a click and a guess to "
                        + "find out what the alternatives even are — fine for a long list, wrong for three "
                        + "words. Three switches say the wrong thing in the other direction: nothing in their "
                        + "shape forbids turning two on, so the exclusivity lives in a handler and the user "
                        + "meets it by being corrected. Picking between a Segment and a Select is picking which "
                        + "of those costs you can afford, and it is decided by the number of options."),
                Ui.heading(gui, "A cursor is not a selection"),
                Ui.prose(gui, "Open the tags and press Down twice: an outline moves and nothing is chosen. "
                        + "Space flips whatever it is on, Shift+Down ranges, Escape puts back what the popup "
                        + "opened with. In the two single-choice popups the same Down key selects where it "
                        + "lands — the widget makes one call either way, because an operation a mode does not "
                        + "permit degrades to the nearest one it does. Without that fourth operation the only "
                        + "way to reach the fourth tag would be to select it, and reaching it is exactly what "
                        + "must not select it."),
                Ui.controls(gui,
                        Ui.label(gui, "Try:", Length.rem(3)),
                        Ui.button(gui, "Open the swatches", () -> {
                            deep.open();
                            console.note("opened at swatch-06000 — the popup scrolled to the value, not to the "
                                    + "top, and built the rows around it");
                        }),
                        Ui.button(gui, "Tag it twice", () -> {
                            tags.selection().set(List.of("draft", "pinned"));
                            console.note("set from the application: no commit, because nobody chose");
                        }),
                        Ui.button(gui, "Clear the tags", () -> {
                            tags.selection().clear();
                            console.note("cleared");
                        })));

        return gui.column().width(Length.FILL).height(Length.FILL).gap(Ui.GAP)
                .children(
                        Ui.strip(gui, Ui.heading(gui, "One of these"), choosers, counter),
                        Ui.strip(gui, Ui.heading(gui, "Several of these — the same control, a different mode"),
                                field(gui, "Tags", Length.rem(18), tags.node())),
                        notes);
    }

    /**
     * A captioned control in a column, so the three across the top line up on their labels.
     *
     * <p>The width is the caller's because the two uses want different answers: three side by side share what
     * the card has ({@code grow}), and one on its own would look absurd stretched to the same width, so it
     * states a size. A control that decided this for itself would be wrong in one of the two places.
     */
    private static Node field(Gui gui, String caption, Length width, Node control) {
        return gui.column().width(width).height(Length.AUTO).gap(Length.rem(0.25f))
                .scroll(false, false)
                .children(
                        gui.text(caption).width(Length.FILL).height(Length.rem(1.25f))
                                .textSize(Length.rem(0.8125f))
                                .textColor(gui.theme().color(Role.FAINT))
                                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE),
                        control);
    }

    /** The one thing in a set, or a word for none — the read-out a single-choice console line wants. */
    private static String oneOf(Set<String> chosen) {
        return chosen.isEmpty() ? "nothing" : chosen.iterator().next();
    }

    /** {@code EXTRA_LARGE} as {@code Extra large}: an enum constant is not a label until somebody says so. */
    private static String word(String constant) {
        String lower = constant.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return constant.length() <= 3
                ? constant                                  // SSS stays SSS
                : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
