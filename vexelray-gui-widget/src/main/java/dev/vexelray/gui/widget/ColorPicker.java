package dev.vexelray.gui.widget;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Hex;
import dev.vexelray.gui.core.style.Hsv;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.draw.Picture;
import sibarum.atchung.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Pick a colour: a saturation/value square over a hue strip and an alpha strip, a preview, a hex box, and a strip
 * of the colours recently chosen.
 *
 * <p>The panel is an ordinary node ({@link #node()}), so where it appears is the application's decision — inline
 * in an inspector, inside a {@link Popout}, or floated over the page with {@code Node.floatAt} the way
 * {@link ContextMenu} does. This widget draws a picker; it does not also decide it is a drop-down.
 *
 * <h2>Three announcements, and they are not the same event</h2>
 * {@link #onChange} fires continuously while a drag moves — that is the live preview, and the whole reason to
 * drag rather than type. {@link #onCommit} fires when a choice has been <em>made</em>: the drag ended, the hex was
 * submitted, a recent swatch was clicked; that is what records into the {@link ColorHistory}. And
 * {@link #color(Color)} — the application telling the picker what the selection is now — fires neither, because
 * nobody chose anything. An application drives its preview from the first and its document edit and its undo
 * entry from the second. Collapsing them would make either every pixel of a drag an undo step or the picker
 * impossible to watch.
 *
 * <h2>Why the ramps are pictures, and why they are exact</h2>
 * A ramp is hundreds of marks that participate in no layout and change wholesale when the box does, so it is a
 * {@link Picture} rather than a subtree of boxes. The engine's alphabet has no gradient (see {@link Picture}), so
 * a ramp is banded — but the saturation/value square is banded in one axis only, and the other is exact. Value in
 * HSV is a multiply, and the Canvas composites straight alpha, so black at coverage {@code 1-v} over the colour at
 * {@code v=1} <em>is</em> the colour at {@code v}, to the bit. The square is therefore one strip per saturation
 * column plus one strip per value row — a few hundred marks instead of the tens of thousands a cell grid would
 * need, with no approximation bought for it.
 *
 * <p>The gradient goes in {@code picture} and the marker in {@code overlay}, which is also what keeps a drag
 * cheap: the marker is re-authored on every pointer move, the gradient only when the box or the hue changes.
 *
 * <h2>What it holds</h2>
 * The picker's state is an {@link Hsv} and an alpha, not a {@link Color} — because grey has no hue and black has
 * neither hue nor saturation, so a picker that stored the resulting colour and re-derived the coordinates would
 * lose the user's place every time they dragged into a corner (see {@link Hsv}).
 *
 * {@snippet :
 * ColorPicker picker = new ColorPicker(gui, stroke.color(), app.recentColors())
 *         .onChange(c -> canvas.previewStroke(c))
 *         .onCommit(c -> app.edit("Stroke colour", () -> stroke.color(c)));
 * inspector.append(picker.node());
 * }
 */
public final class ColorPicker implements AutoCloseable {

    /** How wide a band is allowed to get before another is added, in px. Below an eye's ability to see a step. */
    private static final double BAND_PX = 2.0;

    /** The most bands any one ramp is drawn with, whatever its size — the cap that bounds a picture's cost. */
    private static final int MAX_BANDS = 256;

    /** The alpha checkerboard's square, in px. Fixed rather than scaled: it is a "nothing here" texture. */
    private static final double CHECKER_PX = 6.0;

    private static final Color CHECKER_LIGHT = Color.rgb(0.62f, 0.62f, 0.62f);
    private static final Color CHECKER_DARK = Color.rgb(0.44f, 0.44f, 0.44f);

    /** The marker's ink: a white ring inside a dark one reads on every colour the square can show. */
    private static final Color MARKER_DARK = Color.rgba(0f, 0f, 0f, 0.8f);
    private static final Color RAMP_EDGE = Color.rgba(0f, 0f, 0f, 0.35f);

    private static final Length HAIRLINE = Length.rem(0.1f);

    private final Gui gui;
    private final Theme theme;
    private final ColorHistory history;

    private final Node root;
    private final RampView field;
    private final RampView hue;
    private final RampView alphaRamp;
    private final Node preview;
    private final TextField hex;
    private final List<Node> slots = new ArrayList<>();
    private final Tooltip swatchTips;
    private final Subscription historySub;

    /** The chosen colour, in the coordinates it is steered in. See the class note on why this and not a Color. */
    private volatile Hsv hsv;
    private volatile float alpha;

    /** The colours the slots currently stand for; a slot past the end of this is empty, and stays a slot. */
    private volatile List<Color> slotColors = List.of();

    /**
     * Set while the picker is writing the hex box itself. Without it, echoing the colour back into the box would
     * rewrite what the user is halfway through typing — the box is an input, and an input the widget also drives
     * has to know which of the two is speaking.
     */
    private volatile boolean writingHex;

    private volatile Consumer<Color> onChange = c -> { };
    private volatile Consumer<Color> onCommit = c -> { };

    /** A picker starting at opaque white, with a private history. */
    public ColorPicker(Gui gui) {
        this(gui, Color.WHITE, new ColorHistory());
    }

    /** A picker starting at {@code initial}, with a private history. */
    public ColorPicker(Gui gui, Color initial) {
        this(gui, initial, new ColorHistory());
    }

    /**
     * A picker starting at {@code initial} and sharing {@code history} — the form an application with more than
     * one picker wants, so that all of them show the same recents (see {@link ColorHistory}).
     */
    public ColorPicker(Gui gui, Color initial, ColorHistory history) {
        this.gui = gui;
        this.theme = gui.theme();
        this.history = history == null ? new ColorHistory() : history;
        Color start = initial == null ? Color.WHITE : initial;
        this.hsv = Hsv.of(start);
        this.alpha = start.a();

        this.field = ramp("colorpicker.field", Length.rem(9), this::onFieldDrag);
        this.hue = ramp("colorpicker.hue", Length.rem(1.1f), this::onHueDrag);
        this.alphaRamp = ramp("colorpicker.alpha", Length.rem(1.1f), this::onAlphaDrag);

        this.preview = gui.box()
                .role("colorpicker.preview")
                .size(Length.rem(2.4f), Length.rem(2.4f))
                .corner(Length.rem(0.4f))
                .border(HAIRLINE, theme.color(Role.LINE))
                .scroll(false, false);
        gui.onResize(preview, computed -> repaintPreview());

        this.hex = new TextField(gui, Hex.format(start));
        hex.node().width(Length.grow(1f)).height(Length.rem(2.4f));
        hex.onSubmit(this::onHexSubmitted);

        this.swatchTips = new Tooltip(gui);
        Node recents = gui.row()
                .role("colorpicker.recents")
                .width(Length.FILL)
                .gap(Length.rem(0.25f))
                .scroll(false, false);
        for (int i = 0; i < this.history.capacity(); i++) {
            Node slot = swatch(i);
            slots.add(slot);
            recents.append(slot);
        }

        this.root = gui.column()
                .role("colorpicker")
                .width(Length.rem(16))
                .gap(Length.rem(0.5f))
                .padding(Length.rem(0.5f))
                .background(theme.color(Role.PANEL))
                .corner(Length.rem(0.5f))
                .scroll(false, false)
                .children(
                        field.node(),
                        hue.node(),
                        alphaRamp.node(),
                        gui.row().width(Length.FILL).gap(Length.rem(0.5f))
                                .alignItems(AlignItems.CENTER)
                                .scroll(false, false)
                                .children(preview, hex.node()),
                        recents);

        // The strip follows the history wherever it is changed from -- this picker, another picker sharing it, or
        // the application restoring a saved palette. Nothing here writes the strip directly.
        this.historySub = this.history.colors().onCommit(committed -> showRecents(committed.value()));
        showRecents(this.history.current());
        repaint();
    }

    /** The node to place in a layout. */
    public Node node() {
        return root;
    }

    /** The colour right now. */
    public Color color() {
        return hsv.toColor(alpha);
    }

    /** The chosen colour in the coordinates the picker steers in — hue survives a slide into grey. */
    public Hsv hsv() {
        return hsv;
    }

    /** The recents this picker shows and records into. */
    public ColorHistory history() {
        return history;
    }

    /**
     * Show {@code color}, as an application does when the selection changes under the picker. A <b>display</b>
     * change: neither callback fires and nothing is recorded, because nobody chose it here.
     */
    public ColorPicker color(Color color) {
        if (color != null) {
            this.hsv = Hsv.of(color);
            this.alpha = color.a();
            repaint();
        }
        return this;
    }

    /**
     * Whether the alpha strip is shown (it is, by default). Hiding it also pins alpha at 1 — a picker with no way
     * to say otherwise must not hand back a colour the user cannot see is transparent.
     */
    public ColorPicker alpha(boolean shown) {
        alphaRamp.node().visible(shown);
        if (!shown) {
            this.alpha = 1f;
            repaint();
        }
        return this;
    }

    /** Called continuously while a drag moves — the live preview. Runs on a worker thread. */
    public ColorPicker onChange(Consumer<Color> handler) {
        this.onChange = handler == null ? c -> { } : handler;
        return this;
    }

    /**
     * Called when a colour has been <b>chosen</b>: a drag released, a hex submitted, a recent clicked. This is the
     * one to hang an edit and an undo entry off, and it is what records into the {@link ColorHistory}. Runs on a
     * worker thread.
     */
    public ColorPicker onCommit(Consumer<Color> handler) {
        this.onCommit = handler == null ? c -> { } : handler;
        return this;
    }

    /** Release the tooltip, the hex field and the history subscription. The nodes go when they leave the tree. */
    @Override
    public void close() {
        historySub.close();
        swatchTips.close();
        hex.close();
    }

    // --- the three ramps ------------------------------------------------------------------------------------

    /** One draggable ramp. No border, so the picture's frame is exactly the box it is authored against. */
    private RampView ramp(String role, Length height, Consumer<DragEvent> drag) {
        Node n = gui.box()
                .role(role)
                .width(Length.FILL)
                .height(height)
                .background(theme.color(Role.WELL))
                .scroll(false, false);
        gui.onDrag(n, drag);
        gui.cursor(n, CursorShape.GRAB);
        // A picture resolves no units of its own, so every ramp is re-authored against the box the layout
        // settled on -- which is also what carries it through a zoom or a DPI change.
        gui.onResize(n, computed -> repaint());
        return new RampView(n);
    }

    private void onFieldDrag(DragEvent e) {
        Hsv now = hsv;
        this.hsv = new Hsv(now.hue(), e.fractionX(), 1f - e.fractionY());
        announce(e.phase() == DragEvent.Phase.END);
    }

    private void onHueDrag(DragEvent e) {
        Hsv now = hsv;
        this.hsv = new Hsv(e.fractionX() * 360.0, now.saturation(), now.value());
        announce(e.phase() == DragEvent.Phase.END);
    }

    private void onAlphaDrag(DragEvent e) {
        this.alpha = e.fractionX();
        announce(e.phase() == DragEvent.Phase.END);
    }

    private void onHexSubmitted(String text) {
        Hex.parse(text).ifPresentOrElse(
                c -> {
                    this.hsv = Hsv.of(c);
                    this.alpha = c.a();
                    announce(true);
                },
                // Not an error to report: the box is showing something that is not a colour, and the colour it is
                // meant to be showing is the one thing that can say so.
                () -> writeHex(color()));
    }

    // --- painting -------------------------------------------------------------------------------------------

    /**
     * Repaint, then say what happened: a commit also records into the history, which is what refills the strip.
     * Every path that changes the colour <em>because the user did something</em> ends here.
     */
    private void announce(boolean committed) {
        repaint();
        Color c = color();
        if (committed) {
            history.use(c);
            onCommit.accept(c);
        } else {
            onChange.accept(c);
        }
    }

    /** Re-author everything that shows the current colour. Says nothing to anyone. */
    private void repaint() {
        Hsv now = hsv;
        float a = alpha;

        field.paint(now.hue(), (w, h) -> fieldPicture(w, h, now.hue()),
                (w, h) -> ring(now.saturation() * w, (1.0 - now.value()) * h));
        hue.paint(HUE_IS_HUE, ColorPicker::huePicture,
                (w, h) -> bar(now.hue() / 360.0 * w, w, h));
        alphaRamp.paint(now, (w, h) -> alphaPicture(w, h, now),
                (w, h) -> bar(a * w, w, h));

        preview.background(now.toColor(a));
        repaintPreview();
        writeHex(now.toColor(a));
    }

    /** Marks authored against a box — the one shape every part of a ramp's painting has. */
    @FunctionalInterface
    private interface Ramps {
        List<Picture.Mark> marks(double w, double h);
    }

    /**
     * One ramp's node together with what its gradient was last drawn from, so that the expensive half of a repaint
     * happens only when it would come out different.
     *
     * <p>The marker is re-authored every time and the gradient hardly ever, which is the whole reason they are two
     * props: a pointer move changes where the marker is and (on the square) nothing about the colours behind it,
     * and a hue strip's gradient is the same picture for the life of the box.
     */
    private static final class RampView {

        private final Node node;
        private double w;
        private double h;
        private Object drawnFrom = NOTHING;

        /** A key no caller can produce, so the first paint always draws. */
        private static final Object NOTHING = new Object();

        RampView(Node node) {
            this.node = node;
        }

        Node node() {
            return node;
        }

        /**
         * Author the marker, and the gradient too if {@code gradientKey} or the box has changed since last time.
         * Synchronized because a drag and a resize arrive on different worker threads.
         */
        synchronized void paint(Object gradientKey, Ramps gradient, Ramps marker) {
            NodeLayout computed = node.layout();
            double nw = computed.rect().w();
            double nh = computed.rect().h();
            if (nw <= 0 || nh <= 0) {
                return;                                 // laid out to nothing; the next resize will have a box
            }
            if (nw != w || nh != h || !gradientKey.equals(drawnFrom)) {
                w = nw;
                h = nh;
                drawnFrom = gradientKey;
                node.picture(new Picture(gradient.marks(nw, nh)));
            }
            List<Picture.Mark> over = new ArrayList<>(edge(nw, nh));
            over.addAll(marker.marks(nw, nh));
            node.overlay(new Picture(over));
        }
    }

    /** The hue strip's gradient depends on nothing but its box, so its key is a constant. */
    private static final Object HUE_IS_HUE = "hue";

    private void repaintPreview() {
        NodeLayout computed = preview.layout();
        double w = computed.rect().w();
        double h = computed.rect().h();
        if (w <= 0 || h <= 0) {
            return;
        }
        List<Picture.Mark> marks = checker(w, h);
        marks.add(new Picture.Fill(0, 0, w, h, 0, 0, color(), "colorpicker.preview"));
        preview.picture(new Picture(marks));
    }

    private void writeHex(Color c) {
        String want = Hex.format(c);
        if (!writingHex && !want.equals(hex.text())) {
            writingHex = true;
            try {
                hex.text(want);
            } finally {
                writingHex = false;
            }
        }
    }

    /**
     * The saturation/value square: one vertical strip per saturation column at full value, then one horizontal
     * black strip per value row at coverage {@code 1-v}. Exact down the value axis; see the class note.
     */
    private static List<Picture.Mark> fieldPicture(double w, double h, double hue) {
        int columns = bands(w);
        int rows = bands(h);
        List<Picture.Mark> marks = new ArrayList<>(columns + rows);
        for (int i = 0; i < columns; i++) {
            // One px of overlap on every band: a seam between two bands of a ramp is more visible than the step
            // the bands were there to hide.
            marks.add(new Picture.Fill(i * w / columns, 0, w / columns + 1, h, 0, 0,
                    new Hsv(hue, (i + 0.5) / columns, 1.0).toColor(), "colorpicker.saturation"));
        }
        for (int j = 0; j < rows; j++) {
            float coverage = (float) ((j + 0.5) / rows);          // 1 - v, and v = 1 - y/h
            marks.add(new Picture.Fill(0, j * h / rows, w, h / rows + 1, 0, 0,
                    Color.rgba(0f, 0f, 0f, coverage), "colorpicker.value"));
        }
        return marks;
    }

    private static List<Picture.Mark> huePicture(double w, double h) {
        int columns = bands(w);
        List<Picture.Mark> marks = new ArrayList<>(columns);
        for (int i = 0; i < columns; i++) {
            marks.add(new Picture.Fill(i * w / columns, 0, w / columns + 1, h, 0, 0,
                    new Hsv((i + 0.5) / columns * 360.0, 1.0, 1.0).toColor(), "colorpicker.hue"));
        }
        return marks;
    }

    private static List<Picture.Mark> alphaPicture(double w, double h, Hsv hsv) {
        int columns = bands(w);
        List<Picture.Mark> marks = checker(w, h);
        for (int i = 0; i < columns; i++) {
            marks.add(new Picture.Fill(i * w / columns, 0, w / columns + 1, h, 0, 0,
                    hsv.toColor((float) ((i + 0.5) / columns)), "colorpicker.alpha"));
        }
        return marks;
    }

    /** The "nothing behind this" texture every surface showing an alpha stands on. */
    private static List<Picture.Mark> checker(double w, double h) {
        List<Picture.Mark> marks = new ArrayList<>();
        marks.add(new Picture.Fill(0, 0, w, h, 0, 0, CHECKER_LIGHT, "colorpicker.checker"));
        for (double y = 0; y < h; y += CHECKER_PX) {
            for (double x = 0; x < w; x += CHECKER_PX) {
                if (((int) (x / CHECKER_PX) + (int) (y / CHECKER_PX)) % 2 == 1) {
                    marks.add(new Picture.Fill(x, y, Math.min(CHECKER_PX, w - x), Math.min(CHECKER_PX, h - y),
                            0, 0, CHECKER_DARK, "colorpicker.checker"));
                }
            }
        }
        return marks;
    }

    /** A hairline hugging a ramp's box, so a pale colour still has an edge against a pale panel. */
    private static List<Picture.Mark> edge(double w, double h) {
        return List.of(new Picture.Outline(0, 0, w, h, 0, 0, 1, RAMP_EDGE, "colorpicker.edge"));
    }

    /**
     * The square's marker: a white ring inside a dark one. Two rings rather than one, because there is no single
     * colour a ring can be that survives both the white corner and the black one.
     */
    private static List<Picture.Mark> ring(double cx, double cy) {
        return List.of(
                circle(cx, cy, 6.5, MARKER_DARK),
                circle(cx, cy, 5.0, Color.WHITE));
    }

    private static Picture.Mark circle(double cx, double cy, double r, Color color) {
        return new Picture.Outline(cx - r, cy - r, r * 2, r * 2, r, r, 1.5, color, "colorpicker.marker");
    }

    /** A strip's marker: the same two-tone idea as a full-height capsule, kept inside the strip at both ends. */
    private static List<Picture.Mark> bar(double cx, double w, double h) {
        double half = 4.0;
        double x = Math.clamp(cx - half, 0.0, Math.max(0.0, w - half * 2));
        return List.of(
                new Picture.Outline(x, 0, half * 2, h, half, half, 1.5, MARKER_DARK, "colorpicker.marker"),
                new Picture.Outline(x + 1.5, 1.5, half * 2 - 3, Math.max(0.0, h - 3), half, half, 1.5,
                        Color.WHITE, "colorpicker.marker"));
    }

    private static int bands(double extent) {
        return (int) Math.clamp(Math.round(extent / BAND_PX), 1L, MAX_BANDS);
    }

    // --- recents --------------------------------------------------------------------------------------------

    /**
     * One slot of the recents strip. Every slot exists from the start and never moves, filled or not: a strip that
     * grew as it filled would move the swatch the user was reaching for, and a slot that appeared under the
     * pointer would be a target that arrived after the aim. The slots share the row's width, so how many colours
     * a history keeps is its own decision and costs the panel no width.
     */
    private Node swatch(int index) {
        Node slot = gui.box()
                .role("colorpicker.swatch")
                .width(Length.grow(1f))
                .height(Length.rem(1.5f))
                .corner(Length.rem(0.3f))
                .border(HAIRLINE, theme.color(Role.LINE))
                .background(theme.color(Role.WELL))
                .scroll(false, false);
        gui.onClick(slot, () -> {
            Color c = colorAt(index);
            if (c != null) {
                this.hsv = Hsv.of(c);
                this.alpha = c.a();
                announce(true);
            }
        });
        // An empty slot is not a control, so it does not light up: the strip reserves its space without
        // pretending there is something there to click.
        gui.onState(slot, state -> slot.border(HAIRLINE,
                colorAt(index) != null && state != InteractionState.NORMAL
                        ? theme.color(Role.ACCENT)
                        : theme.color(Role.LINE)));
        gui.onResize(slot, computed -> slot.picture(swatchPicture(slot, colorAt(index))));
        // Asked at hover, not at attach: a slot's colour outlives any one thing it held. Attached once, for the
        // reason Tooltip.attach(Node, Supplier) exists.
        swatchTips.attach(slot, () -> {
            Color c = colorAt(index);
            return c == null ? "" : Hex.format(c);
        });
        return slot;
    }

    private Color colorAt(int index) {
        List<Color> current = slotColors;
        return index < current.size() ? current.get(index) : null;
    }

    private void showRecents(List<Color> colors) {
        this.slotColors = colors;
        for (int i = 0; i < slots.size(); i++) {
            Node slot = slots.get(i);
            Color c = i < colors.size() ? colors.get(i) : null;
            slot.background(c == null ? theme.color(Role.WELL) : c);
            slot.picture(swatchPicture(slot, c));
        }
    }

    /** A swatch of a translucent colour has to say so, so it stands on the same checkerboard the strip does. */
    private static Picture swatchPicture(Node slot, Color c) {
        if (c == null || c.a() >= 1f) {
            return null;                                // the background alone is the whole of an opaque swatch
        }
        NodeLayout computed = slot.layout();
        double w = computed.rect().w();
        double h = computed.rect().h();
        if (w <= 0 || h <= 0) {
            return null;
        }
        List<Picture.Mark> marks = checker(w, h);
        marks.add(new Picture.Fill(0, 0, w, h, 0, 0, c, "colorpicker.swatch"));
        return new Picture(marks);
    }
}
