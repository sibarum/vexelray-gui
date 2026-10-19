# Runtime themes: a theme is part of the ubershader

**Status: R1 landed 2026-10-19 (node paints, below); R0 and R2–R6 not started.** The measurement that makes this
checkable is a capture diff: build the demo in `Theme.DARK`, swap to `Theme.LIGHT`, settle and capture; build it
fresh in `Theme.LIGHT` and capture; diff the two. The plan is done when that diff is empty, and stays empty under a
guard. Today it is not, and R0 is to put the number here: every site in §5 is a region of it.

The decision this plan rests on, from the owner: **a theme can change anything about the ubershader.** Not a table of
colours the shader reads, but a contribution to the composition, the way an effect is. A swap is therefore allowed to
take seconds, as long as the UX says so. Everything below follows from taking that literally, and from the rule the
engine already lives by (`SdfRaymarchTechnique`): **a value is a write, a shape is a new pipeline.**

---

## 1. What a theme decides, in two layers

- **Values**: what colour a role is, in a state; whether a surface is lit. Today's `Theme` (a `Palette`, a
  `Shading`, a `Relief`, two flags) is all values. A swap between two themes that differ only in values is a write to
  a table and costs one frame.
- **Code**: how a surface is lit, what a hover does to a fill per pixel, a texture under the page, a painter no other
  theme has. A theme contributes shadelets to the composition. A swap between two themes whose contributions differ
  is a new manifest, a new SPIR-V, a new pipeline, and that is the swap that takes seconds.

One API serves both. `Gui.theme(t)` says what is wanted. The framework works out which layer changed: a values-only
swap lands in the next frame, and a code swap lands when its pipeline does. The caller never has to know which kind
of theme it handed over.

## 2. R1 — node paints (landed)

A node holds a `Paint` (`Role`, `Role.at(state)`, `.alpha`, `.mix`, `Paint.of(Color)` for colours no theme decides)
and a `Finish` (`Finish.LIT`, `Finish.LETTERPRESS`). The renderer resolves them against the tree's theme every frame.
`Gui.theme(t)` posts `Mutation.Retheme`, applied with the frame's other edits. `Reconciler.retheme` reports every
colour the swap changes to the `PaintMotion` (including text in the theme's ink that never stated a colour), so
`Fades` cross-fades the whole tree. `Gui.drawnTheme()` is the renderer's theme, so a swap is drawn in the frame its
fades start. `RethemeTest` in core pins this. `RethemeGuardTest` fails the build on any framework call to
`Node.background(Color)`, `textColor(Color)`, `border(Length, Color)`, `Theme.lit()`, `Theme.letterpress()`, or
`Paint.of(theme.color(..))`. About thirty widget, keymap, automation, typeset and demo classes were converted to pass
it.

R1 resolves on the CPU, per node, per frame. R2 moves the same resolution behind an id, so composite values can
carry it.

## 3. The phases

### R0 — measure

- The capture diff above, as a harness test that fails today, with its pixel count written into this file's first
  screen.
- The two numbers no document has: lowering the canvas composition in-process (`CoreToSpirv`, on a worker), and
  `vkCreateGraphicsPipelines` for it on the main thread. The only measured pipeline is a surface-compiled SDF shader
  at five seconds, which is not this one. These numbers decide how much UX §4 has to cover.

### R2 — paints as ids: the paint table

Composite values (marks, icon tints, effect styles, spans) cannot hold a `Paint`. `vexelray-gui-draw` and
`vexelray-canvas` know nothing of themes, and must not. They can hold an **index**.

- `vexelray-canvas` gains a colour that is either literal RGBA or a **slot**: an index into a paint region of the
  shadelet tables buffer (set 2, binding 0), beside the look and style tables. Painters read every colour through one
  generated `paint(...)` accessor, so whether a colour is a literal or a slot is invisible to a shadelet, as whether a
  style field is shared or per instance already is. The encoding (a flag bit in the vertex colour, or a record field)
  is settled by building it.
- Core interns each distinct `Paint` it is asked to draw into a slot (`Paint` equality is already by value) and
  resolves every live slot against the drawn theme into the region: on a swap, and when a slot is first used. A
  values-only theme swap is one write of `4 × slots` floats and zero new looks.
- `Role` stays open. A role an application writes at runtime is interned like any other paint. Nothing is registered
  and no shader changes, because the role is resolved on the CPU, in Java, as today.
- A fade between two themes is a per-instance `t` between two slots, which `shadelets.md` already anticipated ("a
  colour animating between two themes").

### R3 — composite values carry slots

- `Picture` marks (`Fill`, `Outline`, `Line`, `Text`) take the canvas's slot-or-literal colour. Core hands a picture
  a slot by asking for `paint.slot()`-shaped values through a small seam, so a widget writes `Role.LINE` into a sketch
  as it writes it into a node. `SvgSink` resolves slots through a resolver passed in (the theme it is exporting in),
  because a file has no table.
- `ImageField.tint` and colour fields in effect styles (`OutlineStyle.colour`, `GlowStyle`, …) become the same type.
  This also removes a leak: today every distinct outline colour appends a look that `LookTable` never evicts, so each
  theme swap would mint looks until `put` throws at 65,536 floats.
- `Span` foreground and background.
- `Cue` colours. A cue is short enough that a swap mid-cue may finish in the old colour. With slots it does not have
  to.

### R4 — a theme's code: contributions and a live recompose

- `Theme` gains its composition contribution: the shadelets it adds or replaces (a lit painter, a shading curve, a
  page texture). `Theme.DARK` and `Theme.LIGHT` contribute nothing beyond the core set, so they swap by R2 alone.
- `GuiApp` holds its `Composed` as a value that can change. A theme whose contribution changes the manifest is
  composed and lowered on a worker (`CoreToSpirv` is pure Java; `ShaderCache` keys it structurally, so returning to a
  theme seen before is a cache hit). The pipeline is built on the main thread, because Vulkan stays there. It is then
  swapped into each window's presenter between frames: the presenter's `final` pipeline becomes a field it replaces,
  and the offscreen capture pipeline follows.
- A new manifest renumbers shadelets, so the `LookTable` is rebuilt for it and every window re-uploads. Looks are
  keyed by value, so the rebuild is a replay, not a migration.
- The precompiled path stays first: an application that ships its themes names their compositions, the maven plugin
  lowers each into the jar, and a swap between shipped themes is a resource read plus a pipeline build. Lowering at
  runtime is the fallback for a theme the jar did not know, said once through `Diagnostics`, as a stale canvas shader
  already is.

### R5 — the UX of a swap that takes time

- The swap is taken at once and shown as **pending**: the request is acknowledged where it was made (the toggle moves
  and says it is applying), and nothing else changes yet. A values-only swap never shows this state.
- When the pipeline lands, the window cross-fades from its last frame to its first frame in the new theme: the old
  frame is kept as an image and drawn over the new one, fading out, as one cue on the root. Values and code arrive
  together, so there is never a frame of new colours over old lighting.
- A swap requested while another is pending replaces it. Only the last one asked for is lowered to completion.
- Vulkan, the window and present stay on the main thread throughout. The wait is covered cosmetically, never by
  moving them.

### R6 — the guard and the gate

- `RethemeGuardTest` grows to the R3 surfaces: no framework class hands a theme-resolved `Color` to a mark, a tint,
  a style, a span or a cue.
- The capture-diff test from R0 passes, for a values-only swap and for a swap between two themes with different
  contributions. That is the done criterion.

## 4. What stays a CPU decision

- **`Relief`** (elevation rungs) resolves to a `Length` when a widget writes it. `Theme.DARK` and `Theme.LIGHT`
  share `Relief.STANDARD`, so no stale pixel exists today. A theme with its own relief needs the same treatment as
  `Finish`: a node holds the rung, and the renderer resolves it.
- **`IconEffects`** (`Theme.icons()`) is read by `IconButton` when it repaints. The same applies.
- **The Vulkan clear colour** is read once at startup (`Demo.java`, `Color page = gui.theme().color(Role.PAGE)`). The
  root paints `PAGE` over all of it, so it shows only where nothing is drawn. It should follow `drawnTheme()` per
  frame.

## 5. What R1 left resolving early

Each of these is a region of R0's diff, and each moves with the phase named:

| Site | What | Phase |
| --- | --- | --- |
| `Tabs.java` (indicator, `Picture.Fill`) | the selected tab's underline | R3 |
| `Slider.java` (tick `Sketch`) | tick dots | R3 |
| `TitleBar.java` `Caption.paint` | caption-button glyph pictures, plate colours, a `Color.lerp` | R3 |
| `Board.java` (rim `Picture.Outline`) | the rim round a carried card | R3 |
| `ListView.java`, `TreeView.java` (`DropIndicator.of`) | drop ring and line; TreeView builds its default once | R3 |
| `AutomationWindow.java` `lamps()`, `AutomationPanel.java` `paintLamp()` | instrument lamp pictures | R3 |
| `IconView.java` `paint()`, `look()` | icon tint and outline band | R3 |
| `FileIconView.java` `dark(theme)` | picks the dark or light icon family once | R3 (or a role per family) |
| `TextField.java` find highlight (`Span.background`) | search-match wash | R3 |
| `Button.java` ripple, demo Motion/Text/Undo cues | one-shot cue colours | R3 |
| `Popout.java` `away.theme(host.theme())` | a popped-out window copies the host's theme once | R1 follow-up: forward the host's swaps |
| demo `Chart.java`, `PlotChapter.java` | sketch colours | R3 |
| demo `TextChapter.java` spans | accent and line spans | R3 |
