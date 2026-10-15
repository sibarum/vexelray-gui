# Font families: every face baked at build time, every atlas loaded at startup

Status: **approved 2026-10-07; steps 1–3 and 5 done; step 4 half done and step 6 waiting on font files.** This plan replaces the runtime-generated atlas proposed
on the same day. The owner's reasoning for the change is §1, and it is the rule the rest follows.

**Measured after step 1** (local run, system fonts, not committed):
- Consolas gave 4 faces and Calibri 6. Arial + Arial Narrow became one family with a `condensed` face.
- Bahnschrift, a variable font, gave 15 named instances.
- That is 25 faces in all, and 98 MB of texture.
- Power-of-two sizing would have cost 220 MB for the same faces, which is why the atlases are sized tight
  (`-square4`).
- A second build skips every face in 0.4 s.

Done means:
- A consumer names a font family in its pom and gets one atlas per face, with every glyph the font has.
- At runtime, a span can say bold or italic.
- That span's caret positions, line breaks and alignment indent come from the bold or italic face's own advances.
- A headless test asserts both points above.

## 1. The rule: memory that varies at runtime is instability

A runtime-grown atlas uses VRAM that depends on which documents are opened, and in what order. Today fonts cost well
under 100 MB. Trading a little VRAM for behaviour that varies with input is not a fair trade, so:

- **Every atlas is decided at build time and loaded at startup.** If it was included, assume something uses it.
  Nothing is generated, evicted or loaded lazily.
- **Coverage defaults to the whole font.** Lists of code points in poms are opt-in narrowing, not the price of
  having text.
- **The build says what it costs.** Every face's texture bytes and the total are printed on every build. An
  optional budget fails the build, not the app.

## 2. Leaving dynamic memory possible later, at no cost now

We are not building a document editor. If memory from fonts ever has to become dynamic, these keep that change
local:

1. **One atlas per face.** A face is the unit that could be resident or not. No image is shared between faces, so
   unloading one never means repacking another.
2. **Metrics and pixels are separate files** (`.json`, `.rgba`). This is already true.
   - Measurement reads only metrics, so it can never depend on whether pixels are resident.
   - That makes the only thing that could ever be late the ink. Late ink drawn at its true advance needs no
     relayout: a placeholder the glyph's own width leaves every caret, break and indent where it will end up.
3. **A manifest (`fonts.json`)** lists every family, face, style, coverage summary and byte size, without loading a
   pixel. A residency policy would read it, not the atlases.
4. **Draws name a face, not a texture.** A text run carries a face id, and the texture is looked up from the
   registry when the frame is recorded. Making a texture absent is then a registry change, not a change to every
   draw site.

## 3. The design

### 3.1 Plugin: families, not code-point lists (`vexelray-msdf-maven-plugin`)

```xml
<families>
  <family>
    <name>sans</name>                      <!-- the key the app uses -->
    <sources>
      <source>${project.basedir}/fonts</source>   <!-- files or directories -->
    </sources>
    <match>Noto Sans</match>               <!-- optional: which family, if the sources hold several -->
    <styles>regular bold italic bold-italic</styles>  <!-- optional: default is every face found -->
    <charset>...</charset>                 <!-- optional: default is every code point the font maps -->
  </family>
</families>
```

- **Faces are found by reading the font, not its file name.** A small build-time sfnt reader takes:
  - the typographic family from `name` IDs 16 and 1
  - the weight from `OS/2.usWeightClass`
  - the width from `OS/2.usWidthClass`
  - italic or oblique from `OS/2.fsSelection`
- **Variable fonts contribute one face per named instance.** The reader takes the instances from `fvar`, and each
  one is generated with `msdf-atlas-gen -varfont file?wght=700&wdth=100`. Google Fonts ships families this way now.
- **The style key is CSS-shaped:** stretch, then weight, then slope, leaving out the defaults. For example
  `regular`, `bold`, `italic`, `bold-italic`, `light`, `condensed-semibold`.
  - Two sources that land on the same key fail the build and name both files.
  - A `.ttc` collection fails with a clear message.
- **"Everything" means the font's `cmap`.** The code points come from `cmap` formats 4 and 12, excluding C0/C1
  controls and U+FFFD.
  - U+FFFD is kept for the synthesized missing-glyph box, so a missing glyph looks the same in every face, as today.
  - A `<charset>` narrows the set and gets the coverage report as before.
- **Atlas size is automatic:** `-square4`, the smallest square that fits. Vulkan has no power-of-two requirement.
  - A tight atlas usually has no room for the missing-glyph box. If the box finds no free cell, the image grows by a strip on the side the y-origin does not
    count from, so no existing glyph's coordinates move.
  - Above 4096 the build warns, because that is Vulkan's guaranteed `maxImageDimension2D`.
- **Outputs:** `<outputDir>/<family>/<style>.{png,json,rgba}` for each face, and `<outputDir>/fonts.json` (§2.3).
- **Incremental by stamp, not by mtime alone.** Each face's command line is hashed into a `.stamp` file, so a
  changed charset, size or instance regenerates the face.
- **Nothing existing changes.** The existing `<atlases>` configuration keeps working untouched until the runtime
  moves (step 4).

### 3.2 Engine: several atlases in one frame (`vexelray-canvas`, `vexelray-text`, techniques)

- **`FontSet` in `vexelray-text`** (landed) loads `fonts.json`. Each face holds its `AtlasData` (metrics) and the
  path of its pixels, which `pixels(face)` reads.
  - It is a registry. `face(family, weight, stretch, slope)` always returns a face: the nearest, by CSS font
    matching (stretch, then slope, then weight).
  - An unknown family throws, because family names are build-time facts.
- **Per-code-point fallback** (landed) runs along `chain(face)`:
  - first every face of its own family, ordered by that same matching
  - then each family named in `withFallback(...)`, ordered the same way
  - so bold-italic asks italic, then bold, then regular, before any other family

  It resolves in `GlyphLayout`, so measurement and drawing agree. Each `GlyphQuad` carries the face id it actually
  came from and that atlas's `screenPxRange`.
- **`Canvas.Run` gains a face id beside its image** (landed). Glyphs from a different face start a new run.
  - Shapes and images never split a run on face.
  - A run with no glyphs inherits the face before it, so an image between two lines of mono costs no rebind.
  - Each glyph vertex takes its own quad's `screenPxRange`, scaled for the letterpress passes.
- **Set 0 is bound per run.** `CanvasTechnique.atlases(List)`, `PanelTechnique.atlases(List | AtlasesSource)`,
  and `WindowedPresenter.Run`/`OffscreenDraw` (which gained `descriptorSet0`) all bind set 0 per run, as set 1
  already was, and only on change.
  - A line in one face is one run, and only mixed lines pay extra binds. There is no shader change.
  - A run naming a face past the atlas list throws, rather than drawing the wrong letters.
- **Every `AtlasTexture` is created at startup,** on the main thread, from the manifest, in manifest order.

### 3.3 GUI: style in spans, and spans in measurement (`vexelray-gui`) — landed

- **Fonts are chosen at construction.** Atlases are uploaded in the constructor, so a later setter would be too
  late.
  - `GuiApp(WindowConfig, FontSet)` takes the application's own set.
  - Every other constructor uses `TextFaces.standard()`: vexelray-text's `sans` and `mono`, each falling back to
    the other.
  - `GuiApp.faces()` hands the same faces to anything that sizes text itself.
  - `GuiApp.capture(gui, FontSet, ...)` captures in a set of the application's own.
- **`TextFaces`** (core.text) is the one place a character's face is decided, and it is also the `TextMeasurer`.
  - The layout, the compute phase, the renderer and typeset all resolve through it, so they cannot disagree.
  - `FontAtlases` (core.app) holds one texture per face, all uploaded at startup and shared by every window.
  - `GuiApp.bind` gives each run its face's atlas at set 0.
- **`FONT` is a family**:
  - `Node.font(String)` names it.
  - `Node.font(int)` keeps working as an index into the manifest's families, so 0 is `sans` and 1 is `mono`, which
    is what the indices always meant. No application had to change.
  - `Node.fontWeight(int)`/`bold()` and `Node.fontSlope(Slope)`/`italic()` set the node's own style.
- **`Span` gains `weight` and `slope`**, with factories `Span.bold`, `italic`, `weight` and `slope`. The node's
  values and the spans are carried into measurement as a `Styling`.
- **Measurement goes per character.**
  - `TextMeasurer.caretAdvances(Styling, ...)` and `lineSpans(Styling, ...)` take each code point at its own
    face's advance.
  - `TextGeometry` already derives caret x, line breaks and the alignment indent from that one array, so they
    follow.
  - Line breaking runs on the engine's `TextLayout.breakLineSpans(text, Advances, ...)`. Single-face text breaks
    bit-identically to before.
  - The renderer cuts runs on face as well as colour.
- **`SPANS` reflows only when a styled span is present** in the old or new list (`PropKey.affectsLayout`), so
  recolouring stays a draw.
- **Typeset** takes `TextFaces`, and `FaceKeys` binds a key to a family, weight and slope. That is P5 of
  typeset.md, without an `<extraFont>`.
  - Its glyph metrics resolve through the GUI's own fallback chain.
  - It used to give a missing whitespace character the missing-glyph box's width while the GUI drew it at zero.
    Both are zero now.

## 4. Order of work

| Step | Repo | Lands | Checked by |
| --- | --- | --- | --- |
| 0 | gui | `H_ALIGN`/`V_ALIGN` re-bake geometry | **done**: `DrawOnChangeTest.realigningTextRebakesItsGeometry` |
| 1 | vexelray | plugin: sfnt reader, face discovery, style keys, full-cmap charset, tight sizing, strip growth, stamps, manifest | **done**: `SfntFontTest` (the cmap read equals the committed atlas's code points exactly), `FamilyPlanTest`, `FaceStyleTest`; the local run above |
| 2 | vexelray | `FontSet` + fallback chain in `vexelray-text` | **done**: `FontSetTest`. Every code point in the primary atlas advances identically in the set's sans face, and mono's own glyphs in its mono face; Noto Sans's missing arrows come from mono when mono is the fallback |
| 3 | vexelray | `Canvas.Run` face id, per-run set-0 bind in both techniques | **done**: `CanvasFaceRunTest`, where one-face text is one run and its glyph vertices carry bit-identical `screenPxRange`, so one-face pictures are unchanged. `PanelFaceAtlasTest` (GPU, headless): an arrow sans borrows from mono is byte-identical to mono's arrow, and binding the wrong atlas for face 1 changes the picture |
| 4 | vexelray | `vexelray-text`'s own pom moves to `<families>` (Noto Sans + Noto Sans Mono, every style supplied); `primary.json` retired | **half done**: `sans` and `mono` (Regular only, every glyph: 3,092 + 3,488) are baked beside `primary` into `atlas/fonts.json`, 15.1 MB against `primary`'s 16 MB for 2,044. Left: the other styles' TTFs, and retiring `primary` once step 5 no longer reads it |
| 5 | gui | `GuiApp(config, FontSet)`, family-key `FONT`, styled `Span` and node style, per-character measurement, typeset on the same faces | **done**: `StyledTextTest`, the done criterion, over a hand-built family where bold `x` is 0.7 em against 0.5 em. Bold spans move the caret xs by bold's advance, a centred line's indent by half the extra width, and wrap where regular fits; a recoloured span stays a draw; runs split regular, bold, regular. The demo capture renders through the per-face atlases unchanged, and all four applications compile untouched |
| 6 | gui | demo chapter: every face in the set, mixed in one aligned paragraph | by eye. Waits on step 4's font files: with Regular only, bold resolves to regular and there is nothing to see |

What is left of step 4:
- **Font files.** Add Noto Sans Bold, Italic and Bold Italic, and Noto Sans Mono Bold, to `vexelray-text/fonts/`.
  Only Regular is there today. The `<families>` block picks them up with no pom change.
- **Retiring `primary`.** No GUI code reads it any more. These still do:
  - mainframe's `TerminalView`, which measures its cell grid with `primary.json` face 1. The advances are the same
    as `mono`'s, but it should take `GuiApp.faces()`.
  - the engine's own demos (`CanvasDemo`, `PanelDemo` and friends)
  - `AtlasPixelsTest` and `FontSetTest`. The last uses it as the oracle for "nothing measures differently", so
    retire `primary` after that comparison has served.

## 5. Decisions

- **Build-time, not runtime** (§1), 2026-10-07, by the owner.
- **Load everything at startup**, 2026-10-07, by the owner.
- **Missing-glyph box synthesized in every face,** not the font's own U+FFFD, so a missing glyph looks the same
  everywhere, as today.
- **Variable fonts: every named instance by default,** like static faces. The build prints the cost, and `<styles>`
  narrows it. A wide variable family can be dozens of faces, and the printed total is what keeps that a
  deliberate choice.
- **No kerning or shaping.** Neither exists today, and they are a separate piece of work.
- **Fallback within a family follows the CSS matching order,** so slope comes before weight. A glyph missing from
  bold-italic comes from italic before bold. That is one ordering for both "which face" and "which face next", not
  two rules that could disagree.
