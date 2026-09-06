# Drawing

A **picture** is an ordered list of marks in one pixel frame. It is authored once and consumed twice: by the
engine's `Canvas`, which puts it on the screen, and by an SVG writer, which puts it in a file.

`vexelray-gui-draw` — `Picture`, `Sketch`, `CanvasSink`, `SvgSink`, `Svg`. Below `-core`, depending on nothing but
the engine's canvas and text, because a picture knows nothing of nodes, layout, themes or the bus.

---

## 1. The gap it fills

Before this, an application that wanted marks on the screen had two ways to get them, and nothing in between.

**One node per mark.** A node is the layout engine: it is measured, positioned, hit-tested, reconciled against the
previous frame and published into the read model. That is exactly right for a button and absurd for a grid line.
The calculator demo's curve renderer is the honest measure of the cost — a plot is some two hundred floating
nodes, none of which participates in layout, all of which are replaced wholesale the moment the panel resizes.
And it cannot draw a diagonal at all: `NodeKind` is `{BOX, TEXT}` and both are axis-aligned.

**A GPU viewport.** A render target, a pipeline, a set of shaders, and a march — for two lines and a label.

A picture is the middle. It is not laid out, not hit-tested, and not reconciled mark by mark; it is a value, and
replacing it is one prop write.

> **That second lane has since become cheap, and this section is no longer the whole choice.** A target is one
> call (`GuiApp.viewport(w, h)`) and the march is a component (`vexelray-technique-sdf`), so a marched scene now
> costs a dependency rather than a rendering project. What has not changed is the *fit*: a picture is flat marks
> in a pixel frame, and a marched surface is a shape in world space. **§7 is where that choice actually lies —
> read it before reaching for a `Sketch` to draw something that has depth.**

## 2. What it looks like

```java
Picture figure = new Sketch()
        .tag("grid").line(x, top, x, bottom, 1, grid)
        .tag("curve").polyline(xs, ys, 2, accent)     // a diagonal, which a node cannot be
        .tag("marker").circle(px, py, 3.5, accent).ring(px, py, 6.5, 1.5, accent)
        .tag("label").text("peak", px + 10, py - 6, 11, ink)
        .picture();

node.picture(figure);                                  // on screen
String file = Svg.document(figure, w, h);              // in a file
```

`vexelray-gui-demo`'s Chart tab is this, in full, against a measured box.

## 3. The alphabet, and what bounds it

`Picture.Sink` is the closed set of operations a consumer implements. `Picture.Mark` is **open**: an application
may define its own kind, so long as it can say what it is in terms of the sink. Same inversion as
`Placed.Draw`/`Placed.Sink` in `-typeset`, and for the same reason — a switch over a sealed draw type is
hand-written dispatch, and it puts the behaviour somewhere other than the type. A mark kind written after a
consumer still works there, because it can only express itself in operations that consumer already handles.

Four operations:

| | |
|---|---|
| `fill` | a rounded box — so also a rectangle, and a circle |
| `outline` | that box's inside edge, one stroke wide |
| `line` | a line at **any angle**, round-capped |
| `glyphs` | one run of text on a baseline |

**What the four are is not a matter of taste: the alphabet is what the engine's rounded-box SDF can actually
draw.** Each operation is one canvas primitive, so a picture costs the vertices its marks need and nothing else —
no emulation on one side, no approximation on the other.

Which is why **there is no filled polygon and no curve**, and that is a statement about the engine rather than
about this module. The uber-shader has no triangle or convex-polygon kind, so a polygon could only be faked on the
canvas while SVG drew it exactly — and a picture that means two different things on two targets is worse than one
that cannot express the shape. Widening `Sink` therefore has a prerequisite, and the prerequisite lands in
`vexelray`, not here.

A polyline is not an exception: it is one `line` per segment, so it needs no operation nobody has implemented, and
the round caps the canvas draws anyway are what make a joint look joined.

`Sink` has **no default methods**, deliberately. A target that silently dropped one of four operations would be a
bug that looks like a blank area, so the compiler is the right place to catch it. (The opposite tuning from an
event sink, where ignoring most of the stream is the normal case.)

## 4. On screen: a box that paints

`PropKey.PICTURE` — `Node.picture(Picture)`, `RetainedNode.picture()`, drawn by `TreeRenderer`.

**Not a node kind**, for the same reason a viewport is not one (§6.9 of architecture.md): a drawing is a box that
paints, so it takes its size from the box it already is. Width, height, flex, corner radius, border, clip and the
subtree transforms all apply unchanged, and putting a drawing on a node moves nothing. It draws over the image and
under the border, so a background shows through and a border frames it.

**Clipped to the box.** Everywhere else in the framework a box's contents were laid out by the framework, so "it
stays inside" is an invariant of the layout. A picture is authored geometry with no measure pass behind it: an
application computes marks from data, and they are as right as its arithmetic. So here the clip is the guarantee
rather than an assertion about it, and a missing one looks like nothing at all until the day the data is wider
than the axis.

The clip is pushed at the node's **displaced** position — a canvas clip is in screen coordinates while a node's
box is not, so a clip pushed at the box's own numbers would stay where the node *would* have been. Mid-slide that
shears the drawing off against a rectangle nothing is being drawn in: invisible at both ends of a transition and
obvious for the 160ms in between. (A container's clip is anchored on purpose — that is what lets a child slide
inside it. A node's own picture is not a child.)

**In pixels, and therefore rebuilt.** The renderer resolves no units of its own — every length a node carries was
resolved by the layout pass — and a picture cannot be scaled in the renderer without it becoming the one place
that does. So a picture is authored for the box that was measured, exactly as a typeset block re-projects when its
basis changes, and a consumer rebuilds it from `onResize`.

Which lane is a real choice. A picture is clipped to its box, so a figure a frame behind its panel visibly does
not fit while a window is being dragged — that is the misalignment flicker, and it is the thing a drawing must not
do. `onResizeUi` lands in the same frame as the layout it reacts to and is the right lane whenever the rebuild is
cheap (the demo's chart is a hundred marks and ninety-six sines). Reach for `onResize` when it genuinely is not,
and accept the frame.

## 5. In a file: the same picture, not a redrawing of it

`SvgSink` writes one element per mark; `Svg.document` wraps them. SVG's coordinate system is the canvas's, so the
numbers go straight out. Two things a vector format can say that a vertex buffer cannot, and they are used in
exactly two places:

- **Colour is a presentation attribute** (`fill=`, `stroke=`), which in CSS loses to every stylesheet rule. A host
  page can restyle an exported picture by class without editing it, and a picture opened with no stylesheet still
  looks like it did on screen. Both, from one output.
- **A mark's tag becomes its `class`** — the whole reason a tag is carried. "These are the grid lines, that is the
  curve, those are the markers" survives the export, so the semantics the application knew are still there for a
  reader that never saw the application. `Sketch.tag(String)` stamps it onto every mark that follows, the same
  stamping idiom the canvas uses for its clip and its translation.

Smaller decisions, each with a reason worth keeping:

- **An outline is inset by half its width.** SVG strokes straddle the path; the canvas's hug the inside edge of
  the box. Without the inset the same numbers would describe a ring half a stroke wider in the file than on
  screen.
- **A box with nothing straight left is written as `<circle>`.** Identical pixels to `<rect rx>`, but a plot's
  markers are circles and someone opening the file to restyle them should find the element they went looking for.
- **A two-radius box becomes a `<path>`.** It is the one shape `<rect>` cannot describe, having a single `rx`.
- **Transparency is its own attribute**, not baked into an `rgba()`. A wash is geometry-adjacent — an enclosure
  band means "somewhere in here" — so overriding the colour must not make it opaque.
- **Text is placed, not measured.** A run's baseline `y` goes straight onto `<text>`, which anchors on the
  baseline too. The advances are then the reader's font's rather than the atlas's, so a label sits exactly where
  the picture put it and is exactly as wide as the viewer's font makes it. That is the honest trade for a
  resolution-independent file; the alternative is converting every glyph to a path, which is a font-embedding
  problem rather than a drawing one. The family is declared once on the root, where a stylesheet can override it.
- **No background.** A drawing is transparent where it drew nothing, and a viewer supplies its own page.

The one deliberate difference between the targets: **a mark with no colour** is written as `fill="none"` rather
than dropped. The canvas cannot draw an unpainted shape, while an unpainted element with a class is precisely what
a host stylesheet is for.

## 6. Colours, not roles

A mark carries a resolved `Color`, like `Node.background(Color)` does — the application asks the theme when it
decides what the mark *is*. A picture holding `Role`s would need the renderer to resolve per frame, which is a
change to how every prop is themed (see the runtime-theme-swap note in todo.md §3) rather than something this
module should decide on its own.

## 7. When it should be a marched surface instead

A picture is **flat marks in the box's own pixel frame**. The engine's `Surface` is **shape in world space**:
`dev.vexelray.surface.Surface` (repo `vexelray`, module `vexelray-surface`) is a sealed record tree of spheres,
boxes, capsules and their transforms and combinators, lowered by `SurfaceCompiler` to core IR, turned into a
fullscreen ray-march fragment by `dev.vexelray.technique.sdf.SdfComposer`/`SdfScene`, marched into a target from
`GuiApp.viewport(w, h)`, and shown as `Node.image(SampledImage)` — the box that samples, architecture.md §6.9.

**The test is the subject, not the complexity.** If the thing being drawn has depth, is lit, or is something the
user will orbit, it is a surface, and any picture of it is a projection maintained by hand — one that has to be
re-derived every time the camera moves and re-authored every time the shape gains a case. Reach for a picture
alone when the figure genuinely *is* flat (a chart, an axis, an enclosure band, a plot overlay), or when the SVG
export is the point, which is the one thing a marched image cannot give back.

**Not this framework's dependency, and deliberately.** `vexelray-gui` builds against `vexelray-canvas`,
`-text`, `-vulkan` and `-os` — not `vexelray-surface` or `-technique-sdf`. The framework supplies the target and
the box that samples; the application brings the technique, exactly as `SurfacePlot` is the consumer's in
reliable-plotting.md. So `Surface` is not on this repo's classpath, which is a fact about the layering and not a
verdict on the lane. (`vexelray-designer` is the worked example: `vexelray-gui-draw` and `vexelray-technique-sdf`
side by side in one pom.)

**Two meanings of the word, and they are unrelated.** `dev.vexelray.surface.Surface` is the engine's 3D distance
field. A *plot* surface — `z = f(x, y)` over a `Cell`, in `vexelray-gui-plot` — is a different thing that happens
to share the noun, and it renders as enclosure boxes rather than as a marched field, for the reasons in
reliable-plotting.md.

**Usually both, on one node.** `vexelray-designer`'s `Viewport` is the shape worth copying: the design is
compiled to a distance field and marched for the image, and a `Sketch` draws the ground grid *over* it — between
the image and the border, clipped to the box, one prop write to toggle, no shader and no recompile. The subject
is marched; the annotations are sketched. Two things that pattern has to get right:

- **The two layers must agree about projection by construction.** There, `Camera.project` is orthographic while
  the march builds a perspective ray, so the viewport inverts `SdfComposer`'s own ray construction rather than
  deriving a second projection that happens to match. Two derivations that agree today drift tomorrow.
- **A drawn overlay is not occluded.** A sketched line behind a marched sphere still draws in front of it, until
  the march's hit depth is available. For a grid that reads as an overlay anyway; for anything meant to sit
  *inside* the scene it is wrong, and that is a reason to put it in the `Surface`.

**Two kinds of dirty**, once a viewport exists: a scene change means new SPIR-V and a new pipeline, while a
camera change is six floats of push constant. Orbiting recompiles nothing. A picture has neither — it is rebuilt
from `onResize`, §4.

## 8. Still ahead

- **A filled polygon**, once the engine has a triangle or convex-polygon kind. The first things that will ask are
  an enclosure band and a projected plot-surface cell (which currently draws as its screen bounding rectangle).
- **Typeset onto a picture.** `Placed.Sink` is `glyphs` + `bar`, and its node projection is axis-aligned — so a
  commutative-diagram arrow is unrepresentable there and expressible here. A picture is the target that unblocks
  it, and the two sinks are close enough that it is a projection rather than a redesign.
- **Hit-testing a mark.** Nothing here is hit-tested, by design. A picture that wants a hover is a picture with a
  node over it today; whether a mark should be addressable at all is a real question, and the `tag` is where the
  answer would attach.
