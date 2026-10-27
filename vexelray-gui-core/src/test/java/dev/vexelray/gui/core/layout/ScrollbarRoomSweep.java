package dev.vexelray.gui.core.layout;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import sibarum.atchung.Atchung;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Investigation, not a regression test: lays a scroller out under every combination of the values an application
 * sets that bear on overflow, and records what its scrollbar does to the content inside it and what a bar drawn
 * outside it (in its margin, below or beside the border box) would land on. Run with
 * {@code mvn -pl vexelray-gui-core test -Dtest=ScrollbarRoomSweep "-DargLine=-Dinvestigate=true -Dsweep.out=<dir>"}.
 * It writes {@code sweep.csv} and {@code summary.txt} there; skipped otherwise. docs/plans/scrollbar-room.md reads them.
 */
@EnabledIfSystemProperty(named = "investigate", matches = "true")
class ScrollbarRoomSweep {

    /** Text at 0.55 em an advance, 1.25 em a line — close enough to the real face for room arithmetic. */
    private static float text(RetainedNode n, Axis axis, float px) {
        String s = n.textString();
        return axis == Axis.HORIZONTAL ? 0.55f * px * (s == null ? 0 : s.length()) : 1.25f * px;
    }

    record Case(boolean horizontal, String sCross, String sPad, String sBorder, String sMargin, boolean declared,
                String childCross, float textRem, float zoom, String pGap, String pPad, boolean pClip,
                boolean sibling) {
        String key() {
            return (horizontal ? "H" : "V") + " sCross=" + sCross + " child=" + childCross
                    + (declared ? " declared" : " undeclared");
        }
    }

    private static Length len(String spec) {
        return switch (spec) {
            case "AUTO" -> Length.AUTO;
            case "FILL" -> Length.FILL;
            case "grow" -> Length.grow(1f);
            case "0" -> Length.ZERO;
            case "pct20" -> Length.percent(20f);
            default -> spec.endsWith("dp") ? Length.dp(Float.parseFloat(spec.replace("dp", "")))
                    : Length.rem(Float.parseFloat(spec.replace("rem", "")));
        };
    }

    record Result(boolean bar, boolean spill, float lossPx, float lossFrac, boolean cascade, float slackPx,
                  boolean marginObscures, boolean marginClipped, String obscured) {
    }

    @Test
    void sweep() throws IOException {
        List<Case> cases = new ArrayList<>();
        for (boolean h : new boolean[]{true, false}) {
            for (String sCross : new String[]{"AUTO", "2.25rem", "36dp", "pct20", "FILL", "grow"}) {
                for (String sPad : new String[]{"0", "0.5rem"}) {
                    for (String sBorder : new String[]{"0"}) {
                        for (String sMargin : new String[]{"0", "1rem"}) {
                            for (boolean declared : new boolean[]{true, false}) {
                                for (String child : new String[]{"FILL", "AUTO", "2.25rem"}) {
                                    for (float textRem : new float[]{1f, 1.5f}) {
                                        for (float zoom : new float[]{1f, 2f}) {
                                            for (String pGap : new String[]{"0", "1rem"}) {
                                                for (String pPad : new String[]{"0", "1rem"}) {
                                                    for (boolean pClip : new boolean[]{false, true}) {
                                                        for (boolean sib : new boolean[]{true, false}) {
                                                            cases.add(new Case(h, sCross, sPad, sBorder, sMargin,
                                                                    declared, child, textRem, zoom, pGap, pPad,
                                                                    pClip, sib));
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Path out = Path.of(System.getProperty("sweep.out", "target/sweep"));
        Files.createDirectories(out);
        Map<String, int[]> summary = new LinkedHashMap<>();
        Map<String, String> example = new LinkedHashMap<>();
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(out.resolve("sweep.csv")));
             Gui gui = new Gui(Atchung.create())) {
            csv.println("orient,sCross,sPad,sBorder,sMargin,declared,child,textRem,zoom,pGap,pPad,pClip,sibling,"
                    + "bar,spill,lossPx,lossFrac,cascade,slackPx,marginObscures,marginClipped,obscured");
            for (Case c : cases) {
                Result r = run(gui, c);
                csv.printf("%s,%s,%s,%s,%s,%b,%s,%.2f,%.1f,%s,%s,%b,%b,%b,%b,%.1f,%.2f,%b,%.1f,%b,%b,%s%n",
                        c.horizontal ? "H" : "V", c.sCross, c.sPad, c.sBorder, c.sMargin, c.declared, c.childCross,
                        c.textRem, c.zoom, c.pGap, c.pPad, c.pClip, c.sibling, r.bar, r.spill, r.lossPx, r.lossFrac,
                        r.cascade, r.slackPx, r.marginObscures, r.marginClipped, r.obscured);
                // 0 cases, 1 bar, 2 spill (no bar, content past the box), 3 bar steals content, 4 steals half or
                // more, 5 cascade, 6 margin strip lands on a neighbour, 7 margin strip clipped, 8 margin strip free
                int[] s = summary.computeIfAbsent(c.key(), k -> new int[9]);
                s[0]++;
                if (r.bar) s[1]++;
                if (r.spill) s[2]++;
                if (r.lossPx > 0.5f) s[3]++;
                if (r.lossFrac >= 0.5f) s[4]++;
                if (r.cascade) s[5]++;
                if (r.bar && r.marginObscures) s[6]++;
                if (r.bar && r.marginClipped) s[7]++;
                if (r.bar && !r.marginObscures && !r.marginClipped) s[8]++;
                if (r.bar && r.lossPx > 0.5f) {
                    example.putIfAbsent(c.key(), c.toString() + " -> " + r);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-52s %5s %5s %5s %6s %5s %5s %7s %7s %6s%n", "group", "n", "bar", "spill",
                "steals", ">=50%", "casc", "m:obsc", "m:clip", "m:free"));
        summary.forEach((k, s) -> sb.append(String.format("%-52s %5d %5d %5d %6d %5d %5d %7d %7d %6d%n",
                k, s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8])));
        sb.append("\nfirst stealing case per group:\n");
        example.forEach((k, v) -> sb.append(k).append("\n    ").append(v).append('\n'));
        Files.writeString(out.resolve("summary.txt"), sb.toString());
        System.out.println(sb);
    }

    private static Result run(Gui gui, Case c) {
        gui.zoom(c.zoom);
        Node scroller = (c.horizontal ? gui.row() : gui.column()).alignItems(AlignItems.STRETCH)
                .padding(len(c.sPad)).margin(len(c.sMargin)).gap(Length.dp(2));
        if (!"0".equals(c.sBorder)) {
            scroller.border(len(c.sBorder), dev.vexelray.canvas.Color.BLACK);
        }
        if (c.declared) {
            scroller.scroll(c.horizontal, !c.horizontal);
        }
        Length cross = len(c.sCross);
        if (c.horizontal) {
            scroller.width(Length.FILL).height(cross);
        } else {
            scroller.height(Length.FILL).width(cross);
        }
        // Twelve tabs' worth along the main axis: far more than the window, whatever the zoom.
        List<Node> items = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Node item = gui.text("Header " + i).textSize(Length.rem(c.textRem));
            Length childCross = len(c.childCross);
            if (c.horizontal) {
                item.height(childCross);
            } else {
                item.width(childCross);
            }
            items.add(item);
        }
        scroller.children(items.toArray(new Node[0]));

        Node before = gui.text("Above").textSize(Length.rem(1f));
        Node after = c.horizontal ? gui.box().width(Length.FILL).height(Length.grow(1f))
                .children(gui.text("Page body").textSize(Length.rem(1f)))
                : gui.box().height(Length.FILL).width(Length.grow(1f))
                .children(gui.text("Page body").textSize(Length.rem(1f)));
        Node parent = (c.horizontal ? gui.column() : gui.row()).width(Length.FILL).height(Length.FILL)
                .alignItems(AlignItems.STRETCH).gap(len(c.pGap)).padding(len(c.pPad)).clip(c.pClip)
                .scroll(false, false);
        if (c.sibling) {
            parent.children(before, scroller, after);
        } else {
            parent.children(before, scroller);
        }
        gui.root().children(parent);
        RetainedNode root = gui.frame(800f, 600f, ScrollbarRoomSweep::text);
        RetainedNode s = find(root, scroller.id());
        RetainedNode p = find(root, parent.id());

        boolean h = c.horizontal;
        boolean bar = h ? s.overflowX : s.overflowY;
        float sbPx = s.scrollbarPx;
        // Content past the scroller's border box along the main axis, unclipped: no bar, so it paints over whatever
        // lies past the box's end.
        float contentEnd = 0f;
        for (RetainedNode k : s.children) {
            contentEnd = Math.max(contentEnd, h ? k.x + k.w : k.y + k.h);
        }
        boolean spill = !bar && contentEnd > (h ? s.x + s.w : s.y + s.h) + 0.5f;

        // What the strip takes from each item's cross extent: its natural cross size against the room the view
        // leaves it, with and without the strip.
        float view = h ? s.viewH : s.viewW;
        float strip = bar ? sbPx : 0f;
        float worst = 0f;
        float worstFrac = 0f;
        for (RetainedNode k : s.children) {
            boolean fixedChild = !"FILL".equals(c.childCross) && !"AUTO".equals(c.childCross);
            float natural = fixedChild ? (h ? k.h : k.w)
                    : (h ? 1.25f * k.textSizePx : 0.55f * k.textSizePx * k.textString().length());
            float loss = Math.max(0f, natural - view) - Math.max(0f, natural - (view + strip));
            if (loss > worst) {
                worst = loss;
                worstFrac = loss / natural;
            }
        }
        // A second bar the first one caused: the cross axis overflows, but would not without the strip.
        boolean cascade = bar && (h ? s.overflowY && s.contentH <= s.viewH + sbPx + 0.5f
                : s.overflowX && s.contentW <= s.viewW + sbPx + 0.5f);

        // The strip drawn outside instead: just past the border box, on the side the reserved strip is on today.
        float mx = h ? s.x : s.x + s.w;
        float my = h ? s.y + s.h : s.y;
        float mw = h ? s.w : sbPx;
        float mh = h ? sbPx : s.h;
        // Room before the next thing along the parent's main axis: the sibling, or the parent's content end.
        float next;
        if (c.sibling) {
            RetainedNode a = find(root, after.id());
            next = h ? a.y : a.x;
        } else {
            next = h ? p.y + p.h - p.borderPx : p.x + p.w - p.borderPx;
        }
        float slack = next - (h ? s.y + s.h : s.x + s.w);
        List<String> hits = new ArrayList<>();
        collectOverlaps(root, s, mx, my, mw, mh, hits);
        boolean clipped = false;
        if (c.pClip || p.overflowX || p.overflowY) {
            float cl = p.overflowX || p.overflowY ? p.viewX : p.x;
            float ct = p.overflowX || p.overflowY ? p.viewY : p.y;
            float cr = cl + (p.overflowX || p.overflowY ? p.viewW : p.w);
            float cb = ct + (p.overflowX || p.overflowY ? p.viewH : p.h);
            clipped = mx < cl - 0.5f || my < ct - 0.5f || mx + mw > cr + 0.5f || my + mh > cb + 0.5f;
        }
        clipped |= mx + mw > 800.5f || my + mh > 600.5f;
        Map<Long, String> names = Map.of(before.id(), "above", after.id(), "after", parent.id(), "parent");
        List<String> named = new ArrayList<>();
        for (String hit : hits) {
            String nm = hit.startsWith("box") ? names.getOrDefault(Long.parseLong(hit.substring(3)), hit) : hit;
            if (!named.contains(nm)) {
                named.add(nm);
            }
        }
        parent.remove();
        return new Result(bar, spill, worst, worstFrac, cascade, slack, !hits.isEmpty(), clipped,
                String.join("|", named));
    }

    /** Every painted node outside {@code s}'s own subtree and ancestry that the rect lands on. */
    private static void collectOverlaps(RetainedNode n, RetainedNode s, float x, float y, float w, float h,
                                        List<String> hits) {
        if (n == s) {
            return;
        }
        boolean ancestor = isAncestor(n, s);
        if (!ancestor && n.w > 0 && n.h > 0
                && x < n.x + n.w - 0.5f && x + w > n.x + 0.5f && y < n.y + n.h - 0.5f && y + h > n.y + 0.5f) {
            hits.add(n.hasText() ? "text:" + n.textString().split(" ")[0] : "box" + n.id);
            return;
        }
        for (RetainedNode k : n.children) {
            collectOverlaps(k, s, x, y, w, h, hits);
        }
    }

    private static boolean isAncestor(RetainedNode a, RetainedNode s) {
        for (RetainedNode p = s.parent; p != null; p = p.parent) {
            if (p == a) {
                return true;
            }
        }
        return false;
    }

    private static RetainedNode find(RetainedNode from, long id) {
        if (from.id == id) {
            return from;
        }
        for (RetainedNode c : from.children) {
            RetainedNode hit = find(c, id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }
}
