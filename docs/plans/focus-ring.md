# The focus-ring plan

**Status: F1–F7 built (2026-10-10); F8 waits on a look at the chapter; F9's reference section is written
([keyboard-focus-text.md §2](../reference/keyboard-focus-text.md)).** Where the build differs from the text
below:

- `Theme.focusRing()` is a **default method** returning `STANDARD`, so a theme implemented outright still
  compiles, and `Theme.focusRing(FocusRing)` returns the same theme with another ring — that is how the
  chapter's controls install theirs. `FocusRing.Visibility` also has `NEVER`, which `NONE` uses.
- The renderer is handed the focus as a value, `FocusState(nodeId, cause, windowActive)`, from
  `Gui.focusState()`; `TreeRenderer.emit(root, canvas, faces, gui)` unpacks the `Gui` once. The dispatcher
  asks for a frame when that state changes, since a focus move edits no prop.
- The semantic snapshot used to republish only on a geometry change, so a focus move on a node with nothing
  to restyle (a button) left `focused` stale. It now also republishes when the focus state or the theme
  changes.
- `TreeView` dropped its focus subscription altogether: with the border gone it had nothing left to do.
- F4's audit: all eleven `gui.focusable` registrations in `-widget` name the visible node. Nothing to change.
- The chapter's "fills its container" field is single-line: a multiline `TextField` takes Tab as text, which
  ends the walk there. And Tab onto a field scrolled out of a viewport does **not** scroll it into view, so
  its ring is trimmed away entirely. That is the Tab walk, not the ring, and is left for a separate change.

The measurement that made this checkable: of the twelve widgets in
`vexelray-gui-widget` that can take keyboard focus, **two** show that they have it. They are `TextField` (and
`NumberField` and `FindBar` through it), which recolours its own border (`TextField.onFocus`), and `TreeView`,
which does the same to its root (`TreeView.java:2219`). `Button`, `Toggle`, `Slider`, `Segment`, `Select`, the
headers of `Tabs`, `Rail`, `Property`, `Inspector` and `ListView` all join the Tab order, and none of them change how they look
when focused. Pressing Tab in the demo moves focus somewhere you can't see. This plan is done when every focusable
node shows focus the same way, from one setting, with no per-widget code, and the demo has a chapter that shows it.

[keyboard-focus-text.md §2](../reference/keyboard-focus-text.md) already promises this ("so widgets restyle (focus
ring)"). Today that sentence describes something the framework doesn't do, so this plan also fixes a defect in
the reference.

---

## 1. Why the fix is not "make every widget do what TextField does"

The two widgets that show focus each subscribe to `gui.focusEvents()`, compare `nodeId` with their own node and
rewrite a prop. That works, and it's exactly the pattern that goes wrong for the third widget:

- **It is opt-in, so the default is invisible.** A widget written without it (all ten above, and anything a
  consumer writes) is focusable and unmarked. The framework can't tell a consumer that the control they made has a
  focus nobody can see.
- **It isn't customisable.** Ring colour, width and shape are hard-coded into each widget. An application that
  wants a thicker ring, a different colour, or none at all has nowhere to say so.
- **It borrows the border, and the border is already in use.** A border is part of the box model (layout reserves
  its width), so a focus border has to be exactly as wide as the resting border, or focusing moves the content.
  `TextField` gets away with it because both are `0.1rem`. `Button`'s hairline is `1dp`, so a visible ring there
  would have to be a hairline or would shift the label. The border also already means something on `Toggle`
  (hover: `Role.GRIP` versus `Role.EDGE`), so the two states fight over one prop.
- **The overlay slot isn't the answer either.** `Node.overlay` is a decoration that doesn't affect layout, which is
  the right kind of thing. But it's clipped to the border box, so a ring can't sit *outside* the control. And
  `Cues` already uses that slot for one-shot feedback (`Cue.ring` among them), so a cue played on a focused
  field would wipe out its focus ring.

Focus is state only the dispatcher knows (`InputDispatcher.focusedId`), and exactly one node has it at a time.
So the renderer should draw the ring, the way it already draws the scrollbars and the selection wash. This is
the "fix the architecture" answer: the edge case (each widget remembering) goes away because nothing is left to
remember.

## 2. Shape

### 2.1 `FocusRing`, a value on the `Theme`

A record in `dev.vexelray.gui.core.style`, beside `Relief` and `Shading`, because it is one more answer to "what
does this look like":

```java
public record FocusRing(Role color, Length width, Length offset, Visibility when) {

    /** The framework's ring: accent, 0.125rem wide, standing 0.125rem clear of the box. */
    public static final FocusRing STANDARD = new FocusRing(Role.ACCENT, Length.rem(0.125f), Length.rem(0.125f),
            Visibility.ALWAYS);

    /** No ring anywhere. For an application that draws focus itself. */
    public static final FocusRing NONE = ...;

    public FocusRing color(Role c) { ... }    // withers, so a theme changes one axis
    public FocusRing width(Length w) { ... }
    public FocusRing offset(Length o) { ... }
    public FocusRing when(Visibility v) { ... }
}
```

- **`color` is a `Role`, not a `Color`.** That's how a theme states colour everywhere else (see `Theme`'s javadoc:
  "`Role` is already the declaration and `Color` only ever the result"), so a palette swap reaches the ring
  without a restyle pass.
- **`offset` is signed.** Positive puts the ring outside the border box, zero puts it on the border's outer
  edge, and `-width` puts it just inside. One axis covers outline, border overlay and inset ring, so they don't
  need to be three kinds.
- **The corner radius isn't a setting.** It follows the node's own radius plus the offset (`NodeLayout` already
  has the radii), so the ring stays parallel to the shape it surrounds. `TextField`'s find bar already does this,
  insetting its corners by the border so the curves run parallel (`TextField.java:946`).
- **`Visibility` is an open interface, not an enum to switch on.** It is "should a node that took focus this way
  show the ring?":

  ```java
  public interface Visibility {
      boolean shows(FocusCause cause, boolean editable);
      Visibility ALWAYS = (cause, editable) -> true;
      Visibility KEYBOARD = (cause, editable) -> cause != FocusCause.POINTER || editable;
  }
  ```

  `editable` is what the dispatcher already answers (`InputDispatcher.java:1362`). It is passed in as a value, so
  `Visibility` never needs the `Gui`. `KEYBOARD` is the CSS `:focus-visible` rule: show the ring when focus arrived by keyboard or programmatically,
  and when the node takes typed text however it got focus. A field always needs to say where your typing will go,
  but a button you clicked doesn't need to light up. That makes `KEYBOARD` the right default for most
  applications. `STANDARD` ships `ALWAYS` only until §5 has been seen, so the change in what shows up is visible
  on its own and isn't mixed in with the new mechanism.

`Theme` gains `focusRing()`, and `Theme.of` gains an overload that takes one. The existing five-argument `of` uses
`FocusRing.STANDARD`, so no theme already written changes. `PaletteTheme` gets the extra component.

### 2.2 Per-node override: `Node.focusRing(FocusRing)`

A new `PropKey.FOCUS_RING`, whose identity value is null (meaning "use the theme's"), the same convention as
`OVERLAY`. A node can:

- change one axis (`node.focusRing(gui.theme().focusRing().offset(Length.rem(-0.125f)))`, an inset ring for a
  control that sits flush in a toolbar),
- opt out (`node.focusRing(FocusRing.NONE)`, for a node that draws its own focus as part of something larger,
  the way `TreeView` and `ListView` paint the lead row),
- or say nothing and get the theme's ring, which is the case that matters.

The prop is read from the focused node only. It isn't inherited: a container's ring setting says nothing about its
descendants, because a container that wants to style its children's rings is styling their look, and that belongs
to the theme.

### 2.3 How focus arrived: `FocusEvent` gains a cause

`Visibility` needs to know whether focus arrived by pointer, by keyboard (Tab or Shift+Tab), or by a call to
`Gui.focus`. The dispatcher already knows at each `setFocus` call site:

| Call site | Cause |
| --- | --- |
| `moveFocus` (Tab, Shift+Tab) | `KEYBOARD` |
| left press, `ancestorFocusable(hit)` | `POINTER` |
| a drop landing (`InputDispatcher.java:474`) | `POINTER` |
| `focus(long)`, which `Gui.focus` calls | `PROGRAM` |
| `remove(nodeId)` clearing focus | none: focus cleared, no ring |

`FocusEvent(long nodeId, boolean gained)` becomes `FocusEvent(long nodeId, boolean gained, FocusCause cause)`. It
is a record on the bus, so the two existing subscribers (`TextField`, `NumberField`) recompile unchanged, because
neither destructures it. `FocusCause` is a three-constant enum that nothing switches on: it is data that
`Visibility` compares with `==`. A `PROGRAM` focus counts as keyboard, because the usual programmatic focus
(`FindBar` opening on Ctrl+F, `Tabs.focus()`) answers a keystroke.

### 2.4 Drawing it: at the close of the nearest clipping ancestor, in `TreeRenderer`

`TreeRenderer.emit` gains one input: the focused node id and its cause, passed as a value the way
`ReadModels.Context` already gets `focusedId` (`Gui.java:407`). The renderer stays a function of what it's given
and never reaches into the dispatcher.

When the walk visits the focused node, it records its painted rect (after `translate`), its corner radii and the
inherited `alpha`. It does **not** draw the ring there. The ring is drawn by the focused node's **ring host**:
the nearest ancestor that pushes a clip (a scroll viewport, or an explicit `clip()`), or the root if none does.
The host draws one box at the outset rect whose look is a border alone, in the resolved colour, faded by the
recorded alpha. It does this after its flow children and before it pops its clip, its scrollbars and its floating children. In
`walk` that is the point between the flow loop and `canvas.popClip()`. For an explicit `clip()` host, the
float loop moves to after the ring.

Four choices made here, each for a reason:

1. **Not in the node's own slot.** A ring drawn when the node is visited is painted over by its later siblings.
   A field in a row of fields would lose the ring's right edge to the next field. At the host's close, every
   flow sibling inside the host has been painted, so the ring is on top of them.
2. **Not last of everything, either.** Drawn after the whole walk, the ring would paint over the overlays,
   which is the one thing it must stay under. `Tooltip`, `ContextMenu` and `Select` all open their popups as
   floating children of the root (`Tooltip.java:182`, `ContextMenu.java:137`, `Select.java:313`), and floats
   are painted after the flow. A ring drawn before the host's floats leaves every framework popup covering it.
3. **The clip is the real one, not a reconstruction.** A field half scrolled out of a list must not draw a ring
   over the list's header. Drawing while the host's clip is still pushed gets that for free, *including the
   rounded corner*. Re-pushing a clip afterwards would get it wrong: the renderer only tracks the intersection
   of its ancestors' clips as a plain rectangle (`clipL`…`clipB`, used by `masked()`), with no radii, so a
   re-pushed clip would let the ring through the corners of a rounded scroll viewport.
4. **Fall inward when the outset doesn't fit.** The common case in this codebase is a control that fills its
   container (the notes editor in `TextChapter`, any field in a `Property` card). There an outset ring is clipped
   away on every side, so the most important field in the window is the one with no visible focus. Rule: if the
   outset rect isn't contained in the host's clip rect (the `clipL`…`clipB` intersection at the host, which is
   good enough for a containment test even though it can't stand in for the clip itself), draw it at `offset = -width` instead. It is one rule for all four
   sides together, so the ring never comes out lopsided. A ring trimmed by a scroll viewport is fine. A ring
   trimmed to nothing is not.

Nothing is laid out, measured or hit-tested. The ring belongs with the visual transforms in
[architecture.md §7](../reference/architecture.md), with `opacity`, `translate` and `overlay`. So it follows the
hover rule in `CLAUDE.md` by construction: focusing moves no geometry and changes no pointer target.

### 2.5 What the semantic read-model already says

`SemanticNode.focused` is already published. This plan adds `focusCause` to it, and `ringShown` (the result of
`Visibility.shows` for the node that has focus). `ottermate` can then assert "the ring is up" from a readout,
without reading pixels (see §6).

## 3. Migration: the two widgets that already do this

Both must stop, or a focused field shows two indicators: an accent border and an accent ring around it.

- **`TextField.onFocus`** keeps the caret and `caretOn` handling and drops both `border(...)` calls. The resting
  border stays `Role.LINE` permanently.
- **`TreeView`** drops the `focused ? Role.ACCENT : Role.LINE` border on its root and inherits the ring. Its lead
  row marker (`Role.ACCENT` on the row) stays, because that marks the *row*, not focus, and it shows without focus
  as well.
- **`ListView`'s** lead marker, likewise, stays.

No widget gains code. Ten widgets gain a visible focus by doing nothing, and that is the test that the
mechanism is in the right place.

## 4. Things that will bite

- **A widget whose focusable node isn't its visible box.** `Toggle` makes `track` focusable, which is the right
  node to ring. If a widget makes an invisible wrapper focusable, the ring surrounds the wrapper. The fix is in
  that widget (make the visible node the focusable one), not a ring-target indirection. Audit the twelve during
  §5.
- **The ring and `Relief`.** A raised control's shadow is drawn under the node, and the ring is drawn after the host's flow, so
  they don't fight. But a ring with a small positive offset sits inside the shadow's blur. That is the intended
  look, and it is worth confirming once in `Theme.LIGHT`, where the shadow is the only depth cue.
- **Window focus.** When the window itself loses OS focus, the node keeps keyboard focus (so Alt+Tab back
  returns the caret where it was), but the ring should dim, the way the caret already stops blinking. That needs
  the window-active state the `Gui.java:457` key-release clearing already observes. Specify it as "ring at half
  alpha while the window is inactive" and treat it as a separate, small step (§5, F5).
- **A float between the host and the focused node is painted under the ring.** Floats are painted at the end of
  their *own* parent. If that parent sits between the ring host and the focused node and doesn't clip, its float
  is painted during the host's flow pass, before the ring, so the ring draws over it. Nothing in `-widget` does
  this today: every popup floats at the root (§2.4, point 2). If a widget ever needs to float locally over a
  focusable sibling, the fix is in that widget (float at the root, or `clip()` the shared parent so it becomes
  the host). Don't make the host rule smarter: making a floating parent the host would let later flow siblings
  cover the ring again, which is the problem in point 1.
- **Zoom.** `width` and `offset` are `Length`s and resolve per frame like everything else, so the ring scales with
  `gui.zoom()`. A `dp` ring stays a hairline at every zoom. That is a supported choice, and the demo should show
  both.

## 5. Steps

| | Work | Where | Size |
|---|---|---|---|
| **F1** | `FocusRing`, `Visibility`, `FocusCause`; `Theme.focusRing()` and the `of` overload; `PropKey.FOCUS_RING` and `Node.focusRing`. | `-core/style`, `-core/model`, `-core` | S |
| **F2** | `FocusEvent` gains `cause`; the four `setFocus` call sites pass theirs. | `-core/input` | S |
| **F3** | `TreeRenderer` records the focused node's rect, radii and alpha. Its ring host draws the ring after its flow children and before its clip pops and its floats paint, falling inward when it doesn't fit (§2.4). `GuiApp` and `GuiWindow` pass the focused id and cause. | `-core/app` | M |
| **F4** | Migrate `TextField` and `TreeView` (§3). Audit the twelve focusable widgets for "is the focusable node the visible one" (§4). | `-widget` | S |
| **F5** | Dim the ring while the window is inactive. | `-core/app` | S |
| **F6** | `focusCause` and `ringShown` on `SemanticNode`. | `-core/model` | S |
| **F7** | The demo chapter (§6). | `-demo` | M |
| **F8** | Switch `FocusRing.STANDARD` to `Visibility.KEYBOARD` once F7 has been looked at with the chapter open. | `-core/style` | XS |
| **F9** | Update [keyboard-focus-text.md §2](../reference/keyboard-focus-text.md) to describe what was built and point to `FocusRing`, then move this plan's essentials into the reference. Add sections and don't renumber them, per [docs/README.md](../README.md). | `docs/` | S |

F1–F3 is the smallest set that pays for itself: after it, every focusable node shows focus. F4 is needed
immediately after, because without it the two fields show a double ring.

## 6. The demo: a Focus chapter

A new `FocusChapter` in `vexelray-gui-demo/.../chapter`, added to `Gallery.chapters()` after `ChooserChapter`
(the other "controls" page). Its blurb: *"Tab through every control the framework ships. One ring, from the
theme, and no widget draws its own."*

**Top band: every focusable widget in Tab order.** A `TextField`, a `NumberField`, a `Select`, a `Segment`, a
`Toggle`, a `Slider`, a `Button` of each kind, and a three-header `Tabs`, in one row that wraps. Tab walks them
left to right and Shift+Tab walks back. This band is the claim in §1 made visible: before F3, half of it shows
nothing.

**Middle band: the controls for the ring itself**, built from the widgets the band above shows, so changing the
ring restyles the controls changing it:

- **Colour:** a `Segment` over `ACCENT`, `INK`, `DANGER` and `ACTION`, showing that it is a `Role` and so
  follows the palette.
- **Width:** a `Slider`, `0.0625rem` to `0.375rem`, plus a `Toggle` for "hairline (`dp`)", for the zoom point
  in §4.
- **Offset:** a `Slider` from `-width` to `+0.375rem`, labelled *inset ← on the border → outset*.
- **When:** a `Segment`, *Always* / *Keyboard only*. With *Keyboard only*, clicking the button shows no ring,
  tabbing to it does, and clicking the text field still does.
- **None:** a `Button` that sets `FocusRing.NONE`, so the chapter shows what the framework looked like before
  this plan.

Each control writes a new `FocusRing` into the theme the chapter's root uses. If the theme is still read once
at build time when this lands (the restyle limitation in `Theme`'s javadoc), the chapter sets it as a per-node
override on the top band's nodes instead, which also demonstrates §2.2.

**Bottom band: the cases §2.4 exists for**, each labelled with the rule it shows:

- *Fills its container*: a multiline `TextField` flush inside a bordered panel. The ring falls inward.
- *Inside a scroll viewport*: a short scrolling list of fields. Tab to one half scrolled out, and the ring is
  trimmed by the viewport, not drawn over the band's caption.
- *Next to its siblings*: three fields touching. The ring on the middle one is on top of both neighbours.
- *Under a popup*: a focused `Select` with its list open, and a field with a `Tooltip` over its edge. The popup
  covers the ring, because the ring is drawn before the root's floats.
- *Inside a rounded viewport*: a field flush against the rounded corner of a scrolling panel. The ring follows
  the corner instead of poking out of it, because it is drawn under the real clip.
- *Opted out*: a `TreeView` whose lead row is its focus, with `FocusRing.NONE` on the tree's root, and a caption
  saying why.

The `Console` on the chapter's activity rail logs every `FocusEvent` as `focus → <role> <name> by <cause>`. That
makes §2.3 readable without opening a debugger, and it is what F8's decision is made from.

**Verification.** `ottermate` can drive the chapter: open it from the rail, press Tab *n* times, read
`SemanticNode.ringShown` for each control (F6), and screenshot the fall-inward case. That screenshot and the
readouts go in F9's reference section as the record that it works.

## 7. Not in this plan

- **An animated ring** (fading or growing in on focus). `kronometer` could do it, and the ring is a visual
  transform, so it would fit. But motion on focus is a separate decision with its own accessibility question
  (reduced motion), and the static ring has to exist first.
- **Focus groups and roving tab index** (one Tab stop for a whole toolbar, arrows inside). `Tabs` already
  approximates this with `FOCUSED` claims on Left and Right. It is a Tab-order change, not a drawing change, and
  it belongs in keyboard-focus-text.md.
- **Rings on non-focus states** (a "current" marker, a drop target). `Cue.ring` covers one-shot use, and a
  standing non-focus ring is a widget's own picture.
