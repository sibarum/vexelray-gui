# Where a scrollbar's room comes from

**Status: investigated 2026-10-26, proposed, not started.** This plan answers one question: when a box overflows,
whose room does its scrollbar take? Today the bar always takes it from the box's own content. Drawing it in the
margin instead takes it from the neighbour. Neither is safe by default. What follows is one rule that fixes every
content-sized scroller, and two categories that the rule cannot fix and that have to be handled differently.

The measurements come from two investigation tests, both skipped unless `-Dinvestigate=true` is passed:
`ScrollbarRoomSweep` (core, 18,432 layouts) and `TabsOverflowProbe` (widget, the real `Tabs`).

---

## 1. What happens today

`FlexLayout.layoutBox` decides overflow after the box already has its size. A bar along x then takes a strip
0.85 em thick out of the box's own height (`viewH = baseH - sb`). The content is clipped to what is left. The strip
is not hit-tested as content: `HitTest` descends only inside the viewport, and `InputDispatcher.grabScrollbar`
consumes a press there. So whatever the strip covers is both **hidden and dead to the pointer**.

`FlexLayout.measure` never includes the strip. So even a box sized to its content gets no extra room for its bar.
It measures exactly as tall as its content, and then the bar takes 0.85 em of that.

There is one more gap. `SENSIBLE_SCROLL` only asks whether the box is long enough **along** the bar's axis for a
thumb to travel. It never asks whether the box is thick enough **across** that axis to give up a strip. A row
1.25 em tall and 600 px wide, undeclared, gets a horizontal bar that takes 68% of its height.

## 2. MainFrame's tab bar, measured

`Tabs` declares a horizontal scroller with a fixed height of `2.25rem`, and its headers have `height(FILL)`.
`TabsOverflowProbe` with 30 tabs gives:

| zoom | bar | strip | header box | header padding (dp, top + bottom) | left for the text | a line needs |
| --- | --- | --- | --- | --- | --- | --- |
| 1.0 | 36.0 | 13.6 (38%) | 22.4 | 12.0 | **10.4** | 20.0 |
| 1.5 | 54.0 | 20.4 (38%) | 33.6 | 12.0 | 21.6 | 30.0 |
| 2.0 | 72.0 | 27.2 (38%) | 44.8 | 12.0 | 32.8 | 40.0 |

At normal zoom, half of each tab's line of text is under the bar, and that half of each tab cannot be clicked. The
padding is in `dp`, so it does not scale with zoom. That is why it is worst at 1.0.

The page starts at exactly the bar's bottom edge (`y = 36.0`, no gap). A bar drawn in the margin below the tab bar
would lie over the top 13.6 px of the terminal: its first line, more or less.

## 3. The sweep

`ScrollbarRoomSweep` builds a scroller with 40 labels (far wider than an 800 × 600 window). It puts the scroller in
a parent between a label above it and a `grow` body after it, then tries every combination of these:

- **orientation:** row (bar along x) or column (bar along y)
- **the scroller's cross size:** `AUTO`, `2.25rem`, `36dp`, `percent(20)`, `FILL`, `grow(1)`
- **the scroller's padding:** 0 or 0.5 rem
- **the scroller's margin:** 0 or 1 rem
- **scrolling:** declared or undeclared
- **each item's cross size:** `FILL`, `AUTO`, `2.25rem`
- **text size:** 1 or 1.5 rem
- **zoom:** 1 or 2
- **the parent's gap:** 0 or 1 rem
- **the parent's padding:** 0 or 1 rem
- **the parent's clip:** on or off
- **a sibling after the scroller:** present or absent

For each layout the sweep records:

- **Steals:** content the strip takes, measured as the items' natural cross size against the view with and without
  the strip.
- **Margin strip:** for a bar drawn just past the border box instead, whether it lands on another node (obscures)
  or falls outside the parent's clip or the window (clipped).

Every layout overflowed along its main axis, and every one got a bar, declared or not. Grouped by the scroller's
cross size. Each size is 3,072 layouts. A range spans the item sizes and the two sizes that share a line.

| scroller's cross size | bar takes content | takes half an item or more | margin bar obscures a neighbour | margin bar clipped |
| --- | --- | --- | --- | --- |
| `AUTO` (sized to content) | **100%**, both orientations | 33% (rows) | 12.5% | 0 |
| `2.25rem` / `36dp` (declared) | 75–100% for rows, 100% for columns | 25–38% (rows) | 12.5% | 0 |
| `percent(20)` | 6–25% for rows, 0–50% for columns | 0 | 12.5% | 0 |
| `FILL` / `grow` (the parent's room) | 0 for rows, under 4% for columns | 0 | 12.5% | 12.5% |

The margin columns are not chance. They are exactly the layouts where nothing left a clearance:

- **Obscures:** all 2,304 layouts with a sibling after the scroller, no scroller margin and no parent gap. Each one
  lands on that sibling. Every layout with a gap or margin of 1 rem cleared it, because 1 rem is wider than the
  strip.
- **Clipped:** all 768 layouts where a `FILL` or `grow` scroller is the parent's last child and the parent has no
  padding. The strip falls past the window or the parent's clip.

`grow` and `FILL` scrollers are by far the most common kind (bodies, lists, editors), and their defaults are gap 0
and padding 0. So for them, the margin bar is either clipped or on top of something.

### 3.1 So, the margin

Drawing the bar in the margin moves the problem from the content to the neighbour. It is safe only where a
clearance of at least 0.85 em is already there. A clearance somebody reserved for the bar is the same idea as
reserving room for it, with these extra costs:

- The strip is outside the box, so `HitTest.contains` rejects it before the scrollbar code ever sees the press.
- An ancestor's `clip(true)`, or an ancestor's own scroll viewport, cuts the strip.
- The bar shows up over a neighbour the author of the box never saw.

Where the clearance exists, growing the box into it gives the same picture with none of those costs. **The margin
is not the default to adopt.**

## 4. The rule: the strip is room the layout gives, never room it takes

There is one place the strip's room can come from without taking anybody's: **the layout makes the box bigger
across the bar by the strip, and the neighbours move.**

The concrete change is to `FlexLayout.measure`. A box that will overflow along an axis measures `sb` larger across
it. It is the same kind of measure as height-for-width:

- In a column, a child's width is resolved before its height is measured (`crossPre`). The width tells whether the
  content overflows along x, and the height adds the strip.
- In a row, a child's height is its parent's cross size (stretched or fixed), so it is known before the width is
  measured, and the width adds the strip for a vertical bar.

This cannot oscillate. Growing a box across the bar does not change its length along the bar, so the overflow that
caused the growth is still there afterwards. Wrapped text is not affected, because the content box keeps its width.

Predicted, and step 1 measures it: the `AUTO` groups in the sweep lose nothing, in both orientations, and nothing is
obscured, because the sibling after the scroller is a `grow` body and gives up the strip. A parent that is itself
fixed and already full overflows by the strip instead. That case is real and visible, and it goes to the parent's
own bar or diagnostic. The rule also closes the gap in §1: a short, undeclared row that overflows becomes taller
instead of being squashed.

The rule can only act where the box's cross size comes from its content. The other two sources need different
answers.

### 4.1 Which boxes get the room they ask for

Put another way: a horizontal bar should make its box taller exactly when the box is given whatever height it asks
for, and the things after it move down to make room. A vertical bar should make its box wider under the same
condition, read sideways. "Asks for" means `FlexLayout.measure`, so these are the paths along which a size that
`measure` returns is the size the box gets. Defaults are `width(AUTO)`, `height(AUTO)` and `alignItems(STRETCH)`.

**Height, which is what a horizontal bar needs:**

| the box is | its height is `AUTO`, and it gets |
| --- | --- |
| a child of a **column** | **what it asks for**, any alignment. Nothing shrinks a child below its measured size, so the siblings after it move down. If the column runs out, the column scrolls or spills. |
| a child of a **row**, `alignItems` `START`, `CENTER` or `END` | **what it asks for** (height for its width). The row's other children don't move. Only a row that is itself sized to its content grows to hold it. |
| a child of a **row** under `STRETCH` (the default) | **the row's height**. That is what it asks for only when the row's own height is `AUTO`: a content-sized row is as tall as its tallest child. So a request goes through any number of content-sized rows until it reaches a column, where things move. |
| **docked** to its parent | what it asks for, capped at the parent's height. The band it makes pushes the parent's flow down, so it acts like the first child of a column. |
| **floating** | what it asks for, capped at the parent's height. Nothing moves, so there is nowhere for a strip to come from except inside it. |
| the **root** | the window, or the canvas `Gui.minSize` gives it. Never asks. |

Never what it asks for, on either axis: a fixed length (`em`, `rem`, `dp`, `vw`, `vh`), `percent`, `grow`, and
`FILL`. Along the parent's main axis `FILL` is `grow(1)`, which starts from 0 and takes a share of what is left.
Across it, `FILL` takes all there is.

**Width, which is what a vertical bar needs, is the same table turned on its side.** A child of a row gets its
measured width, and the siblings after it move right. A column child gets it under `START`, `CENTER` or `END`. Under
`STRETCH` it gets the column's width, which is what it asked for only if the column is itself sized to its content.

**So the four cases are:**

| the box | the bar |
| --- | --- |
| is free along both axes | never overflows, so has no bar |
| is free across the bar only | is the rule above: the box grows by the strip, and its neighbours move (category A) |
| is held along both axes by its parent | takes its strip from inside (category B) |
| has a declared size across the bar | takes its strip from inside (category C) |

**The width side matters less:** a column stretches its children by default, so most lists and editors are held
across, which is category B. The height side is where it matters: a row of anything inside a column, which is
`Tabs`, toolbars and chip lists.

Because these are the paths a `measure` result travels, putting the strip into `measure` covers all of them, and
nothing else. No list of containers has to be kept.

## 5. The three categories

### A. The cross size comes from the content (`AUTO`): the rule above fixes it automatically

No application changes. The cost is that the layout moves when overflow starts: the box grows by 0.85 em, and the
content below it moves down by the same amount. Content changing (a tab added, a window narrowed) is what triggers
that. Hover never does, so the rule against things moving on hover is not affected.

### B. The cross size comes from the parent (`FILL`, `grow`, a stretch, a percentage): leave it as it is

This box is a viewport. Its content is laid out in whatever room is left, so taking the strip from the inside is
correct. The sweep confirms it: no row stole anything, and under 4% of columns did (narrow labels that wrapped one
more time). Where it does steal, it is because the parent gave the box less room than one item's natural size. A
`percent(20)` box at 2× zoom with 1.5 rem text is the only example in the sweep. That is a sizing mistake in the
application, not something the bar caused.

Possible later step: a `Diagnostics` line when the room left after the strip is smaller than the content's natural
cross size, like `UnaskedScroll` (scrolling.md §5).

### C. The cross size is declared (`rem`, `dp`, `em`) and the box scrolls across it: the declaration is the bug

The application has promised the box's size to its neighbours, and its content is sized to fill it. The strip
cannot come from either side, and the layout must not decide on its own to break the declared size. This is where
MainFrame's tab bar is. Two parts:

1. **The layout reports it.** It uses the same mechanism as `NestedScroll` and `UnaskedScroll`: once per run, it
   names the box and the length it declared, and says that its bar takes N px of content. The fix it suggests is to
   size the box to its content and give the items the minimum size instead.
2. **The widgets that do this move to category A.** For `Tabs`, the bar becomes `height(AUTO)` and each header
   takes the fixed `height(rem(2.25))` instead of `FILL`. A row sized to its content is as tall as its tallest child,
   so the bar is 2.25 rem as before, and the rule makes it 0.85 em taller while it overflows. (There is no per-node
   minimum size in core; `Gui.minSize` is the window's.) `tab-overflow.md`'s `menu()`
   and `wrap()` stay the better ends for `Tabs`. This step makes the scrolling version correct until they exist.

One alternative was considered and not recommended: treat a declared cross size on a scroller as a minimum. It fixes
`Tabs` without changing it, but it changes what `height(rem(x))` means for every scroller, without any warning.

## 6. Order of work

1. **The rule in `FlexLayout.measure`.** Tests: an `AUTO` row with overflowing content is `content + sb` tall, and
   its items keep their natural height. An `AUTO` column is `content + sb` wide. A `grow` sibling gives up the
   strip. The existing `SensibleScrollTest`, `NestedScrollTest` and `UnaskedScrollTest` pass unchanged.
   `ScrollbarRoomSweep` re-run shows 0 losses in every `AUTO` group.
2. **The category C diagnostic,** with a test that a `rem`-height scroller that overflows is reported once.
3. **`Tabs` to a content-sized bar of fixed-height headers.** `TabsOverflowProbe` shows the header keeping 36 px at every zoom
   while the bar grows. Plus an `ottermate` shot of MainFrame with enough tabs to overflow.
4. **Optional:** the category B diagnostic.

Steps 1 and 2 are the only core changes. Step 3 is the only change any application will see.
