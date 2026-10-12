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
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.icons.IconView;
import dev.vexelray.gui.krono.Scheduled;
import dev.vexelray.gui.widget.IconButton;
import dev.vexelray.gui.widget.Tooltip;
import sibarum.kronometer.Dur;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The icon set: every drawing in {@code vexelray-icons}, baked into one atlas of distance fields
 * ({@code vexelray-gui-icons}), and what a field makes cheap.
 *
 * <p>An icon here is not a picture of an icon. It is a field the shader cuts at its edge, so one small texture is the
 * icon at any size, sharp at all of them, in whatever colour the theme says, and the distance past the edge is a glow
 * or a soft shadow for nothing. A state that changes an icon's colour or effect changes four numbers, not a texture,
 * and a node's turn ({@code Node.rotate}) turns it without losing an edge — which is how the tree's chevron opens.
 */
public final class IconsChapter implements Chapter {

    /** Icons to a row in the catalogue. */
    private static final int COLUMNS = 16;

    @Override
    public String title() {
        return "Icons";
    }

    @Override
    public String blurb() {
        return "The suite's icon set as distance fields: one atlas, any size and colour, glow and shadow from the "
                + "shader, and turning for free.";
    }

    @Override
    public Node build(Stage stage) {
        Gui gui = stage.gui();
        Console console = stage.console();
        Tooltip names = new Tooltip(gui);

        // The whole set, at the size a toolbar draws it, each named on hover.
        Node catalogue = gui.column().width(Length.FILL).gap(Length.rem(0.5f)).scroll(false, false);
        Node row = null;
        Icon[] all = Icon.values();
        for (int i = 0; i < all.length; i++) {
            if (i % COLUMNS == 0) {
                row = gui.row().gap(Length.rem(0.75f)).scroll(false, false);
                catalogue.append(row);
            }
            Node icon = IconView.of(gui, all[i]).size(Length.dp(24)).node();
            names.attach(icon, all[i].file());
            row.append(icon);
        }

        // One icon at every size: the small drawing up to 24 px, the large one above it.
        Node sizes = gui.row().gap(Length.rem(1f)).alignItems(AlignItems.END).scroll(false, false);
        for (int px : new int[] {12, 16, 20, 24, 32, 48, 64, 96}) {
            sizes.append(IconView.of(gui, Icon.SETTINGS).size(Length.dp(px)).node());
        }

        // The theme's colours, and the two effects the field's distance gives for free.
        Node tints = gui.row().gap(Length.rem(1f)).scroll(false, false).children(
                IconView.of(gui, Icon.INFO).size(Length.dp(40)).tint(Role.ACCENT).node(),
                IconView.of(gui, Icon.CAUTION).size(Length.dp(40)).tint(Role.CAUTION).node(),
                IconView.of(gui, Icon.ERROR).size(Length.dp(40)).tint(Role.DANGER).node(),
                IconView.of(gui, Icon.SUCCESS).size(Length.dp(40)).tint(Role.SUCCESS).node(),
                IconView.of(gui, Icon.PLAY).size(Length.dp(40)).tint(Role.ACCENT).glow(0.2f).node(),
                IconView.of(gui, Icon.RECORD).size(Length.dp(40)).tint(Role.DANGER).glow(0.25f).node(),
                IconView.of(gui, Icon.FOLDER).size(Length.dp(40)).tint(Role.INK).shadow(0.08f).node(),
                IconView.of(gui, Icon.SAVE).size(Length.dp(40)).tint(Role.INK).shadow(0.12f).node());

        // Buttons that are one icon, and toggles whose on state is the same drawing, lit.
        IconButton play = new IconButton(gui, Icon.PLAY, "Play").toggle(true).tip("Play (Space)");
        play.onPress(() -> {
            boolean now = !play.on();
            play.on(now).icon(now ? Icon.PAUSE : Icon.PLAY).name(now ? "Pause" : "Play");
            console.say(now ? "playing" : "paused");
        });
        IconButton pin = new IconButton(gui, Icon.PIN_UP, "Keep shown").toggle(true).tip("Keep shown");
        pin.onPress(() -> {
            boolean now = !pin.on();
            pin.on(now).icon(now ? Icon.PIN_DOWN : Icon.PIN_UP);
        });
        Node buttons = gui.row().gap(Length.rem(0.5f)).alignItems(AlignItems.CENTER).scroll(false, false).children(
                play.node(), pin.node(),
                new IconButton(gui, Icon.STEP, "Step").tip("Step (.)").onPress(() -> console.say("step")).node(),
                new IconButton(gui, Icon.RESET, "Reset").tip("Reset (R)").onPress(() -> console.say("reset")).node(),
                new IconButton(gui, Icon.TRASH, "Delete").hover(Role.DANGER).onPress(() -> console.say("delete"))
                        .node());

        // A turn, which a field takes without losing an edge: a spinner is the busy arc and a clock.
        IconView busy = IconView.of(gui, Icon.BUSY).size(Length.dp(28)).tint(Role.ACCENT);
        AtomicReference<Scheduled> spinning = new AtomicReference<>();
        float[] angle = {0f};
        Node spin = Ui.button(gui, "Spin", () -> {
            Scheduled now = spinning.getAndSet(null);
            if (now != null) {
                now.cancel();
                return;
            }
            spinning.set(stage.krono().every(Dur.ms(16), () -> {
                angle[0] = (angle[0] + 6f) % 360f;
                busy.node().rotate(angle[0]);
            }));
        });
        Node turning = gui.row().gap(Length.rem(1f)).alignItems(AlignItems.CENTER).scroll(false, false)
                .children(busy.node(), spin);

        return gui.column().width(Length.FILL).gap(Ui.GAP).scroll(false, true).children(
                Ui.strip(gui, Ui.heading(gui, "The set"),
                        Ui.prose(gui, Icon.values().length + " icons, every one a cell of one texture the renderer "
                                + "uploads itself. Rest on one for its name."),
                        catalogue),
                Ui.strip(gui, Ui.heading(gui, "Any size"),
                        Ui.prose(gui, "The same field from 12 to 96 px, sharp at each: the 16-unit drawing up to "
                                + "24 px and the 64-unit one above."),
                        sizes),
                Ui.strip(gui, Ui.heading(gui, "Colour, glow and shadow"),
                        Ui.prose(gui, "Tinted by role, so a theme recolours every icon. The glow and the shadow are "
                                + "the distance past the edge, so they cost nothing more."),
                        tints),
                Ui.strip(gui, Ui.heading(gui, "Buttons"),
                        Ui.prose(gui, "IconButton: one icon, a name for readers and a tooltip. A toggle that is on "
                                + "lights its own drawing."),
                        buttons),
                Ui.strip(gui, Ui.heading(gui, "Turning"),
                        Ui.prose(gui, "A node's turn is a transform of the field's frame, so the edge stays one "
                                + "pixel soft at every angle. The tree's chevron opens the same way."),
                        turning));
    }
}
