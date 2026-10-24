package dev.vexelray.gui.iconplugin;

import dev.vexelray.canvas.DistanceField;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import sibarum.imagelib.Imagelib;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A mark as two layers that line up: its <b>shape</b> as a distance field, for effects that grow, glow and light it,
 * and its <b>colour</b>, the drawing itself, for the frame where it rests. Each atlas holds the one field and the
 * colour at one size, side by side, so a shader reads both from the texture it has bound.
 *
 * <p><b>The field is in the MTSDF layout at two ranges.</b> Alpha is the true distance to the shape's edge over the
 * whole margin; red, green and blue are the same distance over {@value #NEAR} of that range, eight times as precise
 * where precision shows, at the edge. One byte cannot be both: a margin of sixty pixels in 255 steps is a step every
 * quarter pixel at one scale and every pixel at four. The three near channels are equal (the median of three equal
 * values is the value), so a true multi-channel field drawn from outlines drops in later without a reader changing.
 *
 * <p><b>The colour is premultiplied</b>, so its bilinear read never darkens toward a transparent neighbour, and has a
 * texel of nothing round it, so a read at its edge never takes the field's.
 *
 * <p>A part of the drawing can be <b>lifted</b>: left out of the shape and kept in the colour, with its place
 * recorded, for a part the application draws itself on the way in and that lands where the drawing has it.
 */
final class ArtPair {

    /** The share of the far range the near channels span; {@code MtsdfStyle.BAKED} in vexelray-shadelets. */
    static final float NEAR = 0.125f;

    /** Texels between the field's cell and the colour's, so neither is filtered into the other. */
    static final int GUTTER = 2;

    /** The side a lifted part is measured at. */
    private static final int MEASURE = 512;

    /** One atlas: the field at {@code (0, 0)}, the colour square at {@code (colourX, colourY)}. */
    record Atlas(int size, int width, int height, int colourX, int colourY, byte[] rgba) {
    }

    /** Where a lifted part sits: its centre and radius, as shares of the drawing's square. */
    record Lift(float x, float y, float radius) {
    }

    private final int field;
    private final int spread;
    private final List<Atlas> atlases;
    private final Lift lift;

    private ArtPair(int field, int spread, List<Atlas> atlases, Lift lift) {
        this.field = field;
        this.spread = spread;
        this.atlases = atlases;
        this.lift = lift;
    }

    List<Atlas> atlases() {
        return atlases;
    }

    Lift lift() {
        return lift;
    }

    /** The field cell's side: the shape's square and its margin each side. */
    int fieldSide() {
        return field + 2 * spread;
    }

    /**
     * {@code svg} baked: its field {@code field} texels square with {@code margin} of that each side, and its colour at
     * each of {@code sizes}, with the element {@code lift} names (or none) kept out of the field.
     */
    static ArtPair bake(byte[] svg, int field, float margin, int[] sizes, String lift) throws IOException {
        if (field <= 0 || margin < 0f || sizes.length == 0) {
            throw new IllegalArgumentException("a pair of a " + field + " texel field, " + margin + " margin, "
                    + sizes.length + " sizes");
        }
        int spread = Math.round(margin * field);
        byte[] shapeSvg = svg;
        Lift lifted = null;
        if (lift != null && !lift.isBlank()) {
            Document doc = parse(svg);
            Element part = byId(doc, lift.strip());
            if (part == null || part.getParentNode() != doc.getDocumentElement()) {
                throw new IllegalArgumentException("no top-level element with id '" + lift + "' to lift");
            }
            lifted = measure(only(doc, part));
            part.getParentNode().removeChild(part);
            shapeSvg = serialise(doc);
        }

        // The shape: the drawing without what was lifted, its coverage cut at one half, as the icon set's are.
        int fine = DistanceField.maskSide(field);
        byte[] mask = Mask.fromRgba(Imagelib.rasterize(shapeSvg, fine, fine).rgba(0), fine, fine, fine,
                Mask.Coverage.ALPHA, false);
        float[] d = DistanceField.distances(mask, field, spread);
        int fside = field + 2 * spread;
        byte[] cell = new byte[fside * fside * 4];
        float far = 2f * Math.max(1, spread);
        float near = far * NEAR;
        for (int i = 0; i < d.length; i++) {
            byte n = unit(0.5f + d[i] / near);
            cell[i * 4] = n;
            cell[i * 4 + 1] = n;
            cell[i * 4 + 2] = n;
            cell[i * 4 + 3] = unit(0.5f + d[i] / far);
        }

        List<Atlas> atlases = new ArrayList<>();
        for (int size : sizes) {
            byte[] colour = premultiplied(Imagelib.rasterize(svg, size, size).rgba(0));
            int cx = fside + GUTTER + 1;
            int cy = 1;
            int w = cx + size + 1;
            int h = Math.max(fside, size + 2);
            byte[] rgba = new byte[w * h * 4];
            for (int y = 0; y < fside; y++) {
                System.arraycopy(cell, y * fside * 4, rgba, y * w * 4, fside * 4);
            }
            for (int y = 0; y < size; y++) {
                System.arraycopy(colour, y * size * 4, rgba, ((cy + y) * w + cx) * 4, size * 4);
            }
            atlases.add(new Atlas(size, w, h, cx, cy, rgba));
        }
        return new ArtPair(field, spread, List.copyOf(atlases), lifted);
    }

    /**
     * The pair's description, which the run time reads beside its atlases: the field's square, margin and near share,
     * and per size its atlas's dimensions and where the colour square is in it; then what was lifted, if anything.
     */
    String descriptor() {
        StringBuilder s = new StringBuilder();
        s.append("# An art pair: a field and a colour layer per size. Written by vexelray-gui-icons-maven-plugin.\n");
        s.append("format=1\n");
        s.append("field=").append(field).append('\n');
        s.append("spread=").append(spread).append('\n');
        s.append("near=").append(NEAR).append('\n');
        StringBuilder sizes = new StringBuilder();
        for (Atlas a : atlases) {
            sizes.append(sizes.isEmpty() ? "" : " ").append(a.size());
        }
        s.append("sizes=").append(sizes).append('\n');
        for (Atlas a : atlases) {
            s.append("atlas.").append(a.size()).append('=').append(a.width()).append(' ').append(a.height())
                    .append(' ').append(a.colourX()).append(' ').append(a.colourY()).append('\n');
        }
        if (lift != null) {
            s.append(String.format(Locale.ROOT, "lift=%.6f %.6f %.6f%n", lift.x(), lift.y(), lift.radius()));
        }
        return s.toString();
    }

    private static byte unit(float v) {
        return (byte) Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
    }

    private static byte[] premultiplied(byte[] straight) {
        byte[] out = new byte[straight.length];
        for (int i = 0; i < straight.length; i += 4) {
            int a = straight[i + 3] & 0xFF;
            for (int c = 0; c < 3; c++) {
                out[i + c] = (byte) Math.round((straight[i + c] & 0xFF) * a / 255f);
            }
            out[i + 3] = (byte) a;
        }
        return out;
    }

    /** The centre and radius of what {@code svg} covers, as shares of its square: the bounds of its alpha. */
    private static Lift measure(byte[] svg) throws IOException {
        byte[] rgba = Imagelib.rasterize(svg, MEASURE, MEASURE).rgba(0);
        int x0 = MEASURE;
        int y0 = MEASURE;
        int x1 = -1;
        int y1 = -1;
        for (int y = 0; y < MEASURE; y++) {
            for (int x = 0; x < MEASURE; x++) {
                if ((rgba[(y * MEASURE + x) * 4 + 3] & 0xFF) >= 128) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        if (x1 < 0) {
            throw new IllegalArgumentException("the lifted part covers nothing");
        }
        float cx = (x0 + x1 + 1) / 2f / MEASURE;
        float cy = (y0 + y1 + 1) / 2f / MEASURE;
        float r = Math.max(x1 + 1 - x0, y1 + 1 - y0) / 2f / MEASURE;
        return new Lift(cx, cy, r);
    }

    /** {@code doc} with nothing at the top level but its definitions and {@code part}, serialised; {@code doc} kept. */
    private static byte[] only(Document doc, Element part) throws IOException {
        Document copy = (Document) doc.cloneNode(true);
        Element root = copy.getDocumentElement();
        Element keep = byId(copy, part.getAttribute("id"));
        NodeList children = root.getChildNodes();
        List<Node> drop = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element e && e != keep && !e.getLocalName().equals("defs")) {
                drop.add(n);
            }
        }
        drop.forEach(root::removeChild);
        return serialise(copy);
    }

    private static Element byId(Document doc, String id) {
        NodeList all = doc.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            if (all.item(i) instanceof Element e && id.equals(e.getAttribute("id"))) {
                return e;
            }
        }
        return null;
    }

    private static Document parse(byte[] svg) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(svg));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("not an SVG this can read: " + e.getMessage(), e);
        }
    }

    private static byte[] serialise(Document doc) throws IOException {
        try {
            var t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();
        } catch (TransformerException e) {
            throw new IOException("cannot write the SVG back: " + e.getMessage(), e);
        }
    }
}
