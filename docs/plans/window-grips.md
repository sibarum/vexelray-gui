# Window grips: resize controls you can see, and a window that will not shrink past its chrome

Status: **Proposed 2026-10-25, not started.** Optional at first, shown in the demo for refinement, and then a
decision (§7) about whether it becomes the template's default.

## 1. The problem

Resizing a `Decorations.CLIENT` window works, and it is the window manager that runs it (`Decorations`,
`HitRegions`). What is missing is everything around the mechanism:

- **No foreshadowing.** On Windows the only sign that an edge can be dragged is the cursor changing once the
  pointer is already on it. On Linux (X11) a client-drawn window does not even get that: the press is handed to
  the window manager with `_NET_WM_MOVERESIZE` (`X11Window.onButtonPress`), but nothing sets a resize cursor over
  the band, and `NativeWindow.Cursor` has no diagonal shapes to set.
- **A hairline target.** The band is the platform's few pixels, unless the application spends its margins with
  `Gui.resizeBorder`. One pixel off and the click lands on whatever is behind the window, which takes focus.
- **No floor that the chrome respects.** `WindowConfig.minSize` reaches `WM_GETMINMAXINFO` and `WM_NORMAL_HINTS`,
  but it is a fixed pixel pair set at creation, and the framework never sets it: `VexelApplication.mainWindow`
  passes only the decorations, and `Appearance.minSize` becomes `Gui.minSize`, the smallest *canvas*. That one
  is deliberately not an OS minimum (its doc says why), so the body scrolls correctly while the window shrinks
  past what the title bar can hold, and the bar overflows.

Every application has all three, so the answer belongs to the framework and must come from the template. It has
to be something nobody deletes the day after generating a project.

## 2. What already exists, and is kept

- **The window manager still runs the drag.** Snap, keyboard size, the maximized work-area clamp and DPI changes
  stay the OS's. Nothing here moves or sizes a window from application code.
- **Declared, not driven.** Chrome is nodes with a `WindowRegion`; `ChromeRegions` derives `HitRegions` from the
  laid-out tree each frame and pushes it. Grips are more of the same.
- **`SplitPane`'s divider is the model for the look.** A fixed width that never grows, only its colour changes
  on hover, the accent held for the whole drag, faded with a linear `Ramp`. The glow on Vex's divider is this.
- **`tactroller` already refuses resize bands as pointer targets** (`WindowsInputBackend.isPointerTarget` →
  `inResizeBand`), so a press that starts a resize is never also a click on what is drawn under it.

## 3. The shape of the answer

### 3.1 Grips are nodes

A grip is a node the GUI draws and lays out, declared as a resize region. `WindowRegion` gains one value:

```java
/** A resize grip: dragging it sizes the window from the given edge or corner. */
RESIZE   // carries its edge: node.windowRegion(WindowRegion.RESIZE, Edge.BOTTOM_RIGHT)
```

How the edge is carried (a second enum, or eight `RESIZE_*` values) is decided when it is written; the rule is
that it is a fact on the node, like the other regions.

`HitRegions` gains `List<Grip> grips` (a rect and a `Zone`). **Invariant: a window that declares grips is resized
only from its grips.** The implicit edge band is then off everywhere, so content that runs to the window edge
keeps its clicks, and the only resize surface is the one the user can see. A window that declares none keeps
today's behaviour exactly (platform band, or `resizeBorder`).

`zone()`'s precedence becomes: declared grips, then (only when no grips are declared) the edge band, then
maximize button and interactive, then caption. A maximized window still has no resize anywhere.

Both backends map grip zones to what they already map band zones to: `HTLEFT`…`HTBOTTOMRIGHT` on Windows, the
`_NET_WM_MOVERESIZE` direction on X11. No new OS mechanism.

### 3.2 The space is reserved; only colour changes

Grips occupy layout space whether or not the pointer is near them. Nothing appears, moves or grows on hover. A
grip is lit (accent, faded) while hovered and for the whole of a resize, exactly as `SplitPane`'s line is. When the
window is maximized the grips are drawn at rest and do nothing; their space stays (a state change, not a hover).

### 3.3 Hover reaches the grip through tactroller

Today a resize band is not a pointer target at all, so a grip under it would never see hover. The fix is in
tactroller, which is where every pointer event already comes from: over a resize band the pointer keeps
**moving** (position and hover are delivered) but a **press** is not routed, because it belongs to the window
manager. That keeps the one-path rule and makes the grip an ordinary hovered node.

### 3.4 Lit for the whole drag

During a resize the pointer is the window manager's. The window knows it is being sized and from which edge:

- Windows: `WM_ENTERSIZEMOVE` / `WM_EXITSIZEMOVE` are already handled (`Win32Window`, the size-move timer), and
  `WM_SIZING`'s `wParam` names the edge (`WMSZ_*`).
- X11: the window sent `_NET_WM_MOVERESIZE` itself, so it knows the edge; when the drag ends is the open
  question (§8).

`NativeWindow` gains `resizing()` (the zone being dragged, or none). `GuiWindow` reads it each frame like the
other window states, and the grip with that zone holds the accent.

### 3.5 The cursor

Over a grip the cursor is the resize arrow for its zone, held for the whole drag. `CursorShape` and
`NativeWindow.Cursor` gain the two diagonals (`RESIZE_NWSE`, `RESIZE_NESW`). On Windows, `WM_SETCURSOR` with an
`HT*` edge code already does it; on X11 the grip's `CursorShape` reaches `setCursor` (`XC_bottom_right_corner`
and friends) through the ordinary cursor derivation, which is the Linux fix from §1.

### 3.6 The window will not shrink past its chrome

The OS minimum becomes a **derivation from the tree**, pushed each frame like `HitRegions`:

```
window minimum = max(Gui.minSize resolved at the current zoom,  what the non-scrolling chrome needs)
```

"What the chrome needs" is the docked bars' own minimum: a `TitleBar`'s controls, its icon, and a title shrunk to
its ellipsis. Body content is not in it, because the body scrolls under `Gui.minSize` and that is correct. This
keeps the separation `Gui.minSize` documents: the canvas floor and the window floor stay two facts, and the
second now has a source.

`NativeWindow` gains `setMinSize(w, h)` (default no-op); Win32 makes its min fields volatile for
`WM_GETMINMAXINFO`, X11 rewrites `WM_NORMAL_HINTS`. Zooming in raises the floor; a window already smaller than a
new floor is not forced bigger (that is a resize nobody asked for), and `Gui.minSize` keeps it laid out.

## 4. The styles

A `WindowGrips` widget (own module, `vexelray-gui-grips`, beside `vexelray-gui-widget`) offers the styles as
values, each a function from the window frame to grip nodes:

| Style | What it draws | Notes |
| --- | --- | --- |
| `CORNER` | One grip in the bottom-right corner, no border | The corner has to belong to something: the status bar's trailing slot when there is one, otherwise a reserved square. Diagonal cursor. |
| `FRAME` | A border on the left, bottom and right, corners included | Top stays the title bar's: drag from the bar, resize from the sides. The border's thickness is the target, its painted line can be thinner, like `SplitPane.gutter`/`line`. |
| `SYSTEM` | Nothing; today's behaviour | Platform band or `resizeBorder`. Kept so the comparison is honest. |

`FRAME` replaces what `resizeBorder` was for (spending the margin), with the difference that the margin is now
visible as a grip. Whether `resizeBorder` stays once `FRAME` exists is part of §7.

## 5. Where the work lands

| Repo | Change |
| --- | --- |
| `vexelray` (`vexelray-os-api`, `-windows`, `-linux`) | `HitRegions.grips` and the precedence; `Cursor` diagonals; `setMinSize`; `resizing()`; X11 cursors for the new shapes |
| `tactroller` (`tactroller-windows`, Linux backend) | Hover delivered over a resize zone, press withheld |
| `vexelray-gui` core | `WindowRegion.RESIZE` with its edge; `ChromeRegions` collects grips; `CursorShape` diagonals; window minimum derived and pushed from `GuiWindow`; `resizing()` exposed to the tree |
| `vexelray-gui` new module `vexelray-gui-grips` | `WindowGrips` and its styles; `TitleBar` reports its minimum |
| `vexelray-gui-demo` | A grip style selector on the Windows chapter's popup, and on the main window |
| `vexelray-framework` | `Appearance.grips(style)`, off by default until §7; the template passes it through |

## 6. Steps

1. **The floor (§3.6).** `setMinSize` and the derived minimum, with `TitleBar` reporting its own. Useful on its
   own, with no grips: it fixes the title-bar overflow for every application.
2. **OS plumbing (§3.1, §3.5).** `HitRegions.grips`, the precedence, cursor diagonals on both backends. Tested in
   `HitRegionsTest` and `Win32ClientChromeSmoke`.
3. **Hover over grips (§3.3)** in tactroller.
4. **`resizing()` (§3.4)** on Windows.
5. **`WindowGrips` with `CORNER` and `FRAME`** in its own module, lit like `SplitPane`.
6. **The demo.** Popup and main window get a style selector; checked with `ottermate` (`shot` at rest, hovering a
   grip, and mid-resize).
7. **The framework option**, off by default.
8. **The decision (§7).**

Linux follows each Windows step, behind the same API.

## 7. The decision this sets up

After using both styles in the demo: does one become the template default, do both stay as options with a
default, or does it stay opt-in? The plan does not presume the answer. The one thing already decided is that
step 1 (the floor) is not optional: there is no application that wants its title bar to overflow.

## 8. Open questions

- **When an X11 resize ends.** The window manager owns the pointer after `_NET_WM_MOVERESIZE`; the end may have to
  be inferred (the pointer's button state on the next motion, or `ConfigureNotify` going quiet). To be measured on
  a real window manager, not guessed.
- **The chrome minimum.** Whether the layout can already report a docked bar's minimum width, or `TitleBar`
  computes it itself. Look before building.
- **`CORNER` with no status bar.** A reserved square in the corner of the body may look odd; the demo will show.
