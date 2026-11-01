# Scrolling as elements: `Scroller`, `ScrollRange`, `Scrollbar`

**Status: proposed 2026-10-26; steps 0–3 and 6 done (the revert; `Scroller` in core; `vexelray-gui-scroll`; the framework widgets and the demo moved, all but the multi-line `TextField`; `collapsing()`); names settled (§8).** This replaces automatic scrolling, and it supersedes
[scrollbar-room.md](scrollbar-room.md), whose step 1 this plan reverts.

Today any box scrolls when its content is longer than it is. The layout decides that after sizing the box, the
renderer draws a bar the tree does not contain, and the bar's room comes out of the content (scrollbar-room.md §1).
Every fix so far has added a special case around that one fact:

- `SENSIBLE_SCROLL`, so that short boxes don't get a bar;
- the `NestedScroll` and `UnaskedScroll` diagnostics, for scrollers nobody meant to make;
- a viewport rule in `HitTest`, and `grabScrollbar` in `InputDispatcher`, for a bar that has no node;
- the strip predicted in `FlexLayout.measure` (scrollbar-room.md step 1), for a bar that takes no room in layout.

The code also shows the default is backwards. Across `vexelray-gui` and its sibling apps there are **167
`scroll(false, false)` calls, written only to opt out of scrolling, against 27 that opt in**. That counts the
`src/main` trees only.

This plan makes scrolling three ordinary things that a developer places on purpose:

- **`Scroller`** masks.
- **`ScrollRange`** is what can be scrolled.
- **`Scrollbar`** is an element that shows a range and moves it.

None of them needs the layout to know what a scrollbar is.

---

## 1. The rule: overflow is not scrolling

A box whose content is larger than it is **overflows**, and that's all that happens. The content is laid out where
flex puts it and painted there, unclipped. Nothing scrolls and no bar appears. It is a mistake, and the framework
says so: once per box per run, through `Diagnostics` on `vexelray.diag`, naming the innermost part that reaches
past the box. That is the `UnaskedScroll` warning (scrolling.md §5) generalised to both axes, with "nothing masks
it" in place of "nobody asked".

Undefined for the developer, then, but one fixed behaviour from the framework, and a loud one. Overflow that
nobody planned is visible on screen and in the log, instead of turning into a scrollbar that reads like a style
choice.

`clip(true)` stays as it is: a mask with no scrolling, for a box that means to cut its content off. Since it
cuts its content off, that content is no room anything must make for it: short of room, a clipped box gives way
like a `Scroller` does (§2.1), which is what keeps a long window title from pushing the close button off the window.

## 2. `Scroller`: the mask

`gui.scroller(content)` is a box with **exactly one child**. It:

- **masks** its child to its own box, for painting and for hit-testing. `HitTest` descends into it only inside its
  rectangle, which is the rule `clip(true)` follows already;
- **moves** its child by its offsets, along x and y;
- **publishes** two `ScrollRange`s, one per axis (§3), each measured from its child against its own box;
- **takes the scroll gestures** that land on it: the wheel (through tactroller, with `ScrollGlide` as today), and
  `scrollIntoView`, `scrollTo`, `scrollToEdge` and `lock(...)`. Every one of these works with or without a bar.
  On a trackpad nobody needs a visible bar to scroll, and that has to keep working.

Each axis can be turned off: `scroller(content).axes(false, true)` scrolls only vertically, and along x it lets
the child overflow, which §1 reports. A table that scrolls sideways asks for x. A page asks for y.

### 2.1 How big a `Scroller` is

This is the one sizing rule the plan adds:

> **A `Scroller` measures as its content and gives way to the room it is given, down to nothing.** Across an axis
> its parent sizes, it fills, like `alignItems(STRETCH)`. Along the parent's main axis, its basis is its content's
> size, and where the parent has less room than its children ask for, the `Scroller` gives up the difference
> first.

That is CSS's `flex-shrink` with `min-size: 0`. It's needed because no child in `FlexLayout` shrinks today, and
shrinking is exactly what a `Scroller` exists to do. Where two `Scroller`s share a main axis, they give way in
proportion to their content.

**As built (step 2), it reaches through the boxes around a `Scroller`.** Each `AUTO` box has a *least* size: its
measure with every `Scroller` in it counted as nothing along the axes it scrolls. Short of room, every `AUTO`
child gives up the same share of what it can (its measure less its least), so a `Scroller` wrapped in a
content-sized box gives way through it, and `Scrollbars`' own wrapper works in any parent. That is CSS's
min-content floor. A box with no `Scroller` in it has least == measure, so nothing that existed before moves. Inside
a box that scrolls along the axis (a `Scroller`, or an old-model box that *declared* `scroll` there), nothing gives
way: its room is unbounded, and what does not fit is what it scrolls.

With that rule, the push and shrink behaviour from scrollbar-room.md comes from ordinary layout. The bar is a
sibling with a size:

```
column (height AUTO)                      column (height = the window)
├─ Scroller (x)  ← headers row            ├─ title bar
└─ Scrollbar.horizontal                   ├─ Scroller (y)  ← page
                                          │  (gives way: it is the room left)
                                          └─ status bar
```

- **Content-sized, on the left (the tab bar).** The parent column is `AUTO`. The `Scroller`'s width is the
  column's, so the headers overflow it along x. Its height is the headers'. The bar adds 0.85 em below, so the
  column is headers plus bar, and the pages under it move down. Nothing measured the bar in advance: it's a child
  with a height.
- **Sized from outside, on the right (a page).** The column is the window's height. The `Scroller`'s basis is the
  page's length, more than the room there is, so it gives way to what the title and status bars leave. A vertical
  bar placed beside it in a row takes its width from the row, and the `Scroller` is narrower by that much.

`FlexLayout.measure` goes back to what it was before scrollbar-room.md step 1.

### 2.2 What moves, and why the bar is not inside it

The offset moves the `Scroller`'s child, all of it. A bar placed inside that child would scroll away with the
content. The alternative would be to exempt bars from the offset, which is a special case of the kind this plan
removes. So the bar lives **outside the box it scrolls**: beside it, or anywhere else in the tree. It finds its
range by reference (§4), not by searching ancestors.

"A scrollbar inside an overflow container", as first proposed, becomes "a scrollbar next to a `Scroller`, both
inside an ordinary box". The usual pairing has a helper (§4.2), so placing them by hand is the exception.

## 3. `ScrollRange`: what can be scrolled

One axis of something that can be looked at a part at a time:

```java
record ScrollRange(float content, float view, float offset) { ... }   // px; 0 <= offset <= content - view
```

It is published as an atchung `State<ScrollRange>`, and offset writes go back through a write port, so one source
has two halves:

```java
interface ScrollSource {
    State<ScrollRange> range();
    void scrollTo(float offset);     // a request; the owner clamps and publishes
}
```

- **A `Scroller` is a source**, one per axis. Its writes are the `Mutation.ScrollTo` that `Node.scrollTo` posts
  today, so a bar drag, the wheel and `scrollIntoView` resolve in the same frame as the layout, as they do now.
- **Anything else can be a source.** That covers the first use that doesn't involve layout:
  - a 3D viewport publishes how far it can orbit or pan, and moves its camera when asked;
  - MainFrame's terminal publishes its scrollback;
  - a multi-line text leaf publishes its lines.

  A bar shows and drives each of these exactly as it does a `Scroller`.

`ScrollLock` moves onto the source: `overflow.lock(BOTTOM)`. Detach on scrolling away and re-attach on return work
as they do today.

Virtualisation reads the range instead of the layout's `viewH`/`viewW`. `ListView`, `TreeView`, `Table` and
`TextField`'s lines-per-page all ask "how much can be seen", and `view` is the answer.

## 4. `Scrollbar`: an element

`Scrollbar.horizontal(gui, source)` and `Scrollbar.vertical(gui, source)` make a node, sized like any other node:

- a horizontal bar is `width(FILL)` and `height(0.85em)`, the same thickness as today's strip;
- a vertical bar is `width(0.85em)` and `height(FILL)`.

It draws its track and thumb as a `Picture`, from the range. Its interaction is ordinary handlers on its own node,
through tactroller:

- drag the thumb (under pointer capture);
- press the track to page;
- the wheel over the bar.

There's nothing in `HitTest`, `InputDispatcher` or `TreeRenderer` that knows it exists.

### 4.1 When there is nothing to scroll

The bar **keeps its room**, and draws nothing in it. That is the default, for three reasons:

- it is the no-hover rule's reasoning: reserve the space, and show the bar on overflow;
- the layout never depends on its own result;
- an app that wants the bar gone says so.

(As first built in step 2 it drew an empty track. Step 3 made that nothing, because every list and lane in the
framework would otherwise have grown a permanent empty track that old-model scrollers never had.)

`Scrollbar.collapsing()` takes no room while the range fits: the bar is hidden, and shown once its range scrolls,
**a frame after** the layout that produced the range, rather than by laying out twice. It settles in one step for a
bar across the axis it shows (a horizontal bar's height cannot change a row's width), and for a vertical bar beside
content that only grows taller as it narrows, which is what wrapped text does; content that would fit only once
narrowed could flip, and such a box should keep the room. `Tabs` uses it (step 3), which is what this redesign was
first asked for: the overflowing bar grows by its scrollbar and the pages move down.

### 4.2 The usual pairing

```java
Node page = Scrollbars.vertical(gui, content);        // row[ Scroller(y) grow , Scrollbar.vertical ]
Node bar  = Scrollbars.horizontal(gui, headersRow);   // column[ Scroller(x) , Scrollbar.horizontal ]
Node both = Scrollbars.both(gui, grid);               // the corner square is a plain box
```

`Scrollbars` returns an ordinary box, and its parts can be reached for styling the same way `Tabs.bar()` is. It is
composition, not a new kind of node.

## 5. Where it lives

Following the separate-module rule:

- **`vexelray-gui-core`**: `ScrollRange`, `ScrollSource` and `Scroller`, in `dev.vexelray.gui.core` beside
  `Node`, so `Scroller`'s constructor and its scroll watch stay package-private. Masking, the offset and the sizing
  rule are layout, `Clip` and `HitTest` work, and core is where those live. This is the one part that cannot be
  its own module.
- **`vexelray-gui-scroll`** (new): `Scrollbar` and `Scrollbars`. It depends on core and `vexelray-gui-draw`
  only, and `vexelray-gui-widget` depends on it.

## 6. Order of work

Each step leaves every repo compiling and every test passing. The old model and the new one live side by side
until step 5.

0. **Revert scrollbar-room.md step 1.** `git revert 75bfae2` takes back the strip in `measure`, its seven tests
   and the doc lines. The investigation tests from 4b4ce1b (`ScrollbarRoomSweep`, `TabsOverflowProbe`) stay until
   step 5, because they measure the model being replaced. scrollbar-room.md is marked superseded. Its findings
   stay as the record of why: the margin result (§3.1) is why the bar is in layout and not in a margin.
1. **Core: `ScrollRange`, `ScrollSource`, `Scroller`.** *Done 2026-10-26.*
   - The sizing rule (§2.1).
   - Masking and the offset.
   - Wheel routing: the nearest `Scroller` under the pointer whose range can move on that axis.
   - `scrollIntoView`, `scrollTo`, `scrollToEdge` and `lock` on `Scroller`. `Scrolling`'s nested solve is
     unchanged, except that "a scrolling ancestor" now means a `Scroller`.
   - A `Scroller` turns the old implicit scrolling off for itself and its child, so the two models never stack.
   - Tests: the two §2.1 cases with no bar; the shrink split between two `Scroller`s; reveal through nested
     `Scroller`s.

   **As built.** A `Scroller` is a node with one prop the layout reads, `PropKey.SCROLLER`, made by
   `gui.scroller(content)`. It is a clipped column that declares both axes, so it reuses the old model's offsets,
   clip, wheel routing, reveal and lock unchanged; what differs is in `FlexLayout`, and nowhere else:
   - its strip is zero, so `layoutBox` reserves nothing and `TreeRenderer` and `InputDispatcher` find no bar to
     draw or grab;
   - `mainSizes` lets an `AUTO` `Scroller` give way, in proportion to its content, and `crossSize` and `place`
     clamp a non-stretched one to the cross room;
   - along an axis a `Scroller` scrolls, its child's basis is its content even when it is `grow`, and a stretched
     child is at least its content's width;
   - its child never scrolls without declaring it.

   Ranges are published from `ReadModels.onScroll`, a per-node watch like `onResize` that also fires when only the
   offset moves; each axis commits to its own `State<ScrollRange>` inside the publish. Offsets are
   `Mutation.ScrollTo` with NaN for the axis left alone, and the two axes no longer share a coalescing cell, so
   moving x and y in one frame keeps both. Only a vertical offset detaches a bottom lock.

   `ScrollerTest` has eleven tests: the three layouts of §2.1 (a content-sized box growing by its bar, a page giving
   way between two bars, a vertical bar beside the `Scroller` in a row), the proportional split, room to spare, a
   filling child, no nested scroller in the child, both ranges and per-axis moves, a disabled axis, reveal
   through nested `Scroller`s, and the wheel with no bar. With give-way off, four fail; with the strip back on,
   three do.

   **Known limit, lifted in step 2.** As first built, only the `Scroller` gave way, not a content-sized box around
   it, which is CSS's `min-width: auto` trap. `Scrollbars` hit it at once, since its wrapper is exactly such a box;
   §2.1's "as built" note is the general fix.
2. **`vexelray-gui-scroll`: `Scrollbar`, `Scrollbars`.** *Done 2026-10-26.*
   - Tests:
     - the tab-bar case grows by the bar's height and moves its neighbour;
     - the page case gives the bar's width up from the `Scroller`;
     - drag, track paging and the wheel;
     - a bar bound to a plain `ScrollSource` with no layout behind it.

     The first two are ScrollbarRoomTest's cases again, now passing with no measure change.
   - A demo chapter: a `Scroller` with each kind of bar, and a bar driving a source that is not a `Scroller`.

   **As built.** The module is `dev.vexelray.gui.scroll`, on core and draw only:
   - `Scrollbar.horizontal/vertical(gui, source)` is a box `THICKNESS` (0.85rem) across and `FILL` along, with
     role `scrollbar`. It draws an outlined track and a thumb as a `Picture`, in the old strip's colours and
     proportions (`Thumb` holds the arithmetic), redrawn when the range or the box changes. It keeps its room
     with nothing to scroll. A press on the thumb takes hold where it was pressed, a press on the track pages
     once toward it, and the wheel over the bar scrolls its source. `close()` stops following the source.
   - `Scrollbars.vertical/horizontal/both(gui, content)` is a handle with `node()`, `scroller()` and the bars as
     `Optional`s. `both` puts a plain square where the bars meet.

   Three changes in core came with it:
   - **The least size** (§2.1), which lifts step 1's known limit.
   - **Declared scrolling holds.** A box that declared `scroll` along an axis is a scroller there, so nothing in
     it gives way. The demo found this: its stage squeezed a chapter of `Scroller`s to fit instead of scrolling.
   - **`Gui.onWheel(node, handler)`**, with `WheelEvent(dx, dy)` in px. The wheel over a node that asked for it
     goes to that node instead of the nearest overflowing ancestor. It is fed from tactroller's input topic, like
     `onDrag`. A bar is not inside what it scrolls, so it needs this, and so does any other source: the demo's
     panorama takes the wheel over its own drawing.

   Tests: `ScrollbarTest` (7: both §2.1 cases with the real bar, a thumb drag that keeps the grab under the
   pointer, track paging both ways, the wheel over the bar, no thumb with nothing to scroll, and a bar driving a
   source with no layout behind it) and `ThumbTest` (4). `ScrollerTest` gains two: a wrapper giving way through its
   `Scroller`, and nothing giving way inside a box that declared `scroll`.

   The demo's **Scrolling** chapter (`ScrollChapter`) shows a strip of tabs, a page, a grid that scrolls both
   ways, and a bar driving a panorama that pans by redrawing, with no layout or `Scroller` behind it. The chapter's
   own page is `Scrollbars.vertical`. It was shot with ottermate: the tabs keep their height with the bar under
   them, the wheel scrolls the page and the grid, and a track press and the wheel pan the panorama.
3. **Framework widgets move** (§7.1), with `Tabs` first and checked with `TabsOverflowProbe`. Then the demo.
   *Done 2026-10-26, except the multi-line `TextField`.*

   **As built.**
   - **`Tabs`.** The header row is in a `Scrollbars.horizontal` whose bar is `collapsing()`. Headers are a fixed
     `2.25rem`, and the strip is as tall as they are. `bar()` is still the header row (MainFrame appends its `+`,
     passthrough switch and rename field there). The new `strip()` is the row with its scrollbar, and is what to
     hide. `TabsOverflowProbe` with 30 tabs:

     | Zoom | Header height | Text box (line needs) | Bar | Page starts |
     | --- | --- | --- | --- | --- |
     | 1 | 36 (was 22.4) | 24 (20; was 10.4) | at 36, 13.6 tall | at 49.6 (was 36, under the bar) |
     | 1.5 | 54 | 42 (30) | at 54, 20.4 tall | at 74.4 |
     | 2 | 72 | 60 (40) | at 72, 27.2 tall | at 99.2 |

   - **`ListView` (and so `Table`'s body), `TreeView`, `Deck`.** Each keeps its old scroller's role inside, now
     the `Scroller`'s node: its box, view and offset (virtualisation and paging still read them from its layout,
     which is the same frame as the range), where drags start and land, and its drop overlay. Rows go in the
     content the `Scroller` moves. What the application places is the `Scrollbars` wrapper:
     - `ListView.node()` carries the list's role, keys and focus;
     - `TreeView` keeps them on its framed root, and `scroller()` is still "the box the rows scroll in";
     - `Table` solves its columns against `ListView.view()`, the body less its bar;
     - `Deck.node()` is the wrapper, and `Board` works in the `Scroller`'s box.
   - **`Inspector`, `Rail`'s panel body, `KeymapEditor`'s list** became a `Scrollbars.vertical` each.
   - **Demo.**
     - The seven opt-ins are `Scrollbars.vertical`: the Icons, Image, Motion and Window pages, the Drag
       chapter's archive, the Focus chapter's scroll viewport, and the gallery's chapter rail. The Scrolling
       chapter's own page already was.
     - The console is a `Scroller` locked to its bottom.
     - `Shell` hides `pages.strip()`.
     - The Scrolling chapter's first section is now a real `Tabs`, starting with four tabs and an "Add ten tabs"
       button.

   No `scroll(..., true)` or `scrollLock` is left in this repository outside core.

   Shot with ottermate:
   - the real `Tabs` with four tabs (no bar, no room for one) and with fourteen (bar under the headers, page
     moved down, every header whole);
   - the Trees, Cards and Tables chapters (a lane that overflows shows its bar, and the others keep the room
     empty);
   - a 480 px window. There the Tables chapter's table card, which is `height(FILL)`, so `grow` with a basis of 0,
     gets no height. That was so before this step, and `grow` children are outside the give-way rule.

   **Not moved: the multi-line `TextField`.** It is a text leaf with its own scrolling in `layoutTextLeaf` and the
   renderer's strip, reading `viewH` for lines per page. Making it a `ScrollSource` over its lines with a bar
   composed beside it is its own piece of work, and has to land before step 5 deletes the strip.
4. **Sibling apps move** (§7.2), in dependency order. Each is installed and shot with ottermate. MainFrame with 30
   tabs at zoom 1, 1.5 and 2 is the acceptance shot: every title whole and every header clickable.
5. **Flip the default.** Delete:
   - `Node.scroll` and `PropKey.SCROLL_X`/`SCROLL_Y`, and with them the 167 opt-outs;
   - `SENSIBLE_SCROLL` and the overflow and strip decision in `layoutBox`;
   - `TreeRenderer.drawScrollbars`;
   - the viewport rule in `HitTest` and `grabScrollbar`;
   - `NestedScroll` and `UnaskedScroll`, replaced by the overflow report (§1);
   - `NodeLayout.viewW`/`viewH`, and the two investigation tests.

   Rewrite [reference/scrolling.md](../reference/scrolling.md) from scratch. Done when a repo-wide search for
   `scroll(` finds only `Scroller`, `ScrollSource` and the bar.
6. **`Scrollbar.collapsing()`** (§4.1). *Done early, in step 3, for `Tabs`: shown a frame late rather than by laying out twice.*

## 7. What moves

### 7.1 In this repo

| Where | Today | Becomes |
| --- | --- | --- |
| `Tabs` bar | `scroll(true, false)`, a declared `2.25rem` height, headers `FILL` | `Scrollbars.horizontal` around the header row. The bar becomes `AUTO` and headers a fixed `2.25rem` (scrollbar-room.md §5C), so the bar is headers plus scrollbar. The indicator sits at the headers' bottom, not the bar's |
| `ListView`, `TreeView`, `Table` body | scroll y, `viewH`/`viewW` for virtualisation and paging | `Scrollbars.vertical`, with virtualisation reading `range().view` |
| `Deck`, `Inspector`, `Rail` panel body, `KeymapEditor` | `scroll(false, true)` | `Scrollbars.vertical` |
| `TextField`, multi-line | a text leaf with its own bar and `viewH` | a `ScrollSource` over its lines, with no bar of its own. The text area composes a `Scrollbar` beside it. A single-line field keeps scrolling with its caret and has no bar |
| Window root, under `Gui.minSize` | the root scrolls on a canvas larger than the window (scrolling.md §2) | the root never scrolls. An app that wants a small window to scroll wraps its content in `Scrollbars.both`. Otherwise the overflow is reported |
| `Automation` / `ottermate tree` | `scrolls x/y` from `overflowX/Y` | a `Scroller` line with its ranges, and a `Scrollbar` line naming its source |
| Demo | 7 opt-ins (Drag, Focus, Icons, Image, Motion, Window chapters, `Shell`), `Console`'s bottom lock | `Scrollbars.vertical`. `Console` gets `lock(BOTTOM)` |
| 101 `scroll(false, false)` here (66 more in the siblings) | opting out | deleted in step 5 |

### 7.2 Sibling apps

| Repo | Opt-ins today | Notes |
| --- | --- | --- |
| vexelray-framework | `About` hero | `TactrollerInputBackend` keeps setting `scrollGlide`. `AboutTest` and the acceptance run check for the §1 report instead of the sideways one |
| mainframe | `OpenDialog` grid; `WorkspacesPage` `scrollIntoView` | gets `Tabs` for free in step 3. `TerminalView` can publish its scrollback as a `ScrollSource` and get a real bar, a later change of its own |
| vexplore | `Dock`, `RailView` | |
| text-editor-vexel-demo (VexelRay IDE) | `Formatted`, `Preview`; `Navigator` `scrollIntoView` | |
| calculator-vexel-demo | `SettingsWindow`, the tape (`lock(BOTTOM)` with `scrollToEdge`) | |
| vexelray-sim-fluid | `ControlPanel`, `Sidebar` | |
| pontif-framework | `BoxWalker`'s `Scrolls` atom | the atom becomes a `Scroller` with an optional bar. This is a change to its schema |

Any box in those apps that scrolls today *without* declaring it will overflow after step 5. The §1 report names
each one on the first run that reaches it. Step 4 runs each app through its acceptance path for exactly that
reason.

## 8. Names (settled 2026-10-26)

The core node was first drafted as `Overflow`. That name was taken twice, and it was wrong inside this plan too:

- **`dev.vexelray.framework.api.Overflow`**, the policy for a full mailbox, is imported by application code, not
  only by the framework's processor: `vexelray-sim-fluid-demo`'s `Physics.java` and the acceptance witnesses'
  `Components.java`. Those are the files where an app wires itself together, and the same apps build their UI, so
  a file with both in view would be ordinary. It's `Ramp` again: one word, two meanings, one import away from the
  wrong one.
- **[tab-overflow.md](tab-overflow.md)** planned a `Tabs.Overflow` policy seam, not yet built.
- **§1 uses "overflow" for the mistake**: content larger than its box, drawn unclipped and reported. A node named
  after the problem would make the report read "box 8 overflows; wrap it in an `Overflow`".

So:

| Was | Is | Why |
| --- | --- | --- |
| `Overflow` (core node) | **`Scroller`** | Says what it does: it scrolls its child, by the wheel and by `scrollIntoView`, with or without a bar. Free in every repo, and belongs with `ScrollRange`, `ScrollSource`, `Scrollbar` |
| `Scroll` (helper) | **`Scrollbars`** | `Scroll` sits too close to `Scroller`, and `Scrolling` is core's reveal solver. `Scrollbars.vertical(gui, content)` reads as "this content, with a scrollbar", as `Collections` goes with `Collection` |
| `Tabs.Overflow` (tab-overflow.md) | **`Tabs.Crowding`** | What a bar does when it has more headers than room. Renamed in that plan before its seam exists |

"Overflow" stays the name of the **condition**: the §1 report, and "overflows" in prose. It is not a type in the
GUI layer.

## 9. Open questions

- **tab-overflow.md's `menu()` policy** is a scrolling row plus a button, and is easier after this plan, because
  the scrolling row is a `Scrollbars.horizontal`. Its step order should wait for step 3 here.
- **The window root:** should `VexelApplication` wrap an app's content in `Scrollbars.both` by default? That would
  be scrolling built in again, but only in the one place that has no parent to report to. The default in this plan
  is no.
