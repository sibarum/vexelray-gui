# vexelray-gui-icons

The suite's icons as signed distance fields: every mask in `vexelray-icons` baked into one atlas, and the `Icon`
enum that names each drawing. A widget or an application names an icon; the renderer uploads the atlas itself, once
per device, the first frame anything draws from it. Nothing at run time reads an SVG or decodes an image.

```java
Node save = IconView.of(gui, Icon.SAVE).size(Length.dp(16)).tint(Role.ACCENT).node();
```

## What is here

```
Icon            generated: one constant per drawing, with its cells. Implements core's IconSource, so a menu item,
                a tree action or a tree row can carry one without core knowing the set.
AtlasLayout     generated: the atlas's size and its two grids.
IconAtlas       the atlas as a Pixels (gui-core): read from icons.rgba, uploaded by the renderer per device.
IconSize        which of the two drawings: SMALL (the 16-unit pixel grid, 24 px and under) or LARGE (64-unit).
IconView        a node showing one icon: size, tint by role or colour, glow and shadow as shares of its size, and the
                drawing re-chosen against the pixels the layout gives it.
```

`IconButton` (an icon-only button with a name, a tooltip and a lit toggle state) is in `vexelray-gui-widget`, which
depends on this module; so does every widget that shows an icon (title bar, tree, menus, find bar, breadcrumb, table,
select, inspector, popout, rail, dialogs).

## Why a field, and why baked

A distance field is a shape the shader cuts at its edge, one screen pixel soft at any size: one small texture is the
icon at 12 px and at 96 px, in whatever colour the theme says, turned or scaled without losing an edge, and the
distance past the edge is a glow or a soft shadow for nothing more. A state that changes an icon's colour or effect
changes four numbers on a node, not a texture.

The fields are made once, by `vexelray-icons/tools/Bake.java`, the way `vexelray-msdf-maven-plugin` makes the font
atlas: rasterised finely through imagelib, an exact Euclidean distance transform (vexelray-canvas's `DistanceField`),
averaged down, packed. The atlas is committed in the font atlas's layout (`VXPX`, then zlib'd RGBA8), so the run time
inflates it with `java.util.zip` and needs neither imagelib nor an image decoder: nothing native, nothing for
native-image to ship beside the executable, and nothing on the startup path but a read.

Large art bakes into 64-texel cells and small art into 48-texel cells, each with a quarter of its size again as
margin: as far as a glow or a shadow reaches. Every cell's border reads as far outside, so neither filtering nor a
shadow sampled up into the cell above picks up a neighbour.

## Rebaking

When the art changes (drawn on the canvas, extracted into `vexelray-icons`, as that folder's README says), from
`vexelray-icons`:

```
java --enable-native-access=ALL-UNNAMED -cp "<imagelib-wrapper jar>;<vexelray-canvas jar>" tools/Bake.java . ../vexelray-gui
```

It rewrites `src/main/resources/dev/vexelray/gui/icons/icons.rgba`, `Icon.java` and `AtlasLayout.java` here, and
`vexelray-icons/sheets/atlas.png` to look at. Each file is written only if its bytes changed. `IconTest` checks the
three agree: the atlas is the size the enum was generated for, every cell is inside it, and every cell holds its
shape with its margin outside.

An icon's constant is its file name in capitals, hyphens as underscores: `pin-up` is `Icon.PIN_UP`. Renaming or
removing a drawing is a compile error in whatever used it, which is the point of an enum.
