package dev.vexelray.gui.iconplugin;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A drawing in several solid colours, taken apart into one mask per colour, back to front: what a layered icon is
 * baked from. Each shape's fill, and then its stroke, is one paint in the order the SVG draws them; paints next to each
 * other in that order and of one colour are one layer. A layer's SVG is the drawing with that layer's paints in black
 * and every other paint taken away, so its clips, transforms and joins are the drawing's own; drawn one over the other
 * in their colours, the layers are the drawing again.
 *
 * <p>Text is left out. At the sizes an icon is drawn its letters are a few pixels high, a grey smear, and a bake has
 * no fonts to set them in.
 *
 * <p>Paints are {@code fill} and {@code stroke} attributes, inherited from groups, black where a shape names no fill
 * as SVG has it. A gradient, a pattern, {@code style}, or any opacity is refused: a layer is one solid colour.
 */
final class SvgLayers {

    /** What draws: everything else is structure, or a definition only something drawn refers to. */
    private static final Set<String> SHAPES = Set.of("path", "rect", "circle", "ellipse", "line", "polyline",
            "polygon");

    private static final Set<String> REFUSED = Set.of("style", "opacity", "fill-opacity", "stroke-opacity");

    /** One layer: its colour, {@code #rrggbb}, and the drawing of it alone as a black mask. */
    record Layer(String color, String svg) {
    }

    /** One paint: the shape it is on, which of the shape's two it is, and its colour. */
    private record Paint(int shape, boolean stroke, String color) {
    }

    private SvgLayers() {
    }

    /** The drawing as a single mask: every paint black, text left out. Its silhouette. */
    static String silhouette(String svg) {
        Parsed p = parse(svg);
        return p.mask(i -> true);
    }

    /** The drawing's layers, back to front. */
    static List<Layer> split(String svg) {
        Parsed p = parse(svg);
        List<Layer> layers = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= p.paints.size(); i++) {
            if (i == p.paints.size() || !p.paints.get(i).color.equals(p.paints.get(start).color)) {
                int from = start;
                int to = i;
                layers.add(new Layer(p.paints.get(from).color, p.mask(k -> k >= from && k < to)));
                start = i;
            }
        }
        return layers;
    }

    private interface Chosen {
        boolean paint(int index);
    }

    /** The document, its shapes in drawing order, and their paints in drawing order. */
    private record Parsed(Document doc, List<Element> shapes, List<Paint> paints) {

        /** The drawing with the chosen paints black and every other paint none, serialised. */
        String mask(Chosen chosen) {
            Document copy = (Document) doc.cloneNode(true);
            List<Element> copied = new ArrayList<>();
            collect(copy.getDocumentElement(), copied, false);
            for (Element e : copied) {
                e.setAttribute("fill", "none");
                e.setAttribute("stroke", "none");
            }
            for (int k = 0; k < paints.size(); k++) {
                if (chosen.paint(k)) {
                    Paint p = paints.get(k);
                    copied.get(p.shape).setAttribute(p.stroke ? "stroke" : "fill", "#000000");
                }
            }
            NodeList texts = copy.getElementsByTagNameNS("*", "text");
            for (int k = texts.getLength() - 1; k >= 0; k--) {
                texts.item(k).getParentNode().removeChild(texts.item(k));
            }
            ungroup(copy.getDocumentElement());
            renumber(copy);
            return write(copy);
        }
    }

    /**
     * Every id named afresh in document order, and every reference to one with it: the canvas numbers its clips
     * across the whole board, so the same drawing in two palettes calls its clip by two names.
     */
    private static void renumber(Document doc) {
        NodeList all = doc.getElementsByTagNameNS("*", "*");
        java.util.Map<String, String> ids = new java.util.HashMap<>();
        for (int k = 0; k < all.getLength(); k++) {
            Element e = (Element) all.item(k);
            if (e.hasAttribute("id")) {
                String fresh = "d" + ids.size();
                ids.put(e.getAttribute("id"), fresh);
                e.setAttribute("id", fresh);
            }
        }
        for (int k = 0; k < all.getLength(); k++) {
            var attributes = all.item(k).getAttributes();
            for (int a = 0; a < attributes.getLength(); a++) {
                Node attribute = attributes.item(a);
                String value = attribute.getNodeValue();
                if (!value.contains("#")) {
                    continue;
                }
                for (var id : ids.entrySet()) {
                    value = value.replace("url(#" + id.getKey() + ")", "url(#" + id.getValue() + ")");
                    if (value.equals("#" + id.getKey())) {
                        value = "#" + id.getValue();
                    }
                }
                attribute.setNodeValue(value);
            }
        }
    }

    /**
     * The paints a group passes down taken off it, now every shape names its own: a mask holds no colour, so the two
     * palettes' masks of one drawing are the same text.
     */
    private static void ungroup(Element e) {
        String name = e.getLocalName() != null ? e.getLocalName() : e.getTagName();
        if (SHAPES.contains(name) || name.equals("defs") || name.equals("clipPath") || name.equals("mask")
                || name.equals("symbol")) {
            return;
        }
        e.removeAttribute("fill");
        e.removeAttribute("stroke");
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element child) {
                ungroup(child);
            }
        }
    }

    private static Parsed parse(String svg) {
        Document doc = read(svg);
        List<Element> shapes = new ArrayList<>();
        collect(doc.getDocumentElement(), shapes, false);
        List<Paint> paints = new ArrayList<>();
        for (int i = 0; i < shapes.size(); i++) {
            Element e = shapes.get(i);
            String fill = inherited(e, "fill", "#000000");
            String stroke = inherited(e, "stroke", "none");
            if (!fill.equals("none")) {
                paints.add(new Paint(i, false, color(fill)));
            }
            if (!stroke.equals("none") && !"0".equals(inherited(e, "stroke-width", "1").strip())) {
                paints.add(new Paint(i, true, color(stroke)));
            }
        }
        if (paints.isEmpty()) {
            throw new IllegalArgumentException("a drawing with nothing painted");
        }
        return new Parsed(doc, shapes, paints);
    }

    /** The shapes under {@code e} in document order, leaving out definitions and clips, which draw nothing. */
    private static void collect(Element e, List<Element> shapes, boolean inDefinition) {
        String name = e.getLocalName() != null ? e.getLocalName() : e.getTagName();
        boolean definition = inDefinition || name.equals("defs") || name.equals("clipPath") || name.equals("mask")
                || name.equals("symbol");
        if (!definition) {
            for (String a : REFUSED) {
                if (e.hasAttribute(a)) {
                    throw new IllegalArgumentException("<" + name + "> has '" + a + "', and a layer is one solid colour");
                }
            }
            if (SHAPES.contains(name)) {
                shapes.add(e);
            }
        }
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element child) {
                collect(child, shapes, definition);
            }
        }
    }

    /** {@code attribute} on {@code e} or the nearest group above it that sets it; {@code otherwise} if none does. */
    private static String inherited(Element e, String attribute, String otherwise) {
        for (Node n = e; n instanceof Element el; n = n.getParentNode()) {
            if (el.hasAttribute(attribute)) {
                return el.getAttribute(attribute).strip();
            }
        }
        return otherwise;
    }

    /** {@code #rgb} or {@code #rrggbb} as {@code #rrggbb}, lower case. Anything else is not a solid colour here. */
    static String color(String paint) {
        String p = paint.strip().toLowerCase(Locale.ROOT);
        if (p.matches("#[0-9a-f]{6}")) {
            return p;
        }
        if (p.matches("#[0-9a-f]{3}")) {
            return "#" + p.charAt(1) + p.charAt(1) + p.charAt(2) + p.charAt(2) + p.charAt(3) + p.charAt(3);
        }
        throw new IllegalArgumentException("paint '" + paint + "' is not a colour as #rgb or #rrggbb");
    }

    private static Document read(String svg) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return f.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
        } catch (ParserConfigurationException | SAXException | java.io.IOException e) {
            throw new IllegalArgumentException("not an SVG this reads: " + e.getMessage(), e);
        }
    }

    private static String write(Document doc) {
        try {
            var t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter out = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toString();
        } catch (TransformerException e) {
            throw new IllegalStateException(e);
        }
    }
}
