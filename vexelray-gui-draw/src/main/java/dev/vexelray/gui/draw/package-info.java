/**
 * Drawing: a {@link dev.vexelray.gui.draw.Picture} of marks in one pixel frame, and the two things that consume
 * one — {@link dev.vexelray.gui.draw.CanvasSink} for the screen, {@link dev.vexelray.gui.draw.SvgSink} for a file.
 *
 * <p>This module sits <b>below</b> {@code -core} and depends on nothing but the engine's canvas and text: a
 * picture knows nothing of nodes, layout, themes or the bus, and a test of one needs nothing running. What puts a
 * picture on screen is a node prop in {@code -core}; what a picture <em>is</em> lives here, so an exporter does
 * not drag a GUI in behind it.
 *
 * <p>See docs/drawing.md.
 */
package dev.vexelray.gui.draw;
