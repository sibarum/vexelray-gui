# vexelray-gui-file-icons

The file-type families as signed distance fields, in an atlas of their own: the eight sheets every file icon is cut
from, and each family's emblem. The art is `vexelray-icons/file-types/families`, drawn on the design canvas's
"File type families (SDF)" board.

It is apart from `vexelray-gui-icons` because only a file browser draws these. An application that does not show
files never uploads this atlas, and one that does wants the whole set, so the set is one module.

## A family is two fields

```java
FileFamily f = FileFamily.SOURCE_CODE;
Node sheet  = IconView.of(gui, f.sheet()).size(Length.dp(16)).tint(hue).node();
Node emblem = IconView.of(gui, f.emblem()).size(Length.dp(16)).tint(ink).node();   // in the same box, over the sheet
```

The sheet is drawn first, in the family's colour; the emblem over it, in ink. The emblem is baked already clipped
to its sheet, so the two fields share a box and a size. `FOLDERS` has no emblem (`emblem()` is null).

**Only the shapes are here, not the colours.** Each field is one solid colour, so the colour is the tint and
belongs to whoever draws it. The canvas's palette (a hue and an ink per family, dark and light) is
`vexelray-icons/file-types/families/families.tsv`. Hover, focus, selection and height come from the sheet field at
run time, as glow, shadow and outline do for any icon: none of it is stored.

## What is here

```
FileSheet       generated: the eight silhouettes (page, folder, window, box, disc, nut, envelope, calendar), each a
                core IconSource.
FileFamily      generated: one constant per family, carrying its sheet and its emblem's cell.
AtlasLayout     generated: the atlas's size and its one grid.
FileIconAtlas   the atlas as a Pixels (gui-core): read from file-icons.rgba, uploaded by the renderer per device.
```

Every field is on the 16-unit grid, the families being drawn once for every size, and bakes like the small utility
icons: a 48-texel cell with a quarter again as margin, the sheets first, then the emblems.

## Rebaking

From `vexelray-icons`, as that folder's README says:

```
java --enable-native-access=ALL-UNNAMED -cp "<imagelib-wrapper jar>;<vexelray-canvas jar>" tools/Bake.java . ../vexelray-gui
```

It bakes this atlas and `vexelray-gui-icons`'s together, rewriting `file-icons.rgba`, `FileSheet.java`,
`FileFamily.java` and `AtlasLayout.java` here, and `vexelray-icons/sheets/file-icons-atlas.png` to look at, each only
if its bytes changed. `FileFamilyTest` checks they agree: the atlas is the size the enums were generated for, every
cell is inside it and its own, and every cell holds its shape with its margin outside.

A constant is the family's name in capitals, hyphens as underscores, a leading number moved last: `disk-images` is
`FileFamily.DISK_IMAGES`, `3d-models` is `FileFamily.MODELS_3D`.
