# vexelray-gui-file-icons

The file-type families as signed distance fields, in an atlas of their own: the eight sheets every file icon is cut
from, and each family's emblem, with the canvas's palette for each family and the extensions it covers. The art is
`src/main/icons` (`families.tsv`, `sheet/`, `emblem/`), copied from `vexelray-icons/file-types/families`, drawn on the
design canvas's "File type families (SDF)" board.

It is apart from `vexelray-gui-icons` because only an application that shows files draws these: a file browser, or
an editor's tabs and tree. One that does not never uploads this atlas, and one that does wants the whole set, so the
set is one module.

## Drawing one

```java
Node icon = FileIconView.node(gui, FileFamily.ofExtension("java"), Length.dp(16));
```

`ofExtension` takes the extension without its dot, in any case, and gives `UNKNOWN` for one no family claims;
`FOLDERS` is for a folder. The node is inert to the pointer, so a press lands on the row or tab that holds it.

## A family is two fields and a palette row

```java
FileFamily f = FileFamily.SOURCE_CODE;
boolean dark = FileIconView.dark(gui.theme());
Node sheet  = IconView.of(gui, f.sheet()).size(Length.dp(16)).tint(f.hue(dark)).node();
Node emblem = IconView.of(gui, f.emblem()).size(Length.dp(16)).tint(f.ink(dark)).node();   // in the same box, over the sheet
```

That is what `FileIconView` does. The sheet is drawn first, in the family's hue; the emblem over it, in ink. The
emblem is baked already clipped to its sheet, so the two fields share a box and a size. `FOLDERS` has no emblem
(`emblem()` and `ink()` are null).

**The colours are the suite's, as the shapes are.** Each family carries its row of `families.tsv`: a hue and an ink
for a dark theme and for a light one. A theme is dark when its ink is light. Each field is one solid colour, so an
application that wants other colours tints the two fields itself. Hover, focus, selection and height come from the
sheet field at run time, as glow, shadow and outline do for any icon: none of it is stored.

## What is here

```
FileSheet       generated: the eight silhouettes (page, folder, window, box, disc, nut, envelope, calendar), each a
                core IconSource.
FileFamily      generated: one constant per family, carrying its sheet, its emblem's cell, its palette row and its
                extensions; ofExtension finds one.
AtlasLayout     generated at build time: the atlas's size and its one grid.
FileIconAtlas   the atlas as a Pixels (gui-core): read from file-icons.rgba, uploaded by the renderer per device.
FileIconView    a family as a node, in the theme's row of its palette.
```

Every field is on the 16-unit grid, the families being drawn once for every size, and bakes like the small utility
icons: a 48-texel cell with a quarter again as margin, the sheets first, then the emblems.

## Changing the art

`FileSheet`, `FileFamily`, `AtlasLayout` and `file-icons.rgba` are not in the source tree: the `file-families` goal of
`vexelray-gui-icons-maven-plugin` writes them under `target` on every build whose art or table changed, with a picture
of the atlas at `target/vexelray-icons/file-icons.png`. When the canvas changes, copy `families.tsv`, `sheet/` and
`emblem/` from `vexelray-icons/file-types/families` into `src/main/icons` here, and build. `FileFamilyTest` checks they
agree: the atlas is the size the enums were generated for, every cell is inside it and its own, every cell holds its
shape with its margin outside, and every extension finds its family.

A constant is the family's name in capitals, hyphens as underscores, a leading number moved last: `disk-images` is
`FileFamily.DISK_IMAGES`, `3d-models` is `FileFamily.MODELS_3D`.
