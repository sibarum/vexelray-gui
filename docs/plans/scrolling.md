# Scrolling as elements: `Overflow`, `ScrollRange`, `Scrollbar`

**Status: proposed 2026-10-26; nothing built.** This replaces automatic scrolling, and it supersedes
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

- **`Overflow`** masks.
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

`clip(true)` stays as it is: a mask with no scrolling, for a box that means to cut its content off.

## 2. `Overflow`: the mask

`gui.overflow(content)` is a box with **exactly one child**. It:

- **masks** its child to its own box, for painting and for hit-testing. `HitTest` descends into it only inside its
  rectangle, which is the rule `clip(true)` follows already;
- **moves** its child by its offsets, along x and y;
- **publishes** two `ScrollRange`s, one per axis (§3), each measured from its child against its own box;
- **takes the scroll gestures** that land on it: the wheel (through tactroller, with `ScrollGlide` as today), and
  `scrollIntoView`, `scrollTo`, `scrollToEdge` and `lock(...)`. Every one of these works with or without a bar.
  On a trackpad nobody needs a visible bar to scroll, and that has to keep working.

Each axis can be turned off: `overflow(content).axes(false, true)` scrolls only vertically, and along x it lets
the child overflow, which §1 reports. A table that scrolls sideways asks for x. A page asks for y.

### 2.1 How big an `Overflow` is

This is the one sizing rule the plan adds, and only `Overflow` uses it:

> **An `Overflow` measures as its content and gives way to the room it is given, down to nothing.** Across an axis
> its parent sizes, it fills, like `alignItems(STRETCH)`. Along the parent's main axis, its basis is its content's
> size, and where the parent has less room than its children ask for, the `Overflow` gives up the difference
> first.

That is CSS's `flex-shrink` with `min-size: 0`. It's needed because no child in `FlexLayout` shrinks today, and
shrinking is exactly what an `Overflow` exists to do. Where two `Overflow`s share a main axis, they give way in
proportion to their content.

With that rule, the push and shrink behaviour from scrollbar-room.md comes from ordinary layout. The bar is a
sibling with a size:

```
column (height AUTO)                      column (height = the window)
├─ Overflow (x)  ← headers row            ├─ title bar
└─ Scrollbar.horizontal                   ├─ Overflow (y)  ← page
                                          │  (gives way: it is the room left)
                                          └─ status bar
```

- **Content-sized, on the left (the tab bar).** The parent column is `AUTO`. The `Overflow`'s width is the
  column's, so the headers overflow it along x. Its height is the headers'. The bar adds 0.85 em below, so the
  column is headers plus bar, and the pages under it move down. Nothing measured the bar in advance: it's a child
  with a height.
- **Sized from outside, on the right (a page).** The column is the window's height. The `Overflow`'s basis is the
  page's length, more than the room there is, so it gives way to what the title and status bars leave. A vertical
  bar placed beside it in a row takes its width from the row, and the `Overflow` is narrower by that much.

`FlexLayout.measure` goes back to what it was before scrollbar-room.md step 1.

### 2.2 What moves, and why the bar is not inside it

The offset moves the `Overflow`'s child, all of it. A bar placed inside that child would scroll away with the
content. The alternative would be to exempt bars from the offset, which is a special case of the kind this plan
removes. So the bar lives **outside the box it scrolls**: beside it, or anywhere else in the tree. It finds its
range by reference (§4), not by searching ancestors.

"A scrollbar inside an overflow container", as first proposed, becomes "a scrollbar next to an `Overflow`, both
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

- **An `Overflow` is a source**, one per axis. Its writes are the `Mutation.ScrollTo` that `Node.scrollTo` posts
  today, so a bar drag, the wheel and `scrollIntoView` resolve in the same frame as the layout, as they do now.
- **Anything else can be a source.** That covers the first use that doesn't involve layout:
  - a 3D viewport publishes how far it can orbit or pan, and moves its camera when asked;
  - MainFrame's terminal publishes its scrollback;
  - a multi-line text leaf publishes its lines.

  A bar shows and drives each of these exactly as it does an `Overflow`.

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

The bar **keeps its room**, drawing a track with no thumb. That is the default, for three reasons:

- it is the no-hover rule's reasoning: reserved space, nothing that comes and goes;
- the layout never depends on its own result;
- an app that wants the bar gone says so.

`Scrollbar.collapsing()` hides the bar while the range fits. That means the bar's visibility depends on a range
the layout produces, so the layout runs twice in that one case: once with the bar collapsed and, if the range
then overflows, once with it shown, never more. That's today's "measured again in what is left", made local to
the one node that asked for it. It is step 6, not the first cut.

### 4.2 The usual pairing

```java
Node page = Scroll.vertical(gui, content);            // row[ Overflow(y) grow , Scrollbar.vertical ]
Node bar  = Scroll.horizontal(gui, headersRow);       // column[ Overflow(x) , Scrollbar.horizontal ]
Node both = Scroll.both(gui, grid);                   // the corner square is a plain box
```

`Scroll` returns an ordinary box, and its parts can be reached for styling the same way `Tabs.bar()` is. It is
composition, not a new kind of node.

## 5. Where it lives

Following the separate-module rule:

- **`vexelray-gui-core`**: `ScrollRange`, `ScrollSource` and `Overflow`. Masking, the offset and the sizing rule
  are layout, `Clip` and `HitTest` work, and core is where those live. This is the one part that cannot be its own
  module.
- **`vexelray-gui-scroll`** (new): `Scrollbar` and `Scroll`. It depends on core and `vexelray-gui-draw` only, and
  `vexelray-gui-widget` depends on it.

## 6. Order of work

Each step leaves every repo compiling and every test passing. The old model and the new one live side by side
until step 5.

0. **Revert scrollbar-room.md step 1.** `git revert 75bfae2` takes back the strip in `measure`, its seven tests
   and the doc lines. The investigation tests from 4b4ce1b (`ScrollbarRoomSweep`, `TabsOverflowProbe`) stay until
   step 5, because they measure the model being replaced. scrollbar-room.md is marked superseded. Its findings
   stay as the record of why: the margin result (§3.1) is why the bar is in layout and not in a margin.
1. **Core: `ScrollRange`, `ScrollSource`, `Overflow`.**
   - The sizing rule (§2.1).
   - Masking and the offset.
   - Wheel routing: the nearest `Overflow` under the pointer whose range can move on that axis.
   - `scrollIntoView`, `scrollTo`, `scrollToEdge` and `lock` on `Overflow`. `Scrolling`'s nested solve is
     unchanged, except that "a scrolling ancestor" now means an `Overflow`.
   - An `Overflow` turns the old implicit scrolling off for itself and its child, so the two models never stack.
   - Tests: the two §2.1 cases with no bar; the shrink split between two `Overflow`s; reveal through nested
     `Overflow`s.
2. **`vexelray-gui-scroll`: `Scrollbar`, `Scroll`.**
   - Tests:
     - the tab-bar case grows by the bar's height and moves its neighbour;
     - the page case gives the bar's width up from the `Overflow`;
     - drag, track paging and the wheel;
     - a bar bound to a plain `ScrollSource` with no layout behind it.

     The first two are ScrollbarRoomTest's cases again, now passing with no measure change.
   - A demo chapter: an `Overflow` with each kind of bar, and a bar driving a source that is not an `Overflow`.
3. **Framework widgets move** (§7.1), with `Tabs` first and checked with `TabsOverflowProbe`. Then the demo.
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
   `scroll(` finds only `Overflow`, `ScrollSource` and the bar.
6. **`Scrollbar.collapsing()`** (§4.1), if an app turns out to want it.

## 7. What moves

### 7.1 In this repo

| Where | Today | Becomes |
| --- | --- | --- |
| `Tabs` bar | `scroll(true, false)`, a declared `2.25rem` height, headers `FILL` | `Scroll.horizontal` around the header row. The bar becomes `AUTO` and headers a fixed `2.25rem` (scrollbar-room.md §5C), so the bar is headers plus scrollbar. The indicator sits at the headers' bottom, not the bar's |
| `ListView`, `TreeView`, `Table` body | scroll y, `viewH`/`viewW` for virtualisation and paging | `Scroll.vertical`, with virtualisation reading `range().view` |
| `Deck`, `Inspector`, `Rail` panel body, `KeymapEditor` | `scroll(false, true)` | `Scroll.vertical` |
| `TextField`, multi-line | a text leaf with its own bar and `viewH` | a `ScrollSource` over its lines, with no bar of its own. The text area composes a `Scrollbar` beside it. A single-line field keeps scrolling with its caret and has no bar |
| Window root, under `Gui.minSize` | the root scrolls on a canvas larger than the window (scrolling.md §2) | the root never scrolls. An app that wants a small window to scroll wraps its content in `Scroll.both`. Otherwise the overflow is reported |
| `Automation` / `ottermate tree` | `scrolls x/y` from `overflowX/Y` | an `Overflow` line with its ranges, and a `Scrollbar` line naming its source |
| Demo | 7 opt-ins (Drag, Focus, Icons, Image, Motion, Window chapters, `Shell`), `Console`'s bottom lock | `Scroll.vertical`. `Console` gets `lock(BOTTOM)` |
| 101 `scroll(false, false)` here (66 more in the siblings) | opting out | deleted in step 5 |

### 7.2 Sibling apps

| Repo | Opt-ins today | Notes |
| --- | --- | --- |
| vexelray-framework | `About` hero | `TactrollerInputBackend` keeps setting `scrollGlide`. `AboutTest` and the acceptance run check for the §1 report instead of the sideways one |
| mainframe | `OpenDialog` grid; `WorkspacesPage` `scrollIntoView` | gets `Tabs` for free in step 3. `TerminalView` can publish its scrollback as a `ScrollSource` and get a real bar, a later change of its own |
| vexplore | `Dock`, `RailView` | |
| text-editor-vexel-demo (Vex) | `Formatted`, `Preview`; `Navigator` `scrollIntoView` | |
| calculator-vexel-demo | `SettingsWindow`, the tape (`lock(BOTTOM)` with `scrollToEdge`) | |
| vexelray-sim-fluid | `ControlPanel`, `Sidebar` | |
| pontif-framework | `BoxWalker`'s `Scrolls` atom | the atom becomes an `Overflow` with an optional bar. This is a change to its schema |

Any box in those apps that scrolls today *without* declaring it will overflow after step 5. The §1 report names
each one on the first run that reaches it. Step 4 runs each app through its acceptance path for exactly that
reason.

## 8. Open questions

- **The name `Overflow` collides twice:**
  - `vexelray-framework-api` already has `dev.vexelray.framework.api.Overflow`, the enum for a full mailbox;
  - [tab-overflow.md](tab-overflow.md) plans a `Tabs.Overflow` policy seam.

  Different packages, so nothing breaks, but it's `Ramp` again: one word, two meanings, close enough to import the
  wrong one. Rename this one (`Viewport` is taken by `GuiApp.viewport`; `Window`, `Pane` and `Mask` mean other
  things), or rename tab-overflow's seam to `Tabs.Crowding`.
- **tab-overflow.md's `menu()` policy** is a scrolling row plus a button, and is easier after this plan, because
  the scrolling row is a `Scroll.horizontal`. Its step order should wait for step 3 here.
- **The window root:** should `VexelApplication` wrap an app's content in `Scroll.both` by default? That would be
  scrolling built in again, but only in the one place that has no parent to report to. The default in this plan is
  no.
