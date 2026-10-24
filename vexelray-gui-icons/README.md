# vexelray-gui-icons

The suite's icons as signed distance fields, and the shared set every application includes: every mask in
`src/main/icons` baked into one atlas, and the `Icon` enum that names each drawing. A widget or an application names an
icon; the renderer uploads the atlas itself, once per device, the first frame anything draws from it. Nothing at run
time reads an SVG or decodes an image.

```java
Node save = IconView.of(gui, Icon.SAVE).size(Length.dp(16)).tint(Role.ACCENT).node();
```

## What is here

```
src/main/icons/  the art: small/<name>-mask.svg and large/<name>-mask.svg, copied from vexelray-icons/utility
Icon             generated: one constant per drawing, with its cells. A BakedIcon, and so core's IconSource, so a
                 menu item, a tree action or a tree row can carry one without core knowing the set.
IconAtlas        generated: the atlas as a BakedAtlas, read from icons.rgba, uploaded by the renderer per device.
BakedIcon        what any baked set's constants are: a drawing at each IconSize, or one standing in for both.
                 Icon is one; an application's own set is another, and goes everywhere an Icon goes.
BakedAtlas       a baked set's atlas (a Pixels, gui-core): its size, its grids, and the cell of each drawing.
IconSize         which of the two drawings: SMALL (the 16-unit pixel grid, 24 px and under) or LARGE (64-unit).
IconView         a node showing one icon: size, tint by role or colour, glow and shadow as shares of its size, and the
                 drawing re-chosen against the pixels the layout gives it.
```

`Icon` and `IconAtlas` are not in the source tree: `vexelray-gui-icons-maven-plugin` writes them under
`target/generated-sources` on every build whose art changed, with `icons.rgba` and the set's manifest (below) under
`target/generated-resources`, and a picture of the atlas, the fields as grey, at `target/vexelray-icons/Icon.png`.

`IconButton` (an icon-only button with a name, a tooltip and a lit toggle state) is in `vexelray-gui-widget`, which
depends on this module; so does every widget that shows an icon (title bar, tree, menus, find bar, breadcrumb, table,
select, inspector, popout, rail, dialogs). Each takes an `IconSource`, so each takes an application's own icons too.

## Why a field, and why baked

A distance field is a shape the shader cuts at its edge, one screen pixel soft at any size: one small texture is the
icon at 12 px and at 96 px, in whatever colour the theme says, turned or scaled without losing an edge, and the
distance past the edge is a glow or a soft shadow for nothing more. A state that changes an icon's colour or effect
changes four numbers on a node, not a texture.

The fields are made at build time by `vexelray-gui-icons-maven-plugin`, the way `vexelray-msdf-maven-plugin` makes the
font atlas: rasterised finely through imagelib, an exact Euclidean distance transform (vexelray-canvas's
`DistanceField`), averaged down, packed. The atlas is in the font atlas's layout (`VXPX`, then zlib'd RGBA8), so the run
time inflates it with `java.util.zip` and needs neither imagelib nor an image decoder: nothing native, nothing for
native-image to ship beside the executable, and nothing on the startup path but a read. The generated atlas class opens
its resource by a constant name, so a native image includes it with no resource configuration.

Large art bakes into 64-texel cells and small art into 48-texel cells, each with a quarter of its size again as
margin: as far as a glow or a shadow reaches. Every cell's border reads as far outside, so neither filtering nor a
shadow sampled up into the cell above picks up a neighbour. The atlas's width is chosen to leave it about square.

## Changing the art

The art is drawn on the canvas and extracted into `vexelray-icons`, as that folder's README says. Copy the masks that
changed or were added into `src/main/icons/small` and `src/main/icons/large` here, and build: the bake reruns when any
mask, or the plugin's configuration, differs from the last one. `IconTest` checks the atlas and the enum agree: the
atlas is the size the enum was generated for, every cell is inside it, and every cell holds its shape with its margin
outside.

An icon's constant is its file name in capitals, hyphens as underscores: `pin-up` is `Icon.PIN_UP`. Renaming or
removing a drawing is a compile error in whatever used it, which is the point of an enum.

## An application's own icons

An icon only one application draws is that application's, not the suite's: it is baked in the application's build, as
a set that **extends** this one. The extension takes this set's grids, so its icons sit beside the suite's at the same
weight and with the same reach of glow and shadow, and it may use none of this set's names, so `save` means one drawing
everywhere. It is its own small atlas and its own enum, a `BakedIcon` like `Icon`, so it goes into an `IconView`, an
`IconButton`, a menu item's mark or a tree row exactly as an `Icon` does.

```xml
<plugin>
  <groupId>dev.vexelray.gui</groupId>
  <artifactId>vexelray-gui-icons-maven-plugin</artifactId>
  <version>${vexelray-gui.version}</version>
  <executions><execution><goals><goal>bake</goal></goals></execution></executions>
  <configuration>
    <extends>dev.vexelray.gui.icons.Icon</extends>
    <packageName>com.example.paint</packageName>
    <enumName>PaintIcon</enumName>
    <directories>
      <directory><path>src/main/icons/small</path><size>small</size></directory>
      <directory><path>src/main/icons/large</path><size>large</size></directory>
    </directories>
  </configuration>
</plugin>
```

An application generated from `vexelray-framework-template` has this already, switched on by the folder: put art in
`src/main/icons/small` or `large` and the build makes `<App>Icon` in the application's package.

The art can be any of three kinds, told apart by itself or named with `<coverage>`:

| Art | The shape is | `<coverage>` |
| --- | --- | --- |
| SVG | where it is opaque, as `vexelray-icons`' masks are drawn | `alpha` (`auto` for SVG) |
| a black-and-white raster (PNG, BMP, JPEG, TGA, TIFF, WebP, GIF, QOI) | the dark, over white; a transparent background is background | `luminance` (`auto` when every pixel is opaque) |
| a raster mask with transparency | where it is opaque, whatever its colour | `alpha` (`auto` when any pixel is not opaque) |

`<invert>true</invert>` takes the other part: light on dark, or the hole in a mask. A raster is fitted into the cell
with its aspect kept and read smoothly between its pixels, so a 32 px drawing becomes a clean-edged shape rather than
its pixel staircase; draw it at the size of the large cell or above for the large drawing. An icon whose file is not
named for it is named outright:

```xml
<icons>
  <icon><name>brush</name><small>art/brush-16.png</small><large>art/brush-64.png</large></icon>
</icons>
```

A drawing at one size only stands in at the other. A name the suite's set already holds fails the build, naming the
`Icon` constant to use instead.

## Art pairs: a mark to condense

A **mark** shown large, the way the About window shows an application's and the framework's, is baked as an **art
pair** rather than an icon: its shape as a distance field and its colour, the drawing itself, side by side in one atlas
per size it is shown at, so a look reads both from the texture it has bound. The plugin's `pair` goal bakes them; the
run time reads them with `ArtPair`, by the name they were baked under, beside a class.

```xml
<execution>
  <id>about-art</id>
  <goals><goal>pair</goal></goals>
  <configuration>
    <pairs>
      <pair>
        <art>src/main/rc/pix.svg</art>     <!-- a square SVG -->
        <name>pix</name>                   <!-- pix.pair and pix-<size>.vxpx at the class path's root -->
        <sizes>104,208,312,416</sizes>     <!-- the colour at 1x to 4x of the size it is shown at -->
        <field>160</field>                 <!-- the field's square, texels: fields scale, so one serves every size -->
        <margin>0.6</margin>               <!-- room round the shape, a share of its square, for effects to reach -->
        <lift>orb</lift>                   <!-- optional: a top-level element left out of the shape -->
      </pair>
    </pairs>
  </configuration>
</execution>
```

- **The field is in the MTSDF layout at two ranges.** Alpha is the true distance across the whole margin; red, green
  and blue are the same distance over an eighth of that range, eight times as precise at the edge, where precision
  shows. One byte cannot be both: sixty pixels of margin in 255 steps is a visible staircase in a contour drawn from
  it. The three near channels are equal for now (the median of three equal values is the value), so a true
  multi-channel field drawn from outlines drops in later without a reader changing. `Condense.MTSDF` in
  vexelray-shadelets reads it.
- **The colour is premultiplied**, so a bilinear read never darkens toward a transparent neighbour.
- **A lifted part** is kept in the colour and left out of the shape, and where it sits is recorded (`ArtPair.lift`):
  for a part the application draws live and lands where the drawing has it. The VexelRay mark's orb is one.

An application's About window finds its pair by its icon's name: `@VexelApp(icon = "/pix.ico")` looks for `/pix.pair`.
Without one, the window shows the icon instead.
