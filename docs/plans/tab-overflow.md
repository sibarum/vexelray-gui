# The tab-overflow plan

**Status: step 1 done (2026-10-10); the rest paused** — written down so it can be picked up cold; no
work is scheduled against it. Step 1 as built: the header row is a horizontal scroller, `select` calls
`scrollIntoView()` on the header it lands on, and Left/Right now carry focus to that header (they didn't, so a
walk got no further than the next tab). When the headers overflow, the scroller shows its scrollbar along the
bottom of the bar, which is also the pointer's way to the clipped tabs until `menu()` exists.
`TabsTest.walkingPastTheEdgeKeepsTheSelectedHeaderInView` covers it. What follows is the plan as written. Today `Tabs` has no answer to "more headers than bar". The bar is a fixed
`2.25rem` row with scrolling turned off (`Tabs.java:245`), and `FlexLayout` has no wrap, so the extra headers lay
out past the bar's right edge and are clipped. They still exist and keep focus, so Left/Right can walk onto a tab
you can't see. Nothing tells you there are more tabs, and no pointer gesture reaches them. This plan is done when
an application picks one of three behaviours with one call, every tab can be reached by pointer and by keyboard in
all three, and the demo's tabs chapter shows each one at a width that overflows.

---

## 1. The shape: one seam, not two booleans

"Wrap" and "dropdown" don't combine as `wrap(true)` + `overflowMenu(true)`. What would both on mean? They are
answers to one question: **what does the bar do with headers it has no room for?** So the question gets a type,
built like `TabTransition`: a functional interface with the default as a constant and the alternatives as static
factories.

```java
@FunctionalInterface
public interface Overflow {
    /** Arrange the bar for this policy. Called once, when installed; the policy owns what it changes. */
    void install(Bar bar);

    Overflow CLIP = bar -> { };            // today's behaviour, kept as the default so nothing moves
}

public static Overflow wrap()  { ... }    // §2
public static Overflow menu()  { ... }    // §3

tabs.overflow(Tabs.menu());
```

`Bar` is a narrow handle that only `Tabs` can construct. It exposes the header row, the slot after the row, the
header list and `select`, which is enough for a policy to rearrange the bar without being given the panel. Each
behaviour lives in its own policy, so `Tabs` never switches on which policy is installed (the no-sealed-switch
rule). It also leaves room for a policy nobody has written yet, like `wrap(maxRows)` followed by a menu, without
changing `Tabs`.

---

## 2. `Tabs.wrap()`: headers flow onto more rows

### 2.1 This needs flex-wrap in core, and that is the right place for it

`Tabs` can't wrap the headers itself. It could watch `gui.onResize(bar)`, count how many headers fit and move the
rest into a second row, but that has three problems:

- **It is a frame late, every time.** The headers would be laid out once in the wrong place and then corrected,
  and a window resize would visibly jump.
- **It is a second layout engine,** written by hand in a widget, measuring text that `FlexLayout` already measured.
- **It restructures the tree.** Moving a header to another row means `remove()` and re-insert, which releases
  every registration on it (the reason `Tabs` hides pages and never removes them). Each re-wrap would leave the
  headers inert.

So the change is in `FlexLayout`: `Node.wrap(boolean)` on a `ROW`. Children break into lines by their main-axis
basis. The row's height is the sum of the line heights plus the gap. `alignItems` applies within each line. This
is height-for-width, and the layout already handles height-for-width: a wrapped text label is sized at
`lineCount · lineHeight` (`TextMetrics`). Wrapped children follow the same pattern. The class doc's line "not a
full flex implementation — no wrap" changes accordingly.

Other widgets get wrap too: toolbars that run out of width, chip and tag lists, `Segment` in a narrow pane. It's a
layout feature that `Tabs` is first to need, not a `Tabs` feature.

### 2.2 What the policy does

- `bar.height(Length.rem(2.25f))` becomes `height(AUTO)`, with each header row set to `2.25rem` (`minHeight` on
  the headers). One row looks the same as today.
- `bar.wrap(true)`. That is the whole policy. Child order is unchanged, so paint order is unchanged. `Change.
  incomingOnTop` keeps its meaning ("moved right along the bar"), and the transitions need no changes.
- **Rows are not reordered.** The old Windows tab control moved the selected tab's row down next to the page. That
  makes every other tab jump when you click one, and it needs exactly the tree restructuring §2.1 rules out. The
  selected tab stays where it is.

### 2.3 Open: the seat on an upper row

The default skin gives a tab a flat seat that sits on the page area. On an upper row, the selected tab's seat sits
on the row below it instead. Two possible answers:

1. Accept it. The selected tab is still lit, accent-inked and raised, which is enough to find it.
2. Tell the skin: `HeaderSkin.paint(header, selected, state)` gets a `seated` flag (true when the header is on the
   last line), and the default skin rounds all four corners when it's false. That needs a way to read the line
   index from `NodeLayout`.

I'd ship (1) and add (2) only if it looks wrong in the demo.

---

## 3. `Tabs.menu()`: a scrolling row plus a button that lists every tab

### 3.1 Almost all of it already exists

`menu()` needs **no core changes**:

- The header row becomes a horizontal scroller (`scroll(true, false)`). Headers that don't fit are scrolled off,
  not hidden. Nothing is removed, and every registration stays.
- **`select` reveals.** After the selection moves, the selected header calls `scrollIntoView()` (`Node.java:537`).
  That call scrolls by the least amount needed and is resolved in the same frame. So Left/Right, the menu and
  page reveals (`gui.reveals`) all bring the header into view, and Left/Right can no longer walk onto an
  invisible tab. **CLIP should get this too**, because it fixes today's walk-off without changing how CLIP looks.
- The **scroll wheel** over the bar scrolls it horizontally. That's standard scroller behaviour.
- A **trailing button** sits in the bar's slot, outside the scroller. It opens the list.

### 3.2 The button is always there

The button occupies its slot whether or not anything overflows. This follows the reserve-the-space rule. It also
avoids a real problem: if the button appeared only on overflow, showing it would shrink the scroller, which can
create the overflow (or remove it, at a width where the two are close), and the bar would flicker between the two
states during a resize. A button that is always there also does a second job: it switches between tabs even when
they all fit. VS Code and Chrome both keep their tab-list button visible for the same reason.

The glyph needs checking against the atlas (see the glyph-coverage memory: no arrows). `⋯` (U+22EF) or `▾` are
the candidates. If neither is in the atlas, the button's picture is sketched instead of using text.

### 3.3 The list is `Select`'s list, not a new one

The dropdown lists every tab in bar order and marks the selected one. Choosing a row selects that tab (which then
reveals it, per §3.1). The list should be a `ListView` popup, the one `Select` already composes. That gets
virtualisation (fifty files cost one popup's worth of rows), keyboard navigation, type-to-find and
`ListView.reveal` (the list opens scrolled to the current tab) without writing them again. Building it as rows in
a `ContextMenu` would make a second, worse list. `ContextMenu.present` is also anchored to a `ClickEvent`, so a
keyboard-opened list would have no position.

**To check before building:** can `Select` be used as is, with its closed strip skinned down to the button glyph
and its model bound to the tab selection? If not, the popup half of `Select` gets factored out so that both use
it. That's a refactor of `Select`, not a fork of it.

Optional, cheap, later: list rows for tabs that are fully in view are shown dimmed, and the out-of-view ones at
full ink. This needs the header rects against the scroller's view rect, read when the list opens. It's computed
once at open time and never kept up to date, so it can't fall a frame behind.

### 3.4 Keyboard

- Left/Right on a header work as now, and now reveal.
- The button joins the tab order after the headers. Enter or Down opens the list.
- A panel-level shortcut to open the list (Ctrl+Shift+A in VS Code terms) is the application's to bind, through
  `tabs.openList()`. The bar doesn't take a global chord on its own.

---

## 4. Order of work

1. **Reveal on select, for every policy.** One line in `select`, plus a test that walks Right past the edge and
   checks the header's rect is inside the bar. This fixes today's bug on its own.
2. **`Overflow` seam + `Bar` handle,** with `CLIP` as the default. No change in behaviour, so the existing
   `TabsTest`, `TabTransitionTest` and `TabCrossfadeTest` must pass untouched.
3. **`menu()`.** Scroller, button, `Select`-based list. Check `Select`'s reusability first (§3.3).
4. **`FlexLayout` wrap** in core with its own tests (line breaking, gap across lines, height for width, nested
   wrap), then **`wrap()`** on top of it.
5. **Demo chapter:** one panel per policy with twelve tabs, in a pane narrow enough to overflow. Plus an
   `ottermate` run that resizes the window and screenshots each one.

Steps 1–3 are all in the widget module. Step 4 is the only core change, and the only part that changes behaviour
for anything outside `Tabs`.
