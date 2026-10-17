package dev.vexelray.gui.recorder;

/**
 * How a stream ended: what it delivered, as a span with its loss, and why it stopped.
 *
 * @param span what was delivered: the rows, their time on the probe's clock, and the rows missed among them
 * @param why  what ended it, in a few words ({@code for elapsed}, {@code a line arrived}, ...)
 */
public record StreamEnded(Span span, String why) {
}
