# Working in this repo

`vexelray-gui` is the top of a stack of sibling checkouts under `C:\Users\User\Documents\GitHub\`. Most of what
is confusing here is not in this repo, so this file is the map. [README.md](README.md) is the tour;
[docs/architecture.md](docs/architecture.md) is the deep version.

## The siblings, and what lives where

All of these are the author's own code and are **modifiable**. `sibarum.*` reads like a third-party groupId but
is theirs — if the right fix is in tactroller, change tactroller. A cross-repo change needs the dependency
`mvn install`ed to the local `.m2` before this repo sees it.

| Repo | What it holds |
| --- | --- |
| **vexelray** | The engine. `vexelray-canvas` (2D `Canvas`, the SDF uber-shader, `Color`), `vexelray-text` (MSDF), `vexelray-vulkan` (Panama Vulkan, `SampledImage`, `SampledColorTarget`), `vexelray-os-*` (windowing, no GLFW) — **and `vexelray-surface` + `vexelray-technique-sdf`, which is where 3D content belongs.** See below. |
| **tactroller** | Input middleware. **Every** device event — keys, pointer, scroll, typed characters — flows through it. There is no side channel. |
| **atchung** | The typed bus: topics and versioned `State<T>`. Input in, mutations through. |
| **supirvast** | `vastir` — the expression IR the engine's shaders are authored in. |
| **kronometer** | Timing and animation, wrapped here as `vexelray-gui-krono`. |
| **vexelray-designer**, **calculator-vexel-demo**, **text-editor-vexel-demo** | Applications built on this framework. They are where a renderer or a technique lives when it is the consumer's rather than the framework's. |

## Drawing something: pick the lane before writing code

This is the choice that is most often got wrong, because `vexelray-gui-draw` is in this repo and the alternative
is not:

- **Flat marks in a box** — `Sketch` → `Picture` → `Node.picture(...)`. Charts, axes, grids, markers, labels,
  enclosure bands. Also exports as SVG, which is the one thing the other lanes cannot do.
  [docs/drawing.md](docs/drawing.md).
- **Shape in world space** — `dev.vexelray.surface.Surface` (repo `vexelray`), lowered by `SurfaceCompiler`,
  marched by `dev.vexelray.technique.sdf.SdfComposer`, into a target from `GuiApp.viewport(w, h)`, shown as
  `Node.image(...)`. **If the subject has depth, is lit, or will be orbited, it goes here** — a picture of it is
  a hand-maintained projection. [docs/architecture.md §6.9](docs/architecture.md), and
  [docs/drawing.md §7](docs/drawing.md) for the decision.
- **Usually both.** `vexelray-designer`'s `Viewport` marches the subject and sketches the grid over it, on one
  node. The subject is marched; the annotations are sketched.

**This repo does not depend on `vexelray-surface` or `vexelray-technique-sdf`** — the framework supplies the
target and the box that samples, and the *application* brings the technique (`vexelray-designer`'s pom has
`vexelray-gui-draw` and `vexelray-technique-sdf` side by side). So `Surface` will not be on the classpath while
you are working in this reactor. That is the layering, not a verdict on the lane.

Note the word collides: a *plot* surface (`z = f(x, y)` over a `Cell`, in `vexelray-gui-plot`) is unrelated to
the engine's `Surface`, and renders as enclosure boxes for the reasons in
[docs/reliable-plotting.md](docs/reliable-plotting.md).

## Constraints that are not visible in the code

- **Vulkan, the window and present stay on the main thread.** Startup latency is fixed cosmetically, never by
  moving them.
- **All input arrives through tactroller, on the bus.** Adding a device-event path that bypasses it is a bug
  even when it works.
- **Auto-repeat is command-keys-only.** Typed characters never repeat.
- **Nothing appears, moves or grows on hover.** Reserve the space; show a scrollbar on overflow, not on
  proximity.
- **No sealed `switch` and no throwing `default`.** Both put behaviour outside the type it belongs to; invert to
  a method or a sink. `Picture.Mark` (open) with `Picture.Sink` (closed, no defaults) is the pattern.

## Building

Siblings install in dependency order, then this repo:

```bash
cd ../supirvast && mvn install && cd ../vexelray && mvn install && cd ../tactroller && mvn install && cd ../vexelray-gui && mvn install
```

The interactive showcase, and the headless capture that works without an input backend:

```bash
mvn -pl vexelray-gui-demo -am compile exec:exec
```

```bash
mvn -pl vexelray-gui-demo exec:exec -Ddemo.args=--capture
```
