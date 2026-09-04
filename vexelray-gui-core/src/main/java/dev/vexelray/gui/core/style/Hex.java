package dev.vexelray.gui.core.style;

import dev.vexelray.canvas.Color;

import java.util.Optional;

/**
 * sRGB colour written the way people write it: {@code #ff8800}. The notation, not a space — {@link Oklab} and
 * {@link Hsv} are how a colour is reasoned about, and this is how one is typed and shown.
 *
 * <p>It lives here rather than in the widget that reads it because a hex string is a fact about a {@link Color}:
 * a picker's entry box, a theme file, a log line and a stylesheet all mean the same eight nibbles by it.
 *
 * <p><b>Parsing answers with an {@link Optional} rather than an exception</b>, because the caller that matters is
 * a text field, and half-typed input is that field's normal state, not its error case. Four widths are accepted,
 * with or without the leading {@code #} and in either case: {@code rgb}, {@code rgba}, {@code rrggbb},
 * {@code rrggbbaa}. The short forms double each nibble, so {@code #f80} is {@code #ff8800} exactly.
 */
public final class Hex {

    private Hex() {
    }

    /** {@code text} as a colour, or empty if it is not one of the four accepted forms. */
    public static Optional<Color> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String s = text.strip();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        int n = s.length();
        if (n != 3 && n != 4 && n != 6 && n != 8) {
            return Optional.empty();
        }
        boolean shorthand = n <= 4;
        int digitsPerChannel = shorthand ? 1 : 2;
        int channels = n / digitsPerChannel;

        float[] v = new float[4];
        v[3] = 1f;
        for (int c = 0; c < channels; c++) {
            int lo = hexDigit(s.charAt(c * digitsPerChannel));
            if (lo < 0) {
                return Optional.empty();
            }
            int byteValue;
            if (shorthand) {
                byteValue = lo * 17;                                  // f -> ff, and 0 -> 00
            } else {
                int second = hexDigit(s.charAt(c * digitsPerChannel + 1));
                if (second < 0) {
                    return Optional.empty();
                }
                byteValue = lo * 16 + second;
            }
            v[c] = byteValue / 255f;
        }
        return Optional.of(new Color(v[0], v[1], v[2], v[3]));
    }

    /**
     * {@code color} as {@code #rrggbb}, or {@code #rrggbbaa} when it is not opaque — the shortest form that says
     * everything, so a colour the user typed as {@code #ff8800} is shown back to them as {@code #ff8800}.
     */
    public static String format(Color color) {
        int a = channel(color.a());
        String rgb = "#" + two(channel(color.r())) + two(channel(color.g())) + two(channel(color.b()));
        return a == 255 ? rgb : rgb + two(a);
    }

    private static int channel(float f) {
        return Math.round(Math.clamp(f, 0f, 1f) * 255f);
    }

    private static String two(int b) {
        return b < 16 ? "0" + Integer.toHexString(b) : Integer.toHexString(b);
    }

    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
