package dev.vexelray.gui.draw;

/**
 * A whole SVG document around a {@link Picture} — the wrapper, where {@link SvgSink} is the elements.
 *
 * <p>Split from the sink because they answer different questions. The sink says how a mark is written, and a
 * caller embedding a picture inside a document of its own wants only that. This says what a standalone file looks
 * like, and it is a short list of decisions:
 *
 * <ul>
 *   <li><b>A {@code viewBox} of the picture's own pixel size, and {@code width}/{@code height} to match.</b> So
 *       the file opens at the size it was drawn at, and scales without any coordinate being touched — the
 *       resolution independence a vector format is for, from geometry that was authored in pixels.</li>
 *   <li><b>The font family is on the root, not on each run.</b> One inherited declaration a stylesheet can
 *       override in one place, rather than the same attribute repeated on every label. The atlas face a picture
 *       was drawn with is a GPU artefact and cannot travel, so a family <em>name</em> is the most a file can
 *       honestly carry.</li>
 *   <li><b>No background.</b> A drawing is transparent where it drew nothing, and a viewer supplies its own
 *       page. A picture that wants a ground draws one, as it does on screen.</li>
 * </ul>
 */
public final class Svg {

    /** What a picture is set in when the caller does not say — a family name, not a font. */
    public static final String DEFAULT_FONT = "system-ui, sans-serif";

    private Svg() {
    }

    /** {@code picture} as a standalone document {@code width} x {@code height} px, in {@link #DEFAULT_FONT}. */
    public static String document(Picture picture, double width, double height) {
        return document(picture, width, height, DEFAULT_FONT);
    }

    /** {@code picture} as a standalone document {@code width} x {@code height} px, set in {@code fontFamily}. */
    public static String document(Picture picture, double width, double height, String fontFamily) {
        StringBuilder out = new StringBuilder();
        out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(SvgSink.num(width))
                .append("\" height=\"").append(SvgSink.num(height))
                .append("\" viewBox=\"0 0 ").append(SvgSink.num(width)).append(' ').append(SvgSink.num(height))
                .append('"');
        if (fontFamily != null && !fontFamily.isEmpty()) {
            out.append(" font-family=\"").append(SvgSink.escape(fontFamily)).append('"');
        }
        out.append(">\n");
        picture.emitTo(new SvgSink(out, "  "));
        return out.append("</svg>\n").toString();
    }
}
