# Scrolling: where a scrollbar comes from

Read this when a window scrolls where you did not expect it to, or shows two scrollbars side by side. The short
answers: an application never asks for a scrollbar, so searching its code for one finds nothing (§1); the whole
window scrolls when it is smaller than `Gui.minSize` (§2); and two bars along one edge are two containers each
doing §1, one inside the other, which the layout now reports (§3). §4 is how to see all of it from outside.

Code: `FlexLayout.layoutBox` decides, `TreeRenderer.drawScrollbars` draws, `Metrics.measure` sets the canvas,
`NestedScroll` reports.

## 1. A scrollbar is overflow, not a widget

Every box scrolls along an axis when its content is longer than its content box along it. Nobody asks: there is no
scroll view, and a search of an application for `scroll` usually finds nothing to do with the bar on screen. The
bar is renderer chrome, drawn by `TreeRenderer` for any node the layout marked `overflowX` / `overflowY`.

- **Only where a bar fits.** A box that never declared scrolling scrolls along an axis only if its content box is
  at least four scrollbar thicknesses long there (`FlexLayout.SENSIBLE_SCROLL`, about 3.4 em): a chip, a button
  or a fixed-height row overflows instead, unclipped. `Node.scroll(allowX, allowY)` declares an axis either way,
  and the declaration wins.
- **A bar takes room.** It reserves a strip 0.85 em thick on the other axis, and the content is measured again in
  what is left (narrower wrapped text is taller).
- **A `grow` child is the room that is left.** Its basis is 0 and it takes a share of the leftover space, so a body
  of `height(Length.grow(1f))` between a title bar and a status bar is exactly the space between them, and whatever
  it holds beyond that scrolls *in the body*. That is usually what you want, and it is the first half of §3.

## 2. Why the whole window scrolls

The root is always the window: what is docked to it (a title bar) stays put. But its flow is laid out on a
**canvas**, which is the window or `Gui.minSize`, whichever is larger (`Metrics.measure`). When the canvas is
larger, the root's content is longer than the root, and by §1 the root scrolls, carrying everything in its flow
with it, the status bar included.

A window ends up smaller than its minimum more often than it sounds:

- **Nothing stops it.** `Gui.minSize` is a floor for the layout, not for the window: `VexelApplication` sets only
  this one, so a window can be dragged, restored or `ottermate resize`d below it.
- **A size saved from an earlier run** opens at that size, whatever the minimum is now.
- **A screen shorter than the first-run size.** The window manager fits the window to the work area: a 940 px
  window on a 998 px screen opens about 860 px tall.

So a minimum is not a way to make sure content fits. It decides what the UI is laid out on when it does not, and
the price is that the root scrolls.

## 3. Two scrollbars side by side

The symptom: two bars along one edge of the window, the inner one shorter, each scrolling part of the same content.
The cause is §1 and §2 together, or §1 twice: a scroller that fills its parent's view, inside a parent that scrolls
along the same axis.

The common case is a body that grows and scrolls its content (§1), under a `Gui.minSize` sized to the *content*
rather than to what cannot scroll. Every window shorter than that minimum lays the root out on the taller canvas
(§2), so the root scrolls as well. The VexelRay Inspector did this with a 58em minimum and its 940 px window on a
998 px screen. Both decisions look right on their own, and the result looks deliberate, so nothing would have said
otherwise; the layout now does, once per axis per run, through `Diagnostics` on the `vexelray.diag` logger:

```
WARN vexelray.diag - one scrollbar along y — box 2 scrolls inside box 1, the root, and fills its height (880px
in a 860px view), so their two scrollbars stand side by side. The root scrolls because it is laid out on a canvas
928px tall in a 860px window: Gui.minSize is larger than the window. Size the minimum to what cannot scroll, and
box 2 is the one scroller. (ids are ottermate tree's; docs/reference/scrolling.md §3)
```

The ids are the refs `ottermate tree` prints at the start of each line (§4). The fixes, for that case and in
general:

- **Size the minimum to what cannot scroll:** the title bar, the status bar, anything else fixed, and a body worth
  scrolling. Not the content's height; the body already handles that.
- **Otherwise, choose one scroller.** Give the inner one only the room left beside the rest (`Length.grow`), or stop
  one of them scrolling along that axis with `scroll(...)`. A scroller that is *shorter* than its scrolling parent's
  view, such as a list in a scrolling page, is not reported: its bar is beside its own rows.

## 4. Seeing it from outside

`ottermate tree` lists every scroller, named or not, with `scrolls x`, `scrolls y` or `scrolls x y` after its box.
Two lines on the same axis, the inner one as long as the outer's view, are §3. The Inspector's window under its
58em minimum (the inner box is 14 px narrower than the window because the root's bar takes that strip):

```
1 box [0,0 1040x860] scrolls y
  2 box [0,32 1026x880] clipped to [0,32 1026x828] scrolls y
```

To photograph a layout, shoot **below** its minimum as well as at it. At exactly the minimum the canvas is the
window and the root never scrolls, so a shot at the minimum cannot show §3; one 5 em shorter can:

```
resize 51emx35em      # the minimum
shot at-minimum.png
resize 51emx30em      # below it: what a small screen or a saved size gives
shot below-minimum.png
```
